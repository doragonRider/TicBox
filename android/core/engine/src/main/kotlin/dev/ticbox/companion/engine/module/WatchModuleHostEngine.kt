package dev.ticbox.companion.engine.module

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.HealthMonitoringProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fixed commands around the module. A write is never repeated: whatever becomes of it, the next step
 * is a read, and a physical link change discards everything.
 */
class WatchModuleHostEngine(private val shell: ModuleShell) : WatchModuleHostClient {
    private class Active(val link: HealthModuleLink)
    private val gate = Any()
    private val commands = Mutex()
    private val mutable = MutableStateFlow(WatchModuleHostState())
    override val state = mutable.asStateFlow()
    @Volatile private var active: Active? = null
    @Volatile private var closed = false
    fun attach(link: HealthModuleLink?) = synchronized(gate) {
        if (!closed && active?.link != link) {
            active = link?.let(::Active)
            mutable.value = WatchModuleHostState(if (link == null) WatchModuleHostPhase.DISCONNECTED else WatchModuleHostPhase.READY)
        }
    }
    override suspend fun refresh() = run(null)
    override suspend fun turnOnZygisk() = run(TURN_ON_ZYGISK)
    override suspend fun stayAwake() {
        val selected = active ?: return
        commands.withLock {
            requireCurrent(selected)
            requireModuleTarget(selected.link)
            attempt { shell.execute(selected.link, STAY_AWAKE, SHORT_TIMEOUT_MILLIS) }
        }
    }
    override suspend fun restart() {
        val selected = active ?: return
        commands.withLock {
            requireCurrent(selected)
            requireModuleTarget(selected.link)
            update(selected) { it.copy(reason = null) }
            // Losing the link here may well be the restart, so only a refusal counts as a failure.
            val result = attempt { shell.execute(selected.link, RESTART, SHORT_TIMEOUT_MILLIS) }
            if (result != null && !result.successful) update(selected) { it.copy(reason = "module_restart_failed") }
        }
    }
    override suspend fun restartSystemUi() {
        val selected = active ?: return
        commands.withLock {
            requireCurrent(selected)
            requireModuleTarget(selected.link)
            update(selected) { it.copy(reason = null) }
            // A lost reply says nothing about whether it ran, which the next read shows anyway.
            val result = attempt { shell.execute(selected.link, RESTART_UI, SHORT_TIMEOUT_MILLIS) }
            if (result != null && !result.successful) update(selected) { it.copy(reason = "module_ui_restart_failed") }
        }
    }
    private suspend fun <T> attempt(block: suspend () -> T): T? = try { block() } catch (cancelled: CancellationException) {
        if (cancelled is TimeoutCancellationException) null else throw cancelled
    } catch (_: Exception) { null }
    private suspend fun run(write: String?) {
        val selected = active
        if (closed) return
        if (selected == null) {
            synchronized(gate) { mutable.value = state.value.copy(reason = "companion_link_required") }
            return
        }
        commands.withLock {
            if (closed || active !== selected) return
            requireModuleTarget(selected.link)
            update(selected) { it.copy(phase = WatchModuleHostPhase.BUSY, current = false, reason = null) }
            var problem: String? = null
            try {
                if (write != null) {
                    val result = attempt { shell.execute(selected.link, write, TIMEOUT_MILLIS) }
                    if (result?.successful != true) problem = "module_zygisk_failed"
                }
                val read = shell.execute(selected.link, READ, TIMEOUT_MILLIS)
                if (!read.successful) throw ToolboxException("module_host_unreadable")
                val snapshot = parseModuleHostStatus(read.stdout.decodeToString())
                update(selected) { WatchModuleHostState(WatchModuleHostPhase.READY, snapshot, true, problem) }
            } catch (timeout: TimeoutCancellationException) {
                update(selected) { it.copy(phase = WatchModuleHostPhase.UNAVAILABLE, reason = problem ?: "timeout") }
            } catch (failure: Exception) {
                if (failure is CancellationException) {
                    update(selected) { it.copy(phase = WatchModuleHostPhase.READY, reason = problem) }
                    throw failure
                }
                val reason = problem ?: (failure as? ToolboxException)?.code ?: "module_host_unreadable"
                update(selected) { it.copy(phase = WatchModuleHostPhase.UNAVAILABLE, reason = reason) }
            }
        }
    }
    private fun requireCurrent(selected: Active) { if (closed || active !== selected) throw CancellationException("module_link_changed") }
    private fun update(selected: Active, change: (WatchModuleHostState) -> WatchModuleHostState) = synchronized(gate) {
        if (!closed && active === selected) mutable.value = change(state.value)
    }
    override fun close() = synchronized(gate) { closed = true; active = null; mutable.value = state.value.copy(phase = WatchModuleHostPhase.CLOSED, current = false) }
    private companion object {
        const val TIMEOUT_MILLIS = 30_000L
        const val SHORT_TIMEOUT_MILLIS = 15_000L
        const val SYSTEM_UI = "com.mobvoi.wear.refsysui"
        const val HEALTH = HealthMonitoringProtocol.PACKAGE
        const val VERSION = "grep -m1 -o 'versionCode=[0-9]*'"
        // The kernel's own named lock, with a timeout so that a lost phone can't leave it held.
        const val LOCK_SETUP = "{ echo 'ticbox_module 300000000000' > /sys/power/wake_lock; } 2>/dev/null; "
        // Reading the module's status right after this hashes a large APK with no traffic to wake it.
        const val LOCK_READ = "{ echo 'ticbox_module 30000000000' > /sys/power/wake_lock; } 2>/dev/null; "
        const val STAY_AWAKE = LOCK_SETUP + "exit 0"
        const val READ = LOCK_READ +
            // Magisk has no row until the setting first changes, so the query fills that in, and
            // only Magisk saying nothing at all reads as not knowing.
            "z=\$(magisk --sqlite \"SELECT COALESCE((SELECT value FROM settings WHERE key='zygisk'),0) AS value\" 2>/dev/null) || z=unknown; " +
            "echo \"zygisk \${z:-unknown}\"; " +
            "echo \"deny \$(magisk --denylist ls 2>/dev/null | grep -c '^$HEALTH|')\"; echo \"health_pid \$(pidof $HEALTH)\"; " +
            "echo \"health_version \$(dumpsys package $HEALTH | $VERSION)\"; echo \"sysui_version \$(dumpsys package $SYSTEM_UI | $VERSION)\"; " +
            "echo \"sdk \$(getprop ro.build.version.sdk)\"; exit 0"
        const val TURN_ON_ZYGISK = "magisk --sqlite \"REPLACE INTO settings (key,value) VALUES('zygisk',1)\""
        // Detached so that this answers before the watch goes down, and the framework's normal
        // shutdown runs. The reboot binary is only for a framework that won't take the request.
        const val RESTART = LOCK_READ + "{ sleep 1; svc power reboot || reboot; } >/dev/null 2>&1 &"
        // Without a lit screen it seems not to draw, and its 20 s or so of starting can outlast the
        // screen's timeout, so this keeps waking it for about that long. Waking an awake watch does
        // nothing. Detached so that this answers straight away.
        const val RESTART_UI = LOCK_READ + "p=\$(pidof $SYSTEM_UI); if [ -n \"\$p\" ]; then kill \$p || exit 4; fi; " +
            "{ for i in 1 2 3 4 5 6; do input keyevent KEYCODE_WAKEUP; sleep 5; done; } >/dev/null 2>&1 &"
    }
}

private const val SUPPORTED_SYSTEM_UI_VERSION = 150L
private val SUPPORTED_SYSTEM_UI_SDKS = 33L..34L

internal fun parseModuleHostStatus(stdout: String): WatchModuleHostSnapshot {
    val lines = stdout.lineSequence().map(String::trim).filter(String::isNotEmpty).associate { it.substringBefore(' ') to it.substringAfter(' ', "") }
    val zygisk = lines["zygisk"] ?: throw ToolboxException("module_host_unreadable")
    fun version(key: String) = lines[key]?.substringAfter('=', "")?.toLongOrNull()
    val healthVersion = version("health_version")
    val systemUiVersion = version("sysui_version")
    val sdk = lines["sdk"]?.toLongOrNull()
    return WatchModuleHostSnapshot(
        zygiskOn = when { zygisk == "value=1" -> true; zygisk.startsWith("value=") -> false; else -> null },
        healthOnDenyList = (lines["deny"]?.toIntOrNull() ?: 0) > 0,
        healthSupported = healthVersion?.let { it in HealthMonitoringProtocol.ANALYZED_VERSIONS },
        healthRunning = !lines["health_pid"].isNullOrBlank(),
        systemUiSupported = if (systemUiVersion == null || sdk == null) null else systemUiVersion == SUPPORTED_SYSTEM_UI_VERSION && sdk in SUPPORTED_SYSTEM_UI_SDKS,
    )
}
