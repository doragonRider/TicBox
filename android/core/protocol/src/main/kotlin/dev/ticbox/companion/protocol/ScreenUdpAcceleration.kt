package dev.ticbox.companion.protocol

import java.net.InetSocketAddress

/** Per-socket sender acceleration. Wire authentication, replay and FEC layout stay here. */
interface ScreenUdpAcceleration : AutoCloseable {
    /** Fixed diagnostic enum, never a library path. */
    val fecImplementation: String
    /** Current sender-thread CPU clock; null when unavailable. No process or device identifiers. */
    fun threadCpuNanos(): Long? = null
    /** data is a zero-padded, contiguous block; parity output has ceil(k/4) shards. */
    fun encodeParity(data: ByteArray, dataShards: Int, shardBytes: Int, parity: ByteArray)
    data class BatchStats(
        val calls: Int,
        val copyNanos: Long? = null, val copyCpuNanos: Long? = null,
        val nativeNanos: Long? = null, val nativeCpuNanos: Long? = null,
        val gsoCalls: Int = 0, val gsoDatagrams: Int = 0, val gsoDisabled: Boolean = false,
    )
    /** Up to twenty authenticated datagrams at 1200-byte strides; GSO preserves those boundaries. */
    /** measure=false must not sample diagnostic clocks and returns no statistics. */
    fun sendBatch(bytes: ByteArray, lengths: IntArray, count: Int, target: InetSocketAddress, measure: Boolean): BatchStats?
}
