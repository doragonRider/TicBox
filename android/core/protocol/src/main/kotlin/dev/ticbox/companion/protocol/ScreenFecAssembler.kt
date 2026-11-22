package dev.ticbox.companion.protocol

import java.util.BitSet
import java.util.TreeMap

/** Fixed-size RS blocks; authenticated data goes straight into the single frame output buffer. */
internal class ScreenFecAssembler(private val expiryMillis: Long, private val maximumMessages: Int = MAX_MESSAGES, private val collectDiagnostics: Boolean = false,
    private val independentMessage: ((Int, ByteArray) -> Boolean)? = null,
) {
    init { require(expiryMillis > 0 && maximumMessages in 1..32) }
    private class Block(val dataCount: Int) {
        val seen = BitSet(dataCount + ScreenReedSolomon.parityCount(dataCount))
        val parity = arrayOfNulls<ByteArray>(ScreenReedSolomon.parityCount(dataCount))
        var dataReceived = 0
        var parityReceived = 0
        var complete = false
    }
    private class Partial(val first: ScreenUdpFragment, val arrived: Long, val collectDiagnostics: Boolean) {
        val bytes = ByteArray(first.total)
        val blocks = arrayOfNulls<Block>(ScreenFecLayout.blocks(first.total))
        val reservation = ScreenFecLayout.reservedBytes(first.total)
        var lastProgress = arrived
        var completedBlocks = 0
        var receivedData = 0
        var recoveredData = 0
        var recoveredBlocks = 0
        var decodeNanos = 0L
        val complete get() = completedBlocks == blocks.size

        fun accept(fragment: ScreenUdpFragment, now: Long) {
            val blockIndex = fragment.index / ScreenFecLayout.BLOCK
            val shardIndex = fragment.index % ScreenFecLayout.BLOCK
            val block = blocks[blockIndex] ?: Block(ScreenFecLayout.dataInBlock(first.total, blockIndex)).also { blocks[blockIndex] = it }
            if (block.seen[shardIndex]) return
            block.seen.set(shardIndex)
            lastProgress = now
            // Distinct trailing parity confirms sender progress even when this block
            // already has all data. Do not retain it or run a second recovery.
            if (block.complete) return
            if (shardIndex < block.dataCount) {
                val offset = ScreenFecLayout.dataOffset(blockIndex, shardIndex)
                fragment.payload.copyInto(bytes, offset, 0, minOf(fragment.payload.size, bytes.size - offset))
                block.dataReceived++; receivedData++
            } else {
                block.parity[shardIndex - block.dataCount] = fragment.payload
                block.parityReceived++
            }
            if (block.dataReceived < block.dataCount && block.dataReceived + block.parityReceived >= block.dataCount) {
                val decodingStarted = if (collectDiagnostics) System.nanoTime() else 0L
                val length = ScreenFecLayout.shardBytes(first.total)
                // At most 16 data copies + four parity/right-hand sides + four recovered shards.
                // WORK_BYTES is reserved outside frame storage before admitting any partial frame.
                val data = Array<ByteArray?>(block.dataCount) { index ->
                    if (!block.seen[index]) null else {
                        val offset = ScreenFecLayout.dataOffset(blockIndex, index)
                        bytes.copyOfRange(offset, minOf(bytes.size, offset + length)).copyOf(length)
                    }
                }
                val recovered = checkNotNull(ScreenReedSolomon.recover(data, block.parity, length))
                for ((index, shard) in recovered) {
                    val offset = ScreenFecLayout.dataOffset(blockIndex, index)
                    shard.copyInto(bytes, offset, 0, minOf(shard.size, bytes.size - offset))
                    block.seen.set(index)
                    block.dataReceived++; recoveredData++
                }
                if (recovered.isNotEmpty()) recoveredBlocks++
                if (collectDiagnostics) decodeNanos += (System.nanoTime() - decodingStarted).coerceAtLeast(0)
            }
            if (block.dataReceived == block.dataCount) {
                block.complete = true; completedBlocks++
                block.parity.fill(null) // Healthy or repaired blocks never retain redundant shards.
            }
        }

        fun missingData() = ScreenFecLayout.dataShards(first.total) - receivedData - recoveredData
        fun fec() = ScreenUdpTransport.Fec(ScreenFecLayout.dataShards(first.total), ScreenFecLayout.parityShards(first.total), recoveredData, recoveredBlocks, decodeNanos)
    }

    private val pending = TreeMap<Long, Partial>()
    private var expected = 0L
    private var retained = 0
    private var discontinuity = false
    private var independentFrame = false
    private var laterComplete = false
    private val lostAfter = minOf(expiryMillis, ScreenUdpTransport.LATER_COMPLETE_MILLIS)
    private var skippedMessages = 0L
    private var incompleteMessages = 0L
    private var missingFragments = 0L
    private var missingMessageTimeout = false
    private var fragmentTimeout = false
    private var frameDeadline = false
    private var maxAgeMillis = 0L
    private var maxNoProgressMillis = 0L
    private var capacityEvictions = 0L
    private var capacityRejectedFragments = 0L

    fun accept(fragment: ScreenUdpFragment, now: Long): List<ScreenUdpTransport.Message> {
        if (fragment.messageSequence < expected) return emptyList()
        val delivered = expire(now).toMutableList()
        if (fragment.messageSequence < expected) return delivered
        var partial = pending[fragment.messageSequence]
        if (partial == null) {
            // A one-data-shard message already fills this gap without retaining another frame.
            if (fragment.messageSequence == expected && ScreenFecLayout.dataShards(fragment.total) == 1 && fragment.index == 0) {
                delivered += message(expected, fragment.type, fragment.payload.copyOf(fragment.total), if (collectDiagnostics) ScreenUdpTransport.Fec(1, 1, 0, 0) else null)
                expected++
                delivered += expire(now)
                return delivered
            }
            val charge = ScreenFecLayout.reservedBytes(fragment.total)
            val evicted = ArrayList<Long>(maximumMessages)
            var retainedAfter = retained
            var countAfter = pending.size
            val candidates = pending.descendingMap().entries.iterator()
            while (countAfter >= maximumMessages || retainedAfter.toLong() + charge + WORK_BYTES > MAX_BYTES) {
                if (!candidates.hasNext()) {
                    capacityRejectedFragments = add(capacityRejectedFragments, 1)
                    return delivered
                }
                val furthest = candidates.next()
                if (fragment.messageSequence > furthest.key) {
                    capacityRejectedFragments = add(capacityRejectedFragments, 1)
                    return delivered
                }
                evicted += furthest.key
                retainedAfter -= furthest.value.reservation
                countAfter--
            }
            evicted.forEach(::remove)
            capacityEvictions = add(capacityEvictions, evicted.size.toLong())
            partial = Partial(fragment, now, collectDiagnostics)
            pending[fragment.messageSequence] = partial
            retained += partial.reservation
        }
        val first = partial.first
        if (first.total != fragment.total || first.type != fragment.type || first.count != fragment.count) return delivered
        partial.accept(fragment, now)
        if (partial.complete && fragment.messageSequence > expected && independentMessage?.invoke(first.type, partial.bytes) == true) {
            advanceToIndependent(fragment.messageSequence, now)
        }
        delivered += expire(now)
        return delivered
    }

    private fun advanceToIndependent(sequence: Long, now: Long) {
        // Classification is possible only after authentication and complete FEC assembly. Never
        // jump on a fragment/key flag alone; admission still obeys the same count/byte bounds.
        skippedMessages = add(skippedMessages, sequence - expected)
        val older = pending.headMap(sequence).keys.toList()
        for (id in older) {
            val partial = checkNotNull(pending[id])
            maxAgeMillis = maxOf(maxAgeMillis, now - partial.arrived)
            if (!partial.complete) {
                incompleteMessages = add(incompleteMessages, 1)
                missingFragments = add(missingFragments, partial.missingData().toLong())
                maxNoProgressMillis = maxOf(maxNoProgressMillis, now - partial.lastProgress)
            }
            remove(id)
        }
        expected = sequence; discontinuity = true; independentFrame = true
    }

    fun expire(now: Long): List<ScreenUdpTransport.Message> {
        val result = mutableListOf<ScreenUdpTransport.Message>()
        while (pending.isNotEmpty()) {
            val entry = pending.firstEntry()
            val id = entry.key
            val value = entry.value
            // Parity comes at the end of each block, so a whole later message means this one's is in too.
            val later = pending.tailMap(id, false).values.any { it.complete }
            if (id > expected) {
                val early = value.complete || later
                if (now - value.arrived < if (early) lostAfter else expiryMillis) break
                skippedMessages = add(skippedMessages, id - expected)
                missingMessageTimeout = missingMessageTimeout || now - value.arrived >= expiryMillis
                laterComplete = laterComplete || early && now - value.arrived < expiryMillis
                maxAgeMillis = maxOf(maxAgeMillis, now - value.arrived)
                expected = id; discontinuity = true
            }
            if (value.complete) {
                remove(id)
                result += message(id, value.first.type, value.bytes, if (collectDiagnostics) value.fec() else null)
                expected = id + 1
            } else if (now - value.lastProgress >= expiryMillis || now - value.arrived >= MAX_FRAME_MILLIS ||
                later && now - value.lastProgress >= lostAfter) {
                laterComplete = laterComplete || now - value.lastProgress < expiryMillis && now - value.arrived < MAX_FRAME_MILLIS
                skippedMessages = add(skippedMessages, 1)
                incompleteMessages = add(incompleteMessages, 1)
                missingFragments = add(missingFragments, value.missingData().toLong())
                fragmentTimeout = fragmentTimeout || now - value.lastProgress >= expiryMillis
                frameDeadline = frameDeadline || now - value.arrived >= MAX_FRAME_MILLIS
                maxAgeMillis = maxOf(maxAgeMillis, now - value.arrived)
                maxNoProgressMillis = maxOf(maxNoProgressMillis, now - value.lastProgress)
                remove(id); expected = id + 1; discontinuity = true
            } else break
        }
        return result
    }

    private fun message(sequence: Long, type: Int, bytes: ByteArray, fec: ScreenUdpTransport.Fec?): ScreenUdpTransport.Message {
        val gap = if (discontinuity) ScreenUdpTransport.Gap(
            skippedMessages, incompleteMessages, missingFragments, missingMessageTimeout, fragmentTimeout,
            frameDeadline, maxAgeMillis, maxNoProgressMillis, capacityEvictions, capacityRejectedFragments, independentFrame,
            laterComplete,
        ) else null
        val result = ScreenUdpTransport.Message(sequence, type, bytes, discontinuity, gap, fec)
        if (discontinuity) {
            discontinuity = false; independentFrame = false; laterComplete = false
            skippedMessages = 0; incompleteMessages = 0; missingFragments = 0
            missingMessageTimeout = false; fragmentTimeout = false; frameDeadline = false
            maxAgeMillis = 0; maxNoProgressMillis = 0
            capacityEvictions = 0; capacityRejectedFragments = 0
        }
        return result
    }
    private fun remove(id: Long) { pending.remove(id)?.let { retained -= it.reservation } }
    private fun add(value: Long, increment: Long) = if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment
    internal val retainedBytes get() = retained + if (pending.isEmpty()) 0 else WORK_BYTES
    internal val retainedMessages get() = pending.size
    companion object {
        const val MAX_MESSAGES = 4
        const val MAX_BYTES = 16 * 1024 * 1024
        const val MAX_FRAME_MILLIS = 2_000L
        private const val WORK_BYTES = 64 * 1024
    }
}
