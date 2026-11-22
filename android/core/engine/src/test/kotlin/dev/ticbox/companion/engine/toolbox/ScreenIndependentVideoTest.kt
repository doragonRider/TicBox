package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.ToolboxScreenEvent
import dev.ticbox.companion.protocol.ToolboxProtocol
import java.nio.ByteBuffer
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.*

class ScreenIndependentVideoTest {
    private val config = """{"codec":"h264","width":480,"height":480,"generation":1}""".toByteArray()
    private fun frame(pts: Long, key: Boolean = true, csd: Boolean = false) = ByteBuffer.allocate(9)
        .putLong(pts or (if (key) 1L shl 62 else 0) or (if (csd) Long.MIN_VALUE else 0)).put(1).array()
    private fun bundle(vararg frames: ByteArray, metadata: ByteArray = config) = ScreenUdpMediaTest.bundle(
        ToolboxProtocol.VIDEO_CONFIG to metadata, *frames.map { ToolboxProtocol.VIDEO_FRAME to it }.toTypedArray())
    private fun valid(pts: Long) = bundle(frame(0, key = false, csd = true), frame(pts))
    private fun message(pts: Long) = ScreenUdpMediaTest.video(pts, key = true).copy(bytes = valid(pts))

    @Test fun onlyOneNonemptyKeyAfterCompleteCodecConfigurationCanRestart() {
        val candidate = valid(100)
        val value = assertNotNull(independentVideoBoundary(candidate))
        assertEquals(ToolboxScreenEvent.Configuration("h264", 480, 480, 1), value.configuration)
        assertEquals(100L, value.ptsMicros)
        assertNull(independentVideoBoundary(bundle(frame(100)))) // no CSD
        assertNull(independentVideoBoundary(bundle(frame(0, csd = true), frame(100, key = false))))
        assertNull(independentVideoBoundary(bundle(frame(100), frame(0, csd = true))))
        assertNull(independentVideoBoundary(bundle(frame(0, csd = true), frame(100), frame(101))))
        assertNull(independentVideoBoundary(bundle(frame(0, csd = true)))) // no picture
        assertNull(independentVideoBoundary(candidate + byteArrayOf(1))) // malformed trailing packet
        for (length in 0 until candidate.size) assertNull(independentVideoBoundary(candidate.copyOf(length)))
        assertNull(independentVideoBoundary(bundle(frame(0, csd = true), frame(100), metadata = ByteArray(4_097))))
        assertNull(independentVideoBoundary(bundle(frame(0, csd = true), frame(100), metadata = "{}".toByteArray())))
        val overflow = ByteBuffer.allocate(5).put(ToolboxProtocol.VIDEO_CONFIG.toByte()).putInt(Int.MAX_VALUE).array()
        assertNull(independentVideoBoundary(overflow))
    }

    @Test fun futureKeysMustMatchPreparedGenerationAndCannotReplayAlreadyAcceptedPts() = runBlocking {
        val source = ScreenUdpMedia(requireDecoderReady = true, requireBootstrap = true)
        val configuration = ToolboxScreenEvent.Configuration("h264", 480, 480, 1)
        assertFalse(source.canRestartAt(ToolboxProtocol.VIDEO_FRAME, valid(100)))
        source.prepareBootstrap(configuration); source.media.video.first()
        assertFalse(source.canRestartAt(ToolboxProtocol.VIDEO_FRAME, valid(100)))
        source.decoderReady(configuration, 0)
        assertTrue(source.canRestartAt(ToolboxProtocol.VIDEO_FRAME, valid(100)))
        assertTrue(source.video(message(100))); source.media.video.take(3).toList()
        assertFalse(source.canRestartAt(ToolboxProtocol.VIDEO_FRAME, valid(100)))
        assertFalse(source.canRestartAt(ToolboxProtocol.VIDEO_FRAME, valid(99)))
        assertTrue(source.canRestartAt(ToolboxProtocol.VIDEO_FRAME, valid(101)))
        assertFalse(source.canRestartAt(ToolboxProtocol.AUDIO_FRAME, valid(101)))
        val wrongGeneration = bundle(frame(0, csd = true), frame(101), metadata = config.toString(Charsets.UTF_8).replace("generation\":1", "generation\":2").toByteArray())
        assertFalse(source.canRestartAt(ToolboxProtocol.VIDEO_FRAME, wrongGeneration))
        val rotated = configuration.copy(generation = 2)
        source.prepareBootstrap(rotated); source.media.video.first(); source.decoderReady(rotated, 0)
        assertFalse(source.canRestartAt(ToolboxProtocol.VIDEO_FRAME, valid(200)))
        assertTrue(source.canRestartAt(ToolboxProtocol.VIDEO_FRAME, wrongGeneration))
        source.close()
        assertFalse(source.canRestartAt(ToolboxProtocol.VIDEO_FRAME, wrongGeneration))
    }

    @Test fun candidateInspectionDoesNotMutateReferenceChainBeforeDelivery() = runBlocking {
        val source = ScreenUdpMedia(requireDecoderReady = true, requireBootstrap = true)
        val configuration = ToolboxScreenEvent.Configuration("h264", 480, 480, 1)
        source.prepareBootstrap(configuration); source.media.video.first(); source.decoderReady(configuration, 0)
        source.video(message(100)); source.media.video.take(3).toList()
        val state = source.recoveryState
        assertTrue(source.canRestartAt(ToolboxProtocol.VIDEO_FRAME, valid(101)))
        assertEquals(state, source.recoveryState)
        assertTrue(source.video(message(101).copy(discontinuity = true)))
        assertEquals(101L, (source.media.video.take(3).toList().last() as ToolboxScreenEvent.Frame).ptsMicros)
        assertFalse(source.recoveryState.needsKeyFrame)
        source.cancel()
    }
}
