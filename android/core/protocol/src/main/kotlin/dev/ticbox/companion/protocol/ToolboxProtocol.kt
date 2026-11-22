package dev.ticbox.companion.protocol

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.json.*

/** Independent, binary-safe stream for one operation. Does not share health JSON frames. */
object ToolboxProtocol {
    const val VERSION = 5
    /**
     * The oldest Local Bridge a newer phone can still update over root: the first whose relay flushes small frames.
     * From here on the frame layout, READY's version/uid/deviceId, and the install request with its DATA, END, PROGRESS,
     * RESULT and ERROR replies can't change, or the bridges already on watches can't be updated any more.
     */
    const val FIRST_UPDATABLE_VERSION = 3
    const val ROOT_HOST_PACKAGE = "dev.ticbox.companion"
    const val WAKE_PATH = "/ticbox_toolbox/wake"
    const val DND_PATH = "/ticbox_dnd/state"
    const val DND_RESULT = "/ticbox_dnd/result"
    const val UUID = "d7808c72-8f9b-4e2c-bf43-9661d55ca405"
    const val MAX_PACKET = 8 * 1024 * 1024
    const val CHUNK = 64 * 1024
    const val REQUEST = 1
    const val READY = 2
    const val RESULT = 3
    const val ERROR = 4
    const val DATA = 5
    const val END = 6
    const val STDOUT = 7
    const val STDERR = 8
    const val EXIT = 9
    const val STDIN = 10
    const val CANCEL = 11
    const val PROGRESS = 12
    const val VIDEO_CONFIG = 13
    const val VIDEO_FRAME = 14
    const val CONTROL = 15
    const val CLIPBOARD = 16
    const val CLIPBOARD_ACK = 17
    const val FORCE_STOP = 18
    const val AUDIO_CONFIG = 19
    const val AUDIO_FRAME = 20
    const val TERMINAL_RESIZE = 21
    const val TERMINAL_ACK = 22
    const val SCREEN_UDP_READY = 23
    const val SCRCPY_VERSION = "4.1"
    const val AGENT_CLASS = "dev.ticbox.companion.toolboxagent.ToolboxAgentMain"
    val json = Json { ignoreUnknownKeys = true }

    data class Packet(val type: Int, val bytes: ByteArray) {
        fun objectValue(): JsonObject = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject ?: throw IOException("toolbox_object_required")
        override fun toString() = "ToolboxPacket(type=$type, bytes=${bytes.size})"
    }
    fun read(input: InputStream): Packet? {
        val header = input.read(); if (header == -1) return null
        val stream = DataInputStream(input)
        val length = stream.readInt()
        if (length !in 0..MAX_PACKET) throw IOException("toolbox_packet_size")
        if (header !in REQUEST..SCREEN_UDP_READY) throw IOException("toolbox_packet_type")
        val bytes = ByteArray(length); stream.readFully(bytes)
        return Packet(header, bytes)
    }
    /** Callers with multiple producers serialize writes using their operation's output lock. */
    fun write(output: OutputStream, type: Int, bytes: ByteArray = byteArrayOf()) {
        require(type in REQUEST..SCREEN_UDP_READY && bytes.size <= MAX_PACKET)
        // A shell-v2 OutputStream turns every write into a flushed ADB packet (and,
        // without delayed ACK, a round trip). Keep small control frames in one write.
        // Bound the temporary buffer so large video/file payloads are not copied twice.
        DataOutputStream(BufferedOutputStream(output, minOf(8192, bytes.size + 5))).apply {
            writeByte(type); writeInt(bytes.size); write(bytes); flush()
        }
    }
    fun write(output: OutputStream, type: Int, value: JsonObject) = write(output, type, value.toString().toByteArray(Charsets.UTF_8))
    fun required(input: InputStream): Packet = read(input) ?: throw EOFException("toolbox_stream_ended")
    fun request(op: String, body: JsonObject = buildJsonObject { }) = buildJsonObject { put("op", op); put("args", body) }
    fun quote(value: String): String {
        require('\u0000' !in value)
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
