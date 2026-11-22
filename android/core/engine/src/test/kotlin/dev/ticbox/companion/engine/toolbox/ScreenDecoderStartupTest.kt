package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.ToolboxScreenEvent
import dev.ticbox.companion.protocol.ScreenUdpTransport
import dev.ticbox.companion.protocol.ToolboxProtocol
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlinx.coroutines.flow.*

class ScreenDecoderStartupTest {
    @Test fun sourceRelayMetadataOnlyReachesReceiptsWhenDiagnosticsWereSelected() = runBlocking {
        for (enabled in listOf(false, true)) {
            val source = ScreenUdpMedia(observer = if (enabled) { _, _ -> } else null, requireDecoderReady = true, requireBootstrap = true)
            val config = configuration.copy(generation = 1)
            source.prepareBootstrap(config); source.media.video.first(); source.decoderReady(config, 0)
            val message = versionedVideo(100, 1, key = true, relay = 200).copy(completedNanos = 10_000, receiveWorkNanos = 2_000)
            source.video(message)
            val frame = source.media.video.take(3).toList().last() as ToolboxScreenEvent.Frame
            assertEquals(if (enabled) 200L else null, frame.receipt?.sourceRelayMicros)
            assertEquals(10_000L, frame.receipt?.completedNanos)
            source.cancel()
        }
    }

    @Test fun acknowledgedBootstrapDeduplicatesKeysAndKeepsRotationsAndStaleDatagramsSeparate() = runBlocking {
        val acknowledgements = mutableListOf<Pair<Long, Long>>()
        val source = ScreenUdpMedia(requireDecoderReady = true, requireBootstrap = true, onBootstrapKey = { generation, pts -> acknowledgements += generation to pts })
        val first = configuration.copy(generation = 1)
        source.prepareBootstrap(first)
        assertEquals(first, source.media.video.first())
        assertNull(source.decoderReady(configuration, 1))
        assertTrue(source.recoveryState.waitingForDecoder)
        assertEquals(1L, source.decoderReady(first, 1))
        assertTrue(source.video(versionedVideo(100, 1, key = true)))
        assertEquals(100L, (source.media.video.take(3).toList().last() as ToolboxScreenEvent.Frame).ptsMicros)
        assertFalse(source.video(versionedVideo(100, 1, key = true)))
        assertFalse(source.video(versionedVideo(100, 1, key = true).copy(discontinuity = true)))
        assertFalse(source.recoveryState.needsKeyFrame)
        assertEquals(listOf(1L to 100L, 1L to 100L, 1L to 100L), acknowledgements)
        assertEquals(1L, source.recoveryState.acceptedKeyFrames)
        assertTrue(source.video(versionedVideo(108, 1)))
        assertEquals(108L, (source.media.video.first() as ToolboxScreenEvent.Frame).ptsMicros)
        val rotated = first.copy(generation = 2)
        source.prepareBootstrap(rotated)
        assertEquals(rotated, source.media.video.first())
        assertNull(source.decoderReady(first, 1))
        assertFalse(source.video(versionedVideo(100, 1, key = true).copy(discontinuity = true)))
        assertTrue(source.recoveryState.waitingForDecoder)
        assertEquals(2L, source.decoderReady(rotated, 1))
        assertTrue(source.video(versionedVideo(100, 2, key = true)))
        source.media.video.take(3).toList()
        assertEquals(2L to 100L, acknowledgements.last())
        assertFalse(source.recoveryState.needsKeyFrame)
        source.prepareBootstrap(first) // Reliable stale metadata cannot rewind a newer capture.
        assertFalse(source.recoveryState.waitingForDecoder)
        assertFalse(source.video(versionedVideo(200, 1, key = true).copy(discontinuity = true)))
        assertFalse(source.recoveryState.needsKeyFrame) // Reject before applying the stale gap.
        source.cancel()
    }

    private fun versionedVideo(pts: Long, generation: Long, key: Boolean = false, relay: Long? = null): ScreenUdpTransport.Message {
        val raw = ScreenUdpMediaTest.video(pts, key)
        val packets = raw.bytes.inputStream().use { input -> generateSequence { ToolboxProtocol.read(input) }.toList() }
        val config = buildJsonObject { put("codec", "h264"); put("width", 480); put("height", 480); put("generation", generation); relay?.let { put("sourceRelayMicros", it) } }.toString().toByteArray()
        return raw.copy(bytes = ScreenUdpMediaTest.bundle(*(listOf(ToolboxProtocol.VIDEO_CONFIG to config) + packets.filter { it.type != ToolboxProtocol.VIDEO_CONFIG }.map { it.type to it.bytes }).toTypedArray()))
    }

    private val configuration = ToolboxScreenEvent.Configuration("h264", 480, 480)
    private fun metadata() = ScreenUdpTransport.Message(0, ToolboxProtocol.VIDEO_FRAME,
        ScreenUdpMediaTest.bundle(ToolboxProtocol.VIDEO_CONFIG to """{"codec":"h264","width":480,"height":480}""".toByteArray()), false)

    @Test fun coldDecoderReceivesMetadataBeforeFramesAndCannotOverflowAnEncodedQueue() = runBlocking {
        var now = 0L
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val source = ScreenUdpMedia(observer = { name, details -> events += name to details }, clockNanos = { now }, maxFps = 120, requireDecoderReady = true)
        val recovery = UdpVideoRecovery(500)
        assertFalse(source.video(metadata()))
        assertEquals(configuration, source.media.video.first())
        // A deliberately slow 1-second codec startup: frames do not create a backlog or reset storm.
        assertFalse(source.video(ScreenUdpMediaTest.video(1, key = true)))
        repeat(120) {
            now += 8_333_333
            assertFalse(source.video(ScreenUdpMediaTest.video(it + 2L)))
            assertEquals(UdpVideoRecovery.Action.NONE, recovery.poll(source.recoveryState, now / 1_000_000))
        }
        assertTrue(source.recoveryState.waitingForDecoder)
        assertFalse(events.any { it.first == "waiting_for_key" && it.second["reason"]?.startsWith("queue_") == true })
        source.decoderReady(configuration.copy(width = 320), 10) // stale acknowledgement cannot unlock this codec
        assertTrue(source.recoveryState.waitingForDecoder)
        source.decoderReady(configuration, 1_000)
        assertFalse(source.recoveryState.waitingForDecoder)
        assertEquals(UdpVideoRecovery.Action.REQUEST_KEYFRAME, recovery.poll(source.recoveryState, 1_000))
        val ready = events.single { it.first == "decoder_ready" }.second
        assertEquals("121", ready["warmupDiscardedFrames"])
        assertEquals("1000", ready["initializationMillis"])
        assertFalse(source.video(ScreenUdpMediaTest.video(122)))
        assertTrue(source.video(ScreenUdpMediaTest.video(123, key = true)))
        assertEquals(123L, (source.media.video.take(3).toList().last() as ToolboxScreenEvent.Frame).ptsMicros)
        // The original 150ms runtime guard still applies after warmup.
        assertTrue(source.video(ScreenUdpMediaTest.video(124)))
        now += 151_000_000
        assertFalse(source.video(ScreenUdpMediaTest.video(125)))
        assertEquals("queue_age_limit", events.last { it.first == "waiting_for_key" }.second["reason"])
        source.cancel()
    }

    @Test fun configurationOnlyStartupDoesNotAskForKeysAndCloseCancelsPendingPreparation() = runBlocking {
        val source = ScreenUdpMedia(requireDecoderReady = true)
        source.video(metadata())
        source.decoderReady(configuration, 3)
        assertEquals(UdpVideoRecovery.Action.NONE, UdpVideoRecovery(500).poll(source.recoveryState, 20_000))
        source.cancel()
        assertFailsWith<kotlinx.coroutines.CancellationException> { source.media.video.toList() }
        source.decoderReady(configuration, 8)
        assertFalse(source.video(ScreenUdpMediaTest.video(1, key = true)))
    }

    @Test fun rotationRequiresItsOwnReadinessAndKeepsOnlyTheLatestMetadata() = runBlocking {
        val source = ScreenUdpMedia(requireDecoderReady = true)
        source.video(metadata())
        source.decoderReady(configuration, 3)
        source.video(metadata().copy(bytes = ScreenUdpMediaTest.bundle(ToolboxProtocol.VIDEO_CONFIG to
            """{"codec":"h264","width":320,"height":480}""".toByteArray())))
        source.decoderReady(configuration, 3)
        assertTrue(source.recoveryState.waitingForDecoder)
        assertEquals(configuration.copy(width = 320), source.media.video.first())
        source.decoderReady(configuration.copy(width = 320), 3)
        assertFalse(source.recoveryState.waitingForDecoder)
        source.cancel()
    }
}
