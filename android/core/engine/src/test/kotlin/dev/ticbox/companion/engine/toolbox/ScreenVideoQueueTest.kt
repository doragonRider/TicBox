package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.ToolboxScreenEvent
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ScreenVideoQueueTest {
    @Test fun disabledQueueDiagnosticsDoNotSampleTheConsumerClock() = runBlocking {
        val queue = ScreenVideoQueue(clockNanos = { error("diagnostic residence clock must stay unused") })
        assertNull(queue.offer(batch(1), nowNanos = 0))
        queue.close()
        assertEquals(1L, (queue.events.single() as ToolboxScreenEvent.Frame).ptsMicros)
    }

    @Test fun completeReassemblyWindowAndItsGapFillerFitTheDecoderQueue() = runBlocking {
        for ((fps, reassembly) in listOf(20 to 7, 60 to 13, 120 to 22)) {
            assertEquals(reassembly, ScreenVideoQueue.reassemblyCapacityForFps(fps))
            val queue = ScreenVideoQueue(maximumBatches = ScreenVideoQueue.capacityForFps(fps), clockNanos = { 0 })
            repeat(reassembly + 1) { assertNull(queue.offer(batch(it.toLong()))) }
            assertEquals(ScreenVideoQueue.Rejection.COUNT, queue.offer(batch(999), nowNanos = 75_000_000)?.reason)
            queue.close()
            assertEquals(reassembly + 1, queue.events.toList().size)
        }
    }

    @Test fun `eight ordered video batches fit without dropping a dependency and dequeue releases their bytes`() = runBlocking {
        val queue = ScreenVideoQueue(clockNanos = { 0 })
        repeat(8) { assertNull(queue.offer(batch(it.toLong(), bytes = 100))) }
        assertEquals(8, queue.queuedBatches)
        assertEquals(800L, queue.queuedBytes)
        assertEquals(ScreenVideoQueue.Rejection.COUNT, queue.offer(batch(8), nowNanos = 75_000_000)?.reason)
        assertEquals(8, queue.queuedBatches)
        assertEquals(0L, (queue.events.first() as ToolboxScreenEvent.Frame).ptsMicros)
        assertEquals(7, queue.queuedBatches)
        assertEquals(700L, queue.queuedBytes)
        assertNull(queue.offer(batch(8, bytes = 100)))
        queue.close()
        assertEquals((1L..8).toList(), queue.events.toList().map { (it as ToolboxScreenEvent.Frame).ptsMicros })
        assertEquals(0L, queue.queuedBytes)
    }

    @Test fun `the default encoded byte cap stays at sixteen MiB even though more batches now fit`() {
        val queue = ScreenVideoQueue(clockNanos = { 0 })
        assertNull(queue.offer(batch(0, bytes = 8 * 1024 * 1024)))
        assertNull(queue.offer(batch(1, bytes = 8 * 1024 * 1024)))
        assertEquals(16L * 1024 * 1024, queue.queuedBytes)
        assertEquals(ScreenVideoQueue.Rejection.BYTES, queue.offer(batch(2))?.reason)
        assertEquals(2, queue.queuedBatches)
        val removed = queue.clear()
        assertEquals(2, removed.batches)
        assertEquals(2, removed.deltas)
        assertEquals(16L * 1024 * 1024, removed.bytes)
        assertEquals(0L, queue.queuedBytes)
        queue.cancel()
    }

    @Test fun `codec initialization bytes count toward admission and clearing identifies only actual delta frames`() {
        val queue = ScreenVideoQueue(maximumBytes = 12, clockNanos = { 0 })
        val key = listOf(
            ToolboxScreenEvent.Configuration("h265", 480, 480),
            ToolboxScreenEvent.Frame(0, false, true, ByteArray(4)),
            ToolboxScreenEvent.Frame(1, true, false, ByteArray(8)),
        )
        assertNull(queue.offer(key))
        assertEquals(12L, queue.queuedBytes)
        assertEquals(ScreenVideoQueue.Rejection.BYTES, queue.offer(batch(2))?.reason)
        assertEquals(ScreenVideoQueue.Discarded(1, 0, 12), queue.clear())
        queue.cancel()
    }

    @Test fun `age is checked on new admission and does not silently remove reference frames`() = runBlocking {
        var now = 0L
        val queue = ScreenVideoQueue(clockNanos = { now })
        assertNull(queue.offer(batch(0)))
        now = 149_000_000
        assertNull(queue.offer(batch(1)))
        now = 150_000_000
        assertEquals(ScreenVideoQueue.Rejection.AGE, queue.offer(batch(2))?.reason)
        assertEquals(2, queue.queuedBatches)
        queue.close()
        assertEquals(listOf(0L, 1L), queue.events.toList().map { (it as ToolboxScreenEvent.Frame).ptsMicros })
    }

    @Test fun `a final static picture stays available after the age budget without another arrival`() = runBlocking {
        var now = 0L
        val queue = ScreenVideoQueue(clockNanos = { now })
        assertNull(queue.offer(batch(1)))
        now = 10_000_000_000
        queue.close()
        assertEquals(1L, (queue.events.single() as ToolboxScreenEvent.Frame).ptsMicros)
        assertEquals(0L, queue.queuedBytes)
    }

    @Test fun `error close drains accepted data then fails while cancellation discards data and wakes a waiter`() = runBlocking { withTimeout(2_000) {
        val queue = ScreenVideoQueue(clockNanos = { 0 })
        queue.offer(batch(1))
        val failure = IOException("fixture")
        queue.close(failure)
        val observed = mutableListOf<ToolboxScreenEvent>()
        assertSame(failure, assertFailsWith<IOException> { queue.events.collect { observed += it } })
        assertEquals(1, observed.size)
        assertEquals(ScreenVideoQueue.Rejection.CLOSED, queue.offer(batch(2))?.reason)
        assertEquals(0L, queue.queuedBytes)
        val waiting = ScreenVideoQueue()
        val consumer = async { assertFailsWith<CancellationException> { waiting.events.toList() } }
        yield()
        waiting.cancel()
        consumer.await()
        val discarded = ScreenVideoQueue()
        discarded.offer(batch(2))
        discarded.cancel()
        assertEquals(0, discarded.queuedBatches)
        assertEquals(0L, discarded.queuedBytes)
        assertFailsWith<CancellationException> { discarded.events.toList() }
    } }

    @Test fun capacityMatchesFrameRateAndAdmissionWindowWithAFiniteBurstAllowance() {
        for ((fps, count) in listOf(1 to 8, 20 to 8, 30 to 10, 60 to 14, 90 to 19, 120 to 23)) {
            assertEquals(count, ScreenVideoQueue.capacityForFps(fps))
        }
        assertTrue((1..120).all { ScreenVideoQueue.capacityForFps(it) in 8..32 })
        assertFailsWith<IllegalArgumentException> { ScreenVideoQueue.capacityForFps(0) }
        assertFailsWith<IllegalArgumentException> { ScreenVideoQueue.capacityForFps(121) }
        assertFailsWith<IllegalArgumentException> { ScreenVideoQueue(maximumBatches = 33) }
    }

    @Test fun reportedEightSmallFramesAndEighteenFrameHighRateBacklogFitWithinTheExistingAgeBudget() = runBlocking {
        var now = 0L
        val queue = ScreenVideoQueue(maximumBatches = ScreenVideoQueue.capacityForFps(120), clockNanos = { now })
        repeat(8) { index ->
            now = index * 8_000_000L
            assertNull(queue.offer(batch(index * 8_333L, bytes = if (index == 7) 752 else 746)))
        }
        assertEquals(5_974L, queue.queuedBytes)
        now = 64_000_000
        assertNull(queue.offer(batch(8 * 8_333L, bytes = 746)))
        for (index in 9..17) {
            now = index * 8_000_000L
            assertNull(queue.offer(batch(index * 8_333L, bytes = 746)))
        }
        assertEquals(18, queue.queuedBatches)
        assertTrue(now < 150_000_000)
        queue.close()
        assertEquals((0..17).map { it * 8_333L }, queue.events.toList().map { (it as ToolboxScreenEvent.Frame).ptsMicros })
        assertEquals(0L, queue.queuedBytes)
    }

    @Test fun highRateCapacityDoesNotRelaxTheAgeOrByteBudgets() {
        var now = 0L
        val queue = ScreenVideoQueue(maximumBatches = ScreenVideoQueue.capacityForFps(120), clockNanos = { now })
        repeat(23) { assertNull(queue.offer(batch(it.toLong()))) }
        val count = assertNotNull(queue.offer(batch(24), nowNanos = 75_000_000))
        assertEquals(ScreenVideoQueue.Rejection.COUNT, count.reason)
        assertEquals(23, count.maximumBatches)
        queue.clear()
        assertNull(queue.offer(batch(30, bytes = 8 * 1024 * 1024)))
        assertNull(queue.offer(batch(31, bytes = 8 * 1024 * 1024)))
        assertEquals(ScreenVideoQueue.Rejection.BYTES, queue.offer(batch(32))?.reason)
        queue.clear()
        assertNull(queue.offer(batch(40)))
        now = 150_000_000
        val age = assertNotNull(queue.offer(batch(41)))
        assertEquals(ScreenVideoQueue.Rejection.AGE, age.reason)
        assertEquals(1, age.queuedBatches)
        assertEquals(150L, age.oldestAgeMillis)
        queue.cancel()
    }

    @Test fun aFreshCatchUpBurstPassesTheCountWhileAWaitingBacklogOrAFloodDoesNot() {
        var now = 0L
        val queue = ScreenVideoQueue(maximumBatches = ScreenVideoQueue.capacityForFps(120), clockNanos = { now })
        // The 04 log: 23 queued batches, 38 KB in all, the oldest 15 ms old, 181 ms of pictures.
        repeat(23) { now = it * 650_000L; assertNull(queue.offer(batch(it * 8_333L, bytes = 1_690))) }
        now = 15_000_000
        assertNull(queue.offer(batch(23 * 8_333L, bytes = 998)))
        repeat(23 * 4 - 24) { assertNull(queue.offer(batch(1_000_000L + it))) }
        assertEquals(92, queue.queuedBatches)
        assertEquals(ScreenVideoQueue.Rejection.COUNT, queue.offer(batch(2_000_000))?.reason, "a flood still stops")
        queue.clear()
        repeat(23) { assertNull(queue.offer(batch(it.toLong()), nowNanos = 0)) }
        now = 75_000_000
        assertEquals(ScreenVideoQueue.Rejection.COUNT, queue.offer(batch(99))?.reason, "a backlog that waited half the age budget")
        queue.cancel()
    }

    @Test fun rejectionSnapshotSurvivesConcurrentDequeueAndRecordsSourcePtsSeparatelyFromLocalAge() = runBlocking {
        var now = 0L
        val queue = ScreenVideoQueue(maximumBatches = 2, clockNanos = { now })
        queue.offer(batch(10_000, bytes = 100))
        now = 30_000_000
        queue.offer(batch(75_000, bytes = 200))
        now = 97_000_000
        val snapshot = assertNotNull(queue.offer(batch(90_000, bytes = 300)))
        assertEquals(ScreenVideoQueue.RejectionSnapshot(ScreenVideoQueue.Rejection.COUNT, 2, 300, 97, 65, 300, 2), snapshot)
        assertEquals(10_000L, (queue.events.first() as ToolboxScreenEvent.Frame).ptsMicros)
        assertEquals(1, queue.queuedBatches)
        assertEquals(200L, queue.queuedBytes)
        assertEquals("2", snapshot.diagnosticDetails()["queuedBatches"])
        assertEquals("300", snapshot.diagnosticDetails()["queuedBytes"])
        assertEquals("97", snapshot.diagnosticDetails()["oldestQueuedAgeMillis"])
        assertEquals("65", snapshot.diagnosticDetails()["queuedPtsSpanMillis"])
        assertEquals("300", snapshot.diagnosticDetails()["incomingBytes"])
        queue.cancel()
    }

    @Test fun dequeueObservationRunsOutsideTheProducerLockAndFailuresDoNotLoseFrames() = runBlocking { withTimeout(3_000) {
        var now = 0L
        val observed = mutableListOf<ScreenVideoQueue.Dequeued>()
        val callbackFailure = AtomicReference<Throwable?>()
        val producer = Executors.newSingleThreadExecutor { Thread(it, "screen-queue-observer-test").apply { isDaemon = true } }
        lateinit var queue: ScreenVideoQueue
        try {
            queue = ScreenVideoQueue(clockNanos = { now }, onDequeued = { sample ->
                observed += sample
                try {
                    // This would time out if the callback retained the queue's producer lock.
                    producer.submit<ScreenVideoQueue.RejectionSnapshot?> { queue.offer(batch(2, bytes = 20)) }.get(1, TimeUnit.SECONDS)
                } catch (failure: Throwable) { callbackFailure.set(failure) }
            })
            queue.offer(batch(1, bytes = 10))
            now = 40_000_000
            assertEquals(1L, (queue.events.first() as ToolboxScreenEvent.Frame).ptsMicros)
            assertNull(callbackFailure.get())
            assertEquals(listOf(ScreenVideoQueue.Dequeued(40, 10, 0, 0)), observed)
            assertEquals(1, queue.queuedBatches)
            assertEquals(20L, queue.queuedBytes)
            queue.cancel()
        } finally { producer.shutdownNow() }
        val broken = ScreenVideoQueue(clockNanos = { 0 }, onDequeued = { error("observer fixture") })
        broken.offer(batch(3))
        broken.close()
        assertEquals(3L, (broken.events.single() as ToolboxScreenEvent.Frame).ptsMicros)
    } }

    private fun batch(id: Long, bytes: Int = 1): List<ToolboxScreenEvent> = listOf(ToolboxScreenEvent.Frame(id, false, false, ByteArray(bytes)))
}
