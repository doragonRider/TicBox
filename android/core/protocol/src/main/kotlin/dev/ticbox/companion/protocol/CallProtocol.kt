package dev.ticbox.companion.protocol

object CallProtocol {
    const val RESET = "/call/rpc/reset_call_state"
    const val CALL = "/call/rpc/call"
    const val AUDIO_STATE = "/call/rpc/call_audio_state"
    const val SILENCE_RINGER = "/call/rpc/silence_ringer"
    const val ACTION_REQUEST = "/call/rpc/call_action_request"
    const val REJECT_REQUEST = "/call/rpc/call_reject_request"
    const val DIAL_REQUEST = "/call/rpc/call_dial_request"
    const val DTMF_REQUEST = "/call/rpc/call_dtmf_request"
    const val LEGACY_PHONE = "/phone"
    const val CALL_V2_CAPABILITY = "call_v2_capability"
    const val FULL_CONTROL_CAPABILITY = "full_companion_call_control_capability"
    val INBOUND_PATHS = setOf(ACTION_REQUEST, REJECT_REQUEST, DIAL_REQUEST, DTMF_REQUEST, LEGACY_PHONE)
    /** The watch firmware's vendor/OEM companion identity. CallApi roles do not imply a com.google.android.gms route. */
    val OFFICIAL_COMPANION_APP_KEY = DataAppKey("com.mobvoi.companion.at", "5cc9866db03e530a21f842a421c7eb42bac58961")
}

data class CallWireState(
    val id: String, val state: Int = -1, val handle: String? = null, val handlePresentation: Int? = null,
    val callerDisplayName: String? = null, val callerDisplayNamePresentation: Int? = null,
    val capabilities: Int? = null, val properties: Int? = null, val disconnectCause: ByteArray? = null,
    val connectTimeMillis: Long? = null, val videoState: Int? = null, val creationTimeMillis: Long? = null,
    val contactDisplayName: String? = null, val direction: Int = -1, val companionCanAnswer: Boolean = true,
    val voipCall: ByteArray? = null, val extras: ByteArray? = null,
)
data class CallWireCommand(val callId: String = "", val action: Int = 0, val reason: Int = 0,
    val number: String = "", val code: String = "", val textMessage: String = "", val notices: List<String> = emptyList())

/** Unknown command fields produce notices without blocking known fields; duplicate scalars use the last value. */
object CallCodec {
    fun call(value: CallWireState): ByteArray = buildList<ByteArray> {
        add(number(1, value.state.toLong())); add(text(2, value.id))
        value.handle?.let { add(text(3, it)) }; value.handlePresentation?.let { add(number(4, it.toLong())) }
        value.callerDisplayName?.let { add(text(5, it)) }; value.callerDisplayNamePresentation?.let { add(number(6, it.toLong())) }
        value.capabilities?.let { add(number(7, it.toLong())) }; value.properties?.let { add(number(8, it.toLong())) }
        value.disconnectCause?.let { add(bytes(9, it)) }; value.connectTimeMillis?.let { add(number(10, it)) }
        value.videoState?.let { add(number(11, it.toLong())) }; value.creationTimeMillis?.let { add(number(12, it)) }
        value.contactDisplayName?.let { add(text(13, it)) }; add(number(14, value.direction.toLong()))
        add(number(15, if (value.companionCanAnswer) 1 else 0))
        value.voipCall?.let { add(bytes(16, it)) }; value.extras?.let { add(bytes(17, it)) }
    }.fold(byteArrayOf()) { all, next -> all + next }

    fun disconnectCause(code: Int, label: String?, description: String?, reason: String?, tone: Int?): ByteArray =
        number(1, code.toLong()) + (label?.let { text(2, it) } ?: byteArrayOf()) +
            (description?.let { text(3, it) } ?: byteArrayOf()) + (reason?.let { text(4, it) } ?: byteArrayOf()) +
            (tone?.let { number(5, it.toLong()) } ?: byteArrayOf())

    fun audioState(muted: Boolean): ByteArray = number(1, if (muted) 1 else 0)
    fun action(callId: String, action: Int): ByteArray = text(1, callId) + number(2, action.toLong())
    fun reject(callId: String, reason: Int, message: String = ""): ByteArray = text(1, callId) + number(2, reason.toLong()) + text(3, message)
    fun dial(number: String): ByteArray = text(1, number)
    fun dtmf(callId: String, code: String): ByteArray = text(1, callId) + text(2, code)

    fun decodeCommand(path: String, payload: ByteArray): CallWireCommand {
        val fields = ProtoWire.decodeFields(payload)
        val notices = mutableListOf<String>()
        fun string(field: Int): String {
            val matching = fields.filter { it.number == field }
            if (matching.any { it.wireType != 2 }) notices += "field_${field}_unexpected_wire_type"
            if (matching.count { it.wireType == 2 } > 1) notices += "field_${field}_last_value_used"
            return (matching.lastOrNull { it.wireType == 2 }?.value as? ByteArray)?.toString(Charsets.UTF_8) ?: ""
        }
        fun integer(field: Int): Int {
            val matching = fields.filter { it.number == field }
            if (matching.any { it.wireType != 0 }) notices += "field_${field}_unexpected_wire_type"
            if (matching.count { it.wireType == 0 } > 1) notices += "field_${field}_last_value_used"
            return (matching.lastOrNull { it.wireType == 0 }?.value as? ULong)?.toInt() ?: 0
        }
        val command = when (path) {
            CallProtocol.ACTION_REQUEST -> CallWireCommand(callId = string(1), action = integer(2))
            CallProtocol.REJECT_REQUEST -> CallWireCommand(callId = string(1), reason = integer(2), textMessage = string(3))
            CallProtocol.DIAL_REQUEST -> CallWireCommand(number = string(1))
            CallProtocol.DTMF_REQUEST -> CallWireCommand(callId = string(1), code = string(2))
            else -> throw ProtocolException("Unsupported call command path")
        }
        val known = when (path) { CallProtocol.REJECT_REQUEST -> setOf(1, 2, 3); CallProtocol.DIAL_REQUEST -> setOf(1); else -> setOf(1, 2) }
        fields.filter { it.number !in known }.map { it.number }.distinct().forEach { notices += "unknown_field_$it" }
        return command.copy(notices = notices)
    }

    private fun number(field: Int, value: Long) = ProtoWire.encodedField(field, 0, ProtoWire.varint(value.toULong()))
    private fun bytes(field: Int, value: ByteArray) = ProtoWire.encodedField(field, 2, value)
    private fun text(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))
}
