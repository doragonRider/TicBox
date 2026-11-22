package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.*
import kotlin.test.*
import kotlinx.coroutines.runBlocking

class ScreenDiagnosticsTest {
    @Test fun pipelineExpansionHasItsOwnThrottledEventAndKeepsReceiveAndQueueStagesSigned() {
        val observed = mutableListOf<ProtocolDiagnostic>()
        val trace = ScreenDiagnostics(DiagnosticSink(observed::add), ToolboxBackendKind.ADB, ToolboxScreenOptions(udp = true), { 0L })
        val pair = ToolboxScreenPresentationGap(120_000, 40_000, 8_000, 42_000, -3_000, 1_000, 50_000, -8_000)
        val sample = ToolboxScreenPlayback(100, 96, 500, 20, 15, 4, 0, 0, "codec", true, largestPipelineExpansion = pair)
        repeat(10) { trace.playback(sample) }
        val event = observed.single { it.event == "presentation_expansion" }
        assertEquals("32000", event.details["expansionMicros"])
        assertEquals("50000", event.details["receiveGapMicros"])
        assertEquals("-8000", event.details["queueChangeMicros"])
        assertEquals("-3000", event.details["decodeChangeMicros"])
        assertTrue(event.details.size <= 24)
        trace.finish()
        trace.playback(sample)
        assertEquals(1, observed.count { it.event == "presentation_expansion" })
    }

    @Test fun schedulerMetadataIsSeparateBoundedAndRateLimited() {
        val observed = mutableListOf<ProtocolDiagnostic>()
        var now = 0L
        val trace = ScreenDiagnostics(DiagnosticSink(observed::add), ToolboxBackendKind.ADB, ToolboxScreenOptions(udp = true), { now })
        val sample = ToolboxScreenPlayback(120, 115, 10, 12, 8, 15, 0, 0, "codec", true,
            outputScheduler = ToolboxScreenOutputScheduler(300, 12, 2, 3, 1, 1, 12_000, null))
        repeat(10) { trace.playback(sample) }
        val scheduler = observed.single { it.event == "display_scheduler_sample" }
        assertEquals("3", scheduler.details["queueOverflowDrops"])
        assertEquals("12000", scheduler.details["callbackDelayMaxMicros"])
        assertEquals("unknown", scheduler.details["outputQueueWaitMaxMicros"])
        assertEquals(8, scheduler.details.size)
        assertTrue(observed.all { it.details.size <= 24 })
        now = 1_000_000_000L
        trace.playback(sample)
        assertEquals(2, observed.count { it.event == "display_scheduler_sample" })
        trace.finish()
        now += 1_000_000_000L
        trace.playback(sample)
        assertEquals(2, observed.count { it.event == "display_scheduler_sample" })
    }

    @Test fun sameFramePairIsLoggedWithoutOverflowingPlaybackMetadataOrFilteringSignedChanges() {
        val observed = mutableListOf<ProtocolDiagnostic>()
        val trace = ScreenDiagnostics(DiagnosticSink(observed::add), ToolboxBackendKind.ADB, ToolboxScreenOptions(udp = true))
        trace.playback(ToolboxScreenPlayback(90, 80, 100, 20, 15, 4, 12, 1, "codec", true,
            longestPresentationGap = ToolboxScreenPresentationGap(120_000, 100_000, 8_000, 106_000, -8_000, 2_000)))
        val gap = observed.single { it.event == "presentation_gap" }
        assertEquals("-8000", gap.details["decodeChangeMicros"])
        assertEquals("100000", gap.details["intervalMicros"])
        assertTrue(observed.all { it.details.size <= 24 })
    }
    @Test fun profileAndPlaybackIdentifyActualSessionAndOnlyRetainMetadata() {
        val observed = mutableListOf<ProtocolDiagnostic>()
        var now = 0L
        val trace = ScreenDiagnostics(DiagnosticSink(observed::add), ToolboxBackendKind.ADB,
            ToolboxScreenOptions(udp = true, maxSize = 2560, maxFps = 120, bitRate = 100_000_000, codec = ToolboxVideoCodec.H265), { now })
        assertEquals("UDP", observed.first().details["transport"])
        assertEquals("100000000", observed.first().details["requestedBitRate"])
        assertEquals("23", observed.single { it.event == "video_queue_budget" }.details["maximumBatches"])
        assertEquals("22", observed.single { it.event == "video_queue_budget" }.details["maximumReassemblyMessages"])
        trace.playback(ToolboxScreenPlayback(90, 80, 100, 20, 15, 4, 12, 1, "c2.exynos.hevc.decoder", true))
        val playback = observed.last()
        assertEquals("playback_sample", playback.event)
        assertEquals("true", playback.details["hardwareDecoder"])
        assertEquals("12", playback.details["inputWaitMaxMillis"])
        assertEquals(observed.first().sessionId, playback.sessionId)
        assertTrue(observed.all { it.peer == null && it.component == "toolbox_screen" })
        now += 2_000_000_000L
        trace.playback(ToolboxScreenPlayback(0, 0, null, null, null, null, null, 0, "private clipboard\nsecret", null))
        assertEquals("unknown", observed.last().details["decoder"])
        assertFalse(observed.any { "secret" in it.details.values.joinToString() })
    }

    @Test fun gapAndPlaybackObservationsAreRateLimitedAndCloseIsRecordedOnce() {
        val observed = mutableListOf<ProtocolDiagnostic>()
        var now = 0L
        val trace = ScreenDiagnostics(DiagnosticSink(observed::add), ToolboxBackendKind.ADB, ToolboxScreenOptions(udp = true), { now })
        repeat(1000) {
            trace.event("video_ingress_gap", mapOf("arrivalGapMillis" to "250"))
            trace.event("video_reassembly_gap", mapOf("missingFragments" to "1"))
        }
        assertEquals(1, observed.count { it.event == "video_reassembly_gap" })
        assertEquals(1, observed.count { it.event == "video_ingress_gap" })
        now = 1_000_000_000L
        trace.event("video_ingress_gap")
        assertEquals(2, observed.count { it.event == "video_ingress_gap" })
        trace.finish(ToolboxException("screen_udp_recovery_failed"))
        trace.finish()
        trace.event("video_reset_requested")
        assertEquals(1, observed.count { it.event == "stream_closed" })
        assertEquals("screen_udp_recovery_failed", observed.last().details["reason"])
    }

    @Test fun touchBoundaryMeasurementsDoNotContainCoordinatesTextOrMoveEvents() = runBlocking {
        val observed = mutableListOf<ProtocolDiagnostic>()
        var now = 0L
        val trace = ScreenDiagnostics(DiagnosticSink(observed::add), ToolboxBackendKind.ADB, ToolboxScreenOptions(), { now })
        val down = ToolboxScreenInput.Touch(0, 42, 17, 39, 2560, 1184, .5f)
        trace.controlQueued(down.action, 25)
        trace.control(down) { now += 12_000_000 }
        val record = observed.last()
        assertEquals(mapOf("action" to "0", "writeMillis" to "12"), record.details)
        trace.controlQueued(2, 999)
        trace.control(down.copy(action = 2)) { }
        trace.control(ToolboxScreenInput.Text("private message")) { }
        assertEquals(3, observed.size)
        assertFalse(observed.any { it.details.keys.any { key -> key in setOf("x", "y", "text", "pointerId") } })
    }

    @Test fun brokenObserversCannotBreakMediaOrControl() = runBlocking {
        val trace = ScreenDiagnostics(DiagnosticSink { error("observer failed") }, ToolboxBackendKind.ADB, ToolboxScreenOptions())
        var sent = false
        trace.controlQueued(1, 0)
        trace.control(ToolboxScreenInput.Touch(1, 0, 0, 0, 100, 100, 0f)) { sent = true }
        trace.event("video_waiting_for_key")
        trace.finish()
        assertTrue(sent)
    }
}
