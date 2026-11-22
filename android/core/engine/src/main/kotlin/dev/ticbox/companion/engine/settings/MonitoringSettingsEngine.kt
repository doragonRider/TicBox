package dev.ticbox.companion.engine.settings

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

data class MonitoringLink(val peer: PeerAddress, val sessionId: Long)
fun interface MonitoringRemote {
    /** One root operation against the supplied physical watch link; never retries a written command. */
    suspend fun exchange(link: MonitoringLink, request: JsonObject): JsonObject
}

class MonitoringSettingsEngine(
    parentScope: CoroutineScope,
    private val remote: MonitoringRemote,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
) : MonitoringSettingsClient {
    private class Active(val link: MonitoringLink)
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val worker = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val commands = Mutex()
    private val lifecycle = Any()
    private val operations = ConcurrentHashMap.newKeySet<Deferred<MonitoringCommandResult>>()
    private val mutable = MutableStateFlow(MonitoringSettingsState())
    override val state = mutable.asStateFlow()
    @Volatile private var active: Active? = null
    @Volatile private var closed = false

    /** Attaching never prompts for root or silently changes health preferences. */
    fun attach(link: MonitoringLink?) {
        val obsolete = synchronized(lifecycle) {
            if (closed || active?.link == link) return
            active = link?.let(::Active)
            mutable.value = MonitoringSettingsState(sessionId = link?.sessionId ?: 0, peer = link?.peer,
                phase = if (link == null) MonitoringPhase.DISCONNECTED else MonitoringPhase.READY)
            operations.toList()
        }
        obsolete.forEach { it.cancel() }
    }

    override suspend fun refresh() = execute(null, null)
    override suspend fun set(id: MonitoringSettingId, value: MonitoringValue): MonitoringCommandResult {
        val spec = HealthMonitoringProtocol.spec(id.name)
        val raw = MonitoringSettingsCodec.raw(value)
        if (MonitoringSettingsCodec.kind(value) != spec.kind || !spec.accepts(raw))
            return MonitoringCommandResult(MonitoringCommandStatus.INVALID_ARGUMENT, id, "invalid_monitoring_value")
        return execute(id, raw)
    }
    private suspend fun execute(id: MonitoringSettingId?, value: String?): MonitoringCommandResult {
        if (closed) return MonitoringCommandResult(MonitoringCommandStatus.UNAVAILABLE, id, "monitoring_closed")
        val selected = active ?: return MonitoringCommandResult(MonitoringCommandStatus.UNAVAILABLE, id, "companion_link_required")
        val task = worker.async(start = CoroutineStart.LAZY) { commands.withLock {
            requireCurrent(selected)
            publish(selected) { it.copy(phase = if (id == null) MonitoringPhase.READING else MonitoringPhase.WRITING) }
            try {
                val response = remote.exchange(selected.link, buildJsonObject { if (id != null) { put("id", id.name); put("value", value) } })
                requireCurrent(selected)
                val snapshot = MonitoringSettingsCodec.decode(response)
                val outcome = MonitoringCommandStatus.valueOf(response.text("outcome") ?: error("monitoring_outcome_missing"))
                val result = MonitoringCommandResult(outcome, id, response.text("reason"))
                publish(selected) { it.copy(source = snapshot.first, settings = snapshot.second,
                    phase = if (outcome == MonitoringCommandStatus.UNAVAILABLE) MonitoringPhase.UNAVAILABLE else MonitoringPhase.READY,
                    current = outcome in setOf(MonitoringCommandStatus.READ, MonitoringCommandStatus.UNCHANGED, MonitoringCommandStatus.STORED_AND_NOTIFIED), lastOperation = result) }
                result
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException || !currentCoroutineContext().isActive) throw cancelled
                requireCurrent(selected)
                val result = MonitoringCommandResult(if (id == null) MonitoringCommandStatus.FAILED else MonitoringCommandStatus.STORED_UNVERIFIED,
                    id, "monitoring_timeout")
                publish(selected) { it.copy(phase = MonitoringPhase.READY, current = false, lastOperation = result) }
                result
            }
            catch (problem: Exception) {
                requireCurrent(selected)
                val reason = (problem as? ToolboxException)?.code ?: "monitoring_operation_failed"
                // Only explicit write-before-request refusals prove the setter was not attempted.
                // A broken stream can follow a committed value; do not invite an automatic retry.
                val beforeRequest = reason in setOf("companion_link_required", "monitoring_root_required", "root_required",
                    "monitoring_bridge_update_required", "device_changed", "device_identity_required", "not_connected",
                    "agent_version_mismatch", "bridge_update_needs_adb", "phone_app_outdated", "bridge_unavailable")
                val outcome = when {
                    id != null && !beforeRequest -> MonitoringCommandStatus.STORED_UNVERIFIED
                    problem is ToolboxException -> MonitoringCommandStatus.UNAVAILABLE
                    else -> MonitoringCommandStatus.FAILED
                }
                val result = MonitoringCommandResult(outcome, id, reason)
                publish(selected) { it.copy(phase = MonitoringPhase.UNAVAILABLE, current = false, lastOperation = result) }
                runCatching { diagnostics.record(ProtocolDiagnostic("monitoring", "operation_failed", sessionId = selected.link.sessionId,
                    peer = selected.link.peer, details = mapOf("exception" to problem.javaClass.simpleName, "reason" to reason))) }
                result
            } finally {
                update(selected) { if (it.phase in setOf(MonitoringPhase.READING, MonitoringPhase.WRITING)) it.copy(phase = MonitoringPhase.READY, current = false) else it }
            }
        } }
        operations += task
        try { task.start(); return task.await() }
        catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            return MonitoringCommandResult(MonitoringCommandStatus.INTERRUPTED, id, "monitoring_link_changed")
        } finally { operations.remove(task); task.cancel() }
    }
    private fun requireCurrent(selected: Active) { if (closed || active !== selected) throw CancellationException("monitoring_link_changed") }
    private fun publish(selected: Active, change: (MonitoringSettingsState) -> MonitoringSettingsState) {
        if (!update(selected, change)) throw CancellationException("monitoring_link_changed")
    }
    private fun update(selected: Active, change: (MonitoringSettingsState) -> MonitoringSettingsState): Boolean {
        // Gate and publication share the lifecycle lock: a reconnect cannot interleave between
        // checking the old generation and merging its settings into the new generation's state.
        return synchronized(lifecycle) {
            if (closed || active !== selected) false else { mutable.update(change); true }
        }
    }
    override fun close() {
        val obsolete = synchronized(lifecycle) {
            if (closed) return
            closed = true; active = null
            mutable.value = MonitoringSettingsState(phase = MonitoringPhase.CLOSED)
            operations.toList()
        }
        lifetime.cancel(); obsolete.forEach { it.cancel() }
    }
}

internal object MonitoringSettingsCodec {
    fun kind(value: MonitoringValue) = when (value) { is MonitoringValue.Toggle -> "BOOLEAN"; is MonitoringValue.Number -> "INTEGER"; is MonitoringValue.Text -> "TEXT" }
    fun raw(value: MonitoringValue) = when (value) { is MonitoringValue.Toggle -> value.enabled.toString(); is MonitoringValue.Number -> value.value.toString(); is MonitoringValue.Text -> value.value }
    private fun value(kind: String, raw: String?): MonitoringValue? = when (kind) {
        "BOOLEAN" -> when (raw) { "true" -> MonitoringValue.Toggle(true); "false" -> MonitoringValue.Toggle(false); else -> null }
        "INTEGER" -> raw?.toLongOrNull()?.let { MonitoringValue.Number(it) }
        "TEXT" -> raw?.let { MonitoringValue.Text(it) }
        else -> null
    }
    fun decode(reply: JsonObject): Pair<MonitoringSource, List<MonitoringSettingObservation>> {
        val source = reply["source"] as? JsonObject ?: error("monitoring_source_missing")
        require(source.text("packageName") == HealthMonitoringProtocol.PACKAGE)
        val version = source.number("versionCode") ?: error("monitoring_version_missing")
        val analyzed = version in HealthMonitoringProtocol.ANALYZED_VERSIONS
        val writable = reply.flag("writable") == true && analyzed
        val entries = (reply["settings"] as? JsonArray)?.map { it.jsonObject } ?: error("monitoring_settings_missing")
        require(entries.map { it.text("id") ?: error("monitoring_id_missing") }.distinct().size == entries.size)
        val settings = HealthMonitoringProtocol.SETTINGS.map { spec ->
            val entry = entries.singleOrNull { it.text("id") == spec.id } ?: error("monitoring_setting_missing")
            val raw = entry.text("value")
            val parsed = value(spec.kind, raw)
            val available = entry.flag("available") != false
            MonitoringSettingObservation(MonitoringSettingDescriptor(
                MonitoringSettingId.valueOf(spec.id), MonitoringValueKind.valueOf(spec.kind), spec.group, spec.unit,
                spec.choices.map { (raw, meaning) -> MonitoringChoice(value(spec.kind, raw) ?: error("monitoring_choice_invalid"), meaning) },
                minimum = spec.minimum, maximum = spec.maximum,
                defaultValue = if (analyzed) value(spec.kind, entry.text("default") ?: spec.default) else null,
                deviceDependentDefault = !analyzed || spec.deviceDependentDefault,
                writable = writable && available, fixedIntervalSeconds = spec.fixedIntervalSeconds,
                appliesOnNextExercise = spec.id in setOf("EXERCISE_HEART_RATE_ALERT", "EXERCISE_HEART_RATE_UPPER"),
                requiresInProcessModule = spec.inProcess),
                parsed, raw != null && (parsed == null || !spec.accepts(raw)), available,
                entry.text("unavailableReason"), entry.flag("firmwareFeatureEnabled"))
        }
        return MonitoringSource(HealthMonitoringProtocol.PACKAGE, source.text("versionName"), version) to settings
    }
}
