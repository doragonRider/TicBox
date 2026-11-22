package dev.ticbox.companion.ui.toolbox

import dev.ticbox.companion.api.ToolboxScreenEvent
import java.io.ByteArrayOutputStream

/**
 * Declares in each H.264 sequence parameter set that no picture waits to be reordered. Without
 * that, a decoder has to assume reordering up to its whole picture buffer and keeps finished
 * pictures back, even in low-latency mode: up to hundreds of milliseconds with a Qualcomm decoder
 * and a MediaTek encoder. The sender asks its encoder for no B-frames, so output order is decode
 * order; [hasBSlice] notices an encoder that makes them anyway.
 *
 * Anything that doesn't parse as expected is left exactly as it came.
 */
/**
 * The H.264 a decoder gets. Parameter sets declare that nothing is reordered until a B picture
 * arrives; the decoder then gets the stream's own ones again with the next key frame, the earliest
 * point they may change, and shows the pictures before it in decode order.
 */
internal class H264DecoderInput(private val reorders: () -> Unit = {}) {
    var lowDelay = true
        private set

    private var original: ByteArray? = null
    private var restore: ByteArray? = null

    fun bytes(frame: ToolboxScreenEvent.Frame): ByteArray {
        if (frame.codecConfig) original = frame.bytes
        if (lowDelay) {
            if (frame.codecConfig || frame.keyFrame) return H264LowDelay.rewrite(frame.bytes)
            if (!H264LowDelay.hasBSlice(frame.bytes)) return frame.bytes
            lowDelay = false
            restore = original
            reorders()
            return frame.bytes
        }
        val config = restore?.takeIf { frame.keyFrame } ?: return frame.bytes
        restore = null
        return config + frame.bytes
    }
}

internal object H264LowDelay {
    private const val SPS = 7

    /** Rewrites the SPS NAL units ahead of the first picture in an Annex B buffer. */
    fun rewrite(annexB: ByteArray): ByteArray {
        var output: ByteArrayOutputStream? = null
        var copied = 0
        var start = nextStartCode(annexB, 0)
        while (start != null) {
            val payload = start.second
            if (payload >= annexB.size) break
            val type = annexB[payload].toInt() and 0x1F
            if (type in 1..5) break // Pictures follow; nothing after them needs a look.
            val next = nextStartCode(annexB, payload)
            if (type == SPS) {
                var end = next?.first ?: annexB.size
                while (end > payload && annexB[end - 1].toInt() == 0) end-- // trailing_zero_8bits
                val rewritten = rewriteSps(annexB.copyOfRange(payload, end))
                if (rewritten != null) {
                    val out = output ?: ByteArrayOutputStream(annexB.size + 16).also { output = it }
                    out.write(annexB, copied, payload - copied)
                    out.write(rewritten)
                    copied = end
                }
            }
            start = next
        }
        val out = output ?: return annexB
        out.write(annexB, copied, annexB.size - copied)
        return out.toByteArray()
    }

    /**
     * Whether the first picture in an Annex B buffer is made of B slices. Those may be shown before
     * pictures decoded earlier, so a stream that has them must keep its own declaration.
     */
    fun hasBSlice(annexB: ByteArray): Boolean {
        var start = nextStartCode(annexB, 0)
        while (start != null) {
            val payload = start.second
            if (payload >= annexB.size) return false
            when (annexB[payload].toInt() and 0x1F) {
                1,
                2 ->
                    return try {
                        val header = annexB.copyOfRange(payload, minOf(annexB.size, payload + 16))
                        val reader = BitReader(unescape(header, 1))
                        reader.ue() // first_mb_in_slice
                        reader.ue() % 5 == 1
                    } catch (_: Malformed) {
                        false
                    }
                3,
                4,
                5 -> return false
            }
            start = nextStartCode(annexB, payload)
        }
        return false
    }

    /** (start code position, first byte after it), or null when there's no further one. */
    private fun nextStartCode(bytes: ByteArray, from: Int): Pair<Int, Int>? {
        var i = from
        while (i + 2 < bytes.size) {
            if (bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0 && bytes[i + 2].toInt() == 1)
                return i to i + 3
            i++
        }
        return null
    }

    /** One escaped SPS NAL unit, header included; null to keep the original. */
    internal fun rewriteSps(nal: ByteArray): ByteArray? =
        try {
            val rbsp = unescape(nal, 1)
            val reader = BitReader(rbsp)
            val writer = BitWriter()
            copySps(reader, writer)
            byteArrayOf(nal[0]) + escape(writer.toByteArray())
        } catch (_: Malformed) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        }

    private fun copySps(r: BitReader, w: BitWriter) {
        val profile = r.bits(8)
        w.bits(profile, 8)
        w.bits(r.bits(16), 16) // constraint flags and level
        w.ue(r.ue()) // seq_parameter_set_id
        if (profile in HIGH_PROFILES) {
            val chroma = r.ue()
            w.ue(chroma)
            if (chroma == 3) w.bit(r.bit())
            w.ue(r.ue())
            w.ue(r.ue()) // bit depths
            w.bit(r.bit()) // qpprime_y_zero_transform_bypass_flag
            val scaling = r.bit()
            w.bit(scaling)
            if (scaling == 1)
                repeat(if (chroma != 3) 8 else 12) { index ->
                    val present = r.bit()
                    w.bit(present)
                    if (present == 1) copyScalingList(r, w, if (index < 6) 16 else 64)
                }
        }
        w.ue(r.ue()) // log2_max_frame_num_minus4
        val pocType = r.ue()
        w.ue(pocType)
        when (pocType) {
            0 -> w.ue(r.ue())
            1 -> {
                w.bit(r.bit())
                w.se(r.se())
                w.se(r.se())
                val cycle = r.ue()
                if (cycle > 255) throw Malformed()
                w.ue(cycle)
                repeat(cycle) { w.se(r.se()) }
            }
            2 -> Unit
            else -> throw Malformed()
        }
        val references = r.ue()
        w.ue(references)
        w.bit(r.bit()) // gaps_in_frame_num_value_allowed_flag
        w.ue(r.ue())
        w.ue(r.ue()) // picture size in macroblocks
        val frameOnly = r.bit()
        w.bit(frameOnly)
        if (frameOnly == 0) w.bit(r.bit())
        w.bit(r.bit()) // direct_8x8_inference_flag
        val cropping = r.bit()
        w.bit(cropping)
        if (cropping == 1) repeat(4) { w.ue(r.ue()) }
        val vui = r.bit()
        w.bit(1)
        // Without VUI: the eight presence flags from aspect ratio to pic_struct, all off.
        if (vui == 1) copyVuiBeforeRestriction(r, w) else repeat(8) { w.bit(0) }
        val restricted = vui == 1 && r.bit() == 1
        w.bit(1) // bitstream_restriction_flag
        if (restricted) {
            w.bit(r.bit())
            repeat(4) { w.ue(r.ue()) }
            r.ue()
            r.ue() // max_num_reorder_frames, max_dec_frame_buffering
        } else {
            // The values a decoder infers when they are absent.
            w.bit(1)
            w.ue(2)
            w.ue(1)
            w.ue(16)
            w.ue(16)
        }
        w.ue(0) // max_num_reorder_frames
        w.ue(maxOf(references, 1)) // max_dec_frame_buffering
        // Only rbsp_trailing_bits may follow; anything else is a syntax this doesn't know.
        if (r.bit() != 1 || !r.restIsZero()) throw Malformed()
        w.trailingBits()
    }

    private fun copyScalingList(r: BitReader, w: BitWriter, size: Int) {
        var last = 8
        var next = 8
        repeat(size) {
            if (next != 0) {
                val delta = r.se()
                w.se(delta)
                next = (last + delta + 256) % 256
            }
            if (next != 0) last = next
        }
    }

    /** Everything in vui_parameters() before bitstream_restriction_flag. */
    private fun copyVuiBeforeRestriction(r: BitReader, w: BitWriter) {
        if (copyFlag(r, w)) { // aspect_ratio_info_present_flag
            val idc = r.bits(8)
            w.bits(idc, 8)
            if (idc == 255) w.bits(r.bits(32), 32)
        }
        if (copyFlag(r, w)) w.bit(r.bit()) // overscan
        if (copyFlag(r, w)) { // video_signal_type_present_flag
            w.bits(r.bits(4), 4)
            if (copyFlag(r, w)) w.bits(r.bits(24), 24)
        }
        if (copyFlag(r, w)) {
            w.ue(r.ue())
            w.ue(r.ue())
        } // chroma_loc_info
        if (copyFlag(r, w)) {
            w.bits(r.bits(32), 32)
            w.bits(r.bits(32), 32)
            w.bit(r.bit())
        } // timing_info
        val nal = copyFlag(r, w)
        if (nal) copyHrd(r, w)
        val vcl = copyFlag(r, w)
        if (vcl) copyHrd(r, w)
        if (nal || vcl) w.bit(r.bit()) // low_delay_hrd_flag
        w.bit(r.bit()) // pic_struct_present_flag
    }

    private fun copyHrd(r: BitReader, w: BitWriter) {
        val count = r.ue()
        if (count > 31) throw Malformed()
        w.ue(count)
        w.bits(r.bits(8), 8) // bit_rate_scale, cpb_size_scale
        repeat(count + 1) {
            w.ue(r.ue())
            w.ue(r.ue())
            w.bit(r.bit())
        }
        w.bits(r.bits(20), 20) // four delay lengths
    }

    private fun copyFlag(r: BitReader, w: BitWriter): Boolean = r.bit().also(w::bit) == 1

    /** Drops each emulation_prevention_three_byte from [from] on. */
    internal fun unescape(nal: ByteArray, from: Int): ByteArray {
        val out = ByteArrayOutputStream(nal.size)
        var zeros = 0
        for (i in from until nal.size) {
            val b = nal[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) {
                zeros = 0
                continue
            }
            out.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    internal fun escape(rbsp: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(rbsp.size + 8)
        var zeros = 0
        for (byte in rbsp) {
            val b = byte.toInt() and 0xFF
            if (zeros >= 2 && b <= 3) {
                out.write(3)
                zeros = 0
            }
            out.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    private class Malformed : Exception()

    private class BitReader(private val bytes: ByteArray) {
        private var position = 0

        fun bit(): Int {
            if (position >= bytes.size * 8) throw Malformed()
            val value = (bytes[position ushr 3].toInt() ushr (7 - (position and 7))) and 1
            position++
            return value
        }

        fun bits(count: Int): Int {
            var value = 0
            repeat(count) { value = (value shl 1) or bit() }
            return value
        }

        fun ue(): Int {
            var zeros = 0
            while (bit() == 0) if (++zeros > 31) throw Malformed()
            return ((1L shl zeros) - 1 + bits(zeros).toLong().and(0xFFFFFFFFL)).toInt()
        }

        fun se(): Int {
            val code = ue().toLong() and 0xFFFFFFFFL
            return if (code and 1L == 1L) ((code + 1) / 2).toInt() else (-(code / 2)).toInt()
        }

        fun restIsZero(): Boolean {
            while (position < bytes.size * 8) if (bit() != 0) return false
            return true
        }
    }

    private class BitWriter {
        private val out = ByteArrayOutputStream()
        private var current = 0
        private var count = 0

        fun bit(value: Int) {
            current = (current shl 1) or (value and 1)
            if (++count == 8) {
                out.write(current)
                current = 0
                count = 0
            }
        }

        fun bits(value: Int, width: Int) {
            for (shift in width - 1 downTo 0) bit(value ushr shift)
        }

        fun ue(value: Int) {
            val code = (value.toLong() and 0xFFFFFFFFL) + 1
            val length = 63 - java.lang.Long.numberOfLeadingZeros(code)
            repeat(length) { bit(0) }
            for (shift in length downTo 0) bit((code ushr shift).toInt())
        }

        fun se(value: Int) = ue(if (value > 0) 2 * value - 1 else -2 * value)

        fun trailingBits() {
            bit(1)
            while (count != 0) bit(0)
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
