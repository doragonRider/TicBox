package dev.ticbox.companion.platform.service

import dev.ticbox.companion.api.ConnectionState
import dev.ticbox.companion.api.DataSyncState
import dev.ticbox.companion.api.NotificationSyncState

/** A read-only dumpsys projection. Never dump notifications, package lists, node IDs or failures' text. */
internal fun notificationDiagnostics(link: ConnectionState, data: DataSyncState, notifications: NotificationSyncState): List<String> = listOf(
    "TicBox notification sync (local observations; no watch display acknowledgement):",
    "  linkPhase=${link.phase} linkSessionId=${link.sessionId}",
    "  notificationPhase=${notifications.phase} notificationSessionId=${notifications.sessionId}",
    "  enabled=${notifications.settings.enabled} notificationAccessGranted=${notifications.accessGranted} listenerConnected=${notifications.listenerConnected}",
    "  filterMode=${notifications.settings.mode} allowedPackageCount=${notifications.settings.allowedPackages.size} blockedPackageCount=${notifications.settings.blockedPackages.size}",
    "  soundEnabled=${notifications.settings.soundEnabled} vibrationEnabled=${notifications.settings.vibrationEnabled} appAlertSettingsCount=${notifications.settings.appAlerts.size}",
    "  activeNotificationCount=${notifications.activeNotificationCount} mirroredNotificationCount=${notifications.mirroredNotificationCount} filteredNotificationCount=${notifications.filteredNotificationCount} pendingRemovalCount=${notifications.pendingRemovalCount}",
    "  dataPhase=${data.phase} dataSessionId=${data.sessionId} matchingNotificationSession=${notifications.sessionId != 0L && notifications.sessionId == data.sessionId && data.sessionId == link.sessionId}",
    "  sentDataItems=${data.sentItems} receivedDataItems=${data.receivedItems} pendingAssetSends=${data.pendingAssetSends} pendingAssetFetches=${data.pendingAssetFetches} incomingAttachments=${data.incomingAttachments} unavailableAssets=${data.unavailableAssets}",
    "  notificationIssue=${notifications.lastIssue ?: "none"} dataIssue=${data.lastIssue ?: "none"}",
)
