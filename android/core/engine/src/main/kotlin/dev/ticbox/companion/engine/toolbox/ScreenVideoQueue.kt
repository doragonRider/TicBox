package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.ToolboxScreenEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * One non-blocking producer and one decoder consumer. A short, correctly ordered receive burst
 * must not itself discard a GOP. Admission age and encoded bytes bound the pending backlog; the
 * count does only for a backlog that has also waited, or for a flood. A sender that falls behind
 * catches up in one burst of fresh, small frames, and the decoder gets through those well within
 * the age budget, whereas discarding them would freeze the picture until a new keyframe.
 */
internal class ScreenVideoQueue(
    private val maximumBatches: Int = capacityForFps(20),
    private val maximumBytes: Long = DEFAULT_MAXIMUM_BYTES,
    private val maximumAgeNanos: Long = DEFAULT_MAXIMUM_AGE_MILLIS * 1_000_000,
    private val clockNanos: () -> Long = System::nanoTime,
    private val onDequeued: ((Dequeued) -> Unit)? = null,
) {
    enum class Rejection(val diagnosticReason: String) {
        COUNT("queue_count_limit"), BYTES("queue_byte_limit"), AGE("queue_age_limit"), CLOSED("closed"),
    }
    /** Captured with the rejection, before a concurrent decoder may remove queued frames. */
    data class RejectionSnapshot(
        val reason: Rejection,
        val queuedBatches: Int,
        val queuedBytes: Long,
        val oldestAgeMillis: Long,
        val queuedPtsSpanMillis: Long,
        val incomingBytes: Long,
        val maximumBatches: Int,
    ) {
        fun diagnosticDetails(): Map<String, String> = mapOf(
            "queuedBatches" to queuedBatches.toString(), "queuedBytes" to queuedBytes.toString(),
            "oldestQueuedAgeMillis" to oldestAgeMillis.toString(), "queuedPtsSpanMillis" to queuedPtsSpanMillis.toString(),
            "incomingBytes" to incomingBytes.toString(), "maximumBatches" to maximumBatches.toString(),
        )
    }
    /** Queue residence ends before decoder work begins; this includes consumer scheduling delay. */
    data class Dequeued(val residenceMillis: Long, val batchBytes: Long, val remainingBatches: Int, val remainingBytes: Long)
    data class Discarded(val batches: Int, val deltas: Int, val bytes: Long)
    private data class Batch(val events: List<ToolboxScreenEvent>, val bytes: Long, val arrived: Long)
    private sealed interface Next {
        data class Prepare(val configuration: ToolboxScreenEvent.Configuration) : Next
        data class Value(val events: List<ToolboxScreenEvent>, val observation: Dequeued?) : Next
        data class End(val failure: Throwable?) : Next
        data object Wait : Next
    }
    private val lock = Any()
    private val pending = ArrayDeque<Batch>()
    private val ready = Channel<Unit>(Channel.CONFLATED)
    private var preparation: ToolboxScreenEvent.Configuration? = null
    private var encodedBytes = 0L
    private var closed = false
    private var failure: Throwable? = null

    init { require(maximumBatches in 1..MAXIMUM_BATCHES_LIMIT && maximumBytes > 0 && maximumAgeNanos > 0) }
    private val floodBatches = (maximumBatches * BURST_FACTOR).coerceAtMost(MAXIMUM_BURST_BATCHES)
    private val countAgeNanos = maximumAgeNanos / 2

    /** One latest metadata slot, independent of encoded queue age and reference-chain clearing. */
    fun prepare(configuration: ToolboxScreenEvent.Configuration) = synchronized(lock) {
        if (!closed) { preparation = configuration; ready.trySend(Unit) }
    }

    /** Accepted batches transfer their immutable event/byte ownership to this queue. */
    fun offer(events: List<ToolboxScreenEvent>, nowNanos: Long = clockNanos()): RejectionSnapshot? {
        require(events.isNotEmpty() && events.all { it is ToolboxScreenEvent.Configuration || it is ToolboxScreenEvent.Frame })
        val bytes = events.sumOf { (it as? ToolboxScreenEvent.Frame)?.bytes?.size?.toLong() ?: 0 }
        synchronized(lock) {
            if (closed) return rejectionLocked(Rejection.CLOSED, bytes, nowNanos)
            // Only new arrivals judge backlog age. A final static image remains useful even if
            // the decoder took longer to become available; the consumer never drops a reference.
            if (pending.firstOrNull()?.let { nowNanos - it.arrived >= maximumAgeNanos } == true) return rejectionLocked(Rejection.AGE, bytes, nowNanos)
            if (pending.size >= floodBatches || pending.size >= maximumBatches &&
                pending.first().let { nowNanos - it.arrived >= countAgeNanos }) return rejectionLocked(Rejection.COUNT, bytes, nowNanos)
            if (bytes > maximumBytes - encodedBytes) return rejectionLocked(Rejection.BYTES, bytes, nowNanos)
            pending.addLast(Batch(events.toList(), bytes, nowNanos))
            encodedBytes += bytes
            ready.trySend(Unit)
            return null
        }
    }

    private fun rejectionLocked(reason: Rejection, incomingBytes: Long, nowNanos: Long): RejectionSnapshot {
        var firstPts: Long? = null
        var lastPts: Long? = null
        for (batch in pending) for (event in batch.events) {
            if (event is ToolboxScreenEvent.Frame && !event.codecConfig) {
                firstPts = firstPts?.let { minOf(it, event.ptsMicros) } ?: event.ptsMicros
                lastPts = lastPts?.let { maxOf(it, event.ptsMicros) } ?: event.ptsMicros
            }
        }
        val span = if (firstPts != null && lastPts != null) (lastPts - firstPts).coerceAtLeast(0) / 1_000 else 0L
        return RejectionSnapshot(reason, pending.size, encodedBytes,
            pending.firstOrNull()?.let { (nowNanos - it.arrived).coerceAtLeast(0) / 1_000_000 } ?: 0L,
            span, incomingBytes, maximumBatches)
    }

    val events: Flow<ToolboxScreenEvent> = flow {
        while (true) {
            when (val next = synchronized(lock) {
                preparation?.let { preparation = null; Next.Prepare(it) } ?: pending.removeFirstOrNull()?.let {
                    encodedBytes -= it.bytes
                    Next.Value(it.events, if (onDequeued != null) Dequeued((clockNanos() - it.arrived).coerceAtLeast(0) / 1_000_000,
                        it.bytes, pending.size, encodedBytes) else null)
                } ?: if (closed) Next.End(failure) else Next.Wait
            }) {
                is Next.Prepare -> emit(next.configuration)
                is Next.Value -> {
                    // Observers may enqueue diagnostics, but never run under the producer's lock.
                    next.observation?.let { observation -> runCatching { onDequeued?.invoke(observation) } }
                    next.events.forEach { emit(it) }
                }
                is Next.End -> { next.failure?.let { throw it }; return@flow }
                Next.Wait -> ready.receiveCatching()
            }
        }
    }

    /** Only the producer calls this to discard an entire broken dependency chain. */
    fun clear(): Discarded = synchronized(lock) { discardLocked() }
    private fun discardLocked(): Discarded {
        val result = Discarded(pending.size,
            pending.sumOf { batch -> batch.events.count { it is ToolboxScreenEvent.Frame && !it.codecConfig && !it.keyFrame } }, encodedBytes)
        pending.clear(); encodedBytes = 0
        return result
    }
    /** Preserve Channel semantics: deliver already accepted batches, then the terminal failure. */
    fun close(error: Throwable? = null) = synchronized(lock) {
        if (!closed) { closed = true; failure = error; ready.close() }
    }
    fun cancel() = synchronized(lock) {
        preparation = null; discardLocked(); closed = true; failure = CancellationException("screen_video_queue_cancelled"); ready.close()
        Unit
    }
    internal val queuedBatches: Int get() = synchronized(lock) { pending.size }
    internal val queuedBytes: Long get() = synchronized(lock) { encodedBytes }

    companion object {
        const val DEFAULT_MAXIMUM_BYTES = 16L * 1024 * 1024
        const val DEFAULT_MAXIMUM_AGE_MILLIS = 150L
        private const val MAXIMUM_BATCHES_LIMIT = 32
        private const val BURST_FACTOR = 4
        private const val MAXIMUM_BURST_BATCHES = 128
        // Allow a small arrival burst in addition to the frames that fit the age budget.
        private const val REASSEMBLY_BURST_ALLOWANCE = 5
        /** A complete gap filler can unlock the retained reorder window plus itself at once. */
        fun reassemblyCapacityForFps(maxFps: Int): Int = capacityForFps(maxFps) - 1
        fun capacityForFps(maxFps: Int): Int {
            require(maxFps in 1..120)
            val framesWithinAge = (maxFps * DEFAULT_MAXIMUM_AGE_MILLIS + 999) / 1_000
            return (framesWithinAge.toInt() + REASSEMBLY_BURST_ALLOWANCE).coerceIn(8, MAXIMUM_BATCHES_LIMIT)
        }
    }
}
