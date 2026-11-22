package dev.ticbox.companion.engine.media

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.CompanionFeatureSession
import dev.ticbox.companion.protocol.*
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class MediaControlsConfig(val enableV2: Boolean = true, val maxImageBytes: Int = 4 * 1024 * 1024, val maxImages: Int = 128,
    val v2BarrierTimeoutMillis: Long = 1500) {
    init { require(maxImageBytes > 0 && maxImages > 0 && v2BarrierTimeoutMillis > 0) }
}

/** One persistent media DataItem for the chosen native session, with no invented command ACK. */
class MediaControlsEngine(
    parentScope: CoroutineScope,
    private val data: DataSyncClient,
    private val source: MediaSource,
    private val settingsStore: MediaSettingsStore = MemoryMediaSettingsStore(),
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val config: MediaControlsConfig = MediaControlsConfig(),
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val assetRetirement: MediaAssetRetirement = MediaAssetRetirement.NONE,
) : MediaControlsClient {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val worker = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val mutableState = MutableStateFlow(MediaControlsState())
    override val state: StateFlow<MediaControlsState> = mutableState.asStateFlow()
    private val operations = Mutex()
    private val sessionLock = Any()
    private val settingsLock = Mutex()
    private val dirty = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var closed = false
    @Volatile private var settingsReady = false
    @Volatile private var active: Run? = null
    @Volatile private var selected: PhoneMediaSession? = null
    private class Run(val session: CompanionFeatureSession, @Volatile var supportsV2: Boolean, val job: Job, parentContext: CoroutineContext) {
        val worker = CoroutineScope(parentContext + job)
        var fresh = true
        var fingerprint: String? = null
        var sequence: Long? = null
        var v2Job: Job? = null
        @Volatile var v2Sequence: Long? = null
    }

    init {
        worker.launch {
            settingsLock.withLock {
                try { mutableState.update { it.copy(settings = settingsStore.load()) } }
                catch (problem: Exception) { issue(MediaControlsIssue.STORAGE, "settings_restore_failed", problem) }
                settingsReady = true
            }
            invalidate()
        }
        worker.launch { source.state.collect { mutableState.update { old -> old.copy(accessGranted = it.accessGranted) }; invalidate() } }
        worker.launch { source.events.collect { invalidate() } }
        worker.launch { data.state.map { Triple(it.phase, it.scope, it.sessionId) }.distinctUntilChanged().collect { invalidate() } }
        worker.launch {
            for (ignored in dirty) operations.withLock {
                val run = active
                if (!settingsReady || run == null || !ready(run)) { updatePhase(); return@withLock }
                try { inRun(run) { reconcile(run) } }
                catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive() }
                catch (problem: Exception) { issue(MediaControlsIssue.PUBLISH, "reconcile_failed", problem) }
            }
        }
    }

    fun attach(session: CompanionFeatureSession, supportsV2: Boolean = false) {
        val previous = synchronized(sessionLock) { alive(); val old = active; active = Run(session, supportsV2, SupervisorJob(lifetime), worker.coroutineContext); old }
        previous?.job?.cancel()
        mutableState.update { it.copy(sessionId = session.sessionId, published = false) }
        invalidate()
    }

    fun detach(sessionId: Long) {
        val run = synchronized(sessionLock) { active?.takeIf { it.session.sessionId == sessionId }?.also { active = null } } ?: return
        run.job.cancel()
        mutableState.update { it.copy(sessionId = 0, published = false) }
        updatePhase()
        // Persist a tombstone while offline. A replacement run publishes after this same mutex,
        // and a queued cleanup cannot remove a new run's already committed mirror.
        worker.launch { operations.withLock {
            if (active?.session?.scope == run.session.scope) return@withLock
            try { withdraw(run.session, null) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Exception) { log("disconnect_withdraw_failed", mapOf("exception" to problem.javaClass.simpleName)) }
        } }
    }

    /** Capability discovery can arrive after the physical session was established. */
    fun setSupportsV2(sessionId: Long, enabled: Boolean) {
        val run = active?.takeIf { it.session.sessionId == sessionId } ?: return
        if (run.supportsV2 == enabled) return
        run.supportsV2 = enabled; run.v2Sequence = null
        if (!enabled) run.v2Job?.cancel()
        invalidate()
    }

    suspend fun handleMessage(peer: PeerAddress, sessionId: Long, path: String, payload: ByteArray, route: ApplicationRoute? = null): Boolean {
        if (path != MediaProtocol.PATH) return false
        val run = active?.takeIf { it.session.peer == peer && it.session.sessionId == sessionId &&
            (route == null || route == it.session.companionRoute) } ?: return false
        val command = try { MediaCodec.decodeCommand(payload) }
        catch (problem: Exception) { issue(MediaControlsIssue.MALFORMED_MESSAGE, "command_decode_failed", problem); return true }
        if (command.notices.isNotEmpty()) log("command_compatibility_notices", mapOf("count" to command.notices.size.toString()))
        operations.withLock {
            current(run)
            mutableState.update { it.copy(receivedCommands = it.receivedCommands + 1) }
            val count = MediaProtocol.operationCount(command)
            if (count == 0) { log("unknown_or_empty_command", mapOf("fields" to command.fields.size.toString())); return@withLock }
            val target = selected?.takeIf { state.value.settings.enabled && source.state.value.available }
            val result = if (target == null) MediaCommandOutcome(count, 0) else try {
                source.execute(target.summary.id, command)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Exception) { log("command_execution_failed", mapOf("exception" to problem.javaClass.simpleName)); MediaCommandOutcome(count, 0) }
            current(run)
            mutableState.update { it.copy(acceptedOperations = it.acceptedOperations + result.accepted,
                failedOperations = it.failedOperations + result.failed,
                lastIssue = if (result.failed > 0) MediaControlsIssue.COMMAND else it.lastIssue) }
            log("command_dispatched", mapOf("attempted" to result.attempted.toString(), "accepted" to result.accepted.toString()))
            // Source callbacks and this authoritative rescan publish actual state after the action.
            invalidate()
        }
        return true
    }

    override suspend fun setEnabled(enabled: Boolean) = updateSettings { it.copy(enabled = enabled) }
    override suspend fun setPreferredPackage(packageName: String?) = updateSettings { it.copy(preferredPackage = packageName?.takeIf(String::isNotBlank)) }
    override suspend fun setAutoLaunch(enabled: Boolean) = updateSettings { it.copy(autoLaunch = enabled) }
    override suspend fun resync() { alive(); active?.v2Sequence = null; invalidate() }
    private suspend fun updateSettings(change: (MediaControlsSettings) -> MediaControlsSettings) {
        settingsLock.withLock { alive(); val next = change(state.value.settings); settingsStore.save(next); mutableState.update { it.copy(settings = next) } }
        invalidate()
    }

    private suspend fun reconcile(run: Run) {
        current(run)
        val settings = state.value.settings
        if (!settings.enabled || !source.state.value.accessGranted) {
            withdraw(run.session, run)
            selected = null; run.fingerprint = null; run.sequence = null
            mutableState.update { it.copy(published = false, selectedSessionId = null, sessions = emptyList(),
                phase = if (!settings.enabled) MediaControlsPhase.DISABLED else MediaControlsPhase.ACCESS_REQUIRED) }
            return
        }
        if (!source.state.value.available) { updatePhase(); return }
        val sessions = try { source.snapshot() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) { issue(MediaControlsIssue.SOURCE, "snapshot_failed", problem); return }
        current(run)
        if (!source.state.value.available) { updatePhase(); return }
        val preferred = sessions.filter { it.summary.packageName == settings.preferredPackage }
        val candidate = (preferred.ifEmpty { sessions }).let { values -> values.firstOrNull { it.summary.playing } ?: values.firstOrNull() }
        selected = candidate
        mutableState.update { it.copy(sessions = sessions.map { value -> value.summary }, selectedSessionId = candidate?.summary?.id) }
        if (candidate == null) {
            withdraw(run.session, run); run.fingerprint = null; run.sequence = null
            mutableState.update { it.copy(phase = MediaControlsPhase.IDLE, published = false) }
            return
        }
        val base = candidate.content.copy(fields = candidate.content.fields - setOf("elapsed_time", "artwork_data_elapsed_time") +
            ("mediacontrols.watch_app_auto_launch_enabled" to DataMapValue.Bool(settings.autoLaunch)))
        val encoded = MediaCodec.encode(base, NotificationCodecLimits(maxAssets = config.maxImages, maxAssetBytes = config.maxImageBytes))
        val digests = encoded.assets.map { it.data?.let(DataProtocol::digest) ?: it.digest }
        if (digests.any { it.isNullOrEmpty() }) { issue(MediaControlsIssue.RESOURCE_LIMIT, "asset_has_no_bytes_or_digest"); return }
        val fingerprint = hash(encoded.data + digests.joinToString("\u0000").toByteArray(Charsets.UTF_8))
        val previous = record(run.session)
        if (previous != null && !previous.deleted && run.fingerprint == fingerprint && run.sequence == previous.sequenceId) {
            mutableState.update { it.copy(phase = MediaControlsPhase.ACTIVE, published = true) }
            if (run.supportsV2 && run.v2Sequence != previous.sequenceId) {
                val priorFields = previous.data?.let { MediaCodec.decode(it).document.values }.orEmpty()
                val elapsed = (priorFields["elapsed_time"] as? DataMapValue.LongNumber)?.value ?: elapsedMillis()
                val content = base.copy(fields = base.fields + ("elapsed_time" to DataMapValue.LongNumber(elapsed)) +
                    ("mediacontrols.watch_app_auto_launch_enabled" to (priorFields["mediacontrols.watch_app_auto_launch_enabled"] ?: DataMapValue.Bool(false))))
                scheduleV2(run, content, previous.sequenceId, elapsed)
            }
            return
        }
        val references = encoded.assets.mapIndexed { index, asset ->
            current(run)
            val digest = asset.data?.let { data.putAsset(run.session.scope, run.session.companionRoute, it).digest } ?: checkNotNull(digests[index])
            DataAssetReference(asset.slot, digest, asset.type)
        }
        val lastElapsed = previous?.data?.let { runCatching { (MediaCodec.decode(it).document.values["elapsed_time"] as? DataMapValue.LongNumber)?.value }.getOrNull() }
        val elapsed = maxOf(elapsedMillis(), lastElapsed?.takeIf { it < Long.MAX_VALUE }?.plus(1) ?: 0)
        val content = base.copy(fields = base.fields + mapOf("elapsed_time" to DataMapValue.LongNumber(elapsed),
            "mediacontrols.watch_app_auto_launch_enabled" to DataMapValue.Bool(settings.autoLaunch && !run.fresh)))
        current(run)
        val item = data.publish(run.session.scope, run.session.companionRoute, DataItemDraft(uri(run.session), MediaCodec.encode(content).data, references))
        current(run)
        run.fingerprint = fingerprint; run.sequence = item.sequenceId; run.fresh = false
        mutableState.update { it.copy(phase = MediaControlsPhase.ACTIVE, published = true) }
        retire(run.session, previous?.assets.orEmpty().map { it.digest }.toSet() - references.map { it.digest }.toSet(), run)
        scheduleV2(run, content, item.sequenceId, elapsed)
    }

    private fun scheduleV2(run: Run, content: MediaContent, sequence: Long, elapsed: Long) {
        if (config.enableV2 && run.supportsV2 && run.v2Sequence != sequence) {
            val bundle = content.maps["mediacontrols.wear_media_bundle"]
            if (bundle != null && MediaCodec.encode(bundle).data.size >= 1000) { log("v2_wear_bundle_budget"); return }
            val fastContent = content.copy(fields = content.fields +
                ("elapsed_time" to DataMapValue.LongNumber(if (elapsed < Long.MAX_VALUE) elapsed + 1 else elapsed)) +
                ("artwork_data_elapsed_time" to DataMapValue.LongNumber(elapsed)))
            // The v2 receiver requires the matching legacy session/Asset data to exist first,
            // and accepts only a strictly newer elapsed_time. Coalesce this optional fast path
            // without holding the native-command lock while the finite DataApi barrier waits.
            run.v2Job?.cancel()
            run.v2Sequence = sequence
            run.v2Job = run.worker.launch {
                try {
                    val barrier = data.awaitProgressBarrier(config.v2BarrierTimeoutMillis)
                    current(run)
                    if (!run.supportsV2 || barrier.status != DataSyncBarrierStatus.COMPLETE || run.sequence != sequence) {
                        log("v2_waiting_for_dataapi"); return@launch
                    }
                    run.session.sendMessage(MediaProtocol.V2_PATH, MediaV2Codec.encode(fastContent)); current(run)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (problem: Exception) { log("v2_message_unavailable", mapOf("exception" to problem.javaClass.simpleName)) }
            }
        }
    }

    private suspend fun record(session: CompanionFeatureSession): DataItemRecord? = data.items(session.scope,
        DataItemQuery(session.companionRoute, uri(session), includeDeleted = true)).items.firstOrNull { it.uri == uri(session) }
    private suspend fun withdraw(session: CompanionFeatureSession, run: Run?) {
        run?.let(::current)
        val prior = record(session)?.takeUnless { it.deleted } ?: return
        data.delete(session.scope, session.companionRoute, prior.uri)
        run?.let(::current)
        retire(session, prior.assets.map { it.digest }.toSet(), run)
    }
    private suspend fun retire(session: CompanionFeatureSession, digests: Set<String>, run: Run?) {
        if (digests.isEmpty()) return
        run?.let(::current)
        try { assetRetirement.retire(session.scope, session.companionRoute, digests) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) { log("asset_retirement_failed", mapOf("exception" to problem.javaClass.simpleName, "candidates" to digests.size.toString())) }
        run?.let(::current)
    }
    private fun uri(session: CompanionFeatureSession) = "wear://${session.scope.localNodeId}${MediaProtocol.PATH}"
    private fun ready(run: Run) = data.state.value.let { it.phase == DataSyncPhase.ACTIVE && it.scope == run.session.scope && it.sessionId == run.session.sessionId }
    private fun invalidate() { if (!closed) dirty.trySend(Unit) }
    private fun current(run: Run) { if (closed || active !== run || !run.job.isActive) throw CancellationException("Media session ended") }
    private suspend fun <T> inRun(run: Run, action: suspend () -> T): T = coroutineScope {
        current(run); val job = currentCoroutineContext().job
        val registration = run.job.invokeOnCompletion { job.cancel(CancellationException("Media session ended")) }
        try { action() } finally { registration.dispose() }
    }
    private fun updatePhase() { mutableState.update { it.copy(phase = when {
        closed -> MediaControlsPhase.CLOSED
        !it.settings.enabled -> MediaControlsPhase.DISABLED
        !source.state.value.accessGranted -> MediaControlsPhase.ACCESS_REQUIRED
        !source.state.value.available -> MediaControlsPhase.SOURCE_UNAVAILABLE
        active?.let(::ready) != true -> MediaControlsPhase.DISCONNECTED
        selected == null -> MediaControlsPhase.IDLE
        else -> MediaControlsPhase.ACTIVE
    }) } }
    private fun issue(kind: MediaControlsIssue, event: String, problem: Exception? = null) {
        mutableState.update { it.copy(phase = MediaControlsPhase.FAILED, lastIssue = kind,
            published = if (kind == MediaControlsIssue.PUBLISH || kind == MediaControlsIssue.RESOURCE_LIMIT) false else it.published) }
        log(event, problem?.let { mapOf("exception" to it.javaClass.simpleName) }.orEmpty())
    }
    private fun log(event: String, details: Map<String, String> = emptyMap()) { runCatching {
        diagnostics.record(ProtocolDiagnostic("media", event, peer = active?.session?.peer, sessionId = active?.session?.sessionId ?: 0, details = details))
    } }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun alive() { if (closed) throw BackendClosedException() }
    override fun close() {
        if (closed) return
        synchronized(sessionLock) { if (closed) return; closed = true; active?.job?.cancel(); active = null }
        lifetime.cancel(); dirty.close()
        mutableState.update { it.copy(phase = MediaControlsPhase.CLOSED, sessionId = 0, published = false) }
    }
}
