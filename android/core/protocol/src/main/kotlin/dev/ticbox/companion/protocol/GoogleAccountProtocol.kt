package dev.ticbox.companion.protocol

import java.nio.ByteBuffer

/** Independent optional command/status/data fields, not a protobuf oneof. */
class GoogleAccountEnvelope(
    val type: Int = 1,
    val command: Int? = null,
    val status: Int? = null,
    payload: ByteArray? = null,
    val unknownFieldNumbers: List<Int> = emptyList(),
) {
    private val body = payload?.copyOf()
    val payload: ByteArray? get() = body?.copyOf()
}

object GoogleAccountProtocol {
    const val CHANNEL_PATH = "/accounts/sync"
    const val HOST_ACTIVITY_PATH = "/accounts/start_host_activity"
    const val START_COMMAND = 2
    const val STARTED_STATUS = 2

    private fun scalar(number: Int, value: Int) = ProtoWire.encodedField(number, 0, ProtoWire.varint(value.toULong()))
    private fun message(number: Int, value: ByteArray) = ProtoWire.encodedField(number, 2, value)

    /** The recovered sender leaves the outer type at its default for START. */
    fun start(): ByteArray = message(2, scalar(1, START_COMMAND))
    fun payload(bytes: ByteArray): ByteArray = scalar(1, 3) + message(4, message(1, bytes))
    fun frame(body: ByteArray, maxBytes: Int = ProtocolLimits.MAX_MESSAGE_BYTES): ByteArray {
        protocolCheck(body.size <= maxBytes, "Account frame size budget exceeded")
        return ByteBuffer.allocate(body.size + 4).putInt(body.size).put(body).array()
    }

    fun decode(bytes: ByteArray): GoogleAccountEnvelope {
        val fields = ProtoWire.decodeFields(bytes)
        fun last(number: Int, wire: Int) = fields.lastOrNull { it.number == number && it.wireType == wire }
        fun enum(field: ProtoField?): Int? {
            val value = field?.contents as? ULong ?: return null
            return value.toInt()
        }
        fun nestedEnum(number: Int): Int? = (last(number, 2)?.contents as? ByteArray)?.let { nested ->
            enum(ProtoWire.decodeFields(nested).lastOrNull { it.number == 1 && it.wireType == 0 }) ?: 1
        }
        val payload = (last(4, 2)?.contents as? ByteArray)?.let { nested ->
            (ProtoWire.decodeFields(nested).lastOrNull { it.number == 1 && it.wireType == 2 }?.contents as? ByteArray) ?: byteArrayOf()
        }
        return GoogleAccountEnvelope(enum(last(1, 0)) ?: 1, nestedEnum(2), nestedEnum(3), payload,
            fields.filter { it.number !in 1..4 }.map { it.number })
    }
}

/** A 4-byte big-endian length may span any number of Channel DATA records. */
class GoogleAccountFrameDecoder(private val maxBytes: Int = ProtocolLimits.MAX_MESSAGE_BYTES) {
    private val header = ByteArray(4)
    private var headerBytes = 0
    private var body: ByteArray? = null
    private var bodyBytes = 0
    init { require(maxBytes > 0) }

    fun feed(chunk: ByteArray): List<GoogleAccountEnvelope> {
        val result = ArrayList<GoogleAccountEnvelope>()
        var offset = 0
        while (offset < chunk.size) {
            if (body == null) {
                val n = minOf(4 - headerBytes, chunk.size - offset)
                chunk.copyInto(header, headerBytes, offset, offset + n); headerBytes += n; offset += n
                if (headerBytes != 4) continue
                val size = ByteBuffer.wrap(header).int
                protocolCheck(size in 0..maxBytes, "Account frame size budget exceeded")
                body = ByteArray(size); bodyBytes = 0
            }
            val current = body!!
            val n = minOf(current.size - bodyBytes, chunk.size - offset)
            chunk.copyInto(current, bodyBytes, offset, offset + n); bodyBytes += n; offset += n
            if (bodyBytes == current.size) {
                result += GoogleAccountProtocol.decode(current)
                body = null; bodyBytes = 0; headerBytes = 0
            }
        }
        return result
    }

    fun finish() { protocolCheck(headerBytes == 0 && body == null, "Truncated account frame") }
}

object GoogleAccountHostOptions {
    /** Null is the observed no-options form. Malformed DataMaps remain explicit errors. */
    fun reauthenticate(bytes: ByteArray?): Boolean {
        if (bytes == null) return false
        val values = DataMapCodec.decode(bytes).values
        val options = (values["transfer_options"] as? DataMapValue.MapValue)?.value ?: return false
        return (options["reauth"] as? DataMapValue.Bool)?.value ?: false
    }
}
