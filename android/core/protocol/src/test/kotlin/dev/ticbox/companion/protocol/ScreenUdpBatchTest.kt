package dev.ticbox.companion.protocol

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ScreenUdpBatchTest {
    @Test fun ordinarySendingAndReceivingDoNotCollectStatisticsAndDoNotResendNativeBatches() {
        val received = LinkedBlockingQueue<ScreenUdpTransport.Message>()
        val failures = LinkedBlockingQueue<IOException>()
        lateinit var native: RecordingAcceleration
        val key = ByteArray(32) { it.toByte() }
        ScreenUdpTransport.listen(key, ScreenUdpTransport.VIDEO, onFailure = failures::add,
            protection = ScreenUdpTransport.VideoProtection.RS_16_4,
            accelerationFactory = { RecordingAcceleration(it).also { native = it } }).use { sender ->
            ScreenUdpTransport.connect(InetAddress.getLoopbackAddress(), sender.port, key, ScreenUdpTransport.VIDEO,
                received::add, failures::add, protection = ScreenUdpTransport.VideoProtection.RS_16_4).use { receiver ->
                receiver.awaitConnected(); sender.awaitConnected()
                for (size in listOf(0, 1, 1153, 80_000)) {
                    val bytes = ByteArray(size) { (it * 17).toByte() }
                    sender.send(ToolboxProtocol.VIDEO_FRAME, bytes)
                    val frame = assertNotNull(received.poll(2, TimeUnit.SECONDS))
                    assertContentEquals(bytes, frame.bytes)
                    assertNull(frame.completedNanos); assertEquals(0L, frame.receiveWorkNanos); assertNull(frame.fec)
                }
                assertTrue(native.measurements.isNotEmpty() && native.measurements.none { it })
                assertEquals(0, native.cpuQueries)
                assertNull(received.poll(100, TimeUnit.MILLISECONDS))
                assertNull(receiver.roundTripNanos()); assertNull(failures.poll())
            }
        }
    }

    @Test fun encryptedBatchAccelerationPreservesFramesCountersAndCloseOwnership() {
        val received = LinkedBlockingQueue<ScreenUdpTransport.Message>()
        val failures = LinkedBlockingQueue<IOException>()
        val key = ByteArray(32) { (it * 31).toByte() }
        lateinit var native: RecordingAcceleration
        ScreenUdpTransport.listen(key, ScreenUdpTransport.VIDEO, onFailure = failures::add,
            protection = ScreenUdpTransport.VideoProtection.RS_16_4,
            accelerationFactory = { RecordingAcceleration(it).also { native = it } }).use { sender ->
            ScreenUdpTransport.connect(InetAddress.getByName("127.0.0.1"), sender.port, key, ScreenUdpTransport.VIDEO,
                received::add, failures::add, protection = ScreenUdpTransport.VideoProtection.RS_16_4).use { receiver ->
                receiver.awaitConnected(); sender.awaitConnected()
                for (size in listOf(0, 1, 1151, 1152, 1153, 16 * 1152, 80_000)) {
                    val bytes = ByteArray(size) { (it * 17).toByte() }
                    val stats = sender.sendMeasured(ToolboxProtocol.VIDEO_FRAME, bytes)
                    assertContentEquals(bytes, assertNotNull(received.poll(2, TimeUnit.SECONDS)).bytes)
                    assertTrue(stats.nativeBatch)
                    assertEquals(0L, stats.pacingWaitNanos) // Video has no send budget.
                    assertEquals("scalar", stats.fecImplementation)
                    assertEquals(stats.sendCalls, stats.gsoCalls)
                    assertEquals(stats.datagrams, stats.gsoDatagrams)
                    assertEquals(stats.sendCalls * 10L, stats.socketCopyNanos)
                    assertEquals(stats.sendCalls * 7L, stats.socketCopyCpuNanos)
                    assertEquals(stats.sendCalls * 100L, stats.socketNativeNanos)
                    assertEquals(stats.sendCalls * 50L, stats.socketNativeCpuNanos)
                    assertTrue(stats.sendCalls in 1..stats.datagrams)
                    if (size >= 16 * 1152) assertTrue(stats.sendCalls < stats.datagrams)
                    if (size == 16 * 1152) assertEquals(1, stats.sendCalls) // A whole block, data and parity, in one call.
                    assertTrue(stats.encryptNanos + stats.socketSendNanos + stats.fecEncodeNanos + stats.pacingWaitNanos <= stats.sendNanos)
                }
                assertTrue(native.counts.any { it == ScreenUdpTransport.MAX_BATCH }); assertTrue(native.counts.all { it in 1..ScreenUdpTransport.MAX_BATCH })
                assertNull(failures.poll())
            }
        }
        assertEquals(1, native.closes)
    }

    @Test fun cipherWorkspacesDoNotAuthenticateStaleTailOrReuseHeaderNonce() {
        val key = ByteArray(32) { it.toByte() }
        val sender = ScreenUdpCipher(key, ScreenUdpTransport.VIDEO, true)
        val receiver = ScreenUdpCipher(key, ScreenUdpTransport.VIDEO, true)
        val source = ByteArray(2000) { it.toByte() }
        val output = ByteArray(9600) { 42 }
        for (i in 0..7) {
            val value = ScreenUdpFragment(ScreenUdpCipher.MEDIA, i.toLong(), i.toLong(), 1, 100, 0, 1, source, 300, 100)
            val length = sender.encodeInto(value, output, i * 1200)
            assertEquals(148, length)
            val decoded = assertNotNull(receiver.decode(output.copyOfRange(i * 1200, i * 1200 + length)))
            assertEquals(i.toLong(), decoded.packetSequence)
            assertContentEquals(source.copyOfRange(300, 400), decoded.payload)
            assertEquals(42.toByte(), output[i * 1200 + length])
        }
        val valid = output.copyOfRange(0, 148)
        assertNotNull(receiver.decode(valid.copyOf(1201), valid.size))
        assertNull(receiver.decode(valid.copyOf(1201)))
        assertFailsWith<IllegalArgumentException> { sender.encodeInto(ScreenUdpFragment(ScreenUdpCipher.MEDIA, 9, 9, 1, 100, 0, 1, source, 1980, 100), output, 0) }
    }

    @Test fun reusableParityMatchesExistingCodecForEveryBlockSizeIncludingPadding() {
        for (k in 1..16) for (size in listOf(0, 1, 7, 16, 17, 1152)) {
            val shards = Array(k) { row -> ByteArray(size) { column -> (row * 31 + column).toByte() } }
            val input = ByteArray(k * size); shards.forEachIndexed { index, bytes -> bytes.copyInto(input, index * size) }
            val output = ByteArray(4 * 1152)
            ScreenReedSolomon.encodeInto(input, k, size, output)
            ScreenReedSolomon.encode(shards).forEachIndexed { index, bytes -> assertContentEquals(bytes, output.copyOfRange(index * size, (index + 1) * size)) }
        }
    }

    private class RecordingAcceleration(private val socket: DatagramSocket) : ScreenUdpAcceleration {
        val counts = mutableListOf<Int>()
        val measurements = mutableListOf<Boolean>()
        var cpuQueries = 0
        var closes = 0
        override val fecImplementation = "scalar"
        override fun threadCpuNanos(): Long? { cpuQueries++; return null }
        override fun encodeParity(data: ByteArray, dataShards: Int, shardBytes: Int, parity: ByteArray) =
            ScreenReedSolomon.encodeInto(data, dataShards, shardBytes, parity)
        override fun sendBatch(bytes: ByteArray, lengths: IntArray, count: Int, target: InetSocketAddress, measure: Boolean): ScreenUdpAcceleration.BatchStats? {
            counts += count
            measurements += measure
            repeat(count) { socket.send(DatagramPacket(bytes, it * 1200, lengths[it], target)) }
            return if (measure) ScreenUdpAcceleration.BatchStats(1, 10, 7, 100, 50, 1, count) else null
        }
        override fun close() { closes++ }
    }
}
