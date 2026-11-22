package dev.ticbox.companion.ui

import dev.ticbox.companion.api.CloudSettingsClient
import dev.ticbox.companion.api.CompanionCallingClient
import dev.ticbox.companion.api.CompanionServices
import dev.ticbox.companion.api.ConnectionRecoveryClient
import dev.ticbox.companion.api.DataSyncClient
import dev.ticbox.companion.api.DeviceSettingsClient
import dev.ticbox.companion.api.DndSyncClient
import dev.ticbox.companion.api.FindDevicesClient
import dev.ticbox.companion.api.GoogleServices
import dev.ticbox.companion.api.HealthModuleClient
import dev.ticbox.companion.api.HealthRecordsClient
import dev.ticbox.companion.api.LocalHealthSyncClient
import dev.ticbox.companion.api.MediaControlsClient
import dev.ticbox.companion.api.MobvoiAccountClient
import dev.ticbox.companion.api.MobvoiCloudHealthClient
import dev.ticbox.companion.api.MonitoringSettingsClient
import dev.ticbox.companion.api.NetworkProxyState
import dev.ticbox.companion.api.NotificationAlertPatchClient
import dev.ticbox.companion.api.NotificationSyncClient
import dev.ticbox.companion.api.PairingClient
import dev.ticbox.companion.api.SetupClient
import dev.ticbox.companion.api.SosClient
import dev.ticbox.companion.api.WatchFaceManagement
import dev.ticbox.companion.api.WatchHistoryClient
import dev.ticbox.companion.api.WatchModuleHostClient
import dev.ticbox.companion.api.WatchToolboxClient
import dev.ticbox.companion.api.WatchUnpairClient
import dev.ticbox.companion.ui.bridge.ModuleSetupState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The subset of backend clients the phone UI uses. */
data class WatchUiServices(
    val privacy: CloudSettingsClient,
    val pairing: PairingClient,
    val setup: SetupClient,
    val notifications: NotificationSyncClient,
    val media: MediaControlsClient,
    val finding: FindDevicesClient,
    val deviceSettings: DeviceSettingsClient,
    val watchFaces: WatchFaceManagement,
    val recovery: ConnectionRecoveryClient,
    val history: WatchHistoryClient,
    val healthRecords: HealthRecordsClient,
    val localHealth: LocalHealthSyncClient,
    val unpair: WatchUnpairClient = WatchUnpairClient.UNAVAILABLE,
    val data: DataSyncClient,
    val calling: CompanionCallingClient,
    val network: StateFlow<NetworkProxyState>,
    val account: MobvoiAccountClient,
    val cloudHealth: MobvoiCloudHealthClient,
    val google: GoogleServices,
    val toolbox: WatchToolboxClient = WatchToolboxClient.UNAVAILABLE,
    val dnd: DndSyncClient = DndSyncClient.UNAVAILABLE,
    val monitoring: MonitoringSettingsClient = MonitoringSettingsClient.UNAVAILABLE,
    val sos: SosClient = SosClient.UNAVAILABLE,
    val healthModule: HealthModuleClient = HealthModuleClient.UNAVAILABLE,
    val notificationAlertPatch: NotificationAlertPatchClient =
        NotificationAlertPatchClient.UNAVAILABLE,
    val watchModuleHost: WatchModuleHostClient = WatchModuleHostClient.UNAVAILABLE,
    val moduleSetup: MutableStateFlow<ModuleSetupState> = MutableStateFlow(ModuleSetupState()),
)

fun CompanionServices.forUi() =
    WatchUiServices(
        privacy = privacy,
        pairing = pairing,
        setup = setup,
        notifications = notifications,
        media = media,
        finding = finding,
        deviceSettings = deviceSettings,
        watchFaces = watchFaces,
        recovery = recovery,
        history = history,
        healthRecords = healthRecords,
        localHealth = localHealth,
        unpair = unpair,
        data = data,
        calling = calling,
        network = network,
        account = account,
        cloudHealth = health,
        google = google,
        toolbox = toolbox,
        dnd = dnd,
        monitoring = monitoring,
        sos = sos,
        healthModule = healthModule,
        notificationAlertPatch = notificationAlertPatch,
        watchModuleHost = watchModuleHost,
    )
