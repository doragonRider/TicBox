package dev.ticbox.companion.protocol

object DeviceSettingsProtocol {
    const val WEAR_INFO = "/companion/sync_wear_info"
    const val BATTERY = "/companion/sync_wear_battery"
    const val HEART_HEALTH = "/health/cmd_change_heart_health_switch"
    const val REMOTE_INTENT = "/remote_intent/rpc"
    const val REMOTE_INTENT_RESULT = "/remote_intent/result"
    const val LOCK_SUPPORT = "/lock_screen_configuration/rpc/get_lock_screen_config_request"
    const val LOCK_CHANGE = "/lock_screen/rpc/change_request"
    const val LOCK_CANCEL = "/lock_screen/rpc/cancel_ongoing_lock_configuration"
    const val LOCK_STARTED = "/lock_screen/rpc/notify_configuration_started_request"
    const val LOCK_UNLOCKED = "/lock_screen/rpc/notify_lock_screen_unlock_finished_request"
    const val LOCK_FINISHED = "/lock_screen/rpc/notify_configuration_finished_request"
    const val LOCK_TYPES = "/lock_screen/data/supported_lock_screen_types"
    const val APPS = "/installed_apps/data/installed_apps/"
    val MESSAGES = setOf(WEAR_INFO, BATTERY, REMOTE_INTENT_RESULT)
    val REQUESTS = setOf(LOCK_STARTED, LOCK_UNLOCKED, LOCK_FINISHED)
}

data class DecodedInstalledApp(val packageName: String?, val label: String?, val iconAssetName: String?,
    val launchable: Boolean?, val notificationPermissionState: Int?, val unknownFields: Int)

/** OEM defaults, unknown enum numbers and fields survive; structural byte budgets remain bounded. */
object DeviceSettingsCodec {
    fun lockType(type: Int): ByteArray = ProtoWire.encodedField(1, 0, ProtoWire.varint(type.toULong()))
    fun code(bytes: ByteArray): Int = ProtoWire.decodeFields(bytes).lastOrNull { it.number == 1 && it.wireType == 0 }
        ?.let { (it.value as ULong).toInt() } ?: 0
    /** Accept both observed packed DataItem and unpacked RPC response representations. */
    fun lockTypes(bytes: ByteArray): List<Int> = buildList {
        ProtoWire.decodeFields(bytes).filter { it.number == 1 }.forEach { field -> when (field.wireType) {
            0 -> add((field.value as ULong).toInt())
            2 -> { val packed = field.value as ByteArray; var cursor = 0
                while (cursor < packed.size) { val next = ProtoWire.readVarint(packed, cursor); add(next.value.toInt()); cursor = next.next } }
        } }
    }
    fun app(bytes: ByteArray): DecodedInstalledApp {
        val fields = ProtoWire.decodeFields(bytes)
        fun text(number: Int): String? = (fields.lastOrNull { it.number == number && it.wireType == 2 }?.value as? ByteArray)?.toString(Charsets.UTF_8)
        fun number(number: Int): Int? = (fields.lastOrNull { it.number == number && it.wireType == 0 }?.value as? ULong)?.toInt()
        return DecodedInstalledApp(text(1), text(2), text(3), number(4)?.let { it != 0 }, number(5), fields.count { it.number !in 1..5 })
    }
    fun remoteSettings(action: String, invocationId: String, uri: String? = null): ByteArray = DataMapCodec.encode(mapOf(
        "action" to DataMapValue.Text(action), "start_mode" to DataMapValue.IntNumber(0),
        NotificationProtocol.INVOCATION_ID to DataMapValue.Text(invocationId),
    ) + (uri?.let { mapOf("uri_data" to DataMapValue.Text(it)) } ?: emptyMap()))
}
