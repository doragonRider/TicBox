package dev.ticbox.companion.engine.google

import dev.ticbox.companion.api.*
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val noGoogleActions: StateFlow<List<GoogleUserAction>> = MutableStateFlow(emptyList())

/** The GMS direct-transfer pipe is opaque. No account credentials cross this interface as models. */
interface GoogleAccountPipe : AutoCloseable {
    val completion: Deferred<GoogleServiceResult>
    suspend fun read(): ByteArray?
    suspend fun write(bytes: ByteArray)
    override fun close()
}

interface GoogleServiceProvider : AutoCloseable {
    val actions: StateFlow<List<GoogleUserAction>> get() = noGoogleActions
    suspend fun startAccountTransfer(request: GoogleAccountTransferRequest, awaitingUser: () -> Unit): GoogleAccountPipe
    suspend fun optInCloudSync(enabled: Boolean): GoogleServiceResult
    suspend fun setCloudSyncSetting(enabled: Boolean): GoogleServiceResult
    override fun close()
}

class GoogleServiceException(
    val status: GoogleServiceStatus,
    val reasonCode: String,
    val serviceStatusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(reasonCode, cause)

/** Local resource budgets; the watch does not negotiate these values. */
data class GoogleServicePolicy(
    val channelOpenMillis: Long = 30_000,
    val watchStartedMillis: Long = 60_000,
    val transferMillis: Long = 600_000,
    val pendingPipeChunks: Int = 16,
    val readChunkBytes: Int = 64_000,
) {
    init {
        require(channelOpenMillis > 0 && watchStartedMillis > 0 && transferMillis > 0)
        require(pendingPipeChunks in 1..128 && readChunkBytes in 1..65_536)
    }
}
