package dev.ticbox.companion.engine.privacy

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.GoogleAccountHostOptions

/** The runtime's optional Google integration. Frontend confirmation changes settings first. */
class ConfiguredGoogleServices(
    private val delegate: GoogleServices,
    private val settings: () -> CloudPrivacySettings,
) : GoogleServices by delegate {
    suspend fun handleHostActivityRequest(peer: PeerAddress, sessionId: Long, payload: ByteArray?): GoogleServiceResult =
        transferAccount(peer, sessionId, GoogleAccountTransferRequest(reauthenticate = GoogleAccountHostOptions.reauthenticate(payload)))
    override suspend fun transferAccount(peer: PeerAddress, sessionId: Long, request: GoogleAccountTransferRequest): GoogleServiceResult =
        if (settings().allows(CloudCapability.GOOGLE_ACCOUNT_TRANSFER)) delegate.transferAccount(peer, sessionId, request)
        else GoogleServiceResult(GoogleServiceStatus.SKIPPED, "account_transfer", "disabled_in_settings")

    override suspend fun applyConsents(peer: PeerAddress, sessionId: Long, consents: List<SetupConsent>): List<GoogleServiceResult> {
        val selected = settings()
        val local = consents.mapNotNull { consent -> when {
            consent.type == SetupOptinType.CLOUDSYNC && !selected.allows(CloudCapability.GOOGLE_CLOUD_SYNC) -> null
            consent.type == SetupOptinType.LOGGING && !selected.allows(CloudCapability.VENDOR_DIAGNOSTICS) -> consent.copy(choice = SetupOptinChoice.DECLINED)
            else -> consent
        } }
        return if (local.isEmpty()) emptyList() else delegate.applyConsents(peer, sessionId, local)
    }

    override suspend fun setCloudSyncSetting(peer: PeerAddress, sessionId: Long, enabled: Boolean): GoogleServiceResult =
        if (settings().allows(CloudCapability.GOOGLE_CLOUD_SYNC)) delegate.setCloudSyncSetting(peer, sessionId, enabled)
        else GoogleServiceResult(GoogleServiceStatus.SKIPPED, "cloud_sync_setting", "disabled_in_settings")
}
