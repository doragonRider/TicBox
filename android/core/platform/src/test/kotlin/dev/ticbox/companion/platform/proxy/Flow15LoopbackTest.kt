package dev.ticbox.companion.platform.proxy

import dev.ticbox.companion.api.DuplexTransport
import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.engine.proxy.Flow15ProxySession
import dev.ticbox.companion.engine.proxy.ProxyEndpoint
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Local process fixtures only; no adapter, target APK, watch, DNS or external server is used. */
class Flow15LoopbackTest {
    @Test fun `actual TCP proxy completes handshake and relays bidirectional bytes until EOF`() = runBlocking {
        withTimeout(10_000) {
            ServerSocket(0, 1, LOOPBACK).use { fixture ->
                val fixtureJob = async(Dispatchers.IO) {
                    fixture.accept().use { socket ->
                        socket.soTimeout = 5_000
                        assertContentEquals("ping".toByteArray(), socket.getInputStream().readNBytes(4))
                        socket.getOutputStream().write("pong".toByteArray())
                        socket.getOutputStream().flush()
                    }
                }
                val transport = Transport()
                val session = Flow15ProxySession(PEER, 1, transport, JavaNetProxyNetwork())
                val proxyJob = launch { session.run() }
                try {
                    transport.input.send(hex("100000007b0000"))
                    assertContentEquals(hex("11000000050000"), transport.output.receive())
                    transport.input.send(hex("120000007b0000"))
                    assertContentEquals(hex("0bffffffff00"), transport.awaitOpcode(11))
                    transport.input.send(ByteBuffer.allocate(13).put(1).putInt(7).putInt(0x7f000001)
                        .putShort(fixture.localPort.toShort()).putShort(0xabcd.toShort()).array())
                    transport.input.send(hex("0300000007000470696e67"))
                    assertContentEquals(hex("03000000070004706f6e67"), transport.awaitOpcode(3))
                    assertContentEquals(hex("0200000007"), transport.awaitOpcode(2))
                    fixtureJob.await()
                } finally { session.close(); proxyJob.cancelAndJoin() }
                assertTrue(transport.closed.get())
            }
        }
    }

    @Test fun `actual UDP v1 and v2 echo retains watch source metadata and uses an ephemeral phone socket`() = runBlocking {
        withTimeout(10_000) {
            DatagramSocket(InetSocketAddress(LOOPBACK, 0)).use { fixture ->
                fixture.soTimeout = 5_000
                val fixtureJob = async(Dispatchers.IO) {
                    repeat(2) {
                        val datagram = DatagramPacket(ByteArray(64), 64)
                        fixture.receive(datagram)
                        assertEquals(1, datagram.length)
                        assertEquals(LOOPBACK, datagram.address)
                        val payload = byteArrayOf((datagram.data[0] + 1).toByte())
                        fixture.send(DatagramPacket(payload, payload.size, datagram.socketAddress))
                    }
                }
                val transport = Transport()
                val session = Flow15ProxySession(PEER, 2, transport, JavaNetProxyNetwork())
                val proxyJob = launch { session.run() }
                try {
                    val first = ByteBuffer.allocate(10).put(4).putInt(0x7f000001).putShort(fixture.localPort.toShort())
                        .putShort(1).put(8).array()
                    transport.input.send(first)
                    assertContentEquals(first.copyOf().also { it[it.lastIndex] = 9 }, transport.awaitOpcode(4))
                    val second = ByteBuffer.allocate(18).put(14).putInt(0xc0000201.toInt()).putShort(1234)
                        .putInt(0x7f000001).putShort(fixture.localPort.toShort()).putShort(0xffee.toShort()).putShort(1).put(9).array()
                    transport.input.send(second)
                    assertContentEquals(second.copyOf().also { it[it.lastIndex] = 10 }, transport.awaitOpcode(14))
                    fixtureJob.await()
                } finally { session.close(); proxyJob.cancelAndJoin() }
            }
        }
    }

    @Test fun `real oversized UDP send returns stock v1 and 15-byte v2 failure layouts`() = runBlocking {
        withTimeout(10_000) {
            val transport = Transport()
            val session = Flow15ProxySession(PEER, 3, transport, JavaNetProxyNetwork())
            val proxyJob = launch { session.run() }
            try {
                // A 65535-byte Flow15 payload is representable but exceeds the IPv4 UDP data limit.
                val payload = ByteArray(65_535) { 0x5a }
                val v1 = ByteBuffer.allocate(9 + payload.size).put(4).putInt(0x7f000001).putShort(9)
                    .putShort(payload.size.toShort()).put(payload).array()
                transport.input.send(v1)
                assertContentEquals(v1.copyOf().also { it[0] = 7 }, transport.awaitOpcode(7))
                val v2 = ByteBuffer.allocate(17 + payload.size).put(14).putInt(0xc0000201.toInt()).putShort(1)
                    .putInt(0x7f000001).putShort(9).putShort(0x1234).putShort(payload.size.toShort()).put(payload).array()
                transport.input.send(v2)
                val failed = transport.awaitOpcode(15)
                assertEquals(15 + payload.size, failed.size)
                assertContentEquals(v2.copyOfRange(1, 13), failed.copyOfRange(1, 13))
                assertContentEquals(payload, failed.copyOfRange(15, failed.size))
            } finally { session.close(); proxyJob.cancelAndJoin() }
        }
    }

    @Test fun `cancelling a real blocking TCP read closes the socket and unblocks both endpoints`() = runBlocking {
        withTimeout(10_000) {
            ServerSocket(0, 1, LOOPBACK).use { fixture ->
                val network = JavaNetProxyNetwork()
                val client = network.openTcp(ProxyEndpoint(0x7f000001, fixture.localPort))
                val accepted = withContext(Dispatchers.IO) { fixture.accept() }
                accepted.use { remote ->
                    remote.soTimeout = 5_000
                    val pending = async(start = CoroutineStart.UNDISPATCHED) { client.read(4_096) }
                    try {
                        // The cancellable I/O bridge is entered before cancellation; its close,
                        // including cancellation racing worker dispatch, must unblock the peer.
                        pending.cancelAndJoin()
                        assertEquals(-1, withContext(Dispatchers.IO) { remote.getInputStream().read() })
                    } finally { client.close() }
                }
            }
        }
    }

    private class Transport : DuplexTransport {
        val input = Channel<ByteArray>(64)
        val output = Channel<ByteArray>(64)
        val closed = AtomicBoolean()
        override suspend fun read(): ByteArray? = input.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) { output.send(bytes.copyOf()) }
        override fun close() { closed.set(true); input.close(); output.close() }
        suspend fun awaitOpcode(opcode: Int): ByteArray {
            while (true) { val bytes = output.receive(); if ((bytes[0].toInt() and 255) == opcode) return bytes }
        }
    }
    companion object {
        val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val PEER = PeerAddress("AA:BB:CC:DD:EE:FF")
        fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
