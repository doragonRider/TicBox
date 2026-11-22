package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.protocol.ToolboxProtocol
import kotlin.test.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ScreenUdpSenderDiagnosticsTest {
    @Test fun vendorParameterNamesSplitIntoDetailsThatFitTheLog() {
        val names = List(40) { "parameter-number-$it.enable:i" }
        val encoder = buildJsonObject {
            put("stage", "vendor_parameters"); put("encoder", "c2.mtk.hevc.encoder"); put("prefix", "vendor.mtk.ext.venc.")
            put("count", 42); put("omitted", 2); put("names", JsonArray(names.map { JsonPrimitive(it) }))
        }
        val details = screenVendorParameterDetails(encoder)
        assertEquals("c2.mtk.hevc.encoder", details["encoder"]); assertEquals("42", details["count"]); assertEquals("2", details["omitted"])
        val parts = details.filterKeys { it.startsWith("names") }
        assertEquals(parts.keys.toList(), List(parts.size) { "names${it + 1}" })
        assertTrue(parts.values.all { it.length <= 256 }); assertTrue(details.size <= 24)
        assertEquals(names, parts.values.flatMap { it.split(',') })
    }

    @Test fun theAgentsEncoderDefaultsAreLoggedOnlyAsCodecOptions() {
        val base = """{"kind":"screen_encoder_limit","version":1,"limitToEncoder":true,"codec":"h265","fps":120,"bitRate":40000000,""" +
            """"requestedCodec":"h265","requestedFps":120,"requestedBitRate":40000000"""
        val (event, details) = assertNotNull(ScreenUdpSenderDiagnostics.parseLimit(packet("$base,\"encoderDefaults\":\"video-qp-max=35,bitrate-mode=1\"}")))
        assertEquals("video_encoder_limit", event)
        assertEquals("video-qp-max=35,bitrate-mode=1", details["encoderDefaults"])
        assertNull(assertNotNull(ScreenUdpSenderDiagnostics.parseLimit(packet("$base}"))).second["encoderDefaults"])
        assertNull(ScreenUdpSenderDiagnostics.parseLimit(packet("$base,\"encoderDefaults\":\"qp 35; rm\"}")))
    }

    @Test fun rejectedTimestampBreakdownDoesNotBecomeAFakeEncoderMean() {
        val raw = """{"kind":"screen_udp_timing","version":1,"frames":0,"rejectedFrames":4,"overRangeFrames":2,"negativeAgeFrames":1,"missingTimestamps":1,"maxOverRangeAgeMicros":2500000}"""
        val result = assertNotNull(ScreenUdpSenderDiagnostics.parseTiming(packet(raw)))
        assertNull(result.micros); assertEquals("unknown", result.details["meanMicros"])
        assertEquals("2500000", result.details["maxOverRangeAgeMicros"])
        assertNull(ScreenUdpSenderDiagnostics.parseTiming(packet(raw.replace("2500000", "999999"))))
        assertNull(ScreenUdpSenderDiagnostics.parseTiming(packet(raw.replace(":2,", ":4,"))))
    }

    @Test fun timingComponentsShareAValidatedFrameSetAndNeverForwardArbitraryMetadata() {
        val raw = """{"kind":"screen_udp_timing","version":1,"frames":100,"rejectedFrames":2,"meanMicros":19001,"meanCaptureToRelayMicros":15000,"maxCaptureToRelayMicros":30000,"meanSendWorkMicros":4000,"meanPacingMicros":1000,"private":"secret"}"""
        val observed = assertNotNull(ScreenUdpSenderDiagnostics.parseTiming(packet(raw)))
        assertEquals(19001L, observed.micros)
        assertEquals("15000", observed.details["meanCaptureToRelayMicros"])
        assertEquals("4000", observed.details["meanSendWorkMicros"])
        assertEquals("1000", observed.details["meanPacingMicros"])
        assertEquals("2", observed.details["rejectedFrames"])
        assertFalse(observed.details.values.contains("secret")); assertTrue(observed.details.size <= 24)
        assertNull(ScreenUdpSenderDiagnostics.parseTiming(packet(raw.replace("19001", "19002"))))
        assertNull(ScreenUdpSenderDiagnostics.parseTiming(packet(raw.replace("30000", "14999"))))
        assertNull(ScreenUdpSenderDiagnostics.parseTiming(packet(raw.replace("\"meanSendWorkMicros\":4000,", ""))))
        assertNull(ScreenUdpSenderDiagnostics.parseTiming(packet(raw.replace("\"meanPacingMicros\":1000", "\"meanPacingMicros\":-1"))))
    }

    @Test fun gsoAndNativePhasesUseASeparateBoundedEventWithNoUnvalidatedFields() {
        val extended = slow.dropLast(1) + ""","socketSendMicros":3000,"sendCalls":70,"nativeBatch":true,"socketCopyMicros":30,"socketCopyCpuMicros":20,"socketNativeMicros":2800,"socketNativeCpuMicros":2000,"gsoCalls":60,"gsoDatagrams":480,"gsoDisabled":false,"ptsMicros":120000,"ptsGapMicros":100000,"producerGapMicros":101000,"private":"secret"}"""
        val base = assertNotNull(ScreenUdpSenderDiagnostics.parse(packet(extended))).second
        assertEquals("100000", base["ptsGapMicros"])
        assertNull(base["socketCopyMicros"])
        val (event, details) = assertNotNull(ScreenUdpSenderDiagnostics.parseSocket(packet(extended)))
        assertEquals("video_sender_socket", event); assertEquals("480", details["gsoDatagrams"])
        assertEquals("2000", details["socketNativeCpuMicros"])
        assertFalse(details.values.contains("secret")); assertTrue(base.size <= 24 && details.size <= 24)
        assertNull(ScreenUdpSenderDiagnostics.parseSocket(packet(extended.replace(":2800", ":3001"))))
        assertNull(ScreenUdpSenderDiagnostics.parseSocket(packet(extended.replace(":480", ":523"))))
        assertNull(ScreenUdpSenderDiagnostics.parseSocket(packet(extended.replace("\"gsoCalls\":60", "\"gsoCalls\":71"))))
        assertNull(ScreenUdpSenderDiagnostics.parseSocket(packet(slow)))
        assertEquals("video_sender_frame_gap", assertNotNull(ScreenUdpSenderDiagnostics.parse(packet(extended.replace("slow_frame", "frame_gap")))).first)
    }
    @Test fun onlyMeasuredNumericMetadataAndKeyFlagReachTheDiagnosticSink() {
        val sample = slow.dropLast(1) + ",\"private\":\"clipboard text\",\"address\":\"192.0.2.1\"}"
        val (name, details) = checkNotNull(ScreenUdpSenderDiagnostics.parse(packet(sample)))
        assertEquals("video_sender_slow_frame", name)
        assertEquals(mapOf("sequence" to "7", "frameBytes" to "600546", "datagrams" to "522", "wireBytes" to "625602",
            "sendMicros" to "50000", "pacingWaitMicros" to "45000", "keyFrame" to "false"), details)
    }

    @Test fun legacyTimingMeanIsStillReadableWithoutInventingComponentMeasurements() {
        val raw = """{"kind":"screen_udp_timing","version":1,"frames":58,"meanMicros":12400}"""
        assertEquals(12_400L, assertNotNull(ScreenUdpSenderDiagnostics.parseTiming(packet(raw))).micros)
        assertNull(ScreenUdpSenderDiagnostics.parse(packet(raw)))
        // A second with no frame it could time is known to be unmeasured.
        assertNull(assertNotNull(ScreenUdpSenderDiagnostics.parseTiming(packet("""{"kind":"screen_udp_timing","version":1,"frames":0}"""))).micros)
        assertNull(ScreenUdpSenderDiagnostics.parseTiming(packet(raw.replace("12400", "-1"))))
        assertNull(ScreenUdpSenderDiagnostics.parseTiming(packet(raw.replace("\"version\":1", "\"version\":2"))))
        assertNull(ScreenUdpSenderDiagnostics.parseTiming(packet(raw.replace(",\"meanMicros\":12400", ""))))
        assertNull(ScreenUdpSenderDiagnostics.parseTiming(packet(slow)))
    }

    @Test fun windowsKeepCountsAndMaximaAndCannotDisguisePayloadAsMetrics() {
        val raw = """{"kind":"screen_udp_sender","version":1,"sample":"window","elapsedMillis":2004,"frames":225,"encodedBytes":5649332,"keyFrames":3,"maxFrameBytes":600546,"maxSendMicros":55000,"maxPacingWaitMicros":45000}"""
        val (name, details) = checkNotNull(ScreenUdpSenderDiagnostics.parse(packet(raw)))
        assertEquals("video_sender_sample", name)
        assertEquals("225", details["frames"])
        assertEquals("45000", details["maxPacingWaitMicros"])
        assertNull(ScreenUdpSenderDiagnostics.parse(packet(raw.replace("\"frames\":225", "\"frames\":0"))))
        assertNull(ScreenUdpSenderDiagnostics.parse(packet(raw.replace("\"keyFrames\":3", "\"keyFrames\":226"))))
    }

    @Test fun fecSenderCostsAreOptionalValidatedMetadata() {
        val extended = slow.dropLast(1) + ",\"parityDatagrams\":100,\"fecEncodeMicros\":1200}"
        val parsed = checkNotNull(ScreenUdpSenderDiagnostics.parse(packet(extended))).second
        assertEquals("100", parsed["parityDatagrams"])
        assertEquals("1200", parsed["fecEncodeMicros"])
        assertNull(ScreenUdpSenderDiagnostics.parse(packet(extended.replace("\"parityDatagrams\":100", "\"parityDatagrams\":523"))))
        assertNull(ScreenUdpSenderDiagnostics.parse(packet(extended.replace("\"fecEncodeMicros\":1200", "\"fecEncodeMicros\":50001"))))
    }

    @Test fun nativeSendTimingsAndBackendEnumsAreValidatedWithoutLeakingArbitraryText() {
        val extended = slow.dropLast(1) + ""","encryptMicros":2000,"socketSendMicros":3000,"sendCalls":70,"nativeBatch":true,"fecImplementation":"neon"}"""
        val details = checkNotNull(ScreenUdpSenderDiagnostics.parse(packet(extended))).second
        assertEquals("neon", details["fecImplementation"])
        assertEquals("70", details["sendCalls"])
        assertEquals("true", details["nativeBatch"])
        assertNull(ScreenUdpSenderDiagnostics.parse(packet(extended.replace("neon", "/private/path"))))
        assertNull(ScreenUdpSenderDiagnostics.parse(packet(extended.replace(":3000", ":50001"))))
    }

    @Test fun cryptoModeAndCpuTimingsRemainBoundedTypedMetadata() {
        val extended = slow.dropLast(1) + ""","mediaSecurity":"hmac_sha256_128","encryptCpuMicros":1500,"socketSendCpuMicros":2000}"""
        val details = checkNotNull(ScreenUdpSenderDiagnostics.parse(packet(extended))).second
        assertEquals("hmac_sha256_128", details["mediaSecurity"])
        assertEquals("1500", details["encryptCpuMicros"])
        assertNull(ScreenUdpSenderDiagnostics.parse(packet(extended.replace("hmac_sha256_128", "unauthenticated"))))
        assertNull(ScreenUdpSenderDiagnostics.parse(packet(extended.replace(":2000", ":50001"))))
    }

    @Test fun malformedUnsupportedOrContradictoryMetadataIsIgnored() {
        for (raw in listOf("not json", "[]", "{}", " ".repeat(2049),
            slow.replace("\"version\":1", "\"version\":2"),
            slow.replace("\"sample\":\"slow_frame\"", "\"sample\":\"raw_image\""),
            slow.replace("\"frameBytes\":600546", "\"frameBytes\":8388609"),
            slow.replace("\"datagrams\":522", "\"datagrams\":-1"),
            slow.replace("\"pacingWaitMicros\":45000", "\"pacingWaitMicros\":50001"),
            slow.replace("\"sendMicros\":50000", "\"sendMicros\":\"private\""),
            slow.replace("\"keyFrame\":false", "\"keyFrame\":null"))) {
            assertNull(ScreenUdpSenderDiagnostics.parse(packet(raw)), raw.take(100))
        }
        assertNull(ScreenUdpSenderDiagnostics.parse(ToolboxProtocol.Packet(ToolboxProtocol.VIDEO_FRAME, slow.toByteArray())))
    }

    companion object {
        private const val slow = """{"kind":"screen_udp_sender","version":1,"sample":"slow_frame","sequence":7,"frameBytes":600546,"datagrams":522,"wireBytes":625602,"sendMicros":50000,"pacingWaitMicros":45000,"keyFrame":false}"""
        private fun packet(raw: String) = ToolboxProtocol.Packet(ToolboxProtocol.PROGRESS, raw.toByteArray())
    }
}
