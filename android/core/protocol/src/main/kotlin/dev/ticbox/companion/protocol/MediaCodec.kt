package dev.ticbox.companion.protocol

/** Shares the recovered DataMap/Asset allocator, including nested UTF-16 TreeSet slot order. */
object MediaCodec {
    fun encode(content: MediaContent, limits: NotificationCodecLimits = NotificationCodecLimits()): MediaEncodedContent {
        val encoded = NotificationCodec.encode(content.toDataMapContent(), limits)
        return MediaEncodedContent(encoded.data, encoded.assets.map { MediaEncodedAsset(it.slot, it.fieldPath, it.data, it.digest) },
            encoded.notices.map { MediaNotice(it.code) })
    }

    fun decode(bytes: ByteArray): MediaDecodedContent {
        val decoded = NotificationCodec.decode(bytes)
        return MediaDecodedContent(decoded.document, decoded.notices.map { MediaNotice(it.code) }, decoded.document.unknownFields, bytes,
            decoded.wireUnknownFields.map { MediaWireUnknown(it.fieldPath, it.field) })
    }

    fun decodeCommand(bytes: ByteArray): MediaCommand {
        val decoded = decode(bytes)
        val fields = decoded.document.values
        val notices = decoded.notices.toMutableList()
        fun text(key: String): String? = (fields[key] as? DataMapValue.Text)?.value.also {
            if (it == null && fields.containsKey(key)) notices += MediaNotice("command_string_type")
        }
        fun number(key: String): Number? = when (val value = fields[key]) {
            null -> null
            is DataMapValue.IntNumber -> value.value
            is DataMapValue.LongNumber -> value.value
            is DataMapValue.FloatNumber -> value.value
            is DataMapValue.DoubleNumber -> value.value
            is DataMapValue.ByteNumber -> value.value
            else -> { notices += MediaNotice("command_number_type"); null }
        }
        val extras = (fields["custom_action_extras"] as? DataMapValue.MapValue)?.value.orEmpty()
        return MediaCommand(text("command"), number("volume")?.toInt(), number("absolute_volume")?.toFloat(),
            number("current_media_item_position")?.toLong(), number("play_id")?.toLong(), number("rating")?.toInt(),
            text("custom_action"), extras, fields, notices, decoded.unknownFields, decoded.wireUnknownFields)
    }

    fun encodeCommand(command: MediaCommand): ByteArray {
        val fields = command.fields.toMutableMap()
        command.command?.let { fields["command"] = DataMapValue.Text(it) }
        command.volumeDelta?.let { fields["volume"] = DataMapValue.IntNumber(it) }
        command.absoluteVolume?.let { fields["absolute_volume"] = DataMapValue.FloatNumber(it) }
        command.positionMillis?.let { fields["current_media_item_position"] = DataMapValue.LongNumber(it) }
        command.playQueueId?.let { fields["play_id"] = DataMapValue.LongNumber(it) }
        command.rating?.let { fields["rating"] = DataMapValue.IntNumber(it) }
        command.customAction?.let { fields["custom_action"] = DataMapValue.Text(it) }
        if (command.customActionExtras.isNotEmpty()) fields["custom_action_extras"] = DataMapValue.MapValue(command.customActionExtras)
        return DataMapCodec.encode(DataMapDocument(fields, command.unknownFields))
    }

    private fun MediaContent.toDataMapContent(): NotificationContent = NotificationContent(fields,
        assets.mapValues { (_, asset) -> NotificationAsset(asset.data, asset.digest) },
        maps.mapValues { (_, content) -> content.toDataMapContent() }, lists.mapValues { (_, contents) -> contents.map { it.toDataMapContent() } })
}

class MediaEncodedAsset(val slot: String, val fieldPath: String, data: ByteArray?, val digest: String?) {
    private val body = data?.copyOf()
    val data: ByteArray? get() = body?.copyOf()
    val type: Int get() = 4
}
class MediaEncodedContent(data: ByteArray, val assets: List<MediaEncodedAsset>, val notices: List<MediaNotice>) {
    private val body = data.copyOf()
    val data: ByteArray get() = body.copyOf()
}
class MediaDecodedContent(val document: DataMapDocument, val notices: List<MediaNotice>, val unknownFields: List<ProtoField>, raw: ByteArray,
    val wireUnknownFields: List<MediaWireUnknown> = emptyList()) {
    private val body = raw.copyOf()
    val raw: ByteArray get() = body.copyOf()
}
