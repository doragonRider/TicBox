package dev.ticbox.companion.engine.settings

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.CompanionFeatureSession
import dev.ticbox.companion.engine.WatchStatusCodec
import dev.ticbox.companion.engine.StatusReadException
import dev.ticbox.companion.protocol.*
import java.net.URI
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

interface DevicePreferencesStore {
    suspend fun load(): CompanionDevicePreferences
    suspend fun save(preferences: CompanionDevicePreferences)
}
class MemoryDevicePreferencesStore : DevicePreferencesStore {
    private var preferences = CompanionDevicePreferences()
    override suspend fun load() = preferences
    override suspend fun save(preferences: CompanionDevicePreferences) { this.preferences = preferences }
}

class DeviceSettingsEngine(
    parentScope: CoroutineScope,
    private val data: DataSyncClient,
    private val preferencesStore: DevicePreferencesStore = MemoryDevicePreferencesStore(),
    private val phoneLanguageTags: () -> String = { "en-US" },
    private val phone24HourFormat: () -> Boolean = { false },
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val clock: Clock = Clock.systemUTC(),
    private val responseTimeoutMillis: Long = 15_000,
    private val monitoring: () -> MonitoringSettingsClient = { MonitoringSettingsClient.UNAVAILABLE },
) : DeviceSettingsClient {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val worker = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val commands = Mutex()
    private val settingsWriter = Mutex()
    private val mutableState = MutableStateFlow(DeviceSettingsState())
    private val initialized = CompletableDeferred<Unit>(lifetime)
    override val state = mutableState.asStateFlow()
    @Volatile private var session: CompanionFeatureSession? = null
    private val pendingLock = Any()
    private val pending = mutableMapOf<String, CompletableDeferred<ByteArray>>()
    private val intents = mutableMapOf<String, CompletableDeferred<Boolean?>>()
    @Volatile private var closed = false
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    init {
        worker.launch {
            try {
                val saved = preferencesStore.load()
                ensureActive()
                mutableState.update { it.copy(preferences = saved, preferencesReady = true) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Exception) {
                mutableState.update { it.copy(preferencesReady = true, lastIssue = DeviceSettingsIssue(DeviceSettingsIssueKind.PREFERENCES_RESTORE, problem.javaClass.simpleName)) }
                log("preferences_restore_failed", session, mapOf("exception" to problem.javaClass.simpleName))
            }
            initialized.complete(Unit)
        }
        worker.launch { data.state.map { Triple(it.sessionId, it.scope, it.receivedItems) }.distinctUntilChanged().collect { (id, scope, _) ->
        val current = session
        if (current != null && scope == current.scope && id == current.sessionId) refreshStored(current)
        } }
    }

    suspend fun awaitReady() { initialized.await(); check(!closed) }

    fun attach(next: CompanionFeatureSession) {
        check(!closed)
        val previous = session
        if (previous?.sessionId == next.sessionId && previous.peer == next.peer) return
        cancelPending(); session = next
        mutableState.update { DeviceSettingsState(next.sessionId, it.preferences, preferencesReady = it.preferencesReady) }
        worker.launch { refreshStored(next) }
    }
    fun detach(sessionId: Long) {
        if (session?.sessionId != sessionId) return
        session = null; cancelPending()
        mutableState.update { it.copy(sessionId = 0) }
    }

    suspend fun handleMessage(peer: PeerAddress, sessionId: Long, path: String, bytes: ByteArray,
        route: ApplicationRoute? = null): Boolean {
        val current = session?.takeIf { it.peer == peer && it.sessionId == sessionId } ?: return false
        if (route != null && route != current.companionRoute) return false
        if (path !in DeviceSettingsProtocol.MESSAGES) return false
        try {
            when (path) {
                DeviceSettingsProtocol.WEAR_INFO -> {
                    require(bytes.size <= ProtocolLimits.MAX_MESSAGE_BYTES) { "Device info budget exceeded" }
                    val value = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject ?: throw ProtocolException("Device info is not an object")
                    val fields = value.mapValues { (_, field) -> if (field is JsonPrimitive) field.content else field.toString() }
                    if (session === current) mutableState.update { it.copy(info = WatchDeviceInfo(fields["wearType"], fields["wearVersionNumber"]?.toIntOrNull(), fields)) }
                }
                DeviceSettingsProtocol.BATTERY -> {
                    val value = WatchStatusCodec.battery(bytes) { log(it.event, current, it.details) }
                    if (session === current) mutableState.update { it.copy(batteryPercent = value, lastIssue = null) }
                }
                DeviceSettingsProtocol.REMOTE_INTENT_RESULT -> {
                    val result = NotificationRpcCodec.decodeResult(bytes)
                    result.invocationId?.let { id -> synchronized(pendingLock) { intents[id] }?.complete(result.successful) }
                    if (result.notices.isNotEmpty()) log("intent_result_notices", current, mapOf("count" to result.notices.size.toString()))
                }
            }
            synchronized(pendingLock) { pending[path] }?.complete(bytes.copyOf())
        } catch (problem: Exception) {
            log("message_decode_failed", current, mapOf("path" to path, "bytes" to bytes.size.toString(), "exception" to problem.javaClass.simpleName) +
                if (problem is StatusReadException) mapOf("field" to problem.field, "reason" to problem.reason) else emptyMap())
            mutableState.update { it.copy(lastIssue = DeviceSettingsIssue(DeviceSettingsIssueKind.REPLY_DECODE, problem.javaClass.simpleName)) }
            // Keep waiting for a usable response; malformed unsolicited updates do not break the link.
        }
        return true
    }

    /** SessionEngine provides the same physical-session request check before invoking this. */
    fun handleLockRequest(peer: PeerAddress, sessionId: Long, path: String, bytes: ByteArray): ByteArray {
        val current = session?.takeIf { it.peer == peer && it.sessionId == sessionId } ?: throw NotConnectedException()
        var code = 0
        try { code = DeviceSettingsCodec.code(bytes) }
        catch (problem: Exception) { log("lock_notification_decode_failed", current, mapOf("path" to path, "exception" to problem.javaClass.simpleName)) }
        val phase = when (path) {
            DeviceSettingsProtocol.LOCK_STARTED -> if (code == 1) WatchLockPhase.CONFIGURATION_SCREEN else WatchLockPhase.UNKNOWN
            DeviceSettingsProtocol.LOCK_UNLOCKED -> when (code) { 1 -> WatchLockPhase.WRONG_CREDENTIAL; 2 -> WatchLockPhase.CANCELLED; 3 -> WatchLockPhase.UNLOCKED; else -> WatchLockPhase.UNKNOWN }
            DeviceSettingsProtocol.LOCK_FINISHED -> when (code) { 1 -> WatchLockPhase.CONFIGURED; 2 -> WatchLockPhase.CANCELLED; 3 -> WatchLockPhase.REMOVED; else -> WatchLockPhase.UNKNOWN }
            else -> WatchLockPhase.UNKNOWN
        }
        if (session === current) mutableState.update { it.copy(lock = it.lock.copy(phase = phase, lastWireCode = code)) }
        log("lock_notification", current, mapOf("path" to path, "code" to code.toString()))
        return byteArrayOf()
    }

    override suspend fun refresh(): DeviceCommandResult = runCommand { current ->
            awaitMessage(current, DeviceSettingsProtocol.WEAR_INFO)
            awaitMessage(current, DeviceSettingsProtocol.BATTERY)
            refreshStored(current)
            DeviceCommandResult(DeviceCommandStatus.REMOTE_CONFIRMED)
    }

    override suspend fun setLanguageTags(languageTags: String?): DeviceCommandResult = preferences { it.copy(languageTags = languageTags) }
    override suspend fun set24HourFormat(enabled: Boolean?): DeviceCommandResult = preferences { it.copy(is24HourFormat = enabled) }
    private suspend fun preferences(change: (CompanionDevicePreferences) -> CompanionDevicePreferences): DeviceCommandResult {
        awaitReady()
        return commands.withLock {
            val next = change(mutableState.value.preferences)
            preferencesStore.save(next)
            mutableState.update { it.copy(preferences = next) }
            val current = session ?: return@withLock DeviceCommandResult(DeviceCommandStatus.SUBMITTED_UNVERIFIED, reason = "saved_locally_pending_connection")
            operation(current) {
                publishSharedSetting(current.scope, current.sessionId, CompanionSettingsCodec.LOCALES_PATH,
                    CompanionSettingsCodec.localesValue(next.languageTags ?: phoneLanguageTags()))
                publishSharedSetting(current.scope, current.sessionId, CompanionSettingsCodec.TIME_FORMAT_PATH,
                    CompanionSettingsCodec.booleanValue(next.is24HourFormat ?: phone24HourFormat()))
                DeviceCommandResult(DeviceCommandStatus.PERSISTED_AND_SUBMITTED)
            }
        }
    }

    /** Shared with bootstrap, so timestamps and concurrent UI setting writes have one writer. */
    suspend fun publishSharedSetting(scope: DataScope, sessionId: Long, path: String, value: ByteArray) = settingsWriter.withLock {
        val current = session?.takeIf { it.sessionId == sessionId && it.scope == scope } ?: throw NotConnectedException()
        var latest = Long.MIN_VALUE; var same = false
        for (item in allItems(scope, current.companionRoute)) if (runCatching { URI(item.uri).path == path }.getOrDefault(false)) {
            try {
                val body = item.data ?: continue
                val at = CompanionSettingsCodec.changedAt(body) ?: 0
                if (at >= latest) { latest = at; same = CompanionSettingsCodec.value(body)?.contentEquals(value) == true }
            } catch (problem: Exception) { log("existing_setting_unreadable", current, mapOf("exception" to problem.javaClass.simpleName)) }
        }
        if (same) return@withLock
        if (latest == Long.MAX_VALUE) throw IllegalStateException("Setting timestamp space exhausted")
        requireCurrent(current)
        val changed = maxOf(clock.millis(), latest + 1)
        data.publish(scope, current.companionRoute, DataItemDraft("wear://${scope.localNodeId}$path",
            CompanionSettingsCodec.setting(value, changed), lastModifiedMillis = changed))
    }

    override suspend fun openSettings(screen: WatchSettingsScreen): DeviceCommandResult = openRemoteIntent(screen.action)
    override suspend fun requestUninstall(packageName: String): DeviceCommandResult = openRemoteIntent("android.intent.action.DELETE", "package:$packageName")
    suspend fun requestUninstallForSession(current: CompanionFeatureSession, packageName: String): DeviceCommandResult =
        openRemoteIntent("android.intent.action.DELETE", "package:$packageName", current)
    private suspend fun openRemoteIntent(action: String, uri: String? = null,
        selected: CompanionFeatureSession? = session): DeviceCommandResult = runCommand(selected) { current ->
            val id = UUID.randomUUID().toString(); val response = CompletableDeferred<Boolean?>()
            synchronized(pendingLock) { intents[id] = response }
            try {
                requireCurrent(current)
                current.sendMessage(DeviceSettingsProtocol.REMOTE_INTENT, DeviceSettingsCodec.remoteSettings(action, id, uri))
                when (withTimeout(responseTimeoutMillis) { response.await() }) {
                    true -> DeviceCommandResult(DeviceCommandStatus.REMOTE_UI_OPENED)
                    false -> DeviceCommandResult(DeviceCommandStatus.REJECTED, reason = "watch_activity_rejected")
                    null -> DeviceCommandResult(DeviceCommandStatus.SUBMITTED_UNVERIFIED, reason = "missing_result_flag")
                }
            } finally { synchronized(pendingLock) { intents.remove(id) }; response.cancel() }
    }
    override suspend fun queryLockSupport(): DeviceCommandResult = runCommand { current ->
            val types = DeviceSettingsCodec.lockTypes(current.request(DeviceSettingsProtocol.LOCK_SUPPORT, byteArrayOf(), responseTimeoutMillis))
            requireCurrent(current)
            mutableState.update { it.copy(lock = it.lock.copy(supportedTypes = types)) }
            DeviceCommandResult(DeviceCommandStatus.REMOTE_CONFIRMED)
    }
    override suspend fun configureLock(type: WatchLockType): DeviceCommandResult = runCommand { current ->
            val code = DeviceSettingsCodec.code(current.request(DeviceSettingsProtocol.LOCK_CHANGE, DeviceSettingsCodec.lockType(type.wireValue), responseTimeoutMillis))
            requireCurrent(current)
            mutableState.update { it.copy(lock = it.lock.copy(lastWireCode = code, phase = when(code) {
                2 -> WatchLockPhase.CONFIGURATION_SCREEN; 3 -> WatchLockPhase.VERIFICATION_SCREEN; else -> WatchLockPhase.UNKNOWN
            })) }
            DeviceCommandResult(when(code) { 1 -> DeviceCommandStatus.REJECTED; 2,3 -> DeviceCommandStatus.REMOTE_UI_OPENED; else -> DeviceCommandStatus.SUBMITTED_UNVERIFIED }, code)
    }
    override suspend fun cancelLockConfiguration(): DeviceCommandResult = runCommand { current ->
            current.request(DeviceSettingsProtocol.LOCK_CANCEL, byteArrayOf(), responseTimeoutMillis)
            requireCurrent(current)
            mutableState.update { it.copy(lock = it.lock.copy(phase = WatchLockPhase.CANCELLED)) }
            DeviceCommandResult(DeviceCommandStatus.REMOTE_CONFIRMED)
    }
    @Deprecated("Use services.monitoring.set(HEART_HEALTH, MonitoringValue.Toggle(enabled))")
    override suspend fun setHeartHealthMonitoring(enabled: Boolean): DeviceCommandResult {
        val result = monitoring().set(MonitoringSettingId.HEART_HEALTH, MonitoringValue.Toggle(enabled))
        return DeviceCommandResult(when (result.status) {
            MonitoringCommandStatus.STORED_AND_NOTIFIED, MonitoringCommandStatus.UNCHANGED -> DeviceCommandStatus.REMOTE_CONFIRMED
            MonitoringCommandStatus.STORED_UNVERIFIED -> DeviceCommandStatus.SUBMITTED_UNVERIFIED
            MonitoringCommandStatus.INVALID_ARGUMENT -> DeviceCommandStatus.REJECTED
            MonitoringCommandStatus.UNAVAILABLE -> DeviceCommandStatus.UNAVAILABLE
            else -> DeviceCommandStatus.FAILED
        }, reason = result.reason)
    }
    override suspend fun setHeartHealthFeatureVisible(visible: Boolean): DeviceCommandResult = runCommand { current ->
        current.sendMessage(DeviceSettingsProtocol.HEART_HEALTH, if (visible) byteArrayOf(49) else byteArrayOf(48))
        DeviceCommandResult(DeviceCommandStatus.SUBMITTED_UNVERIFIED)
    }
    override suspend fun appIcon(packageName: String): ByteArray? {
        val current = session ?: return null
        val digest = state.value.apps.firstOrNull { it.packageName == packageName }?.iconDigest ?: return null
        val body = data.asset(current.scope, current.companionRoute, digest)?.data
        if (body == null) data.requestAsset(current.scope, current.companionRoute, digest)
        return body
    }

    private suspend fun refreshStored(current: CompanionFeatureSession) {
        try {
            val apps = ArrayList<WatchInstalledApp>(); var supported: List<Int>? = null
            val rows = allItems(current.scope, current.companionRoute)
            for (item in rows) {
                if (item.local) continue
                val path = runCatching { URI(item.uri).path }.getOrNull() ?: continue
                val bytes = item.data ?: continue
                try {
                    if (path == DeviceSettingsProtocol.LOCK_TYPES) supported = DeviceSettingsCodec.lockTypes(bytes)
                    if (path.startsWith(DeviceSettingsProtocol.APPS)) {
                        val app = DeviceSettingsCodec.app(bytes)
                        val pkg = app.packageName?.takeIf { it.isNotEmpty() } ?: path.removePrefix(DeviceSettingsProtocol.APPS)
                        val asset = item.assets.firstOrNull { it.name == app.iconAssetName } ?: item.assets.firstOrNull { it.name == "app_icon" }
                        apps += WatchInstalledApp(pkg, app.label, app.launchable, app.notificationPermissionState, asset?.digest)
                        if (app.unknownFields > 0) log("app_unknown_fields", current, mapOf("count" to app.unknownFields.toString()))
                    }
                } catch (problem: Exception) { log("stored_item_unreadable", current, mapOf("exception" to problem.javaClass.simpleName)) }
            }
            if (session === current) mutableState.update { it.copy(apps = apps.sortedBy { app -> app.packageName },
                appDirectoryLimited = rows.size >= 10_000,
                lock = supported?.let { values -> it.lock.copy(supportedTypes = values) } ?: it.lock) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) { log("stored_refresh_failed", current, mapOf("exception" to problem.javaClass.simpleName)) }
    }
    private suspend fun allItems(scope: DataScope, route: ApplicationRoute): List<DataItemRecord> {
        val items = ArrayList<DataItemRecord>(); var offset = 0
        do {
            val page = data.items(scope, DataItemQuery(route = route, offset = offset, limit = 500))
            items += page.items
            if (items.size >= 10_000) { log("settings_inventory_budget", session, mapOf("items" to items.size.toString())); break }
            val next = page.nextOffset ?: break
            if (next <= offset) break
            offset = next
        } while (true)
        return items
    }
    private suspend fun awaitMessage(current: CompanionFeatureSession, path: String): ByteArray {
        val response = CompletableDeferred<ByteArray>()
        synchronized(pendingLock) { pending[path] = response }
        try { requireCurrent(current); current.sendMessage(path, byteArrayOf())
            return withTimeout(responseTimeoutMillis) { response.await() }
        } finally { synchronized(pendingLock) { if (pending[path] === response) pending.remove(path) }; response.cancel() }
    }
    private suspend fun operation(current: CompanionFeatureSession, block: suspend () -> DeviceCommandResult): DeviceCommandResult = try {
        requireCurrent(current); block()
    } catch (_: TimeoutCancellationException) { log("command_timeout", current); DeviceCommandResult(DeviceCommandStatus.TIMED_OUT) }
    catch (_: NotConnectedException) { unavailable() }
    catch (cancelled: CancellationException) {
        if (session !== current && currentCoroutineContext().isActive) unavailable() else throw cancelled
    } catch (problem: Exception) {
        log("command_failed", current, mapOf("exception" to problem.javaClass.simpleName))
        mutableState.update { it.copy(lastIssue = DeviceSettingsIssue(DeviceSettingsIssueKind.COMMAND, problem.javaClass.simpleName)) }
        DeviceCommandResult(if (problem.cause is TimeoutCancellationException) DeviceCommandStatus.TIMED_OUT else DeviceCommandStatus.FAILED,
            reason = problem.javaClass.simpleName)
    }
    private suspend fun runCommand(selected: CompanionFeatureSession? = session,
        block: suspend (CompanionFeatureSession) -> DeviceCommandResult): DeviceCommandResult {
        val current = selected ?: return unavailable()
        return commands.withLock { operation(current) { block(current) } }
    }
    private fun requireCurrent(current: CompanionFeatureSession) { if (closed || session !== current) throw NotConnectedException() }
    private fun cancelPending() = synchronized(pendingLock) {
        pending.values.forEach { it.cancel() }; intents.values.forEach { it.cancel() }; pending.clear(); intents.clear()
    }
    private fun unavailable() = DeviceCommandResult(DeviceCommandStatus.UNAVAILABLE, reason = "no_current_session")
    private fun log(event: String, current: CompanionFeatureSession?, details: Map<String, String> = emptyMap()) {
        runCatching { diagnostics.record(ProtocolDiagnostic("device_settings", event, peer = current?.peer, sessionId = current?.sessionId ?: 0, details = details)) }
    }
    override fun close() { closed = true; session = null; cancelPending(); lifetime.cancel(); mutableState.update { it.copy(sessionId = 0) } }
}
