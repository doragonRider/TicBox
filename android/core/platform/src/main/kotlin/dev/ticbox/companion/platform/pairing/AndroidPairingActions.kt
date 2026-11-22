package dev.ticbox.companion.platform.pairing

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import dev.ticbox.companion.api.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Descriptors for :app ActivityResultContracts. The backend never stores an Activity. */
sealed interface AndroidPairingLaunch {
    val actionId: String
    data class Permissions(override val actionId: String, val permissions: List<String>) : AndroidPairingLaunch
    data class Sender(override val actionId: String, val sender: IntentSender) : AndroidPairingLaunch
    data class ActivityIntent(override val actionId: String, val intent: Intent) : AndroidPairingLaunch
}

internal sealed interface PairingHostResult {
    data class ActivityResult(val code: Int, val data: Intent?) : PairingHostResult
    data object PermissionsReturned : PairingHostResult
}

/** Process-local, one-result token registry. Configuration changes do not invalidate its entries. */
class AndroidPairingActions(private val diagnostics: DiagnosticSink = DiagnosticSink.NONE) : AutoCloseable {
    /** Distinguishes a retained service registry from a replacement after process/service death. */
    val instanceId: String = UUID.randomUUID().toString()
    private data class Entry(val action: PairingUserAction, val launch: AndroidPairingLaunch,
        val answer: CompletableDeferred<PairingHostResult> = CompletableDeferred(), var claimed: Boolean = false)
    private val lock = Any()
    private val entries = linkedMapOf<String, Entry>()
    private val mutableState = MutableStateFlow<List<PairingUserAction>>(emptyList())
    val state: StateFlow<List<PairingUserAction>> = mutableState.asStateFlow()
    private var closed = false

    /** Claims a pending launch once. Keep actionId beside the app's ActivityResult launcher state. */
    fun prepare(actionId: String): AndroidPairingLaunch? = synchronized(lock) {
        val entry = entries[actionId] ?: return null
        if (closed || entry.claimed) return null
        entry.claimed = true
        entry.launch
    }

    /** Host had no resumed Activity and did not launch anything; it can try the same token again. */
    fun releaseLaunch(actionId: String): Boolean = synchronized(lock) {
        val entry = entries[actionId] ?: return false
        entry.claimed = false
        true
    }

    fun completeActivityResult(actionId: String, resultCode: Int, data: Intent?): Boolean = synchronized(lock) {
        val entry = entries[actionId] ?: return false
        if (entry.launch is AndroidPairingLaunch.Permissions) return false
        val accepted = entry.answer.complete(PairingHostResult.ActivityResult(resultCode, data))
        if (accepted) { entries.remove(actionId); publish() }
        accepted
    }

    /** The platform checks current Android grants after this notification; it never trusts UI booleans. */
    fun completePermissionsResult(actionId: String): Boolean = synchronized(lock) {
        val entry = entries[actionId] ?: return false
        if (entry.launch !is AndroidPairingLaunch.Permissions) return false
        val accepted = entry.answer.complete(PairingHostResult.PermissionsReturned)
        if (accepted) { entries.remove(actionId); publish() }
        accepted
    }

    fun failLaunch(actionId: String, problem: Exception): Boolean = synchronized(lock) {
        val entry = entries.remove(actionId) ?: return false
        publish()
        entry.answer.completeExceptionally(PairingWorkflowException(PairingFailure(PairingFailureKind.PLATFORM_UNAVAILABLE, "system_ui_launch_${problem.javaClass.simpleName}")))
    }

    /** Optional bridge for a host that already uses the native onActivityResult callbacks. */
    @Suppress("DEPRECATION")
    fun launch(actionId: String, activity: Activity, requestCode: Int): Boolean {
        val descriptor = prepare(actionId) ?: return false
        return try {
            when (descriptor) {
                is AndroidPairingLaunch.Permissions -> activity.requestPermissions(descriptor.permissions.toTypedArray(), requestCode)
                is AndroidPairingLaunch.Sender -> activity.startIntentSenderForResult(descriptor.sender, requestCode, null, 0, 0, 0)
                is AndroidPairingLaunch.ActivityIntent -> activity.startActivityForResult(descriptor.intent, requestCode)
            }
            true
        } catch (problem: Exception) { failLaunch(actionId, problem); false }
    }

    internal suspend fun permissions(operationId: Long, permissions: List<String>): PairingHostResult = request(operationId,
        PairingActionKind.BLUETOOTH_PERMISSIONS, "bluetooth_permissions_required", permissions) { id -> AndroidPairingLaunch.Permissions(id, permissions.toList()) }
    internal suspend fun activity(operationId: Long, kind: PairingActionKind, reason: String, intent: Intent): PairingHostResult =
        request(operationId, kind, reason) { id -> AndroidPairingLaunch.ActivityIntent(id, intent) }
    internal suspend fun sender(operationId: Long, kind: PairingActionKind, reason: String, sender: IntentSender): PairingHostResult =
        request(operationId, kind, reason) { id -> AndroidPairingLaunch.Sender(id, sender) }

    internal fun cancellation(operationId: Long, sender: IntentSender) {
        synchronized(lock) {
            if (closed || entries.values.any { it.action.operationId == operationId && it.action.kind == PairingActionKind.CANCEL_ASSOCIATION }) return
            add(operationId, PairingActionKind.CANCEL_ASSOCIATION, "cancel_system_association", emptyList()) { AndroidPairingLaunch.Sender(it, sender) }
        }
    }

    /** A new association does not race an older chooser's queued cancel IntentSender. */
    internal suspend fun awaitCancellations() {
        val pending = synchronized(lock) { entries.values.filter { it.action.kind == PairingActionKind.CANCEL_ASSOCIATION }.map { it.answer } }
        for (answer in pending) answer.await()
    }

    internal fun cancel(operationId: Long) {
        synchronized(lock) {
            val cancelled = entries.values.filter { it.action.operationId == operationId && it.action.kind != PairingActionKind.CANCEL_ASSOCIATION }
            cancelled.forEach { entries.remove(it.action.id); it.answer.cancel(CancellationException("pairing_action_cancelled")) }
            publish()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            val pending = entries.values.toList()
            entries.clear()
            publish()
            pending.forEach { it.answer.cancel(CancellationException("pairing_actions_closed")) }
        }
    }

    private suspend fun request(operationId: Long, kind: PairingActionKind, reason: String, permissions: List<String> = emptyList(), launch: (String) -> AndroidPairingLaunch): PairingHostResult {
        val entry = synchronized(lock) { check(!closed); add(operationId, kind, reason, permissions, launch) }
        return try { entry.answer.await() } finally { synchronized(lock) { entries.remove(entry.action.id); publish() } }
    }
    private fun add(operationId: Long, kind: PairingActionKind, reason: String, permissions: List<String>, launch: (String) -> AndroidPairingLaunch): Entry {
        val id = UUID.randomUUID().toString()
        val action = PairingUserAction(id, operationId, kind, permissions.toList(), reason)
        return Entry(action, launch(id)).also {
            entries[id] = it
            publish()
            runCatching { diagnostics.record(ProtocolDiagnostic("pairing_ui", "action_required", DiagnosticSeverity.INFO,
                details = mapOf("operationId" to operationId.toString(), "actionId" to id, "kind" to kind.name))) }
        }
    }
    private fun publish() { mutableState.value = entries.values.map { it.action } }
}
