package dev.ticbox.companion.protocol

import java.io.ByteArrayOutputStream

/** A rejected wire value. No decoder attempts to resynchronize after malformed bytes. */
class ProtocolException(message: String) : IllegalArgumentException(message)

/** Client resource budgets, not negotiated limits or claims about every watch version. */
object ProtocolLimits {
    const val MAX_MESSAGE_BYTES = 1024 * 1024
    const val MAX_FIELDS = 32768
    const val MAX_DEPTH = 32
}

internal fun protocolCheck(condition: Boolean, message: String) {
    if (!condition) throw ProtocolException(message)
}

/** An exact unknown-field observation; byte arrays returned to callers are copies. */
class ProtoField internal constructor(
    val number: Int,
    val wireType: Int,
    val offset: Int,
    internal val contents: Any,
    encoded: ByteArray,
) {
    private val encodedBytes = encoded.copyOf()
    val raw: ByteArray get() = encodedBytes.copyOf()
    val value: Any get() = if (contents is ByteArray) contents.copyOf() else contents

    internal fun appendTo(output: ByteArrayOutputStream) = output.write(encodedBytes)

    companion object {
        fun fromEncoded(bytes: ByteArray): ProtoField {
            val fields = ProtoWire.decodeFields(bytes)
            protocolCheck(fields.size == 1, "Expected exactly one raw protobuf field")
            return fields.single()
        }
    }
}

/** Bounded protobuf wire parser for the observed varint, fixed64, bytes and fixed32 forms.
 * Groups are rejected explicitly. Parsing does not invent a type for unknown fields.
 */
object ProtoWire {
    internal data class Varint(val value: ULong, val next: Int)

    internal fun readVarint(bytes: ByteArray, start: Int): Varint {
        var value = 0uL
        var offset = start
        repeat(10) { index ->
            protocolCheck(offset < bytes.size, "Truncated protobuf varint")
            val byte = bytes[offset++].toInt() and 0xff
            protocolCheck(index != 9 || byte <= 1, "Protobuf varint exceeds uint64")
            value = value or ((byte and 0x7f).toULong() shl (index * 7))
            if ((byte and 0x80) == 0) return Varint(value, offset)
        }
        throw ProtocolException("Protobuf varint exceeds ten bytes")
    }

    internal fun varint(input: ULong): ByteArray {
        var value = input
        val output = ByteArrayOutputStream(10)
        do {
            val low = (value and 0x7fuL).toInt()
            value = value shr 7
            output.write(low or if (value != 0uL) 0x80 else 0)
        } while (value != 0uL)
        return output.toByteArray()
    }

    fun decodeFields(
        input: ByteArray,
        maxBytes: Int = ProtocolLimits.MAX_MESSAGE_BYTES,
        maxFields: Int = ProtocolLimits.MAX_FIELDS,
    ): List<ProtoField> {
        require(maxBytes > 0 && maxFields > 0)
        protocolCheck(input.size <= maxBytes, "Protobuf size budget exceeded")
        val bytes = input.copyOf()
        var offset = 0
        val fields = ArrayList<ProtoField>()
        while (offset < bytes.size) {
            protocolCheck(fields.size < maxFields, "Protobuf field budget exceeded")
            val start = offset
            val tag = readVarint(bytes, offset)
            offset = tag.next
            val field = tag.value shr 3
            protocolCheck(field in 1uL..536870911uL, "Invalid protobuf field number")
            val wireType = (tag.value and 7uL).toInt()
            val value: Any
            when (wireType) {
                0 -> {
                    val decoded = readVarint(bytes, offset)
                    value = decoded.value
                    offset = decoded.next
                }
                2 -> {
                    val size = readVarint(bytes, offset)
                    protocolCheck(size.value <= (bytes.size - size.next).toULong(), "Truncated protobuf bytes")
                    offset = size.next + size.value.toInt()
                    value = bytes.copyOfRange(size.next, offset)
                }
                1, 5 -> {
                    val size = if (wireType == 1) 8 else 4
                    protocolCheck(size <= bytes.size - offset, "Truncated protobuf fixed field")
                    value = bytes.copyOfRange(offset, offset + size)
                    offset += size
                }
                else -> throw ProtocolException("Unsupported protobuf wire type $wireType")
            }
            fields += ProtoField(field.toInt(), wireType, start, value, bytes.copyOfRange(start, offset))
        }
        return fields
    }

    internal fun encodedField(number: Int, wireType: Int, value: ByteArray): ByteArray {
        protocolCheck(number in 1..536870911, "Invalid protobuf field number")
        protocolCheck(wireType in listOf(0, 1, 2, 5), "Unsupported protobuf wire type")
        if (wireType == 1 || wireType == 5) {
            protocolCheck(value.size == if (wireType == 1) 8 else 4, "Invalid fixed-field width")
        }
        val tag = varint((number.toULong() shl 3) or wireType.toULong())
        val size = if (wireType == 2) varint(value.size.toULong()) else byteArrayOf()
        protocolCheck(tag.size.toLong() + size.size + value.size <= ProtocolLimits.MAX_MESSAGE_BYTES, "Encoded protobuf size budget exceeded")
        return tag + size + value
    }
}
