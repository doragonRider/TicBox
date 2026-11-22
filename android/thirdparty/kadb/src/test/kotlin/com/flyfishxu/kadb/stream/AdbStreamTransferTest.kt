package com.flyfishxu.kadb.stream

import com.flyfishxu.kadb.core.AdbConnection
import com.flyfishxu.kadb.core.AdbProtocol
import com.flyfishxu.kadb.core.AdbReader
import com.flyfishxu.kadb.core.AdbWriter
import com.flyfishxu.kadb.queue.AdbMessageQueue
import okio.Buffer
import okio.Pipe
import okio.Source
import okio.Timeout
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException
import kotlin.test.*

class AdbStreamTransferTest {
    @Test fun transportEofDoesNotReturnPartialShellOutputAsSuccess() {
        val incoming = Pipe(65536)
        val remote = AdbWriter(incoming.sink)
        val queue = AdbMessageQueue(AdbReader(incoming.source)) { incoming.cancel() }
        queue.startListening(1)
        val stream = AdbStream(queue, AdbWriter(Buffer()), 4096, 1, 10)
        try {
            remote.writeWrite(10, 1, "partial".encodeToByteArray(), 0, 7)
            remote.close()
            assertFailsWith<EOFException> { stream.source.readUtf8() }
            queue.ensureEmpty()
        } finally {
            stream.close()
            queue.close()
        }
    }

    @Test fun syncBatchesSmallSourceReadsAndPreservesFraming() {
        val toHost = Pipe(65536)
        val fromHost = Pipe(65536)
        val remoteWriter = AdbWriter(toHost.sink)
        val remoteReader = AdbReader(fromHost.source)
        val queue = AdbMessageQueue(AdbReader(toHost.source)) { toHost.cancel(); fromHost.cancel() }
        queue.startListening(1)
        val stream = AdbStream(queue, AdbWriter(fromHost.sink), 8192, 1, 10)
        val executor = Executors.newFixedThreadPool(2)
        val original = ByteArray(128 * 1024) { (it % 251).toByte() }
        val source = object : Source {
            val input = Buffer().write(original)
            override fun read(sink: Buffer, byteCount: Long) = input.read(sink, minOf(byteCount, 111))
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }
        val received = Buffer()
        val peer = executor.submit<Int> {
            val pending = Buffer()
            var writes = 0
            while (true) {
                val packet = remoteReader.readMessage()
                if (packet.command != AdbProtocol.CMD_WRTE) continue
                writes++
                assertTrue(packet.payloadLength <= 8192)
                remoteWriter.writeOkay(10, 1)
                pending.write(packet.payload)
                while (pending.size >= 8) {
                    val header = pending.peek()
                    val id = header.readUtf8(4)
                    val size = header.readIntLe()
                    if (id == "DONE") {
                        pending.skip(8)
                        assertEquals(0L, pending.size)
                        val response = Buffer().writeUtf8("OKAY").writeIntLe(0).readByteArray()
                        remoteWriter.writeWrite(10, 1, response, 0, response.size)
                        return@submit writes
                    }
                    if (pending.size < 8L + size) break
                    pending.skip(8)
                    when (id) {
                        "SEND" -> pending.skip(size.toLong())
                        "DATA" -> {
                            assertTrue(size in 1..65536)
                            received.write(pending, size.toLong())
                        }
                        else -> error("Unexpected sync frame: $id")
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE") 0
        }
        try {
            executor.submit { AdbSyncStream(stream).send(source, "/data/local/tmp/test.bin", 420, 0) }.get(3, SECONDS)
            assertTrue(peer.get(3, SECONDS) < 25, "Small reads must not each wait for an ADB ACK")
            assertContentEquals(original, received.readByteArray())
        } finally {
            queue.close()
            executor.shutdownNow()
        }
    }

    private class DelayedAckStream(window: Long) : AutoCloseable {
        private val toHost = Pipe(1L shl 20)
        private val fromHost = Pipe(1L shl 20)
        val peer = AdbWriter(toHost.sink)
        val peerReader = AdbReader(fromHost.source)
        val queue = AdbMessageQueue(AdbReader(toHost.source)) { toHost.cancel(); fromHost.cancel() }
            .apply { startListening(1) }
        val stream = AdbStream(queue, AdbWriter(fromHost.sink), 8192, 1, 10, delayedAckEnabled = true, initialAvailableSendBytes = window)

        fun write(size: Int) {
            stream.sink.write(ByteArray(size))
            stream.sink.flush()
        }

        override fun close() = queue.close()
    }

    @Test fun delayedAckConfirmsEachMessageOnceWithItsWholeSize() {
        DelayedAckStream(window = 0).use {
            it.peer.writeWrite(10, 1, ByteArray(20_000), 0, 20_000)
            it.peer.writeWrite(10, 1, ByteArray(5_000), 0, 5_000)
            it.stream.source.readByteArray(25_000)
            it.stream.close()
            val acks = generateSequence { it.peerReader.readMessage() }
                .takeWhile { message -> message.command != AdbProtocol.CMD_CLSE }
                .map { message -> Buffer().write(message.payload).readIntLe() }
                .toList()
            assertEquals(listOf(20_000, 5_000), acks)
        }
    }

    @Test fun delayedAckWritesWaitOnlyOnceTheWindowIsUsedUp() {
        val executor = Executors.newSingleThreadExecutor()
        DelayedAckStream(window = 100).use {
            try {
                executor.submit { it.write(100) }.get(3, SECONDS)
                assertEquals(100, it.peerReader.readMessage().payloadLength)
                val next = executor.submit { it.write(10) }
                assertFailsWith<TimeoutException> { next.get(200, MILLISECONDS) }
                it.peer.writeOkay(10, 1, 100)
                next.get(3, SECONDS)
                assertEquals(10, it.peerReader.readMessage().payloadLength)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test fun delayedAckTakesTheAcksThatCameInWithoutWaitingForThem() {
        DelayedAckStream(window = 1_000).use {
            repeat(3) { _ -> it.peer.writeOkay(10, 1, 10) }
            // The stream's own queue sees these in order, so the acks are in by the time this is.
            it.peer.writeWrite(10, 1, ByteArray(1), 0, 1)
            it.queue.take(1, AdbProtocol.CMD_WRTE)
            it.write(10)
            assertNull(it.queue.poll(1, AdbProtocol.CMD_OKAY))
        }
    }

    @Test fun delayedAckWriteFailsOnceThePeerClosedAndLeavesWhatIsLeftToRead() {
        DelayedAckStream(window = 1_000).use {
            it.peer.writeWrite(10, 1, "left".encodeToByteArray(), 0, 4)
            it.peer.writeClose(10, 1)
            assertFailsWith<IOException> { it.queue.take(1, AdbProtocol.CMD_OKAY) }
            assertFailsWith<IOException> { it.write(1) }
            assertEquals("left", it.stream.source.readUtf8())
        }
    }

    private fun windowAskedFor(features: Set<String>, firstAck: Int?): Int {
        val toHost = Pipe(65536)
        val fromHost = Pipe(65536)
        val peer = AdbWriter(toHost.sink)
        val peerReader = AdbReader(fromHost.source)
        val connection = AdbConnection(
            AdbReader(toHost.source), AdbWriter(fromHost.sink), null, features, AdbProtocol.A_VERSION, 8192,
            delayedAckWindowBytes = 65536
        )
        assertFalse(connection.encrypted)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val opened = executor.submit<AdbStream> { connection.open("shell:") }
            val open = peerReader.readMessage()
            assertEquals(AdbProtocol.CMD_OPEN, open.command)
            peer.writeOkay(10, open.arg0, firstAck)
            opened.get(3, SECONDS)
            return open.arg1
        } finally {
            connection.close()
            toHost.cancel()
            fromHost.cancel()
            executor.shutdownNow()
        }
    }

    @Test fun openAsksForTheConfiguredWindowOnlyWhenDelayedAckWasNegotiated() {
        assertEquals(65536, windowAskedFor(setOf(AdbProtocol.FEATURE_DELAYED_ACK), firstAck = 32 shl 20))
        assertEquals(0, windowAskedFor(emptySet(), firstAck = null))
    }
}
