package dev.ticbox.companion.protocol

/** Dynamic fields retain original and future media extensions without exposing them to the UI. */
data class MediaContent(
    val fields: Map<String, DataMapValue> = emptyMap(),
    val assets: Map<String, MediaAsset> = emptyMap(),
    val maps: Map<String, MediaContent> = emptyMap(),
    val lists: Map<String, List<MediaContent>> = emptyMap(),
)
class MediaAsset(data: ByteArray? = null, val digest: String? = null) {
    private val body = data?.copyOf()
    val data: ByteArray? get() = body?.copyOf()
    val sizeBytes: Int? get() = body?.size
}
data class MediaNotice(val code: String)
data class MediaWireUnknown(val fieldPath: String, val field: ProtoField)
data class MediaCommand(
    val command: String? = null,
    val volumeDelta: Int? = null,
    val absoluteVolume: Float? = null,
    val positionMillis: Long? = null,
    val playQueueId: Long? = null,
    val rating: Int? = null,
    val customAction: String? = null,
    val customActionExtras: Map<String, DataMapValue> = emptyMap(),
    val fields: Map<String, DataMapValue> = emptyMap(),
    val notices: List<MediaNotice> = emptyList(),
    val unknownFields: List<ProtoField> = emptyList(),
    val wireUnknownFields: List<MediaWireUnknown> = emptyList(),
)

object MediaProtocol {
    const val PATH = "/mediacontrols/mediacontrols"
    const val V2_PATH = "/media_controls_bridging_v2/rpc/media_notification_message"
    const val V2_CAPABILITY = "media_notification_message_watch"
    const val PREFIX = "mediacontrols."
    val transportCommands: Set<String> = setOf("previous", "togglepause", "next", "play", "stop", "pause", "seek_forward", "seek_back")
    /** A whole command can carry several independent operations, as in the source listener. */
    fun operationCount(command: MediaCommand): Int =
        (if (command.command in transportCommands) 1 else 0) +
            (if (command.volumeDelta == -1 || command.volumeDelta == 1) 1 else 0) +
            (if (command.absoluteVolume != null) 1 else 0) + (if (command.positionMillis != null) 1 else 0) +
            (if (command.playQueueId != null) 1 else 0) + (if (command.rating?.let { it in -1..1 } == true) 1 else 0) +
            (if (!command.customAction.isNullOrEmpty()) 1 else 0)
}
