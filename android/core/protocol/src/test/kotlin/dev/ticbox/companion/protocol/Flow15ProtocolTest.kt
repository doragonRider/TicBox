package dev.ticbox.companion.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class Flow15ProtocolTest {
    @Test fun `phone output matches independent stock ByteBuffer vectors`() {
        assertContentEquals(hex("11000000050000"), Flow15Codec.handshakeAck())
        val cases = listOf(
            Flow15Frame.Data(3, 0x01020304, hex("deadbeef")) to "03010203040004deadbeef",
            Flow15Frame.Data(9, -1, hex("000102")) to "09ffffffff0003000102",
            Flow15Frame.TcpClose(Int.MIN_VALUE) to "0280000000",
            Flow15Frame.NetworkState(1, 1) to "0b0000000101",
            Flow15Frame.Ping(-1) to "05ffffffff",
        )
        cases.forEach { (frame, bytes) -> assertContentEquals(hex(bytes), Flow15Codec.encode(frame)) }
    }

    @Test fun `all known boundary fixtures survive every possible split and bytewise reads`() {
        val vectors = listOf(
            "01ffffffffcb007109ffffffff", "02ffffffff", "03010203040004deadbeef",
            "04c000020100350003616263", "05ffffffff", "06ffffffff",
            "07c000020100350001ff", "080000000100010001", "09ffffffff000100",
            "0aaabb0003fffefd", "0bffffffff02", "0cffffffffffffffff", "0dffffffff000100",
            "0ec00002021234c6336401003556780001ff", "0fc00002021234c633640100350001ff",
            "10ffffffff0002aabb", "11000000050000", "12ffffffff0000",
        ).map(::hex)
        val wire = vectors.fold(byteArrayOf()) { all, bytes -> all + bytes }
        for (split in 0..wire.size) {
            val decoder = Flow15StreamDecoder()
            val frames = decoder.push(wire.copyOfRange(0, split)) + decoder.push(wire.copyOfRange(split, wire.size))
            assertEquals((1..18).toList(), frames.map { it.opcode })
            vectors.zip(frames).forEach { (expected, frame) -> assertContentEquals(expected, Flow15Codec.encode(frame)) }
            assertEquals(Flow15StreamStatus.COMPLETE, decoder.status)
        }
        val decoder = Flow15StreamDecoder()
        assertEquals(18, wire.flatMap { decoder.push(byteArrayOf(it)) }.size)
    }

    @Test fun `signed IDs opaque fields and noncanonical metered bytes remain observed values`() {
        val open = assertIs<Flow15Frame.TcpOpen>(Flow15Codec.decode(hex("01ffffffffcb007109ffffffff")))
        assertEquals(-1, open.id); assertEquals(65_535, open.port); assertEquals(65_535, open.opaque)
        val state = assertIs<Flow15Frame.NetworkState>(Flow15Codec.decode(hex("0b0000000102")))
        assertEquals(2, state.rawMetered); assertNull(state.isMetered)
        val start = assertIs<Flow15Frame.Handshake>(Flow15Codec.decode(hex("10ffffffff0001aa")))
        assertEquals(-1, start.version); assertContentEquals(hex("aa"), start.extensions)
    }

    @Test fun `unknown opcode stops without scanning the next plausible header`() {
        val decoder = Flow15StreamDecoder()
        assertEquals(1, decoder.push(hex("05000000017faa0500000002")).size)
        assertEquals(127, decoder.stoppedOpcode)
        assertEquals(Flow15StreamStatus.UNKNOWN, decoder.status)
        assertContentEquals(hex("7faa0500000002"), decoder.remainder)
        assertEquals(0, decoder.push(hex("ff")).size)
        assertContentEquals(hex("7faa0500000002ff"), decoder.remainder)
        assertIs<Flow15Frame.Unknown>(Flow15Codec.decode(hex("7faabb")))
    }

    @Test fun `truncation resource admission and defensive copies preserve a rejected tail`() {
        val decoder = Flow15StreamDecoder(8)
        decoder.push(hex("03000000010002ff"))
        assertEquals(Flow15StreamStatus.INCOMPLETE, decoder.status)
        assertFailsWith<IllegalArgumentException> { decoder.push(hex("0000")) }
        val tail = decoder.remainder
        tail[0] = 0
        assertEquals(3, decoder.remainder[0].toInt())
        assertFailsWith<IllegalArgumentException> { Flow15Codec.decode(hex("0500000001ff")) }
        assertFailsWith<IllegalArgumentException> { Flow15Codec.decode(hex("03000000010002ff")) }
        assertFailsWith<IllegalArgumentException> { Flow15Codec.decode(byteArrayOf()) }
        val frame = Flow15Frame.Data(3, 1, byteArrayOf(1))
        frame.payload[0] = 2
        assertContentEquals(byteArrayOf(1), frame.payload)
    }

    @Test fun `unsigned maximum payload and 15-byte UDP v2 failure header stay distinct`() {
        val frame = Flow15Frame.DatagramV2(0xc0000201.toInt(), 1, 0xc6336401.toInt(), 2, null, byteArrayOf())
        assertContentEquals(hex("0fc00002010001c633640100020000"), Flow15Codec.encode(frame))
        val bytes = Flow15Codec.encode(Flow15Frame.Data(3, Int.MAX_VALUE, ByteArray(65_535) { 0x5a }))
        val decoded = assertIs<Flow15Frame.Data>(Flow15Codec.decode(bytes))
        assertEquals(65_535, decoded.payload.size); assertEquals(0x5a, decoded.payload.last().toInt())
        assertFailsWith<IllegalArgumentException> { Flow15Frame.Data(3, 1, ByteArray(65_536)) }
    }

    private fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
