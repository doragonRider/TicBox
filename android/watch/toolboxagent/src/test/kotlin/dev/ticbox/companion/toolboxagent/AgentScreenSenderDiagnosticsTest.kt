package dev.ticbox.companion.toolboxagent

import dev.ticbox.companion.protocol.ScreenUdpTransport
import dev.ticbox.companion.protocol.number
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class AgentScreenSenderDiagnosticsTest {
    @Test fun rejectedTimingReasonsExposeLongPositiveAgeWithoutCallingItEncoderTime() {
        var now = 0L
        val published = LinkedBlockingQueue<JsonObject>()
        val diagnostics = AgentScreenSenderDiagnostics({ published.put(it) }, { now }, timing = true)
        try {
            diagnostics.record(100, false, stats(1, 2, 0), 1_000_000, 3_500_000_000)
            diagnostics.record(100, false, stats(2, 2, 0), 5_000_000, 3_500_000_000)
            diagnostics.record(100, false, stats(3, 2, 0))
            now = 1_000_000_000
            diagnostics.record(100, false, stats(4, 2, 0), 2_000_000, 3_500_000_000)
            val timing = generateSequence { published.poll(2, TimeUnit.SECONDS) }.first { it["kind"]!!.jsonPrimitive.content == "screen_udp_timing" }
            assertEquals(0L, timing.number("frames")); assertNull(timing.number("meanMicros"))
            assertEquals(4L, timing.number("rejectedFrames")); assertEquals(2L, timing.number("overRangeFrames"))
            assertEquals(1L, timing.number("negativeAgeFrames")); assertEquals(1L, timing.number("missingTimestamps"))
            assertEquals(2_500_000L, timing.number("maxOverRangeAgeMicros"))
        } finally { diagnostics.close(); assertTrue(diagnostics.awaitStopped(1_000)) }
    }

    @Test fun sourcePausesAndSendCostsAreSeparateAndNativeBreakdownResetsWithTheWindow() {
        var now = 0L
        val published = LinkedBlockingQueue<JsonObject>()
        val diagnostics = AgentScreenSenderDiagnostics({ published.put(it) }, { now })
        try {
            val native = stats(1, 5, 0).copy(nativeBatch = true, socketSendNanos = 2_000_000,
                socketCopyNanos = 100_000, socketCopyCpuNanos = 80_000, socketNativeNanos = 1_500_000, socketNativeCpuNanos = 1_200_000,
                gsoCalls = 1, gsoDatagrams = 1)
            diagnostics.record(100, false, native, 1_000, 0)
            now = 100_000_000
            diagnostics.record(100, false, native, 101_000, now)
            val gap = assertNotNull(published.poll(2, TimeUnit.SECONDS))
            assertEquals("frame_gap", gap["sample"]!!.jsonPrimitive.content)
            assertEquals(100_000L, gap.number("ptsGapMicros")); assertEquals(100_000L, gap.number("producerGapMicros"))
            assertEquals(101_000L, gap.number("ptsMicros")); assertEquals(100L, gap.number("socketCopyMicros"))
            assertEquals(1_200L, gap.number("socketNativeCpuMicros")); assertEquals(1L, gap.number("gsoDatagrams"))
            now = 2_000_000_000
            diagnostics.record(100, false, native, 201_000, now)
            val window = assertNotNull(published.poll(2, TimeUnit.SECONDS))
            assertEquals(3L, window.number("gsoCalls")); assertEquals(100L, window.number("maxSocketCopyMicros"))
            now = 4_000_000_000
            diagnostics.record(100, false, native.copy(socketCopyNanos = 2_000, gsoCalls = 0, gsoDatagrams = 0, gsoDisabled = true), 0, now)
            val next = assertNotNull(published.poll(2, TimeUnit.SECONDS))
            assertEquals(0L, next.number("gsoCalls")); assertEquals(2L, next.number("maxSocketCopyMicros"))
            assertNull(next.number("maxPtsGapMicros")) // restarted PTS is not a fabricated giant gap
            assertTrue(next.toString().length < 2_048)
        } finally { diagnostics.close(); assertTrue(diagnostics.awaitStopped(1_000)) }
    }
    @Test fun timingIsAPerSecondMeanOfEncodingAndSendingWithoutPacingInItsOwnSlot() {
        var now = 0L
        val published = LinkedBlockingQueue<JsonObject>()
        val diagnostics = AgentScreenSenderDiagnostics({ published.put(it) }, { now }, timing = true)
        try {
            // Read by the Agent 8 ms after capture, then sent in 20 ms of which 15 ms waited for the pacer.
            diagnostics.record(100, false, stats(1, 20, 15), ptsMicros = 1_000_000, ingressNanos = 1_008_000_000)
            // A timestamp from another clock is left out, not read as a giant encode.
            diagnostics.record(100, false, stats(2, 20, 15), ptsMicros = 5_000_000_000, ingressNanos = 1_010_000_000)
            now = 1_000_000_000
            diagnostics.record(100, false, stats(3, 4, 0), ptsMicros = 1_990_000, ingressNanos = 2_000_000_000)
            val timing = generateSequence { published.poll(2, TimeUnit.SECONDS) }
                .first { it["kind"]!!.jsonPrimitive.content == "screen_udp_timing" }
            assertEquals(2L, timing.number("frames"))
            assertEquals((8_000L + 5_000 + 10_000 + 4_000) / 2, timing.number("meanMicros"))
            assertEquals(9_000L, timing.number("meanCaptureToRelayMicros"))
            assertEquals(10_000L, timing.number("maxCaptureToRelayMicros"))
            assertEquals(4_500L, timing.number("meanSendWorkMicros"))
            assertEquals(7_500L, timing.number("meanPacingMicros"))
            assertEquals(1L, timing.number("rejectedFrames"))
            assertTrue(timing.toString().toByteArray().size < 512)
            now = 2_100_000_000
            diagnostics.record(100, false, stats(4, 3, 1), ptsMicros = 3_000_000, ingressNanos = 3_006_000_000)
            val reset = generateSequence { published.poll(2, TimeUnit.SECONDS) }
                .first { it["kind"]!!.jsonPrimitive.content == "screen_udp_timing" }
            assertEquals(1L, reset.number("frames")); assertEquals(0L, reset.number("rejectedFrames"))
            assertEquals(6_000L, reset.number("meanCaptureToRelayMicros")); assertEquals(6_000L, reset.number("maxCaptureToRelayMicros"))
            assertEquals(2_000L, reset.number("meanSendWorkMicros")); assertEquals(1_000L, reset.number("meanPacingMicros"))
        } finally { diagnostics.close(); assertTrue(diagnostics.awaitStopped(1_000)) }
    }
    @Test fun diagnosticsRequireExplicitUdpOptIn() {
        assertFalse(ScreenRequest(buildJsonObject { }).udpDiagnostics)
        assertFalse(ScreenRequest(buildJsonObject { put("udp", true) }).udpDiagnostics)
        assertFalse(ScreenRequest(buildJsonObject { put("udpDiagnostics", true) }).udpDiagnostics)
        assertTrue(ScreenRequest(buildJsonObject { put("udp", true); put("udpDiagnostics", true) }).udpDiagnostics)
    }

    @Test fun slowFramesAreMetadataOnlyAndRateLimitedWhileWindowsPreserveMaxima() {
        var now = 0L
        val published = LinkedBlockingQueue<JsonObject>()
        val diagnostics = AgentScreenSenderDiagnostics({ published.put(it) }, { now })
        try {
            diagnostics.record(1_000, true, stats(7, sendMillis = 80, waitMillis = 60))
            val first = assertNotNull(published.poll(2, TimeUnit.SECONDS))
            assertEquals(setOf("kind", "version", "sample", "sequence", "frameBytes", "keyFrame", "datagrams", "wireBytes", "sendMicros", "pacingWaitMicros", "parityDatagrams", "fecEncodeMicros", "encryptMicros", "socketSendMicros", "sendCalls", "nativeBatch", "fecImplementation", "mediaSecurity"), first.keys)
            assertEquals("screen_udp_sender", first["kind"]!!.jsonPrimitive.content)
            assertEquals("slow_frame", first["sample"]!!.jsonPrimitive.content)
            assertEquals(80_000L, first.number("sendMicros"))
            assertEquals(60_000L, first.number("pacingWaitMicros"))
            assertEquals("true", first["keyFrame"]!!.jsonPrimitive.content)
            assertEquals(0L, first.number("encryptMicros"))
            assertEquals("kotlin", first["fecImplementation"]!!.jsonPrimitive.content)
            repeat(100) {
                now += 1_000_000L
                diagnostics.record(2_000, false, stats(it + 8L, sendMillis = 90, waitMillis = 70))
            }
            assertNull(published.poll(20, TimeUnit.MILLISECONDS))
            now = 1_000_000_000L
            diagnostics.record(3_000, false, stats(108, sendMillis = 100, waitMillis = 75))
            assertEquals(108L, assertNotNull(published.poll(2, TimeUnit.SECONDS)).number("sequence"))
            now = 2_000_000_000L
            diagnostics.record(4_000, true, stats(109, sendMillis = 120, waitMillis = 95))
            val window = assertNotNull(published.poll(2, TimeUnit.SECONDS))
            assertEquals(setOf("kind", "version", "sample", "elapsedMillis", "frames", "encodedBytes", "keyFrames", "maxFrameBytes", "maxSendMicros", "maxPacingWaitMicros", "parityDatagrams", "maxFecEncodeMicros", "maxEncryptMicros", "maxSocketSendMicros", "sendCalls", "datagrams", "nativeBatch", "fecImplementation", "mediaSecurity"), window.keys)
            assertEquals("window", window["sample"]!!.jsonPrimitive.content)
            assertEquals(103L, window.number("frames"))
            assertEquals(208_000L, window.number("encodedBytes"))
            assertEquals(2L, window.number("keyFrames"))
            assertEquals(4_000L, window.number("maxFrameBytes"))
            assertEquals(120_000L, window.number("maxSendMicros"))
            assertEquals(95_000L, window.number("maxPacingWaitMicros"))
            assertNull(published.poll(20, TimeUnit.MILLISECONDS)) // No second slow sample for the same frame.
        } finally { diagnostics.close(); assertTrue(diagnostics.awaitStopped(1_000)) }
    }

    @Test fun cpuSamplesAndMediaModeAreReportedWithoutInventingUnsupportedClocks() {
        val published = LinkedBlockingQueue<JsonObject>()
        val diagnostics = AgentScreenSenderDiagnostics({ published.put(it) }, { 0L })
        try {
            diagnostics.record(1_000, false, stats(1, 80, 10).copy(mediaSecurity = "hmac_sha256_128",
                encryptCpuNanos = 3_000_000, socketSendCpuNanos = 1_000_000))
            val value = assertNotNull(published.poll(2, TimeUnit.SECONDS))
            assertEquals(3_000L, value.number("encryptCpuMicros"))
            assertEquals(1_000L, value.number("socketSendCpuMicros"))
            assertEquals("hmac_sha256_128", value["mediaSecurity"]!!.jsonPrimitive.content)
        } finally { diagnostics.close(); assertTrue(diagnostics.awaitStopped(1_000)) }
    }

    @Test fun fastFrameWindowsStillReportAndResetTheirCounters() {
        var now = 0L
        val published = LinkedBlockingQueue<JsonObject>()
        val diagnostics = AgentScreenSenderDiagnostics({ published.put(it) }, { now })
        try {
            diagnostics.record(100, false, stats(0, 5, 2))
            now = 2_000_000_000
            diagnostics.record(200, true, stats(1, 10, 3))
            val first = assertNotNull(published.poll(2, TimeUnit.SECONDS))
            assertEquals("window", first["sample"]!!.jsonPrimitive.content)
            assertEquals(2L, first.number("frames")); assertEquals(300L, first.number("encodedBytes"))
            now = 4_000_000_000
            diagnostics.record(50, false, stats(2, 1, 0))
            val next = assertNotNull(published.poll(2, TimeUnit.SECONDS))
            assertEquals(1L, next.number("frames")); assertEquals(50L, next.number("encodedBytes"))
            assertEquals(1_000L, next.number("maxSendMicros"))
        } finally { diagnostics.close(); assertTrue(diagnostics.awaitStopped(1_000)) }
    }

    @Test fun blockedAdbPublisherCannotBlockTheVideoProducerOrAccumulateSnapshots() {
        var now = 0L
        val entered = CountDownLatch(1)
        val releaseDescriptor = CountDownLatch(1)
        val producerFinished = CountDownLatch(1)
        val published = LinkedBlockingQueue<JsonObject>()
        val diagnostics = AgentScreenSenderDiagnostics({ sample ->
            entered.countDown()
            // Model a native descriptor write that does not unblock merely on interruption.
            while (releaseDescriptor.count > 0) try { releaseDescriptor.await() } catch (_: InterruptedException) { }
            published.put(sample)
        }, { now }, timing = true)
        try {
            diagnostics.record(1, false, stats(0, 50, 30))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val producer = Thread {
                repeat(2_000) {
                    now += 1_000_000_000
                    diagnostics.record(100, false, stats(it + 1L, 50, 30))
                    diagnostics.event(buildJsonObject { put("kind", "screen_encoder"); put("version", 1); put("stage", "configured") })
                    assertTrue(diagnostics.pendingSnapshots <= 3)
                }
                diagnostics.close() // Must not wait for the blocked diagnostic descriptor.
                producerFinished.countDown()
            }.apply { start() }
            assertTrue(producerFinished.await(2, TimeUnit.SECONDS))
            assertEquals(0, diagnostics.pendingSnapshots)
            releaseDescriptor.countDown()
            producer.join(1_000)
            assertTrue(diagnostics.awaitStopped(1_000))
            assertEquals(1, published.size) // Only the in-flight write; pending snapshots were discarded.
        } finally { releaseDescriptor.countDown(); diagnostics.close(); assertTrue(diagnostics.awaitStopped(1_000)) }
    }

    @Test fun encoderStagesSentBackToBackKeepTheirOwnSlots() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val published = LinkedBlockingQueue<JsonObject>()
        val diagnostics = AgentScreenSenderDiagnostics({ sample ->
            entered.countDown(); release.await(); published.put(sample)
        }, { 0L })
        fun encoder(stage: String, frames: Int) = buildJsonObject {
            put("kind", "screen_encoder"); put("version", 1); put("stage", stage); put("frames", frames)
        }
        try {
            diagnostics.event(encoder("configured", 0))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            diagnostics.event(encoder("vendor_parameters", 0))
            diagnostics.event(encoder("output_sample", 1))
            diagnostics.event(encoder("input_sample", 1))
            diagnostics.event(encoder("output_sample", 2)) // The newer window replaces the waiting one.
            assertEquals(3, diagnostics.pendingSnapshots)
            release.countDown()
            val stages = List(4) { assertNotNull(published.poll(2, TimeUnit.SECONDS)) }
            assertEquals(listOf("configured", "vendor_parameters", "input_sample", "output_sample"), stages.map { it["stage"]!!.jsonPrimitive.content })
            assertEquals("2", stages.last()["frames"]!!.jsonPrimitive.content)
        } finally { release.countDown(); diagnostics.close(); assertTrue(diagnostics.awaitStopped(1_000)) }
    }

    @Test fun failedDiagnosticsStopReportingWithoutFailingSubsequentFrames() {
        val diagnostics = AgentScreenSenderDiagnostics({ throw IOException("fixture closed") }, { 0L })
        diagnostics.record(1, false, stats(0, 50, 30))
        assertTrue(diagnostics.awaitStopped(1_000))
        diagnostics.record(1, false, stats(1, 50, 30))
        assertEquals(0, diagnostics.pendingSnapshots)
        diagnostics.close()
    }

    private fun stats(sequence: Long, sendMillis: Long, waitMillis: Long) = ScreenUdpTransport.SendStats(
        sequence = sequence, payloadBytes = 1_013, datagrams = 1, wireBytes = 1_061,
        sendNanos = sendMillis * 1_000_000, pacingWaitNanos = waitMillis * 1_000_000,
    )
}
