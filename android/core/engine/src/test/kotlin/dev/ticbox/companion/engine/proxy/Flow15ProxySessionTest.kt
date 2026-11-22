@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.ticbox.companion.engine.proxy

import dev.ticbox.companion.api.DiagnosticSink
import dev.ticbox.companion.api.DuplexTransport
import dev.ticbox.companion.api.NetworkProxyPhase
import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.api.ProtocolDiagnostic
import dev.ticbox.companion.protocol.Flow15Codec
import dev.ticbox.companion.protocol.Flow15Frame
import dev.ticbox.companion.protocol.Flow15Opcode as Op
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class Flow15ProxySessionTest {
    @Test fun `unknown peer version receives fixed ACK5 and DONE activates latest actual network`() = runTest {
        val fixture = Fixture()
        fixture.start(this)
        fixture.input(Flow15Frame.Handshake(Op.HANDSHAKE_START, -1, byteArrayOf(10)))
        runCurrent()
        assertContentEquals(hex("11000000050000"), fixture.transport.writes.single())
        assertEquals(NetworkProxyPhase.HANDSHAKING, fixture.session.state.value.phase)
        fixture.status.value = ProxyNetworkStatus(1, true)
        fixture.input(Flow15Frame.Handshake(Op.HANDSHAKE_DONE, 120))
        runCurrent()
        assertEquals(NetworkProxyPhase.ACTIVE, fixture.session.state.value.phase)
        assertEquals(120, fixture.session.state.value.peerHandshakeVersion)
        assertEquals(Flow15Frame.NetworkState(1, 1), fixture.frames.last())
        fixture.status.value = ProxyNetworkStatus.DISCONNECTED
        runCurrent()
        assertEquals(Flow15Frame.NetworkState(-1, 0), fixture.frames.last())
        fixture.close()
    }

    @Test fun `TCP sends ordered bytes returns reads and ACKs only accepted writes`() = runTest {
        val fixture = Fixture()
        fixture.start(this)
        fixture.input(Flow15Frame.TcpOpen(-1, 0x7f000001, 1234, 65_535))
        fixture.input(Flow15Frame.Data(Op.TCP_WRITE, -1, hex("010203")))
        fixture.input(Flow15Frame.Data(Op.TCP_WRITE, 7, hex("ff")))
        runCurrent()
        val socket = fixture.network.tcp.single()
        assertContentEquals(hex("010203"), socket.writes.single())
        assertEquals(listOf(-1), fixture.frames.filterIsInstance<Flow15Frame.Ack>().map { it.id })
        socket.incoming.send(hex("aabb"))
        runCurrent()
        assertContentEquals(hex("aabb"), fixture.frames.filterIsInstance<Flow15Frame.Data>().single().payload)
        socket.incoming.close()
        runCurrent()
        assertEquals(Flow15Frame.TcpClose(-1), fixture.frames.last())
        assertEquals(0, fixture.session.state.value.tcpConnections)
        assertTrue(socket.closed)
        fixture.close()
    }

    @Test fun `remote TCP close drains accepted writes and releases the reader without echo close`() = runTest {
        val fixture = Fixture()
        fixture.start(this)
        fixture.input(Flow15Frame.TcpOpen(1, 0, 0, 1))
        fixture.input(Flow15Frame.Data(Op.TCP_WRITE, 1, byteArrayOf(4)))
        fixture.input(Flow15Frame.TcpClose(1))
        runCurrent()
        assertContentEquals(byteArrayOf(4), fixture.network.tcp.single().writes.single())
        assertTrue(fixture.network.tcp.single().closed)
        assertTrue(fixture.frames.none { it is Flow15Frame.TcpClose })
        fixture.close()
    }

    @Test fun `UDP versions reuse connected channels preserve tuple metadata and return exact failures`() = runTest {
        val fixture = Fixture()
        fixture.start(this)
        fixture.input(Flow15Frame.Datagram(0x7f000001, 53, byteArrayOf(1)))
        fixture.input(Flow15Frame.Datagram(0x7f000001, 53, byteArrayOf(2)))
        fixture.input(Flow15Frame.DatagramV2(0xc0000201.toInt(), 321, 0x7f000001, 54, 0xabcd, byteArrayOf(3)))
        runCurrent()
        assertEquals(2, fixture.network.udp.size)
        assertEquals(2, fixture.network.udp[0].writes.size)
        fixture.network.udp[0].incoming.send(byteArrayOf())
        fixture.network.udp[1].incoming.send(byteArrayOf(5))
        runCurrent()
        assertContentEquals(byteArrayOf(), fixture.frames.filterIsInstance<Flow15Frame.Datagram>().single().payload)
        val reply = fixture.frames.filterIsInstance<Flow15Frame.DatagramV2>().single()
        assertEquals(321, reply.sourcePort); assertEquals(54, reply.destinationPort); assertEquals(0xabcd, reply.opaque)
        fixture.network.udpFailure = true
        fixture.input(Flow15Frame.Datagram(0, 0, hex("ff")))
        fixture.input(Flow15Frame.DatagramV2(1, 1, 2, 2, 3, hex("dd")))
        runCurrent()
        assertTrue(fixture.frames.filterIsInstance<Flow15Frame.Datagram>().last().failure)
        val failure = fixture.frames.filterIsInstance<Flow15Frame.DatagramV2>().last()
        assertEquals(null, failure.opaque)
        assertEquals(16, Flow15Codec.encode(failure).size)
        assertContentEquals(hex("dd"), failure.payload)
        fixture.close()
        assertTrue(fixture.network.udp.all { it.closed })
    }

    @Test fun `a session without UDP sockets does no periodic idle checking`() = runTest {
        val fixture = Fixture(monotonicNanos = { testScheduler.currentTime * 1_000_000 })
        val dispatcher = CountingDispatcher(StandardTestDispatcher(testScheduler))
        backgroundScope.launch(dispatcher) { fixture.session.run() }
        runCurrent()
        val idleDispatches = dispatcher.dispatches
        advanceTimeBy(180_000)
        runCurrent()
        assertEquals(idleDispatches, dispatcher.dispatches)
        fixture.input(Flow15Frame.Ping(9))
        runCurrent()
        assertEquals(Flow15Frame.Ping(9), fixture.frames.last())
        fixture.close()
    }

    @Test fun `UDP expiry waits for its deadline and the tuple can be opened again`() = runTest {
        val fixture = Fixture(monotonicNanos = { testScheduler.currentTime * 1_000_000 })
        fixture.start(this)
        fixture.input(Flow15Frame.Datagram(0, 53, byteArrayOf(1)))
        runCurrent()
        val socket = fixture.network.udp.single()
        val armedReads = fixture.clockReads
        advanceTimeBy(59_999)
        runCurrent()
        assertFalse(socket.closed)
        assertEquals(armedReads, fixture.clockReads)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(socket.closed)
        assertEquals(1, socket.closeCalls)
        assertEquals(0, fixture.session.state.value.udpConnections)
        assertEquals(1, fixture.events.count { it.event == "udp_idle_closed" })
        val emptyReads = fixture.clockReads
        advanceTimeBy(180_000)
        runCurrent()
        assertEquals(emptyReads, fixture.clockReads)
        fixture.input(Flow15Frame.Datagram(0, 53, byteArrayOf(2)))
        fixture.input(Flow15Frame.Ping(9))
        runCurrent()
        assertEquals(2, fixture.network.udp.size)
        assertContentEquals(byteArrayOf(2), fixture.network.udp.last().writes.single())
        assertEquals(Flow15Frame.Ping(9), fixture.frames.last())
        fixture.close()
        runCurrent()
        assertTrue(fixture.network.udp.all { it.closed })
    }

    @Test fun `UDP sends and replies both extend the idle lease beyond an already armed deadline`() = runTest {
        for (outbound in listOf(true, false)) {
            val fixture = Fixture(monotonicNanos = { testScheduler.currentTime * 1_000_000 })
            fixture.start(this)
            fixture.input(Flow15Frame.Datagram(0, 53, byteArrayOf(1)))
            runCurrent()
            val socket = fixture.network.udp.single()
            advanceTimeBy(50_000)
            if (outbound) fixture.input(Flow15Frame.Datagram(0, 53, byteArrayOf(2)))
            else socket.incoming.send(byteArrayOf(3))
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()
            assertFalse(socket.closed, "outbound=$outbound")
            advanceTimeBy(49_999)
            runCurrent()
            assertFalse(socket.closed, "outbound=$outbound")
            advanceTimeBy(1)
            runCurrent()
            assertTrue(socket.closed, "outbound=$outbound")
            assertEquals(0, fixture.session.state.value.udpConnections)
            fixture.close()
            runCurrent()
        }
    }

    @Test fun `UDP sockets expire by their own activity even when the first socket is refreshed`() = runTest {
        val fixture = Fixture(monotonicNanos = { testScheduler.currentTime * 1_000_000 })
        fixture.start(this)
        fixture.input(Flow15Frame.Datagram(0, 53, byteArrayOf(1)))
        runCurrent()
        advanceTimeBy(10_000)
        fixture.input(Flow15Frame.DatagramV2(1, 2, 0, 54, 3, byteArrayOf(2)))
        runCurrent()
        advanceTimeBy(10_000)
        fixture.input(Flow15Frame.Datagram(0, 53, byteArrayOf(3)))
        runCurrent()
        advanceTimeBy(40_000)
        runCurrent()
        assertTrue(fixture.network.udp.none { it.closed })
        advanceTimeBy(10_000)
        runCurrent()
        assertFalse(fixture.network.udp[0].closed)
        assertTrue(fixture.network.udp[1].closed)
        assertEquals(1, fixture.session.state.value.udpConnections)
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(fixture.network.udp.all { it.closed })
        assertEquals(0, fixture.session.state.value.udpConnections)
        fixture.close()
    }

    @Test fun `a failed UDP reader cancels idle scheduling when the final socket is removed`() = runTest {
        val fixture = Fixture(monotonicNanos = { testScheduler.currentTime * 1_000_000 })
        val dispatcher = CountingDispatcher(StandardTestDispatcher(testScheduler))
        backgroundScope.launch(dispatcher) { fixture.session.run() }
        fixture.input(Flow15Frame.Datagram(0, 53, byteArrayOf(1)))
        runCurrent()
        fixture.network.udp.single().incoming.close(IOException("fixture receive failed"))
        runCurrent()
        assertEquals(0, fixture.session.state.value.udpConnections)
        val idleDispatches = dispatcher.dispatches
        advanceTimeBy(180_000)
        runCurrent()
        assertEquals(idleDispatches, dispatcher.dispatches)
        assertTrue(fixture.events.none { it.event == "udp_idle_closed" })
        fixture.close()
    }

    @Test fun `closing a session cancels both an empty UDP wait and an armed idle deadline`() = runTest {
        for (hasUdp in listOf(false, true)) {
            val fixture = Fixture(monotonicNanos = { testScheduler.currentTime * 1_000_000 })
            val dispatcher = CountingDispatcher(StandardTestDispatcher(testScheduler))
            val run = backgroundScope.launch(dispatcher) { fixture.session.run() }
            if (hasUdp) fixture.input(Flow15Frame.Datagram(0, 53, byteArrayOf(1)))
            runCurrent()
            fixture.close()
            runCurrent()
            assertTrue(run.isCompleted)
            assertTrue(fixture.transport.closed)
            assertEquals(0, fixture.session.state.value.udpConnections)
            assertTrue(fixture.network.udp.all { it.closed && it.closeCalls == 1 })
            val closedDispatches = dispatcher.dispatches
            advanceTimeBy(180_000)
            runCurrent()
            assertEquals(closedDispatches, dispatcher.dispatches)
            assertTrue(fixture.events.none { it.event == "udp_idle_closed" })
        }
    }

    @Test fun `a sub-millisecond UDP lease remainder rounds up without spinning or expiring early`() = runTest {
        var nanos = 0L
        val fixture = Fixture(Flow15ProxyPolicy(udpIdleTimeoutMillis = 1), monotonicNanos = { nanos })
        fixture.start(this)
        fixture.input(Flow15Frame.Datagram(0, 53, byteArrayOf(1)))
        runCurrent()
        advanceTimeBy(1)
        nanos = 999_999
        runCurrent()
        assertFalse(fixture.network.udp.single().closed)
        val waitingReads = fixture.clockReads
        runCurrent()
        assertEquals(waitingReads, fixture.clockReads)
        advanceTimeBy(1)
        nanos = 1_000_000
        runCurrent()
        assertTrue(fixture.network.udp.single().closed)
        fixture.close()
    }

    @Test fun `pull probes send zeroes in 65535 chunks and push probes consume bytes without a fabricated ACK`() = runTest {
        val fixture = Fixture()
        fixture.start(this)
        fixture.input(Flow15Frame.Request(Op.PULL_REQUEST, 4, 65_536))
        fixture.input(Flow15Frame.Request(Op.PULL_REQUEST, 5, -1))
        fixture.input(Flow15Frame.Request(Op.PUSH_REQUEST, 8, 3))
        fixture.input(Flow15Frame.Data(Op.PUSH_DATA, 8, hex("0102")))
        fixture.input(Flow15Frame.Data(Op.PUSH_DATA, 8, hex("03")))
        runCurrent()
        val blocks = fixture.frames.filterIsInstance<Flow15Frame.Data>()
        assertEquals(listOf(65_535, 1), blocks.map { it.payload.size })
        assertTrue(blocks.all { it.opcode == Op.PULL_DATA && it.id == 4 && it.payload.all { byte -> byte == 0.toByte() } })
        assertTrue(fixture.events.any { it.event == "push_completed" && it.details["receivedBytes"] == "3" })
        assertTrue(fixture.events.any { it.event == "pull_negative_length" })
        fixture.close()
    }

    @Test fun `known spurious operations are consumed but unknown boundaries stop and clean the stream`() = runTest {
        val fixture = Fixture()
        val completion = async { runCatching { fixture.session.run() } }
        fixture.input(Flow15Frame.TcpOpen(1, 0, 1, 0))
        fixture.input(Flow15Frame.Ack(9))
        fixture.input(Flow15Frame.Handshake(Op.HANDSHAKE_ACK, 5))
        fixture.input(Flow15Frame.Data(Op.PULL_DATA, 1, byteArrayOf(1)))
        fixture.input(Flow15Frame.Ping(Int.MIN_VALUE))
        runCurrent()
        fixture.transport.incoming.send(hex("7faa0500000002"))
        runCurrent()
        assertIs<Flow15ProtocolException>(completion.await().exceptionOrNull())
        assertEquals(listOf(5), fixture.frames.map { it.opcode })
        assertTrue(fixture.events.count { it.event == "incoming_operation_unimplemented_or_spurious" } == 3)
        assertTrue(fixture.transport.closed)
        assertTrue(fixture.network.tcp.single().closed)
    }

    @Test fun `local connection and probe limits diagnose refusal while unrelated PING remains usable`() = runTest {
        val fixture = Fixture(policy = Flow15ProxyPolicy(maxTcpConnections = 1, maxPullResponseBytes = 1))
        fixture.start(this)
        fixture.input(Flow15Frame.TcpOpen(1, 0, 1, 0))
        fixture.input(Flow15Frame.TcpOpen(2, 0, 2, 0))
        fixture.input(Flow15Frame.Request(Op.PULL_REQUEST, 3, 2))
        fixture.input(Flow15Frame.Ping(-1))
        runCurrent()
        assertEquals(1, fixture.network.tcp.size)
        assertTrue(Flow15Frame.TcpClose(2) in fixture.frames)
        assertTrue(Flow15Frame.Ping(-1) in fixture.frames)
        assertTrue(fixture.events.any { it.event == "pull_resource_limit" })
        assertTrue(fixture.frames.none { it is Flow15Frame.Ack })
        fixture.close()
    }

    @Test fun `cancellation closes active network sockets and pending acquisitions`() = runTest {
        val fixture = Fixture()
        val connecting = CompletableDeferred<Unit>()
        fixture.network.waitForConnect = connecting
        val run = fixture.start(this)
        fixture.input(Flow15Frame.TcpOpen(1, 0, 1, 0))
        runCurrent()
        run.cancel()
        runCurrent()
        assertTrue(fixture.transport.closed)
        assertTrue(fixture.network.acquisitionCancelled)
        assertEquals(0, fixture.session.state.value.tcpConnections)
        fixture.close()
    }

    @Test fun `concurrent output sources never overlap a transport write`() = runTest {
        val fixture = Fixture()
        fixture.transport.writeDelay = 10
        fixture.start(this)
        fixture.input(Flow15Frame.TcpOpen(1, 0, 1, 0))
        fixture.input(Flow15Frame.Request(Op.PULL_REQUEST, 2, 131_071))
        fixture.input(Flow15Frame.Ping(3))
        runCurrent()
        fixture.network.tcp.single().incoming.send(byteArrayOf(4))
        advanceTimeBy(100)
        runCurrent()
        assertFalse(fixture.transport.overlap)
        assertEquals(5, fixture.frames.size)
        fixture.close()
    }

    @Test fun `full TCP queue aborts only that connection and never ACKs its rejected write`() = runTest {
        val fixture = Fixture(Flow15ProxyPolicy(maxQueuedTcpFrames = 1))
        fixture.network.waitForConnect = CompletableDeferred()
        fixture.start(this)
        fixture.input(Flow15Frame.TcpOpen(1, 0, 1, 0))
        runCurrent()
        fixture.input(Flow15Frame.Data(Op.TCP_WRITE, 1, byteArrayOf(1)))
        fixture.input(Flow15Frame.Data(Op.TCP_WRITE, 1, byteArrayOf(2)))
        fixture.input(Flow15Frame.Ping(3))
        runCurrent()
        assertEquals(1, fixture.frames.filterIsInstance<Flow15Frame.Ack>().size)
        assertTrue(Flow15Frame.TcpClose(1) in fixture.frames)
        assertTrue(Flow15Frame.Ping(3) in fixture.frames)
        assertTrue(fixture.network.acquisitionCancelled)
        assertEquals(0, fixture.session.state.value.tcpConnections)
        fixture.close()
    }

    @Test fun `replacing a TCP socket does not cancel its already-running shared transport frame`() = runTest {
        val fixture = Fixture()
        fixture.start(this)
        fixture.input(Flow15Frame.TcpOpen(1, 0, 1, 0))
        runCurrent()
        fixture.transport.writeDelay = 10
        fixture.network.tcp.single().incoming.send(byteArrayOf(7))
        runCurrent()
        assertTrue(fixture.transport.writing)
        fixture.input(Flow15Frame.TcpOpen(1, 0, 2, 0))
        fixture.input(Flow15Frame.Ping(8))
        runCurrent()
        advanceTimeBy(25)
        runCurrent()
        assertContentEquals(byteArrayOf(7), fixture.frames.filterIsInstance<Flow15Frame.Data>().single().payload)
        assertTrue(Flow15Frame.Ping(8) in fixture.frames)
        assertFalse(fixture.transport.closed)
        fixture.close()
    }

    @Test fun `connect timeout releases acquisition and other operations continue`() = runTest {
        val fixture = Fixture(Flow15ProxyPolicy(connectTimeoutMillis = 100))
        fixture.network.waitForConnect = CompletableDeferred()
        fixture.start(this)
        fixture.input(Flow15Frame.TcpOpen(1, 0, 1, 0))
        runCurrent()
        advanceTimeBy(101)
        runCurrent()
        assertTrue(Flow15Frame.TcpClose(1) in fixture.frames)
        assertTrue(fixture.network.acquisitionCancelled)
        assertEquals(0, fixture.session.state.value.tcpConnections)
        fixture.input(Flow15Frame.Ping(9))
        runCurrent()
        assertEquals(Flow15Frame.Ping(9), fixture.frames.last())
        fixture.close()
    }

    @Test fun `transport write failure releases all active sockets and reports the failing stage`() = runTest {
        val fixture = Fixture()
        val completion = async { runCatching { fixture.session.run() } }
        fixture.input(Flow15Frame.TcpOpen(1, 0, 1, 0))
        runCurrent()
        fixture.transport.failWrites = true
        fixture.input(Flow15Frame.Ping(9))
        runCurrent()
        assertIs<IOException>(completion.await().exceptionOrNull())
        assertTrue(fixture.transport.closed)
        assertTrue(fixture.network.tcp.single().closed)
        assertTrue(fixture.events.any { it.event == "transport_write_failed" })
    }

    @Test fun `transport write timeout cancels the stream and releases all socket workers`() = runTest {
        val fixture = Fixture(Flow15ProxyPolicy(writeTimeoutMillis = 25))
        fixture.transport.writeDelay = 1000
        val completion = async { runCatching { fixture.session.run() } }
        fixture.input(Flow15Frame.TcpOpen(1, 0, 1, 0))
        runCurrent()
        fixture.input(Flow15Frame.Ping(9))
        runCurrent()
        advanceTimeBy(26)
        runCurrent()
        assertIs<kotlinx.coroutines.TimeoutCancellationException>(completion.await().exceptionOrNull())
        assertTrue(fixture.transport.closed)
        assertTrue(fixture.network.tcp.single().closed)
        assertTrue(fixture.events.any { it.event == "transport_write_timeout" })
    }

    @Test fun `a truncated socket EOF is diagnosed and closes the stream`() = runTest {
        val fixture = Fixture()
        val completion = async { runCatching { fixture.session.run() } }
        fixture.transport.incoming.send(hex("03000000010002ff"))
        fixture.transport.incoming.close()
        runCurrent()
        assertIs<Flow15ProtocolException>(completion.await().exceptionOrNull())
        assertTrue(fixture.events.any { it.event == "truncated_stream" })
        assertTrue(fixture.transport.closed)
    }

    @Test fun `a TCP socket returned as acquisition is cancelled remains owned and closes`() = runTest {
        val fixture = Fixture()
        fixture.network.cancelAfterTcpAcquire = true
        fixture.start(this)
        fixture.input(Flow15Frame.TcpOpen(1, 0, 1, 0))
        runCurrent()
        assertTrue(fixture.network.tcp.single().closed)
        assertEquals(0, fixture.session.state.value.tcpConnections)
        fixture.close()
    }

    @Test fun `a UDP socket returned as acquisition is cancelled closes before escaping the session`() = runTest {
        val fixture = Fixture()
        fixture.network.cancelAfterUdpAcquire = true
        val completion = async { runCatching { fixture.session.run() } }
        fixture.input(Flow15Frame.Datagram(0, 1, byteArrayOf(1)))
        runCurrent()
        assertIs<kotlinx.coroutines.CancellationException>(completion.await().exceptionOrNull())
        assertTrue(fixture.network.udp.single().closed)
        assertTrue(fixture.transport.closed)
    }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    private class CountingDispatcher(private val delegate: TestDispatcher) : CoroutineDispatcher(), Delay by delegate {
        var dispatches = 0
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            delegate.dispatch(context, block)
        }
    }

    private class Fixture(policy: Flow15ProxyPolicy = Flow15ProxyPolicy(), monotonicNanos: () -> Long = System::nanoTime) {
        val transport = TestTransport()
        val network = TestNetwork()
        val events = mutableListOf<ProtocolDiagnostic>()
        val status = MutableStateFlow(ProxyNetworkStatus.DISCONNECTED)
        var clockReads = 0
        val session = Flow15ProxySession(PEER, 1, transport, network, ProxyNetworkMonitor { status }, DiagnosticSink(events::add), policy,
            monotonicNanos = { clockReads++; monotonicNanos() })
        val frames get() = transport.writes.map(Flow15Codec::decode)
        fun start(scope: TestScope): Job = scope.backgroundScope.launch { session.run() }
        suspend fun input(frame: Flow15Frame) { transport.incoming.send(Flow15Codec.encode(frame)) }
        fun close() = session.close()
    }

    private class TestTransport : DuplexTransport {
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        val writes = mutableListOf<ByteArray>()
        var closed = false
        var writeDelay = 0L
        var failWrites = false
        var writing = false
        var overlap = false
        override suspend fun read(): ByteArray? = incoming.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) {
            if (failWrites) throw IOException("fixture transport failed")
            if (writing) overlap = true
            writing = true
            try { if (writeDelay > 0) delay(writeDelay); writes.add(bytes.copyOf()) }
            finally { writing = false }
        }
        override fun close() { closed = true; incoming.close() }
    }
    private class TestNetwork : ProxyNetwork {
        val tcp = mutableListOf<TestTcp>()
        val udp = mutableListOf<TestUdp>()
        var udpFailure = false
        var waitForConnect: CompletableDeferred<Unit>? = null
        var acquisitionCancelled = false
        var cancelAfterTcpAcquire = false
        var cancelAfterUdpAcquire = false
        override suspend fun openTcp(destination: ProxyEndpoint): ProxyTcpSocket {
            try { waitForConnect?.await() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { acquisitionCancelled = true; throw cancelled }
            val socket = TestTcp().also(tcp::add)
            if (cancelAfterTcpAcquire) kotlinx.coroutines.currentCoroutineContext()[Job]?.cancel()
            return socket
        }
        override suspend fun openUdp(destination: ProxyEndpoint): ProxyDatagramSocket {
            if (udpFailure) throw IOException("fixture failure")
            val socket = TestUdp().also(udp::add)
            if (cancelAfterUdpAcquire) kotlinx.coroutines.currentCoroutineContext()[Job]?.cancel()
            return socket
        }
    }
    private class TestTcp : ProxyTcpSocket {
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        val writes = mutableListOf<ByteArray>()
        var closed = false
        override suspend fun read(maxBytes: Int): ByteArray? = incoming.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) { writes.add(bytes.copyOf()) }
        override fun close() { closed = true; incoming.close() }
    }
    private class TestUdp : ProxyDatagramSocket {
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        val writes = mutableListOf<ByteArray>()
        var closed = false
        var closeCalls = 0
        override suspend fun receive(maxBytes: Int): ByteArray = incoming.receive()
        override suspend fun send(bytes: ByteArray) { writes.add(bytes.copyOf()) }
        override fun close() { closeCalls++; closed = true; incoming.close() }
    }
    companion object {
        val PEER = PeerAddress("AA:BB:CC:DD:EE:FF")
        fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
