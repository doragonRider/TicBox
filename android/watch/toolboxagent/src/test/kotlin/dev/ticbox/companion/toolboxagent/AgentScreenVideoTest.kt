package dev.ticbox.companion.toolboxagent

import dev.ticbox.companion.protocol.ToolboxProtocol
import dev.ticbox.companion.protocol.number
import dev.ticbox.companion.protocol.text
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.OutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AgentScreenVideoTest {
    @Test fun udpRequestsCapabilityCheckedCbrWithoutChangingTheBitrateOrReliableSessions() {
        for (codec in listOf("h264", "h265")) {
            val args = buildJsonObject { put("udp", true); put("videoCodec", codec); put("bitRate", 20_000_000) }
            val options = ScreenRequest(args).serverOptions("id").split(' ')
            assertTrue("video_prefer_cbr=true" in options)
            assertTrue("video_bit_rate=20000000" in options)
            assertFalse(ScreenRequest(buildJsonObject { put("videoCodec", codec) }).serverOptions("id").contains("video_prefer_cbr"))
        }
    }

    @Test fun `UDP idle repeats follow two frame periods with a typed long and capped cost`() {
        for ((fps, delay) in listOf(1 to 100000L, 20 to 100000L, 30 to 66667L, 60 to 33334L, 120 to 16667L)) {
            val request = ScreenRequest(buildJsonObject { put("udp", true); put("maxFps", fps) })
            assertEquals(delay, request.repeatAfterMicros)
            assertTrue("repeat-previous-frame-after:long=$delay" in request.serverOptions("id"))
            assertFalse("repeat-previous-frame-after" in ScreenRequest(buildJsonObject { put("maxFps", fps) }).serverOptions("id"))
        }
    }
    @Test fun longGopRequiresBothFecAndLightRecoveryAndEncoderGetsRequestedRate() {
        fun options(fec: Boolean, light: Boolean) = ScreenRequest(buildJsonObject {
            put("udp", true); put("holdWifi", true); put("maxFps", 120)
            if (fec) put("udpFec", "rs_16_4")
            put("udpLightKeyframe", light)
        }).serverOptions("id")
        assertTrue("video_codec_options=i-frame-interval=5,frame-rate=120,repeat-previous-frame-after:long=16667,max-bframes=0" in options(true, true).split(' '))
        for ((fec, light) in listOf(false to false, true to false, false to true))
            assertTrue("video_codec_options=i-frame-interval=1,frame-rate=120,repeat-previous-frame-after:long=16667,max-bframes=0" in options(fec, light).split(' '))
    }

    @Test fun udpExtensionsAreExplicitAndRejectedOutsideTheNegotiatedTransport() {
        val ordinary = ScreenRequest(buildJsonObject {})
        assertEquals(dev.ticbox.companion.protocol.ScreenUdpTransport.VideoProtection.NONE, ordinary.videoProtection)
        assertFalse(ordinary.udpLightKeyframe)
        val upgraded = ScreenRequest(buildJsonObject {
            put("udp", true); put("holdWifi", true); put("udpFec", "rs_16_4"); put("udpLightKeyframe", true)
        })
        upgraded.validateTransport(2000)
        assertEquals(dev.ticbox.companion.protocol.ScreenUdpTransport.VideoProtection.RS_16_4, upgraded.videoProtection)
        assertTrue(upgraded.udpLightKeyframe)
        assertEquals("screen_udp_unavailable", assertFailsWith<ScreenRelayException> {
            ScreenRequest(buildJsonObject { put("udpFec", "unknown") })
        }.code)
        for (args in listOf(buildJsonObject { put("udpFec", "rs_16_4") }, buildJsonObject { put("udpLightKeyframe", true) })) {
            assertEquals("screen_udp_unavailable", assertFailsWith<ScreenRelayException> { ScreenRequest(args).validateTransport(2000) }.code)
        }
    }

    @Test fun `scrcpy 4_1 codec session configuration and keyframe retain their actual boundaries and flags`() {
        // Streamer.java v4.1: codec u32, session(flags u32, width u32, height u32), then frames.
        val source = bytes {
            writeInt(0x68323634)
            writeInt(Int.MIN_VALUE); writeInt(480); writeInt(480)
            frame(1L shl 62, byteArrayOf(0, 0, 0, 1, 0x67))
            frame((1L shl 61) or 123_456, byteArrayOf(0, 0, 0, 1, 0x65))
            frame(124_456, byteArrayOf(0x41))
        }
        val output = ByteArrayOutputStream()
        // Every read can split a wire field; the parser must not assume socket packet boundaries.
        val fragmented = object : FilterInputStream(source.inputStream()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int) = super.read(bytes, offset, minOf(length, 1))
        }
        relayScrcpyVideo(fragmented, AgentWire(byteArrayOf().inputStream(), output))
        val input = output.toByteArray().inputStream()
        val configuration = ToolboxProtocol.required(input)
        assertEquals(ToolboxProtocol.VIDEO_CONFIG, configuration.type)
        assertEquals(480L, configuration.objectValue().number("width"))
        assertEquals(480L, configuration.objectValue().number("height"))
        // Toolbox v3 retains its own older flag positions; the agent translates at the boundary.
        val config = ToolboxProtocol.required(input)
        assertEquals(ToolboxProtocol.VIDEO_FRAME, config.type)
        assertEquals(Long.MIN_VALUE, ByteBuffer.wrap(config.bytes).long)
        assertContentEquals(byteArrayOf(0, 0, 0, 1, 0x67), config.bytes.copyOfRange(8, config.bytes.size))
        val key = ToolboxProtocol.required(input)
        assertEquals((1L shl 62) or 123_456, ByteBuffer.wrap(key.bytes).long)
        assertContentEquals(byteArrayOf(0, 0, 0, 1, 0x65), key.bytes.copyOfRange(8, key.bytes.size))
        assertEquals(124_456L, ByteBuffer.wrap(ToolboxProtocol.required(input).bytes).long)
        assertEquals(null, ToolboxProtocol.read(input))
    }

    @Test fun `an h265 stream names its codec in the configuration`() {
        val packets = relay(bytes {
            writeInt(0x68323635)
            writeInt(Int.MIN_VALUE); writeInt(2560); writeInt(1440)
            frame(1L shl 61, byteArrayOf(1))
        })
        assertEquals("h265", packets.first().objectValue().text("codec"))
        assertEquals(ToolboxProtocol.VIDEO_FRAME, packets.last().type)
    }

    @Test fun `sound keeps the frame flags and its codec is announced first`() {
        val output = ByteArrayOutputStream()
        relayScrcpyAudio(bytes {
            writeInt(0x6f707573)
            frame(1L shl 62, byteArrayOf(1, 2))
            frame(1_000, byteArrayOf(3))
        }.inputStream(), AgentWire(byteArrayOf().inputStream(), output))
        val input = output.toByteArray().inputStream()
        val configuration = ToolboxProtocol.required(input)
        assertEquals(ToolboxProtocol.AUDIO_CONFIG, configuration.type)
        assertEquals("opus", configuration.objectValue().text("codec"))
        val head = ToolboxProtocol.required(input)
        assertEquals(ToolboxProtocol.AUDIO_FRAME, head.type)
        assertEquals(Long.MIN_VALUE, ByteBuffer.wrap(head.bytes).long)
        assertContentEquals(byteArrayOf(1, 2), head.bytes.copyOfRange(8, head.bytes.size))
        assertEquals(1_000L, ByteBuffer.wrap(ToolboxProtocol.required(input).bytes).long)
        assertEquals(null, ToolboxProtocol.read(input))
    }

    @Test fun `a device that can't capture sound leaves the picture running and a failed one ends the session`() {
        val output = ByteArrayOutputStream()
        relayScrcpyAudio(bytes { writeInt(0) }.inputStream(), AgentWire(byteArrayOf().inputStream(), output))
        val announced = ToolboxProtocol.required(output.toByteArray().inputStream())
        assertEquals(ToolboxProtocol.AUDIO_CONFIG, announced.type)
        assertEquals(null, announced.objectValue().text("codec"))
        val wire = AgentWire(byteArrayOf().inputStream(), ByteArrayOutputStream())
        for ((id, code) in listOf(1 to "screen_audio_failed", 7 to "screen_protocol_mismatch")) {
            val failure = assertFailsWith<ScreenRelayException> { relayScrcpyAudio(bytes { writeInt(id) }.inputStream(), wire) }
            assertEquals(code, failure.code)
        }
    }

    private class FlagCommands(val value: String, val putCode: Int = 0) : PackageCommands {
        val log = mutableListOf<String>()
        override fun run(command: List<String>, input: ((OutputStream) -> Unit)?, timeoutMillis: Long): CapturedCommand {
            log += command.drop(1).joinToString(" ")
            return when (command[1]) {
                "get" -> CapturedCommand(0, "$value\n")
                "put" -> CapturedCommand(putCode, "")
                else -> CapturedCommand(0, "")
            }
        }
    }

    private fun FlagCommands.steps(): List<String> {
        withHighPerfLocksAllowed(this, settleMillis = 0) { log += "acquire" }
        return log
    }

    @Test fun `the high-performance flag is only changed while acquiring and then put back`() {
        val flag = "wifi high_perf_lock_deprecated"
        assertEquals(listOf("get $flag", "put $flag false", "acquire", "delete $flag"), FlagCommands("null").steps())
        assertEquals(listOf("get $flag", "put $flag false", "acquire", "put $flag true"), FlagCommands("true").steps())
    }

    @Test fun `a flag that's already off or can't be changed is left alone`() {
        val flag = "wifi high_perf_lock_deprecated"
        assertEquals(listOf("get $flag", "acquire"), FlagCommands("false").steps())
        assertEquals(listOf("get $flag", "put $flag false", "acquire"), FlagCommands("null", putCode = 255).steps())
    }

    @Test fun `the mirror still runs when Wi-Fi can't be held`() {
        val commands = FlagCommands("null")
        assertNull(holdWifi(34, commands) { error("no service") })
        assertTrue(commands.log.isEmpty(), "the device isn't touched without a lock to take")
    }

    @Test fun `the server is told the requested codec sound and limits and refuses the rest`() {
        val options = ScreenRequest(buildJsonObject {
            put("maxSize", 2560); put("maxFps", 120); put("bitRate", 1_000_000_000); put("videoCodec", "h265"); put("audio", true)
        }).serverOptions("id")
        listOf("audio=true", "audio_codec=opus", "video_codec=h265", "video_bit_rate=1000000000", "max_fps=120", "max_size=2560").forEach {
            assertTrue(it in options.split(' '), it)
        }
        assertTrue("audio=false" in ScreenRequest(buildJsonObject { }).serverOptions("id").split(' '))
        assertFalse(ScreenRequest(buildJsonObject { put("video", false); put("audio", true) }).audio)
        assertTrue(ScreenRequest(buildJsonObject { put("holdWifi", true) }).holdWifi)
        assertFalse(ScreenRequest(buildJsonObject { }).holdWifi)
        assertFailsWith<IllegalArgumentException> { ScreenRequest(buildJsonObject { put("videoCodec", "av1") }) }
        assertFailsWith<IllegalArgumentException> { ScreenRequest(buildJsonObject { put("bitRate", 1_000_000_001) }) }
    }

    @Test fun `subsequent sessions including client resize become configurations before their frames`() {
        val source = bytes {
            writeInt(0x68323634)
            writeInt(Int.MIN_VALUE); writeInt(480); writeInt(320)
            frame((1L shl 61) or 7, byteArrayOf(1))
            writeInt(Int.MIN_VALUE or 1); writeInt(320); writeInt(480)
            frame(1L shl 62, byteArrayOf(2))
            frame((1L shl 61) or 8, byteArrayOf(3))
        }
        val packets = relay(source)
        assertEquals(listOf(13, 14, 13, 14, 14), packets.map { it.type })
        assertEquals(320L, packets[2].objectValue().number("width"))
        assertEquals(480L, packets[2].objectValue().number("height"))
        assertEquals(Long.MIN_VALUE, ByteBuffer.wrap(packets[3].bytes).long)
        assertEquals((1L shl 62) or 8, ByteBuffer.wrap(packets[4].bytes).long)
    }

    @Test fun `codec disabled unsupported and malformed session metadata fail before any frame`() {
        for (codec in listOf(0, 1)) {
            assertEquals("screen_encoder_unavailable", assertFailsWith<ScreenRelayException> { relay(bytes { writeInt(codec) }) }.code)
        }
        assertEquals("screen_protocol_mismatch", assertFailsWith<ScreenRelayException> { relay(bytes { writeInt(0x68766331) }) }.code)
        for ((width, height) in listOf(0 to 480, 480 to -1, 65_536 to 480)) {
            assertEquals("screen_protocol_mismatch", assertFailsWith<ScreenRelayException> { relay(bytes {
                writeInt(0x68323634); writeInt(Int.MIN_VALUE); writeInt(width); writeInt(height)
            }) }.code)
        }
        assertEquals("screen_protocol_mismatch", assertFailsWith<ScreenRelayException> { relay(bytes {
            writeInt(0x68323634); frame(1L shl 62, byteArrayOf(1))
        }) }.code)
    }

    @Test fun `invalid frame lengths and truncated headers or payloads cannot escape as video frames`() {
        for (length in listOf(-1, ToolboxProtocol.MAX_PACKET - 7)) {
            assertEquals("screen_protocol_mismatch", assertFailsWith<ScreenRelayException> { relay(bytes {
                writeInt(0x68323634); writeInt(Int.MIN_VALUE); writeInt(480); writeInt(480)
                writeLong(1L shl 62); writeInt(length)
            }) }.code)
        }
        val valid = bytes {
            writeInt(0x68323634); writeInt(Int.MIN_VALUE); writeInt(480); writeInt(480)
            frame(1L shl 62, byteArrayOf(1, 2, 3))
        }
        for (length in listOf(2, 8, 17, valid.size - 1)) {
            assertFailsWith<EOFException> { relay(valid.copyOf(length)) }
        }
    }

    @Test fun `a failed relay forwards typed failure and closes stdin without leaking exception content`() {
        val output = ByteArrayOutputStream()
        var inputClosed = false
        val input = object : FilterInputStream(byteArrayOf().inputStream()) {
            override fun close() { inputClosed = true; super.close() }
        }
        val closed = AtomicBoolean()
        runScreenRelay(AgentWire(input, output), closed, { closed.set(true) }) {
            throw ScreenRelayException("screen_protocol_mismatch")
        }
        val packet = ToolboxProtocol.required(output.toByteArray().inputStream())
        assertEquals(ToolboxProtocol.ERROR, packet.type)
        assertEquals("screen_protocol_mismatch", packet.objectValue().text("code"))
        assertTrue(closed.get()); assertTrue(inputClosed)
        val generic = ByteArrayOutputStream()
        runScreenRelay(AgentWire(byteArrayOf().inputStream(), generic), AtomicBoolean(), {}) {
            throw IOException("private clipboard /data/private/path")
        }
        assertEquals("screen_stream_failed", ToolboxProtocol.required(generic.toByteArray().inputStream()).objectValue().text("code"))
        assertFalse(generic.toString(Charsets.UTF_8).contains("private"))
    }

    @Test fun `normal EOF ends the relay and local cancellation does not emit a remote error`() {
        val output = ByteArrayOutputStream()
        val closed = AtomicBoolean()
        runScreenRelay(AgentWire(byteArrayOf().inputStream(), output), closed, { closed.set(true) }) { }
        assertEquals(ToolboxProtocol.END, ToolboxProtocol.required(output.toByteArray().inputStream()).type)
        val cancelled = ByteArrayOutputStream()
        runScreenRelay(AgentWire(byteArrayOf().inputStream(), cancelled), AtomicBoolean(true), {}) { throw IOException("closed") }
        assertEquals(0, cancelled.size())
    }

    private fun relay(source: ByteArray): List<ToolboxProtocol.Packet> {
        val output = ByteArrayOutputStream()
        relayScrcpyVideo(source.inputStream(), AgentWire(byteArrayOf().inputStream(), output))
        val input = output.toByteArray().inputStream()
        return buildList { while (true) add(ToolboxProtocol.read(input) ?: break) }
    }

    private fun DataOutputStream.frame(flags: Long, payload: ByteArray) {
        writeLong(flags); writeInt(payload.size); write(payload)
    }
    private fun bytes(write: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).apply(write)
    }.toByteArray()
}
