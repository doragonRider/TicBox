package dev.ticbox.companion.engine.finding

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.CompanionFeatureSession
import dev.ticbox.companion.protocol.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface PhoneRinger : AutoCloseable {
    val state: StateFlow<PhoneRingingState>
    suspend fun start()
    suspend fun stop()
}
interface FindDeviceSettingsStore {
    suspend fun load(): FindDeviceSettings
    suspend fun save(settings: FindDeviceSettings)
}
class MemoryFindDeviceSettingsStore : FindDeviceSettingsStore {
    private var settings = FindDeviceSettings()
    override suspend fun load() = settings
    override suspend fun save(settings: FindDeviceSettings) { this.settings = settings }
}

/** One ringer for the current physical session. All native effects and callbacks are serialized. */
class FindDevicesEngine(
    parentScope: CoroutineScope,
    private val data: DataSyncClient,
    private val ringer: PhoneRinger,
    private val settingsStore: FindDeviceSettingsStore = MemoryFindDeviceSettingsStore(),
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val ringMillis: Long = 300_000,
    private val watchRoute: ApplicationRoute? = null,
    private val watchResponseMillis: Long = 15_000,
) : FindDevicesClient {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val worker = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(FindDeviceState())
    private val initialized = CompletableDeferred<Unit>(lifetime)
    override val state = mutableState.asStateFlow()
    private var session: CompanionFeatureSession? = null
    private var timer: Job? = null
    private var watchTimer: Job? = null
    private var watchResponseTimer: Job? = null
    private var watchRequest: WatchRequest? = null
    private var ringOwner: CompanionFeatureSession? = null
    private var ringAcknowledged = false
    private var capabilityReconciledSession = 0L
    private var retiredSession = 0L
    private var newestSession = 0L
    private var closed = false
    private class WatchRequest(val owner: CompanionFeatureSession, val id: String) {
        var stopping = false
        var terminal = false
    }

    init {
        require(ringMillis in 1..300_000)
        require(watchResponseMillis > 0)
        worker.launch {
            try {
                val saved = settingsStore.load()
                ensureActive()
                mutableState.update { it.copy(settings = saved, settingsReady = true) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Exception) {
                mutableState.update { it.copy(settingsReady = true, lastIssue = FindDevicesIssue(FindDevicesIssueKind.SETTINGS_RESTORE, problem.javaClass.simpleName)) }
                log("settings_restore_failed", mapOf("exception" to problem.javaClass.simpleName))
            }
            initialized.complete(Unit)
        }
        worker.launch {
            ringer.state.collect { phone -> mutex.withLock {
                val previous = mutableState.value.phone
                mutableState.update { it.copy(phone = phone, lastIssue = phone.soundIssue() ?: it.lastIssue) }
                if (previous.active && !phone.active && ringAcknowledged) finishPhone(ringOwner, "native_stopped")
            } }
        }
        worker.launch {
            initialized.await()
            data.state.collect { sync -> mutex.withLock {
                val current = session
                if (current != null && sync.sessionId == current.sessionId && sync.phase == DataSyncPhase.ACTIVE &&
                    capabilityReconciledSession != current.sessionId) publishCapability(current, mutableState.value.settings.enabled)
            } }
        }
    }

    private suspend fun <T> withSettings(block: suspend () -> T): T {
        initialized.await()
        return mutex.withLock { check(!closed); block() }
    }

    suspend fun attach(next: CompanionFeatureSession) = withSettings {
        if (next.sessionId <= retiredSession || next.sessionId < newestSession) return@withSettings
        if (session?.sessionId == next.sessionId && session?.peer == next.peer) return@withSettings
        newestSession = next.sessionId
        retireWatch()
        stopLocal()
        session = next
        capabilityReconciledSession = 0
        mutableState.update { it.copy(sessionId = next.sessionId, capabilityPublished = false,
            watchStatus = FindWatchStatus.IDLE, watchRingingConfirmed = false,
            watchAudioPlaybackStarted = false, watchVibrationRequested = false, watchStopAvailable = false, lastIssue = null) }
        if (data.state.value.sessionId == next.sessionId && data.state.value.phase == DataSyncPhase.ACTIVE)
            publishCapability(next, mutableState.value.settings.enabled)
    }

    suspend fun detach(sessionId: Long) = mutex.withLock {
        retiredSession = maxOf(retiredSession, sessionId)
        if (session?.sessionId != sessionId) return@withLock
        retireWatch()
        stopLocal()
        session = null
        mutableState.update { it.copy(sessionId = 0, capabilityPublished = false, watchStatus = FindWatchStatus.DISCONNECTED,
            watchRingingConfirmed = false, watchAudioPlaybackStarted = false, watchVibrationRequested = false, watchStopAvailable = false) }
    }

    override suspend fun setEnabled(enabled: Boolean) = withSettings {
        val settings = FindDeviceSettings(enabled)
        settingsStore.save(settings)
        mutableState.update { it.copy(settings = settings) }
        val current = session
        if (!enabled) {
            finishPhone(current, "disabled")
            watchRequest?.takeIf { !it.terminal }?.let { stopWatch(it) }
        }
        if (current != null) publishCapability(current, enabled)
    }

    suspend fun handleMessage(peer: PeerAddress, sessionId: Long, path: String, payload: ByteArray,
        route: ApplicationRoute? = null): Boolean = withSettings {
        val current = session?.takeIf { it.peer == peer && it.sessionId == sessionId } ?: return@withSettings false
        if (path == WatchFindingProtocol.STATE_PATH && route == watchRoute && watchRoute != null)
            return@withSettings receiveWatch(payload)
        if (path == DeviceSettingsProtocol.REMOTE_INTENT_RESULT && (route == null || route == current.companionRoute))
            return@withSettings receiveWatchLaunch(payload)
        if (path !in INCOMING_PATHS || (route != null && route != GMS_ROUTE)) return@withSettings false
        if (payload.isNotEmpty()) log("ignored_ring_payload", mapOf("path" to path, "bytes" to payload.size.toString()))
        if (path == ACTION_CANCEL_RING) finishPhone(current, "remote_cancel")
        else if (mutableState.value.settings.enabled) startPhone(current)
        else { send(current, RING_CANCELED); log("ring_disabled") }
        true
    }

    override suspend fun ringPhone() = withSettings { startPhone(session) }
    override suspend fun stopPhone() = mutex.withLock { finishPhone(ringOwner, "local_cancel") }
    override suspend fun ringWatch() = withSettings {
        check(mutableState.value.settings.enabled) { "Finding disabled" }
        startWatch(session ?: throw NotConnectedException())
    }
    override suspend fun stopWatch() {
        mutex.withLock {
            if (session == null) throw NotConnectedException()
            watchRequest?.takeIf { !it.terminal }?.let { stopWatch(it) }
        }
    }

    private suspend fun startPhone(current: CompanionFeatureSession?) {
        if (!mutableState.value.settings.enabled) return
        timer?.cancel()
        ringOwner = current
        try {
            ringer.start()
            val status = ringer.state.value
            mutableState.update { it.copy(phone = status, lastIssue = status.soundIssue()) }
            if (status.active) {
                ringAcknowledged = current != null
                if (current != null) send(current, RING_STARTED)
                timer = worker.launch { delay(ringMillis); mutex.withLock { timer = null; finishPhone(current, "timeout") } }
            } else {
                ringAcknowledged = false
                if (current != null) send(current, RING_CANCELED)
                log("ring_start_unavailable")
            }
        } catch (cancelled: CancellationException) { withContext(NonCancellable) { stopLocal() }; throw cancelled }
        catch (problem: Exception) {
            stopLocal()
            mutableState.update { it.copy(lastIssue = FindDevicesIssue(FindDevicesIssueKind.PHONE_RING, problem.javaClass.simpleName)) }
            log("ring_failed", mapOf("exception" to problem.javaClass.simpleName))
            if (current != null) send(current, RING_CANCELED)
        }
    }

    private suspend fun finishPhone(owner: CompanionFeatureSession?, reason: String) {
        val acknowledged = ringAcknowledged
        stopLocal()
        if (owner != null && session === owner && (acknowledged || reason == "remote_cancel")) send(owner, RING_CANCELED)
        log("ring_stopped", mapOf("reason" to reason))
    }
    private suspend fun stopLocal() {
        timer?.cancel(); timer = null
        ringAcknowledged = false; ringOwner = null
        try { ringer.stop() } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) { log("native_stop_failed", mapOf("exception" to problem.javaClass.simpleName)) }
        mutableState.update { it.copy(phone = ringer.state.value) }
    }
    private suspend fun startWatch(current: CompanionFeatureSession) {
        val target = watchRoute
        if (target == null) {
            watchFailed(FindWatchStatus.UNAVAILABLE, "watch_extension_route_unavailable")
            return
        }
        watchRequest?.takeIf { !it.terminal }?.let {
            try { sendWatchStop(it) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Exception) { log("previous_watch_stop_failed", mapOf("exception" to problem.javaClass.simpleName)) }
        }
        retireWatch()
        val selected = WatchRequest(current, UUID.randomUUID().toString())
        watchRequest = selected
        mutableState.update { it.copy(watchStatus = FindWatchStatus.STARTING, watchRingingConfirmed = false,
            watchAudioPlaybackStarted = false, watchVibrationRequested = false, watchStopAvailable = true, lastIssue = null) }
        try {
            current.sendMessage(DeviceSettingsProtocol.REMOTE_INTENT,
                WatchFindingProtocol.launch(target.packageName, selected.id, current.scope.localNodeId, ringMillis))
            log("watch_launch_submitted", mapOf("requestId" to selected.id, "decision" to "wait for watch playback feedback"))
            awaitWatchResponse(selected, false)
            watchTimer = worker.launch { delay(ringMillis); mutex.withLock {
                watchTimer = null
                if (watchRequest === selected && !selected.terminal) stopWatch(selected)
            } }
        } catch (cancelled: CancellationException) {
            // Sending may already have queued the remote Activity. Cancellation still owns its STOP.
            worker.launch { mutex.withLock {
                if (watchRequest === selected && session === current && !selected.terminal) stopWatch(selected)
            } }
            throw cancelled
        } catch (problem: Exception) {
            if (watchRequest === selected && !closed) {
                watchFailed(FindWatchStatus.FAILED, problem.javaClass.simpleName)
            }
        }
    }
    private suspend fun stopWatch(selected: WatchRequest) {
        selected.stopping = true
        watchTimer?.cancel(); watchTimer = null
        watchResponseTimer?.cancel(); watchResponseTimer = null
        mutableState.update { it.copy(watchStatus = FindWatchStatus.STOPPING, lastIssue = null) }
        try {
            sendWatchStop(selected)
            awaitWatchResponse(selected, true)
        } catch (cancelled: CancellationException) {
            if (watchRequest === selected && !closed) awaitWatchResponse(selected, true)
            throw cancelled
        } catch (problem: Exception) {
            if (watchRequest === selected && !closed) watchFailed(FindWatchStatus.FAILED, problem.javaClass.simpleName)
        }
    }
    private suspend fun sendWatchStop(selected: WatchRequest) {
        if (session === selected.owner) watchRoute?.let { target ->
            selected.owner.sendMessage(WatchFindingProtocol.STOP_PATH, WatchFindingProtocol.stop(selected.id), target)
        }
    }
    private fun awaitWatchResponse(selected: WatchRequest, stopping: Boolean) {
        watchResponseTimer?.cancel()
        watchResponseTimer = worker.launch { delay(watchResponseMillis); mutex.withLock {
            watchResponseTimer = null
            if (watchRequest !== selected || selected.terminal || selected.stopping != stopping) return@withLock
            if (!stopping && mutableState.value.watchRingingConfirmed) return@withLock
            if (!stopping) {
                selected.stopping = true
                // A delayed Activity launch must also receive cancellation, even if its feedback was lost.
                try { sendWatchStop(selected) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (problem: Exception) { log("watch_timeout_stop_failed", mapOf("exception" to problem.javaClass.simpleName)) }
            }
            watchFailed(FindWatchStatus.TIMED_OUT, if (stopping) "watch_stop_not_acknowledged" else "watch_start_not_acknowledged")
        } }
    }
    private fun receiveWatchLaunch(bytes: ByteArray): Boolean {
        val selected = watchRequest?.takeIf { !it.terminal && !it.stopping } ?: return false
        try {
            val result = NotificationRpcCodec.decodeResult(bytes)
            if (result.invocationId != selected.id) return false
            if (result.notices.isNotEmpty()) log("watch_launch_result_notices", mapOf("count" to result.notices.size.toString()))
            if (result.successful == false && mutableState.value.watchStatus == FindWatchStatus.STARTING) {
                selected.terminal = true
                watchTimer?.cancel(); watchTimer = null
                watchResponseTimer?.cancel(); watchResponseTimer = null
                mutableState.update { it.copy(watchStopAvailable = false) }
                watchFailed(FindWatchStatus.UNAVAILABLE, "watch_activity_rejected")
            } else log("watch_launch_result", mapOf("successful" to result.successful.toString(),
                "decision" to "activity launch is not playback confirmation"))
        } catch (problem: Exception) { log("watch_launch_result_unreadable", mapOf("exception" to problem.javaClass.simpleName)) }
        return true
    }
    private suspend fun receiveWatch(bytes: ByteArray): Boolean {
        val selected = watchRequest?.takeIf { !it.terminal } ?: return false
        try {
            val fields = WatchFindingProtocol.parse(bytes)
            if (WatchFindingProtocol.text(fields, "requestId") != selected.id) return false
            val status = WatchFindingProtocol.text(fields, "state")
            val audio = WatchFindingProtocol.flag(fields, "audioPlaybackStarted")
            val vibration = WatchFindingProtocol.flag(fields, "vibrationRequested")
            log("watch_state_received", mapOf("state" to (status ?: "absent"),
                "audioPlaybackStarted" to audio.toString(), "vibrationRequested" to vibration.toString(),
                "version" to (WatchFindingProtocol.text(fields, "version") ?: "absent"),
                "fields" to fields.size.toString(), "reason" to (WatchFindingProtocol.text(fields, "reason") ?: "none")))
            when (status) {
                "starting", "ringing" -> {
                    if (selected.stopping) { sendWatchStop(selected); return true }
                    val previous = mutableState.value
                    val playingAudio = audio ?: previous.watchAudioPlaybackStarted
                    val vibrating = vibration ?: previous.watchVibrationRequested
                    val playing = playingAudio || vibrating
                    mutableState.update { it.copy(watchStatus = if (playing) FindWatchStatus.RINGING else FindWatchStatus.STARTING,
                        watchRingingConfirmed = playing, watchAudioPlaybackStarted = playingAudio, watchVibrationRequested = vibrating) }
                    if (playing) { watchResponseTimer?.cancel(); watchResponseTimer = null }
                }
                "stopped", "failed" -> {
                    selected.terminal = status == "stopped"
                    watchResponseTimer?.cancel(); watchResponseTimer = null
                    if (selected.terminal) { watchTimer?.cancel(); watchTimer = null }
                    mutableState.update { it.copy(watchStatus = if (status == "stopped") FindWatchStatus.STOPPED else FindWatchStatus.FAILED,
                        watchRingingConfirmed = false, watchAudioPlaybackStarted = false, watchVibrationRequested = false,
                        watchStopAvailable = !selected.terminal,
                        lastIssue = if (status == "failed") FindDevicesIssue(FindDevicesIssueKind.WATCH_FIND,
                            WatchFindingProtocol.text(fields, "reason") ?: "watch_playback_failed") else null) }
                }
                else -> log("watch_state_unrecognized", mapOf("decision" to "keep waiting for usable playback feedback"))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) { log("watch_state_unreadable", mapOf("exception" to problem.javaClass.simpleName)) }
        return true
    }
    private fun watchFailed(status: FindWatchStatus, reason: String) {
        mutableState.update { it.copy(watchStatus = status, lastIssue = FindDevicesIssue(FindDevicesIssueKind.WATCH_FIND, reason)) }
        log("watch_find_failed", mapOf("status" to status.name, "reason" to reason))
    }
    private fun retireWatch() {
        watchTimer?.cancel(); watchTimer = null
        watchResponseTimer?.cancel(); watchResponseTimer = null
        watchRequest?.terminal = true; watchRequest = null
    }
    private suspend fun publishCapability(current: CompanionFeatureSession, enabled: Boolean) {
        val uri = "wear://${current.scope.localNodeId}$CAPABILITY_PATH"
        try {
            if (enabled) data.publish(current.scope, GMS_ROUTE, DataItemDraft(uri, byteArrayOf(0x73)))
            else data.delete(current.scope, GMS_ROUTE, uri)
            capabilityReconciledSession = current.sessionId
            mutableState.update { it.copy(capabilityPublished = enabled) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) { log("capability_publish_failed", mapOf("exception" to problem.javaClass.simpleName)) }
    }
    private suspend fun send(current: CompanionFeatureSession, path: String) {
        if (session !== current) return
        try { current.sendMessage(path, byteArrayOf(), GMS_ROUTE) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) {
            mutableState.update { it.copy(lastIssue = FindDevicesIssue(FindDevicesIssueKind.RING_REPLY, problem.javaClass.simpleName)) }
            log("ring_reply_failed", mapOf("path" to path, "exception" to problem.javaClass.simpleName))
        }
    }
    private fun log(event: String, details: Map<String, String> = emptyMap()) {
        runCatching { diagnostics.record(ProtocolDiagnostic("finding", event, peer = session?.peer,
            sessionId = session?.sessionId ?: 0, details = details)) }
    }
    override fun close() {
        if (closed) return
        closed = true; timer?.cancel(); retireWatch(); lifetime.cancel(); ringer.close()
        mutableState.update { it.copy(sessionId = 0, capabilityPublished = false, phone = PhoneRingingState(), watchStatus = FindWatchStatus.DISCONNECTED,
            watchRingingConfirmed = false, watchAudioPlaybackStarted = false, watchVibrationRequested = false, watchStopAvailable = false) }
    }
    companion object {
        val GMS_ROUTE = ApplicationRoute("com.google.android.gms", "38918a453d07199354f8b19af05ec6562ced5788")
        const val ACTION_RING = "com.google.android.gms.mdm.ACTION_RING"
        const val ACTION_CANCEL_RING = "com.google.android.gms.mdm.ACTION_CANCEL_RING"
        const val RING_STARTED = "com.google.android.gms.mdm.RING_STARTED"
        const val RING_CANCELED = "com.google.android.gms.mdm.RING_CANCELED"
        const val CAPABILITY_PATH = "/capabilities/com.google.android.gms/38918a453d07199354f8b19af05ec6562ced5788/mdm%252Fringable"
        val INCOMING_PATHS = setOf(ACTION_RING, ACTION_CANCEL_RING)
    }
}

private fun PhoneRingingState.soundIssue() = failure?.let { FindDevicesIssue(FindDevicesIssueKind.PHONE_SOUND, it) }
