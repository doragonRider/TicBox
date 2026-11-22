package dev.ticbox.companion.protocol

data class NotificationStreamId(
    val creatorNodeId: String,
    val packageName: String,
    val notificationId: Int,
    val tag: String?,
    /** Null supports the older five-part watch path; the observed phone writer supplies a key. */
    val notificationKey: String?,
)

/** Dynamic DataMap keys retain compatibility with styles this client does not interpret. */
data class NotificationContent(
    val fields: Map<String, DataMapValue> = emptyMap(),
    val assets: Map<String, NotificationAsset> = emptyMap(),
    val maps: Map<String, NotificationContent> = emptyMap(),
    /** Includes pages/actions/wearable_actions/messages/remote_inputs as appropriate. */
    val lists: Map<String, List<NotificationContent>> = emptyMap(),
)

/** An existing digest or caller-produced file; no MIME format is invented from its role. */
class NotificationAsset(data: ByteArray? = null, val digest: String? = null, val mimeType: String? = null) {
    private val body = data?.copyOf()
    val data: ByteArray? get() = body?.copyOf()
    val sizeBytes: Int? get() = body?.size
}

class NotificationEncodedAsset(
    val index: Long,
    val fieldPath: String,
    val asset: NotificationAsset,
) {
    val slot: String get() = index.toString()
    val name: String get() = slot
    val type: Int get() = 4
    val data: ByteArray? get() = asset.data
    val digest: String? get() = asset.digest
    val mimeType: String? get() = asset.mimeType
}

data class NotificationAssetReference(val index: Long, val slot: String, val fieldPath: String)
data class NotificationNotice(val code: String, val field: String? = null)
data class NotificationWireUnknown(val fieldPath: String, val field: ProtoField)

class NotificationEncodedContent(
    data: ByteArray,
    val assets: List<NotificationEncodedAsset>,
    val assetReferences: List<NotificationAssetReference>,
    val notices: List<NotificationNotice> = emptyList(),
) {
    private val body = data.copyOf()
    val data: ByteArray get() = body.copyOf()
}

class NotificationDecodedContent(
    val document: DataMapDocument,
    val assetReferences: List<NotificationAssetReference>,
    val notices: List<NotificationNotice>,
    raw: ByteArray,
    /** Includes unknown tags inside otherwise recognized nested values. */
    val wireUnknownFields: List<NotificationWireUnknown> = emptyList(),
) {
    private val body = raw.copyOf()
    val raw: ByteArray get() = body.copyOf()
    val fields: Map<String, DataMapValue> get() = document.values
    fun maps(key: String): List<Map<String, DataMapValue>> = (fields[key] as? DataMapValue.ListValue)?.value
        ?.mapNotNull { (it as? DataMapValue.MapValue)?.value } ?: emptyList()
    val pages get() = maps("pages")
    val actions get() = maps("actions")
    val wearableActions get() = maps("wearable_actions")
    val messages get() = maps("messages")
}

data class NotificationReply(
    val stringResult: String? = null,
    val charSequenceHtml: String? = null,
    val fields: Map<String, DataMapValue> = emptyMap(),
)

data class NotificationActionRequest(
    val intentId: String? = null,
    val invocationId: String? = null,
    val remoteStreamItemId: String? = null,
    val actionIndex: Int? = null,
    val isWearableAction: Boolean? = null,
    val replies: Map<String, NotificationReply> = emptyMap(),
    val command: String? = NotificationProtocol.BRIDGED_ACTION,
    /** Decoded original values, including unknown commands/fields; never dump as diagnostics. */
    val fields: Map<String, DataMapValue> = emptyMap(),
    val notices: List<NotificationNotice> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
) {
    val isBridgedAction: Boolean get() = command == NotificationProtocol.BRIDGED_ACTION
}

data class NotificationActionResult(
    val invocationId: String?,
    val successful: Boolean?,
    val messageId: Int?,
    val fields: Map<String, DataMapValue> = emptyMap(),
    val resultFields: Map<String, DataMapValue> = emptyMap(),
    val notices: List<NotificationNotice> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
)

/** Local allocation budgets, not assertions about the target's notification semantics. */
data class NotificationCodecLimits(
    val maxDataBytes: Int = ProtocolLimits.MAX_MESSAGE_BYTES,
    val maxFields: Int = ProtocolLimits.MAX_FIELDS,
    val maxDepth: Int = ProtocolLimits.MAX_DEPTH,
    val maxAssets: Int = 128,
    val maxAssetBytes: Int = 16 * 1024 * 1024,
    val maxTotalAssetBytes: Long = 64L * 1024 * 1024,
    val maxPathChars: Int = 65_536,
) {
    init {
        require(maxDataBytes > 0 && maxFields > 0 && maxDepth > 0 && maxPathChars > 0)
        require(maxAssets >= 0 && maxAssetBytes >= 0 && maxTotalAssetBytes >= 0)
    }
}
