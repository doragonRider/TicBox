@file:Suppress("DEPRECATION") // API 34/35 use MODE_BACKGROUND_ACTIVITY_START_ALLOWED.

package dev.ticbox.companion.platform.notification

import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Html
import dev.ticbox.companion.api.DiagnosticSink
import dev.ticbox.companion.api.ProtocolDiagnostic
import dev.ticbox.companion.protocol.NotificationReply
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class AndroidActionOutcome(val successful: Boolean, val reason: String? = null)

/** Ephemeral opaque IDs. No PendingIntent, notification text, or reply is written to disk/logs. */
internal class AndroidNotificationActions(private val context: Context, private val diagnostics: DiagnosticSink) {
    private data class Entry(
        val id: String,
        val key: String,
        val role: String,
        val pending: PendingIntent,
        val inputs: Array<RemoteInput>,
        val autoCancel: Boolean,
    )
    private val byId = linkedMapOf<String, Entry>()
    private val byKey = mutableMapOf<String, MutableMap<String, String>>()

    @Synchronized
    fun register(key: String, role: String, pending: PendingIntent, inputs: Array<RemoteInput>, autoCancel: Boolean): String {
        val slots = byKey.getOrPut(key) { mutableMapOf() }
        val previous = slots[role]?.let(byId::get)
        val id = if (previous?.pending == pending) previous.id else UUID.randomUUID().toString()
        previous?.let { byId.remove(it.id) }
        byId[id] = Entry(id, key, role, pending, inputs.copyOf(), autoCancel)
        slots[role] = id
        return id
    }

    @Synchronized
    fun retain(key: String, ids: Set<String>) {
        val slots = byKey[key] ?: return
        val iterator = slots.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value !in ids) { byId.remove(entry.value); iterator.remove() }
        }
        if (slots.isEmpty()) byKey.remove(key)
    }

    @Synchronized
    fun retainNotifications(keys: Set<String>) {
        val iterator = byKey.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in keys) { entry.value.values.forEach(byId::remove); iterator.remove() }
        }
    }

    @Synchronized
    fun clear() { byId.clear(); byKey.clear() }

    private fun metadata(event: String, values: Map<String, String> = emptyMap()) {
        runCatching { diagnostics.record(ProtocolDiagnostic("notifications", event, details = values)) }
    }

    suspend fun execute(id: String, replies: Map<String, NotificationReply>): AndroidActionOutcome {
        val entry = synchronized(this) { byId[id] } ?: return AndroidActionOutcome(false, "intent_unavailable")
        return try {
            // A retained token is useful only while its Android notification still exists. No
            // action_index/stream formatting check replaces the original opaque-ID lookup.
            if (NotificationListenerHub.active().none { it.key == entry.key }) return AndroidActionOutcome(false, "notification_removed")
            withContext(Dispatchers.Default) {
                val fillIn = Intent()
                val results = Bundle()
                var unmatched = 0
                for ((key, reply) in replies) {
                    val input = entry.inputs.firstOrNull { it.resultKey == key }
                    val value = reply.stringResult ?: reply.charSequenceHtml?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString() }
                    if (input != null && value != null) results.putCharSequence(key, value) else unmatched++
                }
                if (unmatched > 0) metadata("reply_fields_unavailable", mapOf("count" to unmatched.toString(), "accepted" to results.size().toString()))
                if (!results.isEmpty) {
                    if (entry.pending.isImmutable) {
                        metadata("reply_immutable_pending_intent")
                        return@withContext AndroidActionOutcome(false, "immutable_reply_intent")
                    }
                    RemoteInput.addResultsToIntent(entry.inputs, fillIn, results)
                    RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT)
                } else if (replies.isNotEmpty()) {
                    // Sending an empty fill-in could run a different action while pretending a
                    // reply was sent. The native endpoint did not expose any usable reply key.
                    return@withContext AndroidActionOutcome(false, "reply_input_unavailable")
                }
                val options = if (entry.pending.isActivity) ActivityOptions.makeBasic().apply {
                    // This send represents an explicit action from the user's associated watch.
                    // Android's CDM/BAL eligibility still governs whether an Activity can appear.
                    setPendingIntentBackgroundActivityStartMode(if (Build.VERSION.SDK_INT >= 36)
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }.toBundle() else null
                entry.pending.send(context, 0, fillIn, null, null, null, options)
                metadata("action_submitted", mapOf("replyKeys" to results.size().toString(), "activity" to entry.pending.isActivity.toString()))
                AndroidActionOutcome(true) // send accepted, not proof of app delivery/message receipt.
            }.also { result ->
                if (result.successful && entry.autoCancel) {
                    try { NotificationListenerHub.dismiss(entry.key) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (problem: Exception) { metadata("auto_cancel_unavailable", mapOf("exception" to problem.javaClass.simpleName)) }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) {
            metadata("action_native_failure", mapOf("exception" to problem.javaClass.simpleName))
            AndroidActionOutcome(false, when (problem) {
                is PendingIntent.CanceledException -> "intent_cancelled"
                is SecurityException -> "permission_denied"
                is NotificationListenerUnavailableException -> "listener_unavailable"
                else -> "native_failure"
            })
        }
    }
}
