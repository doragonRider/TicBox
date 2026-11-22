package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ScreenUdpMediaTest {
    @Test fun `lost reference discards queued pictures and deltas until next complete keyframe`() = runBlocking {
        val source = ScreenUdpMedia(clockNanos = { 0 })
        assertTrue(source.video(video(0, key = true)))
        assertFalse(source.recoveryState.needsKeyFrame)
        assertTrue(source.video(video(1)))
        assertFalse(source.video(video(3, gap = true)))
        assertTrue(source.recoveryState.needsKeyFrame)
        assertFalse(source.video(video(4)))
        assertTrue(source.video(video(5, key = true)))
        source.close()
        val frames = source.media.video.toList().filterIsInstance<ToolboxScreenEvent.Frame>().filterNot { it.codecConfig }
        assertEquals(listOf(5L), frames.map { it.ptsMicros })
    }

    @Test fun parityRepairKeepsReferenceChainAndReportsOnlyRecoveryMetadata() = runBlocking {
        val observed = mutableListOf<Pair<String, Map<String, String>>>()
        val source = ScreenUdpMedia(observer = { name, details -> observed += name to details }, clockNanos = { 0 })
        assertTrue(source.video(video(0, key = true)))
        source.media.video.take(3).toList()
        assertTrue(source.video(video(1).copy(fec = ScreenUdpTransport.Fec(16, 4, 3, 1, 20_000))))
        assertFalse(source.recoveryState.needsKeyFrame)
        assertEquals(mapOf("dataShards" to "16", "parityShards" to "4", "recoveredDataShards" to "3", "recoveredBlocks" to "1", "recoveryMicros" to "20"),
            observed.single { it.first == "fec_recovered" }.second)
        source.close()
        assertEquals(1L, (source.media.video.single() as ToolboxScreenEvent.Frame).ptsMicros)
    }

    @Test fun `a short three or five frame burst stays decodable without entering keyframe recovery`() = runBlocking {
        for (burst in listOf(3, 5)) {
            val source = ScreenUdpMedia(clockNanos = { 0 })
            assertTrue(source.video(video(0, key = true)))
            source.media.video.take(3).toList() // The original key has already reached the consumer.
            repeat(burst) { assertTrue(source.video(video(it + 1L))) }
            assertFalse(source.recoveryState.needsKeyFrame)
            assertEquals((1L..burst.toLong()).toList(), source.media.video.take(burst).toList()
                .filterIsInstance<ToolboxScreenEvent.Frame>().map { it.ptsMicros })
            source.cancel()
        }
    }

    @Test fun `a true video backlog still recovers at keyframe without blocking sound`() = runBlocking {
        val observations = mutableListOf<Pair<String, Map<String, String>>>()
        var now = 0L
        val source = ScreenUdpMedia(observer = { event, details -> observations += event to details }, clockNanos = { now })
        assertTrue(source.video(video(0, key = true)))
        assertFalse(source.recoveryState.needsKeyFrame)
        for (id in 1L..7L) assertTrue(source.video(video(id)))
        now = 80_000_000 // Nothing was decoded while they waited.
        assertFalse(source.video(video(8))) // Eight queued batches are the bounded burst allowance.
        assertTrue(source.recoveryState.needsKeyFrame)
        assertEquals(1L, source.recoveryState.acceptedKeyFrames)
        val waiting = observations.last { it.first == "waiting_for_key" }.second
        assertEquals("queue_count_limit", waiting["reason"])
        assertEquals("8", waiting["discardedBatches"])
        source.audio(audio(0))
        val sound = withTimeout(1_000) { source.media.audio.take(3).toList() }
        assertIs<ToolboxScreenEvent.AudioFrame>(sound.last())
        assertFalse(source.video(video(9)))
        assertTrue(source.video(video(10, key = true)))
        val recovered = observations.last { it.first == "key_recovered" }.second
        assertEquals("9", recovered["discardedDeltas"])
        source.close()
        assertEquals(listOf(10L), source.media.video.toList().filterIsInstance<ToolboxScreenEvent.Frame>().filterNot { it.codecConfig }.map { it.ptsMicros })
    }

    @Test fun `metadata distinguishes ingress gaps reference loss and recovery without payload values`() = runBlocking {
        var now = 0L
        val observed = mutableListOf<Pair<String, Map<String, String>>>()
        val source = ScreenUdpMedia(observer = { event, details -> observed += event to details }, clockNanos = { now })
        assertTrue(source.video(video(0, key = true)))
        source.media.video.take(3).toList()
        now = 250_000_000
        assertTrue(source.video(video(1)))
        val ingress = observed.single { it.first == "ingress_gap" }.second
        assertEquals(mapOf("arrivalGapMillis" to "250", "ptsGapMillis" to "0", "frameBytes" to "1", "keyFrame" to "false"), ingress)
        now = 260_000_000
        assertFalse(source.video(video(3, gap = true)))
        now = 300_000_000
        assertFalse(source.video(video(4)))
        now = 360_000_000
        assertTrue(source.video(video(5, key = true)))
        val recovered = observed.last { it.first == "key_recovered" }.second
        assertEquals(mapOf("reason" to "reassembly_gap", "waitMillis" to "100", "discardedDeltas" to "3"), recovered)
        source.cancel()
    }

    @Test fun completeKeyRepairsGapAtomicallyBeforePromptRecoveryCanObserveIt() = runBlocking {
        val duringReset = mutableListOf<UdpVideoRecoveryState>()
        lateinit var source: ScreenUdpMedia
        source = ScreenUdpMedia(observer = { name, details ->
            if (name == "waiting_for_key" && details["reason"] == "reassembly_gap") duringReset += source.recoveryState
        }, clockNanos = { 0 })
        val recovery = UdpVideoRecovery()
        assertTrue(source.video(video(0, key = true)))
        source.media.video.take(3).toList()
        assertTrue(source.video(video(2, key = true, gap = true)))
        assertFalse(duringReset.single().needsKeyFrame)
        assertEquals(UdpVideoRecovery.Action.NONE, recovery.poll(duringReset.single(), 100))
        assertFalse(source.recoveryState.needsKeyFrame)
        assertEquals(2L, source.recoveryState.acceptedKeyFrames)
        assertEquals(UdpVideoRecovery.Action.NONE, recovery.poll(source.recoveryState, 100))
        assertFalse(source.video(video(4, gap = true)))
        assertFalse(duringReset.last().needsKeyFrame)
        assertTrue(source.recoveryState.needsKeyFrame)
        assertEquals(UdpVideoRecovery.Action.REQUEST_KEYFRAME, recovery.poll(source.recoveryState, 200))
        source.cancel()
    }

    @Test fun reassemblyGapMetadataSurvivesWhileWaitingWithoutExposingMediaBytes() = runBlocking {
        val observed = mutableListOf<Pair<String, Map<String, String>>>()
        val source = ScreenUdpMedia(observer = { name, details -> observed += name to details }, clockNanos = { 0 })
        assertTrue(source.video(video(0, key = true)))
        source.media.video.take(3).toList()
        val gap = ScreenUdpTransport.Gap(
            skippedMessages = 2, incompleteMessages = 1, missingFragments = 3,
            missingMessageTimeout = true, fragmentTimeout = true, frameDeadline = false,
            maxAgeMillis = 180, maxNoProgressMillis = 155, capacityEvictions = 1, capacityRejectedFragments = 2,
        )
        assertFalse(source.video(video(3, gap = true).copy(gap = gap)))
        assertFalse(source.video(video(5, gap = true).copy(gap = gap.copy(skippedMessages = 1))))
        val snapshots = observed.filter { it.first == "reassembly_gap" }.map { it.second }
        assertEquals(2, snapshots.size)
        assertEquals(mapOf(
            "skippedMessages" to "2", "incompleteMessages" to "1", "missingFragments" to "3",
            "missingMessageTimeout" to "true", "fragmentTimeout" to "true", "frameDeadline" to "false",
            "maxAgeMillis" to "180", "maxNoProgressMillis" to "155",
            "capacityEvictions" to "1", "capacityRejectedFragments" to "2", "independentFrame" to "false",
            "laterComplete" to "false",
        ), snapshots.first())
        assertEquals("1", snapshots.last()["skippedMessages"])
        assertTrue(source.recoveryState.needsKeyFrame)
        assertTrue(source.video(video(6, key = true)))
        source.cancel()
    }

    @Test fun `admission age breach requests a new key but diagnostic failures do not change delivery`() = runBlocking {
        var now = 0L
        val observed = mutableListOf<Pair<String, Map<String, String>>>()
        val source = ScreenUdpMedia(observer = { event, details -> observed += event to details }, clockNanos = { now })
        assertTrue(source.video(video(0, key = true)))
        now = 150_000_000
        assertFalse(source.video(video(1)))
        assertEquals("queue_age_limit", observed.last { it.first == "waiting_for_key" }.second["reason"])
        assertTrue(source.recoveryState.needsKeyFrame)
        assertTrue(source.video(video(2, key = true)))
        source.cancel()
        val brokenObserver = ScreenUdpMedia(observer = { _, _ -> error("diagnostic fixture") }, clockNanos = { 0 })
        assertTrue(brokenObserver.video(video(0, key = true)))
        assertTrue(brokenObserver.video(video(1)))
        brokenObserver.close()
        assertEquals(listOf(0L, 1L), brokenObserver.media.video.toList()
            .filterIsInstance<ToolboxScreenEvent.Frame>().filterNot { it.codecConfig }.map { it.ptsMicros })
    }

    @Test fun `audio latency remains bounded when consumer stalls and null capture is explicit`() = runBlocking {
        val source = ScreenUdpMedia(clockNanos = { 0 })
        repeat(30) { source.audio(audio(it.toLong())) }
        source.close()
        val frames = source.media.audio.toList().filterIsInstance<ToolboxScreenEvent.AudioFrame>().filterNot { it.codecConfig }
        assertEquals((18L..29L).toList(), frames.map { it.ptsMicros })
        val disabled = ScreenUdpMedia(clockNanos = { 0 })
        disabled.audio(ScreenUdpTransport.Message(0, ToolboxProtocol.AUDIO_CONFIG, bundle(ToolboxProtocol.AUDIO_CONFIG to "{}".toByteArray()), false))
        disabled.close()
        assertEquals(listOf(ToolboxScreenEvent.AudioConfiguration(null)), disabled.media.audio.toList())
    }

    @Test fun `malformed or cross stream bundles cannot reach decoders`() {
        val source = ScreenUdpMedia(clockNanos = { 0 })
        assertEquals("screen_udp_protocol_mismatch", assertFailsWith<ToolboxException> { source.video(audio(0)) }.code)
        assertEquals("screen_udp_protocol_mismatch", assertFailsWith<ToolboxException> {
            source.video(ScreenUdpTransport.Message(0, ToolboxProtocol.VIDEO_FRAME, bundle(ToolboxProtocol.CONTROL to byteArrayOf(1)), false))
        }.code)
        source.cancel()
    }

    @Test fun ingressSamplesCountCompleteFramesBeforeWaitingForKeyDropsThem() {
        var now = 0L
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val source = ScreenUdpMedia(observer = { name, details -> events += name to details }, clockNanos = { now })
        assertFalse(source.video(video(1)))
        now = 1_000_000_000L
        assertFalse(source.video(video(2)))
        now = 2_000_000_000L
        assertFalse(source.video(video(3)))
        val sample = events.single { it.first == "ingress_sample" }.second
        assertEquals("3", sample["completedFrames"])
        assertEquals("3", sample["encodedBytes"])
        assertEquals("2000", sample["elapsedMillis"])
        assertEquals("1000", sample["largestArrivalGapMillis"])
        assertTrue(source.recoveryState.needsKeyFrame)
        source.cancel()
    }

    @Test fun highRateTinyBacklogDoesNotDiscardTheGopBeforeTimeOrByteBudgetsAreReached() = runBlocking {
        var now = 0L
        val observed = mutableListOf<Pair<String, Map<String, String>>>()
        val source = ScreenUdpMedia(observer = { name, details -> observed += name to details }, clockNanos = { now }, maxFps = 120)
        assertTrue(source.video(video(0, key = true)))
        source.media.video.take(3).toList()
        for (id in 1L..18L) {
            now = (id - 1) * 8_000_000L
            assertTrue(source.video(video(id)))
        }
        assertFalse(source.recoveryState.needsKeyFrame)
        assertFalse(observed.any { it.first == "waiting_for_key" && it.second["reason"]?.startsWith("queue_") == true })
        source.close()
        assertEquals((1L..18L).toList(), source.media.video.toList().filterIsInstance<ToolboxScreenEvent.Frame>().map { it.ptsMicros })
        val dequeue = observed.first { it.first == "dequeue_delay" }.second
        assertEquals("136", dequeue["residenceMillis"])
        assertEquals("17", dequeue["remainingBatches"])
        assertEquals("1", dequeue["batchBytes"])
    }

    @Test fun highRateQueueFailurePublishesItsAtomicAdmissionSnapshot() {
        var now = 0L
        val observed = mutableListOf<Pair<String, Map<String, String>>>()
        val source = ScreenUdpMedia(observer = { name, details -> observed += name to details }, clockNanos = { now }, maxFps = 120)
        assertTrue(source.video(video(0, key = true)))
        for (id in 1L..22L) assertTrue(source.video(video(id)))
        now = 90_000_000
        assertFalse(source.video(video(23)))
        val failure = observed.last { it.first == "waiting_for_key" }.second
        assertEquals("queue_count_limit", failure["reason"])
        assertEquals("23", failure["queuedBatches"])
        assertEquals("23", failure["maximumBatches"])
        assertEquals("90", failure["oldestQueuedAgeMillis"])
        assertEquals("24", failure["queuedBytes"]) // One CSD byte plus 23 one-byte video frames.
        assertEquals("1", failure["incomingBytes"])
        assertEquals("0", failure["queuedPtsSpanMillis"])
        assertTrue(source.recoveryState.needsKeyFrame)
        source.cancel()
    }

    @Test fun dequeueDiagnosticsOnlyObserveResidenceWithoutChangingProducerRecoveryState() = runBlocking {
        var now = 0L
        val observed = mutableListOf<Pair<String, Map<String, String>>>()
        val source = ScreenUdpMedia(observer = { name, details -> observed += name to details }, clockNanos = { now })
        assertTrue(source.video(video(0, key = true)))
        now = 15_000_000
        source.media.video.take(3).toList()
        assertFalse(observed.any { it.first == "dequeue_delay" })
        assertTrue(source.video(video(1)))
        now = 45_000_000
        source.close()
        assertEquals(1L, (source.media.video.single() as ToolboxScreenEvent.Frame).ptsMicros)
        assertEquals("30", observed.single { it.first == "dequeue_delay" }.second["residenceMillis"])
        assertFalse(source.recoveryState.needsKeyFrame)
    }

    companion object {
        internal fun video(id: Long, key: Boolean = false, gap: Boolean = false): ScreenUdpTransport.Message {
            val payload = if (key) bundle(
                ToolboxProtocol.VIDEO_CONFIG to buildJsonObject { put("codec", "h264"); put("width", 480); put("height", 480) }.toString().toByteArray(),
                ToolboxProtocol.VIDEO_FRAME to frame(Long.MIN_VALUE, byteArrayOf(0x67)),
                ToolboxProtocol.VIDEO_FRAME to frame((1L shl 62) or id, byteArrayOf(0x65)),
            ) else bundle(ToolboxProtocol.VIDEO_FRAME to frame(id, byteArrayOf(0x41)))
            return ScreenUdpTransport.Message(id, ToolboxProtocol.VIDEO_FRAME, payload, gap)
        }
        internal fun audio(id: Long): ScreenUdpTransport.Message = ScreenUdpTransport.Message(id, ToolboxProtocol.AUDIO_FRAME, bundle(
            ToolboxProtocol.AUDIO_CONFIG to "{\"codec\":\"opus\"}".toByteArray(),
            ToolboxProtocol.AUDIO_FRAME to frame(Long.MIN_VALUE, "OpusHead".toByteArray() + ByteArray(11)),
            ToolboxProtocol.AUDIO_FRAME to frame(id, byteArrayOf(1, 2)),
        ), false)
        internal fun bundle(vararg packets: Pair<Int, ByteArray>): ByteArray = ByteArrayOutputStream().also { stream -> packets.forEach { ToolboxProtocol.write(stream, it.first, it.second) } }.toByteArray()
        private fun frame(flags: Long, bytes: ByteArray) = ByteBuffer.allocate(8 + bytes.size).putLong(flags).put(bytes).array()
    }
}
