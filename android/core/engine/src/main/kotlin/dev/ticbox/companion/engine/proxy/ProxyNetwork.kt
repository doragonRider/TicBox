package dev.ticbox.companion.engine.proxy

import dev.ticbox.companion.api.DuplexTransport
import dev.ticbox.companion.api.PeerAddress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

data class ProxyEndpoint(val addressBits: Int, val port: Int) {
    init { require(port in 0..65_535) }
    fun addressBytes(): ByteArray = byteArrayOf(
        (addressBits ushr 24).toByte(), (addressBits ushr 16).toByte(),
        (addressBits ushr 8).toByte(), addressBits.toByte(),
    )
}

/** All acquisition methods own and close a result until it has been delivered, including cancellation. */
interface ProxyNetwork {
    suspend fun openTcp(destination: ProxyEndpoint): ProxyTcpSocket
    suspend fun openUdp(destination: ProxyEndpoint): ProxyDatagramSocket
}

interface ProxyTcpSocket : AutoCloseable {
    /** At most maxBytes nonempty bytes, or null at EOF; one reader and one writer are allowed. */
    suspend fun read(maxBytes: Int): ByteArray?
    suspend fun write(bytes: ByteArray)
    /** Synchronous, idempotent, and must unblock all pending I/O. */
    override fun close()
}

/** Connected datagram socket: accepts replies from the selected destination only. */
interface ProxyDatagramSocket : AutoCloseable {
    /** Exactly one datagram; a zero-byte datagram is valid. */
    suspend fun receive(maxBytes: Int): ByteArray
    suspend fun send(bytes: ByteArray)
    override fun close()
}

data class ProxyNetworkStatus(val networkType: Int, val isMetered: Boolean) {
    companion object { val DISCONNECTED = ProxyNetworkStatus(-1, false) }
}

fun interface ProxyNetworkMonitor {
    /** Cold subscription; must release callback resources on collector cancellation. */
    fun observe(): Flow<ProxyNetworkStatus>
    companion object { val DISCONNECTED = ProxyNetworkMonitor { flowOf(ProxyNetworkStatus.DISCONNECTED) } }
}

data class AcceptedProxyTransport(val peer: PeerAddress, val isBonded: Boolean, val transport: DuplexTransport)

interface ProxyListener : AutoCloseable {
    suspend fun accept(): AcceptedProxyTransport
    /** Must unblock accept immediately. */
    override fun close()
}

fun interface ProxyListenerFactory {
    /** Validate local permissions and the selected peer's bond before publishing a listener. */
    suspend fun listen(peer: PeerAddress): ProxyListener
}

/**
 * Phone resource and scheduling policy. These values are not watch protocol constants. Socket
 * exhaustion affects that socket/request and is diagnosed; no fabricated success is returned.
 */
data class Flow15ProxyPolicy(
    val maxTcpConnections: Int = 32,
    val maxUdpConnections: Int = 32,
    val maxQueuedTcpBytes: Int = 262_144,
    val maxQueuedTcpFrames: Int = 64,
    val tcpReadBytes: Int = 4_096,
    val connectTimeoutMillis: Long = 15_000,
    val writeTimeoutMillis: Long = 30_000,
    val udpIdleTimeoutMillis: Long = 60_000,
    val maxPullResponseBytes: Int = 16 * 1_024 * 1_024,
    val maxConcurrentPulls: Int = 4,
    val maxTrackedPushes: Int = 32,
    val decoderBufferBytes: Int = 131_104,
) {
    init {
        require(maxTcpConnections > 0 && maxUdpConnections > 0)
        require(maxQueuedTcpBytes >= 65_535 && maxQueuedTcpFrames > 0)
        require(tcpReadBytes in 1..65_535)
        require(connectTimeoutMillis in 1..Int.MAX_VALUE.toLong())
        require(writeTimeoutMillis > 0 && udpIdleTimeoutMillis > 0)
        require(maxPullResponseBytes >= 0 && maxConcurrentPulls > 0 && maxTrackedPushes > 0)
        require(decoderBufferBytes >= 65_552)
    }
}
