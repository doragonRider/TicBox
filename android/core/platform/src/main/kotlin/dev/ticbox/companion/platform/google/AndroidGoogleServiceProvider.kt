package dev.ticbox.companion.platform.google

import android.app.PendingIntent
import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.google.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select

/** Allows the host to present the real Google PendingIntent from its foreground UI. */
fun interface GoogleResolutionLauncher {
    fun launch(intent: PendingIntent): Boolean
}

/** Recovered SDK Binder contracts. Always identifies this APK's real package to GMS. */
class AndroidGoogleServiceProvider(
    context: Context,
    private val scope: CoroutineScope,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val resolutionLauncher: GoogleResolutionLauncher? = null,
    private val operationTimeoutMillis: Long = 30_000,
    val hostActions: AndroidGoogleActions = AndroidGoogleActions(diagnostics),
) : GoogleServiceProvider {
    private val broker = GoogleServiceBroker(context, scope, diagnostics, operationTimeoutMillis)
    private val closed = AtomicBoolean()
    private val activeAccount = AtomicReference<AccountPipe?>()
    private val connections = ConcurrentHashMap.newKeySet<GoogleServiceBroker.Connection>()
    override val actions get() = hostActions.state

    override suspend fun startAccountTransfer(request: GoogleAccountTransferRequest, awaitingUser: () -> Unit): GoogleAccountPipe {
        checkOpen()
        val connection = broker.connect(210, ACCOUNT_ACTION, ACCOUNT_DESCRIPTOR)
        connections += connection
        if (closed.get()) { connections -= connection; connection.close(); checkOpen() }
        var toGoogle: Array<ParcelFileDescriptor>? = null
        var fromGoogle: Array<ParcelFileDescriptor>? = null
        var pipe: AccountPipe? = null
        try {
            val toPipe = ParcelFileDescriptor.createPipe().also { toGoogle = it }
            val fromPipe = ParcelFileDescriptor.createPipe().also { fromGoogle = it }
            val accountPipe = AccountPipe(connection, toPipe, fromPipe, awaitingUser).also { pipe = it }
            if (!activeAccount.compareAndSet(null, accountPipe)) throw GoogleServiceException(GoogleServiceStatus.FAILED, "google_transfer_already_running")
            if (closed.get()) throw GoogleServiceException(GoogleServiceStatus.UNAVAILABLE, "provider_closed")
            val remote = connection.ready.await()
            withContext(Dispatchers.IO) {
                GoogleServiceBroker.transact(remote, ACCOUNT_DESCRIPTOR, 1, oneWay = true) { parcel ->
                    accountPipe.startSubmitted.set(true)
                    parcel.writeStrongBinder(accountPipe.startCallback)
                    parcel.writeInt(1)
                    writeConfigurations(parcel, request)
                    parcel.writeTypedArray(arrayOf(toPipe[0], fromPipe[1]), 0)
                    parcel.writeStrongBinder(accountPipe.listener)
                }
            }
            // The Binder driver has duplicated the GMS ends, including for oneway delivery.
            toPipe[0].close(); fromPipe[1].close()
            currentCoroutineContext().ensureActive()
            return accountPipe
        } catch (problem: Throwable) {
            if (pipe != null) pipe.close() else {
                toGoogle?.forEach { runCatching { it.close() } }
                fromGoogle?.forEach { runCatching { it.close() } }
                connections -= connection; connection.close()
            }
            throw problem
        }
    }

    override suspend fun optInCloudSync(enabled: Boolean): GoogleServiceResult = cloudCall("cloud_sync_consent", 48, enabled)
    override suspend fun setCloudSyncSetting(enabled: Boolean): GoogleServiceResult = cloudCall("cloud_sync_setting", 50, enabled)

    private suspend fun cloudCall(operation: String, transaction: Int, enabled: Boolean): GoogleServiceResult {
        checkOpen()
        val connection = broker.connect(14, "com.google.android.gms.wearable.BIND", WEAR_DESCRIPTOR)
        connections += connection
        try {
            checkOpen()
            val completed = CompletableDeferred<Int?>()
            val callback = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code == INTERFACE_TRANSACTION) { reply?.writeString(WEAR_CALLBACK); return true }
                    if (code != 11) return super.onTransact(code, data, reply, flags)
                    data.enforceInterface(WEAR_CALLBACK)
                    try {
                        val status = GoogleSafeParcel.status(data)
                        data.enforceNoDataAvail()
                        completed.complete(status)
                        reply?.writeNoException()
                    } catch (problem: Exception) { completed.completeExceptionally(problem); reply?.writeException(problem) }
                    return true
                }
            }
            val status = withTimeout(operationTimeoutMillis) {
                val binder = connection.ready.await()
                withContext(Dispatchers.IO) {
                    GoogleServiceBroker.transact(binder, WEAR_DESCRIPTOR, transaction) { parcel ->
                        parcel.writeStrongBinder(callback); parcel.writeInt(if (enabled) 1 else 0)
                    }
                }
                select {
                    completed.onAwait { it }
                    connection.lost.onAwait { throw GoogleServiceException(GoogleServiceStatus.UNAVAILABLE, "gms_service_lost") }
                }
            }
            log("cloud_callback", mapOf("operation" to operation, "enabled" to enabled.toString(), "status" to status.toString()))
            return if (status != null && status <= 0) GoogleServiceResult(GoogleServiceStatus.COMPLETE, operation, serviceStatusCode = status)
            else GoogleServiceResult(status?.let(GoogleServiceBroker::statusToResult) ?: GoogleServiceStatus.FAILED, operation, "gms_callback_failed", status)
        } catch (problem: TimeoutCancellationException) {
            throw GoogleServiceException(GoogleServiceStatus.TIMED_OUT, "gms_operation_timeout", cause = problem)
        } catch (problem: SecurityException) {
            throw GoogleServiceException(GoogleServiceStatus.PERMISSION_DENIED, "gms_wearable_permission_denied", cause = problem)
        } finally { connections -= connection; connection.close() }
    }

    private inner class AccountPipe(
        private val connection: GoogleServiceBroker.Connection,
        private val toGoogle: Array<ParcelFileDescriptor>,
        private val fromGoogle: Array<ParcelFileDescriptor>,
        private val awaitingUser: () -> Unit,
    ) : GoogleAccountPipe {
        override val completion = CompletableDeferred<GoogleServiceResult>()
        private val input = ParcelFileDescriptor.AutoCloseInputStream(fromGoogle[0])
        private val output = ParcelFileDescriptor.AutoCloseOutputStream(toGoogle[1])
        private val released = AtomicBoolean()
        val startSubmitted = AtomicBoolean()
        private val loss = scope.launch {
            connection.lost.await()
            completion.complete(GoogleServiceResult(GoogleServiceStatus.UNAVAILABLE, "account_transfer", "gms_service_lost"))
        }
        val startCallback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == INTERFACE_TRANSACTION) { reply?.writeString(ACCOUNT_CALLBACK); return true }
                if (code != 1) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(ACCOUNT_CALLBACK)
                try {
                    val status = GoogleSafeParcel.status(data)
                    data.enforceNoDataAvail()
                    log("account_start_callback", mapOf("status" to status.toString()))
                    if (status == null || status > 0) completion.complete(GoogleServiceResult(
                        status?.let(GoogleServiceBroker::statusToResult) ?: GoogleServiceStatus.FAILED,
                        "account_transfer", "gms_start_rejected", status))
                } catch (problem: Exception) { completion.completeExceptionally(problem); reply?.writeException(problem) }
                return true
            }
        }
        val listener = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == INTERFACE_TRANSACTION) { reply?.writeString(ACCOUNT_LISTENER); return true }
                if (code !in 1..3) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(ACCOUNT_LISTENER)
                try {
                    when (code) {
                        1 -> {
                            val count = data.readInt()
                            require(count in -1..1024) { "account_result_count_budget" }
                            var succeeded = 0; var failed = 0
                            repeat(maxOf(0, count)) {
                                if (data.readInt() == 0) { failed++; return@repeat }
                                var result: Int? = null
                                GoogleSafeParcel.fields(data) { id, width -> if (id == 3 && width == 4) result = data.readInt() }
                                if (result == 1) succeeded++ else failed++
                            }
                            data.enforceNoDataAvail()
                            completion.complete(GoogleAccountResults.completed(succeeded, failed))
                            log("account_completed", mapOf("successfulAccounts" to succeeded.toString(), "failedAccounts" to failed.toString()))
                        }
                        2 -> {
                            val intent = if (data.readInt() == 0) null else PendingIntent.CREATOR.createFromParcel(data)
                            data.enforceNoDataAvail()
                            if (!released.get() && !completion.isCompleted) {
                                awaitingUser()
                                if (intent == null) completion.complete(GoogleServiceResult(GoogleServiceStatus.FAILED, "account_transfer", "google_user_action_missing"))
                                else if (resolutionLauncher != null) {
                                    if (!resolutionLauncher.launch(intent)) completion.complete(GoogleServiceResult(GoogleServiceStatus.UNAVAILABLE, "account_transfer", "google_user_action_not_launched"))
                                } else hostActions.request(this@AccountPipe, intent, { !released.get() && !completion.isCompleted }) { reason ->
                                    completion.complete(GoogleServiceResult(GoogleServiceStatus.UNAVAILABLE, "account_transfer", reason))
                                }
                            }
                            log("account_user_action", mapOf("intentPresent" to (intent != null).toString()))
                        }
                        3 -> {
                            val status = data.readInt()
                            data.readString() // Error text can contain identifiers: never retain it.
                            data.enforceNoDataAvail()
                            completion.complete(GoogleAccountResults.error(status))
                            log("account_error_callback", mapOf("status" to status.toString()))
                        }
                    }
                    data.enforceNoDataAvail()
                    reply?.writeNoException()
                } catch (problem: Exception) { completion.completeExceptionally(problem); reply?.writeException(problem) }
                return true
            }
        }
        override suspend fun read(): ByteArray? = withContext(Dispatchers.IO) {
            val bytes = ByteArray(64_000)
            val read = input.read(bytes)
            if (read < 0) null else bytes.copyOf(read)
        }
        override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) { output.write(bytes); output.flush() }
        override fun close() {
            if (!released.compareAndSet(false, true)) return
            val abort = startSubmitted.get() && !completion.isCompleted
            completion.complete(GoogleServiceResult(GoogleServiceStatus.CANCELLED, "account_transfer", "local_cancelled"))
            loss.cancel()
            hostActions.cancel(this)
            runCatching { input.close() }; runCatching { output.close() }
            toGoogle.forEach { runCatching { it.close() } }; fromGoogle.forEach { runCatching { it.close() } }
            if (abort) {
                // Oneway abort has no result claim. Close still releases local resources promptly.
                if (connection.ready.isCompleted && !connection.ready.isCancelled) scope.launch(Dispatchers.IO) {
                    runCatching {
                        GoogleServiceBroker.transact(connection.ready.await(), ACCOUNT_DESCRIPTOR, 2, oneWay = true) { it.writeStrongBinder(startCallback) }
                    }.onFailure { log("account_abort_failed", mapOf("exception" to it.javaClass.simpleName)) }
                }.invokeOnCompletion { connections -= connection; connection.close() }
                else { connections -= connection; connection.close() }
            } else { connections -= connection; connection.close() }
            activeAccount.compareAndSet(this, null)
        }
    }

    private fun checkOpen() { if (closed.get()) throw GoogleServiceException(GoogleServiceStatus.UNAVAILABLE, "provider_closed") }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeAccount.getAndSet(null)?.close()
        hostActions.close()
        connections.toList().forEach { it.close() }; connections.clear()
    }
    private fun log(event: String, details: Map<String, String>) {
        runCatching { diagnostics.record(ProtocolDiagnostic("google_services", event, details = details)) }
    }

    companion object {
        const val ACCOUNT_ACTION = "com.google.android.gms.smartdevice.directtransfer.SourceDirectTransferService.START"
        const val ACCOUNT_DESCRIPTOR = "com.google.android.gms.smartdevice.directtransfer.internal.ISourceDirectTransferService"
        const val ACCOUNT_CALLBACK = "com.google.android.gms.smartdevice.directtransfer.internal.IDirectTransferCallback"
        const val ACCOUNT_LISTENER = "com.google.android.gms.smartdevice.d2d.internal.IDirectTransferListener"
        const val WEAR_DESCRIPTOR = "com.google.android.gms.wearable.internal.IWearableService"
        const val WEAR_CALLBACK = "com.google.android.gms.wearable.internal.IWearableCallbacks"

        private fun writeConfigurations(parcel: Parcel, request: GoogleAccountTransferRequest) {
            GoogleSafeParcel.objectValue(parcel) {
                GoogleSafeParcel.string(parcel, 1, "Copy your Google account to your watch")
                GoogleSafeParcel.string(parcel, 3, "Set up Google on your watch")
                GoogleSafeParcel.string(parcel, 4, "deviceIconWatch")
                GoogleSafeParcel.string(parcel, 5, "themeFollowSystem")
                GoogleSafeParcel.bool(parcel, 6, request.account == null)
                if (request.account == null) GoogleSafeParcel.variable(parcel, 7) {
                    GoogleSafeParcel.objectValue(parcel) {
                        GoogleSafeParcel.string(parcel, 2, "Choose a Google account")
                        GoogleSafeParcel.string(parcel, 3, "Choose the account to copy to your watch")
                        GoogleSafeParcel.bool(parcel, 4, !request.reauthenticate)
                        GoogleSafeParcel.bool(parcel, 5, false)
                        GoogleSafeParcel.bool(parcel, 6, false)
                        GoogleSafeParcel.bool(parcel, 7, false)
                        GoogleSafeParcel.bool(parcel, 8, false)
                    }
                }
                GoogleSafeParcel.string(parcel, 8, "Copying account")
                GoogleSafeParcel.string(parcel, 9, "Keep your watch connected")
                request.account?.let { account -> GoogleSafeParcel.variable(parcel, 11) {
                    parcel.writeInt(1)
                    // SafeParcelWriter.writeTypedList uses the serialized element's byte size.
                    val sizeAt = parcel.dataPosition(); parcel.writeInt(0); val start = parcel.dataPosition()
                    GoogleSafeParcel.objectValue(parcel) { GoogleSafeParcel.string(parcel, 2, account.name); GoogleSafeParcel.string(parcel, 3, account.type) }
                    val end = parcel.dataPosition(); parcel.setDataPosition(sizeAt); parcel.writeInt(end - start); parcel.setDataPosition(end)
                } }
                GoogleSafeParcel.int(parcel, 12, 0) // DEFAULT: stock confirmation and verification.
            }
        }
    }
}
