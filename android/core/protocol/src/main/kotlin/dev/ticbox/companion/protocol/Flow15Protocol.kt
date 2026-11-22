package dev.ticbox.companion.protocol

/** Flow15 layouts; see docs/spec/flow15-legacy.md. */
object Flow15Opcode {
    const val TCP_OPEN = 1
    const val TCP_CLOSE = 2
    const val TCP_WRITE = 3
    const val UDP_DATAGRAM = 4
    const val PING = 5
    const val ACK = 6
    const val UDP_FAILURE = 7
    const val PULL_REQUEST = 8
    const val PULL_DATA = 9
    const val PACKAGE_NAME = 10
    const val NETWORK_STATE = 11
    const val PUSH_REQUEST = 12
    const val PUSH_DATA = 13
    const val UDP_V2_DATAGRAM = 14
    const val UDP_V2_FAILURE = 15
    const val HANDSHAKE_START = 16
    const val HANDSHAKE_ACK = 17
    const val HANDSHAKE_DONE = 18
}

/** IPv4 addresses and PING tokens are stored as their original 32 bits, including the sign bit. */
sealed interface Flow15Frame {
    val opcode: Int

    data class TcpOpen(val id: Int, val host: Int, val port: Int, val opaque: Int) : Flow15Frame {
        override val opcode = Flow15Opcode.TCP_OPEN
    }
    data class TcpClose(val id: Int) : Flow15Frame { override val opcode = Flow15Opcode.TCP_CLOSE }
    data class Ping(val token: Int) : Flow15Frame { override val opcode = Flow15Opcode.PING }
    data class Ack(val id: Int) : Flow15Frame { override val opcode = Flow15Opcode.ACK }
    data class Request(override val opcode: Int, val id: Int, val requestedBytes: Int) : Flow15Frame {
        init { require(opcode == Flow15Opcode.PULL_REQUEST || opcode == Flow15Opcode.PUSH_REQUEST) }
    }
    class Data(override val opcode: Int, val id: Int, payload: ByteArray) : Flow15Frame {
        init {
            require(opcode in intArrayOf(Flow15Opcode.TCP_WRITE, Flow15Opcode.PULL_DATA, Flow15Opcode.PUSH_DATA))
            require(payload.size <= 65_535)
        }
        private val body = payload.copyOf()
        val payload: ByteArray get() = body.copyOf()
    }
    class Datagram(val host: Int, val port: Int, payload: ByteArray, val failure: Boolean = false) : Flow15Frame {
        override val opcode = if (failure) Flow15Opcode.UDP_FAILURE else Flow15Opcode.UDP_DATAGRAM
        private val body = payload.copyOf().also { require(it.size <= 65_535) }
        val payload: ByteArray get() = body.copyOf()
    }
    class DatagramV2(
        val sourceHost: Int,
        val sourcePort: Int,
        val destinationHost: Int,
        val destinationPort: Int,
        /** Absent on the 15-byte failure header. Its meaning on a datagram is unconfirmed. */
        val opaque: Int?,
        payload: ByteArray,
    ) : Flow15Frame {
        override val opcode = if (opaque == null) Flow15Opcode.UDP_V2_FAILURE else Flow15Opcode.UDP_V2_DATAGRAM
        private val body = payload.copyOf().also { require(it.size <= 65_535) }
        val payload: ByteArray get() = body.copyOf()
    }
    class PackageName(val opaquePrefix: Int, payload: ByteArray) : Flow15Frame {
        override val opcode = Flow15Opcode.PACKAGE_NAME
        private val body = payload.copyOf().also { require(it.size <= 65_535) }
        val payload: ByteArray get() = body.copyOf()
    }
    data class NetworkState(val networkType: Int, val rawMetered: Int) : Flow15Frame {
        override val opcode = Flow15Opcode.NETWORK_STATE
        /** Preserve noncanonical captured bytes instead of inventing a boolean interpretation. */
        val isMetered: Boolean? get() = when (rawMetered) { 0 -> false; 1 -> true; else -> null }
    }
    class Handshake(override val opcode: Int, val version: Int, extensions: ByteArray = byteArrayOf()) : Flow15Frame {
        init { require(opcode in Flow15Opcode.HANDSHAKE_START..Flow15Opcode.HANDSHAKE_DONE) }
        private val body = extensions.copyOf().also { require(it.size <= 65_535) }
        val extensions: ByteArray get() = body.copyOf()
    }
    class Unknown(override val opcode: Int, raw: ByteArray) : Flow15Frame {
        private val body = raw.copyOf()
        val raw: ByteArray get() = body.copyOf()
    }
}

/** No common outer length prefix exists. All multibyte fields below are big endian. */
object Flow15Codec {
    const val MAX_FRAME_BYTES = 65_552

    /** Null means the fixed header is incomplete; -1 means no reliable boundary is known. */
    internal fun frameLength(bytes: ByteArray, offset: Int, available: Int): Int? {
        if (available == 0) return null
        return when (val opcode = bytes[offset].toInt() and 255) {
            1 -> 13
            2, 5, 6 -> 5
            8, 12 -> 9
            11 -> 6
            3, 9, 13, 16, 17, 18 -> if (available < 7) null else 7 + u16(bytes, offset + 5)
            4, 7 -> if (available < 9) null else 9 + u16(bytes, offset + 7)
            10 -> if (available < 5) null else 5 + u16(bytes, offset + 3)
            14 -> if (available < 17) null else 17 + u16(bytes, offset + 15)
            15 -> if (available < 15) null else 15 + u16(bytes, offset + 13)
            else -> { check(opcode !in 1..18); -1 }
        }
    }

    /** Exactly one caller-delimited frame; unknown opcodes preserve all supplied bytes. */
    fun decode(raw: ByteArray): Flow15Frame {
        require(raw.isNotEmpty()) { "Empty Flow15 frame" }
        val opcode = raw[0].toInt() and 255
        val size = frameLength(raw, 0, raw.size)
        if (size == -1) return Flow15Frame.Unknown(opcode, raw)
        require(size == raw.size) { "Flow15 length mismatch: expected $size, received ${raw.size}" }
        fun payload(offset: Int) = raw.copyOfRange(offset, raw.size)
        return when (opcode) {
            1 -> Flow15Frame.TcpOpen(i32(raw, 1), i32(raw, 5), u16(raw, 9), u16(raw, 11))
            2 -> Flow15Frame.TcpClose(i32(raw, 1))
            3, 9, 13 -> Flow15Frame.Data(opcode, i32(raw, 1), payload(7))
            4, 7 -> Flow15Frame.Datagram(i32(raw, 1), u16(raw, 5), payload(9), opcode == 7)
            5 -> Flow15Frame.Ping(i32(raw, 1))
            6 -> Flow15Frame.Ack(i32(raw, 1))
            8, 12 -> Flow15Frame.Request(opcode, i32(raw, 1), i32(raw, 5))
            10 -> Flow15Frame.PackageName(u16(raw, 1), payload(5))
            11 -> Flow15Frame.NetworkState(i32(raw, 1), raw[5].toInt() and 255)
            14, 15 -> Flow15Frame.DatagramV2(
                i32(raw, 1), u16(raw, 5), i32(raw, 7), u16(raw, 11),
                if (opcode == 14) u16(raw, 13) else null, payload(if (opcode == 14) 17 else 15),
            )
            else -> Flow15Frame.Handshake(opcode, i32(raw, 1), payload(7))
        }
    }

    /** Fixed phone ACK sent by the official client. Peer versions are not compared with 5. */
    fun handshakeAck(): ByteArray = encode(Flow15Frame.Handshake(Flow15Opcode.HANDSHAKE_ACK, 5))

    fun encode(frame: Flow15Frame): ByteArray {
        val writer = Writer(frame.opcode)
        when (frame) {
            is Flow15Frame.TcpOpen -> writer.i32(frame.id).i32(frame.host).u16(frame.port).u16(frame.opaque)
            is Flow15Frame.TcpClose -> writer.i32(frame.id)
            is Flow15Frame.Ping -> writer.i32(frame.token)
            is Flow15Frame.Ack -> writer.i32(frame.id)
            is Flow15Frame.Request -> writer.i32(frame.id).i32(frame.requestedBytes)
            is Flow15Frame.Data -> writer.i32(frame.id).sized(frame.payload)
            is Flow15Frame.Datagram -> writer.i32(frame.host).u16(frame.port).sized(frame.payload)
            is Flow15Frame.DatagramV2 -> {
                writer.i32(frame.sourceHost).u16(frame.sourcePort).i32(frame.destinationHost).u16(frame.destinationPort)
                frame.opaque?.let(writer::u16)
                writer.sized(frame.payload)
            }
            is Flow15Frame.PackageName -> writer.u16(frame.opaquePrefix).sized(frame.payload)
            is Flow15Frame.NetworkState -> writer.i32(frame.networkType).byte(frame.rawMetered)
            is Flow15Frame.Handshake -> writer.i32(frame.version).sized(frame.extensions)
            is Flow15Frame.Unknown -> return frame.raw
        }
        return writer.result()
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)

    private fun i32(bytes: ByteArray, offset: Int): Int =
        (u16(bytes, offset) shl 16) or u16(bytes, offset + 2)

    private class Writer(opcode: Int) {
        private val bytes = java.io.ByteArrayOutputStream()
        init { byte(opcode) }
        fun byte(value: Int) = apply { require(value in 0..255); bytes.write(value) }
        fun u16(value: Int) = apply { require(value in 0..65_535); bytes.write(value ushr 8); bytes.write(value and 255) }
        fun i32(value: Int) = apply { u16(value ushr 16); u16(value and 65_535) }
        fun sized(value: ByteArray) = apply { u16(value.size); bytes.write(value) }
        fun result(): ByteArray = bytes.toByteArray()
    }
}

enum class Flow15StreamStatus { COMPLETE, INCOMPLETE, UNKNOWN }

/**
 * Bounded incremental parser; unknown opcodes retain the remainder and permanently stop parsing.
 * This limit is an allocation policy, not a protocol rule. Rejected chunks leave pending bytes intact.
 */
class Flow15StreamDecoder(val maxBufferedBytes: Int = Flow15Codec.MAX_FRAME_BYTES * 2) {
    init { require(maxBufferedBytes > 0) }
    private var pending = byteArrayOf()
    var stoppedOpcode: Int? = null
        private set
    val remainder: ByteArray get() = pending.copyOf()
    val status: Flow15StreamStatus get() = when {
        stoppedOpcode != null -> Flow15StreamStatus.UNKNOWN
        pending.isNotEmpty() -> Flow15StreamStatus.INCOMPLETE
        else -> Flow15StreamStatus.COMPLETE
    }

    fun push(chunk: ByteArray): List<Flow15Frame> {
        require(chunk.size <= maxBufferedBytes - pending.size) { "Flow15 local decoder buffer limit exceeded" }
        val combined = pending + chunk
        val frames = ArrayList<Flow15Frame>()
        var offset = 0
        while (stoppedOpcode == null && offset < combined.size) {
            val length = Flow15Codec.frameLength(combined, offset, combined.size - offset) ?: break
            if (length == -1) { stoppedOpcode = combined[offset].toInt() and 255; break }
            if (length > combined.size - offset) break
            frames.add(Flow15Codec.decode(combined.copyOfRange(offset, offset + length)))
            offset += length
        }
        pending = combined.copyOfRange(offset, combined.size)
        return frames
    }
}
