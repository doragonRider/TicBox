package dev.ticbox.companion.engine.module

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

data class HealthModuleLink(val peer: PeerAddress, val sessionId: Long)
data class HealthModulePayload(val catalog: MagiskModuleProtocol.Catalog, val open: () -> InputStream)
data class HealthModuleReply(val target: ToolboxTarget, val value: JsonObject)
fun interface HealthModuleRemote {
    suspend fun exchange(link: HealthModuleLink, payload: HealthModulePayload, install: Boolean,
        progress: (ToolboxProgress) -> Unit, submitted: () -> Unit): HealthModuleReply
}

/** Explicit operations only. A physical link change invalidates observations, including old replies. */
class HealthModuleEngine(
    private val payload: suspend () -> HealthModulePayload,
    private val remote: HealthModuleRemote,
) : HealthModuleClient {
    private class Active(val link: HealthModuleLink)
    private val gate = Any()
    private val commands = Mutex()
    private val mutable = MutableStateFlow(HealthModuleState())
    override val state = mutable.asStateFlow()
    @Volatile private var active: Active? = null
    @Volatile private var closed = false
    fun attach(link: HealthModuleLink?) = synchronized(gate) {
        if (!closed && active?.link != link) {
            active = link?.let(::Active)
            mutable.value = HealthModuleState(phase = if (link == null) HealthModulePhase.DISCONNECTED else HealthModulePhase.READY, bundle = state.value.bundle)
        }
    }
    private fun bundle(c: MagiskModuleProtocol.Catalog) = HealthModuleBundle(c.versionName, c.versionCode, c.buildId, c.sha256, c.bytes)
    override suspend fun bundled(): HealthModuleBundle {
        val result = bundle(payload().catalog)
        synchronized(gate) { if (!closed) mutable.value = state.value.copy(bundle = result) }
        return result
    }
    override suspend fun refresh() = execute(false) {}
    override suspend fun installBundled(progress: (ToolboxProgress) -> Unit) = execute(true, progress)
    private suspend fun execute(install: Boolean, progress: (ToolboxProgress) -> Unit): HealthModuleResult {
        val selected = active
        if (closed || selected == null) return HealthModuleResult(HealthModuleOutcome.REJECTED, if (closed) "module_closed" else "companion_link_required")
        return commands.withLock {
            requireCurrent(selected)
            requireModuleTarget(selected.link)
            val submitted = AtomicBoolean()
            update(selected) { it.copy(phase = if (install) HealthModulePhase.INSTALLING else HealthModulePhase.READING, current = false) }
            try {
                val artifact = payload()
                requireCurrent(selected)
                update(selected) { it.copy(bundle = bundle(artifact.catalog)) }
                val reply = remote.exchange(selected.link, artifact, install, progress) { submitted.set(true) }
                requireCurrent(selected)
                require(reply.target.kind == ToolboxBackendKind.ROOT_BRIDGE)
                val value = reply.value["snapshot"]?.jsonObject ?: error("module_snapshot_missing")
                val outcome = HealthModuleOutcome.valueOf(reply.value.moduleText("outcome"))
                require(if (install) outcome in setOf(HealthModuleOutcome.STAGED_REBOOT_REQUIRED, HealthModuleOutcome.UP_TO_DATE) else outcome == HealthModuleOutcome.READ)
                fun image(key: String): HealthModuleImage? = (value[key] as? JsonObject)?.let {
                    HealthModuleImage(it.text("buildId"), it.text("versionName"), it.number("versionCode"), it.flag("matchesBundled") == true,
                        it.flag("disabled") == true, it.flag("removalPending") == true)
                }
                val last = value["lastInstall"] as? JsonObject
                val snapshot = HealthModuleSnapshot(reply.target, value.number("magiskVersionCode")?.toInt(), value.moduleNumber("sdk").toInt(),
                    value.moduleText("abi"), image("installed"), image("staged"), value.text("healthRuntimeBuildId"), value.text("runtimeIssue"),
                    last?.text("buildId"), last?.text("outcome")?.let { runCatching { HealthModuleOutcome.valueOf(it) }.getOrNull() })
                val result = HealthModuleResult(outcome)
                update(selected) { it.copy(phase = HealthModulePhase.READY, snapshot = snapshot, current = true, lastOperation = result) }
                result
            } catch (cancelled: CancellationException) {
                val result = HealthModuleResult(if (submitted.get()) HealthModuleOutcome.UNCONFIRMED else HealthModuleOutcome.REJECTED,
                    if (submitted.get()) "module_install_unconfirmed" else "module_operation_interrupted")
                update(selected) { it.copy(phase = HealthModulePhase.READY, current = false, lastOperation = result) }
                if (cancelled is TimeoutCancellationException && currentCoroutineContext().isActive) result else throw cancelled
            } catch (problem: Exception) {
                val reason = (problem as? ToolboxException)?.code ?: "module_operation_failed"
                val refused = reason in setOf("module_root_required", "module_bridge_update_required", "module_device_unsupported", "magisk_unavailable", "magisk_too_old",
                    "module_disabled_or_removing", "module_id_conflict", "module_busy", "module_payload_invalid", "magisk_install_failed", "invalid_request", "module_unsafe_path",
                    // Staged, but a restart would load what was turned off, so nothing may follow it.
                    "module_alerts_off_not_kept")
                val result = HealthModuleResult(if (submitted.get() && !refused) HealthModuleOutcome.UNCONFIRMED else HealthModuleOutcome.REJECTED, reason)
                update(selected) { it.copy(phase = HealthModulePhase.UNAVAILABLE, current = false, lastOperation = result) }
                result
            }
        }
    }
    private fun requireCurrent(selected: Active) { if (closed || active !== selected) throw CancellationException("module_link_changed") }
    private fun update(selected: Active, change: (HealthModuleState) -> HealthModuleState) = synchronized(gate) {
        if (!closed && active === selected) mutable.value = change(state.value)
    }
    override fun close() = synchronized(gate) { closed = true; active = null; mutable.value = state.value.copy(phase = HealthModulePhase.CLOSED, current = false) }
}
