package dev.ticbox.companion.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.Deflater
import kotlinx.serialization.json.*
import kotlin.test.*

class LocalHealthFrameTest {
    private val page = buildJsonObject {
        put("op", "changes")
        put("rows", JsonArray(List(128) { id -> buildJsonObject {
            put("id", id.toString()); put("columns", buildJsonObject { put("_id", id); put("values", "72"); put("type", 1) })
            put("storageTypes", buildJsonObject { put("_id", "integer"); put("values", "string"); put("type", "integer") })
        } }))
    }
    private fun read(bytes: ByteArray) = LocalHealthProtocol.read(ByteArrayInputStream(bytes))
    private fun compressed(bytes: ByteArray) = LocalHealthProtocol.header(ByteBuffer.wrap(bytes, 0, 4).int).second
    private fun zlibFrame(body: ByteArray): ByteArray {
        val deflater = Deflater(); deflater.setInput(body); deflater.finish()
        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (!deflater.finished()) output.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        val packed = output.toByteArray()
        return ByteBuffer.allocate(4 + packed.size).putInt(packed.size or Int.MIN_VALUE).put(packed).array()
    }

    @Test fun aCompressedPageReadsBackUnchangedAndSmallOrUnaskedFramesStayPlain() {
        val packed = LocalHealthProtocol.frame(page, zlib = true)
        val plain = LocalHealthProtocol.frame(page)
        assertTrue(compressed(packed)); assertFalse(compressed(plain))
        assertTrue(packed.size * 4 < plain.size)
        assertEquals(page, read(packed)); assertEquals(page, read(plain))
        assertFalse(compressed(LocalHealthProtocol.frame(buildJsonObject { put("op", "ack") }, zlib = true)))
    }

    @Test fun aCompressedBodyMayNotExpandPastTheFrameBudgetOrEndEarly() {
        val bomb = zlibFrame("{\"pad\":\"${" ".repeat(LocalHealthProtocol.MAX_FRAME_BYTES)}\"}".toByteArray())
        assertTrue(bomb.size < 16_384)
        assertFailsWith<IllegalArgumentException> { read(bomb) }
        val whole = LocalHealthProtocol.frame(page, zlib = true)
        val cut = whole.copyOf(whole.size - 10)
        ByteBuffer.wrap(cut).putInt(0, (cut.size - 4) or Int.MIN_VALUE)
        assertFailsWith<IllegalArgumentException> { read(cut) }
    }
}
