package dev.ticbox.companion.ui.toolbox

import dev.ticbox.companion.api.ToolboxScreenEvent
import kotlin.test.*
import org.junit.Test

/**
 * The expected parameter sets were read back with ffmpeg's trace_headers: every field the input had
 * stays, and only the bitstream restriction changes.
 */
class H264LowDelayTest {
    @Test
    fun aStreamWithoutVuiGetsOneThatDeclaresNoReordering() {
        // Constrained baseline 1072x2400, POC type 2, no VUI, as some MediaTek encoders send.
        assertRewrites("6742c02ada010c04b640", "6742c02ada010c04b6806d044235")
    }

    @Test
    fun x264HighProfileKeepsEveryOtherFieldAndStopsReordering() {
        // Field coding, cropping, NAL HRD, pic_struct, timing with escaped bytes, 2 reordered.
        assertRewrites(
            "6764001eacd940a063f3e02200000300020000030078c48001e848003d09c58601f1429960",
            "6764001eacd940a063f3e02200000300020000030078c48001e848003d09c58601f142a580",
        )
    }

    @Test
    fun scalingListsAPictureOrderCycleAndVclHrdSurvive() {
        // Explicit SPS scaling lists (one ending early), POC type 1, extended SAR, colour
        // description, chroma location, two HRD schedules and no restriction at all.
        assertRewrites(
            "67640028ad8d42c8451c70232350b21a331082e502818fcbff80020001fb808080d38000000300800000" +
                "1e1446007d0007d10009c4000bb85ef7c480",
            "67640028ad8d42c8451c70232350b21a331082e502818fcbff80020001fb808080d38000000300800000" +
                "1e1446007d0007d10009c4000bb85ef7c5b41108cb",
        )
    }

    @Test
    fun aStreamThatAlreadyDeclaresNoReorderingComesBackTheSame() {
        val sps = "6742c033d90043012db011000003000100000300788f183248"
        assertRewrites(sps, sps)
    }

    @Test
    fun onlyParameterSetsAheadOfTheFirstPictureChange() {
        val sps = "6764001eacd940a063f3e02200000300020000030078c48001e848003d09c58601f1429960"
        val rewritten = "6764001eacd940a063f3e02200000300020000030078c48001e848003d09c58601f142a580"
        val picture = "000001" + "6588820b01aff6f7d4b7ccb2" + "00000001" + "67" + sps.drop(2)
        val key =
            "0000000109f0" +
                "00000001" +
                sps +
                "00" +
                "0000000168ebe3cb22c0" +
                "00000106" +
                "0501ff80"
        val expected =
            "0000000109f0" +
                "00000001" +
                rewritten +
                "00" +
                "0000000168ebe3cb22c0" +
                "00000106" +
                "0501ff80"
        assertEquals(expected + picture, H264LowDelay.rewrite(hex(key + picture)).hex())
    }

    @Test
    fun whatDoesNotParseOrIsNoParameterSetIsLeftAlone() {
        val truncated = hex("00000001" + "6764001eacd940a063f3e022000003000200000300")
        assertSame(truncated, H264LowDelay.rewrite(truncated))
        val unknownPictureOrder = hex("00000001" + "6742c02ac440") // pic_order_cnt_type 7
        assertSame(unknownPictureOrder, H264LowDelay.rewrite(unknownPictureOrder))
        val trailingData = hex("00000001" + "6742c02ada010c04b641")
        assertSame(trailingData, H264LowDelay.rewrite(trailingData))
        val predicted = hex("00000001$P_SLICE")
        assertSame(predicted, H264LowDelay.rewrite(predicted))
        assertSame(EMPTY, H264LowDelay.rewrite(EMPTY))
    }

    @Test
    fun bSlicesAreTold() {
        assertFalse(H264LowDelay.hasBSlice(hex("00000001$P_SLICE")))
        assertTrue(H264LowDelay.hasBSlice(hex("00000001$B_SLICE")))
        assertTrue(H264LowDelay.hasBSlice(hex("0000000106" + "0501ff80" + "000001$B_SLICE")))
        assertFalse(H264LowDelay.hasBSlice(hex("00000001" + "6588820b01aff6f7d4b7ccb2")))
        assertFalse(H264LowDelay.hasBSlice(hex("000000014100")), "no slice type to read")
        assertFalse(H264LowDelay.hasBSlice(EMPTY))
    }

    @Test
    fun theDecoderGetsTheStreamsOwnParameterSetsBackWithTheKeyFrameAfterABPicture() {
        var told = 0
        val input = H264DecoderInput { told++ }
        val config = hex("00000001" + "6742c02ada010c04b640" + "0000000168ce3c80")
        val lowDelay = input.bytes(frame(config, config = true))
        assertEquals(
            "00000001" + "6742c02ada010c04b6806d044235" + "0000000168ce3c80",
            lowDelay.hex(),
        )
        val predicted = hex("00000001$P_SLICE")
        assertSame(predicted, input.bytes(frame(predicted)))
        assertTrue(input.lowDelay)

        val bidirectional = hex("00000001$B_SLICE")
        assertSame(bidirectional, input.bytes(frame(bidirectional)))
        assertFalse(input.lowDelay)
        assertEquals(1, told)
        assertSame(predicted, input.bytes(frame(predicted)))
        val key = hex("00000001" + "6588820b01aff6f7d4b7ccb2")
        assertContentEquals(config + key, input.bytes(frame(key, key = true)))
        assertSame(key, input.bytes(frame(key, key = true)), "restored once")
        assertSame(
            config,
            input.bytes(frame(config, config = true)),
            "a new configuration stays as sent",
        )
        assertEquals(1, told)
    }

    private fun assertRewrites(sps: String, expected: String) {
        assertEquals("00000001$expected", H264LowDelay.rewrite(hex("00000001$sps")).hex())
        assertEquals("000001$expected", H264LowDelay.rewrite(hex("000001$sps")).hex())
    }

    private fun frame(bytes: ByteArray, key: Boolean = false, config: Boolean = false) =
        ToolboxScreenEvent.Frame(0, key, config, bytes)

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        /** From the same x264 stream: slice_type 5 (P) and 6 (B). */
        const val P_SLICE = "419a225e41affefbb7a00000"
        const val B_SLICE = "019e60df441aff0000030000"
        val EMPTY = ByteArray(0)
    }
}
