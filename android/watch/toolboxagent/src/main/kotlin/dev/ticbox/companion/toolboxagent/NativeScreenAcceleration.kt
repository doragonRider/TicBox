package dev.ticbox.companion.toolboxagent

import android.os.ParcelFileDescriptor
import dev.ticbox.companion.protocol.ScreenUdpAcceleration
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

/** The native side duplicates this exact Java socket; control and media retain one source port. */
internal class NativeScreenAcceleration private constructor(handle: Long) : ScreenUdpAcceleration {
    private val handle = AtomicLong(handle)
    // Media calls are serialized by ScreenUdpTransport; close touches only the atomic handle.
    private val statistics by lazy { LongArray(7) }
    private var cachedTarget: InetSocketAddress? = null
    private var cachedAddress = byteArrayOf()
    private var cachedScope = 0
    override val fecImplementation = when (ScreenNative.fecMode()) { 1 -> "neon"; 2 -> "ssse3"; else -> "scalar" }
    override fun threadCpuNanos(): Long? = ScreenNative.threadCpuNanos().takeIf { it >= 0 }
    override fun encodeParity(data: ByteArray, dataShards: Int, shardBytes: Int, parity: ByteArray) {
        if (handle.get() == 0L) throw IOException("screen_udp_closed")
        ScreenNative.encodeParity(data, dataShards, shardBytes, parity, true)
    }
    override fun sendBatch(bytes: ByteArray, lengths: IntArray, count: Int, target: InetSocketAddress, measure: Boolean): ScreenUdpAcceleration.BatchStats? {
        if (cachedTarget !== target) {
            cachedTarget = target
            cachedAddress = target.address.address
            cachedScope = (target.address as? Inet6Address)?.scopeId ?: 0
        }
        val calls = ScreenNative.sendBatch(handle.get(), bytes, lengths, count, cachedAddress, cachedScope, target.port, if (measure) statistics else null)
        if (!measure) return null
        return ScreenUdpAcceleration.BatchStats(calls, statistics[0].takeIf { it >= 0 }, statistics[1].takeIf { it >= 0 },
            statistics[2].takeIf { it >= 0 }, statistics[3].takeIf { it >= 0 }, statistics[4].toInt(), statistics[5].toInt(), statistics[6] != 0L)
    }
    override fun close() { handle.getAndSet(0).takeIf { it != 0L }?.let(ScreenNative::close) }
    companion object {
        fun open(socket: DatagramSocket): NativeScreenAcceleration {
            AgentNativeLibrary.failure?.let { throw IOException("screen_udp_native_unavailable", it) }
            return ParcelFileDescriptor.fromDatagramSocket(socket).use { NativeScreenAcceleration(ScreenNative.open(it.fd)) }
        }
    }
}
