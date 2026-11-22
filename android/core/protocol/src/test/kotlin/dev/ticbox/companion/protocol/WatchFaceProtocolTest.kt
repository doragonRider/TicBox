package dev.ticbox.companion.protocol

import kotlin.test.*

class WatchFaceProtocolTest {
    @Test fun `favorite requests use Int32 wrappers and proto3 success default`() {
        assertContentEquals(hex("0a02080912020807"), WatchFaceProtocol.change(9, 7))
        assertContentEquals(hex("0a020809120208071a00"), WatchFaceProtocol.remove(9, 7, 0))
        assertContentEquals(hex("0a0208091203070001"), WatchFaceProtocol.reorder(9, listOf(7, 0, 1)))
        assertEquals(WatchFaceWireOperation(9, 0), WatchFaceProtocol.operation(hex("0a020809")))
        assertEquals(WatchFaceWireOperation(0, 47), WatchFaceProtocol.operation(hex("0a00102f")))
        assertEquals(listOf(7, 0, 1), WatchFaceProtocol.order(hex("0a03070001")))
        assertEquals(listOf(7, 0, 1), WatchFaceProtocol.order(hex("080708000801")))
    }
    @Test fun `start frame preserves zero wrapper presence and big endian length`() {
        val bytes = WatchFaceProtocol.startEditing(7, 400, 400, false)
        assertContentEquals(hex("0a120a100a02080712001a030890032203089003"), bytes)
        assertContentEquals(hex("00000014") + bytes, WatchFaceProtocol.frame(bytes))
        val root = ProtoWire.decodeFields(WatchFaceProtocol.startEditing(0, 0, 0, false))
        val request = ProtoWire.decodeFields(root.single().value as ByteArray)
        val start = ProtoWire.decodeFields(request.single().value as ByteArray)
        assertEquals(listOf(1, 2, 3, 4), start.map { it.number })
        assertTrue(start.all { (it.value as ByteArray).isEmpty() })
    }
    @Test fun `frames accept every split coalescing and empty messages`() {
        val bytes = WatchFaceProtocol.frame(hex("12090a070a030a01741001")) + WatchFaceProtocol.frame(byteArrayOf())
        for (split in 0..bytes.size) {
            val decoder = WatchFaceFrameDecoder()
            val frames = decoder.feed(bytes.copyOfRange(0, split)) + decoder.feed(bytes.copyOfRange(split, bytes.size))
            assertEquals(2, frames.size); assertTrue(frames[1].isEmpty()); decoder.finish()
        }
        val truncated = WatchFaceFrameDecoder(); truncated.feed(hex("00000005ff"))
        assertFailsWith<ProtocolException> { truncated.finish() }
        assertFailsWith<ProtocolException> { WatchFaceFrameDecoder(10).feed(hex("0000000b")) }
        assertFailsWith<ProtocolException> { WatchFaceFrameDecoder().feed(hex("ffffffff")) }
    }
    @Test fun `editing preview uses reserved field gap and complication time is Int64 wrapper`() {
        val request = WatchFaceProtocol.screenshot(9, "t", byteArrayOf(), emptyList(), 100, 1)
        val body = unwrapRequest(request)
        assertEquals(4, body.first)
        assertEquals(listOf(1, 2, 4, 6, 8), ProtoWire.decodeFields(body.second).map { it.number })
        val complication = WatchFaceWireComplication(2, "pkg/Provider", 3)
        val second = unwrapRequest(WatchFaceProtocol.complicationScreenshot(9, "t", complication, byteArrayOf(), 100, 2, Long.MAX_VALUE))
        assertEquals(5, second.first)
        assertEquals(listOf(1, 2, 3, 5, 6, 7, 8), ProtoWire.decodeFields(second.second).map { it.number })
        val time = ProtoWire.decodeFields(second.second).single { it.number == 6 }.value as ByteArray
        assertEquals(Long.MAX_VALUE.toULong(), ProtoWire.decodeFields(time).single().value)
    }
    @Test fun `style maps complications active and favorite preview keys are recovered independently`() {
        val style = WatchFaceProtocol.style(mapOf("c" to byteArrayOf(0, -1)))
        assertContentEquals(hex("12070a0163120200ff"), style)
        assertContentEquals(byteArrayOf(0, -1), WatchFaceProtocol.styleOptions(style)["c"])
        val raw = hex("980601")
        val merged = WatchFaceProtocol.style(mapOf("new" to byteArrayOf(7)), raw)
        assertTrue(ProtoWire.decodeFields(merged).any { it.number == 99 })
        val complication = WatchFaceProtocol.complication(WatchFaceWireComplication(0, "pkg/P", 4, raw))
        val decoded = WatchFaceProtocol.complication(complication)
        assertEquals(0, decoded.slotId); assertEquals("pkg/P", decoded.providerComponent); assertEquals(4, decoded.dataType)
        assertTrue(ProtoWire.decodeFields(complication).any { it.number == 99 })
        assertEquals(WatchFaceWireActive("p/C", 0), WatchFaceProtocol.active(hex("0a050a03702f431200")))
        val favorite = WatchFaceProtocol.favorite(hex("0a02080712050a03702f4322040a001a002a030a0130"))
        assertEquals(7, favorite.id); assertEquals("p/C", favorite.componentName); assertEquals("0", favorite.previewAssetName)
        assertEquals(0, favorite.complications.single().slotId)
    }
    @Test fun `response metadata and unknown status values do not become success`() {
        val start = WatchFaceProtocol.editingMessage(hex("12090a070a030a01741001"))
        assertEquals(1, start.kind); assertEquals("t", start.token); assertEquals(1, start.status)
        val screenshot = WatchFaceProtocol.editingMessage(hex("120a1a080a020809102f1a00"))
        assertEquals(3, screenshot.kind); assertEquals(9, screenshot.requestId); assertEquals(47, screenshot.status)
        val metadata = WatchFaceProtocol.editingMessage(hex("1a070a020801120100"))
        assertEquals(4, metadata.kind); assertContentEquals(hex("0801"), metadata.styleSchema); assertContentEquals(hex("00"), metadata.complicationStates)
        assertEquals(0, WatchFaceProtocol.editingMessage(hex("980601")).kind)
    }
    @Test fun `Mobvoi combined photo is raw big endian JSON length then image`() {
        val bytes = WatchFacePhotoProtocol.combine("{}".toByteArray(), byteArrayOf(-1, 0, 1))
        assertContentEquals(hex("000000027b7dff0001"), bytes)
        val (json, image) = WatchFacePhotoProtocol.split(bytes)
        assertEquals("{}", json.toString(Charsets.UTF_8)); assertContentEquals(byteArrayOf(-1, 0, 1), image)
        assertFailsWith<ProtocolException> { WatchFacePhotoProtocol.split(hex("ffffffff")) }
        assertFailsWith<ProtocolException> { WatchFacePhotoProtocol.split(hex("00000004ff")) }
    }
    private fun unwrapRequest(bytes: ByteArray): Pair<Int, ByteArray> {
        val request = ProtoWire.decodeFields(ProtoWire.decodeFields(bytes).single().value as ByteArray).single()
        return request.number to request.value as ByteArray
    }
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
