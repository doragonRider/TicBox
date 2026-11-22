package dev.ticbox.companion.platform.transport

import dev.ticbox.companion.api.*
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WearChannelStreamsTest {
    @Test fun readsRespectOffsetsPartialChunksUnsignedBytesAndEof() {
        val channel = TestChannel()
        val streams = WearChannelStreams(channel, chunkBytes = 2)
        try {
            channel.feed(byteArrayOf(1, 2, 3, -1))
            channel.finishIncoming()
            val target = ByteArray(6) { 9 }
            assertEquals(2, streams.input.read(target, 2, 4))
            assertContentEquals(byteArrayOf(9, 9, 1, 2, 9, 9), target)
            assertEquals(3, streams.input.read())
            assertEquals(255, streams.input.read())
            assertEquals(-1, streams.input.read())
            assertEquals(0, streams.input.read(target, 0, 0))
            assertFailsWith<IndexOutOfBoundsException> { streams.input.read(target, Int.MAX_VALUE, 2) }
        } finally { streams.close() }
    }

    @Test fun writesOnlySelectedRangesAndUsesBoundedChunksWithoutSyntheticFlushData() {
        val channel = TestChannel()
        val streams = WearChannelStreams(channel, chunkBytes = 3)
        try {
            streams.output.write(byteArrayOf(9, 1, 2, 3, 4, 5, 6, 7, 9), 1, 7)
            streams.output.write(-1)
            streams.output.write(byteArrayOf())
            streams.output.flush()
            assertEquals(listOf(listOf<Byte>(1, 2, 3), listOf<Byte>(4, 5, 6), listOf<Byte>(7), listOf<Byte>(-1)), channel.writes.map { it.toList() })
            assertEquals(0, channel.finishes.get())
            assertFailsWith<IndexOutOfBoundsException> { streams.output.write(byteArrayOf(1), -1, 1) }
        } finally { streams.close() }
    }

    @Test fun outputEofDoesNotCloseInputOrTheWholeChannel() {
        val channel = TestChannel()
        val streams = WearChannelStreams(channel)
        try {
            streams.output.close()
            streams.output.close()
            assertEquals(1, channel.finishes.get())
            assertEquals(0, channel.closes.get())
            channel.feed(byteArrayOf(42))
            assertEquals(42, streams.input.read())
            assertFailsWith<IOException> { streams.output.write(1) }
        } finally { streams.close() }
    }

    @Test fun inputCloseWakesItsBlockedReaderWhileOutputRemainsUsable() = runBlocking {
        val channel = TestChannel()
        val streams = WearChannelStreams(channel)
        val pending = async(Dispatchers.IO) { runCatching { streams.input.read() } }
        try {
            assertTrue(channel.readStarted.await(3, TimeUnit.SECONDS))
            streams.input.close()
            assertTrue(withTimeout(3_000) { pending.await() }.isFailure)
            assertEquals(1, channel.inputCloses.get())
            streams.output.write(7)
            assertContentEquals(byteArrayOf(7), channel.writes.single())
            assertEquals(0, channel.closes.get())
        } finally { streams.close(); pending.cancelAndJoin() }
    }

    @Test fun wholeAdapterCloseDoesNotWaitForPeerAckOrStreamLocks(): Unit = runBlocking {
        val channel = TestChannel().apply { writeGate = CompletableDeferred() }
        val streams = WearChannelStreams(channel)
        val pending = async(Dispatchers.IO) { runCatching { streams.output.write(byteArrayOf(1, 2)) } }
        try {
            assertTrue(channel.writeStarted.await(3, TimeUnit.SECONDS))
            withTimeout(3_000) { async(Dispatchers.Default) { streams.close() }.await() }
            assertTrue(withTimeout(3_000) { pending.await() }.isFailure)
            streams.close()
            assertEquals(1, channel.closes.get())
            assertFailsWith<IOException> { streams.input.read() }
            assertFailsWith<IOException> { streams.output.write(1) }
        } finally { streams.close(); pending.cancelAndJoin() }
    }

    @Test fun concurrentJavaWritesDoNotInterleaveTheirChunks() = runBlocking {
        val channel = TestChannel().apply { writeDelayMillis = 1 }
        val streams = WearChannelStreams(channel, chunkBytes = 2)
        try {
            withTimeout(5_000) {
                (1..8).map { value -> launch(Dispatchers.IO) { streams.output.write(ByteArray(6) { value.toByte() }) } }.joinAll()
            }
            val writes = channel.writes.toList()
            assertEquals(24, writes.size)
            val groups = writes.chunked(3)
            assertEquals((1..8).toSet(), groups.map { it.first().first().toInt() }.toSet())
            assertTrue(groups.all { group -> group.flatMap { it.toList() }.distinct().size == 1 })
        } finally { streams.close() }
    }

    @Test fun interruptedBlockingIoRestoresTheFlagAndDisposesTheLogicalChannel() {
        val channel = TestChannel().apply { writeGate = CompletableDeferred() }
        val streams = WearChannelStreams(channel)
        val thrown = AtomicReference<Throwable>()
        val interruptRestored = AtomicBoolean(false)
        val thread = Thread {
            try { streams.output.write(1) }
            catch (failure: Throwable) {
                thrown.set(failure)
                interruptRestored.set(Thread.currentThread().isInterrupted)
            }
        }
        try {
            thread.start()
            assertTrue(channel.writeStarted.await(3, TimeUnit.SECONDS))
            thread.interrupt()
            thread.join(3_000)
            assertFalse(thread.isAlive)
            assertIs<InterruptedIOException>(thrown.get())
            assertTrue(interruptRestored.get())
            assertEquals(1, channel.closes.get())
        } finally { streams.close(); thread.interrupt(); thread.join(3_000) }
    }

    private class TestChannel : WearChannel {
        override val info = ChannelInfo(PeerAddress("AA:BB:CC:DD:EE:FF"), 1, 10uL, true, "/cdm/channel/permission_sync")
        override val state = MutableStateFlow(ChannelState(ChannelPhase.OPEN))
        private val incoming = Channel<ByteArray>(Channel.UNLIMITED) // Finite synthetic test data only.
        private val readLock = Mutex()
        private var unread = byteArrayOf()
        private var position = 0
        val readStarted = CountDownLatch(1)
        val writeStarted = CountDownLatch(1)
        val writes = ConcurrentLinkedQueue<ByteArray>()
        val finishes = AtomicInteger()
        val inputCloses = AtomicInteger()
        val closes = AtomicInteger()
        var writeGate: CompletableDeferred<Unit>? = null
        var writeDelayMillis = 0L
        fun feed(bytes: ByteArray) { check(incoming.trySend(bytes.copyOf()).isSuccess) }
        fun finishIncoming() { incoming.close() }
        override suspend fun read(maxBytes: Int): ByteArray? = readLock.withLock {
            readStarted.countDown()
            if (position == unread.size) {
                unread = incoming.receiveCatching().getOrNull() ?: return@withLock null
                position = 0
            }
            val size = minOf(maxBytes, unread.size - position)
            unread.copyOfRange(position, position + size).also { position += size }
        }
        override suspend fun write(bytes: ByteArray) {
            writeStarted.countDown()
            writeGate?.await()
            if (writeDelayMillis > 0) delay(writeDelayMillis)
            writes.add(bytes.copyOf())
        }
        override suspend fun finishOutput() { finishes.incrementAndGet() }
        override fun closeInput() { inputCloses.incrementAndGet(); incoming.close() }
        override suspend fun closeGracefully(errorCode: Int) = close()
        override fun close() {
            if (closes.getAndIncrement() > 0) return
            incoming.close()
            writeGate?.completeExceptionally(IOException("closed"))
            state.value = ChannelState(ChannelPhase.CLOSED, true, true, ChannelEndReason.LOCAL_CLOSE)
        }
    }
}
