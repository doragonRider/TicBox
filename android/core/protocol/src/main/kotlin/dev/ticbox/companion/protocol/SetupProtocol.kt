package dev.ticbox.companion.protocol

enum class SetupQuery(val wireValue: Int) {
    GET_STATUS(1), GET_SYSTEM_INFO(2), GET_CONNECTION_INFO(5),
}

/** Setup framing belongs INSIDE a Wear Channel. It is not a raw RFCOMM framing replacement. */
object SetupProtocol {
    const val CHANNEL_PATH = "/setup/channel"
    const val TETHER_CONFIG_PATH = "/clockworkSetupWizard/set_tether_config_request"
    const val RESTORE_STATUS_PATH = "/clockworkSetupWizard/watch_restore_status_request"
    const val CHECKIN_REQUEST_PATH = "/watch_gms_checkin_status/rpc/watch_gms_checkin_status_request"
    const val CHECKIN_UPDATE_PATH = "/watch_gms_checkin_status/rpc/watch_gms_checkin_status_update"

    /** Read-only query construction, without opening a channel or advancing activation. */
    fun encodeQuery(query: SetupQuery, commandId: Long? = null): ByteArray = GmsStructs.encode(
        "SetupMessage", mapOf("type" to 1, "command" to mapOf("type" to query.wireValue, "id" to commandId)),
    )

    fun encodeTime(epochMillis: Long, timeZoneId: String): ByteArray = GmsStructs.encode(
        "SetupMessage", mapOf("type" to 5, "companionTime" to mapOf("millis" to epochMillis, "timeZoneId" to timeZoneId)),
    )

    fun encodeStatus(value: Int): ByteArray = GmsStructs.encode(
        "SetupMessage", mapOf("type" to 3, "status" to mapOf("state" to value)),
    )

    fun encodeOptin(type: Int, state: Int, epochMillis: Long): ByteArray = GmsStructs.encode(
        "SetupMessage", mapOf("type" to 2, "event" to mapOf("type" to 3,
            "optin" to mapOf("type" to type, "state" to state, "time" to epochMillis))),
    )

    fun encodeConnectionInfo(mnc: Int?, mcc: Int?, carrierName: String?): ByteArray = GmsStructs.encode(
        "SetupMessage", mapOf("type" to 7, "connectionInfo" to mapOf("cellInfo" to
            if (mnc != null || mcc != null || carrierName != null) mapOf("mnc" to mnc, "mcc" to mcc, "carrierName" to carrierName) else null)),
    )

    /** Numeric enum identity and explicit presence remain in the returned recovered structure. */
    fun decode(payload: ByteArray): DecodedStruct = GmsStructs.decode("SetupMessage", payload)

    fun encode(message: DecodedStruct): ByteArray {
        require(message.name == "SetupMessage")
        return GmsStructs.encode(message)
    }

    /** Zero-length protobufs are permitted by the observed watch ProtoStreamReader. */
    fun frame(payload: ByteArray): ByteArray = lengthFrame(payload, allowEmpty = true)
}

class SetupFrameDecoder(maxBytes: Int = ProtocolLimits.MAX_MESSAGE_BYTES) {
    private val decoder = LengthFrameDecoder(allowEmpty = true, maxBytes = maxBytes)
    fun push(chunk: ByteArray): List<ByteArray> = decoder.push(chunk)
    fun finish() = decoder.finish()
}
