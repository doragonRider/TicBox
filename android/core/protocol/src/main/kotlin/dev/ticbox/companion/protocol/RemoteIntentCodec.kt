package dev.ticbox.companion.protocol

/** The original DataMap survives decoding; unsupported optional fields do not reject the request. */
data class RemoteIntentRequest(
    val action: String?,
    val uri: String?,
    val packageName: String?,
    val mimeType: String?,
    val categories: List<String>,
    val extras: Map<String, DataMapValue>,
    val activityOptions: Map<String, DataMapValue>,
    val startMode: Int,
    val invocationId: String,
    val document: DataMapDocument,
    val notices: List<String>,
) {
    /** The official watch handler falls back to ACTIVITY for absent/unrecognized start modes. */
    val effectiveStartMode: Int get() = startMode.takeIf { it in 0..4 } ?: 0
    override fun toString() = "RemoteIntentRequest(startMode=$startMode, contents=<redacted>)"
}

object RemoteIntentCodec {
    const val CAPABILITY = "handle_remote_intent"

    fun decode(bytes: ByteArray): RemoteIntentRequest {
        val document = DataMapCodec.decode(bytes)
        val fields = document.values
        val notices = document.notices.toMutableList()
        fun text(key: String) = typed<DataMapValue.Text>(fields, key, notices)?.value
        fun map(key: String) = typed<DataMapValue.MapValue>(fields, key, notices)?.value.orEmpty()
        val mode = typed<DataMapValue.IntNumber>(fields, "start_mode", notices)?.value ?: 0
        if (mode !in 0..4) notices += "unknown_start_mode"
        val categories = when (val value = fields["categories"]) {
            is DataMapValue.Strings -> value.value
            // Retain a usable string list from alternate DataMap writers.
            is DataMapValue.ListValue -> value.value.filterIsInstance<DataMapValue.Text>().map { it.value }
                .also { notices += "categories_list" }
            null, DataMapValue.Null -> emptyList()
            else -> { notices += "field_type:categories"; emptyList() }
        }
        if (fields.keys.any { it !in KNOWN_FIELDS }) notices += "unknown_fields"
        return RemoteIntentRequest(text("action"), text("uri_data"), text("package_name"), text("mime_type"),
            categories, map("extras"), map("activity_options"), mode,
            text(NotificationProtocol.INVOCATION_ID).orEmpty(), document, notices)
    }

    /** Same RpcWithCallback result writer used by stock remote-intent and bridged actions. */
    fun result(invocationId: String, successful: Boolean): ByteArray =
        NotificationRpcCodec.encodeResult(invocationId, successful)

    private inline fun <reified T : DataMapValue> typed(fields: Map<String, DataMapValue>, key: String,
        notices: MutableList<String>): T? {
        val value = fields[key] ?: return null
        if (value !is T && value != DataMapValue.Null) notices += "field_type:$key"
        return value as? T
    }

    private val KNOWN_FIELDS = setOf("action", "uri_data", "package_name", "mime_type", "categories", "extras",
        "activity_options", "start_mode", NotificationProtocol.INVOCATION_ID)
}
