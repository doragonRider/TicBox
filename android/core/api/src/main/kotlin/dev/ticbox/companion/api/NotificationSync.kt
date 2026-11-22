package dev.ticbox.companion.api

import kotlinx.coroutines.flow.StateFlow

/** Android notification access remains a separate, system-managed permission. */
enum class NotificationFilterMode { BLOCKLIST, ALLOWLIST }
/**
 * Overrides the phone notification's alert request; watch system restrictions still apply.
 * FOLLOW_APP retains the observed channel/ranking intent. NEVER clears that dimension;
 * ALWAYS requests the watch's default alert even if the phone app did not request it.
 */
enum class NotificationAlertMode { FOLLOW_APP, NEVER, ALWAYS }
data class NotificationAppAlertSettings(
    val sound: NotificationAlertMode = NotificationAlertMode.FOLLOW_APP,
    val vibration: NotificationAlertMode = NotificationAlertMode.FOLLOW_APP,
) {
    companion object {
        const val MAX_PACKAGES = 4096
        private val packagePattern = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
        fun isValidPackageName(value: String): Boolean = value.length in 1..255 && packagePattern.matches(value)
    }
}
data class NotificationSyncSettings(val enabled: Boolean = true, val blockedPackages: Set<String> = emptySet(),
    val mode: NotificationFilterMode = NotificationFilterMode.BLOCKLIST, val allowedPackages: Set<String> = emptySet(),
    /** These gates affect alerts only; they do not disable notification cards. */
    val soundEnabled: Boolean = true, val vibrationEnabled: Boolean = true,
    /** Sparse overrides; disabling sync for a package preserves its choices for later use. */
    val appAlerts: Map<String, NotificationAppAlertSettings> = emptyMap()) {
    fun allows(packageName: String): Boolean = enabled && packageAllowed(packageName)
    fun packageAllowed(packageName: String): Boolean = when (mode) {
        NotificationFilterMode.BLOCKLIST -> packageName !in blockedPackages
        NotificationFilterMode.ALLOWLIST -> packageName in allowedPackages
    }
    val selectedPackages get() = if (mode == NotificationFilterMode.BLOCKLIST) blockedPackages else allowedPackages
    fun alertsFor(packageName: String): NotificationAppAlertSettings = appAlerts[packageName] ?: NotificationAppAlertSettings()
}

enum class NotificationSyncPhase { DISABLED, ACCESS_REQUIRED, LISTENER_DISCONNECTED, DISCONNECTED, RECONCILING, ACTIVE, FAILED, CLOSED }
enum class NotificationSyncIssue { SOURCE_UNAVAILABLE, SNAPSHOT_FAILED, STORAGE, PUBLISH_FAILED, ACTION_FAILED, RESULT_SEND_FAILED, MALFORMED_RPC, ASSET_UNAVAILABLE }

/** Metadata only. Notification bodies, reply text and opaque action IDs stay inside the backend. */
data class NotificationSyncState(
    val settings: NotificationSyncSettings = NotificationSyncSettings(),
    val phase: NotificationSyncPhase = NotificationSyncPhase.ACCESS_REQUIRED,
    val accessGranted: Boolean = false,
    val listenerConnected: Boolean = false,
    val sessionId: Long = 0,
    val activeNotificationCount: Int = 0,
    val mirroredNotificationCount: Int = 0,
    val filteredNotificationCount: Int = 0,
    /** Old DataItems whose withdrawal failed; successful current mirrors are counted separately. */
    val pendingRemovalCount: Int = 0,
    val actionsExecuted: Long = 0,
    val actionsFailed: Long = 0,
    val duplicateActions: Long = 0,
    val lastIssue: NotificationSyncIssue? = null,
)

interface NotificationSyncClient : AutoCloseable {
    val state: StateFlow<NotificationSyncState>
    suspend fun setEnabled(enabled: Boolean)
    suspend fun setPackageEnabled(packageName: String, enabled: Boolean)
    /** Atomically persist a mode and its list; the other mode's list remains available. */
    suspend fun setPackageFilter(mode: NotificationFilterMode, packages: Set<String>)
    /** Global allow gates, independent of card selection. Off takes precedence over ALWAYS. */
    suspend fun setSoundEnabled(enabled: Boolean)
    suspend fun setVibrationEnabled(enabled: Boolean)
    /** Individual setters preserve the other dimension, even with concurrent UI calls. */
    suspend fun setPackageSoundMode(packageName: String, mode: NotificationAlertMode)
    suspend fun setPackageVibrationMode(packageName: String, mode: NotificationAlertMode)
    /**
     * Atomic pair update; FOLLOW_APP/FOLLOW_APP removes the stored override. Does not select the app.
     * Alert-setting writes are persisted before state changes; failure throws and retains prior settings.
     * Published old cards are refreshed quietly; a new notification uses the latest saved policy.
     * Package names must be valid (1..255 characters); at most MAX_PACKAGES nondefault rules are saved.
     */
    suspend fun setPackageAlertSettings(packageName: String, settings: NotificationAppAlertSettings)
    /** Request a fresh active-notification snapshot; completion is observed through state. */
    suspend fun resync()
    override fun close()
}
