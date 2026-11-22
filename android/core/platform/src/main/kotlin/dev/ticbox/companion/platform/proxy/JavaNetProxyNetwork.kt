package dev.ticbox.companion.platform.proxy

import dev.ticbox.companion.engine.proxy.ProxyDatagramSocket
import dev.ticbox.companion.engine.proxy.ProxyEndpoint
import dev.ticbox.companion.engine.proxy.ProxyNetwork
import dev.ticbox.companion.engine.proxy.ProxyTcpSocket
import dev.ticbox.companion.platform.transport.cancellableIo
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Real connected sockets using the phone's default network. Does not resolve DNS names. */
class JavaNetProxyNetwork(
    // An elastic IO view reserves room for writes while many connected sockets block in reads.
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(144),
    private val connectTimeoutMillis: Int = 15_000,
) : ProxyNetwork {
    init { require(connectTimeoutMillis > 0) }

    override suspend fun openTcp(destination: ProxyEndpoint): ProxyTcpSocket {
        val socket = Socket()
        return cancellableIo(dispatcher, onCancel = { socket.close() }, onDiscard = ProxyTcpSocket::close) {
            try {
                socket.connect(destination.inetAddress(), connectTimeoutMillis)
                Tcp(socket, dispatcher)
            } catch (error: Throwable) { runCatching { socket.close() }; throw error }
        }
    }

    override suspend fun openUdp(destination: ProxyEndpoint): ProxyDatagramSocket {
        val socket = DatagramSocket(null)
        return cancellableIo(dispatcher, onCancel = { socket.close() }, onDiscard = ProxyDatagramSocket::close) {
            try {
                // The watch's v2 source is correlation metadata; stock also chooses an ephemeral
                // phone socket and connects only to the destination. Never bind the watch address.
                socket.connect(destination.inetAddress())
                Udp(socket, dispatcher)
            } catch (error: Throwable) { socket.close(); throw error }
        }
    }

    private class Tcp(private val socket: Socket, private val dispatcher: CoroutineDispatcher) : ProxyTcpSocket {
        private val closed = AtomicBoolean()
        private val reader = AtomicBoolean()
        private val writer = Mutex()
        override suspend fun read(maxBytes: Int): ByteArray? {
            require(maxBytes in 1..65_535)
            check(reader.compareAndSet(false, true)) { "Only one TCP reader is allowed" }
            return try { cancellableIo(dispatcher, ::close) {
                ensureOpen()
                val bytes = ByteArray(maxBytes)
                when (val count = socket.getInputStream().read(bytes)) {
                    -1 -> null
                    0 -> throw IOException("Empty socket read")
                    else -> bytes.copyOf(count)
                }
            } } finally { reader.set(false) }
        }
        override suspend fun write(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            val owned = bytes.copyOf()
            writer.withLock { cancellableIo(dispatcher, ::close) {
                ensureOpen()
                socket.getOutputStream().write(owned)
                socket.getOutputStream().flush()
            } }
        }
        private fun ensureOpen() { if (closed.get()) throw IOException("TCP socket closed") }
        override fun close() { if (closed.compareAndSet(false, true)) runCatching { socket.close() } }
    }

    private class Udp(private val socket: DatagramSocket, private val dispatcher: CoroutineDispatcher) : ProxyDatagramSocket {
        private val closed = AtomicBoolean()
        private val reader = AtomicBoolean()
        private val writer = Mutex()
        override suspend fun receive(maxBytes: Int): ByteArray {
            require(maxBytes in 1..65_535)
            check(reader.compareAndSet(false, true)) { "Only one UDP reader is allowed" }
            return try { cancellableIo(dispatcher, ::close) {
                ensureOpen()
                val packet = DatagramPacket(ByteArray(maxBytes), maxBytes)
                socket.receive(packet)
                packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
            } } finally { reader.set(false) }
        }
        override suspend fun send(bytes: ByteArray) {
            require(bytes.size <= 65_535)
            val owned = bytes.copyOf()
            writer.withLock { cancellableIo(dispatcher, ::close) {
                ensureOpen()
                socket.send(DatagramPacket(owned, owned.size))
            } }
        }
        private fun ensureOpen() { if (closed.get()) throw IOException("UDP socket closed") }
        override fun close() { if (closed.compareAndSet(false, true)) socket.close() }
    }
}

private fun ProxyEndpoint.inetAddress(): InetSocketAddress =
    InetSocketAddress(InetAddress.getByAddress(addressBytes()), port)
