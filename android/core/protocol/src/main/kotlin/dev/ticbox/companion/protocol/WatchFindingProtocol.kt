package dev.ticbox.companion.protocol

import kotlinx.serialization.json.*

/** Our local companion extension, implemented by the watch APK; these are not Mobvoi opcodes. */
object WatchFindingProtocol {
    const val ACTION = "dev.ticbox.companion.action.FIND_WATCH"
    const val STATE_PATH = "/ticbox_finding/state"
    const val STOP_PATH = "/ticbox_finding/stop"
    const val REQUEST_ID = "request_id"
    const val SOURCE_NODE = "source_node"
    const val DURATION_MILLIS = "duration_ms"
    const val MAX_DURATION_MILLIS = 300_000L
    const val VERSION = 1
    private val json = Json { isLenient = true }

    /** The existing WCS remote-intent handler opens a visible, package-scoped watch Activity. */
    fun launch(packageName: String, requestId: String, sourceNode: String, durationMillis: Long): ByteArray =
        DataMapCodec.encode(mapOf(
            "action" to DataMapValue.Text(ACTION),
            "package_name" to DataMapValue.Text(packageName),
            "start_mode" to DataMapValue.IntNumber(0),
            NotificationProtocol.INVOCATION_ID to DataMapValue.Text(requestId),
            "extras" to DataMapValue.MapValue(mapOf(
                REQUEST_ID to DataMapValue.Text(requestId),
                SOURCE_NODE to DataMapValue.Text(sourceNode),
                DURATION_MILLIS to DataMapValue.LongNumber(durationMillis),
            )),
        ))

    fun stop(requestId: String): ByteArray = buildJsonObject {
        put("version", VERSION); put("requestId", requestId)
    }.toString().toByteArray(Charsets.UTF_8)

    fun state(requestId: String, state: String, audioPlaybackStarted: Boolean = false,
        vibrationRequested: Boolean = false, reason: String? = null): ByteArray = buildJsonObject {
        put("version", VERSION); put("requestId", requestId); put("state", state)
        put("audioPlaybackStarted", audioPlaybackStarted); put("vibrationRequested", vibrationRequested)
        reason?.let { put("reason", it) }
    }.toString().toByteArray(Charsets.UTF_8)

    fun parse(bytes: ByteArray): JsonObject {
        require(bytes.size <= ProtocolLimits.MAX_MESSAGE_BYTES) { "Finding message budget exceeded" }
        return json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
            ?: throw IllegalArgumentException("Finding message is not an object")
    }

    fun text(fields: JsonObject, key: String): String? = (fields[key] as? JsonPrimitive)?.contentOrNull
    fun flag(fields: JsonObject, key: String): Boolean? = (fields[key] as? JsonPrimitive)?.let {
        it.booleanOrNull ?: when (it.content.lowercase()) { "true", "1" -> true; "false", "0" -> false; else -> null }
    }
}
