package dev.ticbox.companion.ui.toolbox

import dev.ticbox.companion.api.ToolboxScreenOutputScheduler
import dev.ticbox.companion.api.ToolboxScreenPlayback
import dev.ticbox.companion.api.ToolboxScreenPresentationGap
import dev.ticbox.companion.api.ToolboxScreenReceipt
import kotlin.math.ceil
import kotlin.math.roundToInt

/** A timing summary over the last few seconds. */
data class ScreenStage(val p95Millis: Int, val maxMillis: Int)

/** Actual local presentation intervals, including idle time; not an optical panel measurement. */
data class ScreenPresentationSummary(
    val samples: Int,
    val p50Micros: Long,
    val p95Micros: Long,
    val p99Micros: Long,
    val maxMicros: Long,
    /** Reciprocal of the mean slowest ceil(1% * samples) intervals; not simply 1/P99. */
    val low1Fps: Double,
    val overTwoFramePeriods: Int,
)

/** What happened since the previous tick, for the overlay's graphs. */
data class ScreenFrameTick(
    val shownFps: Double,
    /**
     * Mean per decoded frame of this phone's part: receiving, authenticating, decrypting and
     * reassembling it where the transport says, then until the decoder gives it back. Null when no
     * frame was decoded.
     */
    val decoderMicros: Long?,
)

/** Null means that timing is unavailable or no sample exists in the window. */
data class ScreenFrameSummary(
    val receivedPerSecond: Int,
    val shownPerSecond: Int,
    /** The video that got here, which is what the picture's quality actually has to work with. */
    val receivedMbps: Double,
    /**
     * The gaps between frames as the mirrored device made them. A still screen makes few, and so
     * does an encoder held up by whatever comes after it. Unknown for UDP: missing or discarded
     * frames prevent attributing the observed spacing to the device.
     */
    val source: ScreenStage?,
    /**
     * How much later than the least delayed frame each one got here, from being captured to
     * reaching the decoder. Falling behind at a steady amount reads as nothing.
     */
    val arrival: ScreenStage?,
    /** From getting here to coming out of the decoder, including waiting to go in. */
    val decode: ScreenStage?,
    /** From coming out of the decoder to the screen. */
    val display: ScreenStage?,
    /**
     * UDP PTS gaps between frames retained for decoding, including network loss and local skips.
     */
    val receivedFrameSpacing: ScreenStage? = null,
    /** Binds the interpretation to this session, even before enough frames exist for a sample. */
    val udp: Boolean = false,
    /** Waiting for a decoder input buffer, including a wait that is still in progress. */
    val inputWait: ScreenStage? = null,
    /** Cumulative metadata for this screen session; no media or application contents. */
    val oversizedFrames: Long = 0,
    val decoderName: String? = null,
    val hardwareDecoder: Boolean? = null,
    /** H.264 only: whether its parameter sets still declare that no picture is reordered. */
    val h264LowDelay: Boolean? = null,
    /** Decoded display outputs discarded by pacing/retirement, separate from network loss. */
    val displayDroppedFrames: Long = 0,
    val presentation: ScreenPresentationSummary? = null,
    val invalidPresentationTimes: Long = 0,
    val displayRefreshHz: Double? = null,
    val longestPresentationGap: ToolboxScreenPresentationGap? = null,
    val outputScheduler: ToolboxScreenOutputScheduler? = null,
    val largestPipelineExpansion: ToolboxScreenPresentationGap? = null,
)

/**
 * Reports observed frame timestamps and local decode/display timing. UDP only supplies retained
 * frames, so their spacing cannot identify a capture or encoder stall. The devices' clocks can't be
 * compared, so arrival delay is only known relative to other frames.
 */
class ScreenFrameStats(
    private val windowNanos: Long = 5_000_000_000L,
    private val udp: Boolean = false,
    private val targetFps: Int = 60,
) {
    private class Timing(
        val arrived: Long,
        val ready: Long,
        val work: Long,
        val received: Long?,
        val sourceRelay: Long?,
    ) {
        var decoded: Long? = null
    }

    /** Values over the window, oldest first. */
    private inner class Series {
        private val times = ArrayDeque<Long>()
        private val values = ArrayDeque<Long>()

        fun add(now: Long, value: Long) {
            times.addLast(now)
            values.addLast(value)
            trim(now)
        }

        fun trim(now: Long) {
            while (times.isNotEmpty() && times.first() < now - windowNanos) {
                times.removeFirst()
                values.removeFirst()
            }
        }

        val size
            get() = times.size

        fun values(): List<Long> = values.toList()
    }

    private val pending =
        object : LinkedHashMap<Long, Timing>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Timing>?) =
                size > MAX_PENDING
        }
    private val source = Series()
    private val arrival = Series()
    private val received = Series()
    private val decode = Series()
    private val display = Series()
    private val inputWait = Series()
    private val presentation = Series()
    private val callbackDelay = Series()
    private val outputQueueWait = Series()
    private var vsyncCallbacks = 0L
    private var emptyVsyncCallbacks = 0L
    private var lateVsyncCallbacks = 0L
    private var queueOverflowDrops = 0L
    private var watchdogDrops = 0L
    private var watchdogPresentations = 0L
    private var previousPresented: Long? = null

    private data class Presented(
        val pts: Long,
        val arrived: Long,
        val decoded: Long,
        val shown: Long,
        val received: Long?,
        val sourceRelay: Long?,
    )

    private var previousFrame: Presented? = null
    private val pairedGaps = ArrayDeque<Pair<Long, ToolboxScreenPresentationGap>>()
    private var invalidPresentationTimes = 0L
    private var displayRefreshHz: Double? = null

    init {
        require(targetFps in 1..120)
    }

    private var waitingSince: Long? = null
    private var oversizedFrames = 0L
    private var displayDroppedFrames = 0L
    private var decoderName: String? = null
    private var hardwareDecoder: Boolean? = null
    private var h264LowDelay: Boolean? = null
    private var previousPts: Long? = null
    private var started: Long? = null
    private var lastTick: Long? = null
    private var tickShown = 0
    private var tickDecoded = 0
    private var tickDecoderNanos = 0L

    @Synchronized
    fun arrived(ptsMicros: Long, bytes: Int, now: Long, receipt: ToolboxScreenReceipt? = null) {
        if (started == null) started = now
        val gap = previousPts?.let { (ptsMicros - it) * 1_000 }
        if (gap != null && gap > 0) source.add(now, gap)
        previousPts = ptsMicros
        arrival.add(now, now - ptsMicros * 1_000)
        received.add(now, bytes.toLong())
        // A receipt from before this frame's queueing is the only kind on this phone's clock.
        val ready = receipt?.takeIf { it.completedNanos <= now }
        pending[ptsMicros] =
            Timing(
                now,
                ready?.completedNanos ?: now,
                ready?.workNanos ?: 0,
                ready?.completedNanos,
                ready?.sourceRelayMicros?.takeIf { it >= 0 && it - ptsMicros in 0..1_000_000 },
            )
    }

    @Synchronized
    fun decoded(ptsMicros: Long, now: Long) {
        val timing = pending[ptsMicros] ?: return
        timing.decoded = now
        decode.add(now, now - timing.arrived)
        tickDecoded++
        tickDecoderNanos += now - timing.ready + timing.work
    }

    @Synchronized
    internal fun outputObservation(event: ScreenOutputObservation, durationNanos: Long, now: Long) {
        when (event) {
            ScreenOutputObservation.VSYNC -> {
                vsyncCallbacks++
                if (durationNanos >= 0) {
                    callbackDelay.add(now, durationNanos)
                    if (durationNanos >= NANOS_PER_SECOND / targetFps) lateVsyncCallbacks++
                }
            }
            ScreenOutputObservation.EMPTY_VSYNC -> emptyVsyncCallbacks++
            ScreenOutputObservation.QUEUE_OVERFLOW -> queueOverflowDrops++
            ScreenOutputObservation.WATCHDOG_DROP -> watchdogDrops++
            ScreenOutputObservation.WATCHDOG_PRESENT -> watchdogPresentations++
        }
    }

    @Synchronized
    internal fun scheduledOutput(ptsMicros: Long, now: Long) {
        val decoded = pending[ptsMicros]?.decoded ?: return
        if (now >= decoded) outputQueueWait.add(now, now - decoded)
    }

    @Synchronized
    fun rendered(ptsMicros: Long, renderedAt: Long, now: Long) {
        val timing = pending.remove(ptsMicros) ?: return
        val decoded = timing.decoded ?: return
        // Some phones report it on another clock, which says nothing next to this one's times.
        val shown = renderedAt.takeIf { it in decoded..now } ?: now
        display.add(now, shown - decoded)
        tickShown++
        if (renderedAt in decoded..now && previousPresented?.let { renderedAt > it } != false) {
            previousPresented?.let { presentation.add(now, renderedAt - it) }
            previousFrame?.let { before ->
                val relayGap =
                    if (timing.sourceRelay != null && before.sourceRelay != null)
                        (timing.sourceRelay - before.sourceRelay).takeIf { it >= 0 }
                    else null
                val ptsGap = (ptsMicros - before.pts).takeIf { it >= 0 }
                val receiveGap =
                    if (timing.received != null && before.received != null)
                        (timing.received - before.received) / 1_000
                    else null
                pairedGaps.addLast(
                    now to
                        ToolboxScreenPresentationGap(
                            ptsMicros,
                            (renderedAt - before.shown) / 1_000,
                            ptsGap,
                            (timing.arrived - before.arrived) / 1_000,
                            ((decoded - timing.arrived) - (before.decoded - before.arrived)) /
                                1_000,
                            ((renderedAt - decoded) - (before.shown - before.decoded)) / 1_000,
                            receiveGap,
                            if (timing.received != null && before.received != null)
                                ((timing.arrived - timing.received) -
                                    (before.arrived - before.received)) / 1_000
                            else null,
                            relayGap,
                            if (relayGap != null && ptsGap != null) relayGap - ptsGap else null,
                            if (relayGap != null && receiveGap != null) receiveGap - relayGap
                            else null,
                        )
                )
            }
            previousFrame =
                Presented(
                    ptsMicros,
                    timing.arrived,
                    decoded,
                    renderedAt,
                    timing.received,
                    timing.sourceRelay,
                )
            trimPairedGaps(now)
            previousPresented = renderedAt
        } else {
            // A callback timestamp from an incompatible clock is not a measured frame interval.
            invalidPresentationTimes++
            previousPresented = null
            previousFrame = null
        }
    }

    @Synchronized
    fun discardedOutput(ptsMicros: Long) {
        pending.remove(ptsMicros)
        displayDroppedFrames++
    }

    @Synchronized
    fun displayRefreshRate(hz: Double?) {
        displayRefreshHz = hz?.takeIf { it.isFinite() && it > 0 && it <= 1000 }
    }

    @Synchronized
    fun decoder(name: String, hardware: Boolean) {
        decoderName = name
        hardwareDecoder = hardware
    }

    @Synchronized
    fun h264LowDelay(declared: Boolean) {
        h264LowDelay = declared
    }

    @Synchronized
    fun inputWaitStarted(now: Long) {
        waitingSince = now
    }

    @Synchronized
    fun inputWaitFinished(now: Long) {
        waitingSince?.let { inputWait.add(now, (now - it).coerceAtLeast(0)) }
        waitingSince = null
    }

    @Synchronized
    fun oversizedFrame() {
        oversizedFrames++
    }

    /** Starts the next tick; the first one runs from the first frame. */
    @Synchronized
    fun tick(now: Long): ScreenFrameTick {
        val since = lastTick ?: started ?: now
        val seconds = (now - since).coerceAtLeast(1).toDouble() / NANOS_PER_SECOND
        val result =
            ScreenFrameTick(
                tickShown / seconds,
                if (tickDecoded > 0) tickDecoderNanos / tickDecoded / 1_000 else null,
            )
        lastTick = now
        tickShown = 0
        tickDecoded = 0
        tickDecoderNanos = 0
        return result
    }

    /** The window's values as they stand, copied while decoder callbacks wait. */
    private class Captured(
        val base: ScreenFrameSummary,
        val source: List<Long>,
        val arrival: List<Long>,
        val decode: List<Long>,
        val display: List<Long>,
        val waits: List<Long>,
        val presentation: List<Long>,
    )

    /** Sorting happens after the copy, so the decoder's callbacks don't wait on it. */
    fun summary(now: Long): ScreenFrameSummary {
        val captured = capture(now)
        val best = captured.arrival.minOrNull() ?: 0
        val sourceStage = captured.source.stage()
        return captured.base.copy(
            source = if (udp) null else sourceStage,
            arrival = captured.arrival.map { it - best }.stage(),
            decode = captured.decode.stage(),
            display = captured.display.stage(),
            receivedFrameSpacing = if (udp) sourceStage else null,
            inputWait = captured.waits.stage(),
            presentation = captured.presentation.presentationSummary(),
        )
    }

    @Synchronized
    private fun capture(now: Long): Captured {
        listOf(
                source,
                arrival,
                received,
                decode,
                display,
                inputWait,
                presentation,
                callbackDelay,
                outputQueueWait,
            )
            .forEach {
                it.trim(now)
            }
        trimPairedGaps(now)
        val seconds =
            ((now - (started ?: now)).coerceIn(NANOS_PER_SECOND, windowNanos)).toDouble() /
                NANOS_PER_SECOND
        val waits =
            inputWait.values() + listOfNotNull(waitingSince?.let { (now - it).coerceAtLeast(0) })
        val base =
            ScreenFrameSummary(
                receivedPerSecond = (arrival.size / seconds).roundToInt(),
                shownPerSecond = (display.size / seconds).roundToInt(),
                receivedMbps = received.values().sum() * 8 / seconds / 1_000_000,
                source = null,
                arrival = null,
                decode = null,
                display = null,
                udp = udp,
                oversizedFrames = oversizedFrames,
                decoderName = decoderName,
                hardwareDecoder = hardwareDecoder,
                h264LowDelay = h264LowDelay,
                displayDroppedFrames = displayDroppedFrames,
                invalidPresentationTimes = invalidPresentationTimes,
                displayRefreshHz = displayRefreshHz,
                longestPresentationGap =
                    pairedGaps.maxByOrNull { it.second.intervalMicros }?.second,
                // An idle/source gap must not hide a different pair that expanded in this pipeline.
                // PTS is differenced only on its own clock; no phone-to-target offset is assumed.
                largestPipelineExpansion =
                    pairedGaps
                        .asSequence()
                        .map { it.second }
                        .filter { gap ->
                            gap.ptsGapMicros?.let { it > 0 && gap.intervalMicros > it } == true
                        }
                        .maxByOrNull { it.intervalMicros - checkNotNull(it.ptsGapMicros) },
                outputScheduler =
                    ToolboxScreenOutputScheduler(
                        vsyncCallbacks,
                        emptyVsyncCallbacks,
                        lateVsyncCallbacks,
                        queueOverflowDrops,
                        watchdogDrops,
                        watchdogPresentations,
                        callbackDelay.values().maxOrNull()?.div(1_000),
                        outputQueueWait.values().maxOrNull()?.div(1_000),
                    ),
            )
        return Captured(
            base,
            source.values(),
            arrival.values(),
            decode.values(),
            display.values(),
            waits,
            presentation.values(),
        )
    }

    private fun trimPairedGaps(now: Long) {
        while (pairedGaps.isNotEmpty() && pairedGaps.first().first < now - windowNanos) pairedGaps
            .removeFirst()
    }

    private fun List<Long>.presentationSummary(): ScreenPresentationSummary? {
        if (isEmpty()) return null
        val sorted = sorted()
        fun percentile(p: Double) = sorted[ceil(size * p).toInt() - 1] / 1_000
        val tail = sorted.takeLast(ceil(size * 0.01).toInt())
        return ScreenPresentationSummary(
            size,
            percentile(0.50),
            percentile(0.95),
            percentile(0.99),
            sorted.last() / 1_000,
            1_000_000_000.0 / tail.map(Long::toDouble).average(),
            count { it > 2_000_000_000L / targetFps },
        )
    }

    private fun List<Long>.stage(): ScreenStage? {
        if (isEmpty()) return null
        val sorted = sorted()
        val p95 = sorted[ceil(sorted.size * 0.95).toInt() - 1]
        return ScreenStage(p95.toMillis(), sorted.last().toMillis())
    }

    private fun Long.toMillis() = (this / 1_000_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val MAX_PENDING = 256
    }
}

/** Compact metadata snapshot for the backend's rate-limited screen diagnostics. */
internal fun ScreenFrameSummary.playback() =
    ToolboxScreenPlayback(
        receivedFps = receivedPerSecond,
        renderedFps = shownPerSecond,
        frameGapMaxMillis = (receivedFrameSpacing ?: source)?.maxMillis,
        arrivalVariationMaxMillis = arrival?.maxMillis,
        decodeMaxMillis = decode?.maxMillis,
        displayMaxMillis = display?.maxMillis,
        inputWaitMaxMillis = inputWait?.maxMillis,
        oversizedFrames = oversizedFrames,
        decoderName = decoderName,
        hardwareDecoder = hardwareDecoder,
        h264LowDelay = h264LowDelay,
        receivedMbps = receivedMbps,
        displayDroppedFrames = displayDroppedFrames,
        presentedIntervalSamples = presentation?.samples ?: 0,
        presentedIntervalP50Micros = presentation?.p50Micros,
        presentedIntervalP95Micros = presentation?.p95Micros,
        presentedIntervalP99Micros = presentation?.p99Micros,
        presentedIntervalMaxMicros = presentation?.maxMicros,
        presentedLow1Fps = presentation?.low1Fps,
        presentedLongIntervals = presentation?.overTwoFramePeriods ?: 0,
        invalidPresentationTimes = invalidPresentationTimes,
        displayRefreshHz = displayRefreshHz,
        longestPresentationGap = longestPresentationGap,
        outputScheduler = outputScheduler,
        largestPipelineExpansion = largestPipelineExpansion,
    )
