package dev.ticbox.companion.protocol

import kotlin.test.*

class ScreenReassemblyWindowTest {
    private val data = Array(16) { row -> ByteArray(1152) { column -> (row * 31 + column).toByte() } }
    private val shards = data + ScreenReedSolomon.encode(data)
    private fun fragment(sequence: Long, part: Int) = ScreenUdpFragment(ScreenUdpCipher.MEDIA, sequence * 20 + part,
        sequence, ToolboxProtocol.VIDEO_FRAME, 16 * 1152, part, 20, shards[part])

    @Test fun oneUnrecoverableFrameCannotMakeTheReceiverDiscardTheFreshRecoveryKeyAt120Fps() {
        fun replay(limit: Int): List<ScreenUdpTransport.Message> {
            val assembler = ScreenFecAssembler(150, limit)
            val output = mutableListOf<ScreenUdpTransport.Message>()
            for (sequence in 0L..40) {
                for (part in shards.indices) {
                    if (sequence == 0L && part < 5) continue // Exactly one unrecoverable frame.
                    output += assembler.accept(fragment(sequence, part), sequence * 1000 / 120)
                    assertTrue(assembler.retainedMessages <= limit)
                    assertTrue(assembler.retainedBytes <= ScreenFecAssembler.MAX_BYTES)
                }
            }
            output += assembler.expire(600)
            return output
        }
        // Four slots used to fill up while the lost frame waited the full 150 ms, so the key requested
        // after it was refused locally. Whole successors now give it up after 30 ms, before they do.
        for (limit in listOf(4, 22)) {
            val output = replay(limit)
            assertEquals((1L..40).toList(), output.map { it.sequence })
            assertEquals(1, output.count { it.discontinuity })
            val gap = output.first().gap!!
            assertEquals(1L, gap.skippedMessages)
            assertTrue(gap.laterComplete)
            assertEquals(0L, output.sumOf { it.gap?.capacityRejectedFragments ?: 0 })
            assertEquals(0L, output.sumOf { it.gap?.capacityEvictions ?: 0 })
        }
    }

    @Test fun lateFecRepairWithinTheEarlyLossWindowPreservesABurstOfSuccessors() {
        val assembler = ScreenFecAssembler(150, 22, collectDiagnostics = true)
        for (part in 5..19) assertTrue(assembler.accept(fragment(0, part), 0).isEmpty())
        // A backlog flushed at once: seventeen whole successors in 17 ms hold 18 of the 22 slots.
        for (sequence in 1L..17) for (part in shards.indices) {
            assertTrue(assembler.accept(fragment(sequence, part), sequence).isEmpty())
        }
        // One late original gives RS enough information to reconstruct the other four.
        val repaired = assembler.accept(fragment(0, 0), ScreenUdpTransport.LATER_COMPLETE_MILLIS - 1)
        assertEquals((0L..17).toList(), repaired.map { it.sequence })
        assertEquals(4, repaired.first().fec!!.recoveredDataShards)
        assertTrue(repaired.none { it.discontinuity })
        assertEquals(0, assembler.retainedBytes)
    }

    @Test fun moreMessageSlotsNeverRelaxTheExistingMemoryBudget() {
        val fec = ScreenFecAssembler(150, 22, collectDiagnostics = true)
        val huge = ScreenUdpFragment(ScreenUdpCipher.MEDIA, 0, 0, ToolboxProtocol.VIDEO_FRAME,
            ToolboxProtocol.MAX_PACKET, 0, ScreenFecLayout.wireShards(ToolboxProtocol.MAX_PACKET), ByteArray(1152))
        fec.accept(huge, 0)
        val charge = fec.retainedBytes
        fec.accept(huge.copy(messageSequence = 1), 1)
        assertEquals(1, fec.retainedMessages)
        assertEquals(charge, fec.retainedBytes)
        assertTrue(charge <= ScreenFecAssembler.MAX_BYTES)
        val ordinary = ScreenUdpAssembler(150, 22)
        val raw = huge.copy(count = (ToolboxProtocol.MAX_PACKET + 1151) / 1152)
        ordinary.accept(raw, 0); ordinary.accept(raw.copy(messageSequence = 1), 1)
        ordinary.accept(raw.copy(messageSequence = 2), 2)
        assertEquals(2, ordinary.retainedMessages)
        assertEquals(ScreenUdpAssembler.MAX_BYTES, ordinary.retainedBytes)
    }

    @Test fun configuredOrdinaryReorderingAlsoPreservesABurstWithinTheEarlyLossWindow() {
        val assembler = ScreenUdpAssembler(150, 22)
        for (sequence in 1L..18) assertTrue(assembler.accept(ScreenUdpFragment(ScreenUdpCipher.MEDIA,
            sequence, sequence, 14, 1, 0, 1, byteArrayOf(sequence.toByte())), sequence).isEmpty())
        // Message 1 arrived at 1 ms, so the missing message 0 is still in its window.
        val repaired = assembler.accept(ScreenUdpFragment(ScreenUdpCipher.MEDIA, 0, 0, 14, 1, 0, 1, byteArrayOf(0)),
            ScreenUdpTransport.LATER_COMPLETE_MILLIS)
        assertEquals((0L..18).toList(), repaired.map { it.sequence })
        assertTrue(repaired.none { it.discontinuity })
    }

    @Test fun admissionCountsAreExplicitAndBounded() {
        for (count in listOf(0, 33, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { ScreenUdpTransport.Timing(videoMaxMessages = count) }
            assertFailsWith<IllegalArgumentException> { ScreenFecAssembler(150, count) }
            assertFailsWith<IllegalArgumentException> { ScreenUdpAssembler(150, count) }
        }
        assertEquals(4, ScreenUdpTransport.Timing().videoMaxMessages) // Existing standalone callers retain their profile.
    }
}
