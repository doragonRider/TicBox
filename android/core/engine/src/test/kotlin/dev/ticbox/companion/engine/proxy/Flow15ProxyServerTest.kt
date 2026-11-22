@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.ticbox.companion.engine.proxy

import dev.ticbox.companion.api.DiagnosticSink
import dev.ticbox.companion.api.DuplexTransport
import dev.ticbox.companion.api.FailureKind
import dev.ticbox.companion.api.NetworkProxyPhase
import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.api.ProtocolDiagnostic
import dev.ticbox.companion.protocol.Flow15Codec
import dev.ticbox.companion.protocol.Flow15Frame
import dev.ticbox.companion.protocol.Flow15Opcode
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class Flow15ProxyServerTest {
    @Test fun `only selected bonded peer is admitted and changing selection closes the old generation`() = runTest {
        val listeners = mutableListOf<Listener>()
        val events = mutableListOf<ProtocolDiagnostic>()
        val server = Flow15ProxyServer(backgroundScope,
            ProxyListenerFactory { Listener().also(listeners::add) }, NoNetwork, diagnostics = DiagnosticSink(events::add))
        server.start(PEER)
        runCurrent()
        val rejectedPeer = Transport()
        val unbonded = Transport()
        listeners[0].incoming.send(AcceptedProxyTransport(OTHER, true, rejectedPeer))
        listeners[0].incoming.send(AcceptedProxyTransport(PEER, false, unbonded))
        val accepted = Transport()
        listeners[0].incoming.send(AcceptedProxyTransport(PEER, true, accepted))
        runCurrent()
        assertTrue(rejectedPeer.closed); assertTrue(unbonded.closed); assertFalse(accepted.closed)
        assertEquals(2, events.count { it.event == "peer_rejected" })
        val firstId = server.state.value.connectionId
        server.start(OTHER)
        runCurrent()
        assertTrue(accepted.closed); assertTrue(listeners[0].closed)
        assertEquals(OTHER, server.state.value.peer)
        assertEquals(NetworkProxyPhase.LISTENING, server.state.value.phase)
        val next = Transport()
        listeners[1].incoming.send(AcceptedProxyTransport(OTHER, true, next))
        runCurrent()
        assertNotEquals(firstId, server.state.value.connectionId)
        server.stop()
        assertTrue(next.closed); assertTrue(listeners[1].closed)
        assertEquals(NetworkProxyPhase.STOPPED, server.state.value.phase)
        server.close()
    }

    @Test fun `same-peer reconnect replaces the proxy stream and malformed stream leaves listener usable`() = runTest {
        val listener = Listener()
        val server = Flow15ProxyServer(backgroundScope, ProxyListenerFactory { listener }, NoNetwork)
        server.start(PEER)
        runCurrent()
        val first = Transport()
        listener.incoming.send(AcceptedProxyTransport(PEER, true, first))
        runCurrent()
        val firstId = server.state.value.connectionId
        val second = Transport()
        listener.incoming.send(AcceptedProxyTransport(PEER, true, second))
        runCurrent()
        assertTrue(first.closed)
        assertNotEquals(firstId, server.state.value.connectionId)
        second.incoming.send(byteArrayOf(127, 1, 5))
        runCurrent()
        assertTrue(second.closed)
        assertFalse(listener.closed)
        assertEquals(NetworkProxyPhase.LISTENING, server.state.value.phase)
        assertEquals(FailureKind.PROTOCOL, server.state.value.failure?.kind)
        val third = Transport()
        listener.incoming.send(AcceptedProxyTransport(PEER, true, third))
        third.incoming.send(Flow15Codec.encode(Flow15Frame.Handshake(Flow15Opcode.HANDSHAKE_START, 500)))
        third.incoming.send(Flow15Codec.encode(Flow15Frame.Handshake(Flow15Opcode.HANDSHAKE_DONE, 500)))
        runCurrent()
        assertEquals(NetworkProxyPhase.ACTIVE, server.state.value.phase)
        server.close()
        assertTrue(third.closed); assertTrue(listener.closed)
    }

    @Test fun `listener permission failure is visible and explicit restart can recover`() = runTest {
        var permitted = false
        val listener = Listener()
        val server = Flow15ProxyServer(backgroundScope, ProxyListenerFactory {
            if (!permitted) throw SecurityException("fixture permission")
            listener
        }, NoNetwork)
        server.start(PEER)
        runCurrent()
        assertEquals(NetworkProxyPhase.FAILED, server.state.value.phase)
        assertEquals(FailureKind.PERMISSION_REQUIRED, server.state.value.failure?.kind)
        permitted = true
        server.start(PEER)
        runCurrent()
        assertEquals(NetworkProxyPhase.LISTENING, server.state.value.phase)
        server.close()
    }

    private class Listener : ProxyListener {
        val incoming = Channel<AcceptedProxyTransport>(Channel.UNLIMITED)
        var closed = false
        override suspend fun accept(): AcceptedProxyTransport = incoming.receive()
        override fun close() { closed = true; incoming.close() }
    }
    private class Transport : DuplexTransport {
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        var closed = false
        override suspend fun read(): ByteArray? = incoming.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) = Unit
        override fun close() { closed = true; incoming.close() }
    }
    private object NoNetwork : ProxyNetwork {
        override suspend fun openTcp(destination: ProxyEndpoint): ProxyTcpSocket = throw IOException("unused fixture")
        override suspend fun openUdp(destination: ProxyEndpoint): ProxyDatagramSocket = throw IOException("unused fixture")
    }
    companion object {
        val PEER = PeerAddress("AA:BB:CC:DD:EE:FF")
        val OTHER = PeerAddress("11:22:33:44:55:66")
    }
}
