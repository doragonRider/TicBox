package dev.ticbox.companion.engine.health

import dev.ticbox.companion.api.DuplexTransport
import dev.ticbox.companion.protocol.LocalHealthProtocol
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class LocalHealthFramesTest {
    /** Hands out the bytes in odd-sized chunks, so lengths and bodies split across reads. */
    private class Chunks(bytes: ByteArray) : DuplexTransport {
        private val chunks = bytes.toList().chunked(7).map { it.toByteArray() }.iterator()
        override suspend fun read() = if (chunks.hasNext()) chunks.next() else null
        override suspend fun write(bytes: ByteArray) = Unit
        override fun close() = Unit
    }

    @Test fun compressedAndPlainFramesSplitAcrossChunksReadBackWithTheirWireSize() = runTest {
        val page = buildJsonObject { put("rows", JsonArray(List(64) { JsonPrimitive("row $it with the same words") })) }
        val small = buildJsonObject { put("op", "ack") }
        val packed = LocalHealthProtocol.frame(page, zlib = true)
        val frames = LocalHealthFrames(Chunks(packed + LocalHealthProtocol.frame(small)))
        assertEquals(page, frames.read()); assertEquals(packed.size, frames.lastFrameBytes)
        assertTrue(packed.size < LocalHealthProtocol.frame(page).size)
        assertEquals(small, frames.read())
    }
}
