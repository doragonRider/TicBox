package dev.ticbox.companion.platform.transport

import dev.ticbox.companion.api.DuplexTransport
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Stream adapter with explicit ownership. Canceling active blocking I/O closes the whole transport.
 * [release] must synchronously unblock blocked I/O, as BluetoothSocket.close() does. The adapter
 * also attempts to close both streams, including when release itself throws.
 */
class BlockingStreamTransport(
    private val input: InputStream,
    private val output: OutputStream,
    private val release: () -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val readBufferBytes: Int = 8_192,
) : DuplexTransport {
    private val closed = AtomicBoolean(false)
    private val reading = AtomicBoolean(false)
    private val writing = Mutex()

    init {
        // Buffer size is a local allocation choice, not a watch protocol packet boundary.
        require(readBufferBytes in 1..65_536)
    }

    override suspend fun read(): ByteArray? {
        ensureOpen()
        check(reading.compareAndSet(false, true)) { "Only one stream reader is allowed" }
        return try {
            cancellableIo(dispatcher, ::close) {
                ensureOpen()
                val buffer = ByteArray(readBufferBytes)
                when (val count = input.read(buffer)) {
                    -1 -> null
                    0 -> throw IOException("Input stream returned an empty read")
                    else -> buffer.copyOf(count)
                }
            }
        } finally {
            reading.set(false)
        }
    }

    override suspend fun write(bytes: ByteArray) {
        require(bytes.isNotEmpty()) { "Cannot write an empty transport chunk" }
        // The caller may mutate its array once this suspend call has started.
        val owned = bytes.copyOf()
        writing.withLock {
            ensureOpen()
            cancellableIo(dispatcher, ::close) {
                ensureOpen()
                output.write(owned)
                output.flush()
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching(release)
        runCatching { input.close() }
        runCatching { output.close() }
    }

    private fun ensureOpen() {
        if (closed.get()) throw IOException("Transport is closed")
    }
}
