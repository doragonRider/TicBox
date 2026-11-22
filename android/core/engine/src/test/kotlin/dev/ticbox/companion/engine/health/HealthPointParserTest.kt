package dev.ticbox.companion.engine.health

import dev.ticbox.companion.api.*
import kotlinx.serialization.json.*
import kotlin.test.*

class HealthPointParserTest {
    private val source = HealthRecordSource.LocalDevice("local", PeerAddress("AA:BB:CC:DD:EE:FF"))
    // Independently constructed values, identifiers and intervals; no device export rows are embedded here.
    private fun raw(values: String, type: Long = 1, start: Long = 946684890000, end: Long = start,
        id: Long = 2, synced: Long = 0, deleted: Long = 0, partial: Boolean = false): HealthRawRecord {
        val body = buildJsonObject {
            put("id", id.toString()); put("partial", partial)
            put("columns", buildJsonObject {
                put("_id", id); put("values", values); put("device_id", "watch-identity"); put("wwid", "vendor-identity")
                put("type", type); put("time_from", start); put("time_to", end); put("synced", synced); put("deleted", deleted)
            })
        }.toString()
        return HealthRawRecord(source, HealthRecordKind.POINT, id.toString(), "watch-db", type.toString(), start, end,
            null, deleted != 0L, 946685000000, body)
    }

    @Test fun `synthetic heart rate and motion pairs retain source times and distinct interval semantics`() {
        val heart = HealthPointParser.parse(raw("72"))
        assertEquals(2L, heart.providerId); assertEquals(HealthPointType.HEART_RATE, heart.type)
        assertEquals(946684890000, heart.startMillis); assertEquals(heart.startMillis, heart.endMillis)
        assertEquals("72", heart.values.single().decimal); assertEquals("bpm", heart.values.single().unit)
        assertEquals("heart_rate", heart.values.single().semantic)
        assertEquals("watch-identity", heart.deviceId); assertEquals("vendor-identity", heart.vendorUserId)
        assertEquals(false, heart.vendorSynced); assertEquals(false, heart.deleted); assertTrue(heart.issues.isEmpty())
        assertTrue(heart.raw.updatedAtMillis > heart.endMillis!!)
        val offWrist = HealthPointParser.parse(raw("6,0", 2, 946684800000, 946684860000, id = 1))
        val static = HealthPointParser.parse(raw("0,6", 2, 946684860000, 946684920000, id = 3))
        val offWristMotion = assertNotNull(offWrist.motion); val staticMotion = assertNotNull(static.motion)
        assertEquals(HealthMotionState.OFF_WRIST, offWristMotion.state)
        assertEquals(HealthMotionState.STATIC, offWristMotion.nextState)
        assertEquals(HealthMotionState.STATIC, staticMotion.state)
        assertEquals(HealthMotionState.OFF_WRIST, staticMotion.nextState)
        assertEquals(listOf("interval_state", "next_state"), static.values.map { it.semantic })
        assertTrue(offWrist.issues.isEmpty()); assertTrue(static.issues.isEmpty())
        assertEquals(heart, HealthPointParser.decode(heart.raw, HealthPointParser.encode(heart)))
        assertEquals(offWrist, HealthPointParser.decode(offWrist.raw, HealthPointParser.encode(offWrist)))
    }
    @Test fun `precise decimals empty slots and non numeric values survive persistence without fake zero`() {
        val row = raw(" 9223372036854775807 ,1e3,,NaN,-Infinity,unrecognised,", type = 28, id = Long.MAX_VALUE)
        val point = HealthPointParser.parse(row)
        assertEquals(Long.MAX_VALUE, point.providerId)
        assertEquals("9223372036854775807", point.values[0].decimal)
        assertEquals(" 9223372036854775807 ", point.values[0].rawToken)
        assertEquals("1E+3", point.values[1].decimal)
        assertEquals(listOf(HealthValueStatus.NUMBER, HealthValueStatus.NUMBER, HealthValueStatus.EMPTY,
            HealthValueStatus.NON_FINITE, HealthValueStatus.NON_FINITE, HealthValueStatus.TEXT, HealthValueStatus.EMPTY), point.values.map { it.status })
        assertTrue(point.values.drop(2).all { it.decimal == null })
        assertEquals("blood_oxygen", point.values.first().semantic); assertEquals("%", point.values.first().unit)
        assertContains(point.issues, HealthParseIssue.UNCONFIRMED_VALUE_LAYOUT)
        assertContains(point.issues, HealthParseIssue.NON_FINITE_VALUE)
        assertEquals(point, HealthPointParser.decode(row, HealthPointParser.encode(point)))
        assertFalse(point.toString().contains("922337")); assertFalse(point.values.first().toString().contains("922337"))
    }
    @Test fun `confirmed oxygen respiration and skin temperature scalars keep database units without rescaling`() {
        for ((type, value, unit) in listOf(Triple(28L, "98.5", "%"), Triple(38L, "16", "breaths/min"), Triple(42L, "33.25", "°C"))) {
            val point = HealthPointParser.parse(raw(value, type))
            assertEquals(value, point.values.single().decimal); assertEquals(unit, point.values.single().unit)
            assertEquals(point.type!!.wireName, point.values.single().semantic); assertTrue(point.issues.isEmpty())
            assertEquals(point, HealthPointParser.decode(point.raw, HealthPointParser.encode(point)))
        }
        // Type32 has its own phone Celsius->Fahrenheit consumer; it is independent of type42.
        assertEquals("°C", HealthPointParser.parse(raw("33.00", 32)).values.single().unit)
    }
    @Test fun `activity increments annotate source units without rounding floors or changing signs`() {
        for ((type, value, unit) in listOf(Triple(8L, "0.1234567890123456789", "kcal"),
            Triple(9L, "-1.25", "m"), Triple(10L, "12.5", "steps"), Triple(39L, "3.1234567", "m"))) {
            val point = HealthPointParser.parse(raw(value, type, start = 100, end = 200))
            assertEquals(value, point.values.single().decimal); assertEquals(unit, point.values.single().unit)
            assertEquals(point.type!!.wireName, point.values.single().semantic); assertTrue(point.issues.isEmpty())
            assertEquals(100, point.startMillis); assertEquals(200, point.endMillis)
            assertEquals(point, HealthPointParser.decode(point.raw, HealthPointParser.encode(point)))
        }
    }
    @Test fun `configuration times codes and composite durations retain different source meanings`() {
        val birthday = HealthPointParser.parse(raw("-315619200", 24))
        assertEquals("epoch_s", birthday.values.single().unit); assertEquals("-315619200", birthday.values.single().decimal)
        val bedtime = HealthPointParser.parse(raw("82800000", 33))
        assertEquals("ms_since_midnight", bedtime.values.single().unit); assertEquals("82800000", bedtime.values.single().decimal)
        assertEquals("s", HealthPointParser.parse(raw("1800", 21)).values.single().unit)
        assertEquals("heart_rate_warning_code", HealthPointParser.parse(raw("17", 15)).values.single().unit)
        val training = HealthPointParser.parse(raw("999,120000", 30))
        assertEquals(listOf("sport_type_code", "ms"), training.values.map { it.unit })
        assertEquals(listOf("sport_type", "high_intensity_duration"), training.values.map { it.semantic })
        assertEquals(listOf("999", "120000"), training.values.map { it.decimal }); assertTrue(training.issues.isEmpty())
        val heart = HealthPointParser.parse(raw("99,0", 36))
        assertEquals(listOf("heart_health_code", "bpm"), heart.values.map { it.unit }); assertTrue(heart.issues.isEmpty())
        val body = HealthPointParser.parse(raw("2,5", 37))
        assertEquals(listOf("body_mind_code", "ordinal"), body.values.map { it.unit })
        val unknownGender = HealthPointParser.parse(raw("12345", 23))
        assertEquals("gender_code", unknownGender.values.single().unit); assertTrue(unknownGender.issues.isEmpty())
        for (point in listOf(birthday, bedtime, training, heart, body, unknownGender))
            assertEquals(point, HealthPointParser.decode(point.raw, HealthPointParser.encode(point)))
    }
    @Test fun `noise vectors label every sample without inventing sample times`() {
        val point = HealthPointParser.parse(raw("42.5,51.25,-1", 27, start = 100, end = 200))
        assertEquals(listOf("dB", "dB", "dB"), point.values.map { it.unit })
        assertTrue(point.values.all { it.semantic == "noise_decibel" }); assertTrue(point.issues.isEmpty())
        assertEquals(100, point.startMillis); assertEquals(200, point.endMillis)
        assertEquals(point, HealthPointParser.decode(point.raw, HealthPointParser.encode(point)))
    }
    @Test fun `arty consumer offset keeps its uninterpreted prefix and age bounds`() {
        val point = HealthPointParser.parse(raw("999,80.5,63,55.25,70,32,38", 35))
        assertNull(point.values[0].semantic); assertNull(point.values[0].unit)
        assertEquals("999", point.values[0].decimal)
        assertEquals(listOf("arty_score", "tru_hr", "ecap", "hsx", "arty_age_lower", "arty_age_upper"),
            point.values.drop(1).map { it.semantic })
        assertEquals(listOf("score/100", "bpm", "%", "%", "years", "years"), point.values.drop(1).map { it.unit })
        assertEquals(listOf("32", "38"), point.values.takeLast(2).map { it.decimal })
        assertContains(point.issues, HealthParseIssue.UNCONFIRMED_VALUE_LAYOUT)
        assertEquals(point, HealthPointParser.decode(point.raw, HealthPointParser.encode(point)))
    }
    @Test fun `vo2max textual sport fields stay opaque and future slots stay queryable`() {
        val point = HealthPointParser.parse(raw("42.75, future_sport ,123456,001234567890123456789", 41))
        assertEquals(listOf("vo2max", "sport_type", "duration", "sport_id"), point.values.map { it.semantic })
        assertEquals(listOf("ml/kg/min", null, "ms", null), point.values.map { it.unit })
        assertEquals(listOf(HealthValueStatus.NUMBER, HealthValueStatus.TEXT, HealthValueStatus.NUMBER, HealthValueStatus.TEXT), point.values.map { it.status })
        assertNull(point.values[3].decimal); assertEquals("001234567890123456789", point.values[3].rawToken)
        assertEquals(" future_sport ", point.values[1].rawToken); assertTrue(point.issues.isEmpty())
        assertEquals(point, HealthPointParser.decode(point.raw, HealthPointParser.encode(point)))
        val extra = HealthPointParser.parse(raw("42,run,120000,,new_field", 41))
        assertEquals(5, extra.values.size); assertNull(extra.values.last().semantic)
        assertEquals(HealthValueStatus.EMPTY, extra.values[3].status)
        assertContains(extra.issues, HealthParseIssue.UNEXPECTED_VALUE_COUNT)
        assertContains(extra.issues, HealthParseIssue.UNCONFIRMED_VALUE_LAYOUT)
        // Neither unknown type3 nor type31 is guessed from a similarly named fitness field.
        for (type in listOf(3L, 31L)) {
            val unknown = HealthPointParser.parse(raw("1,2", type))
            assertTrue(unknown.values.all { it.semantic == null && it.unit == null })
            assertContains(unknown.issues, HealthParseIssue.UNCONFIRMED_VALUE_LAYOUT)
        }
    }
    @Test fun `future codes unusual flags inverted times and source partials stay queryable`() {
        val row = raw("0,-12.5", type = 40, start = 200, end = 100, synced = 2, deleted = 7, partial = true)
        val point = HealthPointParser.parse(row)
        assertEquals(40, point.typeCode); assertNull(point.type); assertNull(point.vendorSynced); assertNull(point.deleted)
        assertEquals(listOf("0", "-12.5"), point.values.map { it.decimal })
        assertContains(point.issues, HealthParseIssue.UNKNOWN_TYPE)
        assertContains(point.issues, HealthParseIssue.NONSTANDARD_FLAG)
        assertContains(point.issues, HealthParseIssue.INVALID_TIME_RANGE)
        assertContains(point.issues, HealthParseIssue.PARTIAL_SOURCE_ROW)
        val futureMotion = HealthPointParser.parse(raw("-1,12345", 2))
        val motion = assertNotNull(futureMotion.motion)
        assertEquals(HealthMotionState.UNKNOWN, motion.state)
        assertEquals(12345L, motion.nextStateCode); assertNull(motion.nextState)
        assertContains(futureMotion.issues, HealthParseIssue.UNKNOWN_MOTION_STATE)
    }
    @Test fun `malformed vendor rows and damaged parsed caches retain usable data`() {
        val row = raw("72"); val parsed = HealthPointParser.parse(row)
        assertEquals(parsed, HealthPointParser.decode(row, "{broken"))
        val malformed = HealthPointParser.parse(row.copy(json = "{broken"))
        assertEquals(2, malformed.providerId); assertTrue(malformed.values.isEmpty())
        assertContains(malformed.issues, HealthParseIssue.MALFORMED_ROW)
        assertContains(malformed.issues, HealthParseIssue.MISSING_FIELD)
        val badNumber = HealthPointParser.parse(row.copy(json = row.json.replace("946684890000", "1.5")))
        assertNull(badNumber.startMillis); assertContains(badNumber.issues, HealthParseIssue.INVALID_INTEGER)
        val shortMotion = HealthPointParser.parse(raw("6", 2))
        val motion = assertNotNull(shortMotion.motion)
        assertEquals(HealthMotionState.OFF_WRIST, motion.state); assertNull(motion.nextStateCode)
        assertContains(shortMotion.issues, HealthParseIssue.UNEXPECTED_VALUE_COUNT)
    }
}
