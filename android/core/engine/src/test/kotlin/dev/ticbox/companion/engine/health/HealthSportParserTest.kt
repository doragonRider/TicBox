package dev.ticbox.companion.engine.health

import dev.ticbox.companion.api.*
import kotlin.test.*

class HealthSportParserTest {
    private fun raw(json: String) = HealthRawRecord(
        HealthRecordSource.LocalDevice("profile", PeerAddress("AA:BB:CC:DD:EE:FF")), HealthRecordKind.SPORT,
        "fallback", null, null, null, null, null, false, 0, json)

    @Test fun `summary units preserve zero values and unknown sport type without point reinterpretation`() {
        val record = raw("""{"id":"time_to","sourceOccurrence":1,"columns":{"id":"time_to","wwid":"","device_id":"watch","type":9876,"time_from":1000,"time_to":2000,"during":500,"calorie":0,"heart":0,"synced":3,"future":{"raw":true}}}""")
        val parsed = HealthSportParser.parse(record)
        assertEquals("time_to", parsed.id); assertEquals("[\"time_to\",1]", parsed.recordKey)
        assertEquals(500L, parsed.durationMillis); assertEquals("0", parsed.calorieDecimal)
        assertEquals(0L, parsed.averageHeartRateBpm); assertEquals(3L, parsed.vendorSyncCode)
        assertEquals(9876L, parsed.typeCode); assertTrue(parsed.issues.isEmpty())
        assertTrue("future" in record.json); assertFalse("time_to" in parsed.toString())
    }

    @Test fun `bad cells retain good metadata and original values instead of rejecting the training`() {
        val record = raw("""{"partial":true,"columns":{"id":"not-a-uuid","type":-987,"time_from":2000,"time_to":1000,"during":"not-duration","calorie":"vendor:future","heart":76,"synced":0}}""")
        val parsed = HealthSportParser.parse(record)
        assertEquals("not-a-uuid", parsed.id); assertEquals(-987L, parsed.typeCode)
        assertEquals(76L, parsed.averageHeartRateBpm); assertNull(parsed.durationMillis); assertNull(parsed.calorieDecimal)
        assertTrue(parsed.issues.containsAll(setOf(HealthParseIssue.PARTIAL_SOURCE_ROW, HealthParseIssue.INVALID_INTEGER,
            HealthParseIssue.VALUE_NOT_NUMERIC, HealthParseIssue.INVALID_TIME_RANGE)))
        assertTrue("vendor:future" in record.json)
    }
}
