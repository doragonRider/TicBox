package dev.ticbox.companion.toolboxagent

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.toolbox.*
import dev.ticbox.companion.protocol.*
import java.io.FilterOutputStream
import java.net.*
import java.nio.ByteBuffer
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*

/** Production backend + Agent UDP/bootstrap over real sockets; only capture/decoder are fixtures. */
class ScreenBootstrapIntegrationTest {
    @Test fun coldDecoderAndCompletelyLostFirstKeyRecoverOnAStaticSourceWithoutReliableMediaFallback() = runBlocking { withTimeout(15_000) {
        Fixture(this).use { f ->
            f.backend.connect()
            f.backend.openScreen(ToolboxScreenOptions(udp = true, audio = false, collectDiagnostics = false)).use { session ->
                val configurations = Channel<ToolboxScreenEvent.Configuration>(Channel.UNLIMITED)
                val frames = Channel<ToolboxScreenEvent.Frame>(Channel.UNLIMITED)
                val collecting = launch { checkNotNull(session.media).video.collect { event -> when (event) {
                    is ToolboxScreenEvent.Configuration -> configurations.send(event)
                    is ToolboxScreenEvent.Frame -> if (!event.codecConfig) frames.send(event)
                    else -> Unit
                } } }
                try {
                    val config = configurations.receive()
                    assertEquals(1L, config.generation)
                    delay(200) // A cold decoder: the source must not release its first UDP image yet.
                    assertEquals(0, f.proxy.mediaPackets.get())
                    session.videoDecoderReady(config.copy(generation = 2), 200)
                    delay(20)
                    assertEquals(0, f.proxy.mediaPackets.get())
                    session.videoDecoderReady(config, 220)
                    val key = frames.receive()
                    assertTrue(key.keyFrame); assertEquals(100L, key.ptsMicros)
                    assertContentEquals(f.picture, key.bytes)
                    withContext(Dispatchers.IO) { assertTrue(f.referenceConfirmed.await(2, TimeUnit.SECONDS)) }
                    assertTrue(f.proxy.droppedPackets.get() > 0)
                    assertTrue(frames.tryReceive().isFailure)
                    assertEquals(0, f.reliableImages.get())
                    assertTrue(f.errors.isEmpty(), f.errors.toString())
                } finally { collecting.cancelAndJoin() }
            }
        }
    } }

    @Test fun aStaticSourceAfterReferenceLossGetsOneVideoOnlyRestartWithFreshGeneration() = runBlocking { withTimeout(15_000) {
        Fixture(this, runtimeLoss = true).use { f ->
            f.backend.connect()
            f.backend.openScreen(ToolboxScreenOptions(udp = true, audio = false, collectDiagnostics = false)).use { session ->
                val generations = mutableListOf<Long>()
                val frames = Channel<ToolboxScreenEvent.Frame>(Channel.UNLIMITED)
                val collecting = launch { checkNotNull(session.media).video.collect { event -> when (event) {
                    is ToolboxScreenEvent.Configuration -> {
                        if (event.generation !in generations) generations += event.generation
                        session.videoDecoderReady(event, 0)
                    }
                    is ToolboxScreenEvent.Frame -> if (!event.codecConfig) frames.send(event)
                    else -> Unit
                } } }
                try {
                    assertEquals(100L, frames.receive().ptsMicros)
                    val recovered = frames.receive()
                    assertTrue(recovered.keyFrame)
                    assertEquals(200L, recovered.ptsMicros)
                    assertContentEquals(f.picture, recovered.bytes)
                    withContext(Dispatchers.IO) { assertTrue(f.resetReferenceConfirmed.await(2, TimeUnit.SECONDS)) }
                    assertEquals(listOf(1L, 2L), generations)
                    assertEquals(3, f.lightRequests.get())
                    assertEquals(1, f.captureRestarts.get())
                    assertEquals(0, f.reliableImages.get())
                    assertTrue(f.proxy.droppedPackets.get() > 0)
                    assertTrue(f.errors.isEmpty(), f.errors.toString())
                } finally { collecting.cancelAndJoin() }
            }
        }
    } }

    private class Fixture(scope: CoroutineScope, private val runtimeLoss: Boolean = false) : AutoCloseable {
        val proxy = DropFirstKeyProxy(dropFirstKey = !runtimeLoss, dropRuntimeDelta = runtimeLoss)
        val picture = ByteArray(80_000) { (it * 13).toByte() }
        val errors = LinkedBlockingQueue<Throwable>()
        val referenceConfirmed = CountDownLatch(1)
        val resetReferenceConfirmed = CountDownLatch(1)
        val lightRequests = AtomicInteger()
        val captureRestarts = AtomicInteger()
        val reliableImages = AtomicInteger()
        private val listener = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val executor = Executors.newCachedThreadPool()
        val backend = AgentToolboxBackend(ToolboxBackendKind.ADB, scope, object : ToolboxAgentConnector {
            override val device = "bootstrap-fixture"
            override val screenUdpHost = InetAddress.getLoopbackAddress().hostAddress
            override suspend fun prepare() = Unit
            override suspend fun open(): ToolboxChannel = toolboxIo(onDiscard = ToolboxChannel::close) {
                val phone = Socket(listener.inetAddress, listener.localPort).apply { tcpNoDelay = true }
                val agent = listener.accept().apply { tcpNoDelay = true }
                sockets.add(phone); sockets.add(agent)
                executor.execute { agent.use { runCatching { serve(agent) }.onFailure { if (!agent.isClosed) errors.put(it) } }; sockets.remove(agent) }
                object : ToolboxChannel {
                    override val secureScreenUdp = true // Authenticated local fixture; not a TLS implementation test.
                    override val input = phone.getInputStream()
                    override val output = phone.getOutputStream()
                    override fun close() { phone.close(); sockets.remove(phone) }
                }
            }
            override fun close() { sockets.forEach { runCatching(it::close) }; sockets.clear() }
        })
        private fun serve(socket: Socket) {
            val output = object : FilterOutputStream(socket.getOutputStream()) {
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (bytes[offset].toInt() in listOf(ToolboxProtocol.VIDEO_FRAME, ToolboxProtocol.AUDIO_FRAME)) reliableImages.incrementAndGet()
                    if (bytes[offset].toInt() == ToolboxProtocol.SCREEN_UDP_READY) {
                        val ready = ToolboxProtocol.required(bytes.copyOfRange(offset, offset + length).inputStream()).objectValue()
                        proxy.targetPort = ready.number("videoPort")!!.toInt()
                        ToolboxProtocol.write(out, ToolboxProtocol.SCREEN_UDP_READY, JsonObject(ready + ("videoPort" to JsonPrimitive(proxy.port))))
                    } else out.write(bytes, offset, length)
                }
            }
            val wire = AgentWire(socket.getInputStream(), output)
            wire.json(ToolboxProtocol.READY, buildJsonObject {
                put("version", ToolboxProtocol.VERSION); put("uid", 2000); put("sdk", 33); put("deviceId", "bootstrap-fixture")
                putJsonArray("features") { listOf("SCREEN", "SCREEN_UDP", "SCREEN_UDP_FEC", "SCREEN_KEYFRAME", "SCREEN_UDP_STARTUP").forEach(::add) }
            })
            val request = wire.read().objectValue()
            if (request.text("op") == "probe") { wire.json(ToolboxProtocol.RESULT, buildJsonObject { put("success", true) }); return }
            assertEquals("screen", request.text("op"))
            assertEquals(true, (request["args"] as JsonObject).flag("udpStartupGate"))
            val closed = AtomicBoolean()
            val resources = ScreenResources(closed)
            val media = resources.add(AgentScreenUdp(wire, false, closed, resources::close,
                videoProtection = ScreenUdpTransport.VideoProtection.RS_16_4, lightKeyframe = true, startupGate = true))
            try {
                media.establish()
                media.sendVideo(13, ScreenVideoBootstrapTest.config)
                media.sendVideo(14, ScreenVideoBootstrapTest.frame(Long.MIN_VALUE))
                media.sendVideo(14, ByteBuffer.allocate(picture.size + 8).putLong(ScreenVideoBootstrapTest.KEY or 100L).put(picture).array())
                referenceConfirmed.countDown()
                if (runtimeLoss) {
                    media.sendVideo(14, ScreenVideoBootstrapTest.frame(108)) // sequence 3 is entirely dropped
                    media.sendVideo(14, ScreenVideoBootstrapTest.frame(116))
                    // No new surface frames after the initial tail. A light request cannot emit
                    // a new sync picture; the real capture reset control must reach this source.
                    while (!closed.get()) {
                        val control = media.nextControl() ?: break
                        assertEquals(ToolboxProtocol.CONTROL, control.type)
                        when (control.bytes.single().toInt()) {
                            127 -> lightRequests.incrementAndGet()
                            17 -> {
                                assertEquals(1, captureRestarts.incrementAndGet())
                                media.sendVideo(13, ScreenVideoBootstrapTest.config)
                                media.sendVideo(14, ScreenVideoBootstrapTest.frame(Long.MIN_VALUE))
                                media.sendVideo(14, ByteBuffer.allocate(picture.size + 8).putLong(ScreenVideoBootstrapTest.KEY or 200L).put(picture).array())
                                resetReferenceConfirmed.countDown()
                            }
                            else -> error("Unexpected control")
                        }
                    }
                } else {
                    // Static source: no later frame exists to satisfy a sync-frame request.
                    while (!closed.get()) Thread.sleep(5)
                }
            } finally { resources.close() }
        }
        override fun close() {
            backend.close(); listener.close(); proxy.close(); executor.shutdownNow()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    /** Drops every UDP datagram of the first key message; all later retries retain real crypto/FEC. */
    private class DropFirstKeyProxy(private val dropFirstKey: Boolean = true, private val dropRuntimeDelta: Boolean = false) : AutoCloseable {
        private val socket = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val port get() = socket.localPort
        @Volatile var targetPort = 0
        val mediaPackets = AtomicInteger()
        val droppedPackets = AtomicInteger()
        private var phone: SocketAddress? = null
        private val thread = Thread {
            try {
                val bytes = ByteArray(2048)
                while (!socket.isClosed) {
                    val packet = DatagramPacket(bytes, bytes.size)
                    socket.receive(packet)
                    val fromAgent = packet.port == targetPort
                    if (!fromAgent) phone = packet.socketAddress
                    if (fromAgent && packet.length >= 32 && bytes[6].toInt() == 3) {
                        mediaPackets.incrementAndGet()
                        val sequence = ByteBuffer.wrap(bytes).getLong(16)
                        if ((dropFirstKey && sequence == 2L) || (dropRuntimeDelta && sequence == 3L)) { droppedPackets.incrementAndGet(); continue }
                    }
                    val destination = if (fromAgent) phone ?: continue else InetSocketAddress(InetAddress.getLoopbackAddress(), targetPort)
                    socket.send(DatagramPacket(bytes, packet.length, destination))
                }
            } catch (_: SocketException) { /* owned close */ }
        }.apply { isDaemon = true; start() }
        override fun close() { socket.close(); thread.join(1_000); assertFalse(thread.isAlive) }
    }
}
