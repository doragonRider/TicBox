package dev.ticbox.companion.platform.toolbox

import dev.ticbox.companion.api.AdbEndpoint
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** One-use, loopback-only byte relay. TLS and SPAKE2 remain end to end in Kadb. */
internal class AdbPairingRelay(private val target: AdbEndpoint) : AutoCloseable {
    private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val closed = AtomicBoolean()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    val port: Int get() = listener.localPort
    init {
        Thread({
            try {
                val client = own(listener.accept()); listener.close()
                val peer = own(Socket())
                peer.connect(InetSocketAddress(target.host, target.port), 15_000)
                Thread({ pump(client, peer) }, "toolbox-pair-request").apply { isDaemon = true; start() }
                pump(peer, client)
            } catch (_: Exception) { close() }
        }, "toolbox-pair-response").apply { isDaemon = true; start() }
    }
    private fun own(socket: Socket): Socket {
        sockets += socket
        if (closed.get()) { socket.close(); error("pairing_cancelled") }
        socket.tcpNoDelay = true
        return socket
    }
    private fun pump(source: Socket, destination: Socket) {
        try { source.getInputStream().copyTo(destination.getOutputStream()) }
        catch (_: Exception) { }
        finally { close() }
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching(listener::close); sockets.forEach { runCatching(it::close) }; sockets.clear()
        }
    }
}
