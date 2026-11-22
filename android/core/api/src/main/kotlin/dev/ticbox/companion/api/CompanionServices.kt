package dev.ticbox.companion.api

import kotlinx.coroutines.flow.StateFlow

/** Additional service-owned workflows for the single APK frontend. Unbinding never closes them. */
interface CompanionServices {
    val toolbox: WatchToolboxClient get() = WatchToolboxClient.UNAVAILABLE
    val dnd: DndSyncClient get() = DndSyncClient.UNAVAILABLE
    val sos: SosClient get() = SosClient.UNAVAILABLE
    val healthModule: HealthModuleClient get() = HealthModuleClient.UNAVAILABLE
    val notificationAlertPatch: NotificationAlertPatchClient get() = NotificationAlertPatchClient.UNAVAILABLE
    val watchModuleHost: WatchModuleHostClient get() = WatchModuleHostClient.UNAVAILABLE
    val monitoring: MonitoringSettingsClient get() = MonitoringSettingsClient.UNAVAILABLE
    /** All vendor/cloud and Internet relay capabilities default off. */
    val privacy: CloudSettingsClient
    val pairing: PairingClient
    /** Explicit remote unpair preserving watch data; distinct from temporary disconnect. */
    val unpair: WatchUnpairClient get() = WatchUnpairClient.UNAVAILABLE
    val setup: SetupClient
    val data: DataSyncClient
    /** Local phone notifications; system notification-listener access is granted by the user. */
    val notifications: NotificationSyncClient
    val calling: CompanionCallingClient
    val media: MediaControlsClient
    val finding: FindDevicesClient
    val deviceSettings: DeviceSettingsClient
    val watchFaces: WatchFaceManagement
    /** Foreground-service recovery is supplied by the service host. */
    val recovery: ConnectionRecoveryClient get() = ConnectionRecoveryClient.UNAVAILABLE
    val network: StateFlow<NetworkProxyState>
    val history: WatchHistoryClient
    val account: MobvoiAccountClient
    /** Optional Mobvoi cloud operations and typed cache queries. Raw local reads use healthRecords. */
    val health: MobvoiCloudHealthClient
    /** Source-neutral local records; no cloud account is required for a local-user/watch source. */
    val healthRecords: HealthRecordsClient
    val localHealth: LocalHealthSyncClient
    val google: GoogleServices
}
