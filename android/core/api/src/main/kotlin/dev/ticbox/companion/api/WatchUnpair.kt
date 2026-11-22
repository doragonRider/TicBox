package dev.ticbox.companion.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only a freshly observed, specifically verified receiver may accept skip-wipe unpairing. */
enum class WatchUnpairSupport { UNAVAILABLE, UNKNOWN, SUPPORTED, UNSUPPORTED }
enum class WatchUnpairPhase { DISCONNECTED, IDLE, PROBING, SENDING, WAITING_FOR_ACK, WAITING_FOR_WATCH, CLEANING_UP, FINISHED, CLOSED }

/**
 * ACK_RECEIVED is a message receipt, never confirmation that remote cleanup has finished.
 * LOCAL_ONLY sent nothing to the watch and only cleared this phone's side.
 */
enum class WatchUnpairOutcome { NOT_SENT, ACK_RECEIVED, UNCONFIRMED, LOCAL_ONLY }

data class WatchUnpairResult(
    val outcome: WatchUnpairOutcome,
    val peer: PeerAddress? = null,
    val sessionId: Long = 0,
    /** Stable diagnostic reason; no remote payload or identity credentials. */
    val reason: String,
    /** Exception type only. Null after an attempted cleanup means that callback returned normally. */
    val localCleanupFailure: String? = null,
) {
    val localCleanupAttempted: Boolean get() = outcome != WatchUnpairOutcome.NOT_SENT
}

data class WatchUnpairState(
    val phase: WatchUnpairPhase = WatchUnpairPhase.DISCONNECTED,
    val peer: PeerAddress? = null,
    val sessionId: Long = 0,
    val support: WatchUnpairSupport = WatchUnpairSupport.UNAVAILABLE,
    val homeVersionCode: Long? = null,
    val homeVersionName: String? = null,
    /** The Android API level the watch reported with its version, null if it didn't. */
    val watchSdk: Int? = null,
    val reason: String? = "watch_not_connected",
    val lastResult: WatchUnpairResult? = null,
    /** Remains true across disconnect/replacement and noncancellable cleanup until the command returns. */
    val commandRunning: Boolean = false,
) {
    /** Wear OS 3.5 runs on API 30 and can't move to a new phone while keeping its data. */
    val phoneTransferUnsupported: Boolean get() = watchSdk == 30

    val busy: Boolean get() = commandRunning || phase in setOf(WatchUnpairPhase.PROBING, WatchUnpairPhase.SENDING,
        WatchUnpairPhase.WAITING_FOR_ACK, WatchUnpairPhase.WAITING_FOR_WATCH, WatchUnpairPhase.CLEANING_UP)
}

/** Service-owned controller with caller-cancellable commands; no reset, eSIM erase or health-data deletion. */
interface WatchUnpairClient : AutoCloseable {
    val state: StateFlow<WatchUnpairState>
    /**
     * Refresh from new inbound wire data; a cached HomeInfo or earlier probe cannot authorize a send.
     * The result describes this check's session, even if [state] has moved to a new connection.
     */
    suspend fun probe(): WatchUnpairState
    /**
     * Re-probe before every command. Once writing has been attempted, local cleanup runs even on
     * timeout, link loss or caller cancellation. Cancellation propagates; lastResult retains its outcome.
     * After an ACK, retain the connection briefly for watch execution before local cleanup.
     * WAITING_FOR_WATCH and ACK_RECEIVED do not confirm remote cleanup or transfer completion.
     */
    suspend fun unpairWithoutReset(): WatchUnpairResult
    /**
     * For a watch this phone can't reach, as after it moved to a new phone: stops reconnecting and clears
     * this phone's bond and association for [peer], sending nothing. Refused with NOT_SENT while a link
     * is up, since that watch may still depend on this phone; use [unpairWithoutReset] there.
     */
    suspend fun clearLocalPairing(peer: PeerAddress): WatchUnpairResult =
        WatchUnpairResult(WatchUnpairOutcome.NOT_SENT, peer, reason = "watch_unpair_unavailable")
    override fun close()

    companion object {
        val UNAVAILABLE: WatchUnpairClient = object : WatchUnpairClient {
            override val state = MutableStateFlow(WatchUnpairState(reason = "watch_unpair_unavailable")).asStateFlow()
            override suspend fun probe() = state.value
            override suspend fun unpairWithoutReset() = WatchUnpairResult(WatchUnpairOutcome.NOT_SENT,
                reason = "watch_unpair_unavailable")
            override fun close() = Unit
        }
    }
}
