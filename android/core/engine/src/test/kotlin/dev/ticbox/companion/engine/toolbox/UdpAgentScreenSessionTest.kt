package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import java.io.ByteArrayInputStream
import java.io.FilterOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.net.InetAddress
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.nio.ByteBuffer
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class UdpAgentScreenSessionTest {
    @Test fun remoteTerminalErrorWinsTheUdpCloseItCaused() = runBlocking { withTimeout(10_000) {
        lateinit var fixture: Fixture
        val observed = java.util.concurrent.ConcurrentLinkedQueue<ProtocolDiagnostic>()
        val trace = ScreenDiagnostics(DiagnosticSink { event ->
            observed.add(event)
            if (event.event == "udp_transport_failure" && event.details["side"] == "receiver") {
                fixture.send(ToolboxProtocol.ERROR, """{"code":"screen_udp_recovery_failed","type":"ScreenRelayException"}""".toByteArray())
            }
        }, ToolboxBackendKind.ADB, ToolboxScreenOptions(udp = true, collectDiagnostics = true))
        Fixture(this, trace = trace).use { f ->
            fixture = f
            val video = ScreenUdpTransport.listen(f.key, 1, onFailure = {}, protection = ScreenUdpTransport.VideoProtection.RS_16_4)
            try {
                val result = async { runCatching { f.session.events.collect {} }.exceptionOrNull() }
                val first = async { f.session.media.video.take(3).toList() }
                f.ready(video.port); withContext(Dispatchers.IO) { video.awaitConnected() }
                val key = ScreenUdpMediaTest.video(0, key = true); video.send(key.type, key.bytes)
                first.await(); video.close()
                assertEquals("screen_udp_recovery_failed", assertIs<ToolboxException>(result.await()).code)
                assertTrue(observed.any { it.event == "udp_remote_failure" && it.details["reason"] == "screen_udp_recovery_failed" })
                withTimeout(1_000) { while (!f.closed.get()) delay(10) }
            } finally { video.close() }
        }
    } }

    @Test fun udpCloseWithoutRemoteResultStillEndsWithinABoundedGrace() = runBlocking { withTimeout(10_000) {
        Fixture(this).use { f ->
            val video = ScreenUdpTransport.listen(f.key, 1, onFailure = {}, protection = ScreenUdpTransport.VideoProtection.RS_16_4)
            try {
                val result = async { runCatching { f.session.events.collect {} }.exceptionOrNull() }
                val first = async { f.session.media.video.take(3).toList() }
                f.ready(video.port); withContext(Dispatchers.IO) { video.awaitConnected() }
                val key = ScreenUdpMediaTest.video(0, key = true); video.send(key.type, key.bytes)
                first.await(); video.close()
                val failure = withTimeout(2_000) { result.await() }
                assertEquals("screen_udp_disconnected", assertIs<ToolboxException>(failure).code)
                withTimeout(1_000) { while (!f.closed.get()) delay(10) }
            } finally { video.close() }
        }
    } }

    @Test fun explicitCloseDuringFailureGraceCannotPublishALateFailure() = runBlocking { withTimeout(10_000) {
        lateinit var fixture: Fixture
        val observed = java.util.concurrent.ConcurrentLinkedQueue<ProtocolDiagnostic>()
        val trace = ScreenDiagnostics(DiagnosticSink { event ->
            observed.add(event)
            if (event.event == "udp_transport_failure") fixture.session.close()
        }, ToolboxBackendKind.ADB, ToolboxScreenOptions(udp = true, collectDiagnostics = true))
        Fixture(this, trace = trace).use { f ->
            fixture = f
            val video = ScreenUdpTransport.listen(f.key, 1, onFailure = {}, protection = ScreenUdpTransport.VideoProtection.RS_16_4)
            try {
                val result = async { runCatching { f.session.events.collect {} }.exceptionOrNull() }
                val first = async { f.session.media.video.take(3).toList() }
                f.ready(video.port); withContext(Dispatchers.IO) { video.awaitConnected() }
                val key = ScreenUdpMediaTest.video(0, key = true); video.send(key.type, key.bytes)
                first.await(); video.close()
                assertFalse(result.await() is ToolboxException)
                delay(350)
                assertEquals(1, observed.count { it.event == "stream_closed" })
                assertFalse(observed.any { it.event == "stream_closed" && it.details["reason"] == "screen_udp_disconnected" })
            } finally { video.close() }
        }
    } }

    @Test fun authOnlyNegotiationWaitsForDecoderThenRequestsOneFreshReferenceChain() = runBlocking { withTimeout(10_000) {
        Fixture(this, encryption = false, waitForDecoder = true).use { f ->
            ScreenUdpTransport.listen(f.key, ScreenUdpTransport.VIDEO, onFailure = {},
                protection = ScreenUdpTransport.VideoProtection.RS_16_4, security = ScreenUdpTransport.SecurityMode.AUTH_ONLY).use { video ->
                val configurations = Channel<ToolboxScreenEvent.Configuration>(Channel.UNLIMITED)
                val frames = Channel<ToolboxScreenEvent.Frame>(Channel.UNLIMITED)
                val collect = launch { f.session.media.video.collect { event -> when (event) {
                    is ToolboxScreenEvent.Configuration -> configurations.send(event)
                    is ToolboxScreenEvent.Frame -> if (!event.codecConfig) frames.send(event)
                    else -> Unit
                } } }
                try {
                    f.ready(video.port)
                    withContext(Dispatchers.IO) { video.awaitConnected() }
                    video.send(ToolboxProtocol.VIDEO_FRAME, ScreenUdpMediaTest.bundle(ToolboxProtocol.VIDEO_CONFIG to
                        """{"codec":"h264","width":480,"height":480}""".toByteArray()))
                    val configuration = configurations.receive()
                    val early = ScreenUdpMediaTest.video(0, key = true)
                    video.send(early.type, early.bytes)
                    delay(150)
                    assertTrue(frames.tryReceive().isFailure)
                    f.source.soTimeout = 100
                    withContext(Dispatchers.IO) { assertFailsWith<SocketTimeoutException> { f.source.getInputStream().read() } }
                    f.session.videoDecoderReady(configuration, 250)
                    f.source.soTimeout = 1_000
                    val request = withContext(Dispatchers.IO) { ToolboxProtocol.required(f.source.getInputStream()) }
                    assertEquals(ToolboxProtocol.CONTROL, request.type)
                    assertContentEquals(byteArrayOf(127), request.bytes)
                    val fresh = ScreenUdpMediaTest.video(1, key = true)
                    video.send(fresh.type, fresh.bytes)
                    assertEquals(1L, frames.receive().ptsMicros)
                    assertFalse(f.closed.get())
                } finally { collect.cancelAndJoin() }
            }
        }
    } }

    @Test fun requestedPlaintextCannotBeSilentlyReplacedByAnEncryptedReady() = runBlocking { withTimeout(5_000) {
        Fixture(this, encryption = false).use { f ->
            f.send(ToolboxProtocol.SCREEN_UDP_READY, """{"version":2,"videoProtection":"rs_16_4","lightKeyframe":true}""".toByteArray())
            assertEquals("screen_udp_protocol_mismatch", assertFailsWith<ToolboxException> { f.session.events.collect {} }.code)
        }
    } }

    @Test fun `video and audio arrive over separate sockets while ADB remains control only`() = runBlocking { withTimeout(10_000) {
        Fixture(this, audio = true).use { f ->
            ScreenUdpTransport.listen(f.key, ScreenUdpTransport.VIDEO, onFailure = {}, protection = ScreenUdpTransport.VideoProtection.RS_16_4).use { video ->
                ScreenUdpTransport.listen(f.key, ScreenUdpTransport.AUDIO, onFailure = {}).use { audio ->
                    val pictures = async { f.session.media.video.take(3).toList() }
                    val sounds = async { f.session.media.audio.take(3).toList() }
                    f.ready(video.port, audio.port)
                    withContext(Dispatchers.IO) { video.awaitConnected(); audio.awaitConnected() }
                    val v = ScreenUdpMediaTest.video(0, key = true)
                    val a = ScreenUdpMediaTest.audio(0)
                    video.send(v.type, v.bytes); audio.send(a.type, a.bytes)
                    assertEquals(480, assertIs<ToolboxScreenEvent.Configuration>(pictures.await().first()).width)
                    assertEquals("opus", assertIs<ToolboxScreenEvent.AudioConfiguration>(sounds.await().first()).codec)
                    f.session.input(ToolboxScreenInput.Key(3, true))
                    assertEquals(ToolboxProtocol.CONTROL, withContext(Dispatchers.IO) { ToolboxProtocol.required(f.source.getInputStream()) }.type)
                    f.send(ToolboxProtocol.END)
                    assertTrue(f.session.events.toList().isEmpty())
                }
            }
        }
    } }

    @Test fun `an unreachable UDP port fails explicitly and closes control without media fallback`() = runBlocking { withTimeout(5_000) {
        Fixture(this, startupTimeout = 200).use { f ->
            f.ready(f.silentUdpPort)
            assertEquals("screen_udp_connect_failed", assertFailsWith<ToolboxException> { f.session.events.collect { } }.code)
            withTimeout(1_000) { while (!f.closed.get()) delay(10) }
        }
    } }

    @Test fun `a connected stream whose encoder never makes a picture is not reported as a network fault`() = runBlocking { withTimeout(5_000) {
        Fixture(this, startupTimeout = 300).use { f ->
            val video = ScreenUdpTransport.listen(f.key, 1, onFailure = {}, protection = ScreenUdpTransport.VideoProtection.RS_16_4)
            try {
                val result = async { runCatching { f.session.events.collect {} }.exceptionOrNull() }
                f.ready(video.port); withContext(Dispatchers.IO) { video.awaitConnected() }
                assertEquals("screen_video_no_frames", assertIs<ToolboxException>(result.await()).code)
            } finally { video.close() }
        }
    } }

    @Test fun `reliable media offered to a UDP request is rejected instead of downgrading`() = runBlocking { withTimeout(5_000) {
        Fixture(this).use { f ->
            f.send(ToolboxProtocol.VIDEO_CONFIG, "{\"codec\":\"h264\",\"width\":480,\"height\":480}".toByteArray())
            assertEquals("screen_udp_protocol_mismatch", assertFailsWith<ToolboxException> { f.session.events.collect { } }.code)
        }
    } }

    @Test fun `remote launch error is read during UDP startup and cancellation closes promptly`() = runBlocking { withTimeout(5_000) {
        Fixture(this).use { f ->
            f.ready(f.silentUdpPort)
            f.send(ToolboxProtocol.ERROR, "{\"code\":\"screen_encoder_unavailable\"}".toByteArray())
            assertEquals("screen_encoder_unavailable", assertFailsWith<ToolboxException> { f.session.events.collect { } }.code)
        }
        Fixture(this).use { f ->
            f.ready(f.silentUdpPort)
            f.session.close()
            withTimeout(1_000) { while (!f.closed.get()) delay(10) }
        }
    } }

    @Test fun missingKeyRequestsFreshVideoOnExistingControlAndStopsRequestingAfterRecovery() = runBlocking { withTimeout(5_000) {
        Fixture(this, recoveryInterval = 200).use { f ->
            ScreenUdpTransport.listen(f.key, ScreenUdpTransport.VIDEO, onFailure = {}, protection = ScreenUdpTransport.VideoProtection.RS_16_4).use { video ->
                f.ready(video.port)
                withContext(Dispatchers.IO) { video.awaitConnected() }
                val delta = ScreenUdpMediaTest.video(1)
                video.send(delta.type, delta.bytes)
                f.source.soTimeout = 1_000
                val reset = withContext(Dispatchers.IO) { ToolboxProtocol.required(f.source.getInputStream()) }
                assertEquals(ToolboxProtocol.CONTROL, reset.type)
                assertContentEquals(byteArrayOf(127), reset.bytes)
                val recovered = async { f.session.media.video.take(3).toList() }
                val key = ScreenUdpMediaTest.video(2, key = true)
                video.send(key.type, key.bytes)
                assertTrue(assertIs<ToolboxScreenEvent.Frame>(recovered.await().last()).keyFrame)
                f.source.soTimeout = 250
                withContext(Dispatchers.IO) { assertFailsWith<SocketTimeoutException> { f.source.getInputStream().read() } }
                assertFalse(f.closed.get())
            }
        }
    } }

    @Test fun lostUdpFrameInEstablishedStreamRequestsRecoveryBeforeTheOldGopWait() = runBlocking { withTimeout(10_000) {
        Fixture(this).use { f ->
            ScreenUdpTransport.listen(f.key, ScreenUdpTransport.VIDEO, onFailure = {}, protection = ScreenUdpTransport.VideoProtection.RS_16_4).use { video ->
                DroppingUdpProxy(video.port, droppedMessage = 1).use { proxy ->
                    val pictures = Channel<ToolboxScreenEvent.Frame>(Channel.UNLIMITED)
                    val collecting = launch { f.session.media.video.collect { event ->
                        if (event is ToolboxScreenEvent.Frame && !event.codecConfig) pictures.send(event)
                    } }
                    try {
                        f.ready(proxy.port)
                        withContext(Dispatchers.IO) { video.awaitConnected() }
                        val first = ScreenUdpMediaTest.video(0, key = true)
                        video.send(first.type, first.bytes)
                        assertTrue(pictures.receive().keyFrame)
                        // An ordinary static picture must not start a reset request.
                        f.source.soTimeout = 300
                        withContext(Dispatchers.IO) { assertFailsWith<SocketTimeoutException> { f.source.getInputStream().read() } }
                        val delta = ScreenUdpMediaTest.video(1)
                        val started = System.nanoTime()
                        video.send(delta.type, delta.bytes) // Proxy drops every fragment of sequence 1.
                        video.send(delta.type, delta.bytes) // Sequence 2 establishes an actual gap.
                        f.source.soTimeout = 1_250
                        val reset = withContext(Dispatchers.IO) { ToolboxProtocol.required(f.source.getInputStream()) }
                        assertEquals(ToolboxProtocol.CONTROL, reset.type)
                        assertContentEquals(byteArrayOf(127), reset.bytes)
                        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_500,
                            "A confirmed lost dependency should not wait the old two-second recovery interval")
                        assertTrue(proxy.dropped.get())
                        val key = ScreenUdpMediaTest.video(3, key = true)
                        video.send(key.type, key.bytes)
                        assertTrue(pictures.receive().keyFrame)
                        f.source.soTimeout = 300
                        withContext(Dispatchers.IO) { assertFailsWith<SocketTimeoutException> { f.source.getInputStream().read() } }
                        assertFalse(f.closed.get())
                        assertNull(proxy.failure.get())
                    } finally { collecting.cancelAndJoin(); pictures.cancel() }
                }
            }
        }
    } }

    @Test fun fecRepairsThreeMissingDataShardsWithoutRequestingAKeyFrame() = runBlocking { withTimeout(10_000) {
        Fixture(this).use { f ->
            ScreenUdpTransport.listen(f.key, ScreenUdpTransport.VIDEO, onFailure = {}, protection = ScreenUdpTransport.VideoProtection.RS_16_4).use { video ->
                DroppingUdpProxy(video.port, droppedMessage = 1, droppedShardIndexes = setOf(0, 1, 2)).use { proxy ->
                    val pictures = Channel<ToolboxScreenEvent.Frame>(Channel.UNLIMITED)
                    val collecting = launch { f.session.media.video.collect { event ->
                        if (event is ToolboxScreenEvent.Frame && !event.codecConfig) pictures.send(event)
                    } }
                    try {
                        f.ready(proxy.port)
                        withContext(Dispatchers.IO) { video.awaitConnected() }
                        val key = ScreenUdpMediaTest.video(0, key = true)
                        video.send(key.type, key.bytes)
                        assertTrue(pictures.receive().keyFrame)
                        val pixels = ByteArray(40_000) { (it * 17).toByte() }
                        val frame = ByteBuffer.allocate(pixels.size + 8).putLong(1).put(pixels).array()
                        video.send(ToolboxProtocol.VIDEO_FRAME, ScreenUdpMediaTest.bundle(ToolboxProtocol.VIDEO_FRAME to frame))
                        val repaired = pictures.receive()
                        assertFalse(repaired.keyFrame)
                        assertContentEquals(pixels, repaired.bytes)
                        assertTrue(proxy.dropped.get())
                        f.source.soTimeout = 300
                        withContext(Dispatchers.IO) { assertFailsWith<SocketTimeoutException> { f.source.getInputStream().read() } }
                        assertFalse(f.closed.get())
                        assertNull(proxy.failure.get())
                    } finally { collecting.cancelAndJoin(); pictures.cancel() }
                }
            }
        }
    } }

    @Test fun unnegotiatedReadyCannotSilentlyDisableFecOrLightKeyFrames() = runBlocking { withTimeout(5_000) {
        for ((version, protection, keyframe) in listOf(Triple(1, "none", false), Triple(2, "none", true), Triple(2, "rs_16_4", false))) {
            Fixture(this).use { f ->
                f.send(ToolboxProtocol.SCREEN_UDP_READY, buildJsonObject {
                    put("version", version); put("videoProtection", protection); put("lightKeyframe", keyframe)
                    put("key", f.key.joinToString("") { "%02x".format(it) }); put("videoPort", f.silentUdpPort)
                }.toString().toByteArray())
                assertEquals("screen_udp_protocol_mismatch", assertFailsWith<ToolboxException> { f.session.events.collect { } }.code)
            }
        }
    } }

    @Test fun repeatedDeltasWithoutAnyKeyEndInExplicitBoundedRecoveryFailure() = runBlocking { withTimeout(5_000) {
        Fixture(this, recoveryInterval = 200).use { f ->
            ScreenUdpTransport.listen(f.key, ScreenUdpTransport.VIDEO, onFailure = {}, protection = ScreenUdpTransport.VideoProtection.RS_16_4).use { video ->
                f.ready(video.port)
                withContext(Dispatchers.IO) { video.awaitConnected() }
                val delta = ScreenUdpMediaTest.video(1)
                video.send(delta.type, delta.bytes)
                f.source.soTimeout = 1_000
                repeat(3) {
                    val reset = withContext(Dispatchers.IO) { ToolboxProtocol.required(f.source.getInputStream()) }
                    assertEquals(ToolboxProtocol.CONTROL, reset.type)
                    assertContentEquals(byteArrayOf(127), reset.bytes)
                    video.send(delta.type, delta.bytes) // No reset of the waiting episode's timer.
                }
                assertEquals("screen_udp_recovery_failed", assertFailsWith<ToolboxException> { f.session.events.collect { } }.code)
                withTimeout(1_000) { while (!f.closed.get()) delay(10) }
            }
        }
    } }

    @Test fun encoderColdStartupWithOnlyConfigurationIsNotRepeatedlyReset() = runBlocking { withTimeout(5_000) {
        Fixture(this, recoveryInterval = 30, startupTimeout = 2_000).use { f ->
            ScreenUdpTransport.listen(f.key, ScreenUdpTransport.VIDEO, onFailure = {}, protection = ScreenUdpTransport.VideoProtection.RS_16_4).use { video ->
                f.ready(video.port)
                withContext(Dispatchers.IO) { video.awaitConnected() }
                f.source.soTimeout = 250
                withContext(Dispatchers.IO) { assertFailsWith<SocketTimeoutException> { f.source.getInputStream().read() } }
                val packed = ByteArrayInputStream(ScreenUdpMediaTest.video(0, key = true).bytes)
                val configuration = ToolboxProtocol.required(packed)
                val codecHead = ToolboxProtocol.required(packed)
                video.send(ToolboxProtocol.VIDEO_FRAME, ScreenUdpMediaTest.bundle(configuration.type to configuration.bytes, codecHead.type to codecHead.bytes))
                withContext(Dispatchers.IO) { assertFailsWith<SocketTimeoutException> { f.source.getInputStream().read() } }
                assertFalse(f.closed.get())
            }
        }
    } }

    @Test fun blockedResetWriteCannotOutliveRecoveryDeadline() = runBlocking { withTimeout(5_000) {
        Fixture(this, recoveryInterval = 200, blockReset = true).use { f ->
            ScreenUdpTransport.listen(f.key, ScreenUdpTransport.VIDEO, onFailure = {}, protection = ScreenUdpTransport.VideoProtection.RS_16_4).use { video ->
                f.ready(video.port)
                withContext(Dispatchers.IO) { video.awaitConnected() }
                val delta = ScreenUdpMediaTest.video(1)
                video.send(delta.type, delta.bytes)
                withContext(Dispatchers.IO) { assertTrue(f.resetWriteStarted.await(1, TimeUnit.SECONDS)) }
                assertEquals("screen_udp_recovery_failed", assertFailsWith<ToolboxException> { f.session.events.collect { } }.code)
                withTimeout(1_000) { while (!f.closed.get()) delay(10) }
            }
        }
    } }

    /** Forwards the real encrypted protocol unchanged, except for one whole media message. */
    private class DroppingUdpProxy(serverPort: Int, private val droppedMessage: Long, private val droppedShardIndexes: Set<Int>? = null) : AutoCloseable {
        private val loopback = InetAddress.getLoopbackAddress()
        private val server = InetSocketAddress(loopback, serverPort)
        private val socket = DatagramSocket(InetSocketAddress(loopback, 0))
        val port get() = socket.localPort
        val dropped = AtomicBoolean()
        val failure = AtomicReference<Throwable?>()
        private val forwarding = Thread({
            val bytes = ByteArray(1_201)
            var client: SocketAddress? = null
            try {
                while (!socket.isClosed) {
                    val packet = DatagramPacket(bytes, bytes.size)
                    socket.receive(packet)
                    val destination = if (packet.socketAddress == server) {
                        // The authenticated header is visible; keep its bytes and authentication
                        // tag untouched. MEDIA kind is 3, message sequence is header bytes 16..23.
                        if (packet.length >= 32 && bytes[6].toInt() == 3 && ByteBuffer.wrap(bytes, 16, 8).long == droppedMessage &&
                            (droppedShardIndexes == null || (ByteBuffer.wrap(bytes, 28, 2).short.toInt() and 0xffff) in droppedShardIndexes)) {
                            dropped.set(true)
                            continue
                        }
                        client ?: continue
                    } else {
                        client = packet.socketAddress
                        server
                    }
                    socket.send(DatagramPacket(bytes, packet.length, destination))
                }
            } catch (error: Exception) { if (!socket.isClosed) failure.set(error) }
        }, "screen-loss-test-proxy").apply { isDaemon = true; start() }
        override fun close() { socket.close(); forwarding.join(1_000); check(!forwarding.isAlive) }
    }

    private class Fixture(scope: CoroutineScope, audio: Boolean = false, startupTimeout: Long = 4_000, recoveryInterval: Long = 2_000, blockReset: Boolean = false, private val encryption: Boolean = true, waitForDecoder: Boolean = false, trace: ScreenDiagnostics? = null) : AutoCloseable {
        private val silentUdp = DatagramSocket()
        val silentUdpPort get() = silentUdp.localPort
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val source = Socket(server.inetAddress, server.localPort)
        private val sink = server.accept()
        val key = ByteArray(32) { (it + 1).toByte() }
        val closed = AtomicBoolean()
        val resetWriteStarted = CountDownLatch(1)
        private val resetWriteRelease = CountDownLatch(1)
        private val link = AgentLink(object : ToolboxChannel {
            override val input = sink.getInputStream()
            override val output = object : FilterOutputStream(sink.getOutputStream()) {
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (blockReset && length == 6 && bytes[offset].toInt() == ToolboxProtocol.CONTROL && bytes[offset + 5].toInt() == 127) {
                        resetWriteStarted.countDown()
                        resetWriteRelease.await()
                    }
                    out.write(bytes, offset, length)
                }
            }
            override fun close() { closed.set(true); resetWriteRelease.countDown(); sink.close() }
        }) { }
        val session = UdpAgentScreenSession(link, scope, InetAddress.getLoopbackAddress().hostAddress, audio, startupTimeout, recoveryInterval, requiresVideoDecoderReady = waitForDecoder, encryption = encryption, trace = trace, collectDiagnostics = trace != null)
        fun ready(video: Int, audio: Int? = null) = ToolboxProtocol.write(source.getOutputStream(), ToolboxProtocol.SCREEN_UDP_READY, buildJsonObject {
            put("version", if (encryption) 2 else 4); put("mediaSecurity", if (encryption) "aes_gcm" else "hmac_sha256_128"); put("videoProtection", "rs_16_4"); put("lightKeyframe", true); put("key", key.joinToString("") { "%02x".format(it) }); put("videoPort", video)
            if (audio != null) put("audioPort", audio)
        })
        fun send(type: Int, bytes: ByteArray = byteArrayOf()) = ToolboxProtocol.write(source.getOutputStream(), type, bytes)
        override fun close() { session.close(); source.close(); sink.close(); server.close(); silentUdp.close() }
    }
}
