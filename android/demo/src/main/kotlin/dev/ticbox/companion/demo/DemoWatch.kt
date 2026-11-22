package dev.ticbox.companion.demo

import android.content.Context
import dev.ticbox.companion.api.ApplicationRoute
import dev.ticbox.companion.api.CloudCapability
import dev.ticbox.companion.api.CloudPrivacySettings
import dev.ticbox.companion.api.CloudSettingsClient
import dev.ticbox.companion.api.CompanionCallingClient
import dev.ticbox.companion.api.CompanionDevicePreferences
import dev.ticbox.companion.api.ConnectionPhase
import dev.ticbox.companion.api.ConnectionRecoveryClient
import dev.ticbox.companion.api.ConnectionRecoveryPhase
import dev.ticbox.companion.api.ConnectionRecoveryState
import dev.ticbox.companion.api.ConnectionState
import dev.ticbox.companion.api.DataScope
import dev.ticbox.companion.api.DataSyncClient
import dev.ticbox.companion.api.DeviceCommandResult
import dev.ticbox.companion.api.DeviceCommandStatus
import dev.ticbox.companion.api.DeviceSettingsClient
import dev.ticbox.companion.api.DeviceSettingsState
import dev.ticbox.companion.api.FindDeviceSettings
import dev.ticbox.companion.api.FindDeviceState
import dev.ticbox.companion.api.FindDevicesClient
import dev.ticbox.companion.api.FindWatchStatus
import dev.ticbox.companion.api.GoogleServices
import dev.ticbox.companion.api.HealthModuleClient
import dev.ticbox.companion.api.HealthRecordSource
import dev.ticbox.companion.api.HealthRecordsClient
import dev.ticbox.companion.api.LocalHealthJournalSource
import dev.ticbox.companion.api.LocalHealthJournalStatus
import dev.ticbox.companion.api.LocalHealthSyncClient
import dev.ticbox.companion.api.LocalHealthSyncMode
import dev.ticbox.companion.api.LocalHealthSyncPhase
import dev.ticbox.companion.api.LocalHealthSyncStage
import dev.ticbox.companion.api.LocalHealthSyncState
import dev.ticbox.companion.api.LocalHealthSyncTiming
import dev.ticbox.companion.api.MediaControlsClient
import dev.ticbox.companion.api.MediaControlsPhase
import dev.ticbox.companion.api.MediaControlsState
import dev.ticbox.companion.api.MediaSessionSummary
import dev.ticbox.companion.api.MobvoiAccountClient
import dev.ticbox.companion.api.MobvoiCloudHealthClient
import dev.ticbox.companion.api.MonitoringSettingsClient
import dev.ticbox.companion.api.NetworkProxyState
import dev.ticbox.companion.api.NotificationAlertMode
import dev.ticbox.companion.api.NotificationAlertPatchClient
import dev.ticbox.companion.api.NotificationAppAlertSettings
import dev.ticbox.companion.api.NotificationFilterMode
import dev.ticbox.companion.api.NotificationSyncClient
import dev.ticbox.companion.api.NotificationSyncPhase
import dev.ticbox.companion.api.NotificationSyncState
import dev.ticbox.companion.api.PairingClient
import dev.ticbox.companion.api.PairingOptions
import dev.ticbox.companion.api.PairingPermissionTransfer
import dev.ticbox.companion.api.PairingPhase
import dev.ticbox.companion.api.PairingSnapshot
import dev.ticbox.companion.api.PairingUserAction
import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.api.PhoneRingingState
import dev.ticbox.companion.api.SetupClient
import dev.ticbox.companion.api.SetupOptions
import dev.ticbox.companion.api.SetupPhase
import dev.ticbox.companion.api.SetupProbe
import dev.ticbox.companion.api.SetupSnapshot
import dev.ticbox.companion.api.SetupStep
import dev.ticbox.companion.api.SetupStepProgress
import dev.ticbox.companion.api.SetupStepState
import dev.ticbox.companion.api.SetupUserResponse
import dev.ticbox.companion.api.SosClient
import dev.ticbox.companion.api.StepSnapshot
import dev.ticbox.companion.api.WatchClient
import dev.ticbox.companion.api.WatchDeviceInfo
import dev.ticbox.companion.api.WatchFaceArchive
import dev.ticbox.companion.api.WatchFaceComplication
import dev.ticbox.companion.api.WatchFaceCurrent
import dev.ticbox.companion.api.WatchFaceEditingState
import dev.ticbox.companion.api.WatchFaceFavorite
import dev.ticbox.companion.api.WatchFaceImage
import dev.ticbox.companion.api.WatchFaceInstalled
import dev.ticbox.companion.api.WatchFaceManagement
import dev.ticbox.companion.api.WatchFaceManagementState
import dev.ticbox.companion.api.WatchFaceOperationResult
import dev.ticbox.companion.api.WatchFaceOutcome
import dev.ticbox.companion.api.WatchFacePhoto
import dev.ticbox.companion.api.WatchFacePhotoSettings
import dev.ticbox.companion.api.WatchFacePreviewOptions
import dev.ticbox.companion.api.WatchHistoryClient
import dev.ticbox.companion.api.WatchHistorySample
import dev.ticbox.companion.api.WatchInstalledApp
import dev.ticbox.companion.api.WatchLockPhase
import dev.ticbox.companion.api.WatchLockState
import dev.ticbox.companion.api.WatchLockType
import dev.ticbox.companion.api.WatchModuleHostClient
import dev.ticbox.companion.api.WatchObservation
import dev.ticbox.companion.api.WatchSampleKind
import dev.ticbox.companion.api.WatchSettingsScreen
import dev.ticbox.companion.api.WatchSnapshot
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * An in-memory watch with sample data. It implements the same core:api clients as the local
 * service, so screens render it without knowing it is simulated. Nothing here touches Bluetooth,
 * storage, the network or the real backend. Commands change local state after short delays.
 */
class DemoWatch(
    context: Context,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val resources = context.applicationContext.resources
    val peer = PeerAddress("02:00:00:00:00:01")
    private val records = DemoHealthRecords(peer, clock)

    private fun now() = clock.millis()

    private fun text(id: Int) = resources.getString(id)

    // Watch link and observations.

    private val watch =
        MutableStateFlow(
            WatchSnapshot(
                connection = connectedState(),
                battery = WatchObservation(82, now(), stale = false),
                steps =
                    WatchObservation(
                        StepSnapshot(records.stepsToday(), 8_000),
                        now(),
                        stale = false,
                    ),
            )
        )

    private fun connectedState() =
        ConnectionState(
            phase = ConnectionPhase.LINK_READY,
            peer = peer,
            sessionId = 1,
            peerName = text(R.string.demo_watch_name),
            negotiatedWireVersion = 2,
        )

    val client: WatchClient =
        object : WatchClient {
            override val state = watch.asStateFlow()

            override suspend fun connect(peer: PeerAddress) {
                require(peer == this@DemoWatch.peer) { "Unknown demo peer" }
                reconnect()
            }

            override suspend fun disconnect() {
                watch.update {
                    it.copy(
                        connection = it.connection.copy(phase = ConnectionPhase.IDLE),
                        battery = it.battery?.copy(stale = true),
                        steps = it.steps?.copy(stale = true),
                    )
                }
                recoveryState.value =
                    ConnectionRecoveryState(desiredPeer = peer, durable = true, paused = true)
            }

            override suspend fun refreshBattery() {
                delay(SHORT_DELAY)
                watch.update { it.copy(battery = it.battery?.copy(receivedAtEpochMillis = now())) }
            }

            override suspend fun refreshHealth() = Unit

            override suspend fun setStepGoal(goal: Int) {
                require(goal > 0)
                delay(SHORT_DELAY)
                watch.update {
                    val steps = it.steps?.value ?: StepSnapshot(0, goal)
                    it.copy(steps = WatchObservation(steps.copy(goal = goal), now(), stale = false))
                }
            }

            override fun close() = Unit
        }

    private suspend fun reconnect() {
        watch.update {
            it.copy(connection = it.connection.copy(phase = ConnectionPhase.CONNECTING))
        }
        delay(SHORT_DELAY)
        watch.update {
            it.copy(
                connection = connectedState(),
                battery = it.battery?.copy(receivedAtEpochMillis = now(), stale = false),
                steps = it.steps?.copy(receivedAtEpochMillis = now(), stale = false),
            )
        }
        recoveryState.value = runningRecovery()
    }

    // Connection recovery.

    /** Paired through the system like a real watch, so it holds an association. */
    private fun runningRecovery() =
        ConnectionRecoveryState(
            ConnectionRecoveryPhase.RUNNING,
            desiredPeer = peer,
            associationId = 1,
            durable = true,
        )

    private val recoveryState = MutableStateFlow(runningRecovery())

    val recovery: ConnectionRecoveryClient =
        object : ConnectionRecoveryClient {
            override val state = recoveryState.asStateFlow()

            override suspend fun resume() = reconnect()
        }

    // Privacy switches.

    private val privacyState = MutableStateFlow(CloudPrivacySettings())

    val privacy: CloudSettingsClient =
        object : CloudSettingsClient {
            override val state = privacyState.asStateFlow()

            override suspend fun setEnabled(
                capability: CloudCapability,
                enabled: Boolean,
            ): CloudPrivacySettings {
                privacyState.update {
                    val capabilities = it.enabledCapabilities
                    it.copy(if (enabled) capabilities + capability else capabilities - capability)
                }
                return privacyState.value
            }
        }

    // Pairing and setup: the demo watch is already paired; repeating the flow simulates it.

    private val pairingState =
        MutableStateFlow(
            PairingSnapshot(
                operationId = 1,
                phase = PairingPhase.COMPLETED,
                peer = peer,
                bonded = true,
                linkSessionId = 1,
                permissionTransfer = PairingPermissionTransfer.COMPLETED,
            )
        )
    private var pairingJob: Job? = null

    val pairing: PairingClient =
        object : PairingClient {
            override val state = pairingState.asStateFlow()
            override val actions: StateFlow<List<PairingUserAction>> =
                MutableStateFlow(emptyList<PairingUserAction>()).asStateFlow()

            override suspend fun start(options: PairingOptions): Long {
                val id = pairingState.value.operationId + 1
                pairingJob?.cancel()
                pairingJob = scope.launch {
                    for (phase in
                        listOf(
                            PairingPhase.PREPARING,
                            PairingPhase.ASSOCIATING,
                            PairingPhase.BONDING,
                            PairingPhase.CONNECTING,
                        )) {
                        pairingState.value = PairingSnapshot(operationId = id, phase = phase)
                        delay(STEP_DELAY)
                    }
                    reconnect()
                    pairingState.value =
                        PairingSnapshot(
                            operationId = id,
                            phase = PairingPhase.COMPLETED,
                            peer = peer,
                            bonded = true,
                            linkSessionId = 1,
                        )
                }
                return id
            }

            override suspend fun syncPermissions(peer: PeerAddress): Long {
                delay(SHORT_DELAY)
                pairingState.update {
                    it.copy(permissionTransfer = PairingPermissionTransfer.COMPLETED)
                }
                return pairingState.value.operationId
            }

            override fun cancel() {
                cancel(pairingState.value.operationId)
            }

            override fun cancel(operationId: Long): Boolean {
                if (pairingState.value.operationId != operationId) return false
                pairingJob?.cancel()
                pairingState.update { it.copy(phase = PairingPhase.CANCELLED) }
                return true
            }

            override fun close() = Unit
        }

    private val setupState = MutableStateFlow(completedSetup(runId = 1))
    private var setupJob: Job? = null

    private fun completedSetup(runId: Long) =
        SetupSnapshot(
            runId = runId,
            peer = peer,
            sessionId = 1,
            phase = SetupPhase.COMPLETED,
            progress = SETUP_STEPS.map { SetupStepProgress(it, SetupStepState.SUCCEEDED) },
            watchCompleteObserved = true,
            dataPreparationCompleted = true,
        )

    val setup: SetupClient =
        object : SetupClient {
            override val state = setupState.asStateFlow()

            override suspend fun probe(): SetupProbe {
                delay(SHORT_DELAY)
                return SetupProbe(peer, sessionId = 1, watchStatusValue = 3, systemInfo = null)
            }

            override suspend fun start(options: SetupOptions) {
                val runId = setupState.value.runId + 1
                setupJob?.cancel()
                setupJob = scope.launch {
                    SETUP_STEPS.forEachIndexed { index, step ->
                        setupState.value =
                            SetupSnapshot(
                                runId = runId,
                                peer = peer,
                                sessionId = 1,
                                phase = SetupPhase.RUNNING,
                                currentStep = step,
                                progress =
                                    SETUP_STEPS.take(index).map {
                                        SetupStepProgress(it, SetupStepState.SUCCEEDED)
                                    } + SetupStepProgress(step, SetupStepState.RUNNING),
                            )
                        delay(STEP_DELAY)
                    }
                    setupState.value = completedSetup(runId)
                }
            }

            override fun resume(actionId: Long, response: SetupUserResponse) = false

            override fun cancel() {
                setupJob?.cancel()
                setupState.update { it.copy(phase = SetupPhase.CANCELLED, currentStep = null) }
            }

            override fun close() = Unit
        }

    // Phone-side features mirrored to the watch.

    private val notificationState =
        MutableStateFlow(
            NotificationSyncState(
                phase = NotificationSyncPhase.ACTIVE,
                accessGranted = true,
                listenerConnected = true,
                sessionId = 1,
                activeNotificationCount = 3,
                mirroredNotificationCount = 3,
            )
        )

    val notifications: NotificationSyncClient =
        object : NotificationSyncClient {
            override val state = notificationState.asStateFlow()

            override suspend fun setEnabled(enabled: Boolean) {
                notificationState.update {
                    it.copy(
                        settings = it.settings.copy(enabled = enabled),
                        phase =
                            if (enabled) NotificationSyncPhase.ACTIVE
                            else NotificationSyncPhase.DISABLED,
                    )
                }
            }

            override suspend fun setPackageEnabled(packageName: String, enabled: Boolean) {
                notificationState.update {
                    val settings = it.settings
                    it.copy(
                        settings =
                            when (settings.mode) {
                                NotificationFilterMode.ALLOWLIST ->
                                    settings.copy(
                                        allowedPackages =
                                            if (enabled) settings.allowedPackages + packageName
                                            else settings.allowedPackages - packageName
                                    )
                                NotificationFilterMode.BLOCKLIST ->
                                    settings.copy(
                                        blockedPackages =
                                            if (enabled) settings.blockedPackages - packageName
                                            else settings.blockedPackages + packageName
                                    )
                            }
                    )
                }
            }

            override suspend fun setPackageFilter(
                mode: NotificationFilterMode,
                packages: Set<String>,
            ) {
                notificationState.update {
                    it.copy(
                        settings =
                            when (mode) {
                                NotificationFilterMode.ALLOWLIST ->
                                    it.settings.copy(
                                        mode = mode,
                                        allowedPackages = packages.toSet(),
                                    )
                                NotificationFilterMode.BLOCKLIST ->
                                    it.settings.copy(
                                        mode = mode,
                                        blockedPackages = packages.toSet(),
                                    )
                            }
                    )
                }
            }

            override suspend fun setSoundEnabled(enabled: Boolean) {
                notificationState.update {
                    it.copy(settings = it.settings.copy(soundEnabled = enabled))
                }
            }

            override suspend fun setVibrationEnabled(enabled: Boolean) {
                notificationState.update {
                    it.copy(settings = it.settings.copy(vibrationEnabled = enabled))
                }
            }

            override suspend fun setPackageSoundMode(
                packageName: String,
                mode: NotificationAlertMode,
            ) {
                updateAppAlerts(packageName) { it.copy(sound = mode) }
            }

            override suspend fun setPackageVibrationMode(
                packageName: String,
                mode: NotificationAlertMode,
            ) {
                updateAppAlerts(packageName) { it.copy(vibration = mode) }
            }

            override suspend fun setPackageAlertSettings(
                packageName: String,
                settings: NotificationAppAlertSettings,
            ) {
                updateAppAlerts(packageName) { settings }
            }

            private fun updateAppAlerts(
                packageName: String,
                change: (NotificationAppAlertSettings) -> NotificationAppAlertSettings,
            ) {
                require(NotificationAppAlertSettings.isValidPackageName(packageName))
                notificationState.update {
                    val next = change(it.settings.alertsFor(packageName))
                    val rules =
                        if (next == NotificationAppAlertSettings())
                            it.settings.appAlerts - packageName
                        else it.settings.appAlerts + (packageName to next)
                    require(rules.size <= NotificationAppAlertSettings.MAX_PACKAGES)
                    it.copy(settings = it.settings.copy(appAlerts = rules))
                }
            }

            override suspend fun resync() = Unit

            override fun close() = Unit
        }

    private val mediaState = MutableStateFlow(demoMedia())

    private fun demoMedia(current: MediaControlsState = MediaControlsState()) =
        current.copy(
            phase = MediaControlsPhase.ACTIVE,
            accessGranted = true,
            sessionId = 1,
            published = true,
            sessions =
                listOf(
                    MediaSessionSummary(
                        id = "demo-player",
                        packageName = "dev.ticbox.companion.demo.player",
                        applicationLabel = text(R.string.demo_media_app),
                        title = text(R.string.demo_media_title),
                        artist = text(R.string.demo_media_artist),
                        playing = true,
                    )
                ),
            selectedSessionId = "demo-player",
        )

    val media: MediaControlsClient =
        object : MediaControlsClient {
            override val state = mediaState.asStateFlow()

            override suspend fun setEnabled(enabled: Boolean) {
                mediaState.update {
                    it.copy(
                        settings = it.settings.copy(enabled = enabled),
                        phase =
                            if (enabled) MediaControlsPhase.ACTIVE else MediaControlsPhase.DISABLED,
                    )
                }
            }

            override suspend fun setPreferredPackage(packageName: String?) {
                mediaState.update {
                    it.copy(settings = it.settings.copy(preferredPackage = packageName))
                }
            }

            override suspend fun setAutoLaunch(enabled: Boolean) {
                mediaState.update { it.copy(settings = it.settings.copy(autoLaunch = enabled)) }
            }

            override suspend fun resync() = Unit

            override fun close() = Unit
        }

    private val findState =
        MutableStateFlow(
            FindDeviceState(
                sessionId = 1,
                capabilityPublished = true,
                settingsReady = true,
                watchStatus = FindWatchStatus.IDLE,
            )
        )
    private var phoneRing: Job? = null

    val finding: FindDevicesClient =
        object : FindDevicesClient {
            override val state = findState.asStateFlow()

            override suspend fun setEnabled(enabled: Boolean) {
                findState.update { it.copy(settings = FindDeviceSettings(enabled)) }
            }

            override suspend fun ringPhone() {
                // The demo never plays sound; it shows the ringing state for a few seconds.
                findState.update { it.copy(phone = PhoneRingingState(active = true)) }
                phoneRing?.cancel()
                phoneRing = scope.launch {
                    delay(RING_MILLIS)
                    findState.update { it.copy(phone = PhoneRingingState()) }
                }
            }

            override suspend fun stopPhone() {
                phoneRing?.cancel()
                findState.update { it.copy(phone = PhoneRingingState()) }
            }

            override suspend fun ringWatch() {
                delay(SHORT_DELAY)
                findState.update {
                    it.copy(
                        watchStatus = FindWatchStatus.RINGING,
                        watchRingingConfirmed = true,
                        watchAudioPlaybackStarted = true,
                        watchVibrationRequested = true,
                        watchStopAvailable = true,
                    )
                }
            }

            override suspend fun stopWatch() {
                delay(SHORT_DELAY)
                findState.update {
                    it.copy(
                        watchStatus = FindWatchStatus.STOPPED,
                        watchRingingConfirmed = false,
                        watchAudioPlaybackStarted = false,
                        watchVibrationRequested = false,
                        watchStopAvailable = false,
                    )
                }
            }

            override fun close() = Unit
        }

    // Watch preferences.

    private val deviceState =
        MutableStateFlow(
            DeviceSettingsState(
                sessionId = 1,
                preferencesReady = true,
                info = WatchDeviceInfo(model = text(R.string.demo_watch_model), sdkVersion = 33),
                batteryPercent = 82,
                apps =
                    listOf(
                            R.string.demo_app_weather to "com.example.demo.weather",
                            R.string.demo_app_timer to "com.example.demo.timer",
                            R.string.demo_app_compass to "com.example.demo.compass",
                        )
                        .map { (label, name) -> WatchInstalledApp(name, text(label), true, null) },
            )
        )

    private fun confirmed() = DeviceCommandResult(DeviceCommandStatus.REMOTE_CONFIRMED)

    private val demoMonitoring = DemoMonitoring(peer, SHORT_DELAY)
    val monitoring: MonitoringSettingsClient = demoMonitoring

    private val demoSos = DemoSos(peer, SHORT_DELAY, ::now, text(R.string.demo_sos_contact))
    val sos: SosClient = demoSos

    val healthModule: HealthModuleClient = DemoHealthModule(SHORT_DELAY)
    val notificationAlertPatch: NotificationAlertPatchClient = DemoAlertPatch(SHORT_DELAY)
    val watchModuleHost: WatchModuleHostClient = DemoModuleHost(SHORT_DELAY)

    val deviceSettings: DeviceSettingsClient =
        object : DeviceSettingsClient {
            override val state = deviceState.asStateFlow()

            override suspend fun refresh(): DeviceCommandResult {
                delay(SHORT_DELAY)
                return confirmed()
            }

            override suspend fun setLanguageTags(languageTags: String?): DeviceCommandResult =
                updatePreferences {
                    it.copy(languageTags = languageTags)
                }

            override suspend fun set24HourFormat(enabled: Boolean?): DeviceCommandResult =
                updatePreferences {
                    it.copy(is24HourFormat = enabled)
                }

            override suspend fun openSettings(screen: WatchSettingsScreen): DeviceCommandResult {
                delay(SHORT_DELAY)
                return DeviceCommandResult(DeviceCommandStatus.REMOTE_UI_OPENED)
            }

            override suspend fun requestUninstall(packageName: String): DeviceCommandResult {
                delay(SHORT_DELAY)
                deviceState.update { state ->
                    state.copy(apps = state.apps.filterNot { it.packageName == packageName })
                }
                return DeviceCommandResult(DeviceCommandStatus.REMOTE_UI_OPENED)
            }

            override suspend fun queryLockSupport(): DeviceCommandResult {
                delay(SHORT_DELAY)
                setLock { it.copy(supportedTypes = WatchLockType.entries.map { it.wireValue }) }
                return confirmed()
            }

            override suspend fun configureLock(type: WatchLockType): DeviceCommandResult {
                delay(SHORT_DELAY)
                setLock { it.copy(phase = WatchLockPhase.CONFIGURATION_SCREEN) }
                return DeviceCommandResult(DeviceCommandStatus.REMOTE_UI_OPENED)
            }

            override suspend fun cancelLockConfiguration(): DeviceCommandResult {
                delay(SHORT_DELAY)
                setLock { it.copy(phase = WatchLockPhase.CANCELLED) }
                return confirmed()
            }

            override suspend fun setHeartHealthMonitoring(enabled: Boolean): DeviceCommandResult {
                delay(SHORT_DELAY)
                return DeviceCommandResult(DeviceCommandStatus.SUBMITTED_UNVERIFIED)
            }

            override suspend fun setHeartHealthFeatureVisible(
                visible: Boolean
            ): DeviceCommandResult {
                delay(SHORT_DELAY)
                if (!visible) demoMonitoring.heartHealthHidden()
                return DeviceCommandResult(DeviceCommandStatus.SUBMITTED_UNVERIFIED)
            }

            override suspend fun appIcon(packageName: String): ByteArray? = null

            override fun close() = Unit

            private suspend fun updatePreferences(
                change: (CompanionDevicePreferences) -> CompanionDevicePreferences
            ): DeviceCommandResult {
                delay(SHORT_DELAY)
                deviceState.update { it.copy(preferences = change(it.preferences)) }
                return confirmed()
            }

            private fun setLock(change: (WatchLockState) -> WatchLockState) {
                deviceState.update { it.copy(lock = change(it.lock)) }
            }
        }

    // Watch faces.

    private val faceNames =
        listOf(
            R.string.demo_face_essential,
            R.string.demo_face_sport,
            R.string.demo_face_daylight,
            R.string.demo_face_field,
        )
    private val faceArt = faceNames.indices.map { DemoFaceArt.render(it) }

    private fun installedFaces() = faceNames.mapIndexed { index, name ->
        WatchFaceInstalled(
            componentName = "$FACE_PACKAGE/.Face$index",
            displayedName = text(name),
            sourceUri = "demo:face/$index",
        )
    }

    private fun favorite(index: Int, id: Int) =
        WatchFaceFavorite(
            id = id,
            componentName = "$FACE_PACKAGE/.Face$index",
            preview = faceArt[index],
        )

    private fun initialFaces(): WatchFaceManagementState {
        val favorites = (0..2).map { favorite(it, id = it) }
        return WatchFaceManagementState(
            sessionId = 1,
            scope = DataScope(peer, localNodeId = "demo-phone", remoteNodeId = "demo-watch"),
            installed = installedFaces(),
            favorites = favorites,
            favoriteOrder = favorites.mapNotNull { it.id },
            current = WatchFaceCurrent(favorites[0].componentName, favorites[0].id),
            inventoryComplete = true,
        )
    }

    private val faceState = MutableStateFlow(initialFaces())

    private fun acknowledged() = WatchFaceOperationResult(WatchFaceOutcome.ACKNOWLEDGED)

    private fun faceUnavailable() = WatchFaceOperationResult(WatchFaceOutcome.UNAVAILABLE)

    val watchFaces: WatchFaceManagement =
        object : WatchFaceManagement {
            override val state = faceState.asStateFlow()

            override suspend fun refresh(): WatchFaceManagementState {
                delay(SHORT_DELAY)
                return faceState.value
            }

            override suspend fun previewAsset(digest: String): WatchFaceImage? = null

            override suspend fun addFavorite(
                componentName: String,
                flavorId: String?,
            ): WatchFaceOperationResult {
                delay(SHORT_DELAY)
                val faces = faceState
                val index = faces.value.installed.indexOfFirst { it.componentName == componentName }
                if (index < 0) return faceUnavailable()
                faces.update { current ->
                    if (current.favorites.any { it.componentName == componentName }) {
                        current
                    } else {
                        val id = (current.favorites.mapNotNull { it.id }.maxOrNull() ?: -1) + 1
                        current.copy(
                            favorites = current.favorites + favorite(index, id),
                            favoriteOrder = current.favoriteOrder + id,
                        )
                    }
                }
                return acknowledged()
            }

            override suspend fun removeFavorite(
                favoriteId: Int,
                fallbackFavoriteId: Int?,
            ): WatchFaceOperationResult {
                delay(SHORT_DELAY)
                faceState.update { state ->
                    val remaining = state.favorites.filterNot { it.id == favoriteId }
                    val next =
                        if (state.current?.favoriteId != favoriteId) {
                            state.current
                        } else {
                            remaining
                                .firstOrNull { it.id == fallbackFavoriteId }
                                ?.let { WatchFaceCurrent(it.componentName, it.id) }
                        }
                    state.copy(
                        favorites = remaining,
                        favoriteOrder = state.favoriteOrder - favoriteId,
                        current = next,
                    )
                }
                return acknowledged()
            }

            override suspend fun reorderFavorites(
                favoriteIds: List<Int>
            ): WatchFaceOperationResult {
                delay(SHORT_DELAY)
                faceState.update { it.copy(favoriteOrder = favoriteIds) }
                return acknowledged()
            }

            override suspend fun changeActiveFavorite(favoriteId: Int): WatchFaceOperationResult {
                delay(SHORT_DELAY)
                val faces = faceState
                val favorite =
                    faces.value.favorites.firstOrNull { it.id == favoriteId }
                        ?: return faceUnavailable()
                faces.update {
                    it.copy(current = WatchFaceCurrent(favorite.componentName, favoriteId))
                }
                return acknowledged()
            }

            // All sample faces share one package, so the demo keeps them.
            override suspend fun uninstallPackage(packageName: String): WatchFaceOperationResult {
                delay(SHORT_DELAY)
                return WatchFaceOperationResult(WatchFaceOutcome.SUBMITTED)
            }

            override suspend fun selectMobvoiFace(
                settings: WatchFacePhotoSettings
            ): WatchFaceOperationResult {
                delay(SHORT_DELAY)
                return WatchFaceOperationResult(WatchFaceOutcome.SUBMITTED)
            }

            override suspend fun queryPhoto(): WatchFacePhoto {
                delay(SHORT_DELAY)
                val saved = faceState.value.photo
                return WatchFacePhoto(
                    saved?.settings
                        ?: WatchFacePhotoSettings(
                            name = text(R.string.demo_face_photo),
                            timePlace = 0,
                            showBattery = 1,
                            showDateAndWeek = 1,
                        ),
                    saved?.image ?: faceArt.last(),
                )
            }

            override suspend fun setPhoto(
                settings: WatchFacePhotoSettings?,
                image: ByteArray,
            ): WatchFaceOperationResult {
                delay(SHORT_DELAY)
                faceState.update { state ->
                    state.copy(photo = WatchFacePhoto(settings, WatchFaceImage(image)))
                }
                return acknowledged()
            }

            override suspend fun startEditing(
                favoriteId: Int,
                width: Int,
                height: Int,
                abortCurrent: Boolean,
            ) = WatchFaceEditingState()

            override suspend fun updateEditing(options: WatchFacePreviewOptions): WatchFaceImage? =
                null

            override suspend fun previewEditing(options: WatchFacePreviewOptions): WatchFaceImage? =
                null

            override suspend fun previewComplication(
                complication: WatchFaceComplication,
                options: WatchFacePreviewOptions,
                calendarTimeMillis: Long?,
            ): WatchFaceImage? = null

            override suspend fun endEditing(favorite: WatchFaceFavorite) = faceUnavailable()

            override suspend fun abortEditing() = faceUnavailable()

            override suspend fun transferMwfArchive(
                archive: WatchFaceArchive,
                channelPath: String,
                route: ApplicationRoute,
            ) = faceUnavailable()

            override fun close() = Unit
        }

    // Stored data.

    val history: WatchHistoryClient =
        object : WatchHistoryClient {
            override suspend fun recent(
                peer: PeerAddress,
                kind: WatchSampleKind?,
                limit: Int,
                beforeId: Long?,
            ): List<WatchHistorySample> {
                if (peer != this@DemoWatch.peer) return emptyList()
                val newest = 48L
                return (newest downTo 1L)
                    .asSequence()
                    .filter { beforeId == null || it < beforeId }
                    .map { id -> historySample(id, newest) }
                    .filter { kind == null || it.kind == kind }
                    .take(limit)
                    .toList()
            }
        }

    private fun historySample(id: Long, newest: Long): WatchHistorySample {
        val receivedAt = now() - (newest - id) * 30 * 60_000L
        return if (id % 2 == 0L) {
            WatchHistorySample(
                id,
                peer,
                WatchSampleKind.STEPS,
                receivedAt,
                steps = StepSnapshot((id * 142).toInt(), 8_000),
            )
        } else {
            WatchHistorySample(
                id,
                peer,
                WatchSampleKind.BATTERY,
                receivedAt,
                batteryPercent = (100 - (newest - id) / 2).toInt(),
            )
        }
    }

    val healthRecords: HealthRecordsClient = records

    // Services behind the calling, account, cloud and diagnostics pages.

    val data: DataSyncClient = DemoDataSync()
    val calling: CompanionCallingClient =
        DemoCalling(text(R.string.demo_audio_earpiece), text(R.string.demo_audio_speaker))
    val network: StateFlow<NetworkProxyState> = demoNetworkState()
    val account: MobvoiAccountClient =
        DemoAccount(privacyState.asStateFlow(), text(R.string.demo_account_nickname))
    val cloudHealth: MobvoiCloudHealthClient = DemoCloudHealth(account.state, clock)
    val google: GoogleServices = DemoGoogle(privacyState.asStateFlow())

    private val healthSyncState =
        MutableStateFlow(
            LocalHealthSyncState(
                phase = LocalHealthSyncPhase.COMPLETED,
                source = HealthRecordSource.LocalDevice("demo", peer),
                sessionId = 1,
                recordsCommitted = 0,
                lastSuccessAtMillis = now(),
            )
        )
    private var syncJob: Job? = null
    private val journals = LocalHealthJournalSource.entries.associateWith { false }.toMutableMap()

    val localHealth: LocalHealthSyncClient =
        object : LocalHealthSyncClient {
            override val state = healthSyncState.asStateFlow()

            override suspend fun sync(mode: LocalHealthSyncMode) {
                if (syncJob?.isActive == true) {
                    if (
                        mode != LocalHealthSyncMode.FULL ||
                            healthSyncState.value.mode == LocalHealthSyncMode.FULL
                    )
                        return
                    syncJob?.cancel()
                }
                healthSyncState.update {
                    it.copy(phase = LocalHealthSyncPhase.CONNECTING, mode = mode)
                }
                syncJob = scope.launch {
                    val startedAt = now()
                    delay(SHORT_DELAY)
                    val pages = listOf(120, 260)
                    for (records in pages) {
                        healthSyncState.update {
                            it.copy(
                                phase = LocalHealthSyncPhase.READING,
                                recordsCommitted = records,
                            )
                        }
                        delay(SHORT_DELAY)
                    }
                    val table = "data_point"
                    val timing =
                        LocalHealthSyncTiming(
                            startedAtMillis = startedAt,
                            totalMillis = now() - startedAt,
                            stages =
                                listOf(
                                    LocalHealthSyncStage(table, "open", SHORT_DELAY),
                                    LocalHealthSyncStage(
                                        table,
                                        "read",
                                        SHORT_DELAY * pages.size,
                                        count = pages.size,
                                    ),
                                ),
                        )
                    healthSyncState.update {
                        it.copy(
                            phase = LocalHealthSyncPhase.COMPLETED,
                            lastSuccessAtMillis = now(),
                            lastRun = timing,
                        )
                    }
                }
            }

            override suspend fun syncIfStale() {
                val last = healthSyncState.value.lastSuccessAtMillis
                val age = last?.let { now() - it }
                if (
                    age == null || age !in 0 until LocalHealthSyncClient.AUTO_SYNC_INTERVAL_MILLIS
                ) {
                    sync()
                }
            }

            override suspend fun cancel() {
                syncJob?.cancel()
                healthSyncState.update { it.copy(phase = LocalHealthSyncPhase.IDLE) }
            }

            override suspend fun journalStatus(source: LocalHealthJournalSource) =
                journals.getValue(source).let { LocalHealthJournalStatus(source, it, it) }

            override suspend fun setJournalEnabled(
                source: LocalHealthJournalSource,
                enabled: Boolean,
            ): LocalHealthJournalStatus {
                delay(SHORT_DELAY)
                journals[source] = enabled
                return journalStatus(source)
            }

            override fun close() = Unit
        }

    /** Re-reads localized sample names after the app language changes. */
    fun onLocaleChanged() {
        watch.update {
            it.copy(connection = it.connection.copy(peerName = text(R.string.demo_watch_name)))
        }
        deviceState.update {
            it.copy(info = it.info?.copy(model = text(R.string.demo_watch_model)))
        }
        mediaState.update { demoMedia(it) }
        faceState.update { it.copy(installed = installedFaces()) }
        demoSos.renameSample(text(R.string.demo_sos_contact))
    }

    private companion object {
        const val SHORT_DELAY = 400L
        const val STEP_DELAY = 700L
        const val RING_MILLIS = 5_000L
        const val FACE_PACKAGE = "dev.ticbox.companion.demo.faces"
        val SETUP_STEPS =
            listOf(
                SetupStep.PROBE,
                SetupStep.TIME,
                SetupStep.TERMS,
                SetupStep.ACCOUNT,
                SetupStep.INITIAL_DATA_PREPARATION,
                SetupStep.COMPLETE,
            )
    }
}
