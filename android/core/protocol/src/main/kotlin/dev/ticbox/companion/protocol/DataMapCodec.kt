package dev.ticbox.companion.protocol

import java.io.ByteArrayOutputStream

/** Types mirror the recovered DataMap discriminator; unknown values retain their original bytes. */
sealed interface DataMapValue {
    class Bytes(bytes: ByteArray) : DataMapValue { private val body = bytes.copyOf(); val value get() = body.copyOf() }
    data class Text(val value: String) : DataMapValue
    data class DoubleNumber(val value: Double) : DataMapValue
    data class FloatNumber(val value: Float) : DataMapValue
    data class LongNumber(val value: Long) : DataMapValue
    data class IntNumber(val value: Int) : DataMapValue
    data class ByteNumber(val value: Byte) : DataMapValue
    data class Bool(val value: Boolean) : DataMapValue
    data class MapValue(val value: Map<String, DataMapValue>) : DataMapValue
    data class ListValue(val value: List<DataMapValue>) : DataMapValue
    data class Strings(val value: List<String>) : DataMapValue
    data class Longs(val value: List<Long>) : DataMapValue
    data class AssetIndex(val value: Long) : DataMapValue
    data object Null : DataMapValue
    data class Floats(val value: List<Float>) : DataMapValue
    class Unknown(val type: Int, bytes: ByteArray) : DataMapValue { private val body = bytes.copyOf(); val raw get() = body.copyOf() }
}

data class DataMapDocument(
    val values: Map<String, DataMapValue>,
    val unknownFields: List<ProtoField> = emptyList(),
    val notices: List<String> = emptyList(),
)

/** Explicit opt-in only: ordinary DataApi byte[] payloads need not be a DataMap. */
object DataMapCodec {
    fun decode(bytes: ByteArray): DataMapDocument {
        val raw = GmsStructs.decode("DataMap", bytes)
        val notices = raw.notices.map { "${it.kind}:${it.path}" }.toMutableList()
        val values = entries(raw["entries"], notices)
        return DataMapDocument(values, raw.unknownFields, notices.toList())
    }
    private fun entries(raw: Any?, notices: MutableList<String>): Map<String, DataMapValue> = linkedMapOf<String, DataMapValue>().apply {
        (raw as? List<*>)?.filterIsInstance<DecodedStruct>()?.forEach { entry ->
            val name = entry["name"] as String
            if (containsKey(name)) notices += "duplicate_key:$name"
            put(name, value(entry["value"] as DecodedStruct, notices))
        }
    }
    private fun value(raw: DecodedStruct, notices: MutableList<String>): DataMapValue {
        val type = raw["type"] as Int
        val storage = raw["storage"] as? DecodedStruct
        fun scalar(name: String): Any? = storage?.get(name)
        if (storage == null && type != 14) notices += "missing_storage:$type"
        return when (type) {
            1 -> DataMapValue.Bytes(scalar("byteArray") as? ByteArray ?: byteArrayOf())
            2 -> DataMapValue.Text(scalar("stringValue") as? String ?: "")
            3 -> DataMapValue.DoubleNumber(scalar("doubleValue") as? Double ?: 0.0)
            4 -> DataMapValue.FloatNumber(scalar("floatValue") as? Float ?: 0f)
            5 -> DataMapValue.LongNumber(scalar("longValue") as? Long ?: 0L)
            6 -> DataMapValue.IntNumber(scalar("intValue") as? Int ?: 0)
            7 -> DataMapValue.ByteNumber((scalar("byteValue") as? Int ?: 0).toByte())
            8 -> DataMapValue.Bool(scalar("boolValue") as? Boolean ?: false)
            9 -> DataMapValue.MapValue(entries(scalar("mapEntries"), notices))
            10 -> DataMapValue.ListValue((scalar("listValues") as? List<*>)?.filterIsInstance<DecodedStruct>()?.map { value(it, notices) } ?: emptyList())
            11 -> DataMapValue.Strings((scalar("stringArray") as? List<*>)?.filterIsInstance<String>() ?: emptyList())
            12 -> DataMapValue.Longs((scalar("longArray") as? List<*>)?.filterIsInstance<Long>() ?: emptyList())
            13 -> DataMapValue.AssetIndex(scalar("assetIndex") as? Long ?: 0L)
            14 -> DataMapValue.Null
            15 -> DataMapValue.Floats((scalar("floatArray") as? List<*>)?.filterIsInstance<Float>() ?: emptyList())
            else -> { notices += "unknown_type:$type"; DataMapValue.Unknown(type, join(raw.fields.map { it.raw })) }
        }
    }

    fun encode(document: DataMapDocument): ByteArray = join(listOf(encode(document.values)) + document.unknownFields.map { it.raw })
        .also { GmsStructs.decode("DataMap", it) }
    fun encode(values: Map<String, DataMapValue>): ByteArray {
        var total = 0
        fun entry(name: String, value: DataMapValue, depth: Int): ByteArray {
            val bytes = ProtoWire.encodedField(1, 2, name.toByteArray(Charsets.UTF_8)) + ProtoWire.encodedField(2, 2, encodeValue(value, depth))
            return bytes
        }
        // Kotlin/Java String natural order is UTF-16 code-unit order, matching TreeSet.
        val encoded = values.toSortedMap().map { (name, value) ->
            ProtoWire.encodedField(1, 2, entry(name, value, 0)).also {
                total += it.size; protocolCheck(total <= ProtocolLimits.MAX_MESSAGE_BYTES, "DataMap size budget exceeded")
            }
        }
        return join(encoded).also { GmsStructs.decode("DataMap", it) }
    }
    private fun join(parts: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { part ->
            protocolCheck(part.size <= ProtocolLimits.MAX_MESSAGE_BYTES - out.size(), "DataMap size budget exceeded")
            out.write(part)
        }
        return out.toByteArray()
    }
    private fun encodeValue(value: DataMapValue, depth: Int): ByteArray {
        protocolCheck(depth <= ProtocolLimits.MAX_DEPTH, "DataMap nesting budget exceeded")
        if (value is DataMapValue.Unknown) return value.raw
        val (type, storage) = when (value) {
            is DataMapValue.Bytes -> 1 to mapOf("byteArray" to value.value)
            is DataMapValue.Text -> 2 to mapOf("stringValue" to value.value)
            is DataMapValue.DoubleNumber -> 3 to mapOf("doubleValue" to value.value)
            is DataMapValue.FloatNumber -> 4 to mapOf("floatValue" to value.value)
            is DataMapValue.LongNumber -> 5 to mapOf("longValue" to value.value)
            is DataMapValue.IntNumber -> 6 to mapOf("intValue" to value.value)
            is DataMapValue.ByteNumber -> 7 to mapOf("byteValue" to value.value.toInt())
            is DataMapValue.Bool -> 8 to mapOf("boolValue" to value.value)
            is DataMapValue.MapValue -> {
                // Build nested entries directly to retain an unknown value's exact representation.
                val raw = value.value.toSortedMap().map { (key, nested) ->
                    ProtoWire.encodedField(9, 2, ProtoWire.encodedField(1, 2, key.toByteArray(Charsets.UTF_8)) +
                        ProtoWire.encodedField(2, 2, encodeValue(nested, depth + 1)))
                }.let(::join)
                return ProtoWire.encodedField(1, 0, ProtoWire.varint(9uL)) + ProtoWire.encodedField(2, 2, raw)
            }
            is DataMapValue.ListValue -> {
                val raw = join(value.value.map { ProtoWire.encodedField(10, 2, encodeValue(it, depth + 1)) })
                return ProtoWire.encodedField(1, 0, ProtoWire.varint(10uL)) + ProtoWire.encodedField(2, 2, raw)
            }
            is DataMapValue.Strings -> 11 to mapOf("stringArray" to value.value)
            is DataMapValue.Longs -> 12 to mapOf("longArray" to value.value)
            is DataMapValue.AssetIndex -> 13 to mapOf("assetIndex" to value.value)
            DataMapValue.Null -> return GmsStructs.encode("DataMapValue", mapOf("type" to 14))
            is DataMapValue.Floats -> 15 to mapOf("floatArray" to value.value)
            is DataMapValue.Unknown -> error("Handled above")
        }
        return GmsStructs.encode("DataMapValue", mapOf("type" to type, "storage" to storage))
    }
}
