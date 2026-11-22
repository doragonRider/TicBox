@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.ticbox.companion.engine

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import java.io.IOException
import kotlin.random.Random
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*

/** Real SessionEngine framing/writer/CONNECT integration against a caller-controlled wire peer. */
class ChannelSessionIntegrationTest {
    @Test fun `slow raw observer cannot drop domain commands or stop Channel acknowledgements`() = runTest {
        val rig = Rig(this); val commands = mutableListOf<Int>(); val rawBlocked = CompletableDeferred<Unit>()
        rig.engine.registerMessageHandler("commands", setOf("/command")) { commands += it.message.requestId }
        backgroundScope.launch { rig.engine.events.collect { rawBlocked.await() } }; runCurrent()
        rig.ready(); val channel = rig.open()
        rig.transport.receive(messageWire(1, "/command", byteArrayOf(1))); runCurrent()
        (2..20).forEach { rig.transport.receive(messageWire(it, "/command", byteArrayOf(1))); runCurrent() }
        assertEquals((1..20).toList(), commands)
        assertTrue(rig.engine.state.value.droppedMessageEvents > 0)
        assertEquals(0, rig.engine.state.value.failedBusinessMessages)
        rig.data(channel, 0, byteArrayOf(7), final = true); runCurrent()
        assertContentEquals(byteArrayOf(7), channel.read()); assertNull(channel.read()); runCurrent()
        assertTrue(rig.transport.channels().any { it.channel?.ack != null })
        rig.engine.close()
    }

    @Test fun `business overflow is visible and alternate route admission follows handler lifetime`() = runTest {
        val rig = Rig(this); val route = ApplicationRoute("independent", "other-digest")
        val gate = CompletableDeferred<Unit>(); val handled = mutableListOf<Int>()
        val registration = rig.engine.registerMessageHandler("slow", setOf("/command"), route) { gate.await(); handled += it.message.requestId }
        rig.ready()
        fun incoming(id: Int, path: String = "/command") = wire(WearMessages.encodeRpc(RpcMessage(id, route.packageName,
            route.packageDigest, LOCAL.nodeId, path, byteArrayOf(1), sourceNodeId = WATCH_ID)))
        rig.transport.receive(incoming(1)); runCurrent()
        (2..66).forEach { rig.transport.receive(incoming(it)) }; runCurrent()
        assertEquals(1, rig.engine.state.value.failedBusinessMessages)
        assertEquals(MessageDeliveryFailure("slow", "/command", 66, MessageDeliveryFailureKind.QUEUE_FULL), rig.engine.state.value.lastMessageDeliveryFailure)
        val channel = rig.open(); rig.data(channel, 0, byteArrayOf(8)); runCurrent(); assertContentEquals(byteArrayOf(8), channel.read())
        registration.close(); gate.complete(Unit); runCurrent()
        rig.transport.receive(incoming(67)); runCurrent(); assertTrue(handled.isEmpty())
        assertEquals(ConnectionPhase.LINK_READY, rig.engine.state.value.phase)
        rig.engine.close()
    }

    @Test fun `explicit alternate AppKey Channel isolates acknowledgements data and physical-session teardown`() = runTest {
        val rig = Rig(this); rig.ready()
        val other = ApplicationRoute("independent.mwf", "independent-digest")
        val opening = async { rig.engine.openChannel(PEER, rig.engine.state.value.sessionId, "/explicit/archive", route = other) }; runCurrent()
        val sent = rig.transport.channels().last()
        assertEquals(other.packageName, sent.packageName); assertEquals(other.packageDigest, sent.packageDigest)
        val id = sent.channel!!.control!!.channelId!!
        assertEquals(other.packageName, sent.channel!!.control!!.packageName)
        rig.transport.receive(channelWire(ChannelRequest(control = ChannelControl(ChannelCodec.OPEN_ACK, id, false)))); runCurrent()
        assertFalse(opening.isCompleted)
        rig.transport.receive(channelWire(ChannelRequest(control = ChannelControl(ChannelCodec.OPEN_ACK, id, false)),
            packageName = other.packageName, digest = other.packageDigest)); runCurrent()
        val channel = opening.await()
        rig.transport.receive(channelWire(ChannelRequest(data = ChannelData(ChannelHeader(id, false, 0), byteArrayOf(7, 8))),
            packageName = other.packageName, digest = other.packageDigest)); runCurrent()
        assertContentEquals(byteArrayOf(7, 8), channel.read())
        val waiting = async { runCatching { channel.read() } }; runCurrent()
        rig.engine.disconnect(); runCurrent()
        assertTrue(waiting.await().isFailure)
        rig.engine.close()
    }
    private class Transport : DuplexTransport {
        val input = Channel<ByteArray>(Channel.UNLIMITED)
        val writes = mutableListOf<ByteArray>()
        var beforeWrite: suspend () -> Unit = {}
        var closed = false
        private var activeWrites = 0
        var maximumWriters = 0
        override suspend fun read(): ByteArray? = input.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) {
            if (closed) throw IOException("closed")
            activeWrites++
            maximumWriters = maxOf(maximumWriters, activeWrites)
            try { beforeWrite(); if (closed) throw IOException("closed"); writes.add(bytes.copyOf()) }
            finally { activeWrites-- }
        }
        override fun close() { closed = true; input.close() }
        fun receive(bytes: ByteArray, chunkBytes: Int = 7_000) {
            var at = 0
            while (at < bytes.size) {
                val count = minOf(chunkBytes, bytes.size - at)
                check(input.trySend(bytes.copyOfRange(at, at + count)).isSuccess)
                at += count
            }
        }
        fun messages(): List<ByteArray> {
            val decoder = GmsRecordDecoder()
            val assembler = MessageAssembler()
            return buildList {
                for (bytes in writes) for (piece in decoder.push(bytes)) assembler.accept(piece)?.let(::add)
            }
        }
        fun rpcs() = messages().flatMap(WearMessages::decodeRpcs)
        fun channels() = rpcs().filter { it.kind == RpcKind.CHANNEL }
    }

    private class Rig(val test: TestScope, sendChunkBytes: Int = 128) {
        val transports = mutableListOf<Transport>()
        val engine = SessionEngine(
            test.backgroundScope,
            TransportFactory { Transport().also(transports::add) },
            IdentityStore { LOCAL }, ROUTE,
            SessionConfig(connectTimeoutMillis = 1_000, handshakeTimeoutMillis = 1_000, writeTimeoutMillis = 1_000,
                initialReconnectDelayMillis = 100, maxReconnectDelayMillis = 250, reconnectJitterRatio = 0.0,
                fragmentBytes = 256, eventBufferCapacity = 2),
            random = Random(5), channelConfig = ChannelConfig(sendChunkBytes = sendChunkBytes),
        )
        val transport get() = transports.last()
        suspend fun ready() {
            engine.connect(PEER); test.runCurrent()
            transport.receive(peerConnect(), chunkBytes = 3); test.runCurrent()
            assertEquals(ConnectionPhase.LINK_READY, engine.state.value.phase)
        }
        suspend fun open(path: String = "/setup/channel"): WearChannel {
            val opening = test.async { engine.openChannel(path) }; test.runCurrent()
            val id = transport.channels().last { it.channel?.control?.type == ChannelCodec.OPEN }.channel!!.control!!.channelId!!
            transport.receive(channelWire(ChannelRequest(control = ChannelControl(ChannelCodec.OPEN_ACK, id, false))), chunkBytes = 3)
            test.runCurrent(); return opening.await()
        }
        fun ack(channel: WearChannel, sequence: Long, final: Boolean = false) = transport.receive(channelWire(ChannelRequest(ack = ChannelAck(
            ChannelHeader(channel.info.id, !channel.info.localIsOpener, sequence), final,
        ))))
        fun data(channel: WearChannel, sequence: Long, bytes: ByteArray, final: Boolean = false) = transport.receive(channelWire(ChannelRequest(data = ChannelData(
            ChannelHeader(channel.info.id, !channel.info.localIsOpener, sequence), bytes, final,
        ))))
    }

    @Test fun `Channel uses field16 path in control no payload and shared non-interleaved RPC writer`() = runTest {
        val rig = Rig(this); rig.ready()
        val opening = async { rig.engine.openChannel("/setup/channel") }; runCurrent()
        val sent = rig.transport.channels().single()
        assertEquals("", sent.path)
        assertFalse(sent.payloadPresent)
        assertEquals(0, sent.generation)
        assertEquals(LOCAL.nodeId, sent.sourceNodeId)
        assertEquals(WATCH_ID, sent.targetNodeId)
        assertEquals(ROUTE.packageName, sent.packageName)
        assertEquals(ROUTE.packageDigest, sent.packageDigest)
        assertEquals("/setup/channel", sent.channel!!.control!!.path)
        val id = sent.channel!!.control!!.channelId!!
        // Wrong outer identity must not establish the pending channel.
        rig.transport.receive(channelWire(ChannelRequest(control = ChannelControl(ChannelCodec.OPEN_ACK, id, false)), packageName = "other.app"))
        runCurrent(); assertFalse(opening.isCompleted)
        val messageEvents = mutableListOf<BackendEvent.MessageReceived>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            rig.engine.events.collect { if (it is BackendEvent.MessageReceived) messageEvents.add(it) }
        }
        val combined = channelWire(ChannelRequest(control = ChannelControl(ChannelCodec.OPEN_ACK, id, false))) + messageWire(30, "/ordinary", byteArrayOf(7))
        rig.transport.receive(combined, chunkBytes = 11); runCurrent()
        val channel = opening.await()
        assertEquals(listOf("/ordinary"), messageEvents.map { it.message.path })
        val writing = async { channel.write(ByteArray(300) { it.toByte() }) }
        val ordinary = async { rig.engine.sendMessage("/message", ByteArray(800) { 9 }) }
        runCurrent(); ordinary.await()
        for (sequence in 0L..2L) { rig.ack(channel, sequence); runCurrent() }
        writing.await()
        val requests = rig.transport.rpcs()
        assertEquals(requests.size, requests.map { it.requestId }.toSet().size)
        assertEquals(requests.map { it.requestId }.sorted(), requests.map { it.requestId })
        assertEquals(1, rig.transport.maximumWriters)
        assertTrue(requests.any { it.kind == RpcKind.MESSAGE && it.path == "/message" })
        assertEquals(listOf(0L, 1L, 2L), requests.mapNotNull { it.channel?.data?.header?.sequence })
        rig.engine.close()
    }

    @Test fun `slow raw Message observer cannot stall Channel open or DATA ACK and overflow is visible`() = runTest {
        val rig = Rig(this)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            rig.engine.events.collect { delay(60_000) }
        }
        rig.ready()
        rig.transport.receive((1..25).map { messageWire(it, "/status", byteArrayOf(1)) }.reduce(ByteArray::plus))
        runCurrent()
        assertTrue(rig.engine.state.value.droppedMessageEvents > 0)
        assertEquals(ConnectionPhase.LINK_READY, rig.engine.state.value.phase)
        val channel = rig.open()
        val writing = async { channel.write(byteArrayOf(1, 2, 3)) }; runCurrent()
        rig.ack(channel, 0); runCurrent(); writing.await()
        rig.data(channel, 0, byteArrayOf(5, 6)); runCurrent()
        assertContentEquals(byteArrayOf(5, 6), channel.read()); runCurrent()
        assertEquals(0L, rig.transport.channels().last().channel!!.ack!!.header!!.sequence)
        assertEquals(ConnectionPhase.LINK_READY, rig.engine.state.value.phase)
        val dropped = rig.engine.state.value.droppedMessageEvents
        rig.transport.input.close(); runCurrent()
        assertEquals(ConnectionPhase.RETRYING, rig.engine.state.value.phase)
        assertEquals(dropped, rig.engine.state.value.droppedMessageEvents)
        advanceTimeBy(100); runCurrent()
        assertEquals(0L, rig.engine.state.value.droppedMessageEvents)
        rig.engine.close()
    }

    @Test fun `reconnect terminates old handles pending OPEN and ACK state instead of moving them`() = runTest {
        val rig = Rig(this); rig.ready()
        val old = rig.open("/old")
        val reading = async { runCatching { old.read() } }
        val pendingOpen = async { runCatching { rig.engine.openChannel("/pending") } }
        runCurrent()
        val oldId = rig.transport.channels().last { it.channel?.control?.type == ChannelCodec.OPEN }.channel!!.control!!.channelId
        val oldSession = rig.engine.state.value.sessionId
        rig.transport.input.close(); runCurrent()
        assertEquals(ChannelEndReason.LINK_LOST, assertIs<ChannelClosedException>(reading.await().exceptionOrNull()).reason)
        assertEquals(ChannelEndReason.LINK_LOST, assertIs<ChannelClosedException>(pendingOpen.await().exceptionOrNull()).reason)
        assertEquals(ChannelPhase.CLOSED, old.state.value.phase)
        advanceTimeBy(100); runCurrent()
        rig.transport.receive(peerConnect()); runCurrent()
        assertNotEquals(oldSession, rig.engine.state.value.sessionId)
        val fresh = async { rig.engine.openChannel("/fresh") }; runCurrent()
        val freshId = rig.transport.channels().last().channel!!.control!!.channelId
        assertNotEquals(oldId, freshId)
        rig.transport.receive(channelWire(ChannelRequest(control = ChannelControl(ChannelCodec.OPEN_ACK, oldId, false)))); runCurrent()
        assertFalse(fresh.isCompleted)
        rig.transport.receive(channelWire(ChannelRequest(control = ChannelControl(ChannelCodec.OPEN_ACK, freshId, false)))); runCurrent()
        assertEquals(rig.engine.state.value.sessionId, fresh.await().info.sessionId)
        assertFailsWith<ChannelClosedException> { old.write(byteArrayOf(1)) }
        rig.engine.close()
    }

    @Test fun `coalesced CONNECT and incoming OPEN dispatch once with per-session AppKey filtering`() = runTest {
        val rig = Rig(this)
        rig.engine.connect(PEER); runCurrent()
        val valid = ChannelRequest(control = ChannelControl(ChannelCodec.OPEN, 7uL, true, ROUTE.packageName, ROUTE.packageDigest, "/permission"))
        val other = ChannelRequest(control = ChannelControl(ChannelCodec.OPEN, 8uL, true, ROUTE.packageName, ROUTE.packageDigest, "/other"))
        rig.transport.receive(peerConnect() + channelWire(other, sourceNode = "different-node") + channelWire(other, targetNode = "different-phone") + channelWire(valid))
        runCurrent()
        val accepted = rig.engine.acceptChannel()
        assertEquals(7uL, accepted.info.id)
        assertFalse(accepted.info.localIsOpener)
        assertEquals("/permission", accepted.info.path)
        assertEquals(1, rig.transport.channels().size)
        assertEquals(ChannelCodec.OPEN_ACK, rig.transport.channels().single().channel!!.control!!.type)
        rig.engine.close()
    }

    @Test fun `queued old CLOSE is checked again at wire commit after incoming ID is reused`() = runTest {
        val rig = Rig(this); rig.ready()
        fun incoming(path: String) = channelWire(ChannelRequest(control = ChannelControl(ChannelCodec.OPEN, 9uL, true, ROUTE.packageName, ROUTE.packageDigest, path)))
        rig.transport.receive(incoming("/old")); runCurrent()
        val old = rig.engine.acceptChannel()
        val blocked = CompletableDeferred<Unit>()
        var blockNext = true
        rig.transport.beforeWrite = { if (blockNext) { blockNext = false; blocked.await() } }
        val ordinary = async { rig.engine.sendMessage("/held", byteArrayOf(1)) }; runCurrent()
        old.close(); runCurrent() // Its CLOSE is waiting behind the Message writer mutex.
        rig.transport.receive(incoming("/new")); runCurrent()
        val fresh = rig.engine.acceptChannel()
        blocked.complete(Unit); runCurrent(); ordinary.await()
        assertEquals("/new", fresh.info.path)
        assertTrue(rig.transport.channels().none { it.channel?.control?.type == ChannelCodec.CLOSE })
        assertEquals(ChannelPhase.OPEN, fresh.state.value.phase)
        rig.data(fresh, 0, byteArrayOf(2)); runCurrent(); assertContentEquals(byteArrayOf(2), fresh.read())
        rig.engine.close()
    }

    @Test fun `channel cancellation cannot cancel an in-progress fragmented physical batch`() = runTest {
        val rig = Rig(this, sendChunkBytes = 512); rig.ready()
        val channel = rig.open()
        val blocked = CompletableDeferred<Unit>()
        var blockNext = true
        rig.transport.beforeWrite = { if (blockNext) { blockNext = false; blocked.await() } }
        val writing = async { runCatching { channel.write(ByteArray(512)) } }; runCurrent()
        channel.close(); runCurrent()
        assertEquals(ChannelEndReason.LOCAL_CLOSE, assertIs<ChannelClosedException>(writing.await().exceptionOrNull()).reason)
        assertFalse(rig.transport.closed)
        blocked.complete(Unit); runCurrent()
        assertEquals(ConnectionPhase.LINK_READY, rig.engine.state.value.phase)
        assertFalse(rig.transport.closed)
        assertEquals(1, rig.transport.channels().count { it.channel?.data != null })
        assertEquals(1, rig.transport.channels().count { it.channel?.control?.type == ChannelCodec.CLOSE })
        rig.engine.sendMessage("/still-usable", byteArrayOf(1))
        rig.engine.close()
    }

    @Test fun `oversize DATA closes that Channel without making valid RFCOMM framing fatal`() = runTest {
        val rig = Rig(this); rig.ready()
        val one = rig.open("/one")
        val two = rig.open("/two")
        rig.data(one, 0, ByteArray(65_537)); runCurrent()
        assertEquals(ChannelEndReason.PROTOCOL, one.state.value.endReason)
        assertEquals(ConnectionPhase.LINK_READY, rig.engine.state.value.phase)
        rig.data(two, 0, byteArrayOf(4)); runCurrent(); assertContentEquals(byteArrayOf(4), two.read())
        rig.engine.close()
    }

    @Test fun `application length frames can span separate Channel DATA blocks`() = runTest {
        val rig = Rig(this); rig.ready()
        val channel = rig.open()
        val payload = SetupProtocol.encodeQuery(SetupQuery.GET_SYSTEM_INFO, 17)
        val framed = SetupProtocol.frame(payload)
        val decoder = SetupFrameDecoder()
        rig.data(channel, 0, framed.copyOfRange(0, 2)); runCurrent()
        assertTrue(decoder.push(channel.read()!!).isEmpty()); runCurrent()
        rig.data(channel, 1, framed.copyOfRange(2, framed.size), final = true); runCurrent()
        val decoded = decoder.push(channel.read()!!)
        assertEquals(1, decoded.size)
        assertContentEquals(payload, decoded.single())
        assertNull(channel.read()); decoder.finish()
        rig.engine.close()
    }

    companion object {
        val PEER = PeerAddress("AA:BB:CC:DD:EE:FF")
        val LOCAL = NodeIdentity("phone-node", "network", "Phone")
        val ROUTE = ApplicationRoute("example.companion", "digest")
        const val WATCH_ID = "watch-node"
        fun peerConnect() = wire(WearMessages.encodeConnect(ConnectMessage(WATCH_ID, "Enduro")))
        fun messageWire(id: Int, path: String, bytes: ByteArray) = wire(WearMessages.encodeRpc(RpcMessage(id, ROUTE.packageName, ROUTE.packageDigest, LOCAL.nodeId, path, bytes, sourceNodeId = WATCH_ID)))
        fun channelWire(request: ChannelRequest, packageName: String = ROUTE.packageName, sourceNode: String = WATCH_ID, targetNode: String = LOCAL.nodeId,
            digest: String = ROUTE.packageDigest) = wire(WearMessages.encodeRpc(RpcMessage(
            900, packageName, digest, targetNode, "", sourceNodeId = sourceNode,
            channel = request, generation = 0, kind = RpcKind.CHANNEL, payloadPresent = false,
        )))
        fun wire(message: ByteArray) = MessagePieces.encode(message, 8192, 900).map(GmsRecord::encode).reduce(ByteArray::plus)
    }
}
