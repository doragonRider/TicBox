package dev.ticbox.companion.protocol

import kotlin.test.*

class CallCodecTest {
    @Test fun actionRejectDialAndDtmfMatchObservedFieldNumbers() {
        assertEquals(8, CallCodec.decodeCommand(CallProtocol.ACTION_REQUEST, CallCodec.action("", 8)).action)
        val reject = CallCodec.decodeCommand(CallProtocol.REJECT_REQUEST, CallCodec.reject("id", 2, "later"))
        assertEquals("id", reject.callId); assertEquals(2, reject.reason); assertEquals("later", reject.textMessage)
        assertEquals("+123", CallCodec.decodeCommand(CallProtocol.DIAL_REQUEST, CallCodec.dial("+123")).number)
        assertEquals("#", CallCodec.decodeCommand(CallProtocol.DTMF_REQUEST, CallCodec.dtmf("id", "#")).code)
    }

    @Test fun unknownValuesDuplicateFieldsAndUnexpectedKnownWireTypesAreBestEffort() {
        val payload = CallCodec.action("id", 1) + ProtoWire.encodedField(2, 2, byteArrayOf(1)) +
            ProtoWire.encodedField(2, 0, ProtoWire.varint(109u)) + ProtoWire.encodedField(101, 0, ProtoWire.varint(7u))
        val decoded = CallCodec.decodeCommand(CallProtocol.ACTION_REQUEST, payload)
        assertEquals(109, decoded.action)
        assertEquals("id", decoded.callId)
        assertTrue(decoded.notices.contains("field_2_unexpected_wire_type"))
        assertTrue(decoded.notices.contains("field_2_last_value_used"))
        assertTrue(decoded.notices.contains("unknown_field_101"))
    }

    @Test fun callRetainsDiscontinuousStatesDirectionAndOptionalFields() {
        val cause = CallCodec.disconnectCause(2, "label", "description", "reason", 4)
        val fields = ProtoWire.decodeFields(CallCodec.call(CallWireState("call", 13, "123", 2, "name", 1,
            0x40, 0x10, cause, 12L, 0, 4L, "contact", -1, false, byteArrayOf(8, 1), byteArrayOf(10, 0))))
        assertEquals(13uL, fields.single { it.number == 1 }.value)
        assertEquals(ULong.MAX_VALUE, fields.single { it.number == 14 }.value)
        assertEquals(0uL, fields.single { it.number == 15 }.value)
        assertContentEquals(cause, fields.single { it.number == 9 }.value as ByteArray)
        assertContentEquals(byteArrayOf(8, 1), fields.single { it.number == 16 }.value as ByteArray)
        assertEquals((1..17).toList(), fields.map { it.number })
    }

    @Test fun audioMuteHasOnlyTheObservedBooleanAndMalformedFramingIsReported() {
        assertContentEquals(byteArrayOf(8, 1), CallCodec.audioState(true))
        assertFailsWith<ProtocolException> { CallCodec.decodeCommand(CallProtocol.ACTION_REQUEST, byteArrayOf(10, 9, 1)) }
    }
}
