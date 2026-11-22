package dev.ticbox.companion.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException

/** Explicitly present values plus exact unknown fields. Known-field re-encoding is canonical. */
data class DecodedStruct(
    val name: String,
    val values: Map<String, Any>,
    val fields: List<ProtoField>,
    val unknownFields: List<ProtoField>,
    /** Recoverable wire observations; includes the paths of nested observations. */
    val notices: List<ProtoNotice> = emptyList(),
) {
    operator fun get(key: String): Any? = values[key]
}

data class ProtoNotice(val kind: String, val path: String, val fieldNumber: Int, val occurrences: Int = 1)

/** Schemas reconstructed and descriptor-checked in the existing research project.
 * Numeric enum values remain numeric. Optional fields remain absent; no defaults are inserted.
 * This codec does not decrypt, authenticate, dispatch Android services or open a transport.
 */
object GmsStructs {
    val names: Set<String> get() = gmsSchemas.keys.toSet()

    private class Budget(var fields: Int = 0) {
        fun consume(count: Int = 1) {
            protocolCheck(count <= ProtocolLimits.MAX_FIELDS - fields, "Protobuf field budget exceeded")
            fields += count
        }
    }

    fun decode(name: String, bytes: ByteArray): DecodedStruct = decode(name, bytes, Budget(), 0, name)

    private fun decode(name: String, bytes: ByteArray, budget: Budget, depth: Int, path: String): DecodedStruct {
        protocolCheck(depth <= ProtocolLimits.MAX_DEPTH, "Protobuf nesting budget exceeded")
        val schema = gmsSchemas[name] ?: throw IllegalArgumentException("Unknown GMS structure $name")
        val fields = ProtoWire.decodeFields(bytes)
        budget.consume(fields.size)
        val values = linkedMapOf<String, Any>()
        val unknowns = ArrayList<ProtoField>()
        val notices = ArrayList<ProtoNotice>()
        val accepted = linkedMapOf<FieldSpec, MutableList<ProtoField>>()
        for (field in fields) {
            val spec = schema.firstOrNull { it.number == field.number }
            if (spec == null) {
                unknowns += field
                continue
            }
            val repeated = spec.rule == "repeated" || spec.rule == "packed"
            val expectedWire = wireType(spec.type)
            if (field.wireType != expectedWire && !(repeated && field.wireType == 2 && expectedWire in listOf(0, 1, 5))) {
                // Generated protobuf readers select on the complete tag, including its wire type.
                unknowns += field
                notices += ProtoNotice("unknown_wire_type", "$path.${spec.name}", field.number)
                continue
            }
            accepted.getOrPut(spec) { ArrayList() }.add(field)
        }
        for ((spec, occurrences) in accepted) {
            val repeated = spec.rule == "repeated" || spec.rule == "packed"
            val fieldPath = "$path.${spec.name}"
            if (!repeated && occurrences.size > 1) notices += ProtoNotice(
                if (spec.type in gmsSchemas) "merged_singular_message" else "last_singular_value",
                fieldPath, spec.number, occurrences.size,
            )
            fun value(field: ProtoField): Any = decodeValue(spec.type, field.contents, budget, depth, fieldPath, notices)
            if (repeated) {
                values[spec.name] = occurrences.flatMap { field ->
                    if (field.wireType == wireType(spec.type)) listOf(value(field))
                    else decodePacked(spec.type, field.contents as ByteArray, budget)
                }
            } else if (spec.type in gmsSchemas) {
                // Proto2 merges repeated occurrences of a singular submessage. Concatenate once
                // so split required fields work and many occurrences do not cause quadratic copies.
                val merged = ByteArrayOutputStream()
                for (field in occurrences) merged.write(field.contents as ByteArray)
                values[spec.name] = decodeValue(spec.type, merged.toByteArray(), budget, depth, fieldPath, notices)
            } else {
                values[spec.name] = value(occurrences.last())
            }
        }
        for (spec in schema) {
            protocolCheck(spec.rule != "required" || spec.name in values, "Missing required $name.${spec.name}")
        }
        val snapshot = values.mapValues { (_, value) -> if (value is MutableList<*>) value.toList() else value }
        return DecodedStruct(name, snapshot, fields, unknowns, notices)
    }

    private fun decodePacked(type: String, bytes: ByteArray, budget: Budget): List<Any> {
        val output = ArrayList<Any>()
        var offset = 0
        while (offset < bytes.size) {
            budget.consume()
            val value: Any
            if (wireType(type) == 0) {
                val decoded = ProtoWire.readVarint(bytes, offset)
                offset = decoded.next
                value = scalarDecode(type, decoded.value)
            } else {
                val width = if (wireType(type) == 1) 8 else 4
                protocolCheck(width <= bytes.size - offset, "Truncated packed fixed field")
                value = scalarDecode(type, bytes.copyOfRange(offset, offset + width))
                offset += width
            }
            output += value
        }
        return output
    }

    private fun decodeValue(type: String, value: Any, budget: Budget, depth: Int, path: String, notices: MutableList<ProtoNotice>): Any =
        if (type in gmsSchemas) decode(type, value as ByteArray, budget, depth + 1, path).also { notices.addAll(it.notices) }
        else scalarDecode(type, value)

    private fun scalarDecode(type: String, value: Any): Any = when (type) {
        "string" -> try {
            (value as ByteArray).decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            throw ProtocolException("Invalid protobuf UTF-8")
        }
        "bytes" -> (value as ByteArray).copyOf()
        "int32" -> (value as ULong).toInt()
        "int64" -> (value as ULong).toLong()
        "sint32" -> {
            val low = (value as ULong).toUInt()
            ((low shr 1).toInt()) xor -(low and 1u).toInt()
        }
        "bool" -> (value as ULong) != 0uL
        "fixed64" -> ByteBuffer.wrap(value as ByteArray).order(ByteOrder.LITTLE_ENDIAN).long.toULong()
        "double" -> ByteBuffer.wrap(value as ByteArray).order(ByteOrder.LITTLE_ENDIAN).double
        "float" -> ByteBuffer.wrap(value as ByteArray).order(ByteOrder.LITTLE_ENDIAN).float
        else -> throw IllegalArgumentException("Unknown scalar $type")
    }

    fun encode(message: DecodedStruct): ByteArray = encode(message.name, message.values, message.unknownFields)

    fun encode(name: String, values: Map<String, Any?>, unknownFields: List<ProtoField> = emptyList()): ByteArray =
        encode(name, values, unknownFields, Budget(), 0)

    private fun encode(
        name: String,
        values: Map<String, Any?>,
        unknownFields: List<ProtoField>,
        budget: Budget,
        depth: Int,
    ): ByteArray {
        protocolCheck(depth <= ProtocolLimits.MAX_DEPTH, "Protobuf nesting budget exceeded")
        val schema = gmsSchemas[name] ?: throw IllegalArgumentException("Unknown GMS structure $name")
        val knownNames = schema.map { it.name }.toSet()
        protocolCheck(values.keys.all { it in knownNames }, "Unknown encoder field in $name")
        val output = ByteArrayOutputStream()
        fun append(bytes: ByteArray) {
            protocolCheck(bytes.size <= ProtocolLimits.MAX_MESSAGE_BYTES - output.size(), "Encoded protobuf size budget exceeded")
            output.write(bytes)
        }
        for (spec in schema) {
            val value = values[spec.name]
            if (value == null) {
                protocolCheck(spec.rule != "required", "Missing required $name.${spec.name}")
                continue
            }
            val repeated = spec.rule == "repeated" || spec.rule == "packed"
            val entries = if (repeated) {
                protocolCheck(value is List<*>, "Expected repeated $name.${spec.name}")
                value as List<*>
            } else listOf(value)
            if (spec.rule == "packed") {
                budget.consume(entries.size)
                val packed = ByteArrayOutputStream()
                for (entry in entries) {
                    protocolCheck(entry != null, "Null packed entry")
                    val bytes = scalarEncode(spec.type, entry!!)
                    protocolCheck(bytes.size <= ProtocolLimits.MAX_MESSAGE_BYTES - packed.size(), "Encoded protobuf size budget exceeded")
                    packed.write(bytes)
                }
                if (entries.isNotEmpty()) append(ProtoWire.encodedField(spec.number, 2, packed.toByteArray()))
            } else {
                for (entry in entries) {
                    budget.consume()
                    protocolCheck(entry != null, "Null repeated entry")
                    val encoded = if (spec.type in gmsSchemas) {
                        when (entry) {
                            is DecodedStruct -> {
                                protocolCheck(entry.name == spec.type, "Nested structure type mismatch")
                                encode(spec.type, entry.values, entry.unknownFields, budget, depth + 1)
                            }
                            is Map<*, *> -> {
                                protocolCheck(entry.keys.all { it is String }, "Expected string field names")
                                @Suppress("UNCHECKED_CAST")
                                encode(spec.type, entry as Map<String, Any?>, emptyList(), budget, depth + 1)
                            }
                            else -> throw ProtocolException("Expected nested $name.${spec.name}")
                        }
                    } else scalarEncode(spec.type, entry!!)
                    append(ProtoWire.encodedField(spec.number, wireType(spec.type), encoded))
                }
            }
        }
        for (field in unknownFields) {
            val spec = schema.firstOrNull { it.number == field.number }
            val acceptedWire = spec != null && (field.wireType == wireType(spec.type) ||
                ((spec.rule == "repeated" || spec.rule == "packed") && field.wireType == 2 && wireType(spec.type) in listOf(0, 1, 5)))
            protocolCheck(!acceptedWire, "Unknown raw field collides with $name schema tag")
            budget.consume()
            append(field.raw)
        }
        return output.toByteArray()
    }

    private fun exactLong(value: Any): Long = when (value) {
        is Long -> value
        is Int -> value.toLong()
        else -> throw ProtocolException("Expected exact signed integer")
    }

    private fun scalarEncode(type: String, value: Any): ByteArray = when (type) {
        "bytes" -> {
            protocolCheck(value is ByteArray, "Expected byte array")
            (value as ByteArray).copyOf()
        }
        "string" -> {
            protocolCheck(value is String, "Expected string")
            try {
                (value as String).encodeToByteArray(throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                throw ProtocolException("Invalid UTF-16 string")
            }
        }
        "bool" -> {
            protocolCheck(value is Boolean, "Expected Boolean")
            ProtoWire.varint(if (value as Boolean) 1uL else 0uL)
        }
        "int32" -> {
            val number = exactLong(value)
            protocolCheck(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong(), "Value outside int32")
            ProtoWire.varint(number.toULong())
        }
        "int64" -> ProtoWire.varint(exactLong(value).toULong())
        "sint32" -> {
            val number = exactLong(value)
            protocolCheck(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong(), "Value outside sint32")
            ProtoWire.varint(((number shl 1) xor (number shr 31)).toULong())
        }
        "fixed64" -> {
            val number = when (value) {
                is ULong -> value
                is Long -> { protocolCheck(value >= 0, "Negative fixed64; use ULong for raw bits"); value.toULong() }
                is Int -> { protocolCheck(value >= 0, "Negative fixed64"); value.toULong() }
                else -> throw ProtocolException("Expected uint64")
            }
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(number.toLong()).array()
        }
        "double" -> {
            protocolCheck(value is Double, "Expected Double")
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value as Double).array()
        }
        "float" -> {
            protocolCheck(value is Float, "Expected Float")
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value as Float).array()
        }
        else -> throw IllegalArgumentException("Unknown scalar $type")
    }

    private fun wireType(type: String): Int = when (type) {
        "int32", "int64", "sint32", "bool" -> 0
        "fixed64", "double" -> 1
        "float" -> 5
        else -> 2
    }
}
