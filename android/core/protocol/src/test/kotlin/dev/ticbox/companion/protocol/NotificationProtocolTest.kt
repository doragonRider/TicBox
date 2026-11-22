package dev.ticbox.companion.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

class NotificationProtocolTest {
    @Test fun `path matches Uri encoding vector and accepts observed legacy form`() {
        assertEquals("a~3Ab~7E~E4~B8~AD", NotificationProtocol.safeEncode("a:b~中"))
        assertEquals("a:b~中", NotificationProtocol.safeDecode("a~3Ab~7E~E4~B8~AD"))
        assertEquals("_-.!'()*AZ09~2B~25~2F~3A~7E", NotificationProtocol.safeEncode("_-.!'()*AZ09+%/:~"))
        assertEquals("+%bad%Q0", NotificationProtocol.safeDecode("+~25bad~Q0"))
        val id = NotificationStreamId("p", "example.demo", 7, null, "0|example.demo|7|null")
        assertEquals(STREAM, NotificationProtocol.streamItemPath(id))
        assertEquals(id, NotificationProtocol.parseStreamItemPath("wear://another-owner$STREAM"))
        assertEquals(NotificationStreamId("p", "example.demo", -7, "中:~", null),
            NotificationProtocol.parseStreamItemPath("/bridger/stream_item:p:example.demo:-7:~E4~B8~AD~3A~7E"))
        assertNull(NotificationProtocol.parseStreamItemPath("/bridger/stream_item:p:example.demo:no-number:null_tag"))
        // The original sentinel also maps a literal tag called null_tag to null.
        assertNull(NotificationProtocol.parseStreamItemPath(NotificationProtocol.streamItemPath(id.copy(tag = "null_tag")))!!.tag)
        assertEquals("com.mobvoi.companion.at", NotificationProtocol.OFFICIAL_COMPANION_APP_KEY.packageName)
        assertEquals("5cc9866db03e530a21f842a421c7eb42bac58961", NotificationProtocol.OFFICIAL_COMPANION_APP_KEY.packageDigest)
    }

    @Test fun `plain text notification matches independent fixed DataMap vector`() {
        val fields = mapOf(
            "app_name" to DataMapValue.Text("Demo"), "title_raw" to DataMapValue.Text("Hi"), "text_raw" to DataMapValue.Text("Hello"),
            "show_when" to DataMapValue.Bool(true), "when" to DataMapValue.LongNumber(1),
            "post_time_millis" to DataMapValue.LongNumber(2), "revision" to DataMapValue.LongNumber(3),
        )
        val encoded = NotificationCodec.encode(NotificationContent(fields))
        assertContentEquals(hex(TEXT), encoded.data)
        val decoded = NotificationCodec.decode(hex(TEXT))
        assertEquals(fields, decoded.fields)
        assertTrue(decoded.notices.isEmpty())
        assertTrue(decoded.assetReferences.isEmpty())
        assertEquals(DataMapValue.LongNumber(3), decoded.fields["revision"])
        assertFalse("R_c" in decoded.fields)
    }

    @Test fun `asset references keep explicit indexes while attached files follow global sorted traversal`() {
        val refs = NotificationContent(fields = mapOf("icon" to DataMapValue.AssetIndex(0), "app_icon" to DataMapValue.AssetIndex(1)))
        assertContentEquals(hex(ASSETS), NotificationCodec.encode(refs).data)
        val bytes = byteArrayOf(8, 9)
        val shared = NotificationAsset(bytes, digest = "already-stored", mimeType = "image/webp")
        bytes[0] = 0
        val content = NotificationContent(
            assets = mapOf("app_icon" to shared),
            lists = mapOf(
                "pages" to listOf(NotificationContent(assets = mapOf("big_picture" to shared))),
                "actions" to listOf(NotificationContent(assets = mapOf("icon" to shared))),
            ),
            maps = mapOf("supplemental_assets" to NotificationContent(assets = mapOf("z" to shared))),
        )
        val encoded = NotificationCodec.encode(content)
        assertEquals(listOf("/actions/0/icon", "/app_icon", "/pages/0/big_picture", "/supplemental_assets/z"), encoded.assets.map { it.fieldPath })
        assertEquals(listOf("0", "1", "2", "3"), encoded.assets.map { it.name })
        assertTrue(encoded.assets.all { it.type == 4 && it.digest == "already-stored" && it.mimeType == "image/webp" })
        assertEquals(listOf(0L, 1L, 2L, 3L), NotificationCodec.decode(encoded.data).assetReferences.map { it.index })
        assertContentEquals(byteArrayOf(8, 9), encoded.assets.first().data)
        encoded.assets.first().data!![0] = 0
        assertContentEquals(byteArrayOf(8, 9), shared.data)
        assertEquals(4, encoded.assets.size) // Each occurrence has a slot; the factory does not deduplicate equal assets.
    }

    @Test fun `pages actions remote inputs and unknown style fields remain dynamic`() {
        val input = NotificationContent(fields = NotificationCodec.remoteInputFields("reply", "Say <hi>", listOf("A&B")))
        val action = NotificationContent(
            fields = NotificationCodec.actionFields("Reply", "opaque-local-id", true) + ("future_action_flag" to DataMapValue.IntNumber(9)),
            lists = mapOf("remote_inputs" to listOf(input)),
        )
        val content = NotificationContent(
            fields = NotificationCodec.textFields("Demo", "<Title>", "A&B\nOK", 10, 20, 30),
            lists = mapOf("actions" to listOf(action), "pages" to listOf(NotificationContent(mapOf("future_page_style" to DataMapValue.Text("custom"))))),
        )
        val decoded = NotificationCodec.decode(NotificationCodec.encode(content).data)
        assertEquals(DataMapValue.Text("&lt;Title&gt;"), decoded.fields["title_html"])
        assertEquals(DataMapValue.Text("A&amp;B<br>OK"), decoded.fields["text_html"])
        assertEquals(DataMapValue.Text("A&B\nOK"), decoded.fields["text_raw"])
        assertEquals(DataMapValue.IntNumber(9), decoded.actions.single()["future_action_flag"])
        val inputs = (decoded.actions.single()["remote_inputs"] as DataMapValue.ListValue).value
        val fields = (inputs.single() as DataMapValue.MapValue).value
        assertEquals(DataMapValue.Text("reply"), fields["return_key"])
        assertEquals(DataMapValue.Text("Say &lt;hi&gt;"), fields["label_html"])
        assertEquals(DataMapValue.Strings(listOf("A&amp;B")), fields["choices_html"])
        assertEquals(DataMapValue.Bool(true), fields["allowFreeFormInput"])
        assertEquals(DataMapValue.Text("custom"), decoded.pages.single()["future_page_style"])
    }

    @Test fun `reply RPC matches fixed vector with String invocation and ordinary message paths`() {
        val request = NotificationActionRequest(
            intentId = "intent-1", invocationId = "0", remoteStreamItemId = STREAM, actionIndex = 0,
            isWearableAction = false, replies = mapOf("reply" to NotificationReply(stringResult = "OK")),
        )
        assertContentEquals(hex(REPLY), NotificationRpcCodec.encodeRequest(request))
        val decoded = NotificationRpcCodec.decodeRequest(hex(REPLY))
        assertEquals("/bridger/rpc", NotificationProtocol.RPC_PATH)
        assertEquals("/bridger/result", NotificationProtocol.RESULT_PATH)
        assertTrue(decoded.isBridgedAction)
        assertEquals("0", decoded.invocationId)
        assertEquals("intent-1", decoded.intentId)
        assertEquals(0, decoded.actionIndex)
        assertEquals(false, decoded.isWearableAction)
        assertEquals("OK", decoded.replies["reply"]!!.stringResult)
        assertContentEquals(hex(REPLY), NotificationRpcCodec.encodeRequest(decoded))
        assertFalse(decoded.metadata.values.any { "intent-1" in it || "OK" in it || STREAM in it })
    }

    @Test fun `action result matches fixed nested Boolean and Int vector`() {
        assertContentEquals(hex(RESULT), NotificationRpcCodec.encodeResult("0", true))
        val result = NotificationRpcCodec.decodeResult(hex(RESULT))
        assertEquals("0", result.invocationId)
        assertEquals(true, result.successful)
        assertEquals(1, result.messageId)
        val failure = NotificationRpcCodec.decodeResult(NotificationRpcCodec.encodeResult("zzz", false))
        assertEquals("zzz", failure.invocationId)
        assertEquals(false, failure.successful)
        assertEquals(1, failure.messageId)
        assertNull(NotificationRpcCodec.decodeResult(byteArrayOf()).successful)
    }

    @Test fun `unknown commands numeric invocation IDs and reply alternatives are retained without inferred success`() {
        val unknown = mapOf(
            "rpc_command" to DataMapValue.Text("future-command-secret"), "intent_id" to DataMapValue.Text("opaque-secret"),
            NotificationProtocol.INVOCATION_ID to DataMapValue.LongNumber(47), "future" to DataMapValue.Bool(true),
            "remote_input_results" to DataMapValue.MapValue(mapOf("reply" to DataMapValue.MapValue(mapOf(
                "string_result" to DataMapValue.Text("password-secret"), "char_sequence_html" to DataMapValue.Text("<b>alternate-secret</b>"),
            )))),
        )
        val request = NotificationRpcCodec.decodeRequest(NotificationCodec.encode(NotificationContent(unknown)).data)
        assertFalse(request.isBridgedAction)
        assertNull(request.invocationId)
        assertEquals(DataMapValue.LongNumber(47), request.fields[NotificationProtocol.INVOCATION_ID])
        assertEquals(DataMapValue.Bool(true), request.fields["future"])
        assertEquals("password-secret", request.replies["reply"]!!.stringResult)
        assertEquals("<b>alternate-secret</b>", request.replies["reply"]!!.charSequenceHtml)
        assertTrue(request.notices.any { it.code == "unsupported_rpc_command" })
        assertTrue(request.notices.any { it.field == NotificationProtocol.INVOCATION_ID && it.code == "field_value_type" })
        assertFalse(request.metadata.toString().contains("secret"))
        val malformedResult = NotificationCodec.encode(NotificationContent(mapOf(
            NotificationProtocol.RESULT to DataMapValue.MapValue(mapOf(NotificationProtocol.SUCCESSFUL to DataMapValue.IntNumber(1))),
        ))).data
        assertNull(NotificationRpcCodec.decodeResult(malformedResult).successful)
    }

    @Test fun `all fifteen DataMap types share the established canonical encoding`() {
        val fields = mapOf(
            "1" to DataMapValue.Bytes(byteArrayOf(1, 2)), "2" to DataMapValue.Text("value中"),
            "3" to DataMapValue.DoubleNumber(1.25), "4" to DataMapValue.FloatNumber(-2.5f), "5" to DataMapValue.LongNumber(Long.MIN_VALUE),
            "6" to DataMapValue.IntNumber(-1), "7" to DataMapValue.ByteNumber(-47), "8" to DataMapValue.Bool(false),
            "9" to DataMapValue.MapValue(mapOf("nested" to DataMapValue.Text("yes"))),
            "10" to DataMapValue.ListValue(listOf(DataMapValue.Null, DataMapValue.MapValue(emptyMap()))),
            "11" to DataMapValue.Strings(listOf("", "a")), "12" to DataMapValue.Longs(listOf(0, -1)),
            "13" to DataMapValue.AssetIndex(-7), "14" to DataMapValue.Null, "15" to DataMapValue.Floats(listOf(1f, 2f)),
        )
        val encoded = NotificationCodec.encode(NotificationContent(fields)).data
        assertContentEquals(DataMapCodec.encode(fields), encoded)
        val decoded = NotificationCodec.decode(encoded)
        fields.filterKeys { it != "1" }.forEach { (key, value) -> assertEquals(value, decoded.fields[key]) }
        assertContentEquals(byteArrayOf(1, 2), (decoded.fields["1"] as DataMapValue.Bytes).value)
    }

    @Test fun `semantic gaps invalid text and nested unknown tags preserve recognized neighbors`() {
        val unknown = ProtoWire.encodedField(99, 0, ProtoWire.varint(12uL))
        val recognized = entry("ok", hex("08021203120141") + unknown)
        val invalidText = entry("text", hex("080212031201ff"))
        val missingType = entry("future", hex("1203120142"))
        val missingValue = ProtoWire.encodedField(1, 2, ProtoWire.encodedField(1, 2, "absent".toByteArray()))
        val missingName = ProtoWire.encodedField(1, 2, ProtoWire.encodedField(2, 2, hex("0802")))
        val bytes = recognized + invalidText + missingType + missingValue + missingName + unknown
        val decoded = NotificationCodec.decode(bytes)
        assertEquals(DataMapValue.Text("A"), decoded.fields["ok"])
        assertEquals(DataMapValue.Text("\uFFFD"), decoded.fields["text"])
        assertIs<DataMapValue.Unknown>(decoded.fields["future"])
        assertIs<DataMapValue.Unknown>(decoded.fields["absent"])
        assertTrue(decoded.notices.any { it.code == "missing_value_type" })
        assertTrue(decoded.notices.any { it.code == "missing_entry_value" })
        assertTrue(decoded.notices.any { it.code == "missing_entry_name" })
        assertTrue(decoded.notices.any { it.code == "replaced_invalid_utf8" })
        assertEquals(2, decoded.wireUnknownFields.count { it.field.number == 99 })
        assertContentEquals(bytes, decoded.raw)
        decoded.raw[0] = 0
        assertContentEquals(bytes, decoded.raw)
    }

    @Test fun `packed arrays mixed list types duplicate keys and unfamiliar discriminator remain best effort`() {
        val longValues = ProtoWire.encodedField(12, 2, ProtoWire.varint(1uL) + ProtoWire.varint((-2L).toULong()))
        val floatValues = ProtoWire.encodedField(14, 2, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(1.5f).putFloat(-2f).array())
        val bytes = entry("longs", hex("080c") + ProtoWire.encodedField(2, 2, longValues)) +
            entry("floats", hex("080f") + ProtoWire.encodedField(2, 2, floatValues)) +
            entry("future", hex("0847") + ProtoWire.encodedField(2, 2, hex("0801"))) +
            entry("duplicate", hex("08021203120141")) + entry("duplicate", hex("08021203120142"))
        val decoded = NotificationCodec.decode(bytes)
        assertEquals(DataMapValue.Longs(listOf(1, -2)), decoded.fields["longs"])
        assertEquals(DataMapValue.Floats(listOf(1.5f, -2f)), decoded.fields["floats"])
        assertEquals(71, (decoded.fields["future"] as DataMapValue.Unknown).type)
        assertEquals(DataMapValue.Text("B"), decoded.fields["duplicate"])
        assertTrue(decoded.notices.any { it.code == "duplicate_key" })
    }

    @Test fun `only structural truncation and explicit local allocation budgets reject bytes`() {
        assertTrue(NotificationCodec.decode(byteArrayOf()).fields.isEmpty())
        assertFailsWith<ProtocolException> { NotificationCodec.decode(hex("0a7f")) }
        assertFailsWith<ProtocolException> { NotificationCodec.decode(hex(TEXT), NotificationCodecLimits(maxDataBytes = 100)) }
        assertFailsWith<ProtocolException> { NotificationCodec.decode(hex(TEXT), NotificationCodecLimits(maxFields = 5)) }
        assertFailsWith<ProtocolException> { NotificationCodec.encode(NotificationContent(assets = mapOf("icon" to NotificationAsset(byteArrayOf(1)))), NotificationCodecLimits(maxAssets = 0)) }
        assertFailsWith<ProtocolException> { NotificationCodec.encode(NotificationContent(assets = mapOf("icon" to NotificationAsset(byteArrayOf(1, 2)))), NotificationCodecLimits(maxAssetBytes = 1)) }
        assertFailsWith<ProtocolException> { NotificationCodec.encode(NotificationContent(assets = mapOf("a" to NotificationAsset(byteArrayOf(1)), "b" to NotificationAsset(byteArrayOf(2)))), NotificationCodecLimits(maxTotalAssetBytes = 1)) }
        val nested = NotificationContent(maps = mapOf("a" to NotificationContent(maps = mapOf("b" to NotificationContent(fields = mapOf("x" to DataMapValue.Text("ok")))))))
        assertFailsWith<ProtocolException> { NotificationCodec.encode(nested, NotificationCodecLimits(maxDepth = 1)) }
        assertFailsWith<ProtocolException> { NotificationCodec.decode(NotificationCodec.encode(nested).data, NotificationCodecLimits(maxDepth = 1)) }
        assertFailsWith<ProtocolException> { NotificationProtocol.parseStreamItemPath(STREAM, NotificationCodecLimits(maxPathChars = 4)) }
    }

    private fun entry(key: String, typed: ByteArray): ByteArray = ProtoWire.encodedField(1, 2,
        ProtoWire.encodedField(1, 2, key.toByteArray()) + ProtoWire.encodedField(2, 2, typed))
    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        private const val STREAM = "/bridger/stream_item:p:example.demo:7:null_tag:0~7Cexample.demo~7C7~7Cnull"
        // Independent synthetic vectors; format documented in docs/spec/companion.md.
        private const val TEXT = "0a160a086170705f6e616d65120a08021206120444656d6f0a1a0a10706f73745f74696d655f6d696c6c697312060805120228020a120a087265766973696f6e12060805120228030a130a0973686f775f7768656e12060808120240010a170a08746578745f726177120b08021207120548656c6c6f0a150a097469746c655f726177120808021204120248690a0e0a047768656e1206080512022801"
        private const val ASSETS = "0a120a086170705f69636f6e1206080d120268010a0e0a0469636f6e1206080d12026800"
        private const val REPLY = "0a160a0c616374696f6e5f696e64657812060806120230000a4d0a42636f6d2e676f6f676c652e616e64726f69642e636c6f636b776f726b2e616374696f6e732e5270635769746843616c6c6261636b2e696e766f636174696f6e5f69641207080212031201300a1b0a09696e74656e745f6964120e0802120a1208696e74656e742d310a1c0a1269735f7765617261626c655f616374696f6e12060808120240000a460a1472656d6f74655f696e7075745f726573756c7473122e0809122a4a280a057265706c79121f0809121b4a190a0d737472696e675f726573756c7412080802120412024f4b0a690a1572656d6f74655f73747265616d5f6974656d5f696412500802124c124a2f627269646765722f73747265616d5f6974656d3a703a6578616d706c652e64656d6f3a373a6e756c6c5f7461673a307e37436578616d706c652e64656d6f7e3743377e37436e756c6c0a230a0b7270635f636f6d6d616e64121408021210120e627269646765645f616374696f6e"
        private const val RESULT = "0a4d0a42636f6d2e676f6f676c652e616e64726f69642e636c6f636b776f726b2e616374696f6e732e5270635769746843616c6c6261636b2e696e766f636174696f6e5f69641207080212031201300adb010a3b636f6d2e676f6f676c652e616e64726f69642e636c6f636b776f726b2e616374696f6e732e5270635769746843616c6c6261636b2e726573756c74129b0108091296014a490a3f636f6d2e676f6f676c652e616e64726f69642e636c6f636b776f726b2e616374696f6e732e5270635769746843616c6c6261636b2e6d6573736167655f696412060806120230014a490a3f636f6d2e676f6f676c652e616e64726f69642e636c6f636b776f726b2e616374696f6e732e5270635769746843616c6c6261636b2e7375636365737366756c1206080812024001"
    }
}
