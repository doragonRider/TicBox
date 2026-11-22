package dev.ticbox.companion.api

import kotlinx.coroutines.flow.StateFlow

/** Settings switches controlled by explicit frontend confirmation. All default off. */
enum class CloudCapability {
    MOBVOI_CLOUD,
    GOOGLE_ACCOUNT_TRANSFER,
    GOOGLE_CLOUD_SYNC,
    GOOGLE_LOCATION_SERVICES,
    VENDOR_DIAGNOSTICS,
    /** An unrestricted Internet relay can reach vendor clouds, including through encrypted tunnels. */
    WATCH_INTERNET_PROXY,
}

/** Immutable settings snapshot. Unknown or absent persisted choices are disabled. */
data class CloudPrivacySettings(
    val enabledCapabilities: Set<CloudCapability> = emptySet(),
) {
    fun allows(capability: CloudCapability): Boolean = capability in enabledCapabilities
}

class CloudFeatureDisabledException(val capability: CloudCapability) :
    IllegalStateException("cloud_access_disabled_${capability.name.lowercase()}")

/** UI calls this only after the user explicitly changes a switch in Settings. */
interface CloudSettingsClient {
    val state: StateFlow<CloudPrivacySettings>
    suspend fun setEnabled(capability: CloudCapability, enabled: Boolean): CloudPrivacySettings
}
