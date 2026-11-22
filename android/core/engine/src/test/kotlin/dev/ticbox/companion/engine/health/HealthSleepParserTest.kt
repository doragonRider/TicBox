package dev.ticbox.companion.engine.health

import dev.ticbox.companion.api.*
import kotlinx.serialization.json.*
import kotlin.test.*

class HealthSleepParserTest {
    private val source = HealthRecordSource.LocalDevice("profile", PeerAddress("AA:BB:CC:DD:EE:FF"))
    private fun session(end: Long = 200, extra: JsonObject = JsonObject(emptyMap())) = raw(HealthRecordKind.SESSION, buildJsonObject {
        put("id", "9223372036854775807")
        put("columns", buildJsonObject {
            put("id", "sleep-uuid"); put("device_id", "watch"); put("wwid", JsonNull); put("type", 100)
            put("time_from", 100); put("time_to", end); put("synced", 3); put("deleted", 0)
            extra.forEach { (key, value) -> put(key, value) }
        })
    })
    private fun raw(kind: HealthRecordKind, value: JsonObject) = HealthRawRecord(source, kind, "storage-key", null,
        null, null, null, null, false, 1234, value.toString())
    private fun point(type: Long, end: Long, device: String = "watch", values: String = "75", start: Long = end - 10) =
        HealthPointParser.parse(raw(HealthRecordKind.POINT, buildJsonObject { put("columns", buildJsonObject {
            put("_id", 1); put("device_id", device); put("wwid", JsonNull); put("type", type)
            put("time_from", start); put("time_to", end); put("synced", 0); put("deleted", 0); put("values", values)
        }) }))

    @Test fun `sleep preserves TEXT identity and raw flags separately from its paging cursor`() {
        val raw = session(extra = buildJsonObject { put("future", "keep-me") })
        val data = HealthSleepParser.parse(raw)
        val record = HealthSleepParser.record(raw, data, emptyList(), HealthRecordReadStatus.SOURCE_UNAVAILABLE)
        assertEquals("sleep-uuid", record.sessionId); assertEquals(100L, record.activityTypeCode)
        assertEquals(100L, record.startMillis); assertEquals(200L, record.endMillis)
        assertEquals(3L, record.vendorSyncCode); assertEquals(true, record.vendorSynced); assertEquals(false, record.deleted)
        assertNull(record.vendorUserId); assertEquals(HealthRecordReadStatus.SOURCE_UNAVAILABLE, record.relatedPointsStatus)
        assertTrue("keep-me" in record.raw.json); assertFalse(record.toString().contains("sleep-uuid"))
        assertEquals(data, HealthSleepParser.decode(raw, HealthSleepParser.encode(data)))
        assertEquals(data, HealthSleepParser.decode(raw, "{ damaged cache"))
        val missing = HealthSleepParser.parse(raw(HealthRecordKind.SESSION, buildJsonObject { put("id", "4") }))
        assertNull(missing.id); assertTrue(HealthParseIssue.MISSING_FIELD in missing.issues)
    }

    @Test fun `sleep joins use the APK end-time boundaries without dropping abnormal measurement values`() {
        val data = HealthSleepParser.parse(session())
        for (type in HealthSleepParser.relatedTypeCodes) {
            val motion = type == HealthPointType.MOTION.code
            assertEquals(!motion, HealthSleepParser.belongsToSession(data, point(type, 100)))
            assertTrue(HealthSleepParser.belongsToSession(data, point(type, 150)))
            assertEquals(motion, HealthSleepParser.belongsToSession(data, point(type, 200)))
            assertFalse(HealthSleepParser.belongsToSession(data, point(type, 201)))
            assertFalse(HealthSleepParser.belongsToSession(data, point(type, 150, "another-watch")))
        }
        assertFalse(HealthSleepParser.belongsToSession(data, point(32, 150)))
        val intervals = listOf(point(2, 120, values = "9,10"), point(2, 150, values = "10,8"),
            point(28, 150, values = "0,,future"))
        val record = HealthSleepParser.record(session(), data, intervals, HealthRecordReadStatus.AVAILABLE)
        assertEquals(3, record.relatedPoints.size)
        assertEquals(listOf(HealthMotionState.LIGHT_SLEEP, HealthMotionState.DEEP_SLEEP), record.motionIntervals.map { it.motion?.state })
        assertEquals("0,,future", record.relatedPoints.last().rawValues)
    }


    @Test fun `a complete sleep can include observed off wrist without a malformed row or invented stage`() {
        // Synthetic intervals matching the exported format, not user health values or timestamps.
        val data = HealthSleepParser.parse(session())
        val intervals = listOf(
            point(2, 130, values = "9,6", start = 100),
            point(2, 150, values = "6,10", start = 130),
            point(2, 200, values = "10,99", start = 150),
        )
        val record = HealthSleepParser.record(session(), data, intervals, HealthRecordReadStatus.AVAILABLE)
        assertTrue(record.issues.isEmpty())
        assertEquals(HealthRecordReadStatus.AVAILABLE, record.relatedPointsStatus)
        assertEquals(intervals, record.motionIntervals)
        assertTrue(record.motionIntervals.all { it.issues.isEmpty() })
        assertEquals(listOf(HealthMotionState.LIGHT_SLEEP, HealthMotionState.OFF_WRIST, HealthMotionState.DEEP_SLEEP),
            record.motionIntervals.map { it.motion?.state })
        // The final next state is after the session, never another stage during its last interval.
        assertEquals(HealthMotionState.UNDEFINED, record.motionIntervals.last().motion?.nextState)
        assertEquals(HealthMotionState.DEEP_SLEEP, record.motionIntervals.last().motion?.state)
        assertEquals(listOf(100L to 130L, 130L to 150L, 150L to 200L),
            record.motionIntervals.map { it.startMillis to it.endMillis })
        record.motionIntervals.forEach { point ->
            assertEquals(point, HealthPointParser.decode(point.raw, HealthPointParser.encode(point)))
        }
    }

    @Test fun `unfinished sessions retain zero and unknown activity and broken fields remain diagnosable`() {
        val raw = session(0, buildJsonObject { put("type", 777); put("deleted", "unexpected") })
        val data = HealthSleepParser.parse(raw)
        assertEquals(0L, data.endMillis); assertEquals(777L, data.typeCode); assertNull(data.deletedCode)
        assertTrue(HealthParseIssue.OPEN_SESSION in data.issues); assertTrue(HealthParseIssue.INVALID_INTEGER in data.issues)
        assertTrue(HealthSleepParser.belongsToSession(data, point(1, 999_999)))
        assertFalse(HealthSleepParser.belongsToSession(data.copy(deviceId = null), point(1, 150)))
        assertTrue(HealthParseIssue.INVALID_TIME_RANGE in HealthSleepParser.parse(session(50)).issues)
    }
}
