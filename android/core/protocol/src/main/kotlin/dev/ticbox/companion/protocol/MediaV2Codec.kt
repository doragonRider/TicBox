package dev.ticbox.companion.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Watch-side v2 fields 1..25, in the order the watch's consumer reads them. */
object MediaV2Codec {
    private data class Field(val number: Int, val key: String, val type: String)
    private val schema = listOf(
        Field(1, "watch_app_auto_launch_extra", "string"), Field(2, "watch_app_auto_launch_enabled", "bool"),
        Field(3, "volume", "float"), Field(4, "reserve_space_for_next", "bool"), Field(5, "reserve_space_for_previous", "bool"),
        Field(6, "application_label", "string"), Field(7, "media_session_token", "string"), Field(9, "package_name", "string"),
        Field(10, "transport_flags", "int"), Field(11, "playing", "bool"), Field(13, "notifications.data.source", "bool"),
        Field(14, "title", "string"), Field(15, "artist", "string"), Field(16, "position", "long"),
        Field(17, "position_update_time", "long"), Field(18, "duration", "long"), Field(19, "media_uri", "string"),
        Field(20, "playback_speed", "float"), Field(21, "supports_absolute_volume", "bool"),
        Field(22, "artwork_data_elapsed_time", "long"), Field(23, "elapsed_time", "long"), Field(25, "volume_increments_number", "int"),
    )
    private fun key(name: String) = if (name in setOf("supports_absolute_volume", "volume_increments_number", "elapsed_time", "artwork_data_elapsed_time")) name else MediaProtocol.PREFIX + name

    fun encode(content: MediaContent): ByteArray {
        val out = ByteArrayOutputStream()
        fun emit(number: Int, type: Int, body: ByteArray) { out.write(ProtoWire.encodedField(number, type, body)) }
        schema.forEach { field ->
            val value = content.fields[key(field.key)] ?: content.fields["elapsed_time"].takeIf { field.number == 22 } ?: return@forEach
            when (field.type) {
                "string" -> (value as? DataMapValue.Text)?.let { emit(field.number, 2, it.value.toByteArray(Charsets.UTF_8)) }
                "bool" -> (value as? DataMapValue.Bool)?.let { emit(field.number, 0, ProtoWire.varint(if (it.value) 1uL else 0uL)) }
                "float" -> (value as? DataMapValue.FloatNumber)?.let { emit(field.number, 5, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(it.value).array()) }
                "int" -> (value as? DataMapValue.IntNumber)?.let { emit(field.number, 0, ProtoWire.varint(it.value.toLong().toULong())) }
                "long" -> (value as? DataMapValue.LongNumber)?.let { emit(field.number, 0, ProtoWire.varint(it.value.toULong())) }
            }
        }
        content.lists["mediacontrols.hash_bytes_list"].orEmpty().forEach { certificate ->
            (certificate.fields["mediacontrols.hash_bytes"] as? DataMapValue.Bytes)?.let { emit(8, 2, ProtoWire.encodedField(1, 2, it.value)) }
        }
        content.lists["mediacontrols.custom_actions"].orEmpty().forEach { action ->
            val nested = ByteArrayOutputStream()
            action.assets["mediacontrols.custom_action_icon"]?.data?.let { nested.write(ProtoWire.encodedField(1, 2, it)) }
            (action.fields["mediacontrols.custom_action_action"] as? DataMapValue.Text)?.let { nested.write(ProtoWire.encodedField(2, 2, it.value.toByteArray(Charsets.UTF_8))) }
            (action.fields["mediacontrols.custom_action_name"] as? DataMapValue.Text)?.let { nested.write(ProtoWire.encodedField(3, 2, it.value.toByteArray(Charsets.UTF_8))) }
            emit(12, 2, nested.toByteArray())
        }
        content.maps["mediacontrols.wear_media_bundle"]?.let { emit(24, 2, MediaCodec.encode(it).data) }
        return out.toByteArray().also { protocolCheck(it.size < 50_000, "Media v2 local message budget exceeded") }
    }

    /** Unknown extensions, duplicate fields and nested raw records remain exact observations. */
    fun decode(bytes: ByteArray): MediaV2Document {
        val fields = ProtoWire.decodeFields(bytes)
        val named = linkedMapOf<String, DataMapValue>()
        val notices = mutableListOf<MediaNotice>()
        fields.forEach { raw ->
            val field = schema.firstOrNull { it.number == raw.number } ?: return@forEach
            val value = when {
                field.type == "string" && raw.wireType == 2 -> DataMapValue.Text((raw.value as ByteArray).toString(Charsets.UTF_8))
                field.type == "float" && raw.wireType == 5 -> DataMapValue.FloatNumber(ByteBuffer.wrap(raw.value as ByteArray).order(ByteOrder.LITTLE_ENDIAN).float)
                field.type == "bool" && raw.wireType == 0 -> DataMapValue.Bool((raw.value as ULong) != 0uL)
                field.type == "int" && raw.wireType == 0 -> DataMapValue.IntNumber((raw.value as ULong).toInt())
                field.type == "long" && raw.wireType == 0 -> DataMapValue.LongNumber((raw.value as ULong).toLong())
                else -> { notices += MediaNotice("v2_field_wire_type"); null }
            }
            value?.let { named[key(field.key)] = it }
        }
        return MediaV2Document(named, fields, notices, bytes)
    }
}

class MediaV2Document(val fields: Map<String, DataMapValue>, val wireFields: List<ProtoField>, val notices: List<MediaNotice>, raw: ByteArray) {
    private val body = raw.copyOf()
    val raw: ByteArray get() = body.copyOf()
}
