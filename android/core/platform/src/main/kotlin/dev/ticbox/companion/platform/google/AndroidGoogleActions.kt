package dev.ticbox.companion.platform.google

import android.app.PendingIntent
import android.content.IntentSender
import dev.ticbox.companion.api.*
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AndroidGoogleLaunch(val actionId: String, val sender: IntentSender)

/** Runtime-owned PendingIntent registry. Claim/release/ack mirrors the Activity host lifecycle. */
class AndroidGoogleActions(private val diagnostics: DiagnosticSink = DiagnosticSink.NONE) : AutoCloseable {
    private class Entry(val owner: Any, val action: GoogleUserAction, val intent: PendingIntent,
        val failure: (String) -> Unit, var claimed: Boolean = false)
    private val lock = Any()
    private val entries = linkedMapOf<String, Entry>()
    private val mutableState = MutableStateFlow<List<GoogleUserAction>>(emptyList())
    val state = mutableState.asStateFlow()
    private var closed = false

    internal fun request(owner: Any, intent: PendingIntent, active: () -> Boolean, failure: (String) -> Unit) {
        val admitted = synchronized(lock) {
            if (closed || entries.size >= 8 || !active()) false else {
                val id = UUID.randomUUID().toString()
                val action = GoogleUserAction(id, GoogleUserActionKind.ACCOUNT_TRANSFER_AUTHORIZATION, "google_account_authorization_required")
                entries[id] = Entry(owner, action, intent, failure)
                publish(); true
            }
        }
        if (!admitted) failure("google_user_action_queue_unavailable")
    }

    /** Claim once from a resumed Activity; this descriptor contains the genuine Google sender. */
    fun takeLaunch(actionId: String): AndroidGoogleLaunch? = synchronized(lock) {
        val entry = entries[actionId] ?: return null
        if (entry.claimed || closed) return null
        entry.claimed = true
        AndroidGoogleLaunch(actionId, entry.intent.intentSender)
    }
    /** No launch happened; the same operation may be claimed again after the host resumes. */
    fun releaseLaunch(actionId: String): Boolean = synchronized(lock) {
        val entry = entries[actionId] ?: return false
        entry.claimed = false; true
    }
    /** Activity completion only acknowledges UI delivery. GMS completion decides transfer success. */
    fun activityResult(actionId: String, resultCode: Int): Boolean {
        val removed = synchronized(lock) { entries.remove(actionId)?.also { publish() } } ?: return false
        log("google_user_action_returned", mapOf("resultCode" to resultCode.toString(), "kind" to removed.action.kind.name))
        return true
    }
    fun launchFailed(actionId: String, problem: Throwable): Boolean {
        val removed = synchronized(lock) { entries.remove(actionId)?.also { publish() } } ?: return false
        log("google_user_action_failed", mapOf("exception" to problem.javaClass.simpleName))
        removed.failure("google_user_action_launch_failed")
        return true
    }
    internal fun cancel(owner: Any) = synchronized(lock) {
        entries.entries.removeAll { it.value.owner === owner }; publish()
    }
    override fun close() {
        val removed = synchronized(lock) {
            if (closed) return
            closed = true
            entries.values.toList().also { entries.clear(); publish() }
        }
        removed.forEach { it.failure("google_user_action_registry_closed") }
    }
    private fun publish() { mutableState.value = entries.values.map { it.action } }
    private fun log(event: String, details: Map<String, String>) {
        runCatching { diagnostics.record(ProtocolDiagnostic("google_ui", event, details = details)) }
    }
}
