package dev.ticbox.companion.engine.notification

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*

/** Rewrites only alert fields. Android DEFAULT_SOUND=1, DEFAULT_VIBRATE=2; other bits survive. */
internal object NotificationAlertRules {
    fun apply(content: NotificationContent, packageName: String, settings: NotificationSyncSettings): NotificationContent {
        val app = settings.alertsFor(packageName)
        val sound = if (settings.soundEnabled) app.sound else NotificationAlertMode.NEVER
        val vibration = if (settings.vibrationEnabled) app.vibration else NotificationAlertMode.NEVER
        if (sound == NotificationAlertMode.FOLLOW_APP && vibration == NotificationAlertMode.FOLLOW_APP) return content
        val fields = content.fields.toMutableMap()
        var defaults = (fields["defaults"] as? DataMapValue.IntNumber)?.value ?: 0
        when (sound) {
            NotificationAlertMode.FOLLOW_APP -> Unit
            NotificationAlertMode.NEVER -> { defaults = defaults and 1.inv(); fields["has_sound"] = DataMapValue.Bool(false) }
            NotificationAlertMode.ALWAYS -> { defaults = defaults or 1; fields["has_sound"] = DataMapValue.Bool(true) }
        }
        when (vibration) {
            NotificationAlertMode.FOLLOW_APP -> Unit
            NotificationAlertMode.NEVER -> { defaults = defaults and 2.inv(); fields.remove("vibrate") }
            NotificationAlertMode.ALWAYS -> {
                defaults = defaults or 2
                // An explicit all-zero or invalid pattern would defeat ALWAYS. Missing pattern
                // plus DEFAULT_VIBRATE requests the watch's normal vibration, without inventing one.
                val pattern = (fields["vibrate"] as? DataMapValue.Longs)?.value
                if (pattern == null || pattern.size !in 2..128 || pattern.any { it < 0 || it > 30_000 } ||
                    pattern.sum() !in 1..30_000 || pattern.indices.none { it % 2 == 1 && pattern[it] > 0 }) fields.remove("vibrate")
            }
        }
        fields["defaults"] = DataMapValue.IntNumber(defaults)
        return if (fields == content.fields) content else content.copy(fields = fields.toMap())
    }
}
