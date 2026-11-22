package dev.ticbox.companion.toolboxagent

import dev.ticbox.companion.protocol.ScreenUdpTransport
import dev.ticbox.companion.protocol.ToolboxProtocol
import dev.ticbox.companion.protocol.number
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AgentScreenUdpTest {
    @Test fun anAudioHeartbeatFailureRetainsItsOriginInTheBoundedTerminalReply() {
        SocketFixture(diagnostics = true).use { fixture ->
            val started = CountDownLatch(1)
            val error = AtomicReference<Throwable?>()
            val worker = Thread { try { fixture.media.establish(); started.countDown() } catch (failure: Throwable) { error.set(failure) } }.apply { isDaemon = true; start() }
            try {
                val ready = ToolboxProtocol.required(fixture.phone.getInputStream()).objectValue()
                val key = ready.requiredText("key").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                ScreenUdpTransport.connect(fixture.phone.inetAddress, ready.number("videoPort")!!.toInt(), key, 1, {}, {}).use { video ->
                    ScreenUdpTransport.connect(fixture.phone.inetAddress, ready.number("audioPort")!!.toInt(), key, 2, {}, {}).use { audio ->
                        video.awaitConnected(); audio.awaitConnected(); assertTrue(started.await(2, TimeUnit.SECONDS))
                        audio.close()
                        fixture.phone.soTimeout = 8_000
                        val terminal = ToolboxProtocol.required(fixture.phone.getInputStream())
                        assertEquals(ToolboxProtocol.ERROR, terminal.type)
                        val value = terminal.objectValue()
                        assertEquals("screen_udp_disconnected", value.requiredText("code"))
                        val facts = kotlin.test.assertNotNull(dev.ticbox.companion.protocol.ScreenUdpFailureDiagnostics.parse(value["transport"] as kotlinx.serialization.json.JsonObject))
                        assertEquals("sender", facts["side"]); assertEquals("audio", facts["stream"])
                        assertTrue(facts["reason"] in setOf("screen_udp_connection_lost", "screen_udp_io_failed"))
                        assertTrue(fixture.closed.get())
                    }
                }
                assertEquals(null, error.get())
            } finally { fixture.close(); worker.join(1_000); assertFalse(worker.isAlive) }
        }
    }

    @Test fun relayTimeIsOptionalPerFrameMetadataAndNeverPersistsWhenNotCollected() {
        val messages = mutableListOf<ByteArray>()
        val sink = ScreenUdpMediaSink(true) { _, bytes -> messages += bytes }
        val config = """{"codec":"h265","width":1180,"height":2560,"generation":2}""".toByteArray()
        sink.send(ToolboxProtocol.VIDEO_CONFIG, config)
        sink.send(ToolboxProtocol.VIDEO_FRAME, frame(Long.MIN_VALUE, 1))
        sink.send(ToolboxProtocol.VIDEO_FRAME, frame(1L shl 62, 2), 123_456)
        val annotated = packets(messages.last()).first().objectValue()
        assertEquals(123_456L, annotated.number("sourceRelayMicros")); assertEquals(2L, annotated.number("generation"))
        sink.send(ToolboxProtocol.VIDEO_FRAME, frame(8_333, 3))
        assertContentEquals(config, packets(messages.last()).first().bytes)
        assertEquals(null, packets(messages.last()).first().objectValue().number("sourceRelayMicros"))
    }

    @Test fun `UDP is rejected before launching anything for root Bluetooth and clipboard-only requests`() {
        for ((uid, wifi, video) in listOf(Triple(0, true, true), Triple(2000, false, true), Triple(2000, true, false))) {
            val args = buildJsonObject { put("udp", true); put("holdWifi", wifi); put("video", video) }
            val output = ByteArrayOutputStream()
            val failure = assertFailsWith<ScreenRelayException> {
                AgentScreen(AgentWire(byteArrayOf().inputStream(), output), AgentEnvironment(uid, 33, "missing", "/bin/sh")).run(args)
            }
            assertEquals("screen_udp_unavailable", failure.code)
            assertEquals(0, output.size())
        }
        ScreenRequest(buildJsonObject { put("udp", true); put("holdWifi", true) }).validateTransport(2000)
        ScreenRequest(buildJsonObject { }).validateTransport(0)
        val udpOptions = ScreenRequest(buildJsonObject { put("udp", true); put("holdWifi", true) }).serverOptions("id")
        assertTrue("video_codec_options=i-frame-interval=1,frame-rate=20,repeat-previous-frame-after:long=100000,max-bframes=0" in udpOptions.split(' '))
        assertTrue("video_tail_repeat_us=50000" in udpOptions.split(' '))
        val reliable = ScreenRequest(buildJsonObject { }).serverOptions("id").split(' ')
        assertEquals(listOf("video_codec_options=max-bframes=0"), reliable.filter { it.startsWith("video_codec_options=") })
        assertTrue(reliable.none { it.startsWith("video_tail_repeat_us=") })
    }

    @Test fun experimentalCodecOptionsComeLastAndNothingElseGetsThrough() {
        fun options(udp: Boolean, extra: String) = ScreenRequest(buildJsonObject {
            put("udp", udp); put("holdWifi", udp); put("videoCodecOptions", extra)
        }).serverOptions("id").split(' ').filter { it.startsWith("video_codec_options=") }
        assertEquals(listOf("video_codec_options=i-frame-interval=1,frame-rate=20,repeat-previous-frame-after:long=100000,max-bframes=0," +
            "video-qp-max=35,bitrate-mode=1,vendor.mtk.ext.venc.qpvbr.upper-enable:int=0"),
            options(true, " video-qp-max=35, bitrate-mode=1 ,vendor.mtk.ext.venc.qpvbr.upper-enable:int=0"))
        assertEquals(listOf("video_codec_options=max-bframes=0,profile=1,i-frame-interval:float=0.5,mode:string=x-y"),
            options(false, "profile=1,i-frame-interval:float=0.5,mode:string=x-y"))
        assertEquals(listOf("video_codec_options=max-bframes=0"), options(false, ""))
        for (bad in listOf("a=1 b=2", "a=1,,b=2", "a=1;b", "x=1 power_on=false", "a", "=1", "a:int=1.5", "a=", "1a=2", "a:double=1",
            "a:float=NaN", List(17) { "k$it=1" }.joinToString(","), "a:string=x/y")) {
            assertEquals("screen_codec_options_invalid", assertFailsWith<ScreenRelayException> {
                ScreenRequest(buildJsonObject { put("videoCodecOptions", bad) })
            }.code, bad)
        }
    }

    @Test fun headerIsSentBeforeCodecDataAndPlaintextRequiresAnExplicitUdpRequest() {
        val messages = mutableListOf<ByteArray>()
        val sink = ScreenUdpMediaSink(true) { _, bytes -> messages += bytes }
        val header = """{"codec":"h265","width":1180,"height":2560}""".toByteArray()
        sink.send(ToolboxProtocol.VIDEO_CONFIG, header)
        assertContentEquals(header, packets(messages.single()).single().bytes)
        assertTrue(ScreenRequest(buildJsonObject {}).udpEncryption)
        val plain = ScreenRequest(buildJsonObject { put("udp", true); put("holdWifi", true); put("udpEncryption", false) })
        plain.validateTransport(2000)
        assertFalse(plain.udpEncryption)
        assertFailsWith<ScreenRelayException> { ScreenRequest(buildJsonObject { put("udpEncryption", false) }).validateTransport(2000) }
        assertFailsWith<ScreenRelayException> { ScreenRequest(buildJsonObject { put("udpEncryption", "no") }) }
    }

    @Test fun `every keyframe restores dimensions and all codec initialization after packet loss`() {
        val messages = mutableListOf<Pair<Int, ByteArray>>()
        val sink = ScreenUdpMediaSink(true) { type, payload -> messages += type to payload }
        val config = "{\"codec\":\"h264\",\"width\":480,\"height\":320}".toByteArray()
        val sps = frame(Long.MIN_VALUE, 1)
        val pps = frame(Long.MIN_VALUE, 2)
        val key = frame(1L shl 62, 3)
        val delta = frame(1000, 4)
        sink.send(ToolboxProtocol.VIDEO_CONFIG, config)
        sink.send(ToolboxProtocol.VIDEO_FRAME, sps)
        sink.send(ToolboxProtocol.VIDEO_FRAME, pps)
        sink.send(ToolboxProtocol.VIDEO_FRAME, key)
        sink.send(ToolboxProtocol.VIDEO_FRAME, delta)
        sink.send(ToolboxProtocol.VIDEO_FRAME, key)
        assertTrue(messages.all { it.first == ToolboxProtocol.VIDEO_FRAME })
        // Drop every packet before the final keyframe; the final message is still self contained.
        val recovered = packets(messages.last().second)
        assertEquals(listOf(13, 14, 14, 14), recovered.map { it.type })
        listOf(config, sps, pps, key).zip(recovered).forEach { (expected, packet) -> assertContentEquals(expected, packet.bytes) }
        val deltaPackets = packets(messages[messages.lastIndex - 1].second)
        assertEquals(2, deltaPackets.size)
        assertContentEquals(delta, deltaPackets.last().bytes)
    }

    @Test fun `a new video session drops stale codec initialization and audio replays its header in each packet`() {
        val messages = mutableListOf<ByteArray>()
        val video = ScreenUdpMediaSink(true) { _, payload -> messages += payload }
        video.send(ToolboxProtocol.VIDEO_CONFIG, "{\"width\":480}".toByteArray())
        video.send(ToolboxProtocol.VIDEO_FRAME, frame(Long.MIN_VALUE, 1))
        video.send(ToolboxProtocol.VIDEO_FRAME, frame(1L shl 62, 2))
        video.send(ToolboxProtocol.VIDEO_CONFIG, "{\"width\":320}".toByteArray())
        video.send(ToolboxProtocol.VIDEO_FRAME, frame(Long.MIN_VALUE, 3))
        video.send(ToolboxProtocol.VIDEO_FRAME, frame(1L shl 62, 4))
        val rotated = packets(messages.last())
        assertEquals(3, rotated.size)
        assertEquals(320L, rotated.first().objectValue().number("width"))
        assertContentEquals(frame(Long.MIN_VALUE, 3), rotated[1].bytes)
        messages.clear()
        val audio = ScreenUdpMediaSink(false) { type, payload -> assertEquals(ToolboxProtocol.AUDIO_FRAME, type); messages += payload }
        audio.send(ToolboxProtocol.AUDIO_CONFIG, "{\"codec\":\"opus\"}".toByteArray())
        audio.send(ToolboxProtocol.AUDIO_FRAME, opusHead())
        audio.send(ToolboxProtocol.AUDIO_FRAME, frame(1000, 6))
        audio.send(ToolboxProtocol.AUDIO_FRAME, frame(2000, 7))
        assertEquals(listOf(19, 20, 20), packets(messages.last()).map { it.type })
        assertContentEquals(opusHead(), packets(messages.last())[1].bytes)
    }

    @Test fun `unavailable audio is announced and unbounded codec data is refused`() {
        val messages = mutableListOf<ByteArray>()
        ScreenUdpMediaSink(false) { _, payload -> messages += payload }.send(ToolboxProtocol.AUDIO_CONFIG, "{}".toByteArray())
        assertEquals(ToolboxProtocol.AUDIO_CONFIG, packets(messages.single()).single().type)
        val video = ScreenUdpMediaSink(true) { _, _ -> }
        video.send(ToolboxProtocol.VIDEO_CONFIG, "{}".toByteArray())
        val oversized = ByteArray(262_145).also { ByteBuffer.wrap(it).putLong(Long.MIN_VALUE) }
        assertEquals("screen_udp_frame_too_large", assertFailsWith<ScreenRelayException> { video.send(ToolboxProtocol.VIDEO_FRAME, oversized) }.code)
        val huge = ByteArray(ToolboxProtocol.MAX_PACKET).also { ByteBuffer.wrap(it).putLong(1L shl 62) }
        assertEquals("screen_udp_frame_too_large", assertFailsWith<ScreenRelayException> { video.send(ToolboxProtocol.VIDEO_FRAME, huge) }.code)
    }

    @Test fun `Opus envelopes and initialization cannot grow to video sized allocations`() {
        val audio = ScreenUdpMediaSink(false) { _, _ -> }
        audio.send(ToolboxProtocol.AUDIO_CONFIG, "{}".toByteArray())
        val hugeHead = ByteArray(4105).also { ByteBuffer.wrap(it).putLong(Long.MIN_VALUE) }
        assertEquals("screen_udp_frame_too_large", assertFailsWith<ScreenRelayException> { audio.send(ToolboxProtocol.AUDIO_FRAME, hugeHead) }.code)
        val hugeAudio = ByteArray(65_536).also { ByteBuffer.wrap(it).putLong(1000) }
        assertEquals("screen_udp_frame_too_large", assertFailsWith<ScreenRelayException> { audio.send(ToolboxProtocol.AUDIO_FRAME, hugeAudio) }.code)
    }

    @Test fun `video and audio arrive on distinct UDP sockets and never on the ADB media stream`() {
        SocketFixture().use { fixture ->
            val error = AtomicReference<Throwable?>()
            val started = CountDownLatch(1)
            val task = Thread { try { fixture.media.establish(); started.countDown() } catch (failure: Throwable) { error.set(failure) } }.apply { start() }
            val ready = ToolboxProtocol.required(fixture.phone.getInputStream())
            assertEquals(ToolboxProtocol.SCREEN_UDP_READY, ready.type)
            val config = ready.objectValue()
            val videoPort = config.number("videoPort")!!.toInt()
            val audioPort = config.number("audioPort")!!.toInt()
            assertNotEquals(videoPort, audioPort)
            val key = config.requiredText("key").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val gotVideo = CountDownLatch(1)
            val gotAudio = CountDownLatch(1)
            ScreenUdpTransport.connect(fixture.phone.inetAddress, videoPort, key, ScreenUdpTransport.VIDEO,
                onMessage = { assertEquals(ToolboxProtocol.VIDEO_FRAME, it.type); gotVideo.countDown() }, onFailure = { error.set(it) }).use { video ->
                ScreenUdpTransport.connect(fixture.phone.inetAddress, audioPort, key, ScreenUdpTransport.AUDIO,
                    onMessage = { assertEquals(ToolboxProtocol.AUDIO_FRAME, it.type); gotAudio.countDown() }, onFailure = { error.set(it) }).use { audio ->
                    video.awaitConnected(); audio.awaitConnected()
                    assertTrue(started.await(2, TimeUnit.SECONDS))
                    fixture.media.sendVideo(ToolboxProtocol.VIDEO_CONFIG, "{}".toByteArray())
                    fixture.media.sendVideo(ToolboxProtocol.VIDEO_FRAME, frame(1L shl 62, 8))
                    fixture.media.sendAudio(ToolboxProtocol.AUDIO_CONFIG, "{}".toByteArray())
                    assertTrue(gotVideo.await(2, TimeUnit.SECONDS)); assertTrue(gotAudio.await(2, TimeUnit.SECONDS))
                    fixture.phone.soTimeout = 100
                    assertFailsWith<SocketTimeoutException> { fixture.phone.getInputStream().read() }
                    assertEquals(null, error.get())
                }
            }
            task.join(1000)
            assertFalse(task.isAlive)
        }
    }

    @Test fun optedInSenderReportsMeasuredVideoMetadataOnControlLane() {
        SocketFixture(diagnostics = true).use { fixture ->
            val failure = AtomicReference<Throwable?>()
            val established = CountDownLatch(1)
            val task = Thread {
                try { fixture.media.establish(); established.countDown() }
                catch (error: Throwable) { failure.set(error) }
            }.apply { start() }
            val ready = ToolboxProtocol.required(fixture.phone.getInputStream()).objectValue()
            val key = ready.requiredText("key").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val arrived = CountDownLatch(1)
            ScreenUdpTransport.connect(fixture.phone.inetAddress, ready.number("videoPort")!!.toInt(), key, ScreenUdpTransport.VIDEO,
                onMessage = { arrived.countDown() }, onFailure = { failure.set(it) }).use { video ->
                ScreenUdpTransport.connect(fixture.phone.inetAddress, ready.number("audioPort")!!.toInt(), key, ScreenUdpTransport.AUDIO,
                    onMessage = {}, onFailure = { failure.set(it) }).use { sound ->
                    video.awaitConnected(); sound.awaitConnected()
                    assertTrue(established.await(2, TimeUnit.SECONDS))
                    fixture.media.sendVideo(ToolboxProtocol.VIDEO_CONFIG, "{}".toByteArray())
                    // Nothing paces video any more, so a picture 100 ms after the one before is what gets reported.
                    fixture.media.sendVideo(ToolboxProtocol.VIDEO_FRAME, ByteArray(1_008).also { ByteBuffer.wrap(it).putLong(1L shl 62) })
                    val frame = ByteArray(50_008).also { ByteBuffer.wrap(it).putLong(100_000L) }
                    fixture.media.sendVideo(ToolboxProtocol.VIDEO_FRAME, frame)
                    assertTrue(arrived.await(2, TimeUnit.SECONDS))
                    val progress = ToolboxProtocol.required(fixture.phone.getInputStream())
                    assertEquals(ToolboxProtocol.PROGRESS, progress.type)
                    val sample = progress.objectValue()
                    assertEquals("screen_udp_sender", sample.requiredText("kind"))
                    assertEquals("frame_gap", sample.requiredText("sample"))
                    assertEquals(50_000L, sample.number("frameBytes"))
                    assertTrue(sample.number("datagrams")!! > 1)
                    assertEquals(0L, sample.number("pacingWaitMicros"))
                    assertEquals(null, failure.get())
                }
            }
            task.join(1_000)
            assertFalse(task.isAlive)
        }
    }

    @Test fun negotiatedFecReadyUsesV2VideoAndPreservesIndependentAudioAndKeyControl() {
        SocketFixture(protection = ScreenUdpTransport.VideoProtection.RS_16_4, lightKeyframe = true).use { fixture ->
            val error = AtomicReference<Throwable?>()
            val started = CountDownLatch(1)
            val task = Thread { try { fixture.media.establish(); started.countDown() } catch (failure: Throwable) { error.set(failure) } }.apply { isDaemon = true; start() }
            val ready = ToolboxProtocol.required(fixture.phone.getInputStream()).objectValue()
            assertEquals(2L, ready.number("version"))
            assertEquals("rs_16_4", ready.requiredText("videoProtection"))
            assertTrue(ready["lightKeyframe"].toString() == "true")
            val key = ready.requiredText("key").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val received = CountDownLatch(1)
            ScreenUdpTransport.connect(fixture.phone.inetAddress, ready.number("videoPort")!!.toInt(), key, ScreenUdpTransport.VIDEO,
                onMessage = { received.countDown() }, onFailure = { error.set(it) }, protection = ScreenUdpTransport.VideoProtection.RS_16_4).use { video ->
                ScreenUdpTransport.connect(fixture.phone.inetAddress, ready.number("audioPort")!!.toInt(), key, ScreenUdpTransport.AUDIO,
                    onMessage = {}, onFailure = { error.set(it) }).use { audio ->
                    video.awaitConnected(); audio.awaitConnected()
                    assertTrue(started.await(2, TimeUnit.SECONDS))
                    fixture.media.sendVideo(ToolboxProtocol.VIDEO_CONFIG, "{}".toByteArray())
                    fixture.media.sendVideo(ToolboxProtocol.VIDEO_FRAME, frame(1L shl 62, 8))
                    assertTrue(received.await(2, TimeUnit.SECONDS))
                    val control = java.util.concurrent.FutureTask { fixture.media.nextControl() }
                    Thread(control).apply { isDaemon = true; start() }
                    ToolboxProtocol.write(fixture.phone.getOutputStream(), ToolboxProtocol.CONTROL, byteArrayOf(127))
                    assertContentEquals(byteArrayOf(127), checkNotNull(control.get(2, TimeUnit.SECONDS)).bytes)
                    assertEquals(null, error.get())
                }
            }
            task.join(1_000)
            assertFalse(task.isAlive)
        }
    }

    @Test fun `closing ADB during UDP negotiation promptly closes both listeners`() {
        SocketFixture().use { fixture ->
            val finished = CountDownLatch(1)
            Thread { try { fixture.media.establish() } catch (_: Exception) { } finally { finished.countDown() } }.start()
            assertEquals(ToolboxProtocol.SCREEN_UDP_READY, ToolboxProtocol.required(fixture.phone.getInputStream()).type)
            fixture.phone.close()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertTrue(fixture.closed.get())
        }
    }

    @Test fun `missing UDP peer reports connection failure without starting a reliable media fallback`() {
        SocketFixture().use { fixture ->
            assertFailsWith<java.io.IOException> { fixture.media.establish(100) }
            assertEquals(ToolboxProtocol.SCREEN_UDP_READY, ToolboxProtocol.required(fixture.phone.getInputStream()).type)
            val failed = ToolboxProtocol.required(fixture.phone.getInputStream())
            assertEquals(ToolboxProtocol.ERROR, failed.type)
            assertEquals("screen_udp_connect_failed", failed.objectValue().requiredText("code"))
            fixture.phone.soTimeout = 100
            assertFailsWith<SocketTimeoutException> { fixture.phone.getInputStream().read() }
        }
    }

    @Test fun `blocked ADB error delivery cannot keep UDP listeners alive or hold up shutdown`() {
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            SocketFixture { output -> object : FilterOutputStream(output) {
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (length > 0 && bytes[offset].toInt() == ToolboxProtocol.ERROR) {
                        blocked.countDown()
                        release.await(3, TimeUnit.SECONDS)
                    }
                    out.write(bytes, offset, length)
                }
            } }.use { fixture ->
                assertFailsWith<java.io.IOException> { fixture.media.establish(100) }
                assertTrue(blocked.await(1, TimeUnit.SECONDS))
                assertTrue(fixture.closed.get())
                val ready = ToolboxProtocol.required(fixture.phone.getInputStream()).objectValue()
                DatagramSocket(ready.number("videoPort")!!.toInt()).use { }
                DatagramSocket(ready.number("audioPort")!!.toInt()).use { }
                val start = System.nanoTime()
                fixture.media.awaitTerminalReport(20)
                assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1))
                release.countDown()
                assertEquals(ToolboxProtocol.ERROR, ToolboxProtocol.required(fixture.phone.getInputStream()).type)
            }
        } finally { release.countDown() }
    }

    @Test fun `normal UDP stream end also releases resources before a blocked control reply`() {
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            SocketFixture { output -> object : FilterOutputStream(output) {
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (length > 0 && bytes[offset].toInt() == ToolboxProtocol.END) {
                        blocked.countDown()
                        release.await(3, TimeUnit.SECONDS)
                    }
                    out.write(bytes, offset, length)
                }
            } }.use { fixture ->
                fixture.media.relay { }
                assertTrue(blocked.await(1, TimeUnit.SECONDS))
                assertTrue(fixture.closed.get())
                val start = System.nanoTime()
                fixture.media.awaitTerminalReport(20)
                assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1))
                release.countDown()
                assertEquals(ToolboxProtocol.END, ToolboxProtocol.required(fixture.phone.getInputStream()).type)
            }
        } finally { release.countDown() }
    }

    @Test fun `resources registered after cancellation are immediately closed`() {
        val closed = AtomicBoolean()
        val owner = ScreenResources(closed)
        val first = AtomicBoolean(); val late = AtomicBoolean()
        owner.add(AutoCloseable { first.set(true) })
        owner.close()
        assertTrue(first.get())
        assertFailsWith<java.io.InterruptedIOException> { owner.add(AutoCloseable { late.set(true) }) }
        assertTrue(late.get())
        owner.close()
    }

    private class SocketFixture(diagnostics: Boolean = false, protection: ScreenUdpTransport.VideoProtection = ScreenUdpTransport.VideoProtection.NONE, lightKeyframe: Boolean = false, output: (OutputStream) -> OutputStream = { it }) : AutoCloseable {
        private val listener = ServerSocket(0)
        val phone = Socket("127.0.0.1", listener.localPort).apply { soTimeout = 3000 }
        private val agent = listener.accept()
        val closed = AtomicBoolean()
        private val resources = ScreenResources(closed)
        val media = resources.add(AgentScreenUdp(AgentWire(agent.getInputStream(), output(agent.getOutputStream())), true, closed, resources::close, diagnostics = diagnostics, videoProtection = protection, lightKeyframe = lightKeyframe))
        override fun close() { resources.close(); phone.close(); agent.close(); listener.close() }
    }
    private fun opusHead() = ByteBuffer.allocate(27).putLong(Long.MIN_VALUE).put("OpusHead".toByteArray(Charsets.US_ASCII)).array()
    private fun frame(flags: Long, byte: Byte) = ByteBuffer.allocate(9).putLong(flags).put(byte).array()
    private fun packets(bytes: ByteArray): List<ToolboxProtocol.Packet> = bytes.inputStream().use { input -> buildList {
        while (true) add(ToolboxProtocol.read(input) ?: break)
    } }
}
