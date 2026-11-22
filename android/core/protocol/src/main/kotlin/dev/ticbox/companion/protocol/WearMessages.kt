package dev.ticbox.companion.protocol

/** Constructor defaults describe the observed sender. [presentFields] records decoded presence. */
data class ConnectMessage(
    val nodeId: String,
    val nodeName: String,
    val androidId: Long? = null,
    val connectionVersion: Int = 3,
    val wireVersion: Int = 1,
    val minWireVersion: Int = 0,
    val networkId: String? = null,
    val companionPackage: String? = null,
    val isMigrating: Boolean? = null,
    val previousNodeId: String? = null,
    val senderSdkVersion: Int? = null,
    val unknownFields: List<ProtoField> = emptyList(),
    val presentFields: Set<Int>? = null,
)

enum class RpcKind(val fieldNumber: Int, val fieldName: String) {
    MESSAGE(10, "rpcRequest"), CHANNEL(16, "channelRpcRequest"), SERVICE(18, "rpcServiceRequest"),
}

data class RpcMessage(
    val requestId: Int,
    val packageName: String,
    val packageDigest: String,
    val targetNodeId: String,
    val path: String,
    val payload: ByteArray = byteArrayOf(),
    val sourceNodeId: String? = null,
    val expectsResponse: Boolean? = null,
    val responseCorrelationToken: Int? = null,
    val legacyZero: Int = 0,
    val channel: ChannelRequest? = null,
    val generation: Int? = null,
    val messageOption: Int? = null,
    val kind: RpcKind = RpcKind.MESSAGE,
    val unknownFields: List<ProtoField> = emptyList(),
    val payloadPresent: Boolean = true,
)

/** Typed mainline messages, backed by the complete recovered Android container schema.
 * WearMessage has independent optional members; it is not treated as a oneof.
 */
object WearMessages {
    fun decode(bytes: ByteArray): DecodedStruct = GmsStructs.decode("WearMessage", bytes)
    fun encode(message: DecodedStruct): ByteArray {
        require(message.name == "WearMessage")
        return GmsStructs.encode(message)
    }

    fun encodeConnect(message: ConnectMessage): ByteArray = GmsStructs.encode(
        "WearMessage", mapOf("connect" to connectStruct(message)),
    )

    fun connectOrNull(bytes: ByteArray): ConnectMessage? = connectOrNull(decode(bytes))

    fun connectOrNull(envelope: DecodedStruct): ConnectMessage? {
        val raw = envelope["connect"] as? DecodedStruct ?: return null
        return ConnectMessage(
            nodeId = raw["nodeId"] as String,
            nodeName = raw["nodeName"] as String,
            androidId = raw["androidId"] as? Long,
            connectionVersion = raw["connectionVersion"] as? Int ?: 0,
            wireVersion = raw["wireVersion"] as? Int ?: 0,
            minWireVersion = raw["minWireVersion"] as? Int ?: 0,
            networkId = raw["networkId"] as? String,
            companionPackage = raw["companionPackage"] as? String,
            isMigrating = raw["isMigrating"] as? Boolean,
            previousNodeId = raw["previousNodeId"] as? String,
            senderSdkVersion = raw["senderSdkVersion"] as? Int,
            unknownFields = raw.unknownFields,
            presentFields = raw.fields.map { it.number }.toSet(),
        )
    }

    private fun connectStruct(message: ConnectMessage): DecodedStruct {
        val values = linkedMapOf<String, Any?>("nodeId" to message.nodeId, "nodeName" to message.nodeName)
        fun add(number: Int, name: String, value: Any?) {
            if (message.presentFields == null || number in message.presentFields) values[name] = value
        }
        add(3, "androidId", message.androidId)
        add(4, "connectionVersion", message.connectionVersion)
        add(5, "wireVersion", message.wireVersion)
        add(6, "minWireVersion", message.minWireVersion)
        add(7, "networkId", message.networkId)
        add(8, "companionPackage", message.companionPackage)
        add(9, "isMigrating", message.isMigrating)
        add(10, "previousNodeId", message.previousNodeId)
        add(11, "senderSdkVersion", message.senderSdkVersion)
        return struct("Connect", values, message.unknownFields)
    }

    fun encodeRpc(message: RpcMessage): ByteArray = GmsStructs.encode(
        "WearMessage", mapOf(message.kind.fieldName to rpcStruct(message)),
    )

    fun decodeRpcs(bytes: ByteArray): List<RpcMessage> = decodeRpcs(decode(bytes))

    fun decodeRpcs(envelope: DecodedStruct): List<RpcMessage> {
        return RpcKind.entries.mapNotNull { kind ->
            val raw = envelope[kind.fieldName] as? DecodedStruct ?: return@mapNotNull null
            RpcMessage(
                requestId = raw["requestId"] as Int,
                packageName = raw["packageName"] as String,
                packageDigest = raw["packageDigest"] as String,
                targetNodeId = raw["targetNodeId"] as String,
                path = raw["path"] as String,
                payload = raw["payload"] as? ByteArray ?: byteArrayOf(),
                sourceNodeId = raw["sourceNodeId"] as? String,
                expectsResponse = raw["expectsResponse"] as? Boolean,
                responseCorrelationToken = raw["responseToken"] as? Int,
                legacyZero = raw["legacyZero"] as Int,
                channel = (raw["channel"] as? DecodedStruct)?.let(ChannelCodec::fromStruct),
                generation = raw["generation"] as? Int,
                messageOption = raw["messageOption"] as? Int,
                kind = kind,
                unknownFields = raw.unknownFields,
                payloadPresent = "payload" in raw.values,
            )
        }
    }

    private fun rpcStruct(message: RpcMessage): DecodedStruct = struct("RpcRequest", linkedMapOf<String, Any?>(
        "requestId" to message.requestId,
        "packageName" to message.packageName,
        "packageDigest" to message.packageDigest,
        "targetNodeId" to message.targetNodeId,
        "legacyZero" to message.legacyZero,
        "path" to message.path,
        "sourceNodeId" to message.sourceNodeId,
        "channel" to message.channel?.let(ChannelCodec::toStruct),
        "generation" to message.generation,
        "expectsResponse" to message.expectsResponse,
        "responseToken" to message.responseCorrelationToken,
        "messageOption" to message.messageOption,
    ).apply { if (message.payloadPresent) put("payload", message.payload) }, message.unknownFields)

    fun encodeHeartbeat(): ByteArray = GmsStructs.encode("WearMessage", mapOf("heartbeat" to emptyMap<String, Any>()))
}

internal fun struct(name: String, values: Map<String, Any?>, unknownFields: List<ProtoField> = emptyList()): DecodedStruct =
    DecodedStruct(name, values.filterValues { it != null }.mapValues { it.value!! }, emptyList(), unknownFields)
