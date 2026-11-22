package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Bounded, metadata-only observations of one screen session, shared by media and control lanes. */
internal class ScreenDiagnostics(
    private val sink: DiagnosticSink,
    backend: ToolboxBackendKind,
    options: ToolboxScreenOptions,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val id = nextId.incrementAndGet()
    private val finished = AtomicBoolean()
    private val lock = Any()
    private val recent = HashMap<String, Long>()

    init {
        event("stream_open", mapOf(
            "backend" to backend.name, "transport" to if (options.udp) "UDP" else "STREAM",
            "codec" to options.codec.name, "requestedMaxSize" to options.maxSize.toString(),
            "requestedFps" to options.maxFps.toString(), "requestedBitRate" to options.bitRate.toString(),
            "audio" to options.audio.toString(),
            "udpEncryption" to options.udpEncryption.toString(),
            "limitToEncoder" to options.limitToEncoder.toString(),
            "codecOptions" to options.videoCodecOptions.ifEmpty { "none" },
        ))
        if (options.udp) event("video_queue_budget", mapOf("maximumBatches" to ScreenVideoQueue.capacityForFps(options.maxFps).toString(), "maximumEncodedBytes" to ScreenVideoQueue.DEFAULT_MAXIMUM_BYTES.toString(), "maximumAgeMillis" to ScreenVideoQueue.DEFAULT_MAXIMUM_AGE_MILLIS.toString(), "maximumReassemblyMessages" to ScreenVideoQueue.reassemblyCapacityForFps(options.maxFps).toString()))
    }

    fun event(name: String, details: Map<String, String> = emptyMap()) {
        if (sink === DiagnosticSink.NONE || finished.get()) return
        // A bad link must not replace media work with per-frame logging. The first event is kept;
        // subsequent observations of the same high-frequency kind are bounded to once a second.
        if (name in THROTTLED) synchronized(lock) {
            val now = nanoTime()
            val previous = recent[name]
            if (previous != null && now - previous < 1_000_000_000L) return
            recent[name] = now
        }
        record(name, details)
    }

    fun playback(sample: ToolboxScreenPlayback) {
        event("playback_sample", buildMap {
        put("receivedFps", sample.receivedFps.toString())
        put("renderedFps", sample.renderedFps.toString())
        put("receivedMbps", sample.receivedMbps?.takeIf { it.isFinite() && it >= 0 }?.toString() ?: "unknown")
        put("frameGapMaxMillis", sample.frameGapMaxMillis?.toString() ?: "unknown")
        put("arrivalVariationMaxMillis", sample.arrivalVariationMaxMillis?.toString() ?: "unknown")
        put("decodeMaxMillis", sample.decodeMaxMillis?.toString() ?: "unknown")
        put("displayMaxMillis", sample.displayMaxMillis?.toString() ?: "unknown")
        put("inputWaitMaxMillis", sample.inputWaitMaxMillis?.toString() ?: "unknown")
        put("oversizedFrames", sample.oversizedFrames.toString())
        put("displayDroppedFrames", sample.displayDroppedFrames.toString())
        put("presentedIntervalSamples", sample.presentedIntervalSamples.toString())
        put("presentedIntervalP50Micros", sample.presentedIntervalP50Micros?.toString() ?: "unknown")
        put("presentedIntervalP95Micros", sample.presentedIntervalP95Micros?.toString() ?: "unknown")
        put("presentedIntervalP99Micros", sample.presentedIntervalP99Micros?.toString() ?: "unknown")
        put("presentedIntervalMaxMicros", sample.presentedIntervalMaxMicros?.toString() ?: "unknown")
        put("presentedLow1Fps", sample.presentedLow1Fps?.takeIf { it.isFinite() && it > 0 }?.toString() ?: "unknown")
        put("presentedLongIntervals", sample.presentedLongIntervals.toString())
        put("invalidPresentationTimes", sample.invalidPresentationTimes.toString())
        put("displayRefreshHz", sample.displayRefreshHz?.takeIf { it.isFinite() && it > 0 }?.toString() ?: "unknown")
        put("decoder", sample.decoderName?.takeIf { it.matches(CODEC_NAME) } ?: "unknown")
        put("hardwareDecoder", sample.hardwareDecoder?.toString() ?: "unknown")
        sample.h264LowDelay?.let { put("h264LowDelay", it.toString()) }
    })
        fun gapEvent(name: String, gap: ToolboxScreenPresentationGap) = event(name, buildMap {
            put("framePtsMicros", gap.framePtsMicros.toString()); put("intervalMicros", gap.intervalMicros.toString())
            put("ptsGapMicros", gap.ptsGapMicros?.toString() ?: "unknown"); put("arrivalGapMicros", gap.arrivalGapMicros.toString())
            put("decodeChangeMicros", gap.decodeChangeMicros.toString()); put("displayChangeMicros", gap.displayChangeMicros.toString())
            put("receiveGapMicros", gap.receiveGapMicros?.toString() ?: "unknown"); put("queueChangeMicros", gap.queueChangeMicros?.toString() ?: "unknown")
            put("sourceRelayGapMicros", gap.sourceRelayGapMicros?.toString() ?: "unknown")
            put("captureToRelayChangeMicros", gap.captureToRelayChangeMicros?.toString() ?: "unknown")
            put("deliveryChangeMicros", gap.deliveryChangeMicros?.toString() ?: "unknown")
            put("expansionMicros", gap.ptsGapMicros?.let { (gap.intervalMicros - it).toString() } ?: "unknown")
        })
        sample.longestPresentationGap?.let { gapEvent("presentation_gap", it) }
        sample.largestPipelineExpansion?.let { gapEvent("presentation_expansion", it) }
        sample.outputScheduler?.let { scheduler -> event("display_scheduler_sample", mapOf(
            "vsyncCallbacks" to scheduler.vsyncCallbacks.toString(),
            "emptyVsyncCallbacks" to scheduler.emptyVsyncCallbacks.toString(),
            "lateVsyncCallbacks" to scheduler.lateVsyncCallbacks.toString(),
            "queueOverflowDrops" to scheduler.queueOverflowDrops.toString(),
            "watchdogDrops" to scheduler.watchdogDrops.toString(),
            "watchdogPresentations" to scheduler.watchdogPresentations.toString(),
            "callbackDelayMaxMicros" to (scheduler.callbackDelayMaxMicros?.toString() ?: "unknown"),
            "outputQueueWaitMaxMicros" to (scheduler.outputQueueWaitMaxMicros?.toString() ?: "unknown"),
        )) }
    }

    fun controlQueued(action: Int, waitMillis: Long) {
        if (action in TOUCH_BOUNDARIES) event("touch_queued", mapOf("action" to action.toString(), "queueMillis" to waitMillis.coerceAtLeast(0).toString()))
    }

    suspend fun control(input: ToolboxScreenInput, send: suspend () -> Unit) {
        val touch = (input as? ToolboxScreenInput.Touch)?.takeIf { it.action in TOUCH_BOUNDARIES }
        if (touch == null || sink === DiagnosticSink.NONE) { send(); return }
        val start = nanoTime()
        try {
            send()
            event("touch_written", mapOf("action" to touch.action.toString(), "writeMillis" to elapsedMillis(start).toString()))
        } catch (error: Throwable) {
            event("touch_write_failed", mapOf("action" to touch.action.toString(), "writeMillis" to elapsedMillis(start).toString(), "exception" to error.javaClass.simpleName))
            throw error
        }
    }

    fun finish(error: Throwable? = null) {
        if (!finished.compareAndSet(false, true)) return
        record("stream_closed", mapOf("reason" to ((error as? ToolboxException)?.code ?: error?.javaClass?.simpleName ?: "closed")))
    }
    private fun elapsedMillis(start: Long) = ((nanoTime() - start) / 1_000_000).coerceAtLeast(0)
    private fun record(name: String, details: Map<String, String>) {
        if (sink === DiagnosticSink.NONE) return
        runCatching { sink.record(ProtocolDiagnostic("toolbox_screen", name,
            severity = if (name in WARNINGS) DiagnosticSeverity.WARNING else DiagnosticSeverity.INFO,
            sessionId = id, details = details)) }
    }
    companion object {
        private val nextId = AtomicLong()
        private val CODEC_NAME = Regex("[a-zA-Z0-9_.-]{1,128}")
        private val TOUCH_BOUNDARIES = setOf(0, 1, 5, 6)
        private val THROTTLED = setOf("playback_sample", "presentation_gap", "presentation_expansion", "video_sender_timing", "display_scheduler_sample", "video_sender_socket", "video_sender_frame_gap", "video_ingress_gap", "video_dequeue_delay", "video_reassembly_gap", "video_sender_slow_frame", "video_sender_sample", "video_fec_recovered")
        private val WARNINGS = setOf("video_waiting_for_key", "video_reset_requested", "video_keyframe_requested", "video_recovery_failed", "video_encoder_restart_requested", "touch_write_failed")
    }
}
