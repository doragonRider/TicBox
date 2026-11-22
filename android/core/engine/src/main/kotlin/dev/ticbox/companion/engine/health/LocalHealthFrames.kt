package dev.ticbox.companion.engine.health

import dev.ticbox.companion.api.DuplexTransport
import dev.ticbox.companion.protocol.LocalHealthProtocol
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Chunk boundaries can split a length or combine multiple frames. Keep unread bytes.
 * JSON frames are parsed in [parseContext] so large pages stay off the caller's thread.
 */
class LocalHealthFrames(
    private val transport: DuplexTransport,
    private val parseContext: CoroutineContext = EmptyCoroutineContext,
) {
    private var chunk = byteArrayOf()
    private var position = 0
    /** Size of the last frame [read] returned, length prefix included. */
    var lastFrameBytes = 0
        private set
    suspend fun write(value: JsonObject) = transport.write(LocalHealthProtocol.frame(value))
    suspend fun read(): JsonObject {
        val size = ByteBuffer.wrap(readExactly(4)).int
        if (size !in 1..LocalHealthProtocol.MAX_FRAME_BYTES) throw IOException("Local health frame allocation budget exceeded")
        val frame = readExactly(size)
        lastFrameBytes = 4 + size
        return try { withContext(parseContext) { LocalHealthProtocol.parse(frame) } }
        catch (problem: IllegalArgumentException) { throw IOException("Malformed local health frame") }
    }
    private suspend fun readExactly(size: Int): ByteArray {
        val result = ByteArray(size); var written = 0
        while (written < size) {
            if (position == chunk.size) { chunk = transport.read() ?: throw EOFException("Local health stream closed"); position = 0 }
            if (chunk.isEmpty()) continue
            val count = minOf(size - written, chunk.size - position)
            chunk.copyInto(result, written, position, position + count); written += count; position += count
        }
        return result
    }
}
