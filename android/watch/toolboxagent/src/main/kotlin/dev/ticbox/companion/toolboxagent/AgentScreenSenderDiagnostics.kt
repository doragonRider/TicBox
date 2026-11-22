package dev.ticbox.companion.toolboxagent

import dev.ticbox.companion.protocol.ScreenUdpTransport
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Optional sender timing, without frame contents. The video producer owns the window counters;
 * a separate daemon publishes at most one pending snapshot. A slow ADB reader cannot stall video
 * or grow a diagnostic backlog. Delivery is best effort, so these samples are not a loss counter.
 * With [timing], a per-second mean of each frame's own time here also goes to the player, in its
 * own slot so neither kind of sample pushes out the other. Encoder metadata, the encoder limit and
 * bit rate changes each get a slot of their own as well, encoder metadata one per stage: its
 * output and input samples, and its configuration and vendor parameters, come out back to back.
 */
internal class AgentScreenSenderDiagnostics(
    private val publish: (JsonObject) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
    private val timing: Boolean = false,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val pending = ArrayBlockingQueue<JsonObject>(8)
    private var timingStarted: Long? = null
    private var timedFrames = 0L
    private var timedMicros = 0L
    private var captureToRelayMicros = 0L
    private var sendWorkMicros = 0L
    private var pacingMicros = 0L
    private var maxCaptureToRelayMicros = 0L
    private var timingRejectedFrames = 0L
    private var overRangeFrames = 0L
    private var negativeAgeFrames = 0L
    private var missingTimestamps = 0L
    private var maxOverRangeAgeMicros = 0L
    private var windowStarted: Long? = null
    private var lastSlow: Long? = null
    private var frames = 0L
    private var encodedBytes = 0L
    private var keyFrames = 0L
    private var parityDatagrams = 0L
    private var maxFecEncodeNanos = 0L
    private var maxEncryptNanos = 0L
    private var maxSocketSendNanos = 0L
    private var sendCalls = 0L
    private var datagrams = 0L
    private var maxEncryptCpuNanos: Long? = null
    private var maxSocketSendCpuNanos: Long? = null
    private var maxSocketCopyNanos: Long? = null
    private var maxSocketCopyCpuNanos: Long? = null
    private var maxSocketNativeNanos: Long? = null
    private var maxSocketNativeCpuNanos: Long? = null
    private var gsoCalls = 0L
    private var gsoDatagrams = 0L
    private var previousPts: Long? = null
    private var previousIngress: Long? = null
    private var maxPtsGapMicros: Long? = null
    private var maxProducerGapMicros: Long? = null
    private var maxFrameBytes = 0
    private var maxSendNanos = 0L
    private var maxPacingWaitNanos = 0L
    private val reporter = Thread({
        try {
            while (!closed.get()) {
                val next = pending.take()
                if (!closed.get()) publish(next)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            // Diagnostic transport failure must not terminate the media transport.
        } finally {
            closed.set(true)
            pending.clear()
        }
    }, "toolbox-screen-send-diagnostics").apply { isDaemon = true; start() }

    /** Called serially after a nonempty video frame has been sent successfully. */
    fun record(frameBytes: Int, keyFrame: Boolean, stats: ScreenUdpTransport.SendStats,
        ptsMicros: Long? = null, ingressNanos: Long? = null) {
        if (closed.get()) return
        val now = nanoTime()
        val since = windowStarted ?: now.also { windowStarted = it }
        val ptsGap = ptsMicros?.let { pts -> previousPts?.let { pts - it } }?.takeIf { it >= 0 }
        val producerGap = ingressNanos?.let { time -> previousIngress?.let { (time - it) / 1_000 } }?.takeIf { it >= 0 }
        previousPts = ptsMicros; previousIngress = ingressNanos
        ptsGap?.let { maxPtsGapMicros = maxOf(maxPtsGapMicros ?: 0, it) }
        producerGap?.let { maxProducerGapMicros = maxOf(maxProducerGapMicros ?: 0, it) }
        frames++
        encodedBytes += frameBytes
        if (keyFrame) keyFrames++
        parityDatagrams += stats.parityDatagrams
        maxFecEncodeNanos = maxOf(maxFecEncodeNanos, stats.fecEncodeNanos)
        maxEncryptNanos = maxOf(maxEncryptNanos, stats.encryptNanos)
        maxSocketSendNanos = maxOf(maxSocketSendNanos, stats.socketSendNanos)
        sendCalls += stats.sendCalls; datagrams += stats.datagrams
        stats.encryptCpuNanos?.let { maxEncryptCpuNanos = maxOf(maxEncryptCpuNanos ?: 0, it) }
        stats.socketSendCpuNanos?.let { maxSocketSendCpuNanos = maxOf(maxSocketSendCpuNanos ?: 0, it) }
        stats.socketCopyNanos?.let { maxSocketCopyNanos = maxOf(maxSocketCopyNanos ?: 0, it) }
        stats.socketCopyCpuNanos?.let { maxSocketCopyCpuNanos = maxOf(maxSocketCopyCpuNanos ?: 0, it) }
        stats.socketNativeNanos?.let { maxSocketNativeNanos = maxOf(maxSocketNativeNanos ?: 0, it) }
        stats.socketNativeCpuNanos?.let { maxSocketNativeCpuNanos = maxOf(maxSocketNativeCpuNanos ?: 0, it) }
        gsoCalls += stats.gsoCalls; gsoDatagrams += stats.gsoDatagrams
        maxFrameBytes = maxOf(maxFrameBytes, frameBytes)
        maxSendNanos = maxOf(maxSendNanos, stats.sendNanos)
        maxPacingWaitNanos = maxOf(maxPacingWaitNanos, stats.pacingWaitNanos)
        if (timing) time(now, stats, ptsMicros, ingressNanos)
        val elapsed = (now - since).coerceAtLeast(0)
        if (elapsed >= WINDOW_NANOS) {
            offer(buildJsonObject {
                put("kind", KIND); put("version", 1); put("sample", "window")
                put("elapsedMillis", elapsed / 1_000_000)
                put("frames", frames); put("encodedBytes", encodedBytes); put("keyFrames", keyFrames)
                put("maxFrameBytes", maxFrameBytes)
                put("maxSendMicros", maxSendNanos / 1_000)
                put("maxPacingWaitMicros", maxPacingWaitNanos / 1_000)
                maxEncryptCpuNanos?.let { put("maxEncryptCpuMicros", it / 1_000) }
                maxSocketSendCpuNanos?.let { put("maxSocketSendCpuMicros", it / 1_000) }
                put("maxEncryptMicros", maxEncryptNanos / 1_000); put("maxSocketSendMicros", maxSocketSendNanos / 1_000)
                put("sendCalls", sendCalls); put("datagrams", datagrams); put("nativeBatch", stats.nativeBatch); put("fecImplementation", stats.fecImplementation); put("mediaSecurity", stats.mediaSecurity)
                put("parityDatagrams", parityDatagrams); put("maxFecEncodeMicros", maxFecEncodeNanos / 1_000)
                maxPtsGapMicros?.let { put("maxPtsGapMicros", it) }
                maxProducerGapMicros?.let { put("maxProducerGapMicros", it) }
                maxSocketCopyNanos?.let { put("maxSocketCopyMicros", it / 1_000) }
                maxSocketCopyCpuNanos?.let { put("maxSocketCopyCpuMicros", it / 1_000) }
                maxSocketNativeNanos?.let { put("maxSocketNativeMicros", it / 1_000) }
                maxSocketNativeCpuNanos?.let { put("maxSocketNativeCpuMicros", it / 1_000) }
                if (stats.nativeBatch) { put("gsoCalls", gsoCalls); put("gsoDatagrams", gsoDatagrams); put("gsoDisabled", stats.gsoDisabled) }
            })
            windowStarted = now
            frames = 0; encodedBytes = 0; keyFrames = 0; parityDatagrams = 0; maxFecEncodeNanos = 0
            maxEncryptCpuNanos = null; maxSocketSendCpuNanos = null
            maxSocketCopyNanos = null; maxSocketCopyCpuNanos = null; maxSocketNativeNanos = null; maxSocketNativeCpuNanos = null
            gsoCalls = 0; gsoDatagrams = 0; maxPtsGapMicros = null; maxProducerGapMicros = null
            maxEncryptNanos = 0; maxSocketSendNanos = 0; sendCalls = 0; datagrams = 0
            maxFrameBytes = 0; maxSendNanos = 0; maxPacingWaitNanos = 0
        } else if ((stats.sendNanos >= SLOW_NANOS || (ptsGap ?: 0) >= 80_000 || (producerGap ?: 0) >= 80_000) && lastSlow?.let { now - it >= SLOW_INTERVAL_NANOS } != false) {
            lastSlow = now
            offer(buildJsonObject {
                put("kind", KIND); put("version", 1); put("sample", if (stats.sendNanos >= SLOW_NANOS) "slow_frame" else "frame_gap")
                put("sequence", stats.sequence); put("frameBytes", frameBytes); put("keyFrame", keyFrame)
                put("datagrams", stats.datagrams); put("wireBytes", stats.wireBytes)
                put("sendMicros", stats.sendNanos / 1_000)
                put("pacingWaitMicros", stats.pacingWaitNanos / 1_000)
                stats.encryptCpuNanos?.let { put("encryptCpuMicros", it / 1_000) }
                stats.socketSendCpuNanos?.let { put("socketSendCpuMicros", it / 1_000) }
                put("encryptMicros", stats.encryptNanos / 1_000); put("socketSendMicros", stats.socketSendNanos / 1_000)
                put("sendCalls", stats.sendCalls); put("nativeBatch", stats.nativeBatch); put("fecImplementation", stats.fecImplementation); put("mediaSecurity", stats.mediaSecurity)
                put("parityDatagrams", stats.parityDatagrams); put("fecEncodeMicros", stats.fecEncodeNanos / 1_000)
                ptsMicros?.let { put("ptsMicros", it) }; ptsGap?.let { put("ptsGapMicros", it) }; producerGap?.let { put("producerGapMicros", it) }
                stats.socketCopyNanos?.let { put("socketCopyMicros", it / 1_000) }
                stats.socketCopyCpuNanos?.let { put("socketCopyCpuMicros", it / 1_000) }
                stats.socketNativeNanos?.let { put("socketNativeMicros", it / 1_000) }
                stats.socketNativeCpuNanos?.let { put("socketNativeCpuMicros", it / 1_000) }
                if (stats.nativeBatch) { put("gsoCalls", stats.gsoCalls); put("gsoDatagrams", stats.gsoDatagrams); put("gsoDisabled", stats.gsoDisabled) }
            })
        }
    }

    /**
     * Capture timestamp to the Agent relay, including capture, codec and local-socket waiting;
     * then FEC, authentication and socket sends. This is not isolated hardware encode time.
     * The component means share the same measured frame set; pacing is reported separately.
     * A frame whose timestamp doesn't fit is left out, with the omitted count retained.
     */
    private fun time(now: Long, stats: ScreenUdpTransport.SendStats, ptsMicros: Long?, ingressNanos: Long?) {
        val since = timingStarted ?: now.also { timingStarted = it }
        val observedAge = ptsMicros?.let { pts -> ingressNanos?.let { it / 1_000 - pts } }
        val age = observedAge?.takeIf { it in 0..MAX_ENCODE_MICROS }
        if (age != null) {
            val work = (stats.sendNanos - stats.pacingWaitNanos).coerceAtLeast(0) / 1_000
            timedFrames++
            captureToRelayMicros += age; sendWorkMicros += work; pacingMicros += stats.pacingWaitNanos / 1_000
            maxCaptureToRelayMicros = maxOf(maxCaptureToRelayMicros, age)
            timedMicros += age + work
        } else {
            timingRejectedFrames++
            when {
                observedAge == null -> missingTimestamps++
                observedAge < 0 -> negativeAgeFrames++
                else -> { overRangeFrames++; maxOverRangeAgeMicros = maxOf(maxOverRangeAgeMicros, observedAge) }
            }
        }
        if (now - since < TIMING_NANOS) return
        offer(buildJsonObject {
            put("kind", TIMING_KIND); put("version", 1); put("frames", timedFrames)
            put("rejectedFrames", timingRejectedFrames)
            put("overRangeFrames", overRangeFrames); put("negativeAgeFrames", negativeAgeFrames); put("missingTimestamps", missingTimestamps)
            if (overRangeFrames > 0) put("maxOverRangeAgeMicros", maxOverRangeAgeMicros)
            if (timedFrames > 0) {
                put("meanMicros", timedMicros / timedFrames)
                put("meanCaptureToRelayMicros", captureToRelayMicros / timedFrames)
                put("maxCaptureToRelayMicros", maxCaptureToRelayMicros)
                put("meanSendWorkMicros", sendWorkMicros / timedFrames)
                put("meanPacingMicros", pacingMicros / timedFrames)
            }
        })
        timingStarted = now; timedFrames = 0; timedMicros = 0
        captureToRelayMicros = 0; sendWorkMicros = 0; pacingMicros = 0; maxCaptureToRelayMicros = 0; timingRejectedFrames = 0
        overRangeFrames = 0; negativeAgeFrames = 0; missingTimestamps = 0; maxOverRangeAgeMicros = 0
    }

    /** A snapshot of its own kind and stage; a newer one of both replaces it while it waits. */
    fun event(snapshot: JsonObject) { if (!closed.get()) offer(snapshot) }

    @Synchronized private fun offer(snapshot: JsonObject) {
        if (closed.get()) return
        val kind = snapshot["kind"]
        val stage = snapshot["stage"]
        pending.removeIf { it["kind"] == kind && it["stage"] == stage }
        pending.offer(snapshot)
        if (closed.get()) pending.clear()
    }

    override fun close() {
        closed.set(true)
        pending.clear()
        reporter.interrupt()
        // Do not join an ADB write: its descriptor belongs to the operation owner, which closes
        // it when the request ends. The reporter is a daemon and holds no encoder/UDP resources.
    }

    internal fun awaitStopped(timeoutMillis: Long): Boolean {
        reporter.join(timeoutMillis)
        return !reporter.isAlive
    }
    internal val pendingSnapshots: Int get() = pending.size

    companion object {
        private const val KIND = "screen_udp_sender"
        private const val TIMING_KIND = "screen_udp_timing"
        private const val TIMING_NANOS = 1_000_000_000L
        private const val MAX_ENCODE_MICROS = 1_000_000L
        private const val WINDOW_NANOS = 2_000_000_000L
        private const val SLOW_NANOS = 40_000_000L
        private const val SLOW_INTERVAL_NANOS = 1_000_000_000L
    }
}
