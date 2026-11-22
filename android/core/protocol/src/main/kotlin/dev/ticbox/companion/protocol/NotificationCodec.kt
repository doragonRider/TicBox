package dev.ticbox.companion.protocol

/** DataMap payload and separate Asset slots for one companion-route stream DataItem. */
object NotificationCodec {
    fun encode(content: NotificationContent, limits: NotificationCodecLimits = NotificationCodecLimits()): NotificationEncodedContent {
        val assets = mutableListOf<NotificationEncodedAsset>()
        val notices = mutableListOf<NotificationNotice>()
        var assetBytes = 0L
        var entryCount = 0
        fun flatten(content: NotificationContent, path: String, depth: Int): Map<String, DataMapValue> {
            protocolCheck(depth <= limits.maxDepth, "Notification nesting budget exceeded")
            val keys = (content.fields.keys + content.assets.keys + content.maps.keys + content.lists.keys).toSortedSet()
            protocolCheck(keys.size <= limits.maxFields - entryCount, "Notification field budget exceeded")
            entryCount += keys.size
            return keys.associateWith { key ->
                val fieldPath = "$path/$key"
                if (listOf(content.fields, content.assets, content.maps, content.lists).count { key in it } > 1) {
                    notices += NotificationNotice("explicit_content_replaces_field", fieldPath)
                }
                when {
                    key in content.assets -> {
                        protocolCheck(assets.size < limits.maxAssets, "Notification Asset count budget exceeded")
                        val asset = content.assets.getValue(key)
                        val size = asset.sizeBytes ?: 0
                        protocolCheck(size <= limits.maxAssetBytes, "Notification Asset size budget exceeded")
                        protocolCheck(size.toLong() <= limits.maxTotalAssetBytes - assetBytes, "Notification total Asset size budget exceeded")
                        assetBytes += size
                        val index = assets.size.toLong()
                        assets += NotificationEncodedAsset(index, fieldPath, asset)
                        DataMapValue.AssetIndex(index)
                    }
                    key in content.maps -> DataMapValue.MapValue(flatten(content.maps.getValue(key), fieldPath, depth + 1))
                    key in content.lists -> DataMapValue.ListValue(content.lists.getValue(key).mapIndexed { index, nested ->
                        DataMapValue.MapValue(flatten(nested, "$fieldPath/$index", depth + 2))
                    })
                    else -> content.fields.getValue(key)
                }
            }
        }
        val fields = flatten(content, "", 0)
        val encoded = NotificationDataMapWire.encode(fields, limits)
        return NotificationEncodedContent(encoded, assets.toList(), assetReferences(fields, limits), notices.toList())
    }

    fun decode(bytes: ByteArray, limits: NotificationCodecLimits = NotificationCodecLimits()): NotificationDecodedContent {
        val decoded = NotificationDataMapWire.decode(bytes, limits)
        return NotificationDecodedContent(decoded.document, assetReferences(decoded.document.values, limits), decoded.notices,
            bytes, decoded.unknownFields)
    }

    /** Convenience for new plain text; all other keys remain caller-controlled in NotificationContent. */
    fun textFields(appName: String, title: String, text: String, whenMillis: Long, postTimeMillis: Long, revision: Long): Map<String, DataMapValue> = mapOf(
        "app_name" to DataMapValue.Text(appName),
        "title_raw" to DataMapValue.Text(title), "title_html" to DataMapValue.Text(NotificationProtocol.escapeHtml(title)),
        "text_raw" to DataMapValue.Text(text), "text_html" to DataMapValue.Text(NotificationProtocol.escapeHtml(text)),
        "when" to DataMapValue.LongNumber(whenMillis), "post_time_millis" to DataMapValue.LongNumber(postTimeMillis),
        "revision" to DataMapValue.LongNumber(revision), "show_when" to DataMapValue.Bool(true),
    )

    fun actionFields(title: String, intentId: String, allowGeneratedReplies: Boolean = false): Map<String, DataMapValue> = mapOf(
        "title_html" to DataMapValue.Text(NotificationProtocol.escapeHtml(title)),
        "intent_id" to DataMapValue.Text(intentId), "allow_generated_replies" to DataMapValue.Bool(allowGeneratedReplies),
    )

    fun remoteInputFields(returnKey: String, label: String?, choices: List<String> = emptyList(), allowFreeFormInput: Boolean = true): Map<String, DataMapValue> = buildMap {
        put("return_key", DataMapValue.Text(returnKey))
        label?.let { put("label_html", DataMapValue.Text(NotificationProtocol.escapeHtml(it))) }
        if (choices.isNotEmpty()) put("choices_html", DataMapValue.Strings(choices.map(NotificationProtocol::escapeHtml)))
        put("allowFreeFormInput", DataMapValue.Bool(allowFreeFormInput))
    }

    private fun assetReferences(fields: Map<String, DataMapValue>, limits: NotificationCodecLimits): List<NotificationAssetReference> {
        val references = mutableListOf<NotificationAssetReference>()
        fun visit(value: DataMapValue, path: String, depth: Int) {
            protocolCheck(depth <= limits.maxDepth, "Notification nesting budget exceeded")
            when (value) {
                is DataMapValue.AssetIndex -> {
                    protocolCheck(references.size < limits.maxFields, "Notification Asset reference budget exceeded")
                    references += NotificationAssetReference(value.value, value.value.toString(), path)
                }
                is DataMapValue.MapValue -> value.value.toSortedMap().forEach { (key, nested) -> visit(nested, "$path/$key", depth + 1) }
                is DataMapValue.ListValue -> value.value.forEachIndexed { index, nested -> visit(nested, "$path/$index", depth + 1) }
                else -> Unit
            }
        }
        fields.toSortedMap().forEach { (key, value) -> visit(value, "/$key", 0) }
        return references.toList()
    }
}
