package dev.ticbox.companion.engine.notification

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import kotlin.test.*

class NotificationAlertRulesTest {
    @Test fun allAppModesAndGlobalGatesRespectTheOriginalFourAlertCombinations() {
        fun enabled(mode: NotificationAlertMode, original: Boolean) = when (mode) {
            NotificationAlertMode.FOLLOW_APP -> original
            NotificationAlertMode.NEVER -> false
            NotificationAlertMode.ALWAYS -> true
        }
        for (sound in NotificationAlertMode.entries) for (vibration in NotificationAlertMode.entries)
            for (soundGate in listOf(false, true)) for (vibrationGate in listOf(false, true))
                for (originalSound in listOf(false, true)) for (originalVibration in listOf(false, true)) {
                    val fields = mapOf("title_raw" to DataMapValue.Text("card stays visible"),
                        "has_sound" to DataMapValue.Bool(originalSound),
                        "defaults" to DataMapValue.IntNumber(4 or (if (originalSound) 1 else 0) or (if (originalVibration) 2 else 0))) +
                        if (originalVibration) mapOf("vibrate" to DataMapValue.Longs(listOf(0, 150))) else emptyMap()
                    val settings = NotificationSyncSettings(soundEnabled = soundGate, vibrationEnabled = vibrationGate,
                        appAlerts = mapOf("example.app" to NotificationAppAlertSettings(sound, vibration)))
                    val result = NotificationAlertRules.apply(NotificationContent(fields), "example.app", settings)
                    val wire = NotificationCodec.decode(NotificationCodec.encode(result).data).fields
                    val expectedSound = soundGate && enabled(sound, originalSound)
                    val expectedVibration = vibrationGate && enabled(vibration, originalVibration)
                    assertEquals(DataMapValue.Bool(expectedSound), wire["has_sound"])
                    val flags = (wire["defaults"] as DataMapValue.IntNumber).value
                    assertEquals(expectedSound, flags and 1 != 0)
                    assertEquals(expectedVibration, flags and 2 != 0)
                    assertEquals(4, flags and 4)
                    if (!expectedVibration) assertNull(wire["vibrate"])
                    assertEquals(fields["title_raw"], wire["title_raw"])
                }
    }
    @Test fun followAppRetainsExactIntentIncludingExplicitZeroWaveforms() {
        val source = NotificationContent(mapOf("has_sound" to DataMapValue.Bool(false), "defaults" to DataMapValue.IntNumber(6),
            "vibrate" to DataMapValue.Longs(listOf(0, 0))))
        assertSame(source, NotificationAlertRules.apply(source, "example.app", NotificationSyncSettings()))
    }
    @Test fun alwaysVibrateUsesTheDefaultWhenNoUsableNonzeroPatternExists() {
        val settings = NotificationSyncSettings(appAlerts = mapOf("example.app" to NotificationAppAlertSettings(vibration = NotificationAlertMode.ALWAYS)))
        for (pattern in listOf(emptyList(), listOf(0L), listOf(0L, 0L), listOf(100L, 0L), listOf(0L, 0L, 100L, 0L), listOf(-1L, 100L), listOf(0L, Long.MAX_VALUE),
            List(129) { 1L }, listOf(20_000L, 20_000L))) {
            val result = NotificationAlertRules.apply(NotificationContent(mapOf("vibrate" to DataMapValue.Longs(pattern))), "example.app", settings)
            assertNull(result.fields["vibrate"])
            assertEquals(DataMapValue.IntNumber(2), result.fields["defaults"])
        }
        val source = NotificationContent(mapOf("vibrate" to DataMapValue.Longs(listOf(0, 200, 20, 300))))
        assertEquals(source.fields["vibrate"], NotificationAlertRules.apply(source, "example.app", settings).fields["vibrate"])
    }
    @Test fun packageOverridesDoNotChangeOtherAppsOrImageAndActionData() {
        val image = NotificationAsset(byteArrayOf(1, 2, 3))
        val action = NotificationContent(mapOf("title" to DataMapValue.Text("reply")))
        val source = NotificationContent(mapOf("defaults" to DataMapValue.IntNumber(3), "has_sound" to DataMapValue.Bool(true)),
            assets = mapOf("icon" to image), lists = mapOf("actions" to listOf(action)))
        val settings = NotificationSyncSettings(appAlerts = mapOf("muted.app" to NotificationAppAlertSettings(NotificationAlertMode.NEVER, NotificationAlertMode.NEVER)))
        assertSame(source, NotificationAlertRules.apply(source, "other.app", settings))
        val muted = NotificationAlertRules.apply(source, "muted.app", settings)
        assertEquals(DataMapValue.IntNumber(0), muted.fields["defaults"])
        assertSame(source.assets, muted.assets); assertSame(source.lists, muted.lists)
    }
}
