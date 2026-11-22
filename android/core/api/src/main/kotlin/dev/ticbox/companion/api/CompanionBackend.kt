package dev.ticbox.companion.api

import java.util.Locale
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** A selected Bluetooth peer, independent of Android BluetoothDevice and its lifecycle. */
class PeerAddress(value: String) {
    val value: String = value.uppercase(Locale.ROOT)

    init {
        require(MAC.matches(this.value)) { "Expected a Bluetooth MAC address" }
    }

    override fun equals(other: Any?): Boolean = other is PeerAddress && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value

    private companion object {
        val MAC = Regex("[0-9A-F]{2}(?::[0-9A-F]{2}){5}")
    }
}

/** Stable local wire identity. A reconnect must reuse this identity, not manufacture a new one. */
data class NodeIdentity(
    val nodeId: String,
    val networkId: String,
    val nodeName: String,
    val androidId: Long? = null,
) {
    init {
        require(nodeId.isNotBlank() && nodeId.length <= 256) { "Invalid local node ID" }
        require(networkId.isNotBlank() && networkId.length <= 256) { "Invalid local network ID" }
        require(nodeName.isNotBlank() && nodeName.length <= 256) { "Invalid local node name" }
    }
}

fun interface IdentityStore {
    /** Atomically create once, then reuse. A read/corruption error must not silently replace identity. */
    suspend fun loadOrCreate(): NodeIdentity
}

/** Platform-facing stream seam. None of these bytes is a UI model. */
interface DuplexTransport : AutoCloseable {
    /** Return a nonempty chunk, or null at EOF. There is no read-to-packet boundary assumption. */
    suspend fun read(): ByteArray?

    /** Write the complete supplied chunk, or throw. The caller serializes record batches. */
    suspend fun write(bytes: ByteArray)

    /** Synchronous, idempotent, and must unblock a pending read/write without waiting for it. */
    override fun close()
}

fun interface TransportFactory {
    /**
     * Cancellable acquisition. Until delivery, the implementation owns and closes the transport
     * on failure or cancellation, including cancellation racing a successful connection.
     */
    suspend fun open(peer: PeerAddress): DuplexTransport
}

/** Wire dispatch selector. This configuration is not proof of platform privileges or authentication. */
data class ApplicationRoute(val packageName: String, val packageDigest: String) {
    init {
        require(packageName.isNotBlank() && packageName.length <= 256) { "Invalid routing package" }
        require(packageDigest.isNotBlank() && packageDigest.length <= 512) { "Invalid routing digest" }
    }
}

enum class ConnectionPhase {
    IDLE,
    CONNECTING,
    NEGOTIATING,
    /** CONNECT is negotiated; any accountless transfer has been confirmed on the current stream.
     * First activation and business success remain separate observations. */
    LINK_READY,
    RETRYING,
    FAILED,
}

enum class FailureKind {
    TRANSPORT, TIMEOUT, PROTOCOL, IDENTITY, PERMISSION_REQUIRED, UNEXPECTED,
    /**
     * The watch explicitly ended this peer association. The corresponding FAILED state and
     * ConnectionInterrupted(willRetry = false) identify the exact peer and physical session.
     * Owners must stop automatic restoration for that association; this does not request bond,
     * companion-association, local-identity or business-data removal.
     */
    ASSOCIATION_TERMINATED,
}

data class ConnectionFailure(val kind: FailureKind, val message: String)

enum class MessageDeliveryFailureKind { QUEUE_FULL, HANDLER_FAILED, TIMED_OUT }
data class MessageDeliveryFailure(val handler: String, val path: String, val requestId: Int,
    val kind: MessageDeliveryFailureKind)

data class ConnectionState(
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val peer: PeerAddress? = null,
    /**
     * Identifies one transport/CONNECT attempt within this backend instance, including automatic
     * reconnects. Zero means no current attempt is allocated. Retained observations/events from any older
     * ID stay stale even if the same peer becomes LINK_READY again; this is not a wire credential.
     */
    val sessionId: Long = 0,
    val peerName: String? = null,
    val negotiatedWireVersion: Int? = null,
    val retryAttempt: Int = 0,
    val retryDelayMillis: Long? = null,
    val failure: ConnectionFailure? = null,
    /** Raw MessageApi observations dropped by the local bounded event stream in this attempt. */
    val droppedMessageEvents: Long = 0,
    /** Failed business deliveries in this physical attempt, independent of raw observation drops. */
    val failedBusinessMessages: Long = 0,
    val lastMessageDeliveryFailure: MessageDeliveryFailure? = null,
)

/**
 * Low-level MessageApi envelope. The body stays opaque until a domain codec validates it; this is
 * not a UI business model. Returned payload arrays are defensive copies.
 */
class InboundMessage(
    val requestId: Int,
    val path: String,
    payload: ByteArray,
    val sourceNodeId: String?,
    val expectsResponse: Boolean = false,
    /** Wire dispatch identity, independent of this APK's signing/Android privileges. */
    val route: ApplicationRoute? = null,
) {
    private val body = payload.copyOf()
    val payload: ByteArray get() = body.copyOf()
    val payloadSize: Int get() = body.size
}

sealed interface BackendEvent {
    val peer: PeerAddress
    val sessionId: Long

    data class MessageReceived(
        override val peer: PeerAddress,
        override val sessionId: Long,
        val message: InboundMessage,
    ) : BackendEvent

    data class ConnectionInterrupted(
        override val peer: PeerAddress,
        override val sessionId: Long,
        val failure: ConnectionFailure,
        val willRetry: Boolean,
    ) : BackendEvent
}

enum class TransmissionStage { WRITTEN_TO_TRANSPORT }

/** A local write receipt. It does not confirm remote receipt, dispatch, or business completion. */
data class TransmissionReceipt(
    val requestId: Int,
    val wireBytes: Int,
    val stage: TransmissionStage = TransmissionStage.WRITTEN_TO_TRANSPORT,
)

class NotConnectedException : IllegalStateException("The peer link is not ready")
class BackendClosedException : IllegalStateException("The backend has been closed")

/**
 * Service-owned link/session boundary for domain services, not the final UI API. events is a live,
 * bounded observation stream with no replay or
 * history guarantee; retaining health data requires a domain persistence layer. Every event carries
 * a sessionId so callers can discard observations retained from an older session.
 */
interface CompanionBackend : AutoCloseable {
    val state: StateFlow<ConnectionState>
    val events: SharedFlow<BackendEvent>

    /** Start or replace the selected peer session; return without waiting for negotiation. */
    suspend fun connect(peer: PeerAddress)
    /** Explicit stop cancels reconnection and leaves the backend reusable. */
    suspend fun disconnect()
    suspend fun sendMessage(path: String, payload: ByteArray): TransmissionReceipt
    /** Permanently stop this instance and immediately release its current transport. */
    override fun close()
}
