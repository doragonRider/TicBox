package dev.ticbox.companion.protocol

import kotlin.test.*
import kotlinx.serialization.json.*

class ScreenEncoderDiagnosticsTest {
    @Test fun declaredLimitsDoNotOverwriteAcceptedFormatsOrBecomeAnAutomaticCompatibilityVerdict() {
        val raw = """{"kind":"screen_encoder","version":1,"stage":"output_format","sdk":33,"preferCbr":true,"outputFps":120,"capabilities":{"bitrateMin":64000,"bitrateMax":40000000,"width":1152,"height":2560,"sizeSupported":true,"sizeRateSupported":false,"fpsMin":1,"fpsMax":60,"private":"secret"}}"""
        val parsed = assertNotNull(ScreenEncoderDiagnostics.parse(raw.toByteArray()))
        assertEquals(120L, parsed.number("outputFps"))
        val caps = parsed["capabilities"]!!.jsonObject
        assertEquals(40000000L, caps.number("bitrateMax")); assertEquals(false, caps.flag("sizeRateSupported"))
        assertEquals(60.0, caps["fpsMax"]!!.jsonPrimitive.double); assertNull(caps["private"])
        val malformed = assertNotNull(ScreenEncoderDiagnostics.parse(raw.replace("40000000", "1").toByteArray()))
        assertNull(malformed["capabilities"]!!.jsonObject["bitrateMax"])
    }

    @Test fun sourceBitrateUsesItsMediaIntervalsAndLocalWriteBlockingStaysSeparate() {
        val raw = """{"kind":"screen_encoder","version":1,"stage":"output_sample","sdk":33,"preferCbr":true,"targetBitrate":80000000,"elapsedMillis":2031,"frames":51,"encodedBytes":1275000,"keyFrames":1,"maxFrameBytes":25000,"ptsIntervals":50,"ptsIntervalBytes":1250000,"ptsSpanMicros":500000,"invalidPtsIntervals":0,"maxDequeueWaitMicros":1000,"maxLocalWriteMicros":30000,"maxOutputAgeMicros":1500000,"negativeOutputAges":0,"mediaBitrate":20000000,"private":"secret"}"""
        val parsed = assertNotNull(ScreenEncoderDiagnostics.parse(raw.toByteArray()))
        assertEquals(80000000L, parsed.number("targetBitrate")); assertEquals(20000000L, parsed.number("mediaBitrate"))
        assertEquals(30000L, parsed.number("maxLocalWriteMicros")); assertNull(parsed["private"])
        assertTrue(parsed.size - 2 <= 24)
        assertNull(ScreenEncoderDiagnostics.parse(raw.replace("mediaBitrate\":20000000", "mediaBitrate\":5000000").toByteArray()))
        assertNull(ScreenEncoderDiagnostics.parse(raw.replace("invalidPtsIntervals\":0", "invalidPtsIntervals\":1").toByteArray()))
        val discontinuous = raw.replace("invalidPtsIntervals\":0", "invalidPtsIntervals\":1").replace(",\"mediaBitrate\":20000000", "")
        assertNull(assertNotNull(ScreenEncoderDiagnostics.parse(discontinuous.toByteArray()))["mediaBitrate"])
    }

    @Test fun heldInputsKeepTheirDepthsAndInconsistentCountsAreDropped() {
        val raw = """{"kind":"screen_encoder","version":1,"stage":"input_sample","sdk":33,"preferCbr":true,"elapsedMillis":2004,""" +
            """"submittedPictures":180,"submittedCopies":24,"skippedInputs":2,"overflowedInputs":0,"outputPictures":178,"outputCopies":23,""" +
            """"unmatchedOutputs":1,"maxPictureLatencyMicros":31000,"meanPictureLatencyMicros":12500,"maxCopyLatencyMicros":14000,""" +
            """"maxInputsBehind":1,"picturesNoneBehind":4,"picturesOneBehind":174,"picturesTwoBehind":0,"picturesMoreBehind":0,"frame":"x"}"""
        val parsed = assertNotNull(ScreenEncoderDiagnostics.parse(raw.toByteArray()))
        assertEquals("input_sample", parsed.text("stage")); assertEquals(174L, parsed.number("picturesOneBehind"))
        assertEquals(31000L, parsed.number("maxPictureLatencyMicros")); assertNull(parsed["frame"])
        assertTrue(parsed.size - 2 <= 24)
        assertNull(ScreenEncoderDiagnostics.parse(raw.replace("\"picturesNoneBehind\":4", "\"picturesNoneBehind\":5").toByteArray()), "histogram must add up")
        assertNull(ScreenEncoderDiagnostics.parse(raw.replace("\"meanPictureLatencyMicros\":12500", "\"meanPictureLatencyMicros\":40000").toByteArray()))
        assertNull(ScreenEncoderDiagnostics.parse(raw.replace(",\"unmatchedOutputs\":1", "").toByteArray()))
    }

    @Test fun vendorParameterNamesPassWithTheirTypesAndNothingElse() {
        val raw = """{"kind":"screen_encoder","version":1,"stage":"vendor_parameters","sdk":33,"preferCbr":true,"encoder":"c2.mtk.hevc.encoder",""" +
            """"prefix":"vendor.mtk.ext.venc.","names":["qpvbr.upper-enable:i","tag:s","unknown:?"],"count":5,"omitted":2,"value":"secret"}"""
        val parsed = assertNotNull(ScreenEncoderDiagnostics.parse(raw.toByteArray()))
        assertEquals(listOf("qpvbr.upper-enable:i", "tag:s", "unknown:?"), parsed["names"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("vendor.mtk.ext.venc.", parsed.text("prefix")); assertEquals(2L, parsed.number("omitted")); assertNull(parsed["value"])
        assertEquals("c2.mtk.hevc.encoder", parsed.text("encoder"))
        for (broken in listOf(raw.replace("tag:s", "tag value:s"), raw.replace("tag:s", "tag:x"), raw.replace("\"omitted\":2", "\"omitted\":1"),
            raw.replace("vendor.mtk.ext.venc.", "vendor/mtk/"), raw.replace("\"count\":5", "\"count\":2"))) {
            assertNull(ScreenEncoderDiagnostics.parse(broken.toByteArray()), broken)
        }
    }

    @Test fun requestedAndReadbackFieldsRemainSeparateAndOnlySafeMetadataSurvives() {
        val raw = """{"kind":"screen_encoder","version":1,"stage":"configured","sdk":33,"preferCbr":true,"encoder":"c2.mtk.hevc.encoder","hardware":true,"cbrSupported":false,"configuredBitrate":20000000,"configuredBitrateMode":2,"inputBitrateMode":1,"inputFps":59.94,"inputLatency":2,"private":"secret"}"""
        val parsed = assertNotNull(ScreenEncoderDiagnostics.parse(raw.toByteArray()))
        assertEquals(2L, parsed.number("configuredBitrateMode")); assertEquals(1L, parsed.number("inputBitrateMode"))
        assertEquals(false, parsed.flag("cbrSupported")); assertEquals(59.94, parsed["inputFps"]!!.jsonPrimitive.double)
        assertNull(parsed["private"]); assertTrue(parsed.size <= 24)
        val unsafe = assertNotNull(ScreenEncoderDiagnostics.parse(raw.replace("c2.mtk.hevc.encoder", "/private/path").replace("59.94", "90000000").toByteArray()))
        assertNull(unsafe["encoder"]); assertNull(unsafe["inputFps"])
    }
    @Test fun malformedUnknownOrOversizedMetadataIsIgnoredWithoutInventingCapabilities() {
        for (raw in listOf("[]", "{}", "{".repeat(2049), """{"kind":"screen_encoder","version":2}""")) assertNull(ScreenEncoderDiagnostics.parse(raw.toByteArray()))
        val minimal = assertNotNull(ScreenEncoderDiagnostics.parse("""{"kind":"screen_encoder","version":1,"stage":"output_format","sdk":34,"preferCbr":true}""".toByteArray()))
        assertNull(minimal["cbrSupported"]); assertNull(minimal["inputBitrateMode"]); assertNull(minimal["hardware"])
    }
}
