package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Bounded decoder queues. A lost video dependency discards deltas until a complete keyframe. */
internal class ScreenUdpMedia(
    private val observer: ((String, Map<String, String>) -> Unit)? = null,
    private val clockNanos: () -> Long = System::nanoTime,
    maxFps: Int = 20,
    private val requireDecoderReady: Boolean = false,
    private val requireBootstrap: Boolean = false,
    private val onBootstrapKey: ((Long, Long) -> Unit)? = null,
) {
    private val pictures = ScreenVideoQueue(maximumBatches = ScreenVideoQueue.capacityForFps(maxFps), clockNanos = clockNanos,
        onDequeued = if (observer != null) { sample ->
            if (sample.residenceMillis >= 16) event("dequeue_delay", mapOf(
                "residenceMillis" to sample.residenceMillis.toString(), "batchBytes" to sample.batchBytes.toString(),
                "remainingBatches" to sample.remainingBatches.toString(), "remainingBytes" to sample.remainingBytes.toString(),
            ))
        } else null)
    private val sounds = Channel<List<ToolboxScreenEvent>>(12)
    val media = ToolboxScreenMedia(pictures.events, sounds.flatten())
    private var configuration: ToolboxScreenEvent.Configuration? = null
    private var decoderConfiguration: ToolboxScreenEvent.Configuration? = null
    private var preparationStarted: Long? = null
    private var warmupDiscardedFrames = 0L
    private var stopped = false
    private var bootstrapKeyPts: Long? = null
    private var lastAcceptedPts: Long? = null
    private var codec = emptyList<ToolboxScreenEvent.Frame>()
    private var waitingForKey = true
    private var waitingSinceNanos: Long? = null
    private var waitingReason: String? = null
    private var discardedDeltas = 0L
    private var previousArrivalNanos: Long? = null
    private var previousPtsMicros: Long? = null
    private var ingressWindowStart: Long? = null
    private var ingressFrames = 0L
    private var ingressBytes = 0L
    private var ingressLargestFrame = 0
    private var ingressLargestGap = 0L
    private var fecRecoveredMessages = 0L
    private var fecRecoveredShards = 0L
    private var fecRecoveredBlocks = 0L
    @Volatile var recoveryState = UdpVideoRecoveryState(waitingForDecoder = requireDecoderReady)
        private set
    private var pendingRecoveryState = recoveryState
    private var audioCodec: ToolboxScreenEvent.AudioConfiguration? = null
    private var audioHead: ToolboxScreenEvent.AudioFrame? = null

    /** Reliable metadata arrives before the sender releases this generation's first UDP image. */
    @Synchronized fun prepareBootstrap(value: ToolboxScreenEvent.Configuration) {
        if (stopped || !requireBootstrap || value.generation <= (configuration?.generation ?: 0)) return
        val now = clockNanos()
        resetVideo("configuration_changed", now)
        configuration = value; codec = emptyList(); bootstrapKeyPts = null; lastAcceptedPts = null; decoderConfiguration = null
        preparationStarted = now; warmupDiscardedFrames = 0
        pendingRecoveryState = pendingRecoveryState.copy(waitingForDecoder = true, decoderPrepared = false, observedFrame = false, captureGeneration = value.generation)
        recoveryState = pendingRecoveryState
        pictures.prepare(value)
        event("configuration", mapOf("codec" to value.codec, "width" to value.width.toString(), "height" to value.height.toString(), "generation" to value.generation.toString()))
    }

    /** Pure admission check for a completed, out-of-order FEC message. No media state mutation. */
    @Synchronized fun canRestartAt(type: Int, bytes: ByteArray): Boolean {
        if (stopped || type != ToolboxProtocol.VIDEO_FRAME) return false
        val current = configuration ?: return false
        if (requireDecoderReady && decoderConfiguration != current) return false
        val boundary = independentVideoBoundary(bytes) ?: return false
        if (boundary.configuration != current || lastAcceptedPts?.let { boundary.ptsMicros <= it } == true || boundary.ptsMicros == bootstrapKeyPts) return false
        return true
    }

    /** Each lane is called serially by its own UDP receiver; video never waits for a decoder. */
    @Synchronized fun video(message: ScreenUdpTransport.Message): Boolean = try {
        if (stopped) false else acceptVideo(message)
    } finally {
        // A discontinuous message can itself contain a complete new keyframe. Do not expose
        // its temporary dependency reset to the prompt recovery timer midway through parsing.
        recoveryState = pendingRecoveryState
    }

    private fun acceptVideo(message: ScreenUdpTransport.Message): Boolean {
        if (message.type != ToolboxProtocol.VIDEO_FRAME) mismatch()
        val packetList = packets(message.bytes)
        val firstConfig = packetList.firstOrNull()?.takeIf { it.type == ToolboxProtocol.VIDEO_CONFIG }
        val firstValue = firstConfig?.objectValue()
        if (requireBootstrap) {
            val generation = firstValue?.number("generation")?.takeIf { it > 0 } ?: mismatch()
            if (generation != configuration?.generation) return false
        }
        val now = clockNanos()
        message.fec?.takeIf { observer != null && it.recoveredDataShards > 0 }?.let { fec ->
            fecRecoveredMessages++; fecRecoveredShards += fec.recoveredDataShards; fecRecoveredBlocks += fec.recoveredBlocks
            event("fec_recovered", mapOf(
                "dataShards" to fec.dataShards.toString(), "parityShards" to fec.parityShards.toString(),
                "recoveredDataShards" to fec.recoveredDataShards.toString(), "recoveredBlocks" to fec.recoveredBlocks.toString(),
                "recoveryMicros" to (fec.decodeNanos / 1_000).toString(),
            ))
        }
        var changedConfiguration = false
        var frame: ToolboxScreenEvent.Frame? = null
        val headers = mutableListOf<ToolboxScreenEvent.Frame>()
        for (packet in packetList) when (packet.type) {
            ToolboxProtocol.VIDEO_CONFIG -> {
                val value = if (packet === firstConfig) checkNotNull(firstValue) else packet.objectValue()
                val width = value.number("width")?.takeIf { it in 1..65535 }?.toInt() ?: mismatch()
                val height = value.number("height")?.takeIf { it in 1..65535 }?.toInt() ?: mismatch()
                val encoding = value.text("codec") ?: mismatch()
                if (width !in 1..65535 || height !in 1..65535 || encoding !in setOf("h264", "h265")) mismatch()
                val generation = if (requireBootstrap) value.number("generation")?.takeIf { it > 0 } ?: mismatch() else 0L
                val next = ToolboxScreenEvent.Configuration(encoding, width, height, generation)
                // UDP from an old rotation cannot undo reliable capture-generation ordering.
                if (requireBootstrap && next != configuration) return false
                if (configuration != next) {
                    resetVideo("configuration_changed", now); codec = emptyList(); configuration = next; lastAcceptedPts = null
                    changedConfiguration = true
                    if (requireDecoderReady) {
                        decoderConfiguration = null; preparationStarted = now; warmupDiscardedFrames = 0
                        pendingRecoveryState = pendingRecoveryState.copy(waitingForDecoder = true, decoderPrepared = false)
                    }
                    event("configuration", mapOf("codec" to encoding, "width" to width.toString(), "height" to height.toString()))
                }
            }
            ToolboxProtocol.VIDEO_FRAME -> {
                val (flags, bytes) = payload(packet)
                val receipt = message.completedNanos?.let { ToolboxScreenReceipt(it, message.receiveWorkNanos, if (observer != null) firstValue?.number("sourceRelayMicros")?.takeIf { time -> time >= 0 } else null) }
                val next = ToolboxScreenEvent.Frame(flags and PTS_MASK, flags and KEY != 0L, flags and Long.MIN_VALUE != 0L, bytes, receipt)
                if (next.codecConfig) headers += next
                else { if (frame != null) mismatch(); frame = next }
            }
            else -> mismatch()
        }
        if (headers.isNotEmpty()) {
            if (headers.size > 16 || headers.sumOf { it.bytes.size.toLong() } > 262_144) mismatch()
            codec = headers.toList()
        }
        if (requireDecoderReady && changedConfiguration) pictures.prepare(checkNotNull(configuration))
        // A skipped duplicate bootstrap retransmission does not break the accepted reference:
        // the sender cannot have released its dependent deltas before this key was acknowledged.
        val duplicate = frame
        if (requireBootstrap && !waitingForKey && duplicate?.keyFrame == true && bootstrapKeyPts == duplicate.ptsMicros) {
            onBootstrapKey?.invoke(checkNotNull(configuration).generation, duplicate.ptsMicros)
            return false
        }
        if (message.discontinuity) {
            message.gap?.let { gap -> event("reassembly_gap", mapOf(
                "skippedMessages" to gap.skippedMessages.toString(),
                "incompleteMessages" to gap.incompleteMessages.toString(),
                "missingFragments" to gap.missingFragments.toString(),
                "missingMessageTimeout" to gap.missingMessageTimeout.toString(),
                "fragmentTimeout" to gap.fragmentTimeout.toString(),
                "frameDeadline" to gap.frameDeadline.toString(),
                "maxAgeMillis" to gap.maxAgeMillis.toString(),
                "maxNoProgressMillis" to gap.maxNoProgressMillis.toString(),
                "capacityEvictions" to gap.capacityEvictions.toString(),
                "capacityRejectedFragments" to gap.capacityRejectedFragments.toString(),
                "independentFrame" to gap.independentFrame.toString(),
                "laterComplete" to gap.laterComplete.toString(),
            )) }
            resetVideo("reassembly_gap", now)
        }
        val next = frame ?: return false
        if (next.bytes.isEmpty()) return false
        if (observer != null) recordIngress(next, now)
        pendingRecoveryState = pendingRecoveryState.copy(observedFrame = true)
        if (requireDecoderReady && decoderConfiguration != configuration) {
            warmupDiscardedFrames++
            return false // No dependency chain queues up behind a cold codec.
        }
        if (waitingForKey && !next.keyFrame) {
            if (waitingSinceNanos == null) resetVideo("configuration_changed", now)
            discardedDeltas++
            return false
        }
        val batch = if (next.keyFrame) {
            val config = configuration ?: return false
            if (codec.isEmpty()) return false
            listOf(config) + codec + next
        } else listOf(next)
        val rejected = pictures.offer(batch, now)
        if (rejected != null) {
            if (rejected.reason == ScreenVideoQueue.Rejection.CLOSED) return false
            resetVideo(rejected.reason.diagnosticReason, now, rejected.diagnosticDetails())
            if (!next.keyFrame) { discardedDeltas++; return false }
            if (pictures.offer(batch, now) != null) return false
        }
        if (waitingForKey) {
            event("key_recovered", mapOf(
                "reason" to (waitingReason ?: "configuration_changed"),
                "waitMillis" to (((now - (waitingSinceNanos ?: now)).coerceAtLeast(0)) / 1_000_000).toString(),
                "discardedDeltas" to discardedDeltas.toString(),
            ))
            waitingSinceNanos = null; waitingReason = null; discardedDeltas = 0
        }
        waitingForKey = false
        lastAcceptedPts = maxOf(lastAcceptedPts ?: next.ptsMicros, next.ptsMicros)
        pendingRecoveryState = pendingRecoveryState.copy(needsKeyFrame = false, acceptedKeyFrames = pendingRecoveryState.acceptedKeyFrames + if (next.keyFrame) 1 else 0)
        if (requireBootstrap && next.keyFrame && (bootstrapKeyPts == null || bootstrapKeyPts == next.ptsMicros)) {
            bootstrapKeyPts = next.ptsMicros
            onBootstrapKey?.invoke(checkNotNull(configuration).generation, next.ptsMicros)
        }
        return true
    }

    @Synchronized fun decoderReady(value: ToolboxScreenEvent.Configuration, initializationMillis: Long): Long? {
        if (stopped || !requireDecoderReady || value != configuration || value == decoderConfiguration) return null
        decoderConfiguration = value
        event("decoder_ready", mapOf("initializationMillis" to initializationMillis.coerceAtLeast(0).toString(),
            "preparationWaitMillis" to ((clockNanos() - (preparationStarted ?: clockNanos())).coerceAtLeast(0) / 1_000_000).toString(),
            "warmupDiscardedFrames" to warmupDiscardedFrames.toString()))
        pendingRecoveryState = pendingRecoveryState.copy(waitingForDecoder = false, decoderPrepared = true)
        recoveryState = pendingRecoveryState
        return value.generation.takeIf { requireBootstrap && it > 0 }
    }

    fun audio(message: ScreenUdpTransport.Message) {
        if (message.bytes.size > 65_536) mismatch()
        if (message.type !in setOf(ToolboxProtocol.AUDIO_CONFIG, ToolboxProtocol.AUDIO_FRAME)) mismatch()
        var frame: ToolboxScreenEvent.AudioFrame? = null
        for (packet in packets(message.bytes)) when (packet.type) {
            ToolboxProtocol.AUDIO_CONFIG -> {
                val name = packet.objectValue().text("codec")
                if (name != null && name != "opus") mismatch()
                audioCodec = ToolboxScreenEvent.AudioConfiguration(name)
                if (name == null) { audioHead = null; offerAudio(listOf(audioCodec!!)) }
            }
            ToolboxProtocol.AUDIO_FRAME -> {
                val (flags, bytes) = payload(packet)
                val next = ToolboxScreenEvent.AudioFrame(flags and PTS_MASK, flags and Long.MIN_VALUE != 0L, bytes)
                if (next.codecConfig) {
                    if (bytes.size !in 19..4096 || !bytes.copyOfRange(0, 8).contentEquals("OpusHead".toByteArray())) mismatch()
                    audioHead = next
                } else { if (frame != null) mismatch(); frame = next }
            }
            else -> mismatch()
        }
        val next = frame ?: return
        val config = audioCodec?.takeIf { it.codec == "opus" } ?: return
        val head = audioHead ?: return
        if (next.bytes.isNotEmpty()) offerAudio(listOf(config, head, next))
    }

    private fun offerAudio(batch: List<ToolboxScreenEvent>) {
        if (sounds.trySend(batch).isFailure) {
            // Keep sound near live time. Opus packets do not depend on video or preceding frames.
            sounds.tryReceive()
            sounds.trySend(batch)
        }
    }
    private fun resetVideo(reason: String, now: Long, rejectionDetails: Map<String, String> = emptyMap()) {
        val report = !waitingForKey || waitingReason != reason
        if (waitingSinceNanos == null) waitingSinceNanos = now
        waitingForKey = true; waitingReason = reason
        pendingRecoveryState = pendingRecoveryState.copy(needsKeyFrame = true)
        val discarded = pictures.clear()
        discardedDeltas += discarded.deltas
        if (report) event("waiting_for_key", mapOf("reason" to reason,
            "discardedBatches" to discarded.batches.toString(), "discardedBytes" to discarded.bytes.toString()) + rejectionDetails)
    }
    private fun recordIngress(frame: ToolboxScreenEvent.Frame, now: Long) {
        val gap = previousArrivalNanos?.let { (now - it).coerceAtLeast(0) / 1_000_000 }
        val ptsGap = previousPtsMicros?.let { (frame.ptsMicros - it) / 1_000 }
        previousArrivalNanos = now; previousPtsMicros = frame.ptsMicros
        val windowStart = ingressWindowStart ?: now.also { ingressWindowStart = it }
        ingressFrames++; ingressBytes += frame.bytes.size
        ingressLargestFrame = maxOf(ingressLargestFrame, frame.bytes.size)
        ingressLargestGap = maxOf(ingressLargestGap, gap ?: 0)
        if (now - windowStart >= 2_000_000_000L) {
            event("ingress_sample", mapOf(
                "elapsedMillis" to ((now - windowStart) / 1_000_000).toString(),
                "completedFrames" to ingressFrames.toString(), "encodedBytes" to ingressBytes.toString(),
                "largestFrameBytes" to ingressLargestFrame.toString(), "largestArrivalGapMillis" to ingressLargestGap.toString(),
                "fecRecoveredMessages" to fecRecoveredMessages.toString(), "fecRecoveredShards" to fecRecoveredShards.toString(),
                "fecRecoveredBlocks" to fecRecoveredBlocks.toString(),
            ))
            ingressWindowStart = now; ingressFrames = 0; ingressBytes = 0; ingressLargestFrame = 0; ingressLargestGap = 0
            fecRecoveredMessages = 0; fecRecoveredShards = 0; fecRecoveredBlocks = 0
        }
        if (gap != null && gap >= 200) event("ingress_gap", mapOf(
            "arrivalGapMillis" to gap.toString(), "ptsGapMillis" to (ptsGap?.toString() ?: "unknown"),
            "frameBytes" to frame.bytes.size.toString(), "keyFrame" to frame.keyFrame.toString(),
        ))
    }
    private fun event(name: String, details: Map<String, String>) { observer?.let { runCatching { it(name, details) } } }
    @Synchronized fun close(error: Throwable? = null) { stopped = true; pictures.close(error); sounds.close(error) }
    @Synchronized fun cancel() { stopped = true; pictures.cancel(); sounds.cancel() }

    private fun packets(bytes: ByteArray): List<ToolboxProtocol.Packet> {
        val result = ArrayList<ToolboxProtocol.Packet>(3)
        ByteArrayInputStream(bytes).use { input ->
            while (input.available() > 0) {
                if (result.size == 18) mismatch()
                result += ToolboxProtocol.read(input) ?: mismatch()
            }
        }
        if (result.isEmpty()) mismatch()
        return result
    }
    private fun payload(packet: ToolboxProtocol.Packet): Pair<Long, ByteArray> {
        if (packet.bytes.size < 8) mismatch()
        return ByteBuffer.wrap(packet.bytes).long to packet.bytes.copyOfRange(8, packet.bytes.size)
    }
    private fun mismatch(): Nothing = throw ToolboxException("screen_udp_protocol_mismatch")
    companion object { private const val PTS_MASK = 0x3fff_ffff_ffff_ffffL; private const val KEY = 1L shl 62 }
}

private fun Channel<List<ToolboxScreenEvent>>.flatten(): Flow<ToolboxScreenEvent> = flow {
    for (batch in this@flatten) for (event in batch) emit(event)
}
