package dev.ticbox.companion.engine.calling

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.CompanionFeatureSession
import dev.ticbox.companion.protocol.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Call state uses ordinary MessageApi messages. No invented call-command response/ACK is sent. */
class CompanionCallingEngine(
    scope: CoroutineScope,
    private val platform: CallingPlatform,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    initialEnabled: Boolean = true,
    private val settingsStore: CallingSettingsStore? = null,
) : CompanionCallingClient {
    private val lifetime = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val mutable = MutableStateFlow(CompanionCallingState(enabled = initialEnabled))
    override val state = mutable.asStateFlow()
    private val lock = Mutex()
    private val epoch = AtomicLong()
    @Volatile private var session: CompanionFeatureSession? = null
    @Volatile private var attachedEpoch = 0L
    private val initialized = CompletableDeferred<Unit>()
    @Volatile private var closed = false
    private var publishedCalls = linkedMapOf<String, CompanionCall>()
    private var lastMuted: Boolean? = null
    private var lastSilence = 0L
    private var lastServiceConnected = false
    private var resetRequired = true
    private val endedOnWatch = mutableSetOf<String>()
    private var pendingEnd: Job? = null
    private var pendingEndEpoch: Long? = null

    init {
        lifetime.launch {
            try {
                val saved = settingsStore?.readEnabled()
                lock.withLock { mutable.value = mutable.value.copy(enabled = saved ?: initialEnabled, settingsLoaded = true) }
            } catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                lock.withLock { mutable.value = mutable.value.copy(enabled = false, settingsLoaded = true,
                    settingsDurable = false, lastIssue = "call_settings_read_${problem.javaClass.simpleName}") }
                log("settings_read_failed", mapOf("exception" to problem.javaClass.simpleName))
            } finally { initialized.complete(Unit) }
        }
        lifetime.launch {
            initialized.await()
            platform.state.collect { source ->
                lock.withLock {
                    if (closed) return@withLock
                    reflect(source)
                    val selected = session ?: return@withLock
                    if (!mutable.value.enabled) return@withLock
                    if (attachedEpoch != epoch.get()) return@withLock
                    if (pendingEndEpoch == attachedEpoch) {
                        source.calls.firstOrNull { it.stateValue == 1 || it.stateValue == 9 }?.let { call ->
                            clearPendingEnd()
                            invokePlatform(CallCommand.Action(call.id, 2), selected.peer)
                        }
                    }
                    publish(selected, source, attachedEpoch)
                }
            }
        }
    }

    suspend fun attach(session: CompanionFeatureSession?) {
        val selectedEpoch = epoch.incrementAndGet()
        initialized.await()
        lock.withLock {
            if (closed || epoch.get() != selectedEpoch) return
            this.session = session
            attachedEpoch = selectedEpoch
            clearPendingEnd(); endedOnWatch.clear(); publishedCalls.clear()
            lastMuted = null; lastSilence = platform.state.value.silenceRingerSequence
            lastServiceConnected = platform.state.value.serviceConnected; resetRequired = true
            reflect(platform.state.value)
            if (session != null && mutable.value.enabled) publish(session, platform.state.value, selectedEpoch)
        }
    }

    /** A delayed teardown for an old physical session cannot detach a newer or pending attachment. */
    suspend fun detach(sessionId: Long) {
        initialized.await()
        if (session?.sessionId != sessionId) return
        val oldEpoch = attachedEpoch
        epoch.compareAndSet(oldEpoch, oldEpoch + 1)
        lock.withLock {
            if (session?.sessionId != sessionId) return@withLock
            session = null
            clearPendingEnd(); endedOnWatch.clear(); publishedCalls.clear(); resetRequired = true; lastMuted = null
            reflect(platform.state.value)
        }
    }

    override suspend fun setEnabled(enabled: Boolean) {
        initialized.await()
        lock.withLock {
        if (closed) return@withLock
        val persistenceIssue = try { settingsStore?.writeEnabled(enabled); null } catch (problem: Exception) {
            if (problem is CancellationException) throw problem
            log("settings_write_failed", mapOf("exception" to problem.javaClass.simpleName))
            "call_settings_write_${problem.javaClass.simpleName}"
        }
        if (mutable.value.enabled && !enabled) session?.let { send(it, CallProtocol.RESET, byteArrayOf(), epoch.get()) }
        mutable.value = mutable.value.copy(enabled = enabled)
        clearPendingEnd(); endedOnWatch.clear()
        if (!enabled) {
            publishedCalls.clear(); lastMuted = null; resetRequired = true
            reflect(platform.state.value)
        } else {
            resetRequired = true
            reflect(platform.state.value)
            session?.let { publish(it, platform.state.value, epoch.get()) }
        }
        mutable.value = mutable.value.copy(settingsDurable = persistenceIssue == null,
            lastIssue = persistenceIssue ?: mutable.value.lastIssue?.takeUnless { it.startsWith("call_settings_") })
        }
    }

    override suspend fun refreshAccess() {
        try { platform.refreshAccess(); resync() } catch (problem: Exception) {
            if (problem is CancellationException) throw problem
            lock.withLock { mutable.value = mutable.value.copy(lastIssue = "access_refresh_${problem.javaClass.simpleName}") }
            log("access_refresh_failed", mapOf("exception" to problem.javaClass.simpleName))
        }
    }
    override suspend fun resync() {
        initialized.await()
        lock.withLock {
        if (closed) return@withLock
        reflect(platform.state.value)
        resetRequired = true
        if (mutable.value.enabled) session?.let { publish(it, platform.state.value, epoch.get()) }
        }
    }

    suspend fun handleMessage(peer: PeerAddress, sessionId: Long, path: String, payload: ByteArray,
        route: ApplicationRoute? = null): Boolean {
        if (path !in CallProtocol.INBOUND_PATHS) return false
        initialized.await()
        val selectedEpoch = epoch.get()
        return lock.withLock {
            val selected = session
            if (closed || selected == null || selected.peer != peer || selected.sessionId != sessionId ||
                selectedEpoch != epoch.get() || selectedEpoch != attachedEpoch) {
                outcome(CallCommandResult(CallCommandStatus.STALE_SESSION, "stale_call_session"))
                log("stale_message_ignored", mapOf("path" to path))
                return@withLock true
            }
            if (route != null && route != selected.companionRoute) {
                log("other_app_route_ignored", mapOf("path" to path))
                return@withLock true
            }
            if (route == null) log("message_route_unreported", mapOf("path" to path))
            if (!mutable.value.enabled) { outcome(CallCommandResult(CallCommandStatus.DISABLED)); return@withLock true }
            try {
                val command = if (path == CallProtocol.LEGACY_PHONE) {
                    val document = DataMapCodec.decode(payload)
                    document.notices.forEach { log("compat_decode", mapOf("notice" to it)) }
                    val value = document.values["command"] as? DataMapValue.IntNumber
                    if (value?.value != 3) {
                        outcome(CallCommandResult(CallCommandStatus.UNSUPPORTED, "legacy_phone_command_unknown"))
                        return@withLock true
                    }
                    CallCommand.Dial((document.values["phone_number"] as? DataMapValue.Text)?.value ?: "")
                } else {
                    val decoded = CallCodec.decodeCommand(path, payload)
                    decoded.notices.forEach { log("compat_decode", mapOf("notice" to it)) }
                    when (path) {
                        CallProtocol.ACTION_REQUEST -> CallCommand.Action(decoded.callId, decoded.action)
                        CallProtocol.REJECT_REQUEST -> CallCommand.Reject(decoded.callId, decoded.reason, decoded.textMessage)
                        CallProtocol.DIAL_REQUEST -> CallCommand.Dial(decoded.number)
                        else -> CallCommand.Dtmf(decoded.callId, decoded.code)
                    }
                }
                executeLocked(command, selected.peer, selectedEpoch)
            } catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                outcome(CallCommandResult(CallCommandStatus.MALFORMED, problem.javaClass.simpleName))
                log("command_decode_failed", mapOf("path" to path, "bytes" to payload.size.toString(), "exception" to problem.javaClass.simpleName))
            }
            true
        }
    }

    override suspend fun execute(command: CallCommand): CallCommandResult {
        initialized.await()
        return lock.withLock {
        if (closed) return@withLock CallCommandResult(CallCommandStatus.UNAVAILABLE, "calling_closed")
        if (!mutable.value.enabled) return@withLock outcome(CallCommandResult(CallCommandStatus.DISABLED))
        if (session != null && attachedEpoch != epoch.get())
            return@withLock outcome(CallCommandResult(CallCommandStatus.STALE_SESSION, "session_replacement_pending"))
        executeLocked(command, session?.peer, epoch.get())
        }
    }

    private suspend fun executeLocked(command: CallCommand, peer: PeerAddress?, selectedEpoch: Long): CallCommandResult {
        if (command is CallCommand.Action && command.actionValue !in 1..9)
            return outcome(CallCommandResult(CallCommandStatus.UNSUPPORTED, "unknown_or_unimplemented_action_${command.actionValue}"))
        if (command is CallCommand.Dtmf && (command.code.length != 1 || command.code[0] !in "0123456789ABCD*#"))
            return outcome(CallCommandResult(CallCommandStatus.MALFORMED, "invalid_dtmf"))
        if (command is CallCommand.Dial && command.number.isEmpty())
            return outcome(CallCommandResult(CallCommandStatus.MALFORMED, "empty_dial_number"))
        // The official client keeps a three-second cancellation for an outgoing call not yet added.
        if (command is CallCommand.Action && command.actionValue == 2 && command.callId.isEmpty() &&
            platform.state.value.calls.isEmpty() && platform.state.value.access.canManage && session != null) {
            clearPendingEnd()
            pendingEndEpoch = selectedEpoch
            pendingEnd = lifetime.launch {
                delay(3_000)
                lock.withLock { if (pendingEndEpoch == selectedEpoch) {
                    pendingEndEpoch = null; pendingEnd = null
                    outcome(CallCommandResult(CallCommandStatus.UNAVAILABLE, "outgoing_call_not_observed"))
                } }
            }
            return outcome(CallCommandResult(CallCommandStatus.DEFERRED, "awaiting_next_outgoing_call"))
        }
        val result = invokePlatform(command, peer)
        if (selectedEpoch != epoch.get()) return outcome(CallCommandResult(CallCommandStatus.STALE_SESSION, "session_changed_during_command"))
        return result
    }

    private suspend fun invokePlatform(command: CallCommand, peer: PeerAddress?): CallCommandResult {
        val result = try { platform.execute(command, peer) } catch (problem: Exception) {
            if (problem is CancellationException) throw problem
            log("platform_command_failed", mapOf("exception" to problem.javaClass.simpleName, "kind" to command.javaClass.simpleName))
            CallCommandResult(if (problem is SecurityException) CallCommandStatus.UNAVAILABLE else CallCommandStatus.FAILED,
                problem.javaClass.simpleName)
        }
        if (result.status == CallCommandStatus.DISPATCHED && command is CallCommand.Action && command.actionValue == 2)
            result.callId?.let { endedOnWatch += it }
        return outcome(result)
    }

    private suspend fun publish(selected: CompanionFeatureSession, source: CallingPlatformState, selectedEpoch: Long) {
        if (!current(selected, selectedEpoch)) return
        if (lastServiceConnected && !source.serviceConnected) resetRequired = true
        lastServiceConnected = source.serviceConnected
        if (resetRequired) {
            if (!send(selected, CallProtocol.RESET, byteArrayOf(), selectedEpoch)) return
            publishedCalls.clear(); lastMuted = null; resetRequired = false
        }
        val visible = source.calls.associateBy { it.id }
        for ((id, previous) in publishedCalls.toMap()) {
            if (id !in visible) {
                // Removal is an observed Telecom callback; it withdraws this call from the watch.
                if (!send(selected, CallProtocol.CALL, encode(previous.copy(stateValue = 7)), selectedEpoch)) return
                publishedCalls.remove(id); endedOnWatch.remove(id)
            }
        }
        for (call in source.calls) {
            if (call.id in endedOnWatch && call.stateValue != 7) continue
            if (publishedCalls[call.id] == call) continue
            if (!send(selected, CallProtocol.CALL, encode(call), selectedEpoch)) return
            publishedCalls[call.id] = call
            if (call.stateValue == 7) endedOnWatch.remove(call.id)
        }
        source.muted?.let { muted ->
            if (muted != lastMuted && send(selected, CallProtocol.AUDIO_STATE, CallCodec.audioState(muted), selectedEpoch)) lastMuted = muted
        }
        if (source.silenceRingerSequence != lastSilence && send(selected, CallProtocol.SILENCE_RINGER, byteArrayOf(), selectedEpoch))
            lastSilence = source.silenceRingerSequence
        reflect(source)
    }

    private fun encode(call: CompanionCall): ByteArray = CallCodec.call(CallWireState(call.id, call.stateValue,
        call.handle, call.handlePresentation, call.callerDisplayName, call.callerDisplayNamePresentation,
        call.capabilities, call.properties, call.disconnectCause?.let { CallCodec.disconnectCause(it.code, it.label, it.description, it.reason, it.tone) },
        call.connectTimeMillis, call.videoState, call.creationTimeMillis, call.contactDisplayName, call.directionValue, call.companionCanAnswer))

    private suspend fun send(selected: CompanionFeatureSession, path: String, bytes: ByteArray, selectedEpoch: Long): Boolean {
        repeat(3) { attempt ->
            if (!current(selected, selectedEpoch)) return false
            try {
                selected.sendMessage(path, bytes)
                if (!current(selected, selectedEpoch)) return false
                mutable.value = mutable.value.copy(messagesPublished = mutable.value.messagesPublished + 1,
                    lastIssue = if (mutable.value.settingsDurable) null else mutable.value.lastIssue)
                return true
            } catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                log("state_publish_failed", mapOf("path" to path, "attempt" to (attempt + 1).toString(), "exception" to problem.javaClass.simpleName))
            }
        }
        mutable.value = mutable.value.copy(lastIssue = "call_state_publish_failed")
        return false
    }

    private fun current(selected: CompanionFeatureSession, selectedEpoch: Long) =
        !closed && epoch.get() == selectedEpoch && attachedEpoch == selectedEpoch && session === selected && mutable.value.enabled
    private fun clearPendingEnd() { pendingEnd?.cancel(); pendingEnd = null; pendingEndEpoch = null }
    private fun outcome(result: CallCommandResult): CallCommandResult {
        val dispatched = result.status == CallCommandStatus.DISPATCHED
        val failed = result.status != CallCommandStatus.DISPATCHED && result.status != CallCommandStatus.DEFERRED
        mutable.value = mutable.value.copy(lastCommandResult = result,
            commandsDispatched = mutable.value.commandsDispatched + if (dispatched) 1 else 0,
            commandsFailed = mutable.value.commandsFailed + if (failed) 1 else 0)
        log("command_result", mapOf("status" to result.status.name, "reason" to (result.reason ?: "none")))
        return result
    }
    private fun reflect(source: CallingPlatformState) {
        mutable.value = mutable.value.copy(access = source.access, serviceConnected = source.serviceConnected,
            peer = session?.peer, sessionId = session?.sessionId ?: 0, calls = source.calls, muted = source.muted,
            audioEndpoints = source.endpoints, currentEndpointId = source.currentEndpointId, telephonyStateValue = source.telephonyStateValue,
            phase = when {
                closed -> CallingPhase.CLOSED
                !mutable.value.enabled -> CallingPhase.DISABLED
                !source.access.canManage -> CallingPhase.AUTHORIZATION_REQUIRED
                session == null -> CallingPhase.DISCONNECTED
                source.calls.isNotEmpty() -> CallingPhase.ACTIVE
                else -> CallingPhase.READY
            }, lastIssue = source.issue ?: mutable.value.lastIssue)
    }
    private fun log(event: String, details: Map<String, String> = emptyMap()) {
        runCatching { diagnostics.record(ProtocolDiagnostic("calling", event, details = details)) }
    }
    override fun close() {
        if (closed) return
        closed = true; epoch.incrementAndGet(); clearPendingEnd(); session = null
        initialized.complete(Unit)
        mutable.value = mutable.value.copy(phase = CallingPhase.CLOSED, peer = null, sessionId = 0, calls = emptyList())
        lifetime.cancel()
        try { platform.close() } catch (problem: Exception) { log("platform_close_failed", mapOf("exception" to problem.javaClass.simpleName)) }
    }
}
