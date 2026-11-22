package dev.ticbox.companion.api

import kotlinx.coroutines.flow.StateFlow

/** Null follows the phone's setting. Overrides are local preferences, independent of cloud accounts. */
data class CompanionDevicePreferences(val languageTags: String? = null, val is24HourFormat: Boolean? = null)
data class WatchDeviceInfo(val model: String? = null, val sdkVersion: Int? = null,
    val fields: Map<String, String> = emptyMap())
data class WatchInstalledApp(val packageName: String, val label: String?, val launchable: Boolean?,
    val notificationPermissionState: Int?, val iconDigest: String? = null)
enum class WatchLockType(val wireValue: Int) { NONE(1), PIN(2), GESTURE(3) }
enum class WatchLockPhase { IDLE, CONFIGURATION_SCREEN, VERIFICATION_SCREEN, WRONG_CREDENTIAL, UNLOCKED, CONFIGURED, REMOVED, CANCELLED, UNKNOWN }
data class WatchLockState(val supportedTypes: List<Int> = emptyList(), val phase: WatchLockPhase = WatchLockPhase.IDLE,
    val lastWireCode: Int? = null)
enum class DeviceCommandStatus { PERSISTED_AND_SUBMITTED, SUBMITTED_UNVERIFIED, REMOTE_UI_OPENED, REMOTE_CONFIRMED, REJECTED, UNAVAILABLE, TIMED_OUT, FAILED }
/** Category of the latest device-settings problem. [DeviceSettingsIssue.detail] is diagnostic only. */
enum class DeviceSettingsIssueKind { PREFERENCES_RESTORE, REPLY_DECODE, COMMAND }
data class DeviceSettingsIssue(val kind: DeviceSettingsIssueKind, val detail: String)
data class DeviceCommandResult(val status: DeviceCommandStatus, val wireCode: Int? = null, val reason: String? = null)
enum class WatchSettingsScreen(val action: String) {
    MAIN("android.settings.SETTINGS"), DISPLAY("android.settings.DISPLAY_SETTINGS"), SOUND("android.settings.SOUND_SETTINGS"),
    WIFI("android.settings.WIFI_SETTINGS"), BLUETOOTH("android.settings.BLUETOOTH_SETTINGS"),
    LANGUAGE("android.settings.LOCALE_SETTINGS"), DATE_TIME("android.settings.DATE_SETTINGS"),
    APPS("android.settings.APPLICATION_SETTINGS"), ABOUT("android.settings.DEVICE_INFO_SETTINGS"),
    BATTERY("android.settings.BATTERY_SAVER_SETTINGS"),
    /** Opens the watch's own keep-data transfer confirmation; does not clear the phone's bond. */
    TRANSFER("android.settings.WATCH_TRANSFER"),
}
data class DeviceSettingsState(
    val sessionId: Long = 0,
    val preferences: CompanionDevicePreferences = CompanionDevicePreferences(),
    val info: WatchDeviceInfo? = null,
    val batteryPercent: Int? = null,
    val apps: List<WatchInstalledApp> = emptyList(),
    val appDirectoryLimited: Boolean = false,
    val lock: WatchLockState = WatchLockState(),
    val lastIssue: DeviceSettingsIssue? = null,
    /** Local preferences have finished restoring; commands await this automatically. */
    val preferencesReady: Boolean = false,
)
interface DeviceSettingsClient : AutoCloseable {
    val state: StateFlow<DeviceSettingsState>
    suspend fun refresh(): DeviceCommandResult
    suspend fun setLanguageTags(languageTags: String?): DeviceCommandResult
    suspend fun set24HourFormat(enabled: Boolean?): DeviceCommandResult
    suspend fun openSettings(screen: WatchSettingsScreen): DeviceCommandResult
    /** Opens Android's uninstall confirmation on the watch. Directory removal confirms an uninstall. */
    suspend fun requestUninstall(packageName: String): DeviceCommandResult
    suspend fun queryLockSupport(): DeviceCommandResult
    /** Opens the watch's system UI; credentials are entered only on the watch. */
    suspend fun configureLock(type: WatchLockType): DeviceCommandResult
    suspend fun cancelLockConfiguration(): DeviceCommandResult
    /** Actual monitoring preference, with the same root/readback requirements as services.monitoring. */
    @Deprecated("Use services.monitoring.set(HEART_HEALTH, MonitoringValue.Toggle(enabled))")
    suspend fun setHeartHealthMonitoring(enabled: Boolean): DeviceCommandResult
    /** OEM feature-entry visibility. Hiding also disables monitoring; showing does not enable it. No application ACK. */
    suspend fun setHeartHealthFeatureVisible(visible: Boolean): DeviceCommandResult =
        DeviceCommandResult(DeviceCommandStatus.UNAVAILABLE, reason = "heart_health_visibility_unavailable")
    suspend fun appIcon(packageName: String): ByteArray?
    override fun close()
}
