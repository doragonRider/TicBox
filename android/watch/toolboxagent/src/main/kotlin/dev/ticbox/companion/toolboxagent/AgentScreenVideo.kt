package dev.ticbox.companion.toolboxagent

import dev.ticbox.companion.protocol.ToolboxProtocol
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Converts the pinned server's video stream to the stable toolbox video messages. */
internal fun relayScrcpyVideo(source: InputStream, wire: AgentWire) = relayScrcpyVideo(source, wire::send)

internal fun relayScrcpyVideo(source: InputStream, send: (Int, ByteArray) -> Unit) {
    val input = DataInputStream(source)
    val codec = when (input.readInt()) {
        0x68323634 -> "h264"
        0x68323635 -> "h265"
        0, 1 -> throw ScreenRelayException("screen_encoder_unavailable")
        else -> throw ScreenRelayException("screen_protocol_mismatch")
    }
    var configured = false
    val header = ByteArray(12)
    while (true) {
        val first = input.read()
        if (first == -1) return
        header[0] = first.toByte()
        input.readFully(header, 1, header.size - 1)
        val fields = ByteBuffer.wrap(header)
        val ptsAndFlags = fields.long
        val lastField = fields.int
        if (ptsAndFlags and Long.MIN_VALUE != 0L) {
            // scrcpy 4.1 session header: flags u32, width u32, height u32. It may recur
            // after rotation/resizing; it is not an encoded frame or a payload length.
            val width = ptsAndFlags.toInt()
            val height = lastField
            if (width !in 1..65_535 || height !in 1..65_535) throw ScreenRelayException("screen_protocol_mismatch")
            send(ToolboxProtocol.VIDEO_CONFIG, buildJsonObject { put("codec", codec); put("width", width); put("height", height) }.toString().toByteArray(Charsets.UTF_8))
            configured = true
        } else {
            if (!configured) throw ScreenRelayException("screen_protocol_mismatch")
            send(ToolboxProtocol.VIDEO_FRAME, readFrame(input, ptsAndFlags, lastField))
        }
    }
}

/**
 * The same for the server's sound, which has no session headers. A device that can't capture sound
 * says so with codec 0 and carries on with the picture; codec 1 means the whole session failed.
 */
internal fun relayScrcpyAudio(source: InputStream, wire: AgentWire) = relayScrcpyAudio(source, wire::send)

internal fun relayScrcpyAudio(source: InputStream, send: (Int, ByteArray) -> Unit) {
    val input = DataInputStream(source)
    when (input.readInt()) {
        0x6f707573 -> send(ToolboxProtocol.AUDIO_CONFIG, buildJsonObject { put("codec", "opus") }.toString().toByteArray(Charsets.UTF_8))
        0 -> { send(ToolboxProtocol.AUDIO_CONFIG, buildJsonObject { }.toString().toByteArray(Charsets.UTF_8)); return }
        1 -> throw ScreenRelayException("screen_audio_failed")
        else -> throw ScreenRelayException("screen_protocol_mismatch")
    }
    val header = ByteArray(12)
    while (true) {
        val first = input.read()
        if (first == -1) return
        header[0] = first.toByte()
        input.readFully(header, 1, header.size - 1)
        val fields = ByteBuffer.wrap(header)
        val ptsAndFlags = fields.long
        if (ptsAndFlags and Long.MIN_VALUE != 0L) throw ScreenRelayException("screen_protocol_mismatch")
        send(ToolboxProtocol.AUDIO_FRAME, readFrame(input, ptsAndFlags, fields.int))
    }
}

/** The toolbox's frame message: its own config/key bits (63/62), apart from scrcpy's (62/61). */
private fun readFrame(input: DataInputStream, ptsAndFlags: Long, size: Int): ByteArray {
    if (size !in 0..(ToolboxProtocol.MAX_PACKET - 8)) throw ScreenRelayException("screen_protocol_mismatch")
    var normalized = ptsAndFlags and 0x1fff_ffff_ffff_ffffL
    if (ptsAndFlags and (1L shl 62) != 0L) normalized = normalized or Long.MIN_VALUE
    if (ptsAndFlags and (1L shl 61) != 0L) normalized = normalized or (1L shl 62)
    val payload = ByteArray(size + 8)
    ByteBuffer.wrap(payload).putLong(normalized)
    input.readFully(payload, 8, size)
    return payload
}

internal class ScreenRelayException(val code: String) : IOException(code)

internal fun screenRelayErrorCode(error: Exception): String = when (error) {
    is ScreenRelayException -> error.code
    is EOFException -> "connection_closed"
    else -> "screen_stream_failed"
}

/** Report a failed stream before closing stdin, so the phone does not wait for missing video. */
internal fun runScreenRelay(wire: AgentWire, closed: AtomicBoolean, stop: () -> Unit, relay: () -> Unit) {
    try {
        relay()
        if (!closed.get()) wire.send(ToolboxProtocol.END)
    } catch (error: Exception) {
        if (!closed.get()) runCatching {
            val code = screenRelayErrorCode(error)
            // Exception messages may contain paths or clipboard data; only send typed metadata.
            wire.json(ToolboxProtocol.ERROR, buildJsonObject { put("code", code); put("type", error.javaClass.simpleName) })
        }
    } finally { stop(); runCatching { wire.input.close() } }
}
