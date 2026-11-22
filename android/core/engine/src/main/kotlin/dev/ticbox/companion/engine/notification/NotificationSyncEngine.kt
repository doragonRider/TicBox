package dev.ticbox.companion.engine.notification

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A writer already bound to one physical companion session. */
class NotificationSession(
    val sessionId: Long,
    val scope: DataScope,
    val route: ApplicationRoute,
    private val send: suspend (String, ByteArray) -> Unit,
) {
    suspend fun sendMessage(path: String, bytes: ByteArray) = send(path, bytes)
}

data class NotificationSyncConfig(val remoteQueueCapacity: Int = 64, val actionCacheEntries: Int = 256, val actionCacheMillis: Long = 60_000,
    val dismissalCacheEntries: Int = 512) {
    init { require(remoteQueueCapacity > 0 && actionCacheEntries > 0 && actionCacheMillis > 0 && dismissalCacheEntries > 0) }
}

/** Persistent notification content uses DataApi; MessageApi carries actions and their real results. */
class NotificationSyncEngine(
    parentScope: CoroutineScope,
    private val data: DataSyncClient,
    private val source: NotificationSource,
    private val settingsStore: NotificationSettingsStore = MemoryNotificationSettingsStore(),
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val config: NotificationSyncConfig = NotificationSyncConfig(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val revisionStore: NotificationRevisionStore = MemoryNotificationRevisionStore(),
    private val assetRetirement: NotificationAssetRetirement = NotificationAssetRetirement.NONE,
) : NotificationSyncClient {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val worker = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val mutableState = MutableStateFlow(NotificationSyncState())
    override val state = mutableState.asStateFlow()
    private val settingsLock = Mutex()
    private val actionLock = Mutex()
    private val lock = Any()
    private val dirty = Channel<Unit>(Channel.CONFLATED)
    private val remoteItems = Channel<RemoteDeletion>(config.remoteQueueCapacity)
    @Volatile private var closed = false
    @Volatile private var readySettings = false
    @Volatile private var active: Run? = null
    @Volatile private var latestSnapshot: Map<String, PhoneNotification> = emptyMap()
    // DataApi's APPLIED observer can snapshot these small records from its own reader worker.
    private val published = ConcurrentHashMap<ItemIdentity, Published>()
    private val suppressed = linkedMapOf<Pair<DataScope, String>, Long>()
    private val receipts = linkedMapOf<ActionIdentity, Receipt>()

    private data class ItemIdentity(val scope: DataScope, val route: ApplicationRoute, val uri: String)
    private data class Published(val fingerprint: String, val sourceFingerprint: String, val sequence: Long, val digests: Set<String>)
    private data class ActionIdentity(val scope: DataScope, val route: ApplicationRoute, val invocation: String, val fingerprint: String)
    private data class Receipt(val fingerprint: String, val outcome: NotificationActionOutcome, val at: Long)
    private class Run(val session: NotificationSession, val job: Job) {
        var fresh = true
        val dismissals = linkedSetOf<Pair<String, Long>>()
    }
    private data class RemoteDeletion(val run: Run, val uri: String, val sequence: Long, val key: String, val oldDigests: Set<String>)

    init {
        worker.launch(start = CoroutineStart.UNDISPATCHED) {
            settingsLock.withLock {
                try { mutableState.update { it.copy(settings = settingsStore.load()) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (problem: Exception) {
                    // An unreadable saved allowlist must never become an allow-all blocklist.
                    mutableState.update { it.copy(settings = NotificationSyncSettings(enabled = false, mode = NotificationFilterMode.ALLOWLIST)) }
                    issue(NotificationSyncIssue.STORAGE, "settings_restore_failed", problem)
                }
                readySettings = true
            }
            invalidate()
        }
        worker.launch {
            source.state.collect { observed ->
                mutableState.update { it.copy(accessGranted = observed.accessGranted, listenerConnected = observed.connected) }
                invalidate()
            }
        }
        worker.launch { source.events.collect { invalidate() } }
        worker.launch {
            data.state.map { Triple(it.phase, it.scope, it.sessionId) }.distinctUntilChanged().collect { invalidate() }
        }
        worker.launch {
            while (isActive) select<Unit> {
                remoteItems.onReceive { removal ->
                    try { inRun(removal.run) { removeFromPhone(removal) } }
                    catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive() }
                    catch (problem: Exception) { issue(NotificationSyncIssue.ACTION_FAILED, "remote_dismiss_failed", problem) }
                    invalidate()
                }
                dirty.onReceive {
                    val run = active
                    if (run == null || !readySettings || !dataReady(run)) { updatePhase(); return@onReceive }
                    try { inRun(run) { reconcile(run) } }
                    catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive() }
                    catch (problem: Exception) { issue(NotificationSyncIssue.PUBLISH_FAILED, "reconcile_failed", problem) }
                }
            }
        }
    }

    fun attach(session: NotificationSession) {
        val run = Run(session, SupervisorJob(lifetime))
        val old = synchronized(lock) { alive(); val previous = active; active = run; previous }
        old?.job?.cancel(CancellationException("Notification session replaced"))
        mutableState.update { it.copy(sessionId = session.sessionId) }
        invalidate()
    }
    fun detach(sessionId: Long) {
        val old = synchronized(lock) { active?.takeIf { it.session.sessionId == sessionId }?.also { active = null } } ?: return
        old.job.cancel(CancellationException("Notification session disconnected"))
        mutableState.update { it.copy(sessionId = 0) }
        updatePhase()
    }

    /** Called only for newly APPLIED remote DataItems, never for local mutations or history scans. */
    fun offerRemoteItem(sessionId: Long, item: DataItemRecord): Boolean {
        val run = active ?: return false
        if (closed || run.session.sessionId != sessionId || item.scope != run.session.scope || item.route != run.session.route) return false
        if (!item.deleted || item.local || item.sourceNodeId != run.session.scope.remoteNodeId) return false
        val id = try { NotificationProtocol.parseStreamItemPath(item.uri) } catch (_: Exception) { null } ?: return false
        if (id.creatorNodeId != run.session.scope.localNodeId || !item.uri.startsWith("wear://${run.session.scope.localNodeId}/")) return false
        val key = id.notificationKey ?: return false
        // The durable tombstone has already replaced the old DataItem, so retain only known
        // published references here. Never infer old file identities from the tombstone body.
        val digests = published[ItemIdentity(item.scope, item.route, item.uri)]?.digests.orEmpty()
        if (remoteItems.trySend(RemoteDeletion(run, item.uri, item.sequenceId, key, digests)).isSuccess) return true
        issue(NotificationSyncIssue.ACTION_FAILED, "remote_dismiss_queue_full")
        return false
    }

    /** Ordinary MessageApi input. The caller may launch this on its own observer worker. */
    suspend fun handleMessage(peer: PeerAddress, sessionId: Long, path: String, payload: ByteArray): Boolean {
        if (path != NotificationProtocol.RPC_PATH) return false
        val run = active?.takeIf { it.session.sessionId == sessionId && it.session.scope.peer == peer } ?: return false
        val request = try { NotificationRpcCodec.decodeRequest(payload) }
        catch (problem: Exception) { issue(NotificationSyncIssue.MALFORMED_RPC, "action_decode_failed", problem); return true }
        request.notices.forEach { log("action_compatibility_notice", mapOf("kind" to it.code)) }
        if (!request.isBridgedAction) { log("unsupported_rpc_command", request.metadata); return true }
        actionLock.withLock {
            current(run)
            val fingerprint = actionFingerprint(request)
            val invocation = request.invocationId
            receipts.entries.removeAll { nowMillis() - it.value.at > config.actionCacheMillis }
            val identity = invocation?.let { ActionIdentity(run.session.scope, run.session.route, it, fingerprint) }
            val cached = identity?.let { receipts[it] }
            val outcome = if (cached != null) {
                mutableState.update { it.copy(duplicateActions = it.duplicateActions + 1) }; cached.outcome
            } else {
                val result = execute(request)
                if (identity != null) {
                    receipts[identity] = Receipt(fingerprint, result, nowMillis())
                    while (receipts.size > config.actionCacheEntries) receipts.remove(receipts.keys.first())
                }
                mutableState.update { if (result.successful) it.copy(actionsExecuted = it.actionsExecuted + 1)
                    else it.copy(actionsFailed = it.actionsFailed + 1, lastIssue = NotificationSyncIssue.ACTION_FAILED) }
                result
            }
            if (invocation != null) {
                // Native execution already dispatched cannot be undone by a Bluetooth disconnect.
                // Keep its actual outcome for a short same-peer retry, but never send on a new session.
                if (active !== run || closed) { log("action_result_session_ended"); return@withLock }
                try { run.session.sendMessage(NotificationProtocol.RESULT_PATH, NotificationRpcCodec.encodeResult(invocation, outcome.successful)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (problem: Exception) { issue(NotificationSyncIssue.RESULT_SEND_FAILED, "action_result_send_failed", problem) }
            } else log("action_has_no_callback")
        }
        return true
    }

    override suspend fun setEnabled(enabled: Boolean) = updateSettings { it.copy(enabled = enabled) }
    override suspend fun setPackageEnabled(packageName: String, enabled: Boolean) = updateSettings {
        if (it.mode == NotificationFilterMode.ALLOWLIST)
            it.copy(allowedPackages = if (enabled) it.allowedPackages + packageName else it.allowedPackages - packageName)
        else it.copy(blockedPackages = if (enabled) it.blockedPackages - packageName else it.blockedPackages + packageName)
    }
    override suspend fun setPackageFilter(mode: NotificationFilterMode, packages: Set<String>) = updateSettings {
        if (mode == NotificationFilterMode.ALLOWLIST) it.copy(mode = mode, allowedPackages = packages.toSet())
        else it.copy(mode = mode, blockedPackages = packages.toSet())
    }
    override suspend fun setSoundEnabled(enabled: Boolean) = updateSettings { it.copy(soundEnabled = enabled) }
    override suspend fun setVibrationEnabled(enabled: Boolean) = updateSettings { it.copy(vibrationEnabled = enabled) }
    override suspend fun setPackageSoundMode(packageName: String, mode: NotificationAlertMode) = updateAppAlerts(packageName) { it.copy(sound = mode) }
    override suspend fun setPackageVibrationMode(packageName: String, mode: NotificationAlertMode) = updateAppAlerts(packageName) { it.copy(vibration = mode) }
    override suspend fun setPackageAlertSettings(packageName: String, settings: NotificationAppAlertSettings) = updateAppAlerts(packageName) { settings }
    private suspend fun updateAppAlerts(packageName: String, change: (NotificationAppAlertSettings) -> NotificationAppAlertSettings) {
        require(NotificationAppAlertSettings.isValidPackageName(packageName)) { "invalid_package_name" }
        updateSettings {
            val next = change(it.alertsFor(packageName))
            val overrides = if (next == NotificationAppAlertSettings()) it.appAlerts - packageName else it.appAlerts + (packageName to next)
            require(overrides.size <= NotificationAppAlertSettings.MAX_PACKAGES) { "notification_alert_settings_limit" }
            it.copy(appAlerts = overrides)
        }
    }
    override suspend fun resync() { alive(); invalidate() }
    private suspend fun updateSettings(change: (NotificationSyncSettings) -> NotificationSyncSettings) {
        settingsLock.withLock {
            alive(); val next = change(state.value.settings)
            try { settingsStore.save(next) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Exception) { issue(NotificationSyncIssue.STORAGE, "settings_save_failed", problem); throw problem }
            mutableState.update { it.copy(settings = next, lastIssue = it.lastIssue.takeUnless { issue -> issue == NotificationSyncIssue.STORAGE }) }
        }
        invalidate()
    }

    private suspend fun reconcile(run: Run) {
        current(run)
        val existing = records(run).associateBy { it.uri }
        if (!state.value.settings.enabled) {
            var pending = 0
            existing.values.filter { !it.deleted && it.local }.forEach { record ->
                current(run)
                try {
                    data.delete(run.session.scope, run.session.route, record.uri)
                    retireAssets(run, record.assets.map { it.digest }.toSet())
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (problem: Exception) { pending++; issue(NotificationSyncIssue.PUBLISH_FAILED, "notification_withdraw_failed", problem) }
            }
            published.entries.removeAll { it.key.scope == run.session.scope && it.key.route == run.session.route }
            mutableState.update { it.copy(phase = NotificationSyncPhase.DISABLED, mirroredNotificationCount = 0, filteredNotificationCount = 0, pendingRemovalCount = pending) }
            return
        }
        if (!source.state.value.available) { updatePhase(); return }
        mutableState.update { it.copy(phase = NotificationSyncPhase.RECONCILING) }
        val snapshot = try { source.snapshot() } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) { issue(NotificationSyncIssue.SNAPSHOT_FAILED, "snapshot_failed", problem); return }
        current(run)
        if (!source.state.value.available) { issue(NotificationSyncIssue.SOURCE_UNAVAILABLE, "snapshot_became_unavailable"); return }
        latestSnapshot = snapshot.associateBy { it.key }
        suppressed.entries.removeAll { it.key.first == run.session.scope && it.key.second !in latestSnapshot }
        val settings = state.value.settings
        val desired = latestSnapshot.values.filter { notification ->
            val key = run.session.scope to notification.key
            suppressed[key]?.takeIf { it != notification.postTimeMillis }?.let { suppressed.remove(key) }
            settings.allows(notification.packageName) && !notification.localOnly && suppressed[key] != notification.postTimeMillis
        }
        val desiredUris = mutableSetOf<String>()
        var publishedCount = 0
        var failedCount = 0
        var pendingRemovals = 0
        for (notification in desired) {
            current(run)
            val uri = uri(run, notification)
            desiredUris += uri
            try {
                val original = notification.content.copy(fields = notification.content.fields - "revision" + ("post_time_millis" to DataMapValue.LongNumber(notification.postTimeMillis)))
                val originalEncoded = NotificationCodec.encode(original)
                val content = NotificationAlertRules.apply(original, notification.packageName, settings)
                val encoded = if (content === original) originalEncoded else NotificationCodec.encode(content)
                encoded.notices.forEach { log("content_compatibility_notice", mapOf("kind" to it.code)) }
                val digests = encoded.assets.map { asset -> asset.data?.let(DataProtocol::digest) ?: asset.digest }
                if (digests.any { it == null }) {
                    failedCount++
                    issue(NotificationSyncIssue.ASSET_UNAVAILABLE, "notification_asset_has_no_file_or_digest")
                    continue
                }
                val fingerprint = fingerprint(encoded.data, digests.filterNotNull())
                val sourceFingerprint = if (content === original) fingerprint else fingerprint(originalEncoded.data, digests.filterNotNull())
                val identity = ItemIdentity(run.session.scope, run.session.route, uri)
                val prior = existing[uri]?.takeUnless { it.deleted }
                val remembered = published[identity]
                val same = prior != null && ((remembered?.sequence == prior.sequenceId && remembered.fingerprint == fingerprint) ||
                    (prior.data?.contentEquals(encoded.data) == true && prior.assets.map { it.digest } == digests))
                val needsQuietReplay = run.fresh && prior != null && !isQuiet(prior)
                if (same && !needsQuietReplay) {
                    published[identity] = Published(fingerprint, sourceFingerprint, prior.sequenceId, prior.assets.map { it.digest }.toSet())
                    publishedCount++; continue
                }
                // A settings-only rewrite of this already published source version must not
                // alert again. Keep the unquiet fingerprint so a later rescan cannot re-arm it.
                val policyOnly = prior != null && remembered?.sequence == prior.sequenceId && remembered.sourceFingerprint == sourceFingerprint
                val quietUpdate = run.fresh || policyOnly
                val references = encoded.assets.mapIndexed { index, asset ->
                    current(run)
                    val digest = asset.data?.let { data.putAsset(run.session.scope, run.session.route, it).digest } ?: checkNotNull(digests[index])
                    DataAssetReference(asset.slot, digest, asset.type)
                }
                val priorRevision = prior?.data?.let { runCatching { (NotificationCodec.decode(it).fields["revision"] as? DataMapValue.LongNumber)?.value }.getOrNull() }
                val revision = if (quietUpdate && priorRevision != null) priorRevision else revisionStore.next()
                val revised = content.copy(fields = content.fields + ("revision" to DataMapValue.LongNumber(revision)))
                val outgoing = if (quietUpdate) quiet(revised) else revised
                current(run)
                val stored = data.publish(run.session.scope, run.session.route,
                    DataItemDraft(uri, NotificationCodec.encode(outgoing).data, references, lastModifiedMillis = nowMillis()))
                published[identity] = Published(fingerprint, sourceFingerprint, stored.sequenceId, references.map { it.digest }.toSet())
                publishedCount++
                log("notification_local_write", mapOf("operation" to "publish", "sequenceId" to stored.sequenceId.toString(),
                    "assetCount" to references.size.toString(), "quietReplay" to quietUpdate.toString()))
                retireAssets(run, prior?.assets.orEmpty().map { it.digest }.toSet() - references.map { it.digest }.toSet())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Exception) { failedCount++; issue(NotificationSyncIssue.PUBLISH_FAILED, "notification_publish_failed", problem) }
        }
        for (record in existing.values) {
            if (!record.deleted && record.local && record.uri !in desiredUris) {
                current(run)
                try {
                    data.delete(run.session.scope, run.session.route, record.uri)
                    published.remove(ItemIdentity(run.session.scope, run.session.route, record.uri))
                    retireAssets(run, record.assets.map { it.digest }.toSet())
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (problem: Exception) { failedCount++; pendingRemovals++; issue(NotificationSyncIssue.PUBLISH_FAILED, "notification_withdraw_failed", problem) }
            }
        }
        published.entries.removeAll { it.key.scope != run.session.scope || it.key.route != run.session.route || it.key.uri !in desiredUris }
        run.fresh = false
        mutableState.update { it.copy(phase = if (failedCount == 0) NotificationSyncPhase.ACTIVE else NotificationSyncPhase.FAILED,
            activeNotificationCount = latestSnapshot.size, mirroredNotificationCount = publishedCount,
            filteredNotificationCount = latestSnapshot.size - desired.size, pendingRemovalCount = pendingRemovals) }
    }

    private suspend fun retireAssets(run: Run, candidates: Set<String>) {
        val digests = candidates.filterTo(linkedSetOf()) { it.isNotEmpty() }
        if (digests.isEmpty()) return
        current(run)
        try { assetRetirement.retire(run.session.scope, run.session.route, digests) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) {
            // Publication/deletion has already committed. Cache cleanup failure cannot undo it.
            log("notification_asset_retirement_failed", mapOf("exception" to problem.javaClass.simpleName, "candidates" to digests.size.toString()))
        }
        current(run)
    }

    private suspend fun records(run: Run): List<DataItemRecord> {
        val result = mutableListOf<DataItemRecord>(); var offset = 0
        while (true) {
            current(run)
            val page = data.items(run.session.scope, DataItemQuery(run.session.route, "wear://${run.session.scope.localNodeId}${NotificationProtocol.STREAM_PREFIX}", false, offset, 100))
            result += page.items.filter { NotificationProtocol.parseStreamItemPath(it.uri)?.creatorNodeId == run.session.scope.localNodeId }
            offset = page.nextOffset ?: return result
        }
    }

    private suspend fun removeFromPhone(removal: RemoteDeletion) {
        val run = removal.run
        if (!run.dismissals.add(removal.uri to removal.sequence)) return
        while (run.dismissals.size > config.dismissalCacheEntries) run.dismissals.remove(run.dismissals.first())
        if (!state.value.settings.enabled || !source.state.value.available) return
        val snapshot = source.snapshot().associateBy { it.key }
        current(run)
        val notification = snapshot[removal.key]
        if (notification == null) {
            published.remove(ItemIdentity(run.session.scope, run.session.route, removal.uri))
            retireAssets(run, removal.oldDigests)
            return
        }
        if (notification.localOnly || !state.value.settings.allows(notification.packageName)) return
        val accepted = source.dismiss(notification.key)
        current(run)
        if (accepted) {
            suppressed[run.session.scope to notification.key] = notification.postTimeMillis
            while (suppressed.size > config.dismissalCacheEntries) suppressed.remove(suppressed.keys.first())
            published.remove(ItemIdentity(run.session.scope, run.session.route, removal.uri))
            retireAssets(run, removal.oldDigests)
        }
        log(if (accepted) "remote_dismiss_accepted" else "remote_dismiss_not_accepted")
        if (!accepted) issue(NotificationSyncIssue.ACTION_FAILED, "remote_dismiss_not_accepted")
    }

    private suspend fun execute(request: NotificationActionRequest): NotificationActionOutcome {
        if (!request.isBridgedAction) return NotificationActionOutcome(false, NotificationActionFailure.UNSUPPORTED)
        val intentId = request.intentId ?: return NotificationActionOutcome(false, NotificationActionFailure.UNKNOWN_ACTION)
        if (!state.value.settings.enabled || !source.state.value.available) return NotificationActionOutcome(false, NotificationActionFailure.UNAVAILABLE)
        val owner = latestSnapshot.values.firstOrNull { ownsIntent(it.content, intentId) }
        val hinted = request.remoteStreamItemId?.let { NotificationProtocol.parseStreamItemPath(it) }
        if (owner != null && (owner.localOnly || !state.value.settings.allows(owner.packageName))) return NotificationActionOutcome(false, NotificationActionFailure.UNAVAILABLE)
        if (owner == null && hinted != null && !state.value.settings.allows(hinted.packageName)) return NotificationActionOutcome(false, NotificationActionFailure.UNAVAILABLE)
        if (owner != null && hinted?.notificationKey != null && hinted.notificationKey != owner.key) log("action_stream_hint_differs_from_opaque_id_owner")
        return try { source.execute(NotificationActionInvocation(intentId, request.replies, owner?.key ?: hinted?.notificationKey,
            request.remoteStreamItemId, request.actionIndex, request.isWearableAction)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) { log("action_executor_failed", mapOf("exception" to problem.javaClass.simpleName)); NotificationActionOutcome(false, NotificationActionFailure.FAILED) }
    }

    private fun ownsIntent(content: NotificationContent, id: String): Boolean =
        (content.fields["content_intent_id"] as? DataMapValue.Text)?.value == id || (content.fields["intent_id"] as? DataMapValue.Text)?.value == id ||
            content.maps.values.any { ownsIntent(it, id) } || content.lists.values.any { list -> list.any { ownsIntent(it, id) } }
    private fun uri(run: Run, notification: PhoneNotification) = "wear://${run.session.scope.localNodeId}" + NotificationProtocol.streamItemPath(
        run.session.scope.localNodeId, notification.packageName, notification.id, notification.tag, notification.key)
    private fun quiet(content: NotificationContent): NotificationContent = content.copy(fields = content.fields - setOf("defaults", "vibrate") +
        mapOf("has_sound" to DataMapValue.Bool(false), "only_alert_once" to DataMapValue.Bool(true)))
    private fun isQuiet(record: DataItemRecord): Boolean = record.data?.let { body -> runCatching {
        val fields = NotificationCodec.decode(body).fields
        (fields["defaults"] == null || fields["defaults"] == DataMapValue.IntNumber(0)) &&
            (fields["vibrate"] == null || fields["vibrate"] == DataMapValue.Longs(emptyList())) &&
            fields["has_sound"] == DataMapValue.Bool(false) && fields["only_alert_once"] == DataMapValue.Bool(true)
    }.getOrDefault(false) } ?: false
    private fun fingerprint(bytes: ByteArray, digests: List<String>): String = hash(bytes + digests.joinToString("\u0000").toByteArray(Charsets.UTF_8))
    private fun actionFingerprint(request: NotificationActionRequest): String = hash(NotificationRpcCodec.encodeRequest(request.copy(
        fields = emptyMap(), notices = emptyList(), metadata = emptyMap(), remoteStreamItemId = null, actionIndex = null, isWearableAction = null)))
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun invalidate() { if (!closed) dirty.trySend(Unit) }
    private fun dataReady(run: Run): Boolean = data.state.value.let { it.phase == DataSyncPhase.ACTIVE && it.sessionId == run.session.sessionId && it.scope == run.session.scope }
    private fun current(run: Run) { if (closed || active !== run || !run.job.isActive) throw CancellationException("Stale notification session") }
    private suspend fun <T> inRun(run: Run, action: suspend () -> T): T = coroutineScope {
        current(run); val job = currentCoroutineContext().job
        val registration = run.job.invokeOnCompletion { job.cancel(CancellationException("Notification session ended")) }
        try { action() } finally { registration.dispose() }
    }
    private fun updatePhase() {
        if (closed) return
        mutableState.update { it.copy(phase = when {
            !it.settings.enabled -> NotificationSyncPhase.DISABLED
            !source.state.value.accessGranted -> NotificationSyncPhase.ACCESS_REQUIRED
            !source.state.value.connected -> NotificationSyncPhase.LISTENER_DISCONNECTED
            active?.let(::dataReady) != true -> NotificationSyncPhase.DISCONNECTED
            else -> NotificationSyncPhase.ACTIVE
        }) }
    }
    private fun issue(kind: NotificationSyncIssue, event: String, problem: Exception? = null) {
        mutableState.update { it.copy(lastIssue = kind, phase = if (closed) NotificationSyncPhase.CLOSED else NotificationSyncPhase.FAILED) }
        log(event, problem?.let { mapOf("exception" to it.javaClass.simpleName) } ?: emptyMap())
    }
    private fun log(event: String, details: Map<String, String> = emptyMap()) { runCatching {
        val run = active
        diagnostics.record(ProtocolDiagnostic("notifications", event, DiagnosticSeverity.INFO, run?.session?.scope?.peer, run?.session?.sessionId ?: 0, details = details))
    } }
    private fun alive() { if (closed) throw BackendClosedException() }
    override fun close() {
        if (closed) return
        closed = true; active?.job?.cancel(); active = null; lifetime.cancel(); dirty.close(); remoteItems.close()
        mutableState.update { it.copy(phase = NotificationSyncPhase.CLOSED, sessionId = 0) }
    }
}
