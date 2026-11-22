package dev.ticbox.companion.protocol

import kotlin.test.*

class MediaProtocolTest {
    @Test fun `legacy play command matches independent fixed DataMap vector`() {
        val bytes = hex("0a150a07636f6d6d616e64120a080212061204706c6179")
        assertContentEquals(bytes, MediaCodec.encodeCommand(MediaCommand(command = "play")))
        assertEquals("play", MediaCodec.decodeCommand(bytes).command)
        assertEquals("/mediacontrols/mediacontrols", MediaProtocol.PATH)
    }
    @Test fun `command preserves zero presence mixed independent operations and future fields`() {
        val command = MediaCommand("pause", 1, 0f, 0, 0, 0, "custom",
            mapOf("zero" to DataMapValue.IntNumber(0)), fields = mapOf("future" to DataMapValue.Text("retained")),
            unknownFields = listOf(ProtoField.fromEncoded(hex("f80101"))))
        val decoded = MediaCodec.decodeCommand(MediaCodec.encodeCommand(command))
        assertEquals(7, MediaProtocol.operationCount(decoded)); assertEquals(0L, decoded.positionMillis)
        assertEquals(0L, decoded.playQueueId); assertEquals(0f, decoded.absoluteVolume)
        assertEquals(DataMapValue.Text("retained"), decoded.fields["future"])
        assertEquals(DataMapValue.IntNumber(0), decoded.customActionExtras["zero"])
        assertEquals(31, decoded.unknownFields.single().number)
        assertContentEquals(hex("f80101"), decoded.unknownFields.single().raw)
    }
    @Test fun `wrong command scalar retains usable seek and volume instead of rejecting whole message`() {
        val body = DataMapCodec.encode(mapOf("command" to DataMapValue.IntNumber(5), "volume" to DataMapValue.LongNumber(1),
            "current_media_item_position" to DataMapValue.IntNumber(0), "future" to DataMapValue.Bool(true)))
        val decoded = MediaCodec.decodeCommand(body)
        assertNull(decoded.command); assertEquals(1, decoded.volumeDelta); assertEquals(0L, decoded.positionMillis)
        assertEquals(2, MediaProtocol.operationCount(decoded)); assertTrue(decoded.notices.isNotEmpty())
        assertEquals(DataMapValue.Bool(true), decoded.fields["future"])
    }
    @Test fun `media Assets use global sorted slots across custom action icons and retain bytes defensively`() {
        val bytes = byteArrayOf(1, 2)
        val content = MediaContent(assets = mapOf("mediacontrols.artwork" to MediaAsset(bytes), "mediacontrols.app_icon" to MediaAsset(byteArrayOf(3))),
            lists = mapOf("mediacontrols.custom_actions" to listOf(MediaContent(mapOf("mediacontrols.custom_action_action" to DataMapValue.Text("star")),
                assets = mapOf("mediacontrols.custom_action_icon" to MediaAsset(byteArrayOf(4)))))))
        bytes[0] = 9
        val encoded = MediaCodec.encode(content)
        assertEquals(listOf("0", "1", "2"), encoded.assets.map { it.slot })
        assertContentEquals(byteArrayOf(1, 2), encoded.assets[1].data)
        val fields = MediaCodec.decode(encoded.data).document.values
        assertEquals(DataMapValue.AssetIndex(0), fields["mediacontrols.app_icon"])
        assertEquals(DataMapValue.AssetIndex(1), fields["mediacontrols.artwork"])
        val custom = ((fields["mediacontrols.custom_actions"] as DataMapValue.ListValue).value.single() as DataMapValue.MapValue).value
        assertEquals(DataMapValue.AssetIndex(2), custom["mediacontrols.custom_action_icon"])
    }
    @Test fun `v2 consumer field mapping matches a fixed protobuf vector and preserves unknown extensions`() {
        val content = MediaContent(mapOf("mediacontrols.playing" to DataMapValue.Bool(true), "mediacontrols.title" to DataMapValue.Text("Song"),
            "elapsed_time" to DataMapValue.LongNumber(7)))
        val fixed = hex("58017204536f6e67b00107b80107")
        assertContentEquals(fixed, MediaV2Codec.encode(content))
        val future = fixed + hex("f80101")
        val decoded = MediaV2Codec.decode(future)
        assertEquals(DataMapValue.Text("Song"), decoded.fields["mediacontrols.title"])
        assertEquals(DataMapValue.LongNumber(7), decoded.fields["elapsed_time"])
        assertEquals(31, decoded.wireFields.last().number); assertContentEquals(future, decoded.raw)
    }
    @Test fun `v2 certificate hashes custom action PNG and wear bundle retain distinct nested wire shapes`() {
        val content = MediaContent(lists = mapOf(
            "mediacontrols.hash_bytes_list" to listOf(MediaContent(mapOf("mediacontrols.hash_bytes" to DataMapValue.Bytes(byteArrayOf(1, 2))))),
            "mediacontrols.custom_actions" to listOf(MediaContent(mapOf("mediacontrols.custom_action_action" to DataMapValue.Text("star"),
                "mediacontrols.custom_action_name" to DataMapValue.Text("Like")), mapOf("mediacontrols.custom_action_icon" to MediaAsset(byteArrayOf(8, 9))))),
        ), maps = mapOf("mediacontrols.wear_media_bundle" to MediaContent(mapOf("x" to DataMapValue.Bool(true)))))
        val decoded = MediaV2Codec.decode(MediaV2Codec.encode(content))
        val cert = ProtoWire.decodeFields(decoded.wireFields.single { it.number == 8 }.value as ByteArray)
        assertContentEquals(byteArrayOf(1, 2), cert.single().value as ByteArray)
        val action = ProtoWire.decodeFields(decoded.wireFields.single { it.number == 12 }.value as ByteArray)
        assertContentEquals(byteArrayOf(8, 9), action.single { it.number == 1 }.value as ByteArray)
        assertEquals("star", (action.single { it.number == 2 }.value as ByteArray).toString(Charsets.UTF_8))
        val wear = decoded.wireFields.single { it.number == 24 }.value as ByteArray
        assertEquals(DataMapValue.Bool(true), MediaCodec.decode(wear).document.values["x"])
    }
    @Test fun `local image budgets fail explicitly and invalid binary input never becomes an empty command`() {
        assertFailsWith<ProtocolException> { MediaCodec.encode(MediaContent(assets = mapOf("mediacontrols.artwork" to MediaAsset(byteArrayOf(1, 2)))),
            NotificationCodecLimits(maxAssetBytes = 1)) }
        assertFailsWith<ProtocolException> { MediaCodec.decodeCommand(byteArrayOf(0)) }
        assertFailsWith<ProtocolException> { MediaV2Codec.decode(byteArrayOf(0)) }
    }
    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
