package dev.ticbox.companion.engine.setup

import dev.ticbox.companion.api.*

/** Bridge to the session's correlated MessageApi request/response implementation. */
interface SetupRpcTransport {
    suspend fun request(peer: PeerAddress, sessionId: Long, path: String, payload: ByteArray, timeoutMillis: Long): ByteArray
    /** Handler receives only requests already authenticated/routed by the owning session. */
    fun registerRequestHandler(path: String, handler: suspend (SetupRpcRequest) -> ByteArray): AutoCloseable
}

class SetupRpcRequest(val peer: PeerAddress, val sessionId: Long, payload: ByteArray) {
    private val body = payload.copyOf()
    val payload: ByteArray get() = body.copyOf()
}

data class SetupContext(val peer: PeerAddress, val sessionId: Long, val systemInfo: SetupSystemInfo)
enum class SetupDependencyState { COMPLETE, UNAVAILABLE, RETRYABLE_FAILURE }
data class SetupDependencyResult(val state: SetupDependencyState, val reason: String) {
    companion object {
        fun complete() = SetupDependencyResult(SetupDependencyState.COMPLETE, "completed")
        fun unavailable(reason: String) = SetupDependencyResult(SetupDependencyState.UNAVAILABLE, reason)
    }
}

/** Platform/account/data integration. Unavailable hooks are explicit; required hooks pause. */
interface SetupEnvironment {
    suspend fun cellularInfo(peer: PeerAddress, sessionId: Long): SetupCellularInfo? = null
    suspend fun transferGoogleAccount(context: SetupContext): SetupDependencyResult = SetupDependencyResult.unavailable("account_transfer_unavailable")
    suspend fun prepareInitialData(context: SetupContext, consents: List<SetupConsent>): SetupDependencyResult = SetupDependencyResult.unavailable("initial_data_preparation_unavailable")
    /** Must observe the actual tracker completion; a drained local queue is insufficient. */
    suspend fun awaitDataSyncCompletion(context: SetupContext): SetupDependencyResult = SetupDependencyResult.unavailable("data_sync_completion_unavailable")
    suspend fun runOptionalStep(context: SetupContext, step: SetupOptionalStep): SetupDependencyResult = SetupDependencyResult.unavailable("optional_step_unavailable")

    companion object { val NONE: SetupEnvironment = object : SetupEnvironment {} }
}

/** Timeouts and retries are explicit local budgets, except the recovered six 10s COMPLETE waits. */
data class SetupConfig(
    val queryTimeoutMillis: Long = 15_000,
    val readyTimeoutMillis: Long = 120_000,
    val checkinTimeoutMillis: Long = 120_000,
    val checkinPollMillis: Long = 5_000,
    val completionWaitMillis: Long = 10_000,
    val completionAttempts: Int = 6,
    val oobeTimeoutMillis: Long = 120_000,
    val dependencyTimeoutMillis: Long = 120_000,
    val readChunkBytes: Int = 64_000,
) {
    init {
        require(queryTimeoutMillis > 0 && readyTimeoutMillis > 0 && checkinTimeoutMillis > 0)
        require(checkinPollMillis > 0 && completionWaitMillis > 0 && completionAttempts in 1..32)
        require(oobeTimeoutMillis > 0 && dependencyTimeoutMillis > 0 && readChunkBytes in 1..65_536)
    }
}
