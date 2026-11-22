package dev.ticbox.companion.platform.notification

import dev.ticbox.companion.api.NotificationSyncSettings
import dev.ticbox.companion.api.NotificationFilterMode
import dev.ticbox.companion.api.NotificationAlertMode
import dev.ticbox.companion.api.NotificationAppAlertSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NotificationPreferencesCodecTest {
    @Test fun independentGlobalGatesAndEveryAppModeSurvivePersistence() {
        for (sound in NotificationAlertMode.entries) for (vibration in NotificationAlertMode.entries) {
            val settings = NotificationSyncSettings(soundEnabled = false, vibrationEnabled = true,
                mode = NotificationFilterMode.ALLOWLIST, allowedPackages = setOf("example.app"),
                appAlerts = mapOf("example.app" to NotificationAppAlertSettings(sound, vibration),
                    "retained.app" to NotificationAppAlertSettings(NotificationAlertMode.NEVER, NotificationAlertMode.ALWAYS)))
            assertEquals(settings, NotificationPreferencesCodec.decode(NotificationPreferencesCodec.encode(settings)))
        }
    }
    @Test fun legacyFilesRetainBothListsAndFollowAppAlertsByDefault() {
        val settings = NotificationPreferencesCodec.decode("enabled=true\nmode=ALLOWLIST\nallowed.0=example.app\nblocked.0=other.app\n".toByteArray())
        assertEquals(setOf("example.app"), settings.allowedPackages); assertEquals(setOf("other.app"), settings.blockedPackages)
        assertEquals(NotificationSyncSettings().alertsFor("example.app"), settings.alertsFor("example.app"))
        assertEquals(true, settings.soundEnabled); assertEquals(true, settings.vibrationEnabled)
    }
    @Test fun invalidNewRulesCannotSilentlyResetToAlertingDefaults() {
        for (text in listOf("schema=999", "alerts.sound_enabled=invalid", "alerts.vibration_enabled=1",
            "app-alert.0.package=example.app", "app-alert.bad.sound=NEVER",
            "app-alert.0.package=example.app\napp-alert.0.sound=UNKNOWN\napp-alert.0.vibration=FOLLOW_APP",
            "app-alert.0.package=../bad\napp-alert.0.sound=NEVER\napp-alert.0.vibration=NEVER",
            "app-alert.0.package=example.app\napp-alert.0.sound=NEVER\napp-alert.0.vibration=NEVER\napp-alert.1.package=example.app\napp-alert.1.sound=ALWAYS\napp-alert.1.vibration=ALWAYS")) {
            assertFailsWith<IllegalArgumentException> { NotificationPreferencesCodec.decode(text.toByteArray()) }
        }
    }
    @Test fun bothFilterListsAndModeSurviveRestart() {
        val settings = NotificationSyncSettings(true, setOf("blocked.app"), NotificationFilterMode.ALLOWLIST, setOf("allowed.app"))
        assertEquals(settings, NotificationPreferencesCodec.decode(NotificationPreferencesCodec.encode(settings)))
    }
    @Test
    fun disabledAndPackageSelectionsSurvivePersistenceWithoutTreatingPackageNamesAsSyntax() {
        val settings = NotificationSyncSettings(false, setOf("com.example.a", "odd\\name\nwith=separator", "多语言"))
        assertEquals(settings, NotificationPreferencesCodec.decode(NotificationPreferencesCodec.encode(settings)))
    }

    @Test
    fun blockedPackageEntriesAndEmptyPreferencesUseTheCurrentFormat() {
        val bytes = "enabled=false\nblocked.1=com.example.a\nblocked.3=com.example.b\n".toByteArray()
        assertEquals(NotificationSyncSettings(false, setOf("com.example.a", "com.example.b")), NotificationPreferencesCodec.decode(bytes))
        assertEquals(NotificationSyncSettings(), NotificationPreferencesCodec.decode(byteArrayOf()))
    }

    @Test
    fun oversizedOrBrokenEscapesDoNotBecomeAnEmptySuccessfulPreferenceRestore() {
        assertFailsWith<IllegalArgumentException> { NotificationPreferencesCodec.decode(ByteArray(NotificationPreferencesCodec.MAX_BYTES + 1)) }
        assertFailsWith<IllegalArgumentException> { NotificationPreferencesCodec.decode("blocked.0=broken\\uXX00".toByteArray()) }
    }
}
