package dev.ticbox.companion.toolboxagent

import dev.ticbox.companion.protocol.*
import java.io.FilterInputStream
import kotlin.test.*
import kotlinx.serialization.json.*

class AgentScreenEncoderDiagnosticsTest {
    @Test fun fragmentedMetadataSurvivesButOrdinaryOversizedAndUnterminatedLogsDoNot() {
        val value = """{"kind":"screen_encoder","version":1,"stage":"output_format","sdk":34,"preferCbr":true,"encoder":"c2.fixture.hevc.encoder","configuredBitrateMode":2,"inputBitrateMode":1,"inputFps":59.94,"private":"chat body"}"""
        val lines = "ordinary private log\n${ScreenEncoderDiagnostics.PREFIX}$value\n${ScreenEncoderDiagnostics.PREFIX}${"x".repeat(200_000)}\n${ScreenEncoderDiagnostics.PREFIX}{}\n${ScreenEncoderDiagnostics.PREFIX}$value\n${ScreenEncoderDiagnostics.PREFIX}$value"
        val input = object : FilterInputStream(lines.toByteArray().inputStream()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int) = super.read(bytes, offset, minOf(length, 3))
        }
        val observed = mutableListOf<JsonObject>()
        drainScreenEncoderDiagnostics(input, observed::add)
        assertEquals(2, observed.size)
        assertEquals("c2.fixture.hevc.encoder", observed.first().text("encoder"))
        assertEquals(2L, observed.first().number("configuredBitrateMode"))
        assertEquals(1L, observed.first().number("inputBitrateMode"))
        assertEquals(59.94, observed.first()["inputFps"]!!.jsonPrimitive.double)
        assertTrue(observed.none { it.toString().contains("private") || it.toString().contains("chat body") })
    }

    @Test fun diagnosticsOptionIsOffForOrdinarySessionsAndOffUnlessFrameStatsAreRequested() {
        fun options(udp: Boolean, diagnostics: Boolean) = ScreenRequest(buildJsonObject { put("udp", udp); put("udpDiagnostics", diagnostics) }).serverOptions("id")
        assertFalse(options(false, true).contains("video_diagnostics"))
        assertTrue(options(true, false).contains("video_diagnostics=false"))
        assertTrue(options(true, true).contains("video_diagnostics=true"))
    }
}
