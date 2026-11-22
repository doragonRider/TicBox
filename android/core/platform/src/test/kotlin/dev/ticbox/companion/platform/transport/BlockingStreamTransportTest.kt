package dev.ticbox.companion.platform.transport

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalCoroutinesApi::class)
class BlockingStreamTransportTest {
    @Test
    fun streamReadsRetainChunksAndEofWithoutInventingRecords() = runBlocking {
        val releases = AtomicInteger()
        val transport = BlockingStreamTransport(
            ByteArrayInputStream(byteArrayOf(1, 2, 3)),
            ByteArrayOutputStream(),
            releases::incrementAndGet,
            readBufferBytes = 2,
        )
        try {
            assertContentEquals(byteArrayOf(1, 2), transport.read())
            assertContentEquals(byteArrayOf(3), transport.read())
            assertEquals(null, transport.read())
        } finally {
            transport.close()
        }
        transport.close()
        assertEquals(1, releases.get())
    }

    @Test
    fun cancelingBlockedReadClosesOwnerAndUnblocksTheWorker(): Unit = runBlocking {
        val input = WaitingInput()
        val output = CountingOutput()
        val releases = AtomicInteger()
        val transport = BlockingStreamTransport(input, output, releases::incrementAndGet)
        val read = async(Dispatchers.Default) { transport.read() }
        try {
            assertTrue(input.entered.await(3, TimeUnit.SECONDS))
            withTimeout(3_000) { read.cancelAndJoin() }
            assertTrue(input.closed.get())
            assertEquals(1, output.closes.get())
            assertEquals(1, releases.get())
            assertFailsWith<IOException> { transport.read() }
        } finally {
            transport.close()
            read.cancelAndJoin()
        }
    }

    @Test
    fun closeDoesNotWaitForAWriteToCompleteAndIsIdempotent() = runBlocking {
        val output = WaitingOutput()
        val releases = AtomicInteger()
        val transport = BlockingStreamTransport(ByteArrayInputStream(byteArrayOf()), output, releases::incrementAndGet)
        val write = async(Dispatchers.Default) {
            runCatching { transport.write(byteArrayOf(1, 2, 3)) }
        }
        try {
            assertTrue(output.entered.await(3, TimeUnit.SECONDS))
            withTimeout(3_000) { async(Dispatchers.Default) { transport.close() }.await() }
            assertTrue(withTimeout(3_000) { write.await() }.isFailure)
            transport.close()
            assertEquals(1, releases.get())
            assertEquals(1, output.closes.get())
        } finally {
            transport.close()
            output.gate.countDown()
            write.cancelAndJoin()
        }
    }

    @Test
    fun cancellationWhileWaitingForWriteOwnershipDoesNotCloseAnotherActiveWrite() = runBlocking {
        val output = WaitingOutput()
        val releases = AtomicInteger()
        val transport = BlockingStreamTransport(ByteArrayInputStream(byteArrayOf()), output, releases::incrementAndGet)
        val first = async(Dispatchers.Default) { transport.write(byteArrayOf(1, 2, 3)) }
        try {
            assertTrue(output.entered.await(3, TimeUnit.SECONDS))
            val queued = async(start = CoroutineStart.UNDISPATCHED) { transport.write(byteArrayOf(4)) }
            withTimeout(3_000) { queued.cancelAndJoin() }
            assertEquals(0, releases.get())
            assertEquals(0, output.closes.get())
            output.gate.countDown()
            withTimeout(3_000) { first.await() }
            assertContentEquals(byteArrayOf(1, 2, 3), output.received.toByteArray())
        } finally {
            transport.close()
            output.gate.countDown()
            first.cancelAndJoin()
        }
    }

    @Test
    fun concurrentWritesCannotInterleaveTheBytesOfDifferentChunks() = runBlocking {
        val bytes = ConcurrentLinkedQueue<Int>()
        val output = object : OutputStream() {
            override fun write(value: Int) {
                bytes.add(value and 255)
                Thread.yield()
            }
        }
        val transport = BlockingStreamTransport(ByteArrayInputStream(byteArrayOf()), output, {})
        try {
            withTimeout(5_000) {
                (0 until 32).map { value ->
                    launch(Dispatchers.Default) { transport.write(ByteArray(16) { value.toByte() }) }
                }.joinAll()
            }
            assertEquals(512, bytes.size)
            val chunks = bytes.toList().chunked(16)
            assertEquals((0 until 32).toSet(), chunks.map { it.first() }.toSet())
            assertTrue(chunks.all { chunk -> chunk.all { it == chunk.first() } })
        } finally {
            transport.close()
        }
    }

    @Test
    fun aSecondReaderIsRefusedAndCleanupStillReleasesTheFirst() = runBlocking {
        val input = WaitingInput()
        val transport = BlockingStreamTransport(input, CountingOutput(), {})
        // The first reader runs outside this test's scope: closing may finish it with either
        // cancellation or IOException("closed"), depending on which thread wins the race.
        val first = CoroutineScope(Dispatchers.Default).async { transport.read() }
        try {
            assertTrue(input.entered.await(3, TimeUnit.SECONDS))
            assertFailsWith<IllegalStateException> { transport.read() }
        } finally {
            transport.close()
            first.cancel()
            runCatching { first.await() }
        }
        assertTrue(input.closed.get())
    }

    @Test
    fun allResourcesAreAttemptedEvenWhenOwnerCloseThrows() {
        val inputClosed = AtomicBoolean()
        val output = CountingOutput()
        val input = object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() { inputClosed.set(true) }
        }
        val transport = BlockingStreamTransport(input, output, { throw IOException("close failed") })
        transport.close()
        assertTrue(inputClosed.get())
        assertEquals(1, output.closes.get())
    }

    @Test
    fun cancellationAfterAcquisitionButBeforeDeliveryDisposesTheUndeliveredResource() = runTest {
        val queue = ManualDispatcher()
        val closed = AtomicBoolean()
        val closes = AtomicInteger()
        val resource = AutoCloseable {
            if (closed.compareAndSet(false, true)) closes.incrementAndGet()
        }
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            cancellableIo(queue, onCancel = resource::close, onDiscard = AutoCloseable::close) { resource }
        }
        queue.runOne() // Acquisition succeeds, but delivery to the test dispatcher is still queued.
        result.cancel()
        runCurrent()
        assertTrue(result.isCancelled)
        assertEquals(1, closes.get())
    }

    @Test
    fun throwingCancellationCleanupDoesNotResumeTheContinuationTwice() = runTest {
        val queue = ManualDispatcher()
        val discardCalls = AtomicInteger()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            cancellableIo(
                queue,
                onCancel = { throw IOException("owner cleanup failed") },
                onDiscard = { _: String ->
                    discardCalls.incrementAndGet()
                    throw IOException("late cleanup failed")
                },
            ) { "resource" }
        }
        queue.runOne()
        result.cancel()
        runCurrent()
        assertTrue(result.isCancelled)
        assertEquals(1, discardCalls.get())
    }

    @Test
    fun dispatcherRejectionReleasesOwnershipWithoutRunningIo() = runBlocking {
        val releases = AtomicInteger()
        val rejecting = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                throw RejectedExecutionException("unavailable")
            }
        }
        assertFailsWith<RejectedExecutionException> {
            cancellableIo(rejecting, onCancel = { releases.incrementAndGet() }) {
                error("Rejected work must never run")
            }
        }
        assertEquals(1, releases.get())
    }

    private class ManualDispatcher : CoroutineDispatcher() {
        private val work = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { work.addLast(block) }
        fun runOne() { work.removeFirst().run() }
    }

    private class WaitingInput : InputStream() {
        val entered = CountDownLatch(1)
        val closed = AtomicBoolean()
        private val gate = CountDownLatch(1)
        override fun read(): Int {
            entered.countDown()
            check(gate.await(5, TimeUnit.SECONDS)) { "Test input was never released" }
            if (closed.get()) throw IOException("closed")
            return -1
        }
        override fun close() { closed.set(true); gate.countDown() }
    }

    private class WaitingOutput : OutputStream() {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val closes = AtomicInteger()
        val received = ByteArrayOutputStream()
        override fun write(value: Int) { write(byteArrayOf(value.toByte())) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            entered.countDown()
            check(gate.await(5, TimeUnit.SECONDS)) { "Test output was never released" }
            if (closes.get() > 0) throw IOException("closed")
            received.write(bytes, offset, length)
        }
        override fun close() { closes.incrementAndGet(); gate.countDown() }
    }

    private class CountingOutput : OutputStream() {
        val closes = AtomicInteger()
        override fun write(value: Int) = Unit
        override fun close() { closes.incrementAndGet() }
    }
}
