package dev.ticbox.companion.platform

import android.content.Context
import dev.ticbox.companion.platform.health.*
import dev.ticbox.companion.engine.health.*
import dev.ticbox.companion.engine.toolbox.*
import dev.ticbox.companion.platform.toolbox.*
import android.os.Build
import android.text.format.DateFormat
import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.*
import dev.ticbox.companion.engine.data.*
import dev.ticbox.companion.engine.setup.*
import dev.ticbox.companion.engine.pairing.PairingCoordinator
import dev.ticbox.companion.engine.pairing.WatchUnpairEngine
import dev.ticbox.companion.engine.cloud.*
import dev.ticbox.companion.engine.health.HealthRecordsEngine
import dev.ticbox.companion.engine.google.asSetupDependencyResult
import dev.ticbox.companion.engine.privacy.*
import dev.ticbox.companion.engine.notification.*
import dev.ticbox.companion.platform.notification.*
import dev.ticbox.companion.platform.cloud.*
import dev.ticbox.companion.platform.google.AndroidGoogleServices
import dev.ticbox.companion.platform.data.SQLiteDataSyncStore
import dev.ticbox.companion.platform.data.SQLiteWatchHistoryStore
import dev.ticbox.companion.platform.identity.AtomicFileIdentityStore
import dev.ticbox.companion.platform.identity.AndroidApplicationRoute
import dev.ticbox.companion.platform.proxy.AndroidNetworkProxy
import dev.ticbox.companion.platform.pairing.*
import dev.ticbox.companion.platform.privacy.AtomicFileCloudPrivacyStore
import dev.ticbox.companion.platform.transport.BluetoothRfcommTransportFactory
import dev.ticbox.companion.engine.calling.CompanionCallingEngine
import dev.ticbox.companion.engine.media.MediaControlsEngine
import dev.ticbox.companion.engine.finding.FindDevicesEngine
import dev.ticbox.companion.engine.settings.DeviceSettingsEngine
import dev.ticbox.companion.engine.settings.MonitoringSettingsEngine
import dev.ticbox.companion.engine.settings.MonitoringLink
import dev.ticbox.companion.engine.sos.SosEngine
import dev.ticbox.companion.engine.sos.SosProtocol
import dev.ticbox.companion.platform.sos.AndroidSosAssistance
import dev.ticbox.companion.platform.sos.AndroidSosAuthorizationActions
import dev.ticbox.companion.platform.sos.AtomicFileSosSettingsStore
import dev.ticbox.companion.engine.settings.PhoneRemoteIntentEngine
import dev.ticbox.companion.engine.watchface.WatchFaceManagementEngine
import dev.ticbox.companion.platform.calling.AndroidCallingPlatform
import dev.ticbox.companion.platform.calling.AtomicFileCallingSettings
import dev.ticbox.companion.platform.media.AndroidMediaSource
import dev.ticbox.companion.platform.media.AtomicFileMediaSettingsStore
import dev.ticbox.companion.platform.finding.AndroidPhoneRinger
import dev.ticbox.companion.platform.settings.*
import dev.ticbox.companion.protocol.DeviceSettingsProtocol
import dev.ticbox.companion.protocol.CallProtocol
import dev.ticbox.companion.protocol.MediaProtocol
import dev.ticbox.companion.protocol.WatchFaceProtocol
import dev.ticbox.companion.protocol.WatchFindingProtocol
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** All non-UI workflow owners for one local service. No Android activity is retained. */
class AndroidCompanionRuntime internal constructor(
    context: Context,
    scope: CoroutineScope,
    route: ApplicationRoute,
    config: SessionConfig,
    private val diagnostics: DiagnosticSink,
    private val connectPeer: (suspend (PeerAddress) -> Unit)? = null,
    cloudConfig: MobvoiCloudConfig = MobvoiCloudConfig(),
    findPlaybackForeground: (Boolean) -> Unit = {},
    private val disconnectPeer: (suspend () -> Unit)? = null,
    private val forgetPeer: (suspend () -> Unit)? = null,
) : AutoCloseable {
    private val context = context.applicationContext
    private val closed = AtomicBoolean()
    private val lifetime = SupervisorJob(scope.coroutineContext[Job])
    private val worker = CoroutineScope(scope.coroutineContext + lifetime)
    private val privacyStore = AtomicFileCloudPrivacyStore(this.context, diagnostics)
    internal val cloudSettings = CloudSettingsController(privacyStore.load(), privacyStore::save, ::applyCloudSettings)
    @Volatile private var selectedPeer: PeerAddress? = null
    internal val database = SQLiteDataSyncStore(this.context)
    private val identities = AtomicFileIdentityStore(this.context)
    private val cloudCache = SQLiteMobvoiCloudCacheStore(database, diagnostics)
    private val packageInfo = this.context.packageManager.getPackageInfo(this.context.packageName, 0)
    private val effectiveCloudConfig = cloudConfig.copy(packageName = this.context.packageName,
        versionName = cloudConfig.versionName ?: packageInfo.versionName,
        versionCode = cloudConfig.versionCode ?: packageInfo.longVersionCode.toString())
    private val http = ConfiguredCloudHttpTransport({ cloudSettings.state.value }) {
        AndroidCloudHttpTransport(maxResponseBytes = cloudConfig.maxResponseBytes)
    }
    private val account = MobvoiAccountEngine(http, AndroidMobvoiCredentialStore(this.context), cloudCache,
        effectiveCloudConfig, diagnostics)
    private val health = MobvoiCloudHealthEngine(account, http, cloudCache, effectiveCloudConfig, diagnostics)
    private val localHealthStore = SQLiteLocalHealthStore(database, diagnostics = diagnostics)
    private val healthRecords = HealthRecordsEngine(deviceRecords = localHealthStore, mobvoiCache = MobvoiCacheHealthRecordReader(cloudCache))
    private val accountBridge = MobvoiWatchAccountBridge(account, diagnostics)
    val history: WatchObservationStore = SQLiteWatchHistoryStore(database)
    private val data = DataSyncEngine(worker, database, diagnostics = diagnostics)
    private val proxy = AndroidNetworkProxy(this.context, worker, diagnostics)
    private val callingPlatform = AndroidCallingPlatform(this.context, worker, diagnostics)
    private val calling = CompanionCallingEngine(worker, callingPlatform, diagnostics,
        settingsStore = AtomicFileCallingSettings(this.context))
    val callingActions get() = callingPlatform
    private val mediaSource = AndroidMediaSource(this.context, worker, diagnostics)
    private val media = MediaControlsEngine(worker, data, mediaSource, AtomicFileMediaSettingsStore(this.context), diagnostics,
        assetRetirement = dev.ticbox.companion.engine.media.MediaAssetRetirement { scope, owner, digests ->
            database.retireNotificationAssets(scope, owner, digests)
        })
    private val ringer = AndroidPhoneRinger(this.context, diagnostics, findPlaybackForeground)
    private val ownRoute = AndroidApplicationRoute.read(this.context)
    private val finding = FindDevicesEngine(worker, data, ringer, AtomicFileFindDeviceSettings(this.context, diagnostics), diagnostics,
        watchRoute = ownRoute)
    private val phonePreferences = PhoneSystemPreferences(this.context)
    private val deviceSettings = DeviceSettingsEngine(worker, data, AtomicFileDevicePreferences(this.context, diagnostics),
        phoneLanguageTags = { phonePreferences.languageTags },
        phone24HourFormat = { phonePreferences.is24Hour }, diagnostics = diagnostics,
        monitoring = { monitoring })
    private val sosPlatform = AndroidSosAssistance(this.context, worker, diagnostics)
    private val sos = SosEngine(worker, AtomicFileSosSettingsStore(this.context), sosPlatform, diagnostics)
    val sosActions: AndroidSosAuthorizationActions get() = sosPlatform
    private val watchFaces = WatchFaceManagementEngine(worker, data, diagnostics, uninstallRequest = { session, packageName ->
        if (deviceSettings.state.value.sessionId != session.sessionId)
            WatchFaceOperationResult(WatchFaceOutcome.INTERRUPTED, detail = "session_replaced")
        else {
            val result = deviceSettings.requestUninstallForSession(session, packageName)
            WatchFaceOperationResult(when (result.status) {
                DeviceCommandStatus.REMOTE_UI_OPENED -> WatchFaceOutcome.SUBMITTED
                DeviceCommandStatus.TIMED_OUT -> WatchFaceOutcome.TIMED_OUT
                DeviceCommandStatus.UNAVAILABLE -> WatchFaceOutcome.UNAVAILABLE
                DeviceCommandStatus.REJECTED, DeviceCommandStatus.FAILED -> WatchFaceOutcome.FAILED
                else -> WatchFaceOutcome.UNKNOWN
            }, result.wireCode, if (result.status == DeviceCommandStatus.REMOTE_UI_OPENED) "remote_uninstall_confirmation_opened" else result.reason)
        }
    })
    private val capabilities = CompanionCapabilities(worker, data, diagnostics)
    private val remoteIntents = PhoneRemoteIntentEngine(AndroidPhoneRemoteIntents(this.context, diagnostics), diagnostics)
    private val featureReady = java.util.concurrent.ConcurrentHashMap<Long, CompletableDeferred<Unit>>()
    private val notificationSource = AndroidNotificationSource(this.context, worker, diagnostics)
    private val notifications = NotificationSyncEngine(worker, data, notificationSource,
        AtomicFileNotificationSettingsStore(this.context), diagnostics,
        revisionStore = AtomicFileNotificationRevisionStore(this.context),
        assetRetirement = NotificationAssetRetirement { scope, owner, digests ->
            val released = database.retireNotificationAssets(scope, owner, digests)
            runCatching { diagnostics.record(ProtocolDiagnostic("notifications", "retired_assets_released",
                details = mapOf("candidates" to digests.size.toString(), "released" to released.toString()))) }
        })
    val backend: SessionEngine = SessionEngine(worker, BluetoothRfcommTransportFactory(this.context), identities,
        route, config, senderSdkVersion = Build.VERSION.SDK_INT, diagnostics = diagnostics,
        parseContext = Dispatchers.Default,
        extensions = listOf(object : SessionExtension {
            override fun onReady(session: EstablishedSession) {
                data.attach(DataSyncSession(session.peer, session.sessionId, session.localIdentity.nodeId, session.remoteNodeId,
                    session.remoteNetworkId ?: session.localIdentity.networkId, route,
                    sendEnvelope = { session.sendEnvelope(it) }, onResyncRequired = session::requestReconnect,
                    negotiatedWireVersion = session.negotiatedWireVersion))
                notifications.attach(NotificationSession(session.sessionId,
                    DataScope(session.peer, session.localIdentity.nodeId, session.remoteNodeId,
                        session.remoteNetworkId ?: session.localIdentity.networkId), route) { path, bytes ->
                    backend.sendMessage(session.peer, session.sessionId, path, bytes)
                })
                val feature = featureSession(session)
                remoteIntents.attach(feature)
                capabilities.attach(feature)
                media.attach(feature)
                deviceSettings.attach(feature)
                sos.attach(feature)
                watchFaces.attach(feature)
                unpair.attach(feature)
                val attached = CompletableDeferred<Unit>()
                featureReady[session.sessionId] = attached
                worker.launch {
                    try {
                        if (backend.state.value.sessionId == session.sessionId) calling.attach(feature)
                        if (backend.state.value.sessionId == session.sessionId) finding.attach(feature)
                        attached.complete(Unit)
                    } catch (cancelled: CancellationException) { attached.cancel(); throw cancelled }
                    catch (problem: Exception) { attached.complete(Unit); log("feature_attach_failed", session.peer, session.sessionId, problem) }
                }
            }
            override suspend fun onEnvelope(session: EstablishedSession, envelope: ByteArray) { data.acceptInbound(session.sessionId, envelope) }
            override fun onClosed(session: EstablishedSession) {
                featureReady.remove(session.sessionId)?.cancel()
                remoteIntents.detach(session.sessionId)
                capabilities.detach(session.sessionId)
                media.detach(session.sessionId)
                deviceSettings.detach(session.sessionId)
                sos.detach(session.sessionId)
                watchFaces.detach(session.sessionId)
                unpair.detach(session.sessionId)
                worker.launch {
                    calling.detach(session.sessionId)
                }
                worker.launch { finding.detach(session.sessionId) }
                notifications.detach(session.sessionId)
                data.detach(session.sessionId)
            }
        }))
    private val localHealth = LocalHealthSyncEngine(worker, localHealthStore,
        BluetoothLocalHealthRemote(this.context) { link, appRoute, bytes ->
            backend.sendMessage(link.source.peer, link.sessionId, dev.ticbox.companion.protocol.LocalHealthProtocol.WAKE_PATH, bytes, appRoute)
        }, diagnostics)
    private val toolbox = AndroidToolboxServices(this.context, worker, diagnostics) { link, bytes ->
        backend.sendMessage(link.peer, link.sessionId, dev.ticbox.companion.protocol.ToolboxProtocol.WAKE_PATH, bytes, ownRoute)
    }
    private val healthModule = dev.ticbox.companion.engine.module.HealthModuleEngine(EmbeddedHealthModule(this.context)::load, toolbox.healthModule)
    private val notificationAlertPatch = dev.ticbox.companion.engine.module.NotificationAlertPatchEngine(toolbox.moduleShell)
    private val watchModuleHost = dev.ticbox.companion.engine.module.WatchModuleHostEngine(toolbox.moduleShell)
    private val monitoring = MonitoringSettingsEngine(worker, toolbox.monitoring, diagnostics)
    private val dndRemote = AndroidDndRemote { link, bytes ->
        backend.sendMessage(link.peer, link.sessionId, dev.ticbox.companion.protocol.ToolboxProtocol.DND_PATH, bytes, ownRoute)
    }
    private val dnd = DndSyncEngine(worker, NotificationListenerHub.dnd, AtomicDndSettings(this.context), dndRemote)
    private val googleIntegration = AndroidGoogleServices(this.context, worker, backend, data, diagnostics)
    private val google = ConfiguredGoogleServices(googleIntegration) { cloudSettings.state.value }
    val googleActions get() = googleIntegration.hostActions
    val pairingActions = AndroidPairingActions(diagnostics)
    private val associationMappings = AtomicFileAssociationMappingStore(this.context)
    private val pairing: PairingCoordinator = PairingCoordinator(worker,
        AndroidPairingPlatform(this.context, pairingActions, diagnostics), associationMappings,
        SessionPairingLink(backend.state,
            connect = { peer ->
                if (connectPeer != null) connectPeer.invoke(peer)
                else { startPeer(peer); backend.connect(peer) }
            }, open = { peer, sessionId, path -> backend.openChannel(peer, sessionId, path) }), diagnostics,
        prepareNewPairing = {
            if (unpair.state.value.busy) throw PairingBusyException()
            if (disconnectPeer != null) disconnectPeer.invoke() else backend.disconnect()
        })
    private val unpairCleanup = AndroidWatchUnpairCleanup(this.context, pairingActions, associationMappings, diagnostics)
    private val unpair: WatchUnpairEngine = WatchUnpairEngine(worker,
        requestSnapshot = { session -> data.requestRemoteSnapshot(session.scope, session.sessionId) },
        cleanup = { peer ->
            // Do not disconnect a different watch selected while the old operation was finishing.
            val selected = backend.state.value.peer ?: selectedPeer
            if (selected == null || selected == peer) {
                if (forgetPeer != null) forgetPeer.invoke()
                else if (disconnectPeer != null) disconnectPeer.invoke()
                else { backend.disconnect(); stopPeer() }
            }
            withTimeout(120_000) { unpairCleanup.clear(peer) }
        }, diagnostics = diagnostics)
    private val unpairObservation = data.observeInboundItems(unpair::offerRemoteItem)
    private val initializer = CompanionDataInitializer(data, route,
        languageTags = { deviceSettings.state.value.preferences.languageTags ?: phonePreferences.languageTags },
        is24HourFormat = { deviceSettings.state.value.preferences.is24HourFormat ?: phonePreferences.is24Hour }, phoneSdkVersion = Build.VERSION.SDK_INT,
        diagnostics = diagnostics, settingsWriter = deviceSettings::publishSharedSetting)
    private val setup = SetupEngine(worker, backend, object : SetupRpcTransport {
        override suspend fun request(peer: PeerAddress, sessionId: Long, path: String, payload: ByteArray, timeoutMillis: Long) =
            backend.request(peer, sessionId, path, payload, timeoutMillis)
        override fun registerRequestHandler(path: String, handler: suspend (SetupRpcRequest) -> ByteArray): AutoCloseable =
            backend.registerRequestHandler(path) { request -> handler(SetupRpcRequest(request.peer, request.sessionId, request.payload)) }
    }, environment = object : SetupEnvironment {
        override suspend fun transferGoogleAccount(context: SetupContext): SetupDependencyResult =
            google.transferAccount(context.peer, context.sessionId).asSetupDependencyResult()

        override suspend fun runOptionalStep(context: SetupContext, step: SetupOptionalStep): SetupDependencyResult {
            if (step != SetupOptionalStep.CDM_PERMISSION_SYNC) return SetupDependencyResult.unavailable("optional_step_${step.name.lowercase()}_unavailable")
            val selected = backend.state.value
            if (selected.peer != context.peer || selected.sessionId != context.sessionId || selected.phase != ConnectionPhase.LINK_READY) return SetupDependencyResult.unavailable("permission_sync_session_replaced")
            val operationId = pairing.syncPermissions(context.peer)
            try {
                val result = pairing.state.first { it.operationId != operationId || it.phase in setOf(PairingPhase.COMPLETED, PairingPhase.CANCELLED, PairingPhase.FAILED) }
                return if (result.operationId == operationId && result.linkSessionId == context.sessionId && result.permissionTransfer == PairingPermissionTransfer.COMPLETED)
                    SetupDependencyResult.complete()
                else SetupDependencyResult(SetupDependencyState.RETRYABLE_FAILURE,
                    result.failure?.reason ?: "permission_sync_${result.permissionTransfer.name.lowercase()}")
            } catch (cancelled: CancellationException) { pairing.cancel(operationId); throw cancelled }
        }
        override suspend fun prepareInitialData(context: SetupContext, consents: List<SetupConsent>): SetupDependencyResult = try {
            withTimeout(15_000) {
                data.state.first { (it.sessionId == context.sessionId && it.phase !in setOf(DataSyncPhase.RESUMING, DataSyncPhase.DISCONNECTED)) || it.phase == DataSyncPhase.CLOSED }
                val selected = data.state.value
                if (selected.sessionId != context.sessionId || selected.scope?.peer != context.peer) throw NotConnectedException()
                deviceSettings.awaitReady()
                initializer.prepare(context.sessionId, termsAccepted = true, consents = consents)
            }
            // Consent effects retain their own callback results. Their unavailability does not
            // erase successful locale/settings preparation or fabricate Google opt-in success.
            google.applyConsents(context.peer, context.sessionId, consents)
            SetupDependencyResult.complete()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) { SetupDependencyResult(SetupDependencyState.RETRYABLE_FAILURE, "initial_data_${problem.javaClass.simpleName}") }

        override suspend fun awaitDataSyncCompletion(context: SetupContext): SetupDependencyResult {
            if (data.state.value.sessionId != context.sessionId || data.state.value.scope?.peer != context.peer) return SetupDependencyResult.unavailable("data_session_replaced")
            val barrier = data.awaitProgressBarrier()
            val sameSession = barrier.sessionId == context.sessionId && data.state.value.sessionId == context.sessionId && data.state.value.scope?.peer == context.peer
            return if (sameSession && barrier.status == DataSyncBarrierStatus.COMPLETE) SetupDependencyResult.complete()
            else SetupDependencyResult(SetupDependencyState.RETRYABLE_FAILURE, "data_barrier_${barrier.status.name.lowercase()}")
        }
    }, diagnostics = diagnostics, config = SetupConfig(dependencyTimeoutMillis = 900_000))
    private val configuredSetup = ConfiguredSetupClient(setup) { cloudSettings.state.value }
    private val progressHandler: AutoCloseable
    private val featureRegistrations = mutableListOf<AutoCloseable>()
    private var hostActivityTransfer: Job? = null // Accessed by the single MessageApi observer.

    val services: CompanionServices = object : CompanionServices {
        override val sos: SosClient get() = this@AndroidCompanionRuntime.sos
        override val healthModule: HealthModuleClient get() = this@AndroidCompanionRuntime.healthModule
        override val notificationAlertPatch: NotificationAlertPatchClient get() = this@AndroidCompanionRuntime.notificationAlertPatch
        override val watchModuleHost: WatchModuleHostClient get() = this@AndroidCompanionRuntime.watchModuleHost
        override val monitoring: MonitoringSettingsClient get() = this@AndroidCompanionRuntime.monitoring
        override val toolbox: WatchToolboxClient get() = this@AndroidCompanionRuntime.toolbox
        override val dnd: DndSyncClient get() = this@AndroidCompanionRuntime.dnd
        override val privacy: CloudSettingsClient get() = cloudSettings
        override val pairing: PairingClient get() = this@AndroidCompanionRuntime.pairing
        override val unpair: WatchUnpairClient = object : WatchUnpairClient by this@AndroidCompanionRuntime.unpair {
            override suspend fun unpairWithoutReset(): WatchUnpairResult {
                pairing.cancel()
                setup.cancel()
                return this@AndroidCompanionRuntime.unpair.unpairWithoutReset()
            }

            override suspend fun clearLocalPairing(peer: PeerAddress): WatchUnpairResult {
                pairing.cancel()
                setup.cancel()
                return this@AndroidCompanionRuntime.unpair.clearLocalPairing(peer)
            }
        }
        override val setup: SetupClient get() = configuredSetup
        override val data: DataSyncClient get() = this@AndroidCompanionRuntime.data
        override val notifications: NotificationSyncClient get() = this@AndroidCompanionRuntime.notifications
        override val calling: CompanionCallingClient get() = this@AndroidCompanionRuntime.calling
        override val media: MediaControlsClient get() = this@AndroidCompanionRuntime.media
        override val finding: FindDevicesClient get() = this@AndroidCompanionRuntime.finding
        override val deviceSettings: DeviceSettingsClient get() = this@AndroidCompanionRuntime.deviceSettings
        override val watchFaces: WatchFaceManagement get() = this@AndroidCompanionRuntime.watchFaces
        override val network get() = proxy.state
        override val history: WatchHistoryClient get() = this@AndroidCompanionRuntime.history
        override val account: MobvoiAccountClient = object : MobvoiAccountClient by this@AndroidCompanionRuntime.account {
            override suspend fun logout(revokeRemote: Boolean, clearCachedHealth: Boolean) =
                this@AndroidCompanionRuntime.account.logout(
                    revokeRemote && cloudSettings.state.value.allows(CloudCapability.MOBVOI_CLOUD), clearCachedHealth)
        }
        override val health: MobvoiCloudHealthClient get() = this@AndroidCompanionRuntime.health
        override val healthRecords: HealthRecordsClient get() = this@AndroidCompanionRuntime.healthRecords
        override val localHealth: LocalHealthSyncClient get() = this@AndroidCompanionRuntime.localHealth
        override val google: GoogleServices get() = this@AndroidCompanionRuntime.google
    }

    init {
        worker.launch {
            backend.state.map { state -> state.peer?.takeIf { state.phase == ConnectionPhase.LINK_READY }?.let { ToolboxBridgeLink(it, state.sessionId) } }
                .distinctUntilChanged().collect { link ->
                    toolbox.attach(link)
                    val moduleLink = link?.let { dev.ticbox.companion.engine.module.HealthModuleLink(it.peer, it.sessionId) }
                    healthModule.attach(moduleLink)
                    notificationAlertPatch.attach(moduleLink)
                    watchModuleHost.attach(moduleLink)
                    monitoring.attach(link?.let { MonitoringLink(it.peer, it.sessionId) })
                    dnd.attach(link?.let { DndLink(it.peer, it.sessionId) })
                }
        }
        worker.launch {
            val profileId = LocalHealthProfile.id(this@AndroidCompanionRuntime.context)
            backend.state.map { link ->
                link.peer?.takeIf { link.phase == ConnectionPhase.LINK_READY }?.let {
                    LocalHealthLink(HealthRecordSource.LocalDevice(profileId, it), link.sessionId)
                }
            }.distinctUntilChanged().collect { localHealth.attach(it) }
        }
        DeviceSettingsProtocol.REQUESTS.forEach { path -> featureRegistrations += backend.registerRequestHandler(path) { request ->
            deviceSettings.handleLockRequest(request.peer, request.sessionId, request.path, request.payload)
        } }
        worker.launch { calling.state.collect { status -> capabilities.setPhoneCapability(CallProtocol.FULL_CONTROL_CAPABILITY,
            status.enabled && status.access.canManage) } }
        worker.launch { capabilities.watch.collect { names ->
            media.setSupportsV2(backend.state.value.sessionId, "media_notification_message_watch" in names)
        } }
        observeFeatureMessages("calling", CallProtocol.INBOUND_PATHS, awaitAttachment = true) { message, peer, sessionId -> calling.handleMessage(peer, sessionId, message.path, message.payload, message.route) }
        observeFeatureMessages("media", setOf(MediaProtocol.PATH)) { message, peer, sessionId -> media.handleMessage(peer, sessionId, message.path, message.payload, message.route) }
        observeFeatureMessages("finding", FindDevicesEngine.INCOMING_PATHS, FindDevicesEngine.GMS_ROUTE, awaitAttachment = true) { message, peer, sessionId -> finding.handleMessage(peer, sessionId, message.path, message.payload, message.route) }
        observeFeatureMessages("watch_finding", setOf(WatchFindingProtocol.STATE_PATH), ownRoute, awaitAttachment = true) { message, peer, sessionId ->
            finding.handleMessage(peer, sessionId, message.path, message.payload, message.route)
        }
        observeFeatureMessages("dnd", setOf(dev.ticbox.companion.protocol.ToolboxProtocol.DND_RESULT), ownRoute) { message, peer, sessionId ->
            dndRemote.accept(peer, sessionId, message.payload)
        }
        observeFeatureMessages("watch_finding_launch", setOf(DeviceSettingsProtocol.REMOTE_INTENT_RESULT), awaitAttachment = true) { message, peer, sessionId ->
            finding.handleMessage(peer, sessionId, message.path, message.payload, message.route)
        }
        observeFeatureMessages("device_settings", DeviceSettingsProtocol.MESSAGES) { message, peer, sessionId -> deviceSettings.handleMessage(peer, sessionId, message.path, message.payload, message.route) }
        observeFeatureMessages("sos", SosProtocol.MESSAGES) { message, peer, sessionId ->
            sos.handleMessage(peer, sessionId, message.path, message.payload, message.route)
        }
        observeFeatureMessages("watch_unpair", setOf(WatchUnpairEngine.ACK_PATH), awaitAttachment = true) { message, peer, sessionId ->
            unpair.offerMessage(peer, sessionId, message.path, message.payload, message.route)
        }
        observeFeatureMessages("remote_intent", setOf(DeviceSettingsProtocol.REMOTE_INTENT)) { message, peer, sessionId ->
            remoteIntents.handleMessage(peer, sessionId, message.path, message.payload, message.route)
        }
        // WearServices uses this SDK capability to derive its companion connection status.
        // Publish only after the real phone-side /remote_intent handler has been registered.
        capabilities.setPhoneCapability(dev.ticbox.companion.protocol.RemoteIntentCodec.CAPABILITY, true)
        observeFeatureMessages("watch_faces", setOf(WatchFaceProtocol.OPERATION_RESPONSE, WatchFaceProtocol.MOBVOI_IMAGE,
            WatchFaceProtocol.MOBVOI_PHOTO_INFO, WatchFaceProtocol.MOBVOI_PHOTO_CONFIRM, WatchFaceProtocol.MOBVOI_DOWNLOAD)) { message, peer, sessionId -> watchFaces.offerMessage(peer, sessionId, message.path, message.payload, message.route) }
        data.setRemoteItemObserver { sessionId, item -> notifications.offerRemoteItem(sessionId, item) }
        observeFeatureMessages("notifications", setOf("/bridger/rpc")) { message, peer, sessionId ->
            notifications.handleMessage(peer, sessionId, message.path, message.payload)
        }
        worker.launch {
            try { account.restore() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Exception) { log("account_restore_failed", null, 0, problem) }
        }
        worker.launch {
            combine(backend.state, account.state, cloudSettings.state) { link, account, privacy ->
                val peer = link.peer
                if (privacy.allows(CloudCapability.MOBVOI_CLOUD) && link.phase == ConnectionPhase.LINK_READY && peer != null && account.phase == MobvoiAccountPhase.SIGNED_IN)
                    Triple(peer, link.sessionId, account.profile) else null
            }.distinctUntilChanged().collectLatest { publication ->
                    val (peer, sessionId, _) = publication ?: return@collectLatest
                    try { accountBridge.publishCompanionInfo(companionHostInfo()) { path, payload -> backend.sendMessage(peer, sessionId, path, payload) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (problem: Exception) { log("account_watch_publish_failed", peer, sessionId, problem) }
                }
        }
        featureRegistrations += backend.registerMessageHandler("account", setOf("/accounts/start_host_activity",
            MobvoiWatchAccountBridge.LOGIN, MobvoiWatchAccountBridge.SYNC_ACCOUNT_INFO)) { event ->
            val link = backend.state.value
            if (link.peer == event.peer && link.sessionId == event.sessionId && link.phase == ConnectionPhase.LINK_READY) {
                when (event.message.path) {
                    "/accounts/start_host_activity" -> {
                        // An account picker/transfer can take minutes. It must not occupy the
                        // observer that receives watch login/profile messages in the meantime.
                        if (hostActivityTransfer?.isActive != true) hostActivityTransfer = worker.launch {
                            try { google.handleHostActivityRequest(event.peer, event.sessionId, event.message.payload) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (problem: Exception) { log("google_watch_request_failed", event.peer, event.sessionId, problem) }
                        } else runCatching { diagnostics.record(ProtocolDiagnostic("runtime", "google_host_request_already_running",
                            peer = event.peer, sessionId = event.sessionId, details = mapOf("decision" to "retain current transfer; continue other MessageApi work"))) }
                    }
                    MobvoiWatchAccountBridge.LOGIN -> if (cloudSettings.state.value.allows(CloudCapability.MOBVOI_CLOUD)) {
                        accountBridge.handleLoginRequest(companionHostInfo()) { path, payload -> backend.sendMessage(event.peer, event.sessionId, path, payload) }
                    }
                    MobvoiWatchAccountBridge.SYNC_ACCOUNT_INFO -> accountBridge.acceptCompactProfile(event.message.payload)
                }
            }
        }
        data.setProgressTransport(DataSyncProgressTransport { session, payload, timeout ->
            backend.request(session.peer, session.sessionId, CompanionDataInitializer.DATA_SYNC_PROGRESS, payload, timeout,
                route = CompanionDataInitializer.GMS_ROUTE)
        })
        progressHandler = backend.registerRequestHandler(CompanionDataInitializer.DATA_SYNC_PROGRESS, CompanionDataInitializer.GMS_ROUTE) {
            data.respondProgressBarrier(it.sessionId, it.payload)
        }
        worker.launch {
            var preparedSession: Long? = null
            data.state.collect { state ->
                if (state.phase == DataSyncPhase.ACTIVE && state.sessionId != preparedSession) {
                    preparedSession = state.sessionId
                    try { deviceSettings.awaitReady(); initializer.prepare(state.sessionId) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (problem: Exception) { log("automatic_initial_data_failed", state.scope?.peer, state.sessionId, problem) }
                }
            }
        }
    }

    /** Called only by the foreground service's serialized peer-selection command. */
    suspend fun startPeer(peer: PeerAddress) {
        check(!closed.get())
        if (unpair.state.value.busy) throw PairingBusyException()
        if (backend.state.value.peer != peer) setup.cancel()
        if (pairing.state.value.peer != peer) pairing.cancel()
        selectedPeer = peer
        try {
            if (cloudSettings.state.value.allows(CloudCapability.WATCH_INTERNET_PROXY)) proxy.start(peer)
            else proxy.stop()
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) { log("proxy_start_failed", peer, 0, problem) }
    }

    suspend fun stopPeer() {
        selectedPeer = null
        setup.cancel()
        pairing.cancel()
        google.abortAccountTransfer()
        proxy.stop()
    }

    /** Frontend settings changes start/stop optional services at this one orchestration boundary. */
    private suspend fun applyCloudSettings(settings: CloudPrivacySettings) {
        if (!settings.allows(CloudCapability.MOBVOI_CLOUD)) { health.cancelSync(); http.stop() }
        if (!settings.allows(CloudCapability.GOOGLE_ACCOUNT_TRANSFER)) google.abortAccountTransfer()
        val peer = selectedPeer
        if (peer != null && settings.allows(CloudCapability.WATCH_INTERNET_PROXY)) proxy.start(peer)
        else proxy.stop()
        runCatching { diagnostics.record(ProtocolDiagnostic("privacy", "frontend_settings_applied",
            details = mapOf("enabledFeatures" to settings.enabledCapabilities.joinToString(",") { it.name }))) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        toolbox.close()
        dnd.close()
        dndRemote.close()
        setup.close()
        unpair.close()
        unpairObservation.close()
        pairing.close()
        google.close()
        localHealth.close()
        healthRecords.close()
        health.close()
        account.close()
        http.close()
        progressHandler.close()
        featureRegistrations.forEach { it.close() }
        remoteIntents.close()
        capabilities.close()
        calling.close()
        callingPlatform.close()
        media.close()
        mediaSource.close()
        finding.close()
        deviceSettings.close()
        sos.close()
        sosPlatform.close()
        healthModule.close()
        notificationAlertPatch.close()
        watchModuleHost.close()
        monitoring.close()
        watchFaces.close()
        notifications.close()
        notificationSource.close()
        data.setRemoteItemObserver(null)
        proxy.close()
        backend.close()
        lifetime.cancel()
        data.close() // Owns the shared database, including history.
    }

    private suspend fun companionHostInfo(): MobvoiCompanionHostInfo {
        return MobvoiCompanionHostInfo(Build.MODEL, Build.VERSION.RELEASE,
            packageInfo.versionName ?: "", packageInfo.longVersionCode.toString(), identities.loadOrCreate().nodeId)
    }

    private fun featureSession(session: EstablishedSession) = CompanionFeatureSession(session.sessionId,
        DataScope(session.peer, session.localIdentity.nodeId, session.remoteNodeId,
            session.remoteNetworkId ?: session.localIdentity.networkId), session.route,
        send = { key, path, bytes -> backend.sendMessage(session.peer, session.sessionId, path, bytes, key) },
        request = { key, path, bytes, timeout -> backend.request(session.peer, session.sessionId, path, bytes, timeout, key) },
        open = { key, path -> backend.openChannel(session.peer, session.sessionId, path, route = key) })

    private fun observeFeatureMessages(name: String, paths: Set<String>, route: ApplicationRoute? = null,
        awaitAttachment: Boolean = false, handler: suspend (InboundMessage, PeerAddress, Long) -> Any) {
        featureRegistrations += backend.registerMessageHandler(name, paths, route) { event ->
            if (awaitAttachment) featureReady[event.sessionId]?.await()
            val link = backend.state.value
            if (link.sessionId == event.sessionId && link.peer == event.peer && link.phase == ConnectionPhase.LINK_READY)
                handler(event.message, event.peer, event.sessionId)
        }
    }

    private fun log(event: String, peer: PeerAddress?, sessionId: Long, problem: Exception) {
        runCatching { diagnostics.record(ProtocolDiagnostic("runtime", event, peer = peer, sessionId = sessionId,
            details = mapOf("exception" to problem.javaClass.simpleName, "decision" to "retain independent workflows and expose their state"))) }
    }
}
