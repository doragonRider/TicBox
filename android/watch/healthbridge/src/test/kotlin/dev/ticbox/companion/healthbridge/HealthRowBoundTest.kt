package dev.ticbox.companion.healthbridge

import dev.ticbox.companion.protocol.LocalHealthRow
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class HealthRowBoundTest {
    private fun row(vararg cells: Pair<String, String>) = buildJsonObject {
        put("id", "7")
        put("columns", buildJsonObject { put("_id", 7); cells.forEach { (name, value) -> put(name, value) } })
        put("storageTypes", buildJsonObject { put("_id", "integer"); cells.forEach { (name, _) -> put(name, "string") } })
    }

    @Test fun aRowThatFitsIsLeftAlone() {
        val small = row("a" to "1", "b" to "\u0000\"中")
        assertSame(small, boundHealthRow(small, limit = 1024))
        val measured = row("a" to "x".repeat(400))
        assertSame(measured, boundHealthRow(measured, limit = 1024)) // Too long to pass unmeasured, short enough to fit.
    }

    @Test fun theLargestCellsGoFirstUntilTheRowFitsAndTheIdentityStays() {
        val bounded = boundHealthRow(row("big" to "x".repeat(3000), "mid" to "y".repeat(2000), "small" to "z"), limit = 2600)
        assertEquals(listOf("_id", "mid", "small"), (bounded["columns"] as JsonObject).keys.toList())
        assertEquals(listOf("_id", "mid", "small"), (bounded["storageTypes"] as JsonObject).keys.toList())
        assertEquals("""["big"]""", bounded["oversizedColumns"].toString())
        assertTrue(utf8Length(bounded.toString()) <= 2600)
        val row = LocalHealthRow(bounded)
        assertTrue(row.oversized); assertFalse(row.partial); assertEquals(7, row.id)
        val both = boundHealthRow(row("big" to "x".repeat(3000), "mid" to "y".repeat(2000), "small" to "z"), limit = 1200)
        assertEquals("""["big","mid"]""", both["oversizedColumns"].toString())
    }

    @Test fun escapedTextCountsAtItsWrittenSize() {
        // Six bytes a character once escaped: short in characters, too large once written.
        val bounded = boundHealthRow(row("controls" to "\u0001".repeat(300), "small" to "z"), limit = 1024)
        assertEquals("""["controls"]""", bounded["oversizedColumns"].toString())
    }
}
