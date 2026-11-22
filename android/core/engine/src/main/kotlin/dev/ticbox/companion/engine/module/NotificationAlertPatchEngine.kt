package dev.ticbox.companion.engine.module

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Runs one fixed command as root on the current watch, over Local Bridge. */
fun interface ModuleShell {
    suspend fun execute(link: HealthModuleLink, command: String, timeoutMillis: Long): ToolboxCommandResult
}

/**
 * Fixed commands from docs/spec/notification-alerts.md. A write is never repeated: whatever
 * becomes of it, the next step is a read, and a physical link change discards everything.
 */
class NotificationAlertPatchEngine(private val remote: ModuleShell) : NotificationAlertPatchClient {
    private class Active(val link: HealthModuleLink)
    private val gate = Any()
    private val commands = Mutex()
    private val mutable = MutableStateFlow(NotificationAlertPatchState())
    override val state = mutable.asStateFlow()
    @Volatile private var active: Active? = null
    @Volatile private var closed = false
    fun attach(link: HealthModuleLink?) = synchronized(gate) {
        if (!closed && active?.link != link) {
            active = link?.let(::Active)
            mutable.value = NotificationAlertPatchState(if (link == null) NotificationAlertPatchPhase.DISCONNECTED else NotificationAlertPatchPhase.READY)
        }
    }
    override suspend fun refresh() = run(null, ::noWriteProblem)
    override suspend fun optIn() = run(OPT_IN) { if (it.exitCode == 3) "notification_alert_module_missing" else exitProblem(it) }
    override suspend fun optOut() = run(OPT_OUT, ::optOutProblem)
    override suspend fun keepOff() = run(KEEP_OFF, ::exitProblem)
    override suspend fun applyToAll() = run(APPLY, ::modeProblem)
    private suspend fun run(write: String?, problemIn: (ToolboxCommandResult) -> String?) {
        val selected = active
        if (closed) return
        if (selected == null) {
            synchronized(gate) { mutable.value = state.value.copy(reason = "companion_link_required") }
            return
        }
        commands.withLock {
            if (closed || active !== selected) return
            requireModuleTarget(selected.link)
            update(selected) { it.copy(phase = NotificationAlertPatchPhase.BUSY, current = false, reason = null) }
            var problem: String? = null
            try {
                if (write != null) problem = try {
                    problemIn(remote.execute(selected.link, write, TIMEOUT_MILLIS))
                } catch (timeout: TimeoutCancellationException) {
                    "notification_alert_unconfirmed"
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    (failure as? ToolboxException)?.code ?: "notification_alert_unconfirmed"
                }
                val read = remote.execute(selected.link, STATUS, TIMEOUT_MILLIS)
                if (!read.successful) throw ToolboxException("notification_alert_status_unreadable")
                val snapshot = parseNotificationAlertStatus(read.stdout.decodeToString())
                update(selected) { NotificationAlertPatchState(NotificationAlertPatchPhase.READY, snapshot, true, problem) }
            } catch (timeout: TimeoutCancellationException) {
                update(selected) { it.copy(phase = NotificationAlertPatchPhase.UNAVAILABLE, reason = problem ?: "timeout") }
            } catch (failure: Exception) {
                if (failure is CancellationException) {
                    update(selected) { it.copy(phase = NotificationAlertPatchPhase.READY, reason = problem) }
                    throw failure
                }
                val reason = problem ?: (failure as? ToolboxException)?.code ?: "notification_alert_status_unreadable"
                update(selected) { it.copy(phase = NotificationAlertPatchPhase.UNAVAILABLE, reason = reason) }
            }
        }
    }
    private fun update(selected: Active, change: (NotificationAlertPatchState) -> NotificationAlertPatchState) = synchronized(gate) {
        if (!closed && active === selected) mutable.value = change(state.value)
    }
    override fun close() = synchronized(gate) { closed = true; active = null; mutable.value = state.value.copy(phase = NotificationAlertPatchPhase.CLOSED, current = false) }
    private companion object {
        const val TIMEOUT_MILLIS = 30_000L
        const val MODULE = "/data/adb/modules/ticwatch_health_settings"
        const val DEX = "$MODULE/module.dex"
        const val STAGED = "/data/adb/modules_update/ticwatch_health_settings"
        const val OFF = "notification-alerts.disabled"
        /** What 1.0.7 and earlier loaded the extension for; an older module still installed reads it. */
        const val LEGACY_ON = "notification-alerts.enabled"
        const val CONTROL = "CLASSPATH=$DEX /system/bin/app_process / dev.ticbox.companion.healthmodule.NotificationAlertsControl"
        // A staged copy replaces the active directory at the next boot, so only its marker counts. Anything under the
        // marker's name counts, as the native loader has it.
        const val PICK = "if [ -d $STAGED ]; then d=$STAGED; else d=$MODULE; fi; "
        // On unless turned off. The directory's age against the uptime tells whether this boot has seen it as it is now:
        // an install creates it and turning back on removes the marker, and both change it.
        const val STATUS = PICK + "if [ -e \"\$d/$OFF\" ] || [ -L \"\$d/$OFF\" ]; then echo 'alerts off'; " +
            "else t=\$(stat -c %Y \"\$d\" 2>/dev/null); if [ -n \"\$t\" ]; then echo \"alerts on \$(( \$(date +%s) - t )) \$(cut -d. -f1 /proc/uptime)\"; " +
            "else echo 'alerts on'; fi; fi; [ -f $DEX ] && $CONTROL status 2>/dev/null; exit 0"
        // Both copies, so whichever the next boot keeps loads it.
        const val OPT_IN = "[ -f $STAGED/module.dex ] || [ -f $DEX ] || exit 3; rm -f $MODULE/$OFF $STAGED/$OFF"
        // Into each copy there is, so the next boot finds it whichever it keeps; exit 3 when there's no copy to keep it in.
        const val MARK_OFF = "notification_mark_exit=3; for d in $MODULE $STAGED; do [ -d \"\$d\" ] || continue; " +
            "[ \$notification_mark_exit = 3 ] && notification_mark_exit=0; " +
            "if [ ! -e \"\$d/$OFF\" ] && [ ! -L \"\$d/$OFF\" ]; then touch \"\$d/$OFF\" && chmod 600 \"\$d/$OFF\" || notification_mark_exit=1; fi; " +
            "rm -f \"\$d/$LEGACY_ON\" || notification_mark_exit=1; done; "
        /** Only the record, for a copy that a restart is about to load: whatever runs now goes with that restart. */
        const val KEEP_OFF = MARK_OFF + "exit \$notification_mark_exit"
        // Turning off the loaded extension and cancelling its next load are independent. The
        // receipt preserves both outcomes even when OFF fails or the control socket is absent.
        const val OPT_OUT = "if [ -f $DEX ]; then $CONTROL OFF 2>/dev/null; notification_off_exit=\$?; else notification_off_exit=3; fi; " +
            MARK_OFF + "printf '\\noptout %s %s\\n' \"\$notification_off_exit\" \"\$notification_mark_exit\"; " +
            "if [ \"\$notification_mark_exit\" -ne 0 ]; then exit \"\$notification_mark_exit\"; fi; exit \"\$notification_off_exit\""
        const val APPLY = "$CONTROL ALL 2>/dev/null"
        fun noWriteProblem(@Suppress("UNUSED_PARAMETER") result: ToolboxCommandResult): String? = null
        fun exitProblem(result: ToolboxCommandResult): String? = when {
            result.timedOut -> "notification_alert_unconfirmed"
            result.exitCode != 0 -> "notification_alert_write_failed"
            else -> null
        }
        fun optOutProblem(result: ToolboxCommandResult): String? {
            if (result.timedOut || result.truncated) return "notification_alert_unconfirmed"
            val stdout = result.stdout.decodeToString()
            val receipt = stdout.lineSequence().map(String::trim).filter { it.startsWith("optout ") }.singleOrNull()
                ?.let { Regex("optout ([0-9]{1,3}) ([0-9]{1,3})").matchEntire(it) }
                ?: return "notification_alert_unconfirmed"
            val offExit = receipt.groupValues[1].toInt()
            val removeExit = receipt.groupValues[2].toInt()
            if (offExit !in 0..255 || removeExit !in 0..255 ||
                result.exitCode != (if (removeExit != 0) removeExit else offExit)) return "notification_alert_unconfirmed"
            if (removeExit != 0) return "notification_alert_write_failed"
            val status = statusJson(stdout)
            status?.text("error")?.let { return it }
            if (offExit != 0) return "notification_alert_control_unavailable"
            return "notification_alert_unconfirmed".takeIf { status?.text("mode") != "OFF" }
        }
        fun modeProblem(result: ToolboxCommandResult): String? =
            if (result.timedOut) "notification_alert_unconfirmed"
            else statusJson(result.stdout.decodeToString())?.text("error")
                ?: "notification_alert_control_unavailable".takeIf { result.exitCode != 0 }
    }
}

private fun statusJson(stdout: String): JsonObject? = stdout.lineSequence().map(String::trim).firstOrNull { it.startsWith("{") }
    ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }

/** The first line says whether it was turned off; a JSON line means the control client verified SystemUI's identity. */
internal fun parseNotificationAlertStatus(stdout: String): NotificationAlertPatchSnapshot {
    val marker = stdout.lineSequence().map(String::trim).firstOrNull { it.startsWith("alerts ") }?.split(' ')
        ?: throw ToolboxException("notification_alert_status_unreadable")
    val optedIn = when (marker.getOrNull(1)) { "on" -> true; "off" -> false; else -> throw ToolboxException("notification_alert_status_unreadable") }
    val age = marker.getOrNull(2)?.toLongOrNull()
    val uptime = marker.getOrNull(3)?.toLongOrNull()
    val status = statusJson(stdout)
    return NotificationAlertPatchSnapshot(
        optedIn = optedIn,
        optInSurvivedReboot = optedIn && age != null && uptime != null && age > uptime,
        running = status != null,
        hooked = status?.flag("installed") == true,
        mode = status?.text("mode")?.let { name -> NotificationAlertPatchMode.entries.firstOrNull { it.name == name } },
        issue = status?.text("issue"),
    )
}
