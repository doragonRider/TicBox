package dev.ticbox.companion.engine

import dev.ticbox.companion.api.*
import kotlin.test.*
import kotlinx.serialization.json.*

class DiagnosticJsonTest {
    @Test fun structuredLogsEscapeUntrustedTextAndKeepExactIds() {
        val fields = mutableMapOf("decision" to "ignore\nnext\"field", "sequence" to Long.MAX_VALUE.toString())
        val event = ProtocolDiagnostic("channel", "unexpected_ack", peer = PeerAddress("AA:BB:CC:DD:EE:FF"),
            sessionId = Long.MAX_VALUE, channelId = ULong.MAX_VALUE, localIsOpener = false, details = fields)
        fields["decision"] = "changed later"
        val line = DiagnosticJson.line(event, 1234)
        assertFalse(line.contains('\n'))
        val json = Json.parseToJsonElement(line).jsonObject
        assertEquals(Long.MAX_VALUE, json.getValue("sessionId").jsonPrimitive.long)
        assertEquals(ULong.MAX_VALUE.toString(), json.getValue("channelId").jsonPrimitive.content)
        assertEquals("ignore\nnext\"field", json.getValue("details").jsonObject.getValue("decision").jsonPrimitive.content)
        assertEquals(false, json.getValue("localIsOpener").jsonPrimitive.boolean)
    }

    @Test fun metadataBoundsDoNotCutUnicodePairsOrSilentlyDropTheTruncationNotice() {
        val text = "😀".repeat(1000)
        val event = ProtocolDiagnostic(text, text, details = (0 until 40).associate { "field$it" to text })
        val line = DiagnosticJson.line(event, 1)
        assertTrue(line.toByteArray().size < 32_768)
        val json = Json.parseToJsonElement(line).jsonObject
        assertTrue(json.getValue("truncated").jsonPrimitive.boolean)
        assertEquals(24, json.getValue("details").jsonObject.size)
        assertTrue(json.getValue("details").jsonObject.values.all { value ->
            val decoded = value.jsonPrimitive.content
            decoded.codePointCount(0, decoded.length) == 256 && !decoded.contains('\uFFFD')
        })
    }
}
