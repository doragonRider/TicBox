package dev.ticbox.companion.protocol

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class ScreenFecTest {
    private val key = ByteArray(32) { (it * 19).toByte() }
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val protection = ScreenUdpTransport.VideoProtection.RS_16_4

    @Test fun completeIndependentFrameCanSupersedeMissingAndIncompletePredecessorsBeforeExpiry() {
        val assembler = ScreenFecAssembler(150, maximumMessages = 4, independentMessage = { _, bytes -> bytes.firstOrNull() == 42.toByte() })
        val incomplete = fragments(0, ByteArray(8_000))
        assembler.accept(incomplete.first(), 0)
        fragments(1, byteArrayOf(1)).forEach { assertTrue(assembler.accept(it, 10).isEmpty()) }
        // Sequence 2 is wholly missing, 4 is complete but depends on the forthcoming key 3.
        fragments(4, byteArrayOf(4)).forEach { assertTrue(assembler.accept(it, 12).isEmpty()) }
        val key = ByteArray(5_000) { 42 }
        val data = fragments(3, key).filter { it.index < ScreenFecLayout.dataShards(key.size) }
        for (piece in data.dropLast(1)) assertTrue(assembler.accept(piece, 20).isEmpty())
        val delivered = assembler.accept(data.last(), 25)
        assertEquals(listOf(3L, 4L), delivered.map { it.sequence })
        assertContentEquals(key, delivered.first().bytes)
        val gap = assertNotNull(delivered.first().gap)
        assertTrue(gap.independentFrame)
        assertEquals(3L, gap.skippedMessages)
        assertEquals(1L, gap.incompleteMessages)
        assertTrue(gap.missingFragments > 0)
        assertFalse(gap.fragmentTimeout || gap.missingMessageTimeout || gap.frameDeadline)
        assertFalse(delivered.last().discontinuity)
        assertTrue(incomplete.flatMap { assembler.accept(it, 26) }.isEmpty())
        assertEquals(0, assembler.retainedBytes)
    }

    @Test fun incompleteIndependentFrameAndOrdinaryDeltaKeepNormalOrderingAndBounds() {
        val assembler = ScreenFecAssembler(150, maximumMessages = 3, independentMessage = { _, bytes -> bytes.firstOrNull() == 42.toByte() })
        assembler.accept(fragments(0, ByteArray(8_000)).first(), 0)
        assertTrue(fragments(1, byteArrayOf(1)).flatMap { assembler.accept(it, 10) }.isEmpty())
        assertTrue(assembler.accept(fragments(2, ByteArray(8_000) { 42 }).first(), 20).isEmpty())
        // The whole delta 1 cuts the wait for message 0 short; the incomplete key 2 skips nothing.
        assertTrue(assembler.expire(ScreenUdpTransport.LATER_COMPLETE_MILLIS - 1).isEmpty())
        assertEquals(3, assembler.retainedMessages)
        assertTrue(assembler.retainedBytes <= ScreenFecAssembler.MAX_BYTES)
        val delta = assembler.expire(ScreenUdpTransport.LATER_COMPLETE_MILLIS).single()
        assertEquals(1L, delta.sequence)
        assertFalse(assertNotNull(delta.gap).independentFrame)
        assertTrue(delta.gap!!.laterComplete)
    }

    @Test fun completeAuthenticatedIndependentFrameDoesNotWaitForLostPredecessorOverRealUdp() {
        val received = LinkedBlockingQueue<ScreenUdpTransport.Message>()
        val errors = LinkedBlockingQueue<IOException>()
        val timing = ScreenUdpTransport.Timing(videoReorderMillis = 5_000)
        ScreenUdpTransport.listen(key, ScreenUdpTransport.VIDEO, onFailure = errors::add, protection = protection).use { server ->
            LossProxy(server.port) { sequence, index -> sequence == 0L && index in 0..4 }.use { proxy ->
                ScreenUdpTransport.connect(loopback, proxy.port, key, ScreenUdpTransport.VIDEO, received::add, errors::add,
                    timing = timing, protection = protection, independentVideoMessage = { _, bytes -> bytes.contentEquals(byteArrayOf(42)) }).use { client ->
                    client.awaitConnected(); server.awaitConnected()
                    server.send(ToolboxProtocol.VIDEO_FRAME, ByteArray(16 * 1152))
                    server.send(ToolboxProtocol.VIDEO_FRAME, byteArrayOf(42))
                    val message = assertNotNull(received.poll(1, TimeUnit.SECONDS), "Complete key must not wait for the multi-second partial-frame deadline")
                    assertTrue(assertNotNull(message.gap).independentFrame)
                    assertEquals(5L, message.gap!!.missingFragments)
                    assertContentEquals(byteArrayOf(42), message.bytes)
                    assertNull(proxy.failure.get()); assertNull(errors.poll())
                }
            }
        }
    }

    @Test fun repairStillWorksWithoutCollectingFecDiagnostics() {
        val data = ByteArray(5_000) { (it * 7).toByte() }
        val assembler = ScreenFecAssembler(150)
        val delivered = fragments(0, data).filter { it.index != 0 }.flatMap { assembler.accept(it, 0) }
        assertContentEquals(data, delivered.single().bytes)
        assertFalse(delivered.single().discontinuity)
        assertNull(delivered.single().fec)
    }

    @Test fun aWholeLaterMessageEndsTheWaitForLostShardsEarly() {
        val assembler = ScreenFecAssembler(150)
        // Message 0 loses five shards of one block, more than its parity can repair; message 1 arrives whole.
        val broken = fragments(0, ByteArray(16 * 1152) { it.toByte() }).filter { it.index !in 0..4 }
        assertTrue(broken.flatMap { assembler.accept(it, 0) }.isEmpty())
        assertTrue(fragments(1, byteArrayOf(7)).flatMap { assembler.accept(it, 5) }.isEmpty())
        assertTrue(assembler.expire(ScreenUdpTransport.LATER_COMPLETE_MILLIS - 1).isEmpty(), "Reordering gets its window")
        val next = assembler.expire(ScreenUdpTransport.LATER_COMPLETE_MILLIS).single()
        assertEquals(1L, next.sequence)
        val gap = assertNotNull(next.gap)
        assertTrue(gap.laterComplete); assertFalse(gap.fragmentTimeout); assertFalse(gap.missingMessageTimeout)
        assertEquals(5L, gap.missingFragments)
        // A message lost whole behind a whole one is skipped as early.
        assertTrue(fragments(3, byteArrayOf(9)).flatMap { assembler.accept(it, 100) }.isEmpty())
        val after = assembler.expire(100 + ScreenUdpTransport.LATER_COMPLETE_MILLIS).single()
        assertEquals(3L, after.sequence)
        assertTrue(assertNotNull(after.gap).laterComplete); assertFalse(after.gap!!.missingMessageTimeout)
    }

    @Test fun parityMatchesAnIndependentLogarithmTableReferenceVector() {
        val data = Array(16) { row -> ByteArray(32) { column -> (row * 17 + column * 31).toByte() } }
        val parity = ScreenReedSolomon.encode(data)
        val digest = MessageDigest.getInstance("SHA-256")
        parity.forEach(digest::update)
        assertEquals("da153c3faf520b85e63c648da533618530ff813de8a9e1b794dc3a9c7bedf811", digest.digest().joinToString("") { "%02x".format(it) })
    }

    @Test fun everyPermittedErasureSetRecoversForFullAndShortenedBlocks() {
        for (count in 1..16) {
            val data = Array(count) { row -> ByteArray(17) { column -> (row * 23 + column * 29 + count).toByte() } }
            val parity = ScreenReedSolomon.encode(data)
            for (lostCount in 0..parity.size) combinations(count + parity.size, lostCount) { lost ->
                val damagedData = Array<ByteArray?>(count) { if (it in lost) null else data[it] }
                val damagedParity = Array<ByteArray?>(parity.size) { if (count + it in lost) null else parity[it] }
                val recovered = assertNotNull(ScreenReedSolomon.recover(damagedData, damagedParity, 17))
                for (index in data.indices) assertContentEquals(data[index], damagedData[index] ?: recovered[index], "k=$count lost=${lost.joinToString()}")
            }
            val tooMany = Array<ByteArray?>(count) { if (it < parity.size + 1) null else data[it] }
            // When k <= parity, remove all data AND parity so it is genuinely undecodable.
            val insufficientParity = if (count <= parity.size) arrayOfNulls<ByteArray>(parity.size) else Array<ByteArray?>(parity.size) { parity[it] }
            assertNull(ScreenReedSolomon.recover(tooMany, insufficientParity, 17))
        }
    }

    @Test fun assemblerRecoversFourDataErasuresInEveryBlockWithoutBreakingTheFrameChain() {
        val data = ByteArray(80_000) { (it * 37).toByte() }
        val assembler = ScreenFecAssembler(150, collectDiagnostics = true)
        val pieces = fragments(0, data)
        val delivered = mutableListOf<ScreenUdpTransport.Message>()
        for (piece in pieces.reversed()) {
            val block = piece.index / 20
            val local = piece.index % 20
            val k = ScreenFecLayout.dataInBlock(data.size, block)
            if (local < minOf(ScreenReedSolomon.parityCount(k), k)) continue
            delivered += assembler.accept(piece, 0)
            delivered += assembler.accept(piece, 1) // Duplicate cannot run a second decode.
        }
        val message = delivered.single()
        assertContentEquals(data, message.bytes)
        assertFalse(message.discontinuity)
        val fec = assertNotNull(message.fec)
        assertEquals(ScreenFecLayout.parityShards(data.size), fec.recoveredDataShards)
        assertEquals(ScreenFecLayout.blocks(data.size), fec.recoveredBlocks)
        assertEquals(0, assembler.retainedBytes)
        assertTrue(assembler.accept(pieces.first(), 2).isEmpty())
    }

    @Test fun excessErasuresStillReportARealGapAndDoNotFabricateACompleteFrame() {
        val assembler = ScreenFecAssembler(150, collectDiagnostics = true)
        val content = ByteArray(16 * 1152) { it.toByte() }
        for (piece in fragments(0, content).filter { it.index !in 0..4 }) assertTrue(assembler.accept(piece, 0).isEmpty())
        val future = fragments(1, byteArrayOf(42)).first()
        assertTrue(assembler.accept(future, 10).isEmpty())
        val result = assembler.expire(150).single()
        assertEquals(1L, result.sequence)
        assertContentEquals(byteArrayOf(42), result.bytes)
        assertTrue(result.discontinuity)
        val gap = assertNotNull(result.gap)
        assertEquals(1L, gap.incompleteMessages)
        assertEquals(5L, gap.missingFragments)
        assertTrue(gap.fragmentTimeout)
        assertEquals(0, assertNotNull(result.fec).recoveredDataShards)
    }

    @Test fun dataParityAndRecoveryWorkAreChargedBeforeAllocatingASecondHugeFrame() {
        val assembler = ScreenFecAssembler(150, collectDiagnostics = true)
        val total = ToolboxProtocol.MAX_PACKET
        val first = ScreenUdpFragment(ScreenUdpCipher.MEDIA, 0, 0, ToolboxProtocol.VIDEO_FRAME, total,
            0, ScreenFecLayout.wireShards(total), ByteArray(1152))
        assertTrue(assembler.accept(first, 0).isEmpty())
        val charge = assembler.retainedBytes
        assertTrue(charge > total && charge <= ScreenFecAssembler.MAX_BYTES)
        assertTrue(assembler.accept(first.copy(messageSequence = 1), 1).isEmpty())
        assertEquals(1, assembler.retainedMessages)
        assertEquals(charge, assembler.retainedBytes)
        assembler.expire(151)
        assertEquals(0, assembler.retainedBytes)
        val recovered = assembler.accept(fragments(2, byteArrayOf(3)).first(), 152)
        assertTrue(recovered.isEmpty())
        val next = assembler.expire(302).single()
        assertTrue(next.discontinuity)
        assertEquals(1L, assertNotNull(next.gap).capacityRejectedFragments)
    }

    @Test fun aCompleteExpectedMessageUnlocksFourFutureFramesWithoutCapacityEviction() {
        val assembler = ScreenFecAssembler(150, collectDiagnostics = true)
        for (sequence in 1L..4) assertTrue(assembler.accept(fragments(sequence, byteArrayOf(sequence.toByte())).first(), sequence).isEmpty())
        val result = assembler.accept(fragments(0, byteArrayOf(0)).first(), 20)
        assertEquals((0L..4).toList(), result.map { it.sequence })
        assertTrue(result.none { it.discontinuity })
        assertEquals(0, assembler.retainedMessages)
    }

    @Test fun duplicatesAndParityCannotKeepAnUnrecoverableFrameAliveForever() {
        val assembler = ScreenFecAssembler(100, collectDiagnostics = true)
        val first = fragments(0, ByteArray(50_000)).first()
        assembler.accept(first, 0)
        repeat(20) { assembler.accept(first, 99) }
        assembler.expire(100)
        assertEquals(0, assembler.retainedMessages)
        val large = fragments(1, ByteArray(100_000))
        for (index in 0..22) assembler.accept(large[index], 200L + index * 90)
        assembler.expire(2_201)
        val next = assembler.accept(fragments(2, byteArrayOf(7)).first(), 2_202).single()
        assertTrue(next.discontinuity)
        assertTrue(assertNotNull(next.gap).frameDeadline)
    }

    @Test fun fecHeadersPaddingAndCipherVersionAreAuthenticatedAndCanonical() {
        val cipher = ScreenUdpCipher(key, ScreenUdpTransport.VIDEO, true, protection)
        val piece = fragments(0, ByteArray(2_700) { it.toByte() })[2]
        val packet = cipher.encode(piece)
        assertEquals(1_200, packet.size)
        assertContentEquals(piece.payload, assertNotNull(ScreenUdpCipher(key, ScreenUdpTransport.VIDEO, true, protection).decode(packet)).payload)
        assertNull(ScreenUdpCipher(key, ScreenUdpTransport.VIDEO, true).decode(packet))
        for (index in listOf(4, 7, 16, 27, 28, 31, 32, packet.lastIndex)) {
            assertNull(cipher.decode(packet.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }))
        }
        assertFailsWith<IllegalArgumentException> { cipher.encode(piece.copy(count = piece.count + 1)) }
        assertFailsWith<IllegalArgumentException> { cipher.encode(piece.copy(index = piece.count)) }
        assertFailsWith<IllegalArgumentException> { cipher.encode(piece.copy(payload = piece.payload.copyOf().also { it[it.lastIndex] = 1 })) }
        assertFailsWith<IllegalArgumentException> { ScreenUdpCipher(key, ScreenUdpTransport.AUDIO, true, protection) }
    }

    @Test fun encryptedDatagramsRecoverActualDroppedPacketsAcrossTheSocketWithoutFallback() {
        val received = LinkedBlockingQueue<ScreenUdpTransport.Message>()
        val errors = LinkedBlockingQueue<IOException>()
        ScreenUdpTransport.listen(key, ScreenUdpTransport.VIDEO, onFailure = errors::add, protection = protection, collectDiagnostics = true).use { server ->
            LossProxy(server.port) { _, index -> index % 20 in setOf(0, 3, 7, 12) }.use { proxy ->
                ScreenUdpTransport.connect(loopback, proxy.port, key, ScreenUdpTransport.VIDEO, received::add, errors::add, protection = protection, collectDiagnostics = true).use { client ->
                    client.awaitConnected(); server.awaitConnected()
                    val bytes = ByteArray(5 * 16 * 1152) { (it * 7).toByte() }
                    val stats = server.sendMeasured(ToolboxProtocol.VIDEO_FRAME, bytes)
                    val frame = assertNotNull(received.poll(3, TimeUnit.SECONDS))
                    assertContentEquals(bytes, frame.bytes)
                    assertFalse(frame.discontinuity)
                    val fec = assertNotNull(frame.fec)
                    assertEquals(20, fec.recoveredDataShards)
                    assertEquals(5, fec.recoveredBlocks)
                    assertEquals(20, stats.parityDatagrams)
                    assertEquals(100, stats.datagrams)
                    assertEquals(120_000L, stats.wireBytes)
                    assertTrue(stats.fecEncodeNanos > 0 && stats.fecEncodeNanos <= stats.sendNanos)
                    assertTrue(proxy.dropped.get() >= 20)
                    assertNull(proxy.failure.get()); assertNull(errors.poll())
                }
            }
        }
    }

    @Test fun encryptedLossBeyondParityBudgetStillAdvancesWithAnExplicitGap() {
        val received = LinkedBlockingQueue<ScreenUdpTransport.Message>()
        val errors = LinkedBlockingQueue<IOException>()
        ScreenUdpTransport.listen(key, ScreenUdpTransport.VIDEO, onFailure = errors::add, protection = protection, collectDiagnostics = true).use { server ->
            LossProxy(server.port) { sequence, index -> sequence == 0L && index in 0..4 }.use { proxy ->
                ScreenUdpTransport.connect(loopback, proxy.port, key, ScreenUdpTransport.VIDEO, received::add, errors::add, protection = protection, collectDiagnostics = true).use { client ->
                    client.awaitConnected(); server.awaitConnected()
                    server.send(ToolboxProtocol.VIDEO_FRAME, ByteArray(16 * 1152) { it.toByte() })
                    server.send(ToolboxProtocol.VIDEO_FRAME, byteArrayOf(42))
                    val next = assertNotNull(received.poll(2, TimeUnit.SECONDS))
                    assertEquals(1L, next.sequence)
                    assertContentEquals(byteArrayOf(42), next.bytes)
                    assertTrue(next.discontinuity)
                    assertEquals(5L, assertNotNull(next.gap).missingFragments)
                    assertEquals(0, next.fec!!.recoveredDataShards)
                    assertNull(received.poll(20, TimeUnit.MILLISECONDS))
                    assertNull(proxy.failure.get()); assertNull(errors.poll())
                }
            }
        }
    }

    @Test fun fecSendCancellationClosesInsteadOfFinishingABigFrame() {
        val received = LinkedBlockingQueue<ScreenUdpTransport.Message>()
        val result = LinkedBlockingQueue<Throwable>()
        ScreenUdpTransport.listen(key, ScreenUdpTransport.VIDEO, onFailure = {}, protection = protection, collectDiagnostics = true).use { server ->
            ScreenUdpTransport.connect(loopback, server.port, key, ScreenUdpTransport.VIDEO, received::add, {}, protection = protection, collectDiagnostics = true).use { client ->
                client.awaitConnected(); server.awaitConnected()
                server.setSendRate(1, 1200)
                val sender = Thread { runCatching { server.send(ToolboxProtocol.VIDEO_FRAME, ByteArray(100_000)) }.exceptionOrNull()?.let(result::add) }.apply { start() }
                server.close()
                assertIs<IOException>(assertNotNull(result.poll(2, TimeUnit.SECONDS)))
                sender.join(1_000)
                assertFalse(sender.isAlive)
                assertNull(received.poll())
            }
        }
    }

    @Test fun mismatchedNegotiatedProtectionFailsHandshakeAndEmptyMessagesStayDefined() {
        val timing = ScreenUdpTransport.Timing(handshakeMillis = 150, heartbeatMillis = 25, idleMillis = 500)
        ScreenUdpTransport.listen(key, ScreenUdpTransport.VIDEO, onFailure = {}, timing = timing).use { old ->
            ScreenUdpTransport.connect(loopback, old.port, key, ScreenUdpTransport.VIDEO, {}, {}, timing, protection).use { updated ->
                assertFailsWith<IOException> { updated.awaitConnected(1_000) }
            }
        }
        val received = LinkedBlockingQueue<ScreenUdpTransport.Message>()
        ScreenUdpTransport.listen(key, ScreenUdpTransport.VIDEO, onFailure = {}, protection = protection, collectDiagnostics = true).use { server ->
            ScreenUdpTransport.connect(loopback, server.port, key, ScreenUdpTransport.VIDEO, received::add, {}, protection = protection, collectDiagnostics = true).use { client ->
                client.awaitConnected(); server.awaitConnected()
                val stats = server.sendMeasured(ToolboxProtocol.VIDEO_FRAME, byteArrayOf())
                val message = assertNotNull(received.poll(2, TimeUnit.SECONDS))
                assertContentEquals(byteArrayOf(), message.bytes)
                assertEquals(2, stats.datagrams); assertEquals(1, stats.parityDatagrams)
                assertEquals(0, message.fec!!.recoveredDataShards)
            }
        }
    }

    private fun fragments(sequence: Long, bytes: ByteArray): List<ScreenUdpFragment> {
        val length = ScreenFecLayout.shardBytes(bytes.size)
        val count = ScreenFecLayout.wireShards(bytes.size)
        return buildList {
            for (block in 0 until ScreenFecLayout.blocks(bytes.size)) {
                val data = Array(ScreenFecLayout.dataInBlock(bytes.size, block)) { index ->
                    val offset = ScreenFecLayout.dataOffset(block, index)
                    bytes.copyOfRange(offset, minOf(bytes.size, offset + length)).copyOf(length)
                }
                (data.toList() + ScreenReedSolomon.encode(data)).forEachIndexed { index, payload ->
                    add(ScreenUdpFragment(ScreenUdpCipher.MEDIA, size.toLong(), sequence, ToolboxProtocol.VIDEO_FRAME,
                        bytes.size, block * 20 + index, count, payload))
                }
            }
        }
    }

    private fun combinations(size: Int, count: Int, action: (Set<Int>) -> Unit) {
        val chosen = LinkedHashSet<Int>()
        fun visit(start: Int) {
            if (chosen.size == count) { action(chosen); return }
            for (index in start until size) { chosen += index; visit(index + 1); chosen -= index }
        }
        visit(0)
    }

    private class LossProxy(serverPort: Int, private val drop: (Long, Int) -> Boolean) : AutoCloseable {
        private val loopback = InetAddress.getByName("127.0.0.1")
        private val target = InetSocketAddress(loopback, serverPort)
        private val socket = DatagramSocket(InetSocketAddress(loopback, 0))
        val port get() = socket.localPort
        val dropped = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        private val thread = Thread({
            val bytes = ByteArray(1_201)
            var client: SocketAddress? = null
            try {
                while (!socket.isClosed) {
                    val packet = DatagramPacket(bytes, bytes.size)
                    socket.receive(packet)
                    val destination = if (packet.socketAddress == target) {
                        if (packet.length >= 32 && bytes[6].toInt() == ScreenUdpCipher.MEDIA && drop(ByteBuffer.wrap(bytes, 16, 8).long, ByteBuffer.wrap(bytes, 28, 2).short.toInt() and 65535)) {
                            dropped.incrementAndGet(); continue
                        }
                        client ?: continue
                    } else { client = packet.socketAddress; target }
                    socket.send(DatagramPacket(bytes, packet.length, destination))
                }
            } catch (error: Exception) { if (!socket.isClosed) failure.set(error) }
        }, "fec-loss-proxy").apply { isDaemon = true; start() }
        override fun close() { socket.close(); thread.join(1_000); check(!thread.isAlive) }
    }
}
