package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.ToolboxScreenEvent
import dev.ticbox.companion.protocol.ToolboxProtocol
import dev.ticbox.companion.protocol.number
import dev.ticbox.companion.protocol.text
import java.nio.ByteBuffer

internal data class IndependentVideoBoundary(val configuration: ToolboxScreenEvent.Configuration, val ptsMicros: Long)

/** Inspect authenticated message framing without copying the picture or codec initialization. */
internal fun independentVideoBoundary(bytes: ByteArray): IndependentVideoBoundary? = try {
    val input = ByteBuffer.wrap(bytes)
    if (input.remaining() < 5 || input.get().toInt() != ToolboxProtocol.VIDEO_CONFIG) null else {
        val configSize = input.int
        if (configSize !in 1..4096 || configSize > input.remaining()) null else {
            val configStart = input.position()
            input.position(configStart + configSize)
            var headers = 0
            var headerBytes = 0
            var pts: Long? = null
            var valid = true
            while (input.hasRemaining()) {
                if (input.remaining() < 5 || input.get().toInt() != ToolboxProtocol.VIDEO_FRAME) { valid = false; break }
                val length = input.int
                if (length <= 8 || length > input.remaining()) { valid = false; break }
                val end = input.position() + length
                val flags = input.long
                if (flags and Long.MIN_VALUE != 0L) {
                    headers++; headerBytes += length
                    if (pts != null || headers > 16 || headerBytes > 262_144) { valid = false; break }
                } else {
                    if (pts != null || headers == 0 || flags and (1L shl 62) == 0L) { valid = false; break }
                    pts = flags and 0x3fff_ffff_ffff_ffffL
                }
                input.position(end)
            }
            if (!valid || pts == null) null else {
                // Most blocked messages are deltas: they return before JSON parsing/allocation.
                val metadata = ToolboxProtocol.Packet(ToolboxProtocol.VIDEO_CONFIG, bytes.copyOfRange(configStart, configStart + configSize)).objectValue()
                val codec = metadata.text("codec")
                val width = metadata.number("width")
                val height = metadata.number("height")
                val generation = metadata.number("generation") ?: 0L
                if (codec !in setOf("h264", "h265") || width == null || width !in 1..65535 || height == null || height !in 1..65535 || generation < 0) null
                else IndependentVideoBoundary(ToolboxScreenEvent.Configuration(checkNotNull(codec), width.toInt(), height.toInt(), generation), pts)
            }
        }
    }
} catch (_: Exception) { null }
