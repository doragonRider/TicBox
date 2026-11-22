@file:Suppress("DEPRECATION")

package dev.ticbox.companion.platform.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Process
import android.service.notification.StatusBarNotification
import android.service.notification.NotificationListenerService
import android.text.Html
import android.text.Spanned
import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.notification.PhoneNotification
import dev.ticbox.companion.protocol.*
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

/** Produces the observed public DataMap fields; native Parcelables never go over the link. */
internal class AndroidNotificationMapper(
    private val context: Context,
    private val actions: AndroidNotificationActions,
    private val diagnostics: DiagnosticSink,
) {
    /** Shared across a snapshot so a notification flood cannot allocate an unbounded asset set. */
    class ImageBudget(var remaining: Int = 32 * 1024 * 1024, var remainingSlots: Int = Int.MAX_VALUE)

    fun map(sbn: StatusBarNotification, images: ImageBudget, ranking: NotificationListenerService.Ranking? = null): PhoneNotification {
        val notification = sbn.notification
        val usedActions = mutableSetOf<String>()
        val perNotification = ImageBudget(min(images.remaining, 8 * 1024 * 1024), 64)
        val initialBytes = perNotification.remaining
        var content = content(notification, sbn, "root", usedActions, perNotification, TextBudget(), 0)
        images.remaining -= initialBytes - perNotification.remaining
        // On modern Android, the channel/ranking controls alerts; the old defaults field alone
        // is commonly empty. Preserve the observed wire types consumed by the watch reader.
        val channel = ranking?.channel
        val audible = ranking?.matchesInterruptionFilter() != false && (channel?.importance ?: NotificationManager.IMPORTANCE_DEFAULT) >= NotificationManager.IMPORTANCE_DEFAULT
        val hasSound = audible && (if (channel != null) channel.sound != null else notification.sound != null)
        val vibration = if (!audible) null else if (channel != null) {
            if (channel.shouldVibrate()) channel.vibrationPattern else null
        } else notification.vibrate
        val defaults = if (!audible) 0 else if (channel != null) {
            (if (hasSound) Notification.DEFAULT_SOUND else 0) or
                (if (channel.shouldVibrate()) Notification.DEFAULT_VIBRATE else 0)
        } else notification.defaults
        content = content.copy(fields = content.fields + mapOf("defaults" to DataMapValue.IntNumber(defaults),
            "has_sound" to DataMapValue.Bool(hasSound)) +
            (vibration?.takeIf { it.isNotEmpty() }?.let { mapOf("vibrate" to DataMapValue.Longs(it.take(128))) } ?: emptyMap()))
        actions.retain(sbn.key, usedActions)
        return PhoneNotification(sbn.key, sbn.packageName, sbn.id, sbn.tag, sbn.postTime,
            localOnly = ranking?.importance == NotificationManager.IMPORTANCE_NONE || notification.flags and Notification.FLAG_LOCAL_ONLY != 0 ||
                (sbn.packageName == context.packageName && notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0),
            content = content)
    }

    private fun content(
        notification: Notification,
        sbn: StatusBarNotification,
        role: String,
        usedActions: MutableSet<String>,
        images: ImageBudget,
        text: TextBudget,
        depth: Int,
    ): NotificationContent {
        val fields = linkedMapOf<String, DataMapValue>()
        val assets = linkedMapOf<String, NotificationAsset>()
        val lists = linkedMapOf<String, List<NotificationContent>>()
        val extras = notification.extras ?: Bundle.EMPTY
        fun string(key: String, value: String?) { value?.let { fields[key] = DataMapValue.Text(text.take(it)) } }
        fun bool(key: String, value: Boolean) { fields[key] = DataMapValue.Bool(value) }
        fun number(key: String, value: Int) { fields[key] = DataMapValue.IntNumber(value) }
        fun plain(key: String, value: CharSequence?) {
            value ?: return
            val raw = text.take(value.toString())
            val styled = value is Spanned && raw.length == value.length
            val html = if (styled)
                Html.toHtml(value, Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE) else NotificationProtocol.escapeHtml(raw)
            val styleWithinBudget = html.length <= raw.length * 8L + 128
            if (!styled || !styleWithinBudget) fields["${key}_raw"] = DataMapValue.Text(raw)
            fields["${key}_html"] = DataMapValue.Text(if (styleWithinBudget) html else {
                metadata("text_style_budget", emptyMap()); NotificationProtocol.escapeHtml(raw)
            })
        }
        string("app_name", bestEffort("app_label") {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } ?: sbn.packageName)
        plain("title", bestEffort("title") { extras.getCharSequence(Notification.EXTRA_TITLE) })
        plain("text", bestEffort("text") { extras.getCharSequence(Notification.EXTRA_TEXT) })
        plain("sub_text", bestEffort("sub_text") { extras.getCharSequence(Notification.EXTRA_SUB_TEXT) })
        plain("summary", bestEffort("summary") { extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT) })
        plain("big_title", bestEffort("big_title") { extras.getCharSequence(Notification.EXTRA_TITLE_BIG) })
        plain("big_text", bestEffort("big_text") { extras.getCharSequence(Notification.EXTRA_BIG_TEXT) })
        bool("show_when", bestEffort("show_when") { extras.getBoolean(Notification.EXTRA_SHOW_WHEN, false) } ?: false)
        fields["when"] = DataMapValue.LongNumber(notification.`when`)
        fields["post_time_millis"] = DataMapValue.LongNumber(sbn.postTime)
        number("notification_flags", notification.flags)
        number("defaults", notification.defaults)
        number("color", notification.color)
        number("user_id", sbn.userId)
        number("group_alert_behavior", notification.groupAlertBehavior)
        number("priority", notification.priority)
        bool("only_alert_once", notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        bool("is_group_summary", notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        bool("is_work_profile", sbn.user != Process.myUserHandle())
        string("group", notification.group)
        string("group_key", sbn.groupKey)
        string("category", notification.category)
        string("sort_key", notification.sortKey)
        val contentIntent = notification.contentIntent
        bool("has_content_intent", contentIntent != null)
        contentIntent?.let {
            val id = actions.register(sbn.key, "$role/content", it, emptyArray(),
                notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
            usedActions += id
            string("content_intent_id", id)
        }
        bestEffort("small_icon") {
            val drawable = notification.smallIcon?.loadDrawable(context)
            bool("is_small_icon_vector_drawable", drawable?.javaClass?.simpleName == "VectorDrawable")
            drawable?.let { image(it, 128, images) }?.let { assets["icon"] = it }
        }
        if (depth == 0) bestEffort("app_icon") {
            image(context.packageManager.getApplicationIcon(sbn.packageName), 128, images)?.let { assets["app_icon"] = it }
        }
        bestEffort("large_icon") { notification.getLargeIcon()?.loadDrawable(context)?.let { image(it, 256, images) }
            ?.let { assets["large_icon"] = it } }
        bestEffort("big_picture") {
            val icon = extras.getParcelable(Notification.EXTRA_PICTURE_ICON, Icon::class.java)
            val bitmap = extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
            val picture = icon?.loadDrawable(context)?.let { image(it, 768, images) } ?: bitmap?.let { image(it, 768, images) }
            picture?.let { assets["big_picture"] = it }
        }
        bestEffort("inbox_lines") { extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.let { lines ->
            listsOmitted("inbox_lines", lines.size, MAX_MESSAGES)
            fields["inbox_lines"] = DataMapValue.Strings(lines.take(MAX_MESSAGES).map { NotificationProtocol.escapeHtml(text.take(it.toString())) })
        } }
        bestEffort("progress") {
            if (extras.containsKey(Notification.EXTRA_PROGRESS)) {
                val maximum = extras.getInt(Notification.EXTRA_PROGRESS_MAX)
                fields["progress"] = DataMapValue.FloatNumber(if (maximum > 0) extras.getInt(Notification.EXTRA_PROGRESS).toFloat() / maximum else 0f)
                fields["progressIndeterminate"] = DataMapValue.Bool(extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
            }
        }
        bestEffort("chronometer") {
            bool("uses_chronometer", extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
            bool("chronometer_count_down", extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        }
        notification.actions?.let { nativeActions ->
            listsOmitted("actions", nativeActions.size, MAX_ACTIONS)
            lists["actions"] = nativeActions.take(MAX_ACTIONS).mapIndexedNotNull { index, action ->
                bestEffort("action") { action(action, sbn.key, "$role/action/$index", usedActions, text) }
            }
        }
        bestEffort("wearable") {
            val extender = Notification.WearableExtender(notification)
            string("bridge_tag", extender.bridgeTag)
            listsOmitted("wearable_actions", extender.actions.size, MAX_ACTIONS)
            lists["wearable_actions"] = extender.actions.take(MAX_ACTIONS).mapIndexedNotNull { index, action ->
                bestEffort("wearable_action") { action(action, sbn.key, "$role/wearable/$index", usedActions, text) }
            }
            if (depth < MAX_PAGE_DEPTH) {
                listsOmitted("pages", extender.pages.size, MAX_PAGES)
                lists["pages"] = extender.pages.take(MAX_PAGES).mapIndexedNotNull { index, page ->
                    if (!text.node()) null else bestEffort("page") { content(page, sbn, "$role/page/$index", usedActions, images, text, depth + 1) }
                }
            } else if (extender.pages.isNotEmpty()) metadata("page_depth_budget", mapOf("depth" to depth.toString()))
        }
        bestEffort("messages") {
            val source = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (source != null) {
                val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(source)
                listsOmitted("messages", messages.size, MAX_MESSAGES)
                lists["messages"] = messages.takeLast(MAX_MESSAGES).mapNotNull { message ->
                    if (!text.node()) return@mapNotNull null
                    val values = linkedMapOf<String, DataMapValue>("message_timestamp" to DataMapValue.LongNumber(message.timestamp))
                    fun pair(key: String, value: CharSequence?) { value?.let {
                        val raw = text.take(it.toString())
                        values["${key}_raw"] = DataMapValue.Text(raw)
                        values[key] = DataMapValue.Text(NotificationProtocol.escapeHtml(raw))
                    } }
                    pair("message_text", message.text)
                    pair("message_sender", message.senderPerson?.name ?: message.sender)
                    message.senderPerson?.key?.let { values["message_sender_key"] = DataMapValue.Text(text.take(it)) }
                    val messageAssets = linkedMapOf<String, NotificationAsset>()
                    bestEffort("sender_avatar") { message.senderPerson?.icon?.loadDrawable(context)?.let { image(it, 128, images) }
                        ?.let { messageAssets["sender_avatar"] = it } }
                    bestEffort("message_attachment") {
                        val uri = message.dataUri
                        // Resolve Android-granted local content only. Never fetch http(s) media.
                        if (uri != null && uri.scheme in setOf("content", "android.resource") && message.dataMimeType != null) {
                            val remaining = min(MAX_IMAGE_BYTES, images.remaining)
                            if (remaining > 0 && images.remainingSlots > 0) context.contentResolver.openInputStream(uri)?.use { input ->
                                val bytes = input.readNBytes(remaining + 1)
                                if (bytes.size <= remaining) {
                                    images.remaining -= bytes.size
                                    images.remainingSlots--
                                    values["message_data"] = DataMapValue.Text(digest(bytes))
                                    values["message_data_mime_type"] = DataMapValue.Text(message.dataMimeType)
                                    messageAssets["message_image"] = NotificationAsset(bytes, mimeType = message.dataMimeType)
                                } else metadata("attachment_byte_budget", mapOf("limit" to remaining.toString()))
                            }
                        } else if (uri != null) metadata("attachment_not_local", emptyMap())
                    }
                    NotificationContent(fields = values, assets = messageAssets)
                }
            }
            val user = extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON, android.app.Person::class.java)
            val displayName = user?.name ?: extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)
            displayName?.let {
                val raw = text.take(it.toString())
                fields["display_name_raw"] = DataMapValue.Text(raw)
                fields["display_name"] = DataMapValue.Text(NotificationProtocol.escapeHtml(raw))
            }
            user?.key?.let { string("device_user_key", it) }
            user?.icon?.loadDrawable(context)?.let { image(it, 128, images) }?.let { assets["device_user_avatar"] = it }
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.let {
                val raw = text.take(it.toString())
                fields["conversation_title_raw"] = DataMapValue.Text(raw)
                fields["conversation_title"] = DataMapValue.Text(NotificationProtocol.escapeHtml(raw))
            }
            bool("is_group_conversation", extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION))
        }
        if (text.omitted > 0) metadata("text_byte_budget", mapOf("omittedCodeUnits" to text.omitted.toString()))
        if (depth == 0 && text.omittedNodes > 0) metadata("projection_node_budget", mapOf("omitted" to text.omittedNodes.toString()))
        return NotificationContent(fields, assets, lists = lists)
    }

    private fun action(action: Notification.Action, key: String, role: String, used: MutableSet<String>, text: TextBudget): NotificationContent? {
        if (!text.node()) return null
        val pending = action.actionIntent ?: return null
        val inputs = action.remoteInputs ?: emptyArray()
        val id = actions.register(key, role, pending, inputs, false)
        used += id
        val values = linkedMapOf<String, DataMapValue>(
            "intent_id" to DataMapValue.Text(id),
            "title_html" to DataMapValue.Text(NotificationProtocol.escapeHtml(text.take(action.title?.toString().orEmpty()))),
            "allow_generated_replies" to DataMapValue.Bool(action.allowGeneratedReplies),
            "action_inline" to DataMapValue.Bool(Notification.Action.WearableExtender(action).hintDisplayActionInline),
            "remote_content_intent" to DataMapValue.Bool(action.extras.getBoolean("com.google.android.wearable.preview.extra.REMOTE_CONTENT_INTENT")),
        )
        listsOmitted("remote_inputs", inputs.size, MAX_ACTIONS)
        val remoteInputs = inputs.take(MAX_ACTIONS).mapNotNull { input ->
            if (!text.node()) return@mapNotNull null
            val remoteFields = linkedMapOf<String, DataMapValue>(
                "return_key" to DataMapValue.Text(input.resultKey),
                "allowFreeFormInput" to DataMapValue.Bool(input.allowFreeFormInput))
            input.label?.let { remoteFields["label_html"] = DataMapValue.Text(NotificationProtocol.escapeHtml(text.take(it.toString()))) }
            input.choices?.let { choices -> remoteFields["choices_html"] = DataMapValue.Strings(choices.take(MAX_MESSAGES).map {
                NotificationProtocol.escapeHtml(text.take(it.toString())) }) }
            NotificationContent(remoteFields)
        }
        return NotificationContent(values, lists = mapOf("remote_inputs" to remoteInputs))
    }

    private fun image(drawable: Drawable, dimension: Int, budget: ImageBudget): NotificationAsset? {
        if (budget.remaining <= 0 || budget.remainingSlots <= 0) { metadata("image_snapshot_budget", emptyMap()); return null }
        val originalWidth = max(1, drawable.intrinsicWidth)
        val originalHeight = max(1, drawable.intrinsicHeight)
        val scale = min(1.0, dimension.toDouble() / max(originalWidth, originalHeight))
        val bitmap = Bitmap.createBitmap(max(1, (originalWidth * scale).toInt()), max(1, (originalHeight * scale).toInt()), Bitmap.Config.ARGB_8888)
        try {
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
            return compress(bitmap, budget)
        } finally { bitmap.recycle() }
    }

    private fun image(source: Bitmap, dimension: Int, budget: ImageBudget): NotificationAsset? {
        val scale = min(1.0, dimension.toDouble() / max(source.width, source.height))
        val bitmap = if (scale < 1.0) Bitmap.createScaledBitmap(source, max(1, (source.width * scale).toInt()), max(1, (source.height * scale).toInt()), true) else source
        try { return compress(bitmap, budget) } finally { if (bitmap !== source) bitmap.recycle() }
    }

    private fun compress(bitmap: Bitmap, budget: ImageBudget): NotificationAsset? {
        val remaining = min(MAX_IMAGE_BYTES, budget.remaining)
        if (remaining <= 0 || budget.remainingSlots <= 0) { metadata("image_snapshot_budget", emptyMap()); return null }
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, off: Int, len: Int) {
                if (size().toLong() + len > remaining) throw ImageBudgetException()
                super.write(bytes, off, len)
            }
            override fun write(value: Int) {
                if (size() >= remaining) throw ImageBudgetException()
                super.write(value)
            }
        }
        if (!bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, output)) return null
        val bytes = output.toByteArray()
        budget.remaining -= bytes.size
        budget.remainingSlots--
        return NotificationAsset(bytes, mimeType = "image/webp")
    }

    private inline fun <T> bestEffort(field: String, action: () -> T): T? = try { action() }
    catch (problem: Exception) {
        metadata("native_field_unavailable", mapOf("field" to field, "exception" to problem.javaClass.simpleName)); null
    }

    private fun listsOmitted(field: String, size: Int, limit: Int) {
        if (size > limit) metadata("list_budget", mapOf("field" to field, "omitted" to (size - limit).toString()))
    }
    private fun metadata(event: String, values: Map<String, String>) {
        runCatching { diagnostics.record(ProtocolDiagnostic("notifications", event, details = values)) }
    }
    private class TextBudget(var remaining: Int = 32_000, var omitted: Int = 0, var remainingNodes: Int = 256, var omittedNodes: Int = 0) {
        fun node(): Boolean = if (remainingNodes > 0) { remainingNodes--; true } else { omittedNodes++; false }
        fun take(value: String): String {
            var count = min(value.length, min(remaining, 16_384))
            if (count > 0 && count < value.length && value[count - 1].isHighSurrogate()) count--
            omitted += value.length - count
            remaining -= count
            return value.take(count)
        }
    }
    private class ImageBudgetException : IllegalStateException()
    companion object {
        private const val MAX_ACTIONS = 64
        private const val MAX_MESSAGES = 100
        private const val MAX_PAGES = 16
        private const val MAX_PAGE_DEPTH = 2
        private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
