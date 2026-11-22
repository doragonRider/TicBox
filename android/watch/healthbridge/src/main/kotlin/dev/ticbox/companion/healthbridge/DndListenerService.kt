package dev.ticbox.companion.healthbridge

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import androidx.core.net.toUri
import android.os.CancellationSignal
import android.service.notification.Condition
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.ticbox.companion.protocol.*
import kotlinx.serialization.json.*

/** Own rule only. Never uses setInterruptionFilter(ALL), which would clear somebody else's DND. */
class DndListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != ToolboxProtocol.DND_PATH) return
        val request = runCatching { ToolboxProtocol.json.parseToJsonElement(event.data.toString(Charsets.UTF_8)).jsonObject }.getOrNull() ?: return
        val id = request.text("requestId")?.takeIf { it.length in 1..128 } ?: return
        val revision = request.number("revision")?.takeIf { it > 0 } ?: return
        val active = request.flag("active") ?: return
        val response = synchronized(LOCK) {
            buildJsonObject {
                put("requestId", id); put("revision", revision)
                try { put("active", apply(event.sourceNodeId, revision, active)) }
                catch (error: Exception) { put("error", (error as? DndFailure)?.reason ?: "dnd_apply_failed") }
            }
        }
        Wearable.getMessageClient(this).sendMessage(event.sourceNodeId, ToolboxProtocol.DND_RESULT, response.toString().toByteArray(Charsets.UTF_8))
    }
    @Suppress("DEPRECATION")
    @SuppressLint("UseKtx") // Check synchronous commit failures before acknowledging or losing rule ownership.
    internal fun apply(controller: String, revision: Long, active: Boolean): Boolean {
        val prefs = getSharedPreferences("dnd-follow-rule", Context.MODE_PRIVATE)
        val revisionKey = "revision:$controller"
        if (revision < prefs.getLong(revisionKey, 0)) throw DndFailure("dnd_superseded")
        // Reserve the version before slow Magisk authorization; no older command may overtake it.
        check(prefs.edit().putLong(revisionKey, revision).commit())
        val manager = getSystemService(NotificationManager::class.java)
        var ruleId = prefs.getString("ruleId", null)
        if (!active && ruleId == null) return false
        if (!manager.isNotificationPolicyAccessGranted) {
            val result = RootProcess.run("cmd notification allow_dnd ${shellQuote(packageName)}", CancellationSignal(), 60_000)
            if (result.exitCode != 0 || result.timedOut || !manager.isNotificationPolicyAccessGranted) throw DndFailure("notification_policy_access_required")
        }
        var rule = ruleId?.let { manager.getAutomaticZenRule(it) }
        if (rule == null) {
            if (!active) { check(prefs.edit().remove("ruleId").commit()); return false }
            rule = AutomaticZenRule("Phone Do Not Disturb", null, ComponentName(this, BridgeSetupActivity::class.java),
                CONDITION, null, NotificationManager.INTERRUPTION_FILTER_PRIORITY, true)
            ruleId = manager.addAutomaticZenRule(rule) ?: throw DndFailure("dnd_rule_creation_failed")
            check(prefs.edit().putString("ruleId", ruleId).commit())
        }
        rule.isEnabled = active
        if (!manager.updateAutomaticZenRule(ruleId!!, rule)) throw DndFailure("dnd_rule_update_failed")
        manager.setAutomaticZenRuleState(ruleId, Condition(CONDITION, "Follow phone Do Not Disturb", if (active) Condition.STATE_TRUE else Condition.STATE_FALSE))
        // Zen recomputation is asynchronous in NotificationManagerService. Allow it to settle
        // before acknowledging activation; OFF verifies only our rule and leaves manual DND alone.
        repeat(21) { attempt ->
            val observed = manager.getAutomaticZenRule(ruleId)?.isEnabled ?: false
            if (observed == active && (!active || manager.currentInterruptionFilter in setOf(
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY, NotificationManager.INTERRUPTION_FILTER_ALARMS,
                    NotificationManager.INTERRUPTION_FILTER_NONE))) return observed
            if (attempt < 20) Thread.sleep(100)
        }
        throw DndFailure("watch_dnd_mismatch")
    }
    private class DndFailure(val reason: String) : Exception(reason)
    companion object { private val LOCK = Any(); private val CONDITION = "condition://dev.ticbox.companion/phone-dnd".toUri() }
}
