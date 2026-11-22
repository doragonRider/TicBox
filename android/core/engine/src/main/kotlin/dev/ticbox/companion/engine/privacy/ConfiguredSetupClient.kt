package dev.ticbox.companion.engine.privacy

import dev.ticbox.companion.api.*

/** Apply frontend choices once at the workflow boundary, leaving the protocol state machine intact. */
class ConfiguredSetupClient(
    private val delegate: SetupClient,
    private val settings: () -> CloudPrivacySettings,
) : SetupClient by delegate {
    override suspend fun start(options: SetupOptions) = delegate.start(options.copy(
        accountChoice = account(options.accountChoice), consents = consents(options.consents)))
    override fun resume(actionId: Long, response: SetupUserResponse): Boolean = delegate.resume(actionId, response.copy(
        accountChoice = account(response.accountChoice), consents = consents(response.consents)))

    private fun account(choice: SetupAccountChoice) =
        if (settings().allows(CloudCapability.GOOGLE_ACCOUNT_TRANSFER)) choice else SetupAccountChoice.SKIP

    private fun consents(input: List<SetupConsent>): List<SetupConsent> {
        val selected = settings()
        return input.map { consent ->
            val feature = when (consent.type) {
                SetupOptinType.ACCOUNTS -> CloudCapability.GOOGLE_ACCOUNT_TRANSFER
                SetupOptinType.CLOUDSYNC -> CloudCapability.GOOGLE_CLOUD_SYNC
                SetupOptinType.LOGGING -> CloudCapability.VENDOR_DIAGNOSTICS
                SetupOptinType.LOCATION -> CloudCapability.GOOGLE_LOCATION_SERVICES
                else -> null
            }
            if (feature != null && !selected.allows(feature) && consent.choice == SetupOptinChoice.ACCEPTED)
                consent.copy(choice = SetupOptinChoice.DECLINED) else consent
        }
    }
}
