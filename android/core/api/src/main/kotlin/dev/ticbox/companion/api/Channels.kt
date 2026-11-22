package dev.ticbox.companion.api

import java.io.IOException
import kotlinx.coroutines.flow.StateFlow

/** Identity of a logical stream inside one physical CONNECT session, not a Binder token. */
data class ChannelInfo(
    val peer: PeerAddress,
    val sessionId: Long,
    val id: ULong,
    val localIsOpener: Boolean,
    val path: String,
)

enum class ChannelPhase { OPENING, OPEN, CLOSING, CLOSED }
enum class ChannelEndReason { LOCAL_CLOSE, REMOTE_CLOSE, LINK_LOST, TIMEOUT, PROTOCOL, RESOURCE_LIMIT, CANCELLED }

data class ChannelState(
    val phase: ChannelPhase,
    val inputEnded: Boolean = false,
    val outputEnded: Boolean = false,
    val endReason: ChannelEndReason? = null,
    /** Application error from CLOSE; independent from the local end reason. */
    val applicationErrorCode: Int? = null,
)

class ChannelClosedException(
    val reason: ChannelEndReason,
    val applicationErrorCode: Int? = null,
    message: String = "Channel closed: $reason",
) : IOException(message)

/**
 * Service/workflow-owned byte stream. MessagePiece, Channel DATA and application frame boundaries
 * are independent. Every handle belongs to one physical session and is never resumed on reconnect.
 */
interface WearChannel : AutoCloseable {
    val info: ChannelInfo
    val state: StateFlow<ChannelState>

    /** Nonempty bytes or null at input EOF. Concurrent readers are serialized. */
    suspend fun read(maxBytes: Int = 64_000): ByteArray?

    /** Copy and send all bytes in order, waiting for the remote per-block ACK. Empty writes are no-ops. */
    suspend fun write(bytes: ByteArray)

    /** Send final DATA and wait for its ACK. The incoming direction remains usable. */
    suspend fun finishOutput()

    /** End local input immediately; pending DATA is acknowledged with final=true. */
    fun closeInput()

    /** Reject new writes, drain the already-running write, send CLOSE, then release the handle.
     * Queued writes that have not started are rejected; canceling a secondary await does not
     * cancel the owner that is already draining. */
    suspend fun closeGracefully(errorCode: Int = 0)

    /** Immediate, idempotent local abort; a CLOSE is sent on a best-effort basis. */
    override fun close()
}

/** Logical streams are a backend workflow interface; the frontend still uses WatchClient. */
interface ChannelBackend : CompanionBackend {
    /** Completes only after a matching OPEN_ACK. */
    suspend fun openChannel(path: String, allowOverMetered: Boolean = true): WearChannel

    /** Consume the next incoming OPEN for this physical session; fails when the link ends. */
    suspend fun acceptChannel(): WearChannel
}
