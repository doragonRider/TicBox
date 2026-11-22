package dev.ticbox.companion.engine.sos

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.CompanionFeatureSession
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Each watch has its own private snapshot; a new watch never inherits emergency contacts. */
interface SosSettingsStore {
    suspend fun load(peer: PeerAddress): SosWatchSettings?
    suspend fun save(peer: PeerAddress, settings: SosWatchSettings)
}

class MemorySosSettingsStore : SosSettingsStore {
    private val values = mutableMapOf<PeerAddress, SosWatchSettings>()
    override suspend fun load(peer: PeerAddress) = synchronized(values) { values[peer] }
    override suspend fun save(peer: PeerAddress, settings: SosWatchSettings) { synchronized(values) { values[peer] = settings } }
}

/** A platform-owned executor handles contact SMS and optional location; never phone redialling. */
interface SosEmergencyHandler : SosPhoneAssistanceClient {
    suspend fun handle(request: SosEmergencyRequest): SosEmergencyHandling

    companion object {
        val NONE: SosEmergencyHandler = object : SosEmergencyHandler, SosPhoneAssistanceClient by SosPhoneAssistanceClient.UNAVAILABLE {
            override suspend fun handle(request: SosEmergencyRequest) = SosEmergencyHandling(false, "sos_phone_assistance_unavailable")
        }
    }
}

class SosEngine(
    parentScope: CoroutineScope,
    private val settingsStore: SosSettingsStore = MemorySosSettingsStore(),
    private val emergencyHandler: SosEmergencyHandler = SosEmergencyHandler.NONE,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val clock: Clock = Clock.systemUTC(),
    private val responseTimeoutMillis: Long = 15_000,
) : SosClient {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val worker = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val commands = Mutex()
    private val persistence = Mutex()
    private val mutableState = MutableStateFlow(SosState())
    override val state = mutableState.asStateFlow()
    override val phoneAssistance: SosPhoneAssistanceClient get() = emergencyHandler
    private val pendingLock = Any()
    private class Active(val session: CompanionFeatureSession) {
        val cacheReady = CompletableDeferred<Unit>()
        var ambiguousWrite = false
        var ambiguousQuery = false
    }
    private class Pending(val active: Active, val reply: CompletableDeferred<SosWatchSettings>)
    @Volatile private var active: Active? = null
    private var pending: Pending? = null
    @Volatile private var closed = false
    private var editSequence = 0L

    init { require(responseTimeoutMillis > 0) }

    fun attach(next: CompanionFeatureSession) {
        check(!closed)
        if (active?.session?.let { it.sessionId == next.sessionId && it.peer == next.peer && it.companionRoute == next.companionRoute } == true) return
        active?.cacheReady?.cancel()
        cancelPending()
        val current = Active(next)
        active = current
        mutableState.value = SosState(next.sessionId, next.peer, SosPhase.CONNECTED)
        worker.launch {
            try {
                val saved = persistence.withLock { settingsStore.load(next.peer) }
                update(current) { if (!it.settingsCurrent) it.copy(settings = saved) else it }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { issue(current, "restore", "settings_restore_failed") }
            finally { current.cacheReady.complete(Unit) }
            runCommand(current) { query(current); SosCommandResult(SosCommandStatus.CONFIRMED) }
        }
    }

    fun detach(sessionId: Long) {
        val current = active?.takeIf { it.session.sessionId == sessionId } ?: return
        active = null; current.cacheReady.cancel(); cancelPending()
        mutableState.update { it.copy(sessionId = 0, phase = SosPhase.DISCONNECTED, settingsCurrent = false) }
    }

    override suspend fun refresh() = runCommand(active) { current ->
        if (current.ambiguousQuery) return@runCommand SosCommandResult(SosCommandStatus.UNAVAILABLE,
            reason = "readback_correlation_lost_reconnect_required")
        query(current)
        SosCommandResult(SosCommandStatus.CONFIRMED)
    }

    override suspend fun setEmergencyNumber(number: String) = validated(SosSettingField.EMERGENCY_NUMBER) {
        val bytes = SosCodec.number(number)
        mutate(SosSettingField.EMERGENCY_NUMBER, SosProtocol.NUMBER, bytes) { it.emergencyNumber == number }
    }

    override suspend fun setContacts(contacts: List<SosEmergencyContact>) = validated(SosSettingField.CONTACTS) {
        val selected = contacts.toList()
        val bytes = SosCodec.contacts(selected)
        mutate(SosSettingField.CONTACTS, SosProtocol.CONTACTS, bytes) { it.contacts == selected }
    }

    override suspend fun setButtonTrigger(enabled: Boolean) = mutate(SosSettingField.BUTTON_TRIGGER,
        SosProtocol.BUTTON, enabled.toString().toByteArray()) { it.buttonTriggerEnabled == enabled }
    override suspend fun setShareLocation(enabled: Boolean) = mutate(SosSettingField.SHARE_LOCATION,
        SosProtocol.SHARE_LOCATION, enabled.toString().toByteArray()) { it.shareLocation == enabled }
    override suspend fun setFallDetection(mode: SosFallDetectionMode) = mutate(SosSettingField.FALL_DETECTION,
        SosProtocol.FALL, mode.wireValue.toString().toByteArray()) { it.fallDetectionValue == mode.wireValue }

    private suspend fun validated(field: SosSettingField, block: suspend () -> SosCommandResult): SosCommandResult = try { block() }
        catch (_: IllegalArgumentException) { SosCommandResult(SosCommandStatus.INVALID_ARGUMENT, field, "invalid_${field.name.lowercase()}") }

    private suspend fun mutate(field: SosSettingField, path: String, bytes: ByteArray,
        matches: (SosWatchSettings) -> Boolean): SosCommandResult = runCommand(active, field) { current ->
        if (current.ambiguousWrite) return@runCommand SosCommandResult(SosCommandStatus.UNAVAILABLE, field, "previous_write_outcome_unknown_reconnect_required")
        if (current.ambiguousQuery) return@runCommand SosCommandResult(SosCommandStatus.UNAVAILABLE, field, "readback_correlation_lost_reconnect_required")
        update(current) { it.copy(phase = SosPhase.WRITING) }
        var submitted = false
        try {
            requireCurrent(current)
            submitted = true
            current.session.sendMessage(path, bytes)
            // sync_ok has no ID and can also acknowledge a failed Settings.Global write.
            // It is never used to complete a command. Only a following preference readback does so.
            val observed = query(current)
            val result = if (matches(observed)) SosCommandResult(SosCommandStatus.CONFIRMED, field,
                if (field == SosSettingField.FALL_DETECTION) "watch_preference_confirmed_sensor_execution_unverified" else null)
            else SosCommandResult(SosCommandStatus.READ_BACK_MISMATCH, field, "watch_preference_did_not_match")
            log("setting_read_back", current, mapOf("field" to field.name, "matched" to (result.status == SosCommandStatus.CONFIRMED).toString()))
            result
        } catch (problem: Exception) {
            if (submitted) current.ambiguousWrite = true
            throw problem
        }
    }

    suspend fun handleMessage(peer: PeerAddress, sessionId: Long, path: String, bytes: ByteArray,
        route: ApplicationRoute? = null): Boolean {
        val current = active?.takeIf { it.session.peer == peer && it.session.sessionId == sessionId } ?: return false
        if (route != null && route != current.session.companionRoute) return false
        if (path !in SosProtocol.MESSAGES) return false
        try {
            when (path) {
                SosProtocol.SETTINGS -> {
                    val snapshot = SosCodec.settings(bytes)
                    val prior = state.value.takeIf { active === current && it.settingsCurrent }?.settings
                    if (prior != null && snapshot.updatedAtMillis < prior.updatedAtMillis) {
                        // u_time belongs to the watch, not the phone clock. Only compare snapshots
                        // inside this physical session; a reboot/clock change may reset its range.
                        log("stale_snapshot_ignored", current)
                        return true
                    }
                    update(current) { it.copy(settings = snapshot, settingsCurrent = true, lastIssue = null) }
                    val waiting = synchronized(pendingLock) { pending?.takeIf { it.active === current } }
                    waiting?.reply?.complete(snapshot)
                    worker.launch {
                        try { persistence.withLock { settingsStore.save(peer, snapshot) } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { issue(current, "persist", "settings_save_failed") }
                    }
                    if (snapshot.contacts == null) issue(current, "contacts", "watch_contacts_unreadable")
                }
                SosProtocol.SYNC_OK -> {
                    require(bytes.isEmpty()) { "sos_ack_not_empty" }
                    log("settings_receipt", current)
                }
                SosProtocol.NUMBER, SosProtocol.CONTACTS -> {
                    // These reverse-direction messages request opening the phone's editor. The phone
                    // stock receiver ignores their body; do not replace a confirmed snapshot with it.
                    require(bytes.size <= SosCodec.MAX_CONTACTS_BYTES) { "sos_payload_budget" }
                    val kind = if (path == SosProtocol.NUMBER) SosWatchEditKind.EMERGENCY_NUMBER else SosWatchEditKind.CONTACTS
                    val sequence = synchronized(pendingLock) { ++editSequence }
                    update(current) { it.copy(lastWatchEditRequest = SosWatchEditRequest(sequence, kind, sessionId)) }
                }
                SosProtocol.START -> {
                    // The stock phone ignores this payload. The watch separately places its call.
                    // The enclosing transport already bounds message size. Do not drop a current
                    // emergency merely because an unused body exceeds the number-setting budget.
                    if (bytes.size > SosCodec.MAX_NUMBER_CHARS * 4)
                        log("emergency_payload_ignored", current, mapOf("bytes" to bytes.size.toString()))
                    val eventId = UUID.randomUUID().toString()
                    val receivedAtMillis = clock.millis()
                    update(current) { it.copy(emergencyEventsReceived = it.emergencyEventsReceived + 1) }
                    worker.launch { emergency(current, eventId, receivedAtMillis) }
                }
                else -> log("unexpected_direction", current, mapOf("path" to path))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { issue(current, "receive", "malformed_sos_message"); log("message_rejected", current, mapOf("path" to path, "bytes" to bytes.size.toString())) }
        return true
    }

    private suspend fun emergency(current: Active, eventId: String, receivedAtMillis: Long) {
        try {
            current.cacheReady.await(); requireCurrent(current)
            var snapshot = state.value.settings
            val remaining = 60_000 - (clock.millis() - receivedAtMillis)
            if (remaining !in 1..60_000) { issue(current, "emergency", "sos_event_expired"); return }
            if (!state.value.settingsCurrent || snapshot == null || snapshot.contacts == null) {
                // Bound both queueing behind an in-flight query and the read itself. A stale
                // contact list or location-sharing grant is never authority for sending an SOS.
                val result = withTimeoutOrNull(minOf(responseTimeoutMillis, remaining)) {
                    runCommand(current) {
                        if (state.value.settingsCurrent && state.value.settings?.contacts != null)
                            SosCommandResult(SosCommandStatus.CONFIRMED)
                        else if (current.ambiguousQuery) SosCommandResult(SosCommandStatus.UNAVAILABLE,
                            reason = "readback_correlation_lost_reconnect_required")
                        else { query(current); SosCommandResult(SosCommandStatus.CONFIRMED) }
                    }
                } ?: SosCommandResult(SosCommandStatus.TIMED_OUT, reason = "sos_settings_refresh_timeout")
                if (result.status != SosCommandStatus.CONFIRMED) { issue(current, "emergency", result.reason ?: "sos_settings_unavailable"); return }
                snapshot = state.value.settings
            }
            requireCurrent(current)
            if (!state.value.settingsCurrent) { issue(current, "emergency", "sos_settings_not_current"); return }
            val contacts = snapshot?.contacts
            if (contacts == null) { issue(current, "emergency", "watch_contacts_unreadable"); return }
            if (contacts.isEmpty()) { issue(current, "emergency", "no_emergency_contacts"); return }
            val request = SosEmergencyRequest(eventId, current.session.peer,
                current.session.sessionId, receivedAtMillis, contacts.toList(), snapshot.shareLocation)
            val handling = emergencyHandler.handle(request)
            if (!handling.accepted) issue(current, "emergency", handling.reason ?: "phone_assistance_rejected")
            log("emergency_dispatched", current, mapOf("accepted" to handling.accepted.toString(), "recipientCount" to contacts.size.toString()))
        } catch (cancelled: CancellationException) { if (active === current) throw cancelled }
        catch (_: NotConnectedException) { /* Never apply a queued old-watch event to a replacement session. */ }
        catch (_: Exception) { issue(current, "emergency", "phone_assistance_failed") }
    }

    private suspend fun query(current: Active): SosWatchSettings {
        val waiting = Pending(current, CompletableDeferred())
        synchronized(pendingLock) { pending = waiting }
        var submitted = false
        try {
            requireCurrent(current)
            update(current) { it.copy(phase = if (it.phase == SosPhase.WRITING) SosPhase.WRITING else SosPhase.READING) }
            submitted = true
            current.session.sendMessage(SosProtocol.SETTINGS, byteArrayOf())
            return withTimeout(responseTimeoutMillis) { waiting.reply.await() }.also { requireCurrent(current) }
        } catch (problem: Exception) {
            // A late ID-less settings response must not satisfy a later write's readback.
            if (submitted) current.ambiguousQuery = true
            throw problem
        } finally {
            synchronized(pendingLock) { if (pending === waiting) pending = null }
            waiting.reply.cancel()
        }
    }

    private suspend fun runCommand(selected: Active?, field: SosSettingField? = null,
        block: suspend (Active) -> SosCommandResult): SosCommandResult {
        val current = selected ?: return SosCommandResult(SosCommandStatus.UNAVAILABLE, field, "no_current_session")
        return commands.withLock {
            val result = try { requireCurrent(current); block(current) }
            catch (_: TimeoutCancellationException) { SosCommandResult(SosCommandStatus.TIMED_OUT, field, "watch_response_timeout") }
            catch (_: NotConnectedException) { SosCommandResult(SosCommandStatus.UNAVAILABLE, field, "session_changed") }
            catch (cancelled: CancellationException) {
                if (active !== current && currentCoroutineContext().isActive) SosCommandResult(SosCommandStatus.UNAVAILABLE, field, "session_changed")
                else {
                    update(current) { it.copy(phase = SosPhase.CONNECTED, lastIssue = SosIssue("command",
                        if (current.ambiguousWrite) "write_cancelled_outcome_unknown" else "command_cancelled")) }
                    throw cancelled
                }
            } catch (_: Exception) { SosCommandResult(SosCommandStatus.FAILED, field, "sos_command_failed") }
            update(current) { it.copy(phase = SosPhase.CONNECTED, lastOperation = result,
                lastIssue = if (result.status == SosCommandStatus.CONFIRMED) {
                    if (it.settings?.contacts == null && it.settings != null) SosIssue("contacts", "watch_contacts_unreadable") else null
                } else result.reason?.let { reason -> SosIssue("command", reason) }) }
            result
        }
    }

    private fun requireCurrent(current: Active) { if (closed || active !== current) throw NotConnectedException() }
    private fun update(current: Active, change: (SosState) -> SosState) { mutableState.update { if (active === current) change(it) else it } }
    private fun issue(current: Active, operation: String, reason: String) { update(current) { it.copy(lastIssue = SosIssue(operation, reason)) } }
    private fun cancelPending() = synchronized(pendingLock) { pending?.reply?.cancel(); pending = null }
    private fun log(event: String, current: Active, details: Map<String, String> = emptyMap()) {
        runCatching { diagnostics.record(ProtocolDiagnostic("sos", event, sessionId = current.session.sessionId, peer = current.session.peer, details = details)) }
    }

    override fun close() {
        closed = true; active?.cacheReady?.cancel(); active = null; cancelPending(); lifetime.cancel()
        mutableState.update { it.copy(sessionId = 0, phase = SosPhase.CLOSED, settingsCurrent = false) }
    }
}
