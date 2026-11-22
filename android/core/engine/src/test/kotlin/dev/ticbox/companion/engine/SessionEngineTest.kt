@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.ticbox.companion.engine

import dev.ticbox.companion.api.ApplicationRoute
import dev.ticbox.companion.api.BackendClosedException
import dev.ticbox.companion.api.BackendEvent
import dev.ticbox.companion.api.CompanionBackend
import dev.ticbox.companion.api.ConnectionPhase
import dev.ticbox.companion.api.DuplexTransport
import dev.ticbox.companion.api.DiagnosticSink
import dev.ticbox.companion.api.ProtocolDiagnostic
import dev.ticbox.companion.api.FailureKind
import dev.ticbox.companion.api.IdentityStore
import dev.ticbox.companion.api.NodeIdentity
import dev.ticbox.companion.api.NotConnectedException
import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.api.TransmissionStage
import dev.ticbox.companion.api.TransportFactory
import dev.ticbox.companion.protocol.ConnectMessage
import dev.ticbox.companion.protocol.GmsRecord
import dev.ticbox.companion.protocol.GmsRecordDecoder
import dev.ticbox.companion.protocol.MessageAssembler
import dev.ticbox.companion.protocol.MessagePieces
import dev.ticbox.companion.protocol.RpcMessage
import dev.ticbox.companion.protocol.WearMessages
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class SessionEngineTest {
    @Test
    fun `diagnostics distinguish socket acquisition failure from a peer closing before CONNECT`() = runTest {
        val acquisitionNotes = mutableListOf<ProtocolDiagnostic>()
        val acquisition = SessionEngine(backgroundScope, TransportFactory { throw IOException("read ret -1") },
            IdentityStore { LOCAL }, ROUTE, config = testConfig(maxReconnectAttempts = 0),
            diagnostics = DiagnosticSink { acquisitionNotes.add(it) })
        acquisition.connect(PEER); runCurrent()
        assertEquals("opening_rfcomm", acquisitionNotes.single { it.event == "handshake_incomplete" }.details["stage"])
        assertFalse(acquisitionNotes.any { it.event == "rfcomm_opened" || it.event == "connect_sent" })
        acquisition.close()

        val transport = FakeTransport()
        val handshakeNotes = mutableListOf<ProtocolDiagnostic>()
        val handshake = SessionEngine(backgroundScope, TransportFactory { transport }, IdentityStore { LOCAL }, ROUTE,
            config = testConfig(maxReconnectAttempts = 0), diagnostics = DiagnosticSink { handshakeNotes.add(it) })
        handshake.connect(PEER); runCurrent(); transport.endInput(); runCurrent()
        val ended = handshakeNotes.single { it.event == "handshake_incomplete" }
        assertEquals("waiting_peer_connect", ended.details["stage"])
        assertEquals("0", ended.details["incomingBytes"])
        assertTrue(ended.details.getValue("outgoingBytes").toLong() > 0)
        assertTrue(handshakeNotes.any { it.event == "rfcomm_opened" })
        assertTrue(transport.closed); assertEquals(ConnectionPhase.FAILED, handshake.state.value.phase)
        handshake.close()
    }

    @Test
    fun `handshake diagnostics report buffered input counts without copying message bodies`() = runTest {
        val transport = FakeTransport(); val notes = mutableListOf<ProtocolDiagnostic>()
        val engine = SessionEngine(backgroundScope, TransportFactory { transport }, IdentityStore { LOCAL }, ROUTE,
            config = testConfig(maxReconnectAttempts = 0), diagnostics = DiagnosticSink { notes.add(it) })
        engine.connect(PEER); runCurrent()
        val bytes = rpcWire(1, "/private-path", "private-health-value".toByteArray())
        transport.enqueue(bytes); transport.endInput(); runCurrent()
        val ended = notes.single { it.event == "handshake_incomplete" }
        assertEquals("waiting_peer_connect", ended.details["stage"])
        assertEquals(bytes.size.toString(), ended.details["incomingBytes"])
        assertEquals("1", ended.details["decodedMessages"])
        assertFalse(notes.any { it.details.toString().contains("private-health-value") || it.details.toString().contains("/private-path") })
        assertTrue(transport.closed); engine.close()
    }

    @Test
    fun `a CONNECT response is required before the link is ready`() = runTest {
        val transport = FakeTransport()
        val engine = engine { transport }
        engine.connect(PEER)
        runCurrent()
        assertEquals(ConnectionPhase.NEGOTIATING, engine.state.value.phase)
        assertFailsWith<NotConnectedException> { engine.sendMessage("/steps", byteArrayOf(1)) }
        assertEquals(1, decodeWrites(transport).size)
        assertEquals(LOCAL.nodeId, WearMessages.connectOrNull(decodeWrites(transport).single())!!.nodeId)
        transport.enqueue(peerConnect())
        runCurrent()
        assertEquals(ConnectionPhase.LINK_READY, engine.state.value.phase)
        assertEquals("Enduro", engine.state.value.peerName)
        assertEquals(1, engine.state.value.negotiatedWireVersion)
        engine.close()
    }

    @Test
    fun `missing optional peer versions use protobuf zero defaults`() = runTest {
        val transport = FakeTransport()
        val engine = engine { transport }
        engine.connect(PEER)
        runCurrent()
        // Independently spelled WearMessage(field7 Connect(node_id=n, node_name=N)); no versions.
        transport.enqueue(wire(hex("3a060a016e12014e")))
        runCurrent()
        assertEquals(ConnectionPhase.LINK_READY, engine.state.value.phase)
        assertEquals(0, engine.state.value.negotiatedWireVersion)
        engine.close()
    }

    @Test
    fun `disjoint version ranges fail without announcing readiness or retrying`() = runTest {
        val transport = FakeTransport()
        val engine = engine { transport }
        val phases = mutableListOf<ConnectionPhase>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.state.collect { phases.add(it.phase) }
        }
        engine.connect(PEER)
        runCurrent()
        transport.enqueue(peerConnect(wireVersion = 2, minWireVersion = 2))
        runCurrent()
        assertEquals(ConnectionPhase.FAILED, engine.state.value.phase)
        assertEquals(FailureKind.PROTOCOL, engine.state.value.failure?.kind)
        assertFalse(ConnectionPhase.LINK_READY in phases)
        assertTrue(transport.closed)
        engine.close()
    }

    @Test
    fun `handshake timeout closes the socket then retries with the same identity`() = runTest {
        val transports = mutableListOf<FakeTransport>()
        var loads = 0
        val engine = SessionEngine(
            backgroundScope,
            TransportFactory { FakeTransport().also(transports::add) },
            IdentityStore { loads++; LOCAL },
            ROUTE,
            config = testConfig(maxReconnectAttempts = 1),
        )
        engine.connect(PEER)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(transports[0].closed)
        assertEquals(ConnectionPhase.RETRYING, engine.state.value.phase)
        assertEquals(FailureKind.TIMEOUT, engine.state.value.failure?.kind)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(2, transports.size)
        assertEquals(1, loads)
        assertEquals(LOCAL.nodeId, WearMessages.connectOrNull(decodeWrites(transports[1]).single())!!.nodeId)
        engine.close()
    }

    @Test
    fun `transient acquisition failures use capped exponential backoff`() = runTest {
        var attempts = 0
        val engine = engine(config = testConfig(maxReconnectAttempts = null)) {
            attempts++
            throw IOException("radio disconnected")
        }
        engine.connect(PEER)
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(100L, engine.state.value.retryDelayMillis)
        advanceTimeBy(99)
        runCurrent()
        assertEquals(1, attempts)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, attempts)
        assertEquals(200L, engine.state.value.retryDelayMillis)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(3, attempts)
        assertEquals(250L, engine.state.value.retryDelayMillis)
        engine.disconnect()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(3, attempts)
        assertEquals(ConnectionPhase.IDLE, engine.state.value.phase)
        engine.close()
    }

    @Test
    fun `reconnect budget exhaustion is visible and an explicit connect may restart it`() = runTest {
        var attempts = 0
        val engine = engine(config = testConfig(maxReconnectAttempts = 1)) {
            attempts++
            throw IOException("offline")
        }
        engine.connect(PEER)
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(2, attempts)
        assertEquals(ConnectionPhase.FAILED, engine.state.value.phase)
        engine.connect(PEER)
        runCurrent()
        assertEquals(3, attempts)
        engine.close()
    }

    @Test
    fun `permission and identity errors require intervention instead of reconnect loops`() = runTest {
        var attempts = 0
        val denied = engine {
            attempts++
            throw SecurityException("Bluetooth permission is required")
        }
        denied.connect(PEER)
        runCurrent()
        assertEquals(ConnectionPhase.FAILED, denied.state.value.phase)
        assertEquals(FailureKind.PERMISSION_REQUIRED, denied.state.value.failure?.kind)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, attempts)
        denied.close()

        val identityFailure = SessionEngine(
            backgroundScope,
            TransportFactory { error("Identity failure must not reach the transport") },
            IdentityStore { throw IOException("corrupt identity file") },
            ROUTE,
        )
        identityFailure.connect(PEER)
        runCurrent()
        assertEquals(FailureKind.IDENTITY, identityFailure.state.value.failure?.kind)
        assertEquals(ConnectionPhase.FAILED, identityFailure.state.value.phase)
        identityFailure.close()
    }

    @Test
    fun `explicit disconnect closes an active read and prevents reconnection`() = runTest {
        var opens = 0
        val transport = FakeTransport()
        val engine = engine { opens++; transport }
        engine.connect(PEER)
        runCurrent()
        transport.enqueue(peerConnect())
        runCurrent()
        engine.disconnect()
        assertTrue(transport.closed)
        advanceTimeBy(100_000)
        runCurrent()
        assertEquals(1, opens)
        assertEquals(ConnectionPhase.IDLE, engine.state.value.phase)
        engine.close()
    }

    @Test
    fun `concurrent peer changes and disconnects leave one current session`() = runTest {
        val transports = mutableListOf<FakeTransport>()
        val engine = engine { FakeTransport().also(transports::add) }
        val peers = listOf(PEER, OTHER_PEER, PEER)
        peers.map { peer -> async { engine.connect(peer) } }.awaitAll()
        runCurrent()
        assertEquals(PEER, engine.state.value.peer)
        assertEquals(1, transports.count { !it.closed })
        val current = transports.single { !it.closed }
        current.enqueue(peerConnect())
        runCurrent()
        assertEquals(ConnectionPhase.LINK_READY, engine.state.value.phase)
        val before = transports.size
        engine.connect(PeerAddress(PEER.value.lowercase()))
        runCurrent()
        assertEquals(before, transports.size)
        List(8) { async { engine.disconnect() } }.awaitAll()
        runCurrent()
        assertEquals(0, transports.count { !it.closed })
        assertEquals(ConnectionPhase.IDLE, engine.state.value.phase)
        engine.close()
    }

    @Test
    fun `an old peer cannot publish into a replacement session`() = runTest {
        val transports = mutableListOf<FakeTransport>()
        val engine = engine { FakeTransport().also(transports::add) }
        engine.connect(PEER)
        runCurrent()
        val old = transports.single()
        old.enqueue(peerConnect())
        runCurrent()
        val oldEpoch = engine.state.value.sessionId
        engine.connect(OTHER_PEER)
        runCurrent()
        val current = transports.last()
        val epoch = engine.state.value.sessionId
        assertTrue(old.closed)
        assertNotEquals(oldEpoch, epoch)
        assertFalse(old.tryEnqueue(peerConnect(nodeId = "stale-node")))
        current.enqueue(peerConnect(nodeId = "new-node"))
        runCurrent()
        assertEquals(OTHER_PEER, engine.state.value.peer)
        assertEquals(epoch, engine.state.value.sessionId)
        assertEquals(ConnectionPhase.LINK_READY, engine.state.value.phase)
        engine.close()
    }

    @Test
    fun `automatic reconnect gives each transport attempt its own public session ID`() = runTest {
        val transports = mutableListOf<FakeTransport>()
        val interruptions = mutableListOf<BackendEvent.ConnectionInterrupted>()
        val engine = engine { FakeTransport().also(transports::add) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.events.collect { if (it is BackendEvent.ConnectionInterrupted) interruptions.add(it) }
        }
        engine.connect(PEER)
        runCurrent()
        val firstId = engine.state.value.sessionId
        assertTrue(firstId > 0)
        transports[0].enqueue(peerConnect())
        runCurrent()
        assertEquals(firstId, engine.state.value.sessionId)
        transports[0].endInput()
        runCurrent()
        assertEquals(ConnectionPhase.RETRYING, engine.state.value.phase)
        assertEquals(firstId, engine.state.value.sessionId)
        assertEquals(firstId, interruptions.single().sessionId)

        advanceTimeBy(100)
        runCurrent()
        val secondId = engine.state.value.sessionId
        assertEquals(ConnectionPhase.NEGOTIATING, engine.state.value.phase)
        assertTrue(secondId > firstId)
        transports[1].enqueue(peerConnect())
        runCurrent()
        assertEquals(ConnectionPhase.LINK_READY, engine.state.value.phase)
        assertEquals(secondId, engine.state.value.sessionId)
        transports[1].endInput()
        runCurrent()
        assertEquals(secondId, interruptions.last().sessionId)
        advanceTimeBy(checkNotNull(engine.state.value.retryDelayMillis))
        runCurrent()
        assertTrue(engine.state.value.sessionId > secondId)
        engine.disconnect()
        assertEquals(0L, engine.state.value.sessionId)
        engine.close()
    }

    @Test
    fun `health observations stay stale after automatic reconnect until that field is observed again`() = runTest {
        val transports = mutableListOf<FakeTransport>()
        val engine = engine { FakeTransport().also(transports::add) }
        val client = WatchClientFacade(backgroundScope, engine)
        client.connect(PEER)
        runCurrent()
        transports[0].enqueue(concat(listOf(
            peerConnect(),
            rpcWire(1, BATTERY_PATH, "42".toByteArray()),
            rpcWire(2, STEPS_PATH, """{"current_step":120,"goal":8000}""".toByteArray()),
        )))
        runCurrent()
        assertEquals(42, client.state.value.battery?.value)
        assertEquals(120, client.state.value.steps?.value?.currentSteps)
        assertEquals(false, client.state.value.battery?.stale)
        assertEquals(false, client.state.value.steps?.stale)
        val firstId = engine.state.value.sessionId

        transports[0].endInput()
        runCurrent()
        assertEquals(true, client.state.value.battery?.stale)
        assertEquals(true, client.state.value.steps?.stale)
        advanceTimeBy(100)
        runCurrent()
        transports[1].enqueue(peerConnect())
        runCurrent()
        assertEquals(ConnectionPhase.LINK_READY, client.state.value.connection.phase)
        assertNotEquals(firstId, client.state.value.connection.sessionId)
        assertEquals(42, client.state.value.battery?.value)
        assertEquals(120, client.state.value.steps?.value?.currentSteps)
        assertEquals(true, client.state.value.battery?.stale)
        assertEquals(true, client.state.value.steps?.stale)

        transports[1].enqueue(rpcWire(1, BATTERY_PATH, "43".toByteArray()))
        runCurrent()
        assertEquals(43, client.state.value.battery?.value)
        assertEquals(false, client.state.value.battery?.stale)
        assertEquals(true, client.state.value.steps?.stale)
        transports[1].enqueue(rpcWire(2, STEPS_PATH, """{"current_step":121,"goal":8000}""".toByteArray()))
        runCurrent()
        assertEquals(121, client.state.value.steps?.value?.currentSteps)
        assertEquals(false, client.state.value.steps?.stale)
        client.close()
    }

    @Test
    fun `an event buffered from an old connection cannot refresh the reconnected watch`() = runTest {
        val transports = mutableListOf<FakeTransport>()
        val engine = engine { FakeTransport().also(transports::add) }
        val recorded = mutableListOf<BackendEvent.MessageReceived>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.events.collect { if (it is BackendEvent.MessageReceived) recorded.add(it) }
        }
        // Model a consumer queue that delivers already-emitted observations after reconnection.
        // Its live connection state is the real engine state; only event delivery is delayed.
        val relayed = MutableSharedFlow<BackendEvent>(extraBufferCapacity = 8)
        val delayedSession = object : CompanionBackend by engine {
            override val events = relayed.asSharedFlow()
        }
        val client = WatchClientFacade(backgroundScope, delayedSession)
        client.connect(PEER)
        runCurrent()
        transports[0].enqueue(concat(listOf(peerConnect(), rpcWire(1, BATTERY_PATH, "42".toByteArray()))))
        runCurrent()
        relayed.emit(recorded.single())
        runCurrent()
        assertEquals(false, client.state.value.battery?.stale)
        transports[0].enqueue(rpcWire(2, BATTERY_PATH, "99".toByteArray()))
        runCurrent()
        val held = recorded.last()

        transports[0].endInput()
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        transports[1].enqueue(peerConnect())
        runCurrent()
        assertNotEquals(held.sessionId, engine.state.value.sessionId)
        relayed.emit(held)
        runCurrent()
        assertEquals(42, client.state.value.battery?.value)
        assertEquals(true, client.state.value.battery?.stale)

        transports[1].enqueue(rpcWire(1, BATTERY_PATH, "43".toByteArray()))
        runCurrent()
        assertEquals(engine.state.value.sessionId, recorded.last().sessionId)
        relayed.emit(recorded.last())
        runCurrent()
        assertEquals(43, client.state.value.battery?.value)
        assertEquals(false, client.state.value.battery?.stale)
        client.close()
    }

    @Test
    fun `concurrent fragmented writes are serialized and receipts do not claim delivery`() = runTest {
        val transport = FakeTransport()
        val engine = engine(config = testConfig(fragmentBytes = 256)) { transport }
        engine.connect(PEER)
        runCurrent()
        transport.enqueue(peerConnect())
        runCurrent()
        transport.writeDelayMillis = 1
        val firstBody = ByteArray(2_000) { 0x11 }
        val secondBody = ByteArray(2_000) { 0x22 }
        val first = async { engine.sendMessage("/first", firstBody) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { engine.sendMessage("/second", secondBody) }
        // The second caller copied before its first suspension, even if it is the first to write.
        secondBody.fill(0x33)
        advanceTimeBy(100)
        runCurrent()
        val receipts = listOf(first.await(), second.await())
        assertTrue(receipts.all { it.stage == TransmissionStage.WRITTEN_TO_TRANSPORT })
        assertTrue(receipts.all { it.wireBytes > 2_000 })
        assertNotEquals(receipts[0].requestId, receipts[1].requestId)
        assertEquals(1, transport.maxConcurrentWrites)
        val messages = decodeWrites(transport).flatMap(WearMessages::decodeRpcs).associateBy { it.path }
        assertContentEquals(firstBody, messages.getValue("/first").payload)
        assertContentEquals(ByteArray(2_000) { 0x22 }, messages.getValue("/second").payload)
        assertEquals("watch-node", messages.getValue("/first").targetNodeId)
        engine.close()
    }

    @Test
    fun `a cancelled active write closes the stream rather than leaving a partial record`() = runTest {
        val transport = FakeTransport()
        val engine = engine { transport }
        engine.connect(PEER)
        runCurrent()
        transport.enqueue(peerConnect())
        runCurrent()
        transport.writeDelayMillis = 10_000
        val send = async { engine.sendMessage("/cancel", byteArrayOf(1)) }
        runCurrent()
        send.cancel()
        runCurrent()
        assertTrue(transport.closed)
        assertEquals(ConnectionPhase.RETRYING, engine.state.value.phase)
        engine.close()
    }

    @Test
    fun `cancelling a queued writer does not interrupt a valid active writer`() = runTest {
        val transport = FakeTransport()
        val engine = engine { transport }
        engine.connect(PEER)
        runCurrent()
        transport.enqueue(peerConnect())
        runCurrent()
        transport.writeDelayMillis = 10
        val first = async(start = CoroutineStart.UNDISPATCHED) { engine.sendMessage("/first", byteArrayOf(1)) }
        val queued = async(start = CoroutineStart.UNDISPATCHED) { engine.sendMessage("/queued", byteArrayOf(2)) }
        queued.cancel()
        runCurrent()
        assertFalse(transport.closed)
        advanceTimeBy(10)
        runCurrent()
        assertEquals(TransmissionStage.WRITTEN_TO_TRANSPORT, first.await().stage)
        assertEquals(ConnectionPhase.LINK_READY, engine.state.value.phase)
        val messages = decodeWrites(transport).flatMap(WearMessages::decodeRpcs)
        assertEquals(listOf("/first"), messages.map { it.path })
        engine.close()
    }

    @Test
    fun `disconnect cancels acquisition and the factory disposes its undelivered socket`() = runTest {
        val candidate = FakeTransport()
        var cancelledAcquisitions = 0
        val engine = engine {
            try {
                delay(10_000)
                candidate
            } catch (cancelled: CancellationException) {
                cancelledAcquisitions++
                candidate.close()
                throw cancelled
            }
        }
        engine.connect(PEER)
        runCurrent()
        assertEquals(ConnectionPhase.CONNECTING, engine.state.value.phase)
        engine.disconnect()
        assertEquals(1, cancelledAcquisitions)
        assertTrue(candidate.closed)
        assertEquals(ConnectionPhase.IDLE, engine.state.value.phase)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, cancelledAcquisitions)
        engine.close()
    }

    @Test
    fun `a stalled acquisition times out and closes the untransferred socket`() = runTest {
        val candidate = FakeTransport()
        val engine = engine {
            try {
                delay(10_000)
                candidate
            } catch (cancelled: CancellationException) {
                candidate.close()
                throw cancelled
            }
        }
        engine.connect(PEER)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(candidate.closed)
        assertEquals(ConnectionPhase.RETRYING, engine.state.value.phase)
        assertEquals(FailureKind.TIMEOUT, engine.state.value.failure?.kind)
        engine.close()
    }

    @Test
    fun `a timed out write invalidates the stream and fails the local receipt`() = runTest {
        val transport = FakeTransport()
        val engine = engine { transport }
        engine.connect(PEER)
        runCurrent()
        transport.enqueue(peerConnect())
        runCurrent()
        transport.writeDelayMillis = 10_000
        val result = async { runCatching { engine.sendMessage("/timeout", byteArrayOf(1)) } }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(result.await().isFailure)
        assertTrue(transport.closed)
        assertEquals(ConnectionPhase.RETRYING, engine.state.value.phase)
        engine.close()
    }

    @Test
    fun `coalesced post-CONNECT messages are not charged to the pre-CONNECT message budget`() = runTest {
        val transport = FakeTransport()
        val engine = engine(config = testConfig(maxPreConnectMessages = 1)) { transport }
        val received = mutableListOf<BackendEvent.MessageReceived>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.events.collect { if (it is BackendEvent.MessageReceived) received.add(it) }
        }
        engine.connect(PEER)
        runCurrent()
        val messages = (1..40).map { id -> rpcWire(id, "/steps", byteArrayOf(id.toByte())) }
        transport.enqueue(concat(listOf(peerConnect()) + messages))
        runCurrent()
        assertEquals(ConnectionPhase.LINK_READY, engine.state.value.phase)
        assertEquals(40, received.size)
        assertEquals((1..40).toList(), received.map { it.message.requestId })
        assertTrue(received.all { it.sessionId == engine.state.value.sessionId })
        val copy = received[0].message.payload
        copy[0] = 99
        assertContentEquals(byteArrayOf(1), received[0].message.payload)
        engine.close()
    }

    @Test
    fun `RPC routing ignores other applications and other target nodes`() = runTest {
        val transport = FakeTransport()
        val engine = engine { transport }
        val received = mutableListOf<BackendEvent.MessageReceived>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.events.collect { if (it is BackendEvent.MessageReceived) received.add(it) }
        }
        engine.connect(PEER)
        runCurrent()
        transport.enqueue(concat(listOf(
            peerConnect(),
            rpcWire(1, "/wrong-target", byteArrayOf(1), target = "another-phone"),
            rpcWire(2, "/wrong-app", byteArrayOf(2), packageName = "different.app"),
            rpcWire(3, "/correct", byteArrayOf(3)),
        )))
        runCurrent()
        assertEquals(listOf("/correct"), received.map { it.message.path })
        engine.close()
    }

    @Test
    fun `explicit MessageApi path registration is AppKey scoped reference counted and removable`() = runTest {
        val transport = FakeTransport(); val engine = engine { transport }
        val other = ApplicationRoute("com.google.android.gms", "mdm-digest")
        val first = engine.registerMessageRoutes(other, setOf("com.google.android.gms.mdm.ACTION_RING"))
        val second = engine.registerMessageRoutes(other, setOf("com.google.android.gms.mdm.ACTION_RING"))
        val received = mutableListOf<BackendEvent.MessageReceived>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { engine.events.collect { if (it is BackendEvent.MessageReceived) received += it } }
        engine.connect(PEER); runCurrent(); transport.enqueue(peerConnect()); runCurrent()
        fun inbound(id: Int, path: String, digest: String = other.packageDigest) = wire(WearMessages.encodeRpc(RpcMessage(
            requestId = id, packageName = other.packageName, packageDigest = digest, targetNodeId = LOCAL.nodeId,
            sourceNodeId = "watch-node", path = path, payload = byteArrayOf())))
        transport.enqueue(inbound(1, "com.google.android.gms.mdm.ACTION_RING")); runCurrent()
        transport.enqueue(inbound(2, "/not-registered")); transport.enqueue(inbound(3, "com.google.android.gms.mdm.ACTION_RING", "wrong-digest")); runCurrent()
        assertEquals(1, received.size); assertEquals(other, received.single().message.route)
        first.close(); first.close()
        transport.enqueue(inbound(4, "com.google.android.gms.mdm.ACTION_RING")); runCurrent(); assertEquals(2, received.size)
        second.close()
        transport.enqueue(inbound(5, "com.google.android.gms.mdm.ACTION_RING")); runCurrent(); assertEquals(2, received.size)
        engine.close()
    }

    @Test
    fun `feature MessageApi send selects its actual route and cannot migrate across physical sessions`() = runTest {
        val transport = FakeTransport(); val engine = engine { transport }
        engine.connect(PEER); runCurrent(); transport.enqueue(peerConnect()); runCurrent()
        val sessionId = engine.state.value.sessionId
        val mdm = ApplicationRoute("com.google.android.gms", "mdm-digest")
        engine.sendMessage(PEER, sessionId, "com.google.android.gms.mdm.RING_STARTED", byteArrayOf(), mdm)
        val sent = WearMessages.decodeRpcs(decodeWrites(transport).last()).single()
        assertEquals(mdm.packageName, sent.packageName); assertEquals(mdm.packageDigest, sent.packageDigest)
        assertEquals("com.google.android.gms.mdm.RING_STARTED", sent.path)
        engine.disconnect()
        assertFailsWith<NotConnectedException> { engine.sendMessage(PEER, sessionId, "old", byteArrayOf(), mdm) }
        engine.close()
    }

    @Test
    fun `invalid framing is terminal but EOF within a valid record is a reconnectable interruption`() = runTest {
        val malformed = FakeTransport()
        val engine = engine { malformed }
        engine.connect(PEER)
        runCurrent()
        malformed.enqueue(hex("ffffffff"))
        runCurrent()
        assertEquals(ConnectionPhase.FAILED, engine.state.value.phase)
        assertEquals(FailureKind.PROTOCOL, engine.state.value.failure?.kind)
        engine.close()

        val interrupted = FakeTransport()
        val resumable = engine { interrupted }
        resumable.connect(PEER)
        runCurrent()
        interrupted.enqueue(hex("00000003aa"))
        interrupted.endInput()
        runCurrent()
        assertEquals(ConnectionPhase.RETRYING, resumable.state.value.phase)
        assertEquals(FailureKind.TRANSPORT, resumable.state.value.failure?.kind)
        resumable.close()
    }

    @Test
    fun `identity-changing CONNECT and local oversize arguments cannot masquerade as success`() = runTest {
        val transport = FakeTransport()
        val engine = engine(config = testConfig(maxBusinessPayloadBytes = 4)) { transport }
        engine.connect(PEER)
        runCurrent()
        transport.enqueue(peerConnect())
        runCurrent()
        assertFailsWith<IllegalArgumentException> { engine.sendMessage("/too-large", ByteArray(5)) }
        assertEquals(ConnectionPhase.LINK_READY, engine.state.value.phase)
        assertFalse(transport.closed)
        transport.enqueue(peerConnect(nodeId = "different-node"))
        runCurrent()
        assertEquals(ConnectionPhase.FAILED, engine.state.value.phase)
        assertTrue(transport.closed)
        engine.close()
        engine.close()
        assertFailsWith<BackendClosedException> { engine.connect(PEER) }
    }

    private fun TestScope.engine(
        config: SessionConfig = testConfig(),
        factory: suspend (PeerAddress) -> DuplexTransport,
    ) = SessionEngine(backgroundScope, TransportFactory(factory), IdentityStore { LOCAL }, ROUTE, config, 37)

    private fun testConfig(
        maxReconnectAttempts: Int? = 2,
        fragmentBytes: Int = 8_192,
        maxPreConnectMessages: Int = 32,
        maxBusinessPayloadBytes: Int = 512 * 1_024,
    ) = SessionConfig(
        connectTimeoutMillis = 1_000,
        handshakeTimeoutMillis = 1_000,
        writeTimeoutMillis = 1_000,
        initialReconnectDelayMillis = 100,
        maxReconnectDelayMillis = 250,
        reconnectJitterRatio = 0.0,
        maxReconnectAttempts = maxReconnectAttempts,
        fragmentBytes = fragmentBytes,
        maxPreConnectMessages = maxPreConnectMessages,
        maxBusinessPayloadBytes = maxBusinessPayloadBytes,
    )

    private class FakeTransport : DuplexTransport {
        private val input = Channel<ByteArray>(Channel.UNLIMITED)
        val writes = mutableListOf<ByteArray>()
        var closed = false
            private set
        var writeDelayMillis = 0L
        private var concurrentWrites = 0
        var maxConcurrentWrites = 0
            private set

        fun enqueue(bytes: ByteArray) { check(input.trySend(bytes.copyOf()).isSuccess) }
        fun tryEnqueue(bytes: ByteArray): Boolean = input.trySend(bytes.copyOf()).isSuccess
        fun endInput() { input.close() }
        override suspend fun read(): ByteArray? = input.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) {
            if (closed) throw IOException("Transport closed")
            concurrentWrites++
            maxConcurrentWrites = maxOf(maxConcurrentWrites, concurrentWrites)
            try {
                if (writeDelayMillis > 0) delay(writeDelayMillis)
                if (closed) throw IOException("Transport closed")
                writes.add(bytes.copyOf())
            } finally { concurrentWrites-- }
        }
        override fun close() {
            if (closed) return
            closed = true
            input.close()
        }
    }

    private companion object {
        val PEER = PeerAddress("AA:BB:CC:DD:EE:01")
        val OTHER_PEER = PeerAddress("AA:BB:CC:DD:EE:02")
        val LOCAL = NodeIdentity("local-node", "local-network", "Test phone", 123)
        val ROUTE = ApplicationRoute("dev.ticbox.companion", "test-routing-digest")
        const val BATTERY_PATH = "/companion/sync_wear_battery"
        const val STEPS_PATH = "/health/cmd_new_wear_push_to_phone"

        fun peerConnect(nodeId: String = "watch-node", wireVersion: Int = 1, minWireVersion: Int = 0): ByteArray = wire(
            WearMessages.encodeConnect(ConnectMessage(
                nodeId, "Enduro", wireVersion = wireVersion, minWireVersion = minWireVersion,
            )),
        )

        fun rpcWire(
            id: Int,
            path: String,
            body: ByteArray,
            target: String = LOCAL.nodeId,
            packageName: String = ROUTE.packageName,
        ) = wire(WearMessages.encodeRpc(RpcMessage(
            requestId = id,
            packageName = packageName,
            packageDigest = ROUTE.packageDigest,
            targetNodeId = target,
            path = path,
            payload = body,
            sourceNodeId = "watch-node",
        )))

        fun wire(envelope: ByteArray): ByteArray = concat(
            MessagePieces.encode(envelope, queueId = if (envelope.size > 8_192) 42 else -1).map { GmsRecord.encode(it) },
        )

        fun decodeWrites(transport: FakeTransport): List<ByteArray> {
            val decoder = GmsRecordDecoder()
            val assembler = MessageAssembler()
            val messages = transport.writes.flatMap { decoder.push(it) }.mapNotNull { assembler.accept(it) }
            decoder.finish()
            assembler.finish()
            return messages
        }

        fun concat(arrays: List<ByteArray>): ByteArray {
            val result = ByteArray(arrays.sumOf { it.size })
            var offset = 0
            for (array in arrays) { array.copyInto(result, offset); offset += array.size }
            return result
        }

        fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
