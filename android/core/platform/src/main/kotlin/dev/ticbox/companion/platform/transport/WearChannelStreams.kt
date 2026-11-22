package dev.ticbox.companion.platform.transport

import dev.ticbox.companion.api.WearChannel
import dev.ticbox.companion.platform.cdm.PermissionSyncChannel
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.Objects
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * Blocking Java streams over one established logical Wear Channel. Read/write/output.close must
 * run on a worker thread (CDM's transport workers do this), never on the Android main thread.
 * This adapter owns its channel. Closing the adapter aborts promptly without waiting for peer ACKs;
 * closing only the output sends EOF and may wait for its ACK. Input/output half-close independently.
 * It carries bytes only; it does not create a CDM association or perform a system data transfer.
 */
class WearChannelStreams(
    private val channel: WearChannel,
    private val chunkBytes: Int = 64_000,
) : PermissionSyncChannel {
    init { require(chunkBytes in 1..64_000) { "Invalid stream chunk budget" } }

    override val path: String get() = channel.info.path
    private val closed = AtomicBoolean(false)
    private val inputClosed = AtomicBoolean(false)
    private val outputClosed = AtomicBoolean(false)
    private val inputLock = Any()
    private val outputLock = Any()

    override val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            Objects.checkFromIndexSize(offset, length, bytes.size)
            if (length == 0) return 0
            synchronized(inputLock) {
                requireInputOpen()
                val data = blockingIo { channel.read(minOf(length, chunkBytes)) }
                requireInputOpen()
                if (data == null) return -1
                if (data.isEmpty() || data.size > minOf(length, chunkBytes)) {
                    channel.close()
                    throw IOException("Logical channel returned an invalid read size")
                }
                data.copyInto(bytes, offset)
                return data.size
            }
        }

        override fun close() {
            if (inputClosed.compareAndSet(false, true)) channel.closeInput()
        }
    }

    override val output: OutputStream = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            Objects.checkFromIndexSize(offset, length, bytes.size)
            synchronized(outputLock) {
                requireOutputOpen()
                var cursor = offset
                var remaining = length
                while (remaining > 0) {
                    requireOutputOpen()
                    val size = minOf(remaining, chunkBytes)
                    val chunk = bytes.copyOfRange(cursor, cursor + size)
                    blockingIo { channel.write(chunk) }
                    requireOutputOpen()
                    cursor += size
                    remaining -= size
                }
            }
        }

        override fun flush() {
            // Each write already waits for the channel's ACK. No synthetic empty DATA is needed.
            synchronized(outputLock) { requireOutputOpen() }
        }

        override fun close() {
            synchronized(outputLock) {
                if (!outputClosed.compareAndSet(false, true) || closed.get()) return
                blockingIo { channel.finishOutput() }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        inputClosed.set(true)
        outputClosed.set(true)
        // Do not take either stream lock: a reader/writer may own it while waiting on the peer.
        channel.close()
    }

    private fun requireInputOpen() {
        if (closed.get() || inputClosed.get()) throw IOException("Channel input stream is closed")
    }

    private fun requireOutputOpen() {
        if (closed.get() || outputClosed.get()) throw IOException("Channel output stream is closed")
    }

    private fun <T> blockingIo(block: suspend () -> T): T = try {
        runBlocking { block() }
    } catch (interrupted: InterruptedException) {
        // An interrupted write/read must not leave a partially consumed stream usable.
        close()
        Thread.currentThread().interrupt()
        throw InterruptedIOException("Channel stream operation interrupted").apply { initCause(interrupted) }
    } catch (cancelled: CancellationException) {
        close()
        throw IOException("Channel stream operation cancelled", cancelled)
    }
}
