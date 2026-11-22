package dev.ticbox.companion.protocol

import kotlin.test.*

class DataProtocolTest {
    @Test fun `empty sync uses version two and negative legacy cursor fixed bytes`() {
        assertEquals("420d08ffffffffffffffffff011802", DataProtocol.syncStart(emptyMap(), "watch").hex())
    }
    @Test fun `progress request has independently specified field tags and varint`() {
        assertEquals("0a017410ac02", DataProtocol.encodeProgress("t", 300).hex())
        val progress = DataProtocol.decodeProgress(hex("0a017410ac02"))
        assertEquals("t", progress.trackerId)
        assertEquals(300L, progress.sequenceId)
        assertNull(DataProtocol.decodeProgress(byteArrayOf()).sequenceId)
    }
    @Test fun `asset SHA1 uses base64url without padding`() {
        assertEquals("qZk-NkcGgWq6PiVxeFDCbJzQ2J0", DataProtocol.digest("abc".toByteArray()))
    }
    @Test fun `DataItem split submessages merge and raw unknown fields survive source and sequence overlays`() {
        val first = GmsStructs.encode("SetDataItem", mapOf("packageName" to "p", "packageDigest" to "d", "uri" to "wear://watch/x"))
        val raw = first + hex("2807a00601") // sequence 7, unknown field100=1.
        val normalized = DataProtocol.withItemSource(raw, "watch")
        val replay = DataProtocol.withItemSequence(normalized, 20L)
        val decoded = DataProtocol.decodeItem(replay)
        assertEquals(20L, decoded.sequenceId)
        assertEquals("watch", decoded.sourceNodeId)
        assertEquals(setOf(100), decoded.unknownFieldNumbers)
        assertTrue(decoded.notices.any { it.kind == "last_singular_value" })
        val envelope = DataProtocol.wrapDataItem(first) + DataProtocol.wrapDataItem(hex("2807"))
        assertEquals(7L, DataProtocol.decodeItem(DataProtocol.split(envelope).parts.getValue(9)).sequenceId)
    }
    @Test fun `restrictions match decoded paths and local or wildcard host`() {
        val item = DataWireItem(DataAppKey("p", "cert"), "wear://phone/a%20b/x", 1, "phone")
        assertTrue(DataProtocol.allows(item, listOf(DataPackageRestriction("p", listOf(DataUriFilter(2, "wear://local/a%20b")))), "phone"))
        assertTrue(DataProtocol.allows(item, listOf(DataPackageRestriction("p", listOf(DataUriFilter(1, "wear://*/a%20b/x"))))))
        assertFalse(DataProtocol.allows(item, listOf(DataPackageRestriction("q", null))))
        assertTrue(DataProtocol.allows(item, listOf(DataPackageRestriction("p", emptyList()))))
    }
    @Test fun `DataMap fixed null and signed byte vectors match recovered format`() {
        assertEquals("0a070a016e1202080e", DataMapCodec.encode(mapOf("n" to DataMapValue.Null)).hex())
        val decoded = DataMapCodec.decode(DataMapCodec.encode(mapOf("byte" to DataMapValue.ByteNumber((-128).toByte()))))
        assertEquals((-128).toByte(), assertIs<DataMapValue.ByteNumber>(decoded.values["byte"]).value)
    }
    @Test fun `DataMap accepts packed arrays nested maps and future value types`() {
        // entry n -> value type12, storage.longArray packed [-1, 128].
        val packed = hex("0a170a016e1212080c120e620cffffffffffffffffff018001")
        assertEquals(listOf(-1L, 128L), assertIs<DataMapValue.Longs>(DataMapCodec.decode(packed).values["n"]).value)
        val unknownRaw = hex("08631203a00601")
        val input = mapOf("future" to DataMapValue.Unknown(99, unknownRaw), "map" to DataMapValue.MapValue(mapOf("a" to DataMapValue.Text("ok"))))
        val roundtrip = DataMapCodec.decode(DataMapCodec.encode(input))
        assertContentEquals(unknownRaw, assertIs<DataMapValue.Unknown>(roundtrip.values["future"]).raw)
        assertEquals(DataMapValue.Text("ok"), assertIs<DataMapValue.MapValue>(roundtrip.values["map"]).value["a"])
    }
    @Test fun `DataMap keys use UTF16 ordering and duplicate input keys take last value`() {
        val encoded = DataMapCodec.encode(linkedMapOf("z" to DataMapValue.Null, "a" to DataMapValue.Text("first")))
        assertEquals(listOf("a", "z"), DataMapCodec.decode(encoded).values.keys.toList())
        val second = DataMapCodec.encode(mapOf("a" to DataMapValue.Text("last")))
        val decoded = DataMapCodec.decode(encoded + second)
        assertEquals(DataMapValue.Text("last"), decoded.values["a"])
        assertTrue("duplicate_key:a" in decoded.notices)
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
