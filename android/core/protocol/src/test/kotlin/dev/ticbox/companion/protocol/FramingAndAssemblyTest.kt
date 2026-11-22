package dev.ticbox.companion.protocol

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FramingAndAssemblyTest {
    private val fixtures = Properties().apply {
        requireNotNull(FramingAndAssemblyTest::class.java.getResourceAsStream("/node-golden.properties")).use(::load)
    }
    private fun fixture(name: String) = hex(fixtures.getProperty(name))

    @Test fun opaqueRecordFramingWorksAtEverySplitAndCoalescence() {
        val wire = hex("00000003ff00110000000200ff")
        for (split in 0..wire.size) {
            val decoder = GmsRecordDecoder()
            val records = decoder.push(wire.copyOfRange(0, split)) + decoder.push(wire.copyOfRange(split, wire.size))
            assertEquals(2, records.size)
            assertContentEquals(hex("ff0011"), records[0])
            assertContentEquals(hex("00ff"), records[1])
            decoder.finish()
        }
        val decoder = GmsRecordDecoder()
        val oneByteAtATime = wire.flatMap { decoder.push(byteArrayOf(it)) }
        assertEquals(2, oneByteAtATime.size)
        decoder.finish()
        assertContentEquals(hex("00000003ff0011"), GmsRecord.encode(hex("ff0011")))
        val large = GmsRecord.encode(ByteArray(524289) { 0x5a })
        assertEquals(listOf(524289, 524289), GmsRecordDecoder().push(large + large).map { it.size })
    }

    @Test fun malformedLengthsPoisonDecoderAndEofRejectsPartialRecords() {
        for (prefix in listOf("00", "000000", "00000003aabb")) {
            val decoder = GmsRecordDecoder()
            assertTrue(decoder.push(hex(prefix)).isEmpty())
            assertFailsWith<ProtocolException> { decoder.finish() }
            assertFailsWith<IllegalStateException> { decoder.push(byteArrayOf(0)) }
        }
        for (prefix in listOf("00000000", "ffffffff", "80000000", "00100001")) {
            val decoder = GmsRecordDecoder()
            assertFailsWith<ProtocolException> { decoder.push(hex(prefix)) }
            assertFailsWith<IllegalStateException> { decoder.push(hex("00000001ff")) }
        }
        assertFailsWith<ProtocolException> { GmsRecord.encode(byteArrayOf()) }
        assertFailsWith<ProtocolException> { GmsRecord.encode(ByteArray(1048577)) }
        assertFailsWith<ProtocolException> { GmsRecordDecoder(maxBytes = 1).push(hex("00000002")) }
    }

    @Test fun setupFramesAcceptEmptyMessagesAndEveryBoundary() {
        val wire = hex("0000000808011204080110010000000000000006080322020803")
        for (split in 0..wire.size) {
            val decoder = SetupFrameDecoder()
            val messages = decoder.push(wire.copyOfRange(0, split)) + decoder.push(wire.copyOfRange(split, wire.size))
            assertEquals(3, messages.size)
            assertContentEquals(hex("0801120408011001"), messages[0])
            assertTrue(messages[1].isEmpty())
            assertContentEquals(hex("080322020803"), messages[2])
            decoder.finish()
        }
        assertContentEquals(hex("00000000"), SetupProtocol.frame(byteArrayOf()))
        assertFailsWith<ProtocolException> { SetupFrameDecoder().push(hex("ffffffff")) }
    }

    @Test fun independentSha1VectorUsesUnpaddedBase64urlAndOneBasedIndex() {
        val expected = hex("0a03616263121b") + "qZk-NkcGgWq6PiVxeFDCbJzQ2J0".encodeToByteArray() + hex("18012001")
        assertContentEquals(expected, MessagePieces.encode("abc".encodeToByteArray()).single())
        val piece = MessagePieces.decode(expected)
        assertEquals(-1, piece.queueId)
        assertEquals(1, piece.pieceIndex)
        assertEquals(1, piece.pieceCount)
        assertContentEquals("abc".encodeToByteArray(), MessageAssembler().accept(expected))
        val wrongDigest = expected.copyOf().apply { this[7] = 65 }
        assertFailsWith<ProtocolException> { MessageAssembler().accept(wrongDigest) }
    }

    @Test fun nodeFragmentVectorsReassembleAndMatchKotlinOutgoingPieces() {
        val expected = (1..11).map { fixture("fragment$it") }
        val actual = MessagePieces.encode(fixture("fragmentPayload"), fragmentBytes = 3, queueId = Int.MIN_VALUE)
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (golden, encoded) -> assertContentEquals(golden, encoded) }
        assertEquals(Int.MIN_VALUE, MessagePieces.decode(expected.first()).queueId)
        val assembler = MessageAssembler()
        var message: ByteArray? = null
        for (piece in expected.reversed()) message = assembler.accept(piece) ?: message
        assertContentEquals(fixture("fragmentPayload"), assertNotNull(message))
        assertEquals(0, assembler.pendingMessageCount)
        assertEquals(0, assembler.bufferedByteCount)
        assembler.finish()
    }

    @Test fun interleavedMessagesAndIdenticalDuplicatesDoNotCorruptState() {
        val first = MessagePieces.encode("abcdef".encodeToByteArray(), 2, 1)
        val second = MessagePieces.encode("123456".encodeToByteArray(), 2, 2)
        val assembler = MessageAssembler()
        assertNull(assembler.accept(first[0]))
        assertNull(assembler.accept(second[0]))
        assertNull(assembler.accept(first[0]))
        assertEquals(4, assembler.bufferedByteCount)
        assertNull(assembler.accept(second[2]))
        assertNull(assembler.accept(first[1]))
        assertContentEquals("abcdef".encodeToByteArray(), assembler.accept(first[2]))
        assertContentEquals("123456".encodeToByteArray(), assembler.accept(second[1]))
        assembler.finish()
    }

    @Test fun conflictingPiecesDiscardOnlyTheirPendingAssembly() {
        val pieces = MessagePieces.encode("abcdef".encodeToByteArray(), 2, 7)
        val decoded = GmsStructs.decode("MessagePiece", pieces[0])
        val changed = GmsStructs.encode("MessagePiece", decoded.values + ("fragment" to "zz".encodeToByteArray()))
        val assembler = MessageAssembler()
        assembler.accept(pieces[0])
        assertFailsWith<ProtocolException> { assembler.accept(changed) }
        assertEquals(0, assembler.pendingMessageCount)
        assertEquals(0, assembler.bufferedByteCount)
        val badCount = GmsStructs.encode("MessagePiece", decoded.values + ("pieceCount" to 4))
        assembler.accept(pieces[0])
        assertFailsWith<ProtocolException> { assembler.accept(badCount) }
        assertEquals(0, assembler.pendingMessageCount)
        pieces.dropLast(1).forEach { assembler.accept(it) }
        assertFailsWith<ProtocolException> { assembler.finish() }
        assertEquals(0, assembler.pendingMessageCount)
        assertEquals(0, assembler.bufferedByteCount)
        assembler.reset()
        assembler.finish()
    }

    @Test fun assemblyBudgetsRejectCountBytesAndExcessPendingQueues() {
        val first = MessagePieces.encode("abcdef".encodeToByteArray(), 2, 1)
        val second = MessagePieces.encode("123456".encodeToByteArray(), 2, 2)
        assertFailsWith<ProtocolException> { MessageAssembler(maxPiecesPerMessage = 2).accept(first[0]) }
        val bytes = MessageAssembler(maxMessageBytes = 3)
        bytes.accept(first[0])
        assertFailsWith<ProtocolException> { bytes.accept(first[1]) }
        assertEquals(0, bytes.bufferedByteCount)
        val aggregate = MessageAssembler(maxBufferedBytes = 3)
        aggregate.accept(first[0])
        assertFailsWith<ProtocolException> { aggregate.accept(first[1]) }
        assertEquals(0, aggregate.bufferedByteCount)
        val pending = MessageAssembler(maxPendingMessages = 1)
        pending.accept(first[0])
        assertFailsWith<ProtocolException> { pending.accept(second[0]) }
        assertEquals(1, pending.pendingMessageCount)
        assertFailsWith<ProtocolException> { MessagePieces.encode("abc".encodeToByteArray(), 1) }
        assertFailsWith<ProtocolException> { MessagePieces.decode(hex("0a0161121b") + "qZk-NkcGgWq6PiVxeFDCbJzQ2J0".encodeToByteArray() + hex("18002001")) }
    }

    @Test fun expiredPartialMessagesReleaseMemoryWithoutFabricatingOutput() {
        var now = 0L
        val assembler = MessageAssembler(assemblyTimeoutNanos = 100L, nowNanos = { now })
        val first = MessagePieces.encode("abcdef".encodeToByteArray(), 2, 1)
        assertNull(assembler.accept(first[0]))
        now = 99L
        assertEquals(0, assembler.expire())
        now = 100L
        assertEquals(1, assembler.expire())
        assertEquals(0, assembler.pendingMessageCount)
        assertEquals(0, assembler.bufferedByteCount)
        assertNull(assembler.accept(first[2]))
        assertFailsWith<ProtocolException> { assembler.finish() }
    }

    @Test fun discoveringExpiryDuringAcceptFailsInsteadOfSilentlySkippingAnIncompleteMessage() {
        var now = 0L
        val assembler = MessageAssembler(assemblyTimeoutNanos = 100L, nowNanos = { now })
        val pieces = MessagePieces.encode("abcdef".encodeToByteArray(), 2, 1)
        assembler.accept(pieces[0])
        now = 100L
        assertFailsWith<ProtocolException> { assembler.accept(pieces[1]) }
        assertEquals(0, assembler.pendingMessageCount)
        assertEquals(0, assembler.bufferedByteCount)
        assembler.finish()
    }
}
