package dev.ticbox.companion.platform.google

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.google.GoogleServiceException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*

internal class GoogleServiceBroker(
    context: Context,
    private val scope: CoroutineScope,
    private val diagnostics: DiagnosticSink,
    private val timeoutMillis: Long = 30_000,
) {
    private val context = context.applicationContext

    suspend fun connect(serviceId: Int, action: String, descriptor: String): Connection {
        val connection = Connection(serviceId, action, descriptor)
        try {
            withTimeout(timeoutMillis) {
                connection.bind()
                connection.ready.await()
            }
            return connection
        } catch (problem: TimeoutCancellationException) {
            connection.close()
            throw GoogleServiceException(GoogleServiceStatus.TIMED_OUT, "gms_broker_timeout", cause = problem)
        } catch (problem: Throwable) { connection.close(); throw problem }
    }

    internal inner class Connection(
        val serviceId: Int,
        private val action: String,
        private val descriptor: String,
    ) : ServiceConnection, AutoCloseable {
        val ready = CompletableDeferred<IBinder>()
        val lost = CompletableDeferred<Unit>()
        private val closed = AtomicBoolean()
        private val bound = AtomicBoolean()
        private val remote = AtomicReference<IBinder?>()
        private val death = IBinder.DeathRecipient { fail("gms_binder_died") }
        private val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == INTERFACE_TRANSACTION) { reply?.writeString(CALLBACK_DESCRIPTOR); return true }
                if (code !in 1..3) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(CALLBACK_DESCRIPTOR)
                try {
                    val status = data.readInt()
                    if (code == 2) {
                        if (data.readInt() != 0) Bundle.CREATOR.createFromParcel(data)
                        data.enforceNoDataAvail()
                        log("gms_account_validation_callback", mapOf("service" to serviceId.toString(), "status" to status.toString()))
                    } else {
                        val binder = data.readStrongBinder()
                        if (data.readInt() != 0) {
                            if (code == 1) Bundle.CREATOR.createFromParcel(data) else GoogleSafeParcel.skipObject(data)
                        }
                        data.enforceNoDataAvail()
                        if (status != 0 || binder == null) {
                            ready.completeExceptionally(GoogleServiceException(statusToResult(status), "gms_broker_rejected", status))
                        } else if (!closed.get()) {
                            if (binder.interfaceDescriptor != descriptor) throw GoogleServiceException(GoogleServiceStatus.UNAVAILABLE, "gms_descriptor_mismatch")
                            remote.set(binder)
                            binder.linkToDeath(death, 0)
                            if (closed.get()) {
                                remote.compareAndSet(binder, null)
                                runCatching { binder.unlinkToDeath(death, 0) }
                            } else ready.complete(binder)
                        }
                    }
                    data.enforceNoDataAvail()
                    reply?.writeNoException()
                } catch (problem: Exception) {
                    ready.completeExceptionally(problem)
                    fail("gms_broker_callback_invalid")
                    // The caller sees an actual callback failure; no successful fake result is sent.
                    reply?.writeException(problem)
                }
                return true
            }
        }

        fun bind() {
            if (closed.get()) throw GoogleServiceException(GoogleServiceStatus.CANCELLED, "closed")
            val accepted = try { context.bindService(Intent(action).setPackage(GMS_PACKAGE), this, Context.BIND_AUTO_CREATE) }
            catch (problem: SecurityException) { throw GoogleServiceException(GoogleServiceStatus.PERMISSION_DENIED, "gms_bind_permission_denied", cause = problem) }
            if (!accepted) throw GoogleServiceException(GoogleServiceStatus.UNAVAILABLE, "gms_service_not_resolved")
            bound.set(true)
            if (closed.get()) unbind()
        }

        override fun onServiceConnected(name: ComponentName, broker: IBinder) {
            if (closed.get()) return
            scope.launch(Dispatchers.IO) {
                try {
                    transact(broker, BROKER_DESCRIPTOR, 46) { parcel ->
                        parcel.writeStrongBinder(callback)
                        parcel.writeInt(1)
                        GoogleSafeParcel.objectValue(parcel) {
                            GoogleSafeParcel.int(parcel, 1, 6)
                            GoogleSafeParcel.int(parcel, 2, serviceId)
                            // SDK basement 18.3.0 sends its client version, not a fabricated OEM ID.
                            GoogleSafeParcel.int(parcel, 3, 12451000)
                            GoogleSafeParcel.string(parcel, 4, context.packageName)
                            GoogleSafeParcel.variable(parcel, 6) { parcel.writeInt(0) }
                            GoogleSafeParcel.variable(parcel, 7) { parcel.writeBundle(Bundle()) }
                            GoogleSafeParcel.variable(parcel, 10) { parcel.writeInt(0) }
                            GoogleSafeParcel.variable(parcel, 11) { parcel.writeInt(0) }
                            GoogleSafeParcel.bool(parcel, 12, true)
                            GoogleSafeParcel.int(parcel, 13, 0)
                            GoogleSafeParcel.bool(parcel, 14, false)
                        }
                    }
                } catch (problem: Exception) {
                    ready.completeExceptionally(if (problem is SecurityException) GoogleServiceException(GoogleServiceStatus.PERMISSION_DENIED, "gms_get_service_permission_denied", cause = problem) else problem)
                    log("gms_get_service_failed", mapOf("service" to serviceId.toString(), "caller" to context.packageName, "exception" to problem.javaClass.simpleName))
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) = fail("gms_service_disconnected")
        override fun onBindingDied(name: ComponentName) = fail("gms_binding_died")
        override fun onNullBinding(name: ComponentName) = fail("gms_null_binding")
        private fun fail(reason: String) {
            ready.completeExceptionally(GoogleServiceException(GoogleServiceStatus.UNAVAILABLE, reason))
            lost.complete(Unit)
        }
        private fun unbind() { if (bound.compareAndSet(true, false)) runCatching { context.unbindService(this) } }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            remote.getAndSet(null)?.let { runCatching { it.unlinkToDeath(death, 0) } }
            fail("gms_connection_closed")
            unbind()
        }
    }

    private fun log(event: String, details: Map<String, String>) {
        runCatching { diagnostics.record(ProtocolDiagnostic("google_binder", event, details = details)) }
    }
    companion object {
        const val GMS_PACKAGE = "com.google.android.gms"
        const val CALLBACK_DESCRIPTOR = "com.google.android.gms.common.internal.IGmsCallbacks"
        const val BROKER_DESCRIPTOR = "com.google.android.gms.common.internal.IGmsServiceBroker"
        fun statusToResult(code: Int) = when (code) {
            1, 2, 3, 4, 6, 9, 16, 17 -> GoogleServiceStatus.UNAVAILABLE
            14 -> GoogleServiceStatus.CANCELLED
            15 -> GoogleServiceStatus.TIMED_OUT
            else -> GoogleServiceStatus.FAILED
        }
        fun transact(binder: IBinder, descriptor: String, code: Int, oneWay: Boolean = false, body: (Parcel) -> Unit) {
            val request = Parcel.obtain()
            val response = if (oneWay) null else Parcel.obtain()
            try {
                request.writeInterfaceToken(descriptor)
                body(request)
                if (!binder.transact(code, request, response, if (oneWay) IBinder.FLAG_ONEWAY else 0)) {
                    throw GoogleServiceException(GoogleServiceStatus.UNAVAILABLE, "gms_transaction_unsupported", code)
                }
                response?.readException()
            } finally { request.recycle(); response?.recycle() }
        }
    }
}
