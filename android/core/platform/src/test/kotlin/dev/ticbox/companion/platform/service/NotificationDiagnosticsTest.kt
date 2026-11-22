package dev.ticbox.companion.platform.service

import dev.ticbox.companion.api.*
import kotlin.test.*

class NotificationDiagnosticsTest {
    @Test fun snapshotShowsTheActualGatesAndCountersWithoutPackageListsOrNotificationIdentities() {
        val link = ConnectionState(ConnectionPhase.LINK_READY, PeerAddress("AA:BB:CC:DD:EE:FF"), 7, peerName = "private watch")
        val data = DataSyncState(DataSyncPhase.ACTIVE,
            DataScope(link.peer!!, "private phone node", "private watch node", "private network"), 7,
            receivedItems = 11, sentItems = 5, pendingAssetSends = 2)
        val notifications = NotificationSyncState(
            settings = NotificationSyncSettings(mode = NotificationFilterMode.ALLOWLIST, allowedPackages = setOf("private.package"),
                soundEnabled = false, appAlerts = mapOf("another.private.package" to NotificationAppAlertSettings(sound = NotificationAlertMode.NEVER))),
            phase = NotificationSyncPhase.ACTIVE, accessGranted = true, listenerConnected = true, sessionId = 7,
            activeNotificationCount = 4, mirroredNotificationCount = 1, filteredNotificationCount = 3,
        )
        val text = notificationDiagnostics(link, data, notifications).joinToString("\n")
        assertContains(text, "notificationAccessGranted=true listenerConnected=true")
        assertContains(text, "filterMode=ALLOWLIST allowedPackageCount=1 blockedPackageCount=0")
        assertContains(text, "soundEnabled=false vibrationEnabled=true appAlertSettingsCount=1")
        assertContains(text, "activeNotificationCount=4 mirroredNotificationCount=1 filteredNotificationCount=3")
        assertContains(text, "dataPhase=ACTIVE dataSessionId=7 matchingNotificationSession=true")
        assertContains(text, "sentDataItems=5 receivedDataItems=11 pendingAssetSends=2")
        assertFalse(text.contains("private")); assertFalse(text.contains("AA:BB:CC:DD:EE:FF"))
    }

    @Test fun disconnectedAndMismatchedSessionsNeverLookLikeACurrentNotificationSession() {
        val link = ConnectionState(ConnectionPhase.LINK_READY, sessionId = 8)
        val data = DataSyncState(DataSyncPhase.ACTIVE, sessionId = 7)
        val notifications = NotificationSyncState(sessionId = 7, lastIssue = NotificationSyncIssue.SNAPSHOT_FAILED)
        val text = notificationDiagnostics(link, data, notifications).joinToString("\n")
        assertContains(text, "notificationAccessGranted=false listenerConnected=false")
        assertContains(text, "matchingNotificationSession=false")
        assertContains(text, "notificationIssue=SNAPSHOT_FAILED dataIssue=none")
        assertContains(notificationDiagnostics(ConnectionState(), DataSyncState(), NotificationSyncState()).joinToString(),
            "matchingNotificationSession=false")
    }
}
