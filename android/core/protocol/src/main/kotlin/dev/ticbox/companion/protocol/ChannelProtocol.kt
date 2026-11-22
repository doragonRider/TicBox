package dev.ticbox.companion.protocol

/** Optional members remain independent, matching the recovered schema rather than a oneof. */
data class ChannelRequest(
    val control: ChannelControl? = null,
    val data: ChannelData? = null,
    val ack: ChannelAck? = null,
    val minimumVersion: Int? = null,
    val origin: Int? = null,
    val unknownFields: List<ProtoField> = emptyList(),
)

data class ChannelControl(
    val type: Int? = null,
    val channelId: ULong? = null,
    val fromChannelOpener: Boolean? = null,
    val packageName: String? = null,
    val packageDigest: String? = null,
    val path: String? = null,
    val closeErrorCode: Int? = null,
    val allowOverMetered: Boolean? = null,
    val opaque: ByteArray? = null,
    val unknownFields: List<ProtoField> = emptyList(),
)

data class ChannelHeader(
    val channelId: ULong? = null,
    val fromChannelOpener: Boolean? = null,
    val sequence: Long? = null,
    val unknownFields: List<ProtoField> = emptyList(),
)

data class ChannelData(
    val header: ChannelHeader? = null,
    val data: ByteArray? = null,
    val final: Boolean? = null,
    val unknownFields: List<ProtoField> = emptyList(),
)

data class ChannelAck(
    val header: ChannelHeader? = null,
    val final: Boolean? = null,
    val unknownFields: List<ProtoField> = emptyList(),
)

object ChannelCodec {
    const val OPEN = 1
    const val OPEN_ACK = 2
    const val CLOSE = 3
    const val CHANNEL_API = 0
    const val LARGE_ASSET_API = 1
    const val MAX_DATA_BYTES = 65536

    fun encode(request: ChannelRequest): ByteArray = GmsStructs.encode(toStruct(request))
    fun decode(bytes: ByteArray): ChannelRequest = fromStruct(GmsStructs.decode("ChannelRequest", bytes))

    /** Routing/runtime guards from the selected receiver, separate from protobuf decoding. */
    fun validateForDelivery(request: ChannelRequest) {
        protocolCheck((request.minimumVersion ?: 0) <= 1, "Unsupported Channel minimum version")
        protocolCheck((request.origin ?: 0) in 0..1, "Unsupported Channel origin")
        // Control wins over data; data wins over ACK in the observed receiver.
        // Keep numeric control values for the session interpreter. The selected stock handler
        // treats unknown control values as OPEN, so rejecting them here would be stricter.
        if (request.control == null && request.data != null) {
            protocolCheck((request.data.data?.size ?: 0) <= MAX_DATA_BYTES, "Channel payload exceeds receiver limit")
        }
    }

    internal fun toStruct(request: ChannelRequest): DecodedStruct = struct("ChannelRequest", mapOf(
        "control" to request.control?.let(::controlStruct),
        "data" to request.data?.let { struct("ChannelData", mapOf("header" to it.header?.let(::headerStruct), "data" to it.data, "final" to it.final), it.unknownFields) },
        "ack" to request.ack?.let { struct("ChannelAck", mapOf("header" to it.header?.let(::headerStruct), "final" to it.final), it.unknownFields) },
        "minimumVersion" to request.minimumVersion,
        "origin" to request.origin,
    ), request.unknownFields)

    internal fun fromStruct(raw: DecodedStruct): ChannelRequest {
        require(raw.name == "ChannelRequest")
        val control = raw["control"] as? DecodedStruct
        val data = raw["data"] as? DecodedStruct
        val ack = raw["ack"] as? DecodedStruct
        return ChannelRequest(
            control = control?.let { ChannelControl(it["type"] as? Int, it["channelId"] as? ULong, it["fromChannelOpener"] as? Boolean, it["packageName"] as? String, it["packageDigest"] as? String, it["path"] as? String, it["closeErrorCode"] as? Int, it["allowOverMetered"] as? Boolean, it["opaque"] as? ByteArray, it.unknownFields) },
            data = data?.let { ChannelData((it["header"] as? DecodedStruct)?.let(::fromHeader), it["data"] as? ByteArray, it["final"] as? Boolean, it.unknownFields) },
            ack = ack?.let { ChannelAck((it["header"] as? DecodedStruct)?.let(::fromHeader), it["final"] as? Boolean, it.unknownFields) },
            minimumVersion = raw["minimumVersion"] as? Int,
            origin = raw["origin"] as? Int,
            unknownFields = raw.unknownFields,
        )
    }

    private fun controlStruct(value: ChannelControl): DecodedStruct = struct("ChannelControl", mapOf(
        "type" to value.type, "channelId" to value.channelId, "fromChannelOpener" to value.fromChannelOpener,
        "packageName" to value.packageName, "packageDigest" to value.packageDigest, "path" to value.path,
        "closeErrorCode" to value.closeErrorCode, "allowOverMetered" to value.allowOverMetered, "opaque" to value.opaque,
    ), value.unknownFields)

    private fun headerStruct(value: ChannelHeader): DecodedStruct = struct("ChannelHeader", mapOf(
        "channelId" to value.channelId, "fromChannelOpener" to value.fromChannelOpener, "sequence" to value.sequence,
    ), value.unknownFields)

    private fun fromHeader(raw: DecodedStruct): ChannelHeader = ChannelHeader(
        raw["channelId"] as? ULong, raw["fromChannelOpener"] as? Boolean, raw["sequence"] as? Long, raw.unknownFields,
    )
}
