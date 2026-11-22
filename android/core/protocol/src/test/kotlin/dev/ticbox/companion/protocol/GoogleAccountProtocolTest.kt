package dev.ticbox.companion.protocol

import kotlin.test.*

class GoogleAccountProtocolTest {
    @Test fun `stock START and opaque payload match independent protobuf wire vectors`() {
        assertContentEquals(hex("12020802"), GoogleAccountProtocol.start())
        assertContentEquals(hex("0000000412020802"), GoogleAccountProtocol.frame(GoogleAccountProtocol.start()))
        assertContentEquals(hex("080322050a03ff0001"), GoogleAccountProtocol.payload(hex("ff0001")))
        val status = GoogleAccountProtocol.decode(hex("08021a020802"))
        assertEquals(2, status.type); assertEquals(2, status.status); assertNull(status.payload)
    }
    @Test fun `all channel splits and coalesced independent fields survive decoding`() {
        val wire = hex("000000041a02080200000009080322050a03ff0001")
        for (split in 0..wire.size) {
            val decoder = GoogleAccountFrameDecoder()
            val frames = decoder.feed(wire.copyOfRange(0, split)) + decoder.feed(wire.copyOfRange(split, wire.size))
            assertEquals(2, frames.size)
            assertEquals(2, frames[0].status); assertContentEquals(hex("ff0001"), frames[1].payload)
            decoder.finish()
        }
        val envelope = GoogleAccountProtocol.decode(hex("120208021a02080222030a01ff2a0100"))
        assertEquals(2, envelope.command); assertEquals(2, envelope.status)
        assertContentEquals(hex("ff"), envelope.payload); assertEquals(listOf(5), envelope.unknownFieldNumbers)
    }
    @Test fun `unknown enums are observations while lengths truncation and malformed protobuf fail closed`() {
        assertEquals(47, GoogleAccountProtocol.decode(hex("082f")).type)
        assertFailsWith<ProtocolException> { GoogleAccountFrameDecoder(8).feed(hex("00000009")) }
        assertFailsWith<ProtocolException> { GoogleAccountFrameDecoder().feed(hex("ffffffff")) }
        val partial = GoogleAccountFrameDecoder(); partial.feed(hex("000000041a02"))
        assertFailsWith<ProtocolException> { partial.finish() }
        assertFailsWith<ProtocolException> { GoogleAccountProtocol.decode(hex("1a04ff")) }
    }
    @Test fun `host options use the observed nested DataMap and payload models copy opaque bytes`() {
        assertFalse(GoogleAccountHostOptions.reauthenticate(null))
        assertFalse(GoogleAccountHostOptions.reauthenticate(DataMapCodec.encode(emptyMap())))
        val options = DataMapCodec.encode(mapOf("transfer_options" to DataMapValue.MapValue(mapOf("reauth" to DataMapValue.Bool(true)))))
        assertTrue(GoogleAccountHostOptions.reauthenticate(options))
        assertFailsWith<ProtocolException> { GoogleAccountHostOptions.reauthenticate(hex("0a7f")) }
        val bytes = hex("ff01"); val envelope = GoogleAccountEnvelope(payload = bytes)
        bytes[0] = 0; envelope.payload!![0] = 0
        assertContentEquals(hex("ff01"), envelope.payload)
    }
    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
