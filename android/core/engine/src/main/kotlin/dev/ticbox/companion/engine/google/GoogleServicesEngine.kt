package dev.ticbox.companion.engine.google

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.setup.SetupDependencyResult
import dev.ticbox.companion.engine.setup.SetupDependencyState
import dev.ticbox.companion.protocol.GoogleAccountHostOptions
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

class GoogleServicesEngine(
    private val channels: ChannelBackend,
    private val provider: GoogleServiceProvider,
    data: DataSyncClient,
    diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    policy: GoogleServicePolicy = GoogleServicePolicy(),
) : GoogleServices {
    private val account = GoogleAccountTransferEngine(channels, provider, diagnostics, policy)
    private val consents = GoogleConsentEffects(provider, data, diagnostics)
    private val mutableState = MutableStateFlow(GoogleServicesState())
    override val state = mutableState.asStateFlow()
    override val actions get() = provider.actions
    private val closed = AtomicBoolean()
    private val transferLock = Mutex()

    override suspend fun transferAccount(peer: PeerAddress, sessionId: Long, request: GoogleAccountTransferRequest): GoogleServiceResult {
        if (closed.get()) return GoogleServiceResult(GoogleServiceStatus.UNAVAILABLE, "account_transfer", "closed")
        if (!transferLock.tryLock()) return GoogleServiceResult(GoogleServiceStatus.FAILED, "account_transfer", "transfer_already_running")
        try {
            mutableState.update { it.copy(peer = peer, sessionId = sessionId, lastAccountResult = null) }
            val result = account.transfer(peer, sessionId, request) { phase ->
                if (!closed.get()) mutableState.update { it.copy(accountTransfer = phase) }
            }
            if (!closed.get()) mutableState.update { it.copy(lastAccountResult = result, accountTransfer = when (result.status) {
                GoogleServiceStatus.COMPLETE, GoogleServiceStatus.PARTIAL, GoogleServiceStatus.SKIPPED -> GoogleAccountTransferPhase.COMPLETE
                GoogleServiceStatus.CANCELLED -> GoogleAccountTransferPhase.CANCELLED
                else -> GoogleAccountTransferPhase.FAILED
            }) }
            return result
        } catch (problem: CancellationException) {
            if (!closed.get()) mutableState.update { it.copy(accountTransfer = GoogleAccountTransferPhase.CANCELLED,
                lastAccountResult = GoogleServiceResult(GoogleServiceStatus.CANCELLED, "account_transfer", "local_cancelled")) }
            throw problem
        } finally { transferLock.unlock() }
    }

    /** The original host listener has no response/ACK. Caller dispatches this from its active session. */
    suspend fun handleHostActivityRequest(peer: PeerAddress, sessionId: Long, payload: ByteArray?): GoogleServiceResult =
        transferAccount(peer, sessionId, GoogleAccountTransferRequest(reauthenticate = GoogleAccountHostOptions.reauthenticate(payload)))

    override suspend fun applyConsents(peer: PeerAddress, sessionId: Long, consents: List<SetupConsent>): List<GoogleServiceResult> {
        if (closed.get()) return listOf(GoogleServiceResult(GoogleServiceStatus.UNAVAILABLE, "consents", "closed"))
        ensureSession(peer, sessionId)
        val results = this.consents.apply(peer, sessionId, consents)
        if (!closed.get()) mutableState.update { it.copy(consentResults = results) }
        return results
    }
    override suspend fun setCloudSyncSetting(peer: PeerAddress, sessionId: Long, enabled: Boolean): GoogleServiceResult {
        if (closed.get()) return GoogleServiceResult(GoogleServiceStatus.UNAVAILABLE, "cloud_sync_setting", "closed")
        ensureSession(peer, sessionId)
        return try { consents.setCloudSyncSetting(enabled) }
        catch (problem: CancellationException) { throw problem }
        catch (problem: GoogleServiceException) { GoogleServiceResult(problem.status, "cloud_sync_setting", problem.reasonCode, problem.serviceStatusCode) }
        catch (problem: SecurityException) { GoogleServiceResult(GoogleServiceStatus.PERMISSION_DENIED, "cloud_sync_setting", "gms_permission_denied") }
        catch (problem: Exception) { GoogleServiceResult(GoogleServiceStatus.FAILED, "cloud_sync_setting", problem.javaClass.simpleName) }
    }
    private fun ensureSession(peer: PeerAddress, sessionId: Long) {
        val state = channels.state.value
        if (state.phase != ConnectionPhase.LINK_READY || state.peer != peer || state.sessionId != sessionId) {
            throw GoogleServiceException(GoogleServiceStatus.FAILED, "physical_session_ended")
        }
    }
    override fun abortAccountTransfer() = account.abort()
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        account.close(); provider.close()
        mutableState.update { it.copy(accountTransfer = GoogleAccountTransferPhase.CLOSED) }
    }
}

fun GoogleServiceResult.asSetupDependencyResult(): SetupDependencyResult = when (status) {
    // The official client considers any successfully transferred account a successful transfer.
    GoogleServiceStatus.COMPLETE, GoogleServiceStatus.PARTIAL -> SetupDependencyResult.complete()
    GoogleServiceStatus.UNAVAILABLE, GoogleServiceStatus.PERMISSION_DENIED -> SetupDependencyResult.unavailable(reason ?: status.name)
    else -> SetupDependencyResult(SetupDependencyState.RETRYABLE_FAILURE, reason ?: status.name)
}
