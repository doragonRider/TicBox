package dev.ticbox.companion.protocol

import java.io.ByteArrayOutputStream

object NotificationProtocol {
    const val STREAM_PREFIX = "/bridger/stream_item:"
    const val RPC_PATH = "/bridger/rpc"
    const val RESULT_PATH = "/bridger/result"
    const val BRIDGED_ACTION = "bridged_action"
    const val INVOCATION_ID = "com.google.android.clockwork.actions.RpcWithCallback.invocation_id"
    const val RESULT = "com.google.android.clockwork.actions.RpcWithCallback.result"
    const val SUCCESSFUL = "com.google.android.clockwork.actions.RpcWithCallback.successful"
    const val MESSAGE_ID = "com.google.android.clockwork.actions.RpcWithCallback.message_id"
    const val DISMISSAL_PREFIX = "/bridge_mode/dismissal/"
    const val BRIDGE_MODE_PREFIX = "/bridge_mode/bridge_mode/"
    val OFFICIAL_COMPANION_APP_KEY = DataAppKey("com.mobvoi.companion.at", "5cc9866db03e530a21f842a421c7eb42bac58961")

    fun streamItemPath(id: NotificationStreamId): String = buildString {
        append(STREAM_PREFIX); append(id.creatorNodeId); append(':'); append(id.packageName)
        append(':'); append(id.notificationId); append(':'); append(safeEncode(id.tag ?: "null_tag"))
        id.notificationKey?.let { append(':'); append(safeEncode(it)) }
    }
    fun streamItemPath(creatorNodeId: String, packageName: String, notificationId: Int, tag: String?, notificationKey: String): String =
        streamItemPath(NotificationStreamId(creatorNodeId, packageName, notificationId, tag, notificationKey))

    /** Metadata parse failure returns null; it does not prevent callers retaining the original path. */
    fun parseStreamItemPath(pathOrUri: String, limits: NotificationCodecLimits = NotificationCodecLimits()): NotificationStreamId? {
        protocolCheck(pathOrUri.length <= limits.maxPathChars, "Notification path size budget exceeded")
        val path = if (pathOrUri.startsWith("wear://")) {
            val start = pathOrUri.indexOf('/', 7)
            if (start < 0) return null else pathOrUri.substring(start)
        } else pathOrUri
        val parts = path.split(':', limit = 6)
        if (parts.size < 5 || parts[0] != "/bridger/stream_item") return null
        val id = parts[3].toIntOrNull() ?: return null
        return NotificationStreamId(parts[1], parts[2], id,
            if (parts[4] == "null_tag") null else safeDecode(parts[4]), parts.getOrNull(5)?.let(::safeDecode))
    }

    /** Android Uri.encode's allowed ASCII set, followed by its observed '~'/'%' substitutions. */
    fun safeEncode(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val n = byte.toInt() and 255
            if ((n in 65..90 || n in 97..122 || n in 48..57 || n.toChar() in "_-.!'()*")) append(n.toChar())
            else { append('~'); append(HEX[n ushr 4]); append(HEX[n and 15]) }
        }
    }
    /** '+' stays literal. Malformed escapes/UTF-8 are retained/replaced best effort. */
    fun safeDecode(value: String): String {
        val source = value.replace('~', '%')
        val bytes = ByteArrayOutputStream()
        var at = 0
        while (at < source.length) {
            if (source[at] == '%' && at + 2 < source.length) {
                val high = source[at + 1].digitToIntOrNull(16)
                val low = source[at + 2].digitToIntOrNull(16)
                if (high != null && low != null) { bytes.write((high shl 4) or low); at += 3; continue }
            }
            val end = if (source[at].isHighSurrogate() && at + 1 < source.length && source[at + 1].isLowSurrogate()) at + 2 else at + 1
            bytes.write(source.substring(at, end).toByteArray(Charsets.UTF_8)); at = end
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    fun escapeHtml(text: String): String = buildString {
        text.forEach { when (it) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
            '"' -> append("&quot;"); '\'' -> append("&#39;"); '\n' -> append("<br>")
            else -> append(it)
        } }
    }
    private const val HEX = "0123456789ABCDEF"
}
