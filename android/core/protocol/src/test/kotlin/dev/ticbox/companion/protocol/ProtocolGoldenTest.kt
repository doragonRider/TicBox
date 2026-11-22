package dev.ticbox.companion.protocol

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixtures come from existing src/gms-record.mjs, gms-messages.mjs and setup-channel.mjs.
 * They are synthetic golden vectors, not recordings from a successfully connected watch.
 */
class ProtocolGoldenTest {
    private val fixtures = Properties().apply {
        requireNotNull(ProtocolGoldenTest::class.java.getResourceAsStream("/node-golden.properties")).use(::load)
    }
    private fun fixture(name: String) = hex(fixtures.getProperty(name))

    @Test fun connectMatchesExistingNodeEncoder() {
        val message = ConnectMessage("phone-node", "Protocol fixture", -1L, networkId = "network", companionPackage = "dev.ticbox.companion", isMigrating = false, senderSdkVersion = 34)
        assertContentEquals(fixture("connect"), WearMessages.encodeConnect(message))
        val decoded = assertNotNull(WearMessages.connectOrNull(fixture("connect")))
        assertEquals(-1L, decoded.androidId)
        assertEquals(34, decoded.senderSdkVersion)
        assertFalse(assertNotNull(decoded.isMigrating))
        assertContentEquals(fixture("connect"), WearMessages.encodeConnect(decoded))
        val piece = MessagePieces.encode(fixture("connect")).single()
        assertContentEquals(fixture("connectRecord"), GmsRecord.encode(piece))
    }

    @Test fun connectPresenceDoesNotInventSenderDefaults() {
        val minimal = hex("3a060a016e12016d")
        val decoded = assertNotNull(WearMessages.connectOrNull(minimal))
        assertEquals(0, decoded.connectionVersion)
        assertEquals(0, decoded.wireVersion)
        assertEquals(setOf(1, 2), decoded.presentFields)
        assertContentEquals(minimal, WearMessages.encodeConnect(decoded))
        assertNull(WearMessages.connectOrNull(fixture("emptyHeartbeat")))
        assertContentEquals(hex("5a00"), WearMessages.encodeHeartbeat())
    }

    @Test fun rpcKindsAndCorrelationFieldsMatchFixedNodeVectors() {
        val message = RpcMessage(7, "app", "digest", "watch-node", "/example", byteArrayOf(0, -1, 1), "phone-node", false, 123, generation = 2, messageOption = 1)
        assertContentEquals(fixture("rpcMessage"), WearMessages.encodeRpc(message))
        val decoded = WearMessages.decodeRpcs(fixture("rpcMessage")).single()
        assertEquals(RpcKind.MESSAGE, decoded.kind)
        assertEquals(2, decoded.generation)
        assertEquals(123, decoded.responseCorrelationToken)
        assertEquals(1, decoded.messageOption)
        assertFalse(assertNotNull(decoded.expectsResponse))
        assertContentEquals(message.payload, decoded.payload)
        assertContentEquals(fixture("rpcMessage"), WearMessages.encodeRpc(decoded))

        val channel = WearMessages.decodeRpcs(fixture("rpcChannel")).single()
        assertEquals(RpcKind.CHANNEL, channel.kind)
        assertEquals("", channel.path)
        assertEquals("/setup/channel", channel.channel?.control?.path)
        assertFalse(channel.payloadPresent)
        assertContentEquals(fixture("rpcChannel"), WearMessages.encodeRpc(channel))

        val service = WearMessages.decodeRpcs(fixture("rpcService")).single()
        assertEquals(RpcKind.SERVICE, service.kind)
        assertTrue(assertNotNull(service.expectsResponse))
        assertTrue(service.payloadPresent)
        assertTrue(service.payload.isEmpty())
        assertContentEquals(fixture("rpcService"), WearMessages.encodeRpc(service))
    }

    @Test fun channelFixed64IdsAndSignedSequenceKeepAllBits() {
        val open = ChannelRequest(control = ChannelControl(type = 1, channelId = 0x0102030405060708uL, fromChannelOpener = true, path = "/setup/channel"), minimumVersion = 0, origin = 0)
        assertContentEquals(fixture("channelOpen"), ChannelCodec.encode(open))
        // Field 2 is wire type 1, not a varint; bytes are little endian.
        assertContentEquals(hex("121d08011108070605040302011801320e2f73657475702f6368616e6e656c30003800"), ChannelCodec.encode(open))
        val data = assertNotNull(ChannelCodec.decode(fixture("channelData")).data)
        assertEquals(ULong.MAX_VALUE, data.header?.channelId)
        assertEquals(-1L, data.header?.sequence)
        assertTrue(assertNotNull(data.final))
        assertContentEquals(byteArrayOf(0, 1, -1), data.data)
        assertContentEquals(fixture("channelData"), ChannelCodec.encode(ChannelCodec.decode(fixture("channelData"))))
        val ack = assertNotNull(ChannelCodec.decode(fixture("channelAck")).ack)
        assertEquals(300L, ack.header?.sequence)
        assertFalse(assertNotNull(ack.final))
    }

    @Test fun channelRuntimeValidationFollowsSelectedReceiverPriority() {
        ChannelCodec.validateForDelivery(ChannelRequest(minimumVersion = -1, origin = 0))
        assertFailsWith<ProtocolException> { ChannelCodec.validateForDelivery(ChannelRequest(minimumVersion = 2)) }
        assertFailsWith<ProtocolException> { ChannelCodec.validateForDelivery(ChannelRequest(origin = 2)) }
        ChannelCodec.validateForDelivery(ChannelRequest(control = ChannelControl(type = 99)))
        assertEquals(99, ChannelCodec.decode(ChannelCodec.encode(ChannelRequest(control = ChannelControl(type = 99)))).control?.type)
        assertFailsWith<ProtocolException> { ChannelCodec.validateForDelivery(ChannelRequest(data = ChannelData(data = ByteArray(65537)))) }
        ChannelCodec.validateForDelivery(ChannelRequest(data = ChannelData(data = ByteArray(65536))))
        // An independently present control is selected before an unrelated data member.
        ChannelCodec.validateForDelivery(ChannelRequest(control = ChannelControl(type = 1), data = ChannelData(data = ByteArray(65537))))
    }

    @Test fun genericRecoveredSchemasReencodeReferenceEnvelopes() {
        val names = mapOf("connect" to "WearMessage", "rpcMessage" to "WearMessage", "rpcChannel" to "WearMessage", "rpcService" to "WearMessage", "channelOpen" to "ChannelRequest", "channelData" to "ChannelRequest", "channelAck" to "ChannelRequest", "setupInfo" to "SetupMessage", "emptyHeartbeat" to "WearMessage", "dataMapSigned" to "DataMap")
        names.forEach { (fixtureName, schema) ->
            assertContentEquals(fixture(fixtureName), GmsStructs.encode(GmsStructs.decode(schema, fixture(fixtureName))), fixtureName)
        }
        assertTrue(GmsStructs.names.size >= 47) // Original 46 structures, MessagePiece, and reviewed Setup RPCs.
    }

    @Test fun unknownContainerAndNestedFieldsRemainExactAndIndependentMembersSurvive() {
        val unknown19 = hex("9a01020801")
        val unknown31 = hex("f80107")
        val wire = fixture("connect") + fixture("emptyHeartbeat") + unknown19 + unknown31
        val decoded = WearMessages.decode(wire)
        assertEquals(listOf(19, 31), decoded.unknownFields.map { it.number })
        assertNotNull(decoded["connect"])
        assertNotNull(decoded["heartbeat"])
        assertContentEquals(wire, WearMessages.encode(decoded))
        val exposed = decoded.unknownFields.first().raw
        exposed.fill(0)
        assertContentEquals(unknown19, decoded.unknownFields.first().raw)

        val nested = GmsStructs.decode("Connect", hex("0a016e12016df80107"))
        assertContentEquals(hex("0a016e12016df80107"), GmsStructs.encode(nested))
        assertFailsWith<ProtocolException> {
            GmsStructs.encode("Connect", mapOf("nodeId" to "n", "nodeName" to "m"), listOf(ProtoField.fromEncoded(hex("0a0178"))))
        }
    }

    @Test fun signedZigzagAndPackedNumericFieldsMatchIndependentVectors() {
        val storage = GmsStructs.decode("DataMapStorage", hex("19000000000000f43f250000c03f28ffffffffffffffffff0138ff016803"))
        assertEquals(1.25, storage["doubleValue"])
        assertEquals(1.5f, storage["floatValue"])
        assertEquals(-1L, storage["longValue"])
        assertEquals(-128, storage["byteValue"])
        assertEquals(3L, storage["assetIndex"])
        assertContentEquals(hex("38ff01"), GmsStructs.encode("DataMapStorage", mapOf("byteValue" to -128)))
        assertEquals(128, GmsStructs.decode("DataMapStorage", hex("388002"))["byteValue"])
        // The raw storage preserves +128. Java byte narrowing belongs to the DataMap business adapter.
        val packed = GmsStructs.decode("DataMapStorage", hex("620b01ffffffffffffffffff0172080000803f000000c0"))
        assertEquals(listOf(1L, -1L), packed["longArray"])
        assertEquals(listOf(1f, -2f), packed["floatArray"])
        assertContentEquals(hex("600160ffffffffffffffffff01750000803f75000000c0"), GmsStructs.encode(packed))
        assertFailsWith<ProtocolException> { GmsStructs.decode("DataMapStorage", hex("7203000000")) }
    }

    @Test fun malformedAndAmbiguousProtobufDoesNotGetGuessed() {
        assertFailsWith<ProtocolException> { GmsStructs.decode("Connect", hex("0a016e")) }
        assertFailsWith<ProtocolException> { GmsStructs.decode("SyncEntry", hex("0a01ff")) }
        val wrongTag = GmsStructs.decode("ChannelHeader", hex("0801"))
        assertTrue(wrongTag.values.isEmpty())
        assertContentEquals(hex("0801"), GmsStructs.encode(wrongTag))
        assertEquals("unknown_wire_type", wrongTag.notices.single().kind)
        assertFailsWith<ProtocolException> { ProtoWire.decodeFields(hex("00")) }
        assertFailsWith<ProtocolException> { ProtoWire.decodeFields(hex("0b")) }
        assertFailsWith<ProtocolException> { ProtoWire.decodeFields(hex("08ffffffffffffffffff02")) }
        assertFailsWith<ProtocolException> { ProtoWire.decodeFields(hex("08ffffffffffffffffffff00")) }
        assertFailsWith<ProtocolException> { ProtoWire.decodeFields(hex("1202ff")) }
        assertFailsWith<ProtocolException> { GmsStructs.encode("SyncEntry", mapOf("sequenceId" to 1.0)) }
        assertFailsWith<ProtocolException> { GmsStructs.encode("Connect", mapOf("nodeId" to "\uD800", "nodeName" to "n")) }
        assertFailsWith<ProtocolException> { GmsStructs.encode("Heartbeat", mapOf("typo" to 1)) }
        val max = ProtoWire.decodeFields(hex("08ffffffffffffffffff01")).single()
        assertEquals(ULong.MAX_VALUE, max.value)
    }

    @Test fun protobufSingularValuesFollowLastValueAndSubmessageMergeSemantics() {
        val scalar = GmsStructs.decode("SyncEntry", hex("10011002"))
        assertEquals(2L, scalar["sequenceId"])
        assertEquals(2, scalar.notices.single().occurrences)
        // Required Connect fields can be split across occurrences of the optional submessage.
        val envelope = WearMessages.decode(hex("3a030a016e3a0312016d3a03120178"))
        val connect = assertNotNull(WearMessages.connectOrNull(envelope))
        assertEquals("n", connect.nodeId)
        assertEquals("x", connect.nodeName)
        assertTrue(envelope.notices.any { it.kind == "merged_singular_message" && it.occurrences == 3 })
        assertTrue(envelope.notices.any { it.path == "WearMessage.connect.nodeName" && it.kind == "last_singular_value" })
        assertContentEquals(hex("3a060a016e120178"), WearMessages.encode(envelope))
    }

    @Test fun protobufBudgetsBoundRepeatedFieldsNestingAndEncodedOutput() {
        assertFailsWith<ProtocolException> { ProtoWire.decodeFields(hex("080108020803"), maxFields = 2) }
        assertFailsWith<ProtocolException> { GmsStructs.decode("Heartbeat", ByteArray(ProtocolLimits.MAX_MESSAGE_BYTES + 1)) }
        assertFailsWith<ProtocolException> { GmsStructs.encode("SetupCompanionTime", mapOf("timeZoneId" to "x".repeat(ProtocolLimits.MAX_MESSAGE_BYTES))) }
        var nested: Map<String, Any> = mapOf("type" to 14)
        repeat(40) { nested = mapOf("type" to 9, "storage" to mapOf("mapEntries" to listOf(mapOf("name" to "x", "value" to nested)))) }
        val deep = nested
        assertFailsWith<ProtocolException> { GmsStructs.encode("DataMapValue", deep) }
    }

    @Test fun setupQueriesCapabilitiesAndUnknownEnumsMatchReference() {
        assertContentEquals(hex("000000080801120408011001"), SetupProtocol.frame(SetupProtocol.encodeQuery(SetupQuery.GET_STATUS, 1L)))
        assertContentEquals(hex("080112020802"), SetupProtocol.encodeQuery(SetupQuery.GET_SYSTEM_INFO))
        assertContentEquals(fixture("setupQuery"), SetupProtocol.frame(SetupProtocol.encodeQuery(SetupQuery.GET_STATUS, Long.MIN_VALUE)))
        val info = assertNotNull(SetupProtocol.decode(fixture("setupInfo"))["systemInfo"] as? DecodedStruct)
        assertEquals(listOf(1, 6), info["capabilities"])
        assertEquals(listOf(2, 5, 6), info["setupCapabilities"])
        assertEquals(33, info["platformVersion"])
        assertContentEquals(hex("08041001100618012021280032030205063801"), GmsStructs.encode(info))
        val unknown = SetupProtocol.decode(hex("08633a01ff"))
        assertEquals(99, unknown["type"])
        assertContentEquals(hex("3a01ff"), unknown.unknownFields.single().raw)
        assertContentEquals(hex("08633a01ff"), SetupProtocol.encode(unknown))
        assertTrue(SetupProtocol.decode(byteArrayOf()).values.isEmpty())
    }
}

internal fun hex(text: String): ByteArray {
    val value = text.filterNot(Char::isWhitespace)
    require(value.length % 2 == 0)
    return ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
