package dev.ticbox.companion.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException

/** The existing DataMap types, with tolerant notification-specific semantic projection. */
internal object NotificationDataMapWire {
    class ReadResult(
        val document: DataMapDocument,
        val notices: List<NotificationNotice>,
        val unknownFields: List<NotificationWireUnknown>,
    )

    fun encode(values: Map<String, DataMapValue>, limits: NotificationCodecLimits): ByteArray = Writer(limits).map(values, 0)
    fun decode(bytes: ByteArray, limits: NotificationCodecLimits): ReadResult = Reader(limits).read(bytes)

    private class Writer(val limits: NotificationCodecLimits) {
        var fieldCount = 0
        fun field(number: Int, wire: Int, bytes: ByteArray): ByteArray {
            protocolCheck(fieldCount++ < limits.maxFields, "Notification field budget exceeded")
            protocolCheck(bytes.size <= limits.maxDataBytes, "Notification data size budget exceeded")
            return ProtoWire.encodedField(number, wire, bytes)
        }
        fun join(parts: Iterable<ByteArray>): ByteArray {
            val output = ByteArrayOutputStream()
            for (part in parts) {
                protocolCheck(part.size <= limits.maxDataBytes - output.size(), "Notification data size budget exceeded")
                output.write(part)
            }
            return output.toByteArray()
        }
        fun text(value: String): ByteArray {
            protocolCheck(value.length <= limits.maxDataBytes, "Notification text size budget exceeded")
            return value.toByteArray(Charsets.UTF_8)
        }
        fun entry(key: String, value: DataMapValue, depth: Int): ByteArray =
            join(listOf(field(1, 2, text(key)), field(2, 2, typedValue(value, depth))))
        fun map(values: Map<String, DataMapValue>, depth: Int): ByteArray {
            protocolCheck(depth <= limits.maxDepth, "Notification nesting budget exceeded")
            return join(values.toSortedMap().map { (key, value) -> field(1, 2, entry(key, value, depth)) })
        }
        fun typedValue(value: DataMapValue, depth: Int): ByteArray {
            protocolCheck(depth <= limits.maxDepth, "Notification nesting budget exceeded")
            if (value is DataMapValue.Unknown) {
                val raw = value.raw
                val fields = ProtoWire.decodeFields(raw, limits.maxDataBytes, limits.maxFields)
                protocolCheck(fields.size <= limits.maxFields - fieldCount, "Notification field budget exceeded")
                fieldCount += fields.size
                return raw
            }
            fun scalar(number: Int, wire: Int, bytes: ByteArray): ByteArray = field(number, wire, bytes)
            fun integer(number: Int, value: Long): ByteArray = scalar(number, 0, ProtoWire.varint(value.toULong()))
            val (type, storage) = when (value) {
                is DataMapValue.Bytes -> 1 to scalar(1, 2, value.value)
                is DataMapValue.Text -> 2 to scalar(2, 2, text(value.value))
                is DataMapValue.DoubleNumber -> 3 to scalar(3, 1, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value.value).array())
                is DataMapValue.FloatNumber -> 4 to scalar(4, 5, floatBytes(value.value))
                is DataMapValue.LongNumber -> 5 to integer(5, value.value)
                is DataMapValue.IntNumber -> 6 to integer(6, value.value.toLong())
                is DataMapValue.ByteNumber -> {
                    val number = value.value.toInt()
                    7 to scalar(7, 0, ProtoWire.varint(((number shl 1) xor (number shr 31)).toUInt().toULong()))
                }
                is DataMapValue.Bool -> 8 to integer(8, if (value.value) 1L else 0L)
                is DataMapValue.MapValue -> 9 to join(value.value.toSortedMap().map { (key, nested) -> field(9, 2, entry(key, nested, depth + 1)) })
                is DataMapValue.ListValue -> 10 to join(value.value.map { field(10, 2, typedValue(it, depth + 1)) })
                is DataMapValue.Strings -> 11 to join(value.value.map { scalar(11, 2, text(it)) })
                is DataMapValue.Longs -> 12 to join(value.value.map { integer(12, it) })
                is DataMapValue.AssetIndex -> 13 to integer(13, value.value)
                DataMapValue.Null -> return field(1, 0, ProtoWire.varint(14uL))
                is DataMapValue.Floats -> 15 to join(value.value.map { scalar(14, 5, floatBytes(it)) })
                is DataMapValue.Unknown -> error("Handled above")
            }
            return join(listOf(field(1, 0, ProtoWire.varint(type.toULong())), field(2, 2, storage)))
        }
    }

    private class Reader(val limits: NotificationCodecLimits) {
        var fieldCount = 0
        val notices = mutableListOf<NotificationNotice>()
        val unknowns = mutableListOf<NotificationWireUnknown>()
        fun fields(bytes: ByteArray): List<ProtoField> {
            if (bytes.isEmpty()) return emptyList()
            protocolCheck(fieldCount < limits.maxFields, "Notification field budget exceeded")
            return ProtoWire.decodeFields(bytes, limits.maxDataBytes, limits.maxFields - fieldCount).also { fieldCount += it.size }
        }
        fun record(field: ProtoField, path: String, code: String = "unknown_wire_field") {
            unknowns += NotificationWireUnknown(path, field)
            notices += NotificationNotice(code, path)
        }
        fun selected(fields: List<ProtoField>, number: Int, wire: Int, path: String): List<ProtoField> {
            val candidates = fields.filter { it.number == number }
            candidates.filter { it.wireType != wire }.forEach { record(it, path, "field_wire_type") }
            return candidates.filter { it.wireType == wire }
        }
        fun last(fields: List<ProtoField>, number: Int, wire: Int, path: String): ProtoField? =
            selected(fields, number, wire, path).also { if (it.size > 1) notices += NotificationNotice("last_singular_value", path) }.lastOrNull()
        fun text(bytes: ByteArray, path: String): String = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            notices += NotificationNotice("replaced_invalid_utf8", path)
            bytes.toString(Charsets.UTF_8)
        }
        fun read(bytes: ByteArray): ReadResult {
            val top = fields(bytes)
            val values = entries(selected(top, 1, 2, "$"), 0, "$")
            top.filter { it.number != 1 }.forEach { record(it, "$") }
            val topUnknowns = unknowns.filter { it.fieldPath == "$" }.map { it.field }
            return ReadResult(DataMapDocument(values, topUnknowns, notices.map { it.code }), notices.toList(), unknowns.toList())
        }
        fun entries(entries: List<ProtoField>, depth: Int, path: String): Map<String, DataMapValue> {
            protocolCheck(depth <= limits.maxDepth, "Notification nesting budget exceeded")
            val values = linkedMapOf<String, DataMapValue>()
            entries.forEachIndexed { index, entry ->
                val entryPath = "$path.entry[$index]"
                val contents = fields(entry.contents as ByteArray)
                contents.filter { it.number != 1 && it.number != 2 }.forEach { record(it, entryPath) }
                val nameField = last(contents, 1, 2, "$entryPath.name")
                val valueFields = selected(contents, 2, 2, "$entryPath.value")
                if (nameField == null) {
                    record(entry, entryPath, "missing_entry_name")
                } else {
                    val name = text(nameField.contents as ByteArray, "$entryPath.name")
                    if (values.containsKey(name)) notices += NotificationNotice("duplicate_key", entryPath)
                    if (valueFields.isEmpty()) {
                        notices += NotificationNotice("missing_entry_value", entryPath)
                        values[name] = DataMapValue.Unknown(-1, byteArrayOf())
                    } else {
                        if (valueFields.size > 1) notices += NotificationNotice("merged_singular_message", "$entryPath.value")
                        values[name] = value(join(valueFields.map { it.contents as ByteArray }), depth, "$entryPath.value")
                    }
                }
            }
            return values
        }
        fun join(parts: List<ByteArray>): ByteArray {
            val output = ByteArrayOutputStream()
            for (part in parts) {
                protocolCheck(part.size <= limits.maxDataBytes - output.size(), "Notification data size budget exceeded")
                output.write(part)
            }
            return output.toByteArray()
        }
        fun value(bytes: ByteArray, depth: Int, path: String): DataMapValue {
            protocolCheck(depth <= limits.maxDepth, "Notification nesting budget exceeded")
            val typed = fields(bytes)
            typed.filter { it.number != 1 && it.number != 2 }.forEach { record(it, path) }
            val type = (last(typed, 1, 0, "$path.type")?.contents as? ULong)?.toInt()
            if (type == null) {
                notices += NotificationNotice("missing_value_type", path)
                return DataMapValue.Unknown(-1, bytes)
            }
            if (type !in 1..15) {
                notices += NotificationNotice("unknown_value_type", path)
                return DataMapValue.Unknown(type, bytes)
            }
            val storage = selected(typed, 2, 2, "$path.storage")
            if (storage.isEmpty() && type != 14) notices += NotificationNotice("missing_storage_message", path)
            if (storage.size > 1) notices += NotificationNotice("merged_singular_message", "$path.storage")
            val items = fields(join(storage.map { it.contents as ByteArray }))
            val valueNumber = if (type == 15) 14 else type
            items.filter { it.number != valueNumber || type == 14 }.forEach { record(it, "$path.storage") }
            if (type == 14) return DataMapValue.Null
            fun single(wire: Int): ProtoField? = last(items, valueNumber, wire, "$path.storage.$valueNumber").also {
                if (it == null) notices += NotificationNotice("missing_value_storage", path)
            }
            fun number(): ULong? = single(0)?.contents as? ULong
            fun blob(): ByteArray? = single(2)?.contents as? ByteArray
            return when (type) {
                1 -> blob()?.let { DataMapValue.Bytes(it) } ?: DataMapValue.Unknown(type, bytes)
                2 -> blob()?.let { DataMapValue.Text(text(it, path)) } ?: DataMapValue.Unknown(type, bytes)
                3 -> (single(1)?.contents as? ByteArray)?.let { DataMapValue.DoubleNumber(ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).double) } ?: DataMapValue.Unknown(type, bytes)
                4 -> (single(5)?.contents as? ByteArray)?.let { DataMapValue.FloatNumber(floatValue(it)) } ?: DataMapValue.Unknown(type, bytes)
                5 -> number()?.let { DataMapValue.LongNumber(it.toLong()) } ?: DataMapValue.Unknown(type, bytes)
                6 -> number()?.let { DataMapValue.IntNumber(it.toInt()) } ?: DataMapValue.Unknown(type, bytes)
                7 -> number()?.let { val n = it.toUInt(); DataMapValue.ByteNumber(((n shr 1).toInt() xor -(n and 1u).toInt()).toByte()) } ?: DataMapValue.Unknown(type, bytes)
                8 -> number()?.let { DataMapValue.Bool(it != 0uL) } ?: DataMapValue.Unknown(type, bytes)
                9 -> DataMapValue.MapValue(entries(selected(items, 9, 2, "$path.storage.9"), depth + 1, path))
                10 -> DataMapValue.ListValue(selected(items, 10, 2, "$path.storage.10").mapIndexed { index, it -> value(it.contents as ByteArray, depth + 1, "$path.list[$index]") })
                11 -> DataMapValue.Strings(selected(items, 11, 2, "$path.storage.11").map { text(it.contents as ByteArray, path) })
                12 -> DataMapValue.Longs(packed(items, 12, 0, path).map { (it as ULong).toLong() })
                13 -> number()?.let { DataMapValue.AssetIndex(it.toLong()) } ?: DataMapValue.Unknown(type, bytes)
                15 -> DataMapValue.Floats(packed(items, 14, 5, path).map { floatValue(it as ByteArray) })
                else -> error("Handled above")
            }
        }
        fun packed(items: List<ProtoField>, number: Int, wire: Int, path: String): List<Any> = buildList {
            items.filter { it.number == number }.forEach { field ->
                when (field.wireType) {
                    wire -> add(field.contents)
                    2 -> {
                        val bytes = field.contents as ByteArray
                        var offset = 0
                        while (offset < bytes.size) {
                            protocolCheck(fieldCount++ < limits.maxFields, "Notification field budget exceeded")
                            if (wire == 0) {
                                val decoded = ProtoWire.readVarint(bytes, offset)
                                add(decoded.value); offset = decoded.next
                            } else {
                                // Retain a malformed packed tail while keeping preceding complete values.
                                if (bytes.size - offset < 4) { record(field, path, "packed_float_tail"); break }
                                add(bytes.copyOfRange(offset, offset + 4)); offset += 4
                            }
                        }
                    }
                    else -> record(field, path, "field_wire_type")
                }
            }
        }
    }
    private fun floatBytes(value: Float) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array()
    private fun floatValue(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).float
}
