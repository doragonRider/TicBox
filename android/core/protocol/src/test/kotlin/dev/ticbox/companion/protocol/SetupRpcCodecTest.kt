package dev.ticbox.companion.protocol

import kotlin.test.*

/** Literal vectors follow recovered field numbers; no device execution or activation claim. */
class SetupRpcCodecTest {
    @Test fun setupTimeAndDecisionsMatchRecoveredWireFields() {
        assertContentEquals(hex("0805320c1080d095ffbc312a03555443"), SetupProtocol.encodeTime(1_700_000_000_000, "UTC"))
        assertContentEquals(hex("080322020803"), SetupProtocol.encodeStatus(3))
        assertContentEquals(hex("08021a0a0803120608071002182a"), SetupProtocol.encodeOptin(7, 2, 42))
        assertContentEquals(hex("08074200"), SetupProtocol.encodeConnectionInfo(null, null, null))
    }

    @Test fun setupRpcHasNoChannelLengthPrefixAndPreservesCompatibilityFacts() {
        assertContentEquals(hex("0802"), SetupRpcCodec.encodeTetherConfig(2))
        assertContentEquals(hex("0801"), SetupRpcCodec.encodeRestoreSkipped())
        val response = SetupRpcCodec.decodeCheckin(hex("080108031001980601"))
        assertEquals(3, response["status"])
        assertEquals(true, response["factoryReset"])
        assertEquals(listOf(99), response.unknownFields.map { it.number })
        assertTrue(response.notices.any { it.kind == "last_singular_value" })
        assertEquals(emptyList(), SetupRpcCodec.decodeEmptyResponse(byteArrayOf()))
        assertEquals(listOf(100), SetupRpcCodec.decodeEmptyResponse(hex("a00601")).map { it.number })
    }

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
