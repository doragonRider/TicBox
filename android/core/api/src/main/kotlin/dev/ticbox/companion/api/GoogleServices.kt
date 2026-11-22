package dev.ticbox.companion.api

import kotlinx.coroutines.flow.StateFlow

/** Optional explicit account selection. Names are never included in diagnostics or state. */
class GoogleAccountSelection(val name: String, val type: String = "com.google")

data class GoogleAccountTransferRequest(
    val account: GoogleAccountSelection? = null,
    val reauthenticate: Boolean = false,
)

enum class GoogleServiceStatus { COMPLETE, PARTIAL, SKIPPED, UNAVAILABLE, PERMISSION_DENIED, CANCELLED, TIMED_OUT, FAILED }

/** Metadata only; a service status is kept without copying account names, tokens or error text. */
data class GoogleServiceResult(
    val status: GoogleServiceStatus,
    val operation: String,
    val reason: String? = null,
    val serviceStatusCode: Int? = null,
    val transferredAccounts: Int = 0,
    val failedAccounts: Int = 0,
)

enum class GoogleAccountTransferPhase { IDLE, OPENING_CHANNEL, WAITING_FOR_WATCH, CONNECTING_GOOGLE, TRANSFERRING, AWAITING_USER, COMPLETE, FAILED, CANCELLED, CLOSED }

enum class GoogleUserActionKind { ACCOUNT_TRANSFER_AUTHORIZATION }
/** Platform-owned Google PendingIntent token. No Activity, account name, or credential is exposed. */
data class GoogleUserAction(val id: String, val kind: GoogleUserActionKind, val reason: String)

data class GoogleServicesState(
    val accountTransfer: GoogleAccountTransferPhase = GoogleAccountTransferPhase.IDLE,
    val peer: PeerAddress? = null,
    val sessionId: Long = 0,
    val lastAccountResult: GoogleServiceResult? = null,
    val consentResults: List<GoogleServiceResult> = emptyList(),
)

interface GoogleServices : AutoCloseable {
    val state: StateFlow<GoogleServicesState>
    val actions: StateFlow<List<GoogleUserAction>>
    suspend fun transferAccount(peer: PeerAddress, sessionId: Long, request: GoogleAccountTransferRequest = GoogleAccountTransferRequest()): GoogleServiceResult
    suspend fun applyConsents(peer: PeerAddress, sessionId: Long, consents: List<SetupConsent>): List<GoogleServiceResult>
    suspend fun setCloudSyncSetting(peer: PeerAddress, sessionId: Long, enabled: Boolean): GoogleServiceResult
    fun abortAccountTransfer()
    override fun close()
}
