package dev.ticbox.companion.platform.notification

import android.content.Context
import dev.ticbox.companion.platform.settings.AtomicPreferencesFile
import dev.ticbox.companion.api.NotificationSyncSettings
import dev.ticbox.companion.api.NotificationFilterMode
import dev.ticbox.companion.api.NotificationAlertMode
import dev.ticbox.companion.api.NotificationAppAlertSettings
import dev.ticbox.companion.engine.notification.NotificationSettingsStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties

/** Private local preferences. Notification contents and PendingIntent IDs are never stored here. */
class AtomicFileNotificationSettingsStore(context: Context) : NotificationSettingsStore {
    private val file = AtomicPreferencesFile(File(context.noBackupFilesDir, "notification-sync.settings"), NotificationPreferencesCodec.MAX_BYTES)
    override suspend fun load(): NotificationSyncSettings = file.read()?.let(NotificationPreferencesCodec::decode) ?: NotificationSyncSettings()
    override suspend fun save(settings: NotificationSyncSettings) = file.write(NotificationPreferencesCodec.encode(settings))
}

internal object NotificationPreferencesCodec {
    const val MAX_BYTES = 1024 * 1024
    fun encode(settings: NotificationSyncSettings): ByteArray = ByteArrayOutputStream().use { output ->
        require(settings.appAlerts.size <= NotificationAppAlertSettings.MAX_PACKAGES)
        Properties().apply {
            setProperty("schema", "2")
            setProperty("enabled", settings.enabled.toString())
            setProperty("mode", settings.mode.name)
            setProperty("alerts.sound_enabled", settings.soundEnabled.toString())
            setProperty("alerts.vibration_enabled", settings.vibrationEnabled.toString())
            settings.blockedPackages.sorted().forEachIndexed { index, value -> setProperty("blocked.$index", value) }
            settings.allowedPackages.sorted().forEachIndexed { index, value -> setProperty("allowed.$index", value) }
            settings.appAlerts.toSortedMap().entries.forEachIndexed { index, (packageName, alerts) ->
                require(NotificationAppAlertSettings.isValidPackageName(packageName))
                setProperty("app-alert.$index.package", packageName)
                setProperty("app-alert.$index.sound", alerts.sound.name)
                setProperty("app-alert.$index.vibration", alerts.vibration.name)
            }
        }.store(output, null)
        output.toByteArray().also { require(it.size <= MAX_BYTES) { "Notification settings size budget exceeded" } }
    }
    fun decode(bytes: ByteArray): NotificationSyncSettings {
        require(bytes.size <= MAX_BYTES) { "Notification settings size budget exceeded" }
        val values = Properties().apply { load(ByteArrayInputStream(bytes)) }
        require(values.getProperty("schema") in setOf(null, "1", "2")) { "unsupported_notification_settings_schema" }
        fun gate(key: String): Boolean = values.getProperty(key)?.let { requireNotNull(it.toBooleanStrictOrNull()) } ?: true
        val rowPattern = Regex("app-alert\\.(0|[1-9][0-9]{0,3})\\.(package|sound|vibration)")
        val indices = values.stringPropertyNames().filter { it.startsWith("app-alert.") }.map {
            requireNotNull(rowPattern.matchEntire(it)) { "invalid_notification_alert_setting" }.groupValues[1]
        }.toSet()
        require(indices.size <= NotificationAppAlertSettings.MAX_PACKAGES)
        val alerts = linkedMapOf<String, NotificationAppAlertSettings>()
        for (index in indices) {
            val name = requireNotNull(values.getProperty("app-alert.$index.package"))
            require(NotificationAppAlertSettings.isValidPackageName(name) && name !in alerts)
            alerts[name] = NotificationAppAlertSettings(
                NotificationAlertMode.valueOf(requireNotNull(values.getProperty("app-alert.$index.sound"))),
                NotificationAlertMode.valueOf(requireNotNull(values.getProperty("app-alert.$index.vibration"))))
        }
        return NotificationSyncSettings(values.getProperty("enabled")?.toBooleanStrictOrNull() ?: true,
            values.stringPropertyNames().filter { it.startsWith("blocked.") }.mapTo(mutableSetOf()) { values.getProperty(it) },
            values.getProperty("mode")?.let(NotificationFilterMode::valueOf) ?: NotificationFilterMode.BLOCKLIST,
            values.stringPropertyNames().filter { it.startsWith("allowed.") }.mapTo(mutableSetOf()) { values.getProperty(it) },
            soundEnabled = gate("alerts.sound_enabled"), vibrationEnabled = gate("alerts.vibration_enabled"), appAlerts = alerts.toMap())
    }
}
