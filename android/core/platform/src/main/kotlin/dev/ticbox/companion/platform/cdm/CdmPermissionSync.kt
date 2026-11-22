package dev.ticbox.companion.platform.cdm

import android.companion.CompanionDeviceManager
import android.companion.CompanionException
import android.companion.AssociationInfo
import android.content.Context
import android.content.IntentSender
import android.os.OutcomeReceiver
import android.util.Log
import dev.ticbox.companion.api.DiagnosticSink
import dev.ticbox.companion.api.ProtocolDiagnostic
import dev.ticbox.companion.api.WearChannel
import dev.ticbox.companion.engine.DiagnosticJson
import dev.ticbox.companion.platform.transport.WearChannelStreams
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Seam for a successfully opened GMS Channel, not for the main RFCOMM stream. The Channel engine
 * must provide its independently flow-controlled input/output streams and close implementation.
 * This interface by itself does not establish that the remote end accepted a Channel OPEN.
 */
interface PermissionSyncChannel : AutoCloseable {
    val path: String
    val input: InputStream
    val output: OutputStream
    override fun close()
}

/** Android 14+ system delegation. UKEY2/D2D and permission backup stay owned by the platform. */
class CdmPermissionSync(
    context: Context,
    private val diagnostics: DiagnosticSink = DiagnosticSink {
        Log.w("TicBoxProtocol", DiagnosticJson.line(it, System.currentTimeMillis()))
    },
) : AutoCloseable {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(CompanionDeviceManager::class.java)
        ?: throw UnsupportedOperationException("Companion device services are unavailable")
    private val lock = processLock
    private val active = mutableMapOf<Int, AttachedPermissionTransport>()
    private var closed = false

    /** The Activity host must show this system request and process the actual user result. */
    fun consentRequest(associationId: Int): IntentSender? {
        requireOwnedAssociation(associationId)
        synchronized(lock) { check(!closed) { "CDM adapter is closed" } }
        return manager.buildPermissionTransferUserConsentIntent(associationId)
    }

    /**
     * Takes ownership of [channel], including on failure. A duplicate attach is refused. The
     * caller must have completed actual GMS Channel establishment for PERMISSION_SYNC_PATH.
     */
    fun attach(associationId: Int, channel: PermissionSyncChannel): AttachedPermissionTransport {
        try {
            require(channel.path == PERMISSION_SYNC_PATH) { "Expected the permission-sync GMS Channel" }
            requireOwnedAssociation(associationId)
            return synchronized(lock) {
                check(!closed) { "CDM adapter is closed" }
                check(associationId !in attachmentOwners) { "This association already owns an attached transport" }
                val handle = AttachedPermissionTransport(associationId, channel)
                try {
                    manager.attachSystemDataTransport(associationId, channel.input, channel.output)
                    active[associationId] = handle
                    attachmentOwners[associationId] = handle
                    handle
                } catch (failure: Throwable) {
                    // A partially completed platform attach must not retain our stream ownership.
                    runCatching { manager.detachSystemDataTransport(associationId) }
                    throw failure
                }
            }
        } catch (failure: Throwable) {
            runCatching { channel.close() }
            throw failure
        }
    }

    /**
     * Takes ownership of the association's logical Channel. Known Bluetooth address mismatches
     * are rejected. Missing platform address metadata is logged; app ownership and consent still
     * apply, and the pairing coordinator remains responsible for address-less associations.
     */
    fun attach(associationId: Int, channel: WearChannel): AttachedPermissionTransport {
        val streams = WearChannelStreams(channel)
        try {
            val association = requireOwnedAssociation(associationId)
            val address = association.deviceMacAddress?.toString()
            val binding = try {
                requireAssociationMatchesPeer(address, channel.info.peer)
            } catch (mismatch: SecurityException) {
                associationDiagnostic(channel, associationId, address, "association_peer_mismatch", "reject_known_wrong_device")
                throw mismatch
            }
            if (binding == AssociationPeerBinding.UNAVAILABLE) {
                associationDiagnostic(channel, associationId, address, "association_peer_unavailable", "continue_with_owned_association_and_system_consent")
            }
            return attach(associationId, streams)
        } catch (failure: Throwable) {
            runCatching { streams.close() }
            throw failure
        }
    }

    private fun associationDiagnostic(channel: WearChannel, associationId: Int, address: String?, event: String, decision: String) {
        runCatching { diagnostics.record(ProtocolDiagnostic(
            component = "cdm", event = event, peer = channel.info.peer,
            sessionId = channel.info.sessionId, channelId = channel.info.id,
            localIsOpener = channel.info.localIsOpener,
            details = mapOf("associationId" to associationId.toString(), "associationAddress" to (address ?: "unavailable"), "decision" to decision),
        )) }
    }

    override fun close() {
        val handles = synchronized(lock) {
            if (closed) return
            closed = true
            active.values.toList()
        }
        for (handle in handles) runCatching { handle.close() }
    }

    private fun requireOwnedAssociation(associationId: Int): AssociationInfo {
        require(associationId > 0) { "Invalid association ID" }
        return manager.myAssociations.firstOrNull { it.id == associationId }
            ?: throw SecurityException("Association is not owned by this application")
    }

    inner class AttachedPermissionTransport internal constructor(
        val associationId: Int,
        private val channel: PermissionSyncChannel,
    ) : AutoCloseable {
        private val released = AtomicBoolean(false)
        private val transfer = Mutex()
        private val pending = AtomicReference<CancellableContinuation<Unit>?>(null)

        /**
         * Callback completion confirms the platform request outcome, not every permission grant.
         * Timeout is a local caller budget. Canceling this operation closes its owned Channel.
         */
        suspend fun transferPermissions(timeoutMillis: Long = 30_000) {
            require(timeoutMillis > 0)
            withTimeout(timeoutMillis) {
                transfer.withLock {
                    check(!released.get()) { "Permission transport is closed" }
                    requireOwnedAssociation(associationId)
                    suspendCancellableCoroutine<Unit> { continuation ->
                        val completed = AtomicBoolean(false)
                        pending.set(continuation)
                        continuation.invokeOnCancellation {
                            pending.compareAndSet(continuation, null)
                            completed.set(true)
                            runCatching { close() }
                        }
                        if (released.get()) continuation.cancel(CancellationException("Permission transport is closed"))
                        if (!continuation.isActive) return@suspendCancellableCoroutine
                        try {
                            synchronized(lock) {
                                // Serialize submission against detach/re-attach. An old canceled
                                // operation must never start a transfer on a replacement Channel.
                                if (released.get() || attachmentOwners[associationId] !== this@AttachedPermissionTransport ||
                                    !continuation.isActive
                                ) {
                                    continuation.cancel(CancellationException("Permission transport is closed"))
                                    return@synchronized
                                }
                                manager.startSystemDataTransfer(
                                associationId,
                                Executor { command -> command.run() },
                                object : OutcomeReceiver<Void, CompanionException> {
                                    override fun onResult(result: Void?) {
                                        if (completed.compareAndSet(false, true)) {
                                            pending.compareAndSet(continuation, null)
                                            continuation.resume(Unit) { _, _, _ -> runCatching { close() } }
                                        }
                                    }

                                    override fun onError(error: CompanionException) {
                                        if (completed.compareAndSet(false, true)) {
                                            pending.compareAndSet(continuation, null)
                                            continuation.resumeWithException(error)
                                        }
                                    }
                                },
                                )
                            }
                        } catch (failure: Throwable) {
                            if (completed.compareAndSet(false, true)) {
                                pending.compareAndSet(continuation, null)
                                continuation.resumeWithException(failure)
                            }
                        }
                    }
                }
            }
        }

        override fun close() {
            if (!released.compareAndSet(false, true)) return
            pending.getAndSet(null)?.cancel(CancellationException("Permission transport is closed"))
            synchronized(lock) {
                // Holding the lock across detach prevents an old handle detaching a newer attach.
                if (active[associationId] === this) active.remove(associationId)
                if (attachmentOwners[associationId] === this) {
                    attachmentOwners.remove(associationId)
                    try {
                        manager.detachSystemDataTransport(associationId)
                    } finally {
                        channel.close()
                    }
                } else {
                    channel.close()
                }
            }
        }
    }

    companion object {
        const val PERMISSION_SYNC_PATH = "/cdm/channel/permission_sync"
        private val processLock = Any()
        private val attachmentOwners = mutableMapOf<Int, AttachedPermissionTransport>()
    }
}
