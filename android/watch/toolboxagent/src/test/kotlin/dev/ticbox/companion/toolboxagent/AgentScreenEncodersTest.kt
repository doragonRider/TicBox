package dev.ticbox.companion.toolboxagent

import kotlin.test.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class AgentScreenEncodersTest {
    @Test fun sizeFollowsScrcpyBeforeAnyEncodingFailure() {
        // A 1440×3200 panel; these are the sizes scrcpy computes for it.
        assertEquals(1152 to 2560, scrcpyVideoSize(1440, 3200, 2560, 16))
        assertEquals(1072 to 2400, scrcpyVideoSize(1440, 3200, 2400, 16))
        assertEquals(2560 to 1152, scrcpyVideoSize(3200, 1440, 2560, 16))
        assertEquals(1080 to 2400, scrcpyVideoSize(1080, 2400, 4096, 2), "never enlarged")
        assertEquals(16 to 16, scrcpyVideoSize(8, 8, 0, 16), "at least one alignment step")
    }

    private fun request(vararg pairs: Pair<String, Any>) = ScreenRequest(buildJsonObject {
        pairs.forEach { (key, value) -> when (value) { is Boolean -> put(key, value); is Int -> put(key, value); else -> put(key, value.toString()) } }
    })

    @Test fun limitsLowerOnlyWhatTheEncoderDeclaresAndHevcFallsBackToAvc() {
        val mtkPhone = mapOf(
            "h264" to ScreenEncoderLimit("h264", "c2.mtk.avc.encoder", true, 1152, 2560, maxFps = 85, maxBitRate = 300_000_000),
            "h265" to ScreenEncoderLimit("h265", "c2.mtk.hevc.encoder", true, 1152, 2560, maxFps = 120, maxBitRate = 40_000_000),
        )
        val avc = request("limitToEncoder" to true, "maxFps" to 120, "bitRate" to 80_000_000)
        avc.limit(mtkPhone::getValue)
        assertEquals(85, avc.fps); assertEquals(80_000_000, avc.bitRate); assertEquals("h264", avc.codec)
        assertTrue("max_fps=85" in avc.serverOptions("id").split(' '))
        val hevc = request("limitToEncoder" to true, "maxFps" to 120, "bitRate" to 80_000_000, "videoCodec" to "h265")
        hevc.limit(mtkPhone::getValue)
        assertEquals(120, hevc.fps); assertEquals(40_000_000, hevc.bitRate)
        assertEquals(80_000_000, hevc.requestedBitRate)

        val noHevc = request("limitToEncoder" to true, "maxFps" to 120, "bitRate" to 8_000_000, "videoCodec" to "h265")
        noHevc.limit { if (it == "h265") ScreenEncoderLimit("h265") else mtkPhone.getValue(it) }
        assertEquals("h264", noHevc.codec); assertEquals(85, noHevc.fps)
        assertTrue("video_codec=h264" in noHevc.serverOptions("id").split(' '))

        val off = request("maxFps" to 120, "bitRate" to 80_000_000, "videoCodec" to "h265")
        off.limit(mtkPhone::getValue)
        assertEquals(120, off.fps); assertEquals(80_000_000, off.bitRate); assertEquals("h265", off.codec)
        val unknown = request("limitToEncoder" to true, "maxFps" to 120)
        unknown.limit(null)
        assertEquals(120, unknown.fps)
        val none = request("limitToEncoder" to true, "maxFps" to 120, "videoCodec" to "h264")
        none.limit { ScreenEncoderLimit(it) }
        assertEquals(120, none.fps); assertEquals("h264", none.codec)
    }

    @Test fun repeatTimingFollowsTheLimitedFrameRate() {
        val limited = request("limitToEncoder" to true, "maxFps" to 120, "udp" to true, "holdWifi" to true)
        limited.limit { ScreenEncoderLimit(it, "fixture", true, 64, 64, maxFps = 60, maxBitRate = 20_000_000) }
        assertEquals(33_334, limited.repeatAfterMicros)
        assertEquals(16_667, limited.tailRepeatMicros)
        assertTrue("video_tail_repeat_us=16667" in limited.serverOptions("id").split(' '))
    }

    @Test fun mediaTekHevcGetsAQpCeilingThatFollowsTheBitsEachPixelGets() {
        val mtkPhone = ScreenEncoderLimit("h265", "c2.mtk.hevc.encoder", true, 1072, 2400, 120, 40_000_000)
        assertEquals("video-qp-max=35,bitrate-mode=1", screenEncoderDefaults(mtkPhone, 40_000_000, 120))
        assertEquals("video-qp-max=41,bitrate-mode=1", screenEncoderDefaults(mtkPhone, 20_000_000, 120))
        assertEquals("video-qp-max=35,bitrate-mode=1", screenEncoderDefaults(mtkPhone, 20_000_000, 60))
        assertEquals("video-qp-max=45,bitrate-mode=1", screenEncoderDefaults(mtkPhone, 4_000_000, 120))
        assertEquals("video-qp-max=30,bitrate-mode=1", screenEncoderDefaults(mtkPhone.copy(width = 576, height = 1280), 40_000_000, 30))
        assertNull(screenEncoderDefaults(mtkPhone.copy(codec = "h264", encoder = "c2.mtk.avc.encoder"), 40_000_000, 120))
        assertNull(screenEncoderDefaults(mtkPhone.copy(encoder = "c2.qti.hevc.encoder"), 40_000_000, 120))
        assertNull(screenEncoderDefaults(ScreenEncoderLimit("h265"), 40_000_000, 120))
        // Ours come before the phone's, so a phone option of the same name wins.
        val request = request("udp" to true, "holdWifi" to true, "maxFps" to 120, "bitRate" to 40_000_000, "videoCodec" to "h265",
            "videoCodecOptions" to "video-qp-max=38")
        request.tune { mtkPhone }
        assertEquals("video-qp-max=35,bitrate-mode=1", request.encoderDefaults)
        assertTrue(request.serverOptions("id").endsWith(",max-bframes=0,video-qp-max=35,bitrate-mode=1,video-qp-max=38"))
        request.tune(null)
        assertEquals("", request.encoderDefaults)
        // Not over Bluetooth or ADB's own stream, whatever the encoder.
        val reliable = request("maxFps" to 10, "bitRate" to 300_000, "videoCodec" to "h265")
        reliable.tune { mtkPhone }
        assertEquals("", reliable.encoderDefaults)
        assertFalse("video-qp-max" in reliable.serverOptions("id"))
    }

    @Test fun descriptionOmitsSizeForMissingEncoders() {
        assertEquals("""{"codec":"h265","supported":false}""", ScreenEncoderLimit("h265").json().toString())
        val avc = ScreenEncoderLimit("h264", "c2.mtk.avc.encoder", true, 1152, 2560, 85, 300_000_000).json().toString()
        assertEquals("""{"codec":"h264","supported":true,"encoder":"c2.mtk.avc.encoder","hardware":true,"width":1152,"height":2560,"maxFps":85,"maxBitRate":300000000}""", avc)
    }
}
