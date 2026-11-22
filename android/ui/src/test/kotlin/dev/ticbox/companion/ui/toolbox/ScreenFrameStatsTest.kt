package dev.ticbox.companion.ui.toolbox

import dev.ticbox.companion.api.ToolboxScreenReceipt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ScreenFrameStatsTest {
    @Test
    fun sourceRelayAndReceiveIntervalsSeparateUpstreamJitterWithoutSynchronizingDeviceClocks() {
        val stats = ScreenFrameStats(udp = true, targetFps = 120)
        fun frame(
            pts: Long,
            relay: Long,
            received: Long,
            arrived: Long,
            decoded: Long,
            shown: Long,
        ) {
            stats.arrived(pts, 100, ms(arrived), ToolboxScreenReceipt(ms(received), 0, relay))
            stats.decoded(pts, ms(decoded))
            stats.rendered(pts, ms(shown), ms(shown))
        }
        // Source and phone clocks differ by about seven seconds; only same-clock intervals are
        // compared.
        frame(1_000_000, 1_010_000, 8_000, 8_002, 8_004, 8_010)
        frame(1_008_000, 1_025_000, 8_030, 8_037, 8_042, 8_050)
        val pair = requireNotNull(stats.summary(ms(8_050)).largestPipelineExpansion)
        assertEquals(8_000L, pair.ptsGapMicros)
        assertEquals(15_000L, pair.sourceRelayGapMicros)
        assertEquals(7_000L, pair.captureToRelayChangeMicros)
        assertEquals(15_000L, pair.deliveryChangeMicros)
        assertEquals(5_000L, pair.queueChangeMicros)
        assertEquals(40_000L, pair.intervalMicros)
        assertEquals(
            pair.intervalMicros,
            requireNotNull(pair.ptsGapMicros) +
                requireNotNull(pair.captureToRelayChangeMicros) +
                requireNotNull(pair.deliveryChangeMicros) +
                requireNotNull(pair.queueChangeMicros) +
                pair.decodeChangeMicros +
                pair.displayChangeMicros,
        )
        frame(1_016_000, 20_000_000, 8_100, 8_102, 8_104, 8_110)
        val unmeasured = requireNotNull(stats.summary(ms(8_110)).largestPipelineExpansion)
        assertNull(unmeasured.sourceRelayGapMicros)
        assertNull(unmeasured.captureToRelayChangeMicros)
        assertNull(unmeasured.deliveryChangeMicros)
    }

    @Test
    fun idleDoesNotHideSmallerPipelineExpansionAndUdpQueueChangesStayPaired() {
        val stats = ScreenFrameStats(udp = true, targetFps = 120, windowNanos = ms(1_000))
        fun frame(pts: Long, received: Long, arrived: Long, decoded: Long, shown: Long) {
            stats.arrived(pts * 1_000, 100, ms(arrived), ToolboxScreenReceipt(ms(received), 0))
            stats.decoded(pts * 1_000, ms(decoded))
            stats.rendered(pts * 1_000, ms(shown), ms(shown))
        }
        frame(0, 100, 102, 104, 110)
        frame(500, 600, 602, 604, 610) // A long source gap, with no additional pipeline delay.
        frame(
            508,
            620,
            637,
            642,
            650,
        ) // 20ms receiving + 15ms queue growth + 3ms decode + 2ms display.
        val observed = stats.summary(ms(650)).playback()
        assertEquals(500_000L, observed.longestPresentationGap?.intervalMicros)
        assertEquals(
            500_000L,
            observed.presentedIntervalP99Micros,
        ) // The original statistics still include idle.
        assertEquals(2.0, observed.presentedLow1Fps)
        val expansion = requireNotNull(observed.largestPipelineExpansion)
        assertEquals(508_000L, expansion.framePtsMicros)
        assertEquals(40_000L, expansion.intervalMicros)
        assertEquals(8_000L, expansion.ptsGapMicros)
        assertEquals(20_000L, expansion.receiveGapMicros)
        assertEquals(15_000L, expansion.queueChangeMicros)
        assertEquals(35_000L, expansion.arrivalGapMicros)
        assertEquals(3_000L, expansion.decodeChangeMicros)
        assertEquals(2_000L, expansion.displayChangeMicros)
        assertNull(stats.summary(ms(1_700)).playback().largestPipelineExpansion)
    }

    @Test
    fun receiptCatchUpIsSignedAndMissingOrFutureReceiptsAreNotFabricated() {
        val stats = ScreenFrameStats(udp = true)
        fun frame(pts: Long, arrived: Long, received: Long?) {
            stats.arrived(
                pts * 1_000,
                100,
                ms(arrived),
                received?.let { ToolboxScreenReceipt(ms(it), 0) },
            )
            stats.decoded(pts * 1_000, ms(arrived + 1))
            stats.rendered(pts * 1_000, ms(arrived + 2), ms(arrived + 2))
        }
        frame(0, 100, 80)
        frame(8, 120, 118)
        val pair = requireNotNull(stats.summary(ms(125)).largestPipelineExpansion)
        assertEquals(38_000L, pair.receiveGapMicros)
        assertEquals(-18_000L, pair.queueChangeMicros)
        // Missing and impossible future receipts cannot be treated as zero queueing.
        frame(16, 200, null)
        var missing = requireNotNull(stats.summary(ms(205)).largestPipelineExpansion)
        assertNull(missing.receiveGapMicros)
        assertNull(missing.queueChangeMicros)
        frame(24, 300, 301)
        missing = requireNotNull(stats.summary(ms(305)).largestPipelineExpansion)
        assertNull(missing.receiveGapMicros)
        assertNull(missing.queueChangeMicros)
        // A backwards PTS must not become a huge apparent positive pipeline expansion.
        frame(1, 1_000, 999)
        assertEquals(24_000L, stats.summary(ms(1_005)).largestPipelineExpansion?.framePtsMicros)
    }

    @Test
    fun schedulerCountsHaveExplicitCausesWhileRollingDelaysExpireAndPresentationStaysUnfiltered() {
        val stats = ScreenFrameStats(udp = true, targetFps = 120, windowNanos = ms(1_000))
        stats.frame(0, 0, 3, 8)
        stats.arrived(100_000, 10, ms(100))
        stats.decoded(100_000, ms(105))
        stats.scheduledOutput(100_000, ms(113))
        stats.rendered(100_000, ms(116), ms(116))
        stats.outputObservation(ScreenOutputObservation.VSYNC, ms(9), ms(116))
        stats.outputObservation(
            ScreenOutputObservation.VSYNC,
            -1,
            ms(124),
        ) // Clock-incomparable delay.
        stats.outputObservation(ScreenOutputObservation.EMPTY_VSYNC, 0, ms(124))
        stats.outputObservation(ScreenOutputObservation.QUEUE_OVERFLOW, 0, ms(125))
        stats.outputObservation(ScreenOutputObservation.WATCHDOG_DROP, 0, ms(126))
        stats.outputObservation(ScreenOutputObservation.WATCHDOG_PRESENT, 0, ms(126))
        val playback = stats.summary(ms(130)).playback()
        val scheduler = requireNotNull(playback.outputScheduler)
        assertEquals(2L, scheduler.vsyncCallbacks)
        assertEquals(1L, scheduler.emptyVsyncCallbacks)
        assertEquals(1L, scheduler.lateVsyncCallbacks)
        assertEquals(1L, scheduler.queueOverflowDrops)
        assertEquals(1L, scheduler.watchdogDrops)
        assertEquals(1L, scheduler.watchdogPresentations)
        assertEquals(9_000L, scheduler.callbackDelayMaxMicros)
        assertEquals(8_000L, scheduler.outputQueueWaitMaxMicros)
        assertEquals(108_000L, playback.presentedIntervalP99Micros)
        val expired = requireNotNull(stats.summary(ms(1_500)).playback().outputScheduler)
        assertEquals(
            scheduler.copy(callbackDelayMaxMicros = null, outputQueueWaitMaxMicros = null),
            expired,
        )
    }

    @Test
    fun longIntervalsKeepTheSameFramePairAndExposeWhichLocalStageChanged() {
        val stats = ScreenFrameStats(udp = true, windowNanos = ms(1_000))
        stats.frame(0, 1_000, 1_004, 1_010)
        // Same 100ms source spacing, unchanged local latencies: do not call it a local stall.
        stats.frame(100, 1_100, 1_104, 1_110)
        var pair = requireNotNull(stats.summary(ms(1_110)).playback().longestPresentationGap)
        assertEquals(100_000L, pair.intervalMicros)
        assertEquals(100_000L, pair.ptsGapMicros)
        assertEquals(100_000L, pair.arrivalGapMicros)
        assertEquals(0L, pair.decodeChangeMicros)
        assertEquals(0L, pair.displayChangeMicros)
        // Source now advances 8ms; the received/decoded/presented pair explains a 150ms gap.
        stats.frame(108, 1_208, 1_242, 1_260)
        pair = requireNotNull(stats.summary(ms(1_260)).playback().longestPresentationGap)
        assertEquals(108_000L, pair.framePtsMicros)
        assertEquals(8_000L, pair.ptsGapMicros)
        assertEquals(108_000L, pair.arrivalGapMicros)
        assertEquals(30_000L, pair.decodeChangeMicros)
        assertEquals(12_000L, pair.displayChangeMicros)
        assertEquals(
            pair.intervalMicros,
            pair.arrivalGapMicros + pair.decodeChangeMicros + pair.displayChangeMicros,
        )
        assertEquals(150_000L, stats.summary(ms(1_260)).playback().presentedIntervalP99Micros)
        assertNull(stats.summary(ms(2_300)).playback().longestPresentationGap)
    }

    @Test
    fun pairedIntervalsPreserveCatchUpAndDiscardedFramesInsteadOfPretendingSourceWasIdle() {
        val stats = ScreenFrameStats(udp = true)
        stats.frame(0, 0, 50, 80)
        stats.arrived(8_000, 10, ms(8))
        stats.decoded(8_000, ms(60))
        stats.discardedOutput(8_000)
        stats.frame(100, 100, 102, 108)
        val pair = requireNotNull(stats.summary(ms(108)).playback().longestPresentationGap)
        assertEquals(
            100_000L,
            pair.ptsGapMicros,
        ) // includes skipped frames, not proof of static content
        assertEquals(-48_000L, pair.decodeChangeMicros)
        assertEquals(-24_000L, pair.displayChangeMicros)
        assertEquals(28_000L, pair.intervalMicros)
    }

    @Test
    fun eachTickCountsWhatWasShownAndThisPhonesShareOfEachDecodedFrame() {
        val stats = ScreenFrameStats(udp = true)
        // Complete 2 ms before it reached the decoder, after 1 ms of receive work; decoded 10 ms
        // on.
        stats.arrived(1_000, 10, ms(100), ToolboxScreenReceipt(ms(98), ms(1)))
        stats.decoded(1_000, ms(110))
        stats.rendered(1_000, ms(112), ms(112))
        // Without a receipt, its time starts as it reaches the decoder.
        stats.arrived(2_000, 10, ms(150))
        stats.decoded(2_000, ms(156))
        stats.rendered(2_000, ms(160), ms(160))
        val first = stats.tick(ms(600))
        assertEquals(4.0, first.shownFps, 0.001) // Two frames in the half second since the first.
        assertEquals((13_000L + 6_000) / 2, first.decoderMicros)
        val idle = stats.tick(ms(1_600))
        assertEquals(0.0, idle.shownFps)
        assertNull(idle.decoderMicros)
    }

    private fun ms(value: Long) = value * 1_000_000

    private fun ScreenFrameStats.frame(ptsMs: Long, arrived: Long, decoded: Long, shown: Long) {
        arrived(ptsMs * 1_000, bytes = 0, ms(arrived))
        decoded(ptsMs * 1_000, ms(decoded))
        rendered(ptsMs * 1_000, ms(shown), ms(shown))
    }

    @Test
    fun eachStageIsMeasuredOnItsOwn() {
        val stats = ScreenFrameStats()
        stats.frame(ptsMs = 0, arrived = 1_000, decoded = 1_004, shown = 1_014)
        stats.frame(ptsMs = 16, arrived = 1_016, decoded = 1_020, shown = 1_030)
        // Made on time, but 100 ms late getting here.
        stats.frame(ptsMs = 32, arrived = 1_132, decoded = 1_140, shown = 1_162)
        val summary = stats.summary(ms(1_200))
        assertEquals(ScreenStage(16, 16), summary.source)
        assertNull(summary.receivedFrameSpacing)
        assertFalse(summary.udp)
        assertEquals(100, summary.arrival?.maxMillis)
        assertEquals(8, summary.decode?.maxMillis)
        assertEquals(22, summary.display?.maxMillis)
        assertEquals(3, summary.receivedPerSecond)
        assertEquals(3, summary.shownPerSecond)
    }

    @Test
    fun udpRetainedFrameGapsAreNotReportedAsDeviceOutput() {
        val stats = ScreenFrameStats(udp = true)
        assertTrue(stats.summary(ms(0)).udp)
        stats.frame(ptsMs = 0, arrived = 1_000, decoded = 1_004, shown = 1_014)
        // UDP loss/recovery may discard intervening frames even while the encoder keeps working.
        stats.frame(ptsMs = 1_000, arrived = 2_000, decoded = 2_004, shown = 2_014)
        val summary = stats.summary(ms(2_100))
        assertNull(summary.source)
        assertEquals(ScreenStage(1_000, 1_000), summary.receivedFrameSpacing)
        assertEquals(4, summary.decode?.maxMillis)
        assertEquals(10, summary.display?.maxMillis)
        assertTrue(summary.udp)
    }

    @Test
    fun aHitchLeavesTheNumbersOnceItsOutOfTheWindow() {
        val stats = ScreenFrameStats(windowNanos = ms(1_000))
        stats.frame(ptsMs = 0, arrived = 0, decoded = 1, shown = 2)
        stats.frame(ptsMs = 500, arrived = 500, decoded = 501, shown = 502)
        assertEquals(500, stats.summary(ms(500)).source?.maxMillis)
        stats.frame(ptsMs = 516, arrived = 2_000, decoded = 2_001, shown = 2_002)
        assertEquals(ScreenStage(16, 16), stats.summary(ms(2_002)).source)
    }

    @Test
    fun framesTheDisplayNeverReportedLeaveThatStageEmpty() {
        val stats = ScreenFrameStats()
        stats.arrived(0, bytes = 0, ms(0))
        stats.decoded(0, ms(1))
        val summary = stats.summary(ms(1_000))
        assertNull(summary.display)
        assertEquals(0, summary.shownPerSecond)
        assertEquals(1, summary.decode?.maxMillis)
    }

    @Test
    fun aDisplayTimeFromAnotherClockFallsBackToWhenItWasReported() {
        val stats = ScreenFrameStats()
        stats.arrived(0, bytes = 0, ms(1_000))
        stats.decoded(0, ms(1_004))
        stats.rendered(0, renderedAt = -ms(5_000_000), now = ms(1_020))
        stats.arrived(16_000, bytes = 0, ms(1_016))
        stats.decoded(16_000, ms(1_020))
        stats.rendered(16_000, renderedAt = ms(5_000_000), now = ms(1_030))
        val summary = stats.summary(ms(1_040))
        assertEquals(16, summary.display?.maxMillis)
        assertEquals(2, summary.shownPerSecond)
    }

    @Test
    fun theBitRateCountsWhatArrivedInTheWindow() {
        val stats = ScreenFrameStats(windowNanos = ms(2_000))
        stats.arrived(0, bytes = 1_000_000, ms(0))
        stats.arrived(16_000, bytes = 500_000, ms(1_000))
        stats.arrived(32_000, bytes = 500_000, ms(2_000))
        assertEquals(8.0, stats.summary(ms(2_000)).receivedMbps)
        assertEquals(4.0, stats.summary(ms(2_500)).receivedMbps)
        assertEquals(0.0, stats.summary(ms(5_000)).receivedMbps)
    }

    @Test
    fun playbackReportsActiveDecoderWaitAndPersistentMetadataWithoutChangingFrameCounts() {
        val stats = ScreenFrameStats(windowNanos = ms(1_000), udp = true)
        stats.decoder("c2.test.decoder", true)
        stats.frame(ptsMs = 0, arrived = 0, decoded = 4, shown = 10)
        stats.frame(ptsMs = 16, arrived = 20, decoded = 25, shown = 33)
        stats.oversizedFrame()
        stats.inputWaitStarted(ms(40))
        val ongoing = stats.summary(ms(640)).playback()
        assertEquals(600, ongoing.inputWaitMaxMillis)
        assertEquals(2, ongoing.receivedFps)
        assertEquals(2, ongoing.renderedFps)
        assertEquals(stats.summary(ms(640)).receivedMbps, ongoing.receivedMbps)
        assertEquals(16, ongoing.frameGapMaxMillis)
        assertEquals(4, ongoing.arrivalVariationMaxMillis)
        assertEquals(5, ongoing.decodeMaxMillis)
        assertEquals(8, ongoing.displayMaxMillis)
        assertEquals(1L, ongoing.oversizedFrames)
        assertEquals("c2.test.decoder", ongoing.decoderName)
        assertEquals(true, ongoing.hardwareDecoder)
        stats.inputWaitFinished(ms(740))
        assertEquals(700, stats.summary(ms(750)).playback().inputWaitMaxMillis)
        val expired = stats.summary(ms(2_000)).playback()
        assertNull(expired.inputWaitMaxMillis)
        assertEquals(1L, expired.oversizedFrames)
        assertEquals("c2.test.decoder", expired.decoderName)
        assertEquals(0, expired.receivedFps)
    }

    @Test
    fun discardedDecodedOutputsAreSeparateFromNetworkLossAndNeverCountAsShown() {
        val stats = ScreenFrameStats(udp = true)
        stats.arrived(0, 100, ms(0))
        stats.decoded(0, ms(1))
        stats.discardedOutput(0)
        stats.rendered(0, ms(10), ms(10)) // A stale renderer callback cannot resurrect it.
        stats.frame(ptsMs = 16, arrived = 16, decoded = 20, shown = 33)
        val summary = stats.summary(ms(100))
        assertEquals(1L, summary.displayDroppedFrames)
        assertEquals(1L, summary.playback().displayDroppedFrames)
        assertEquals(2, summary.receivedPerSecond)
        assertEquals(1, summary.shownPerSecond)
        assertEquals(13, summary.display?.maxMillis)
        assertTrue(summary.udp)
    }

    @Test
    fun presentationPercentilesKeepLongAndIdleGapsAndUseSlowestOnePercentMean() {
        val stats = ScreenFrameStats(windowNanos = ms(10_000), targetFps = 120)
        var time = 0L
        stats.frame(0, 0, 0, 0)
        repeat(200) { index ->
            val interval =
                when (index) {
                    198 -> 100L
                    199 -> 300L
                    else -> 8L
                }
            time += interval
            stats.frame(time, time, time, time)
        }
        val result = stats.summary(ms(time)).playback()
        assertEquals(200, result.presentedIntervalSamples)
        assertEquals(8_000L, result.presentedIntervalP50Micros)
        assertEquals(8_000L, result.presentedIntervalP95Micros)
        assertEquals(8_000L, result.presentedIntervalP99Micros)
        assertEquals(300_000L, result.presentedIntervalMaxMicros)
        assertEquals(
            5.0,
            result.presentedLow1Fps,
        ) // mean of 100 + 300ms = 200ms, not reciprocal P99.
        assertEquals(2, result.presentedLongIntervals)
        assertEquals(0, stats.summary(ms(time + 11_000)).playback().presentedIntervalSamples)
    }

    @Test
    fun incompatiblePresentationClockDoesNotProduceInventedFrameIntervals() {
        val stats = ScreenFrameStats()
        stats.frame(0, 0, 0, 0)
        stats.arrived(16_000, 100, ms(16))
        stats.decoded(16_000, ms(17))
        stats.rendered(16_000, -100, ms(20))
        stats.frame(32, 32, 33, 40)
        val result = stats.summary(ms(100)).playback()
        assertEquals(0, result.presentedIntervalSamples)
        assertNull(result.presentedIntervalP99Micros)
        assertEquals(1L, result.invalidPresentationTimes)
        assertNull(result.longestPresentationGap)
        stats.displayRefreshRate(120.0)
        assertEquals(120.0, stats.summary(ms(100)).playback().displayRefreshHz)
        stats.displayRefreshRate(Double.NaN)
        assertNull(stats.summary(ms(100)).playback().displayRefreshHz)
    }

    @Test
    fun aHugeValueStopsAtTheLargestItCanShow() {
        val stats = ScreenFrameStats(windowNanos = Long.MAX_VALUE)
        stats.arrived(0, bytes = 0, 0)
        stats.arrived(3_000_000_000_000_000, bytes = 0, 1)
        assertEquals(Int.MAX_VALUE, stats.summary(1).source?.maxMillis)
    }
}
