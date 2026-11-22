package dev.ticbox.companion.protocol

import kotlin.test.*

class HealthMonitoringProtocolTest {
    @Test fun knownOemSamplingChoicesAreBoundedAndNeverArbitraryIntervals() {
        val oxygen = HealthMonitoringProtocol.spec("SLEEP_BLOOD_OXYGEN_INTERVAL")
        assertEquals(setOf("3600", "1800", "60"), oxygen.choices.keys)
        assertFalse(oxygen.accepts("1"))
        assertFalse(oxygen.accepts("0"))
        assertEquals(setOf("0", "1"), HealthMonitoringProtocol.spec("BACKGROUND_HEART_RATE_MODE").choices.keys)
        assertEquals(1800L, HealthMonitoringProtocol.spec("PRESSURE").fixedIntervalSeconds)
        assertFalse(HealthMonitoringProtocol.spec("PRESSURE").accepts("60"))
    }
    @Test fun enablingRestAlertAlsoEnablesRealtimeHeartRateAndMotionAsTheOemDoes() {
        assertEquals(linkedMapOf("background_hrm" to "true", "background_hrm_mode" to "1",
            "rest_hrm_warning" to "true", "auto_motion" to "true"),
            HealthMonitoringProtocol.changes("REST_HEART_RATE_ALERT", "true", emptyMap()))
    }
    @Test fun changingSleepDoesNotDisableMotionForAnotherEnabledSource() {
        val values = mapOf("auto_detect_fitness" to "true", "rest_hrm_warning" to "false")
        assertEquals("true", HealthMonitoringProtocol.changes("SLEEP", "false", values, autoMotionFeatureEnabled = true)["auto_motion"])
        assertEquals("false", HealthMonitoringProtocol.changes("SLEEP", "false", values + ("auto_detect_fitness" to "false"), autoMotionFeatureEnabled = true)["auto_motion"])
    }
    @Test fun unknownDeviceDefaultCannotBeAssumedFalseWhenDerivingMotion() {
        assertFailsWith<IllegalArgumentException> { HealthMonitoringProtocol.changes("SLEEP", "false", emptyMap()) }
        assertEquals("true", HealthMonitoringProtocol.changes("SLEEP", "true", emptyMap(), autoMotionFeatureEnabled = true)["auto_motion"])
    }
    @Test fun firmwareGateAndModelDefaultsAreUsedBeforeDerivingMotion() {
        assertEquals("false", HealthMonitoringProtocol.changes("SLEEP", "true", emptyMap(),
            autoMotionFeatureEnabled = false)["auto_motion"])
        assertEquals("true", HealthMonitoringProtocol.changes("SLEEP", "false", emptyMap(),
            defaults = mapOf("auto_detect_fitness" to "true"), autoMotionFeatureEnabled = true)["auto_motion"])
        assertFailsWith<IllegalArgumentException> { HealthMonitoringProtocol.changes("SLEEP", "true", emptyMap()) }
    }
    @Test fun exerciseHeartRateLimitHasItsOwnDatabaseStorageAndBoundedIntegerValue() {
        val limit = HealthMonitoringProtocol.spec("EXERCISE_HEART_RATE_UPPER")
        assertFalse(limit.providerPreference)
        for (raw in listOf("100", "195", "220")) assertTrue(limit.accepts(raw))
        for (raw in listOf("99", "221", "195.5", "0100", "true")) assertFalse(limit.accepts(raw))
    }
    @Test fun callerCannotSelectAProviderTableKeyUriOrAnUnrecognizedValue() {
        for (id in listOf("auto_motion", "../../healthy", "content://evil", "SLEEP\u0000"))
            assertFailsWith<IllegalArgumentException> { HealthMonitoringProtocol.changes(id, "true", emptyMap()) }
        for (raw in listOf("1", "TRUE", " true", "false;reboot", ""))
            assertFailsWith<IllegalArgumentException> { HealthMonitoringProtocol.changes("SLEEP", raw, emptyMap()) }
        assertFalse(HealthMonitoringProtocol.spec("REST_HEART_RATE_LOWER").accepts("39"))
        assertFalse(HealthMonitoringProtocol.spec("REST_HEART_RATE_LOWER").accepts("41"))
    }
    @Test fun inProcessSettingsHaveFixedTimeAndEnumDomains() {
        assertEquals(16, HealthMonitoringProtocol.SETTINGS.count { it.inProcess })
        val time = HealthMonitoringProtocol.spec("SLEEP_EXPECTED_BEDTIME")
        for (value in listOf("00:00", "23:59", "08:05")) assertTrue(time.accepts(value))
        for (value in listOf("8:05", "24:00", "12:60", "12:00\n", "../healthy")) assertFalse(time.accepts(value))
        assertEquals(setOf("0", "15", "30", "45", "60"), HealthMonitoringProtocol.spec("SLEEP_SMART_AWAKE_MINUTES").choices.keys)
        assertFalse(HealthMonitoringProtocol.spec("SLEEP_ALARM_VIBRATION").accepts("true"))
        assertTrue(HealthMonitoringProtocol.SETTINGS.filter { it.inProcess }.all { !it.providerPreference })
    }
}
