package dev.ticbox.companion.protocol

import java.security.MessageDigest

/** Ordinary MessageApi payloads. This codec neither dispatches actions nor invents success. */
object NotificationRpcCodec {
    fun encodeRequest(request: NotificationActionRequest, limits: NotificationCodecLimits = NotificationCodecLimits()): ByteArray {
        val fields = request.fields.toMutableMap()
        request.command?.let { fields["rpc_command"] = DataMapValue.Text(it) }
        request.intentId?.let { fields["intent_id"] = DataMapValue.Text(it) }
        request.invocationId?.let { fields[NotificationProtocol.INVOCATION_ID] = DataMapValue.Text(it) }
        request.remoteStreamItemId?.let { fields["remote_stream_item_id"] = DataMapValue.Text(it) }
        request.actionIndex?.let { fields["action_index"] = DataMapValue.IntNumber(it) }
        request.isWearableAction?.let { fields["is_wearable_action"] = DataMapValue.Bool(it) }
        if (request.replies.isNotEmpty()) fields["remote_input_results"] = DataMapValue.MapValue(request.replies.mapValues { (_, reply) ->
            val values = reply.fields.toMutableMap()
            reply.stringResult?.let { values["string_result"] = DataMapValue.Text(it) }
            reply.charSequenceHtml?.let { values["char_sequence_html"] = DataMapValue.Text(it) }
            DataMapValue.MapValue(values)
        })
        return NotificationDataMapWire.encode(fields, limits)
    }

    fun decodeRequest(bytes: ByteArray, limits: NotificationCodecLimits = NotificationCodecLimits()): NotificationActionRequest {
        val decoded = NotificationCodec.decode(bytes, limits)
        val fields = decoded.fields
        val notices = decoded.notices.toMutableList()
        fun text(key: String): String? = text(fields, key, notices)
        val command = text("rpc_command")
        if (command != NotificationProtocol.BRIDGED_ACTION) notices += NotificationNotice("unsupported_rpc_command", "rpc_command")
        val replies = typed<DataMapValue.MapValue>(fields, "remote_input_results", notices)?.value.orEmpty().mapNotNull { (key, value) ->
            val reply = (value as? DataMapValue.MapValue)?.value
            if (reply == null) {
                notices += NotificationNotice("reply_value_type", "remote_input_results")
                null
            } else key to NotificationReply(text(reply, "string_result", notices), text(reply, "char_sequence_html", notices), reply)
        }.toMap()
        return NotificationActionRequest(
            intentId = text("intent_id"), invocationId = text(NotificationProtocol.INVOCATION_ID),
            remoteStreamItemId = text("remote_stream_item_id"),
            actionIndex = typed<DataMapValue.IntNumber>(fields, "action_index", notices)?.value,
            isWearableAction = typed<DataMapValue.Bool>(fields, "is_wearable_action", notices)?.value,
            replies = replies, command = command, fields = fields, notices = notices.toList(),
            metadata = metadata(decoded, notices) + mapOf("command_supported" to (command == NotificationProtocol.BRIDGED_ACTION).toString()) +
                (command?.let { mapOf("command_chars" to it.length.toString(), "command_sha256" to digest(it)) } ?: emptyMap()),
        )
    }

    /** Call only after the action executor supplies its actual result; the observed writer fixes messageId to 1. */
    fun encodeResult(invocationId: String, successful: Boolean, messageId: Int = 1, limits: NotificationCodecLimits = NotificationCodecLimits()): ByteArray =
        NotificationDataMapWire.encode(mapOf(
            NotificationProtocol.INVOCATION_ID to DataMapValue.Text(invocationId),
            NotificationProtocol.RESULT to DataMapValue.MapValue(mapOf(
                NotificationProtocol.SUCCESSFUL to DataMapValue.Bool(successful),
                NotificationProtocol.MESSAGE_ID to DataMapValue.IntNumber(messageId),
            )),
        ), limits)

    fun decodeResult(bytes: ByteArray, limits: NotificationCodecLimits = NotificationCodecLimits()): NotificationActionResult {
        val decoded = NotificationCodec.decode(bytes, limits)
        val notices = decoded.notices.toMutableList()
        val result = typed<DataMapValue.MapValue>(decoded.fields, NotificationProtocol.RESULT, notices)?.value.orEmpty()
        val invocationId = text(decoded.fields, NotificationProtocol.INVOCATION_ID, notices)
        val successful = typed<DataMapValue.Bool>(result, NotificationProtocol.SUCCESSFUL, notices)?.value
        val messageId = typed<DataMapValue.IntNumber>(result, NotificationProtocol.MESSAGE_ID, notices)?.value
        return NotificationActionResult(invocationId, successful, messageId, decoded.fields, result, notices.toList(), metadata(decoded, notices))
    }

    private fun text(fields: Map<String, DataMapValue>, key: String, notices: MutableList<NotificationNotice>): String? =
        typed<DataMapValue.Text>(fields, key, notices)?.value
    private inline fun <reified T : DataMapValue> typed(fields: Map<String, DataMapValue>, key: String, notices: MutableList<NotificationNotice>): T? {
        val value = fields[key] ?: return null
        if (value !is T) notices += NotificationNotice("field_value_type", key)
        return value as? T
    }
    private fun metadata(decoded: NotificationDecodedContent, notices: List<NotificationNotice>): Map<String, String> = mapOf(
        "field_count" to decoded.fields.size.toString(), "wire_unknown_count" to decoded.wireUnknownFields.size.toString(),
        "notice_count" to notices.size.toString(),
    )
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
