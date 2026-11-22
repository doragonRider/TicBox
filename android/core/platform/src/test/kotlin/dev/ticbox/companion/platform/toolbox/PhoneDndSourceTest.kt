package dev.ticbox.companion.platform.toolbox

import android.app.NotificationManager
import dev.ticbox.companion.platform.notification.NotificationListenerHub
import dev.ticbox.companion.platform.notification.WatchNotificationListener
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PhoneDndSourceTest {
    @Test fun currentListenerMapsAllDndModesAndDisconnectionBecomesUnknown() {
        val first = Robolectric.buildService(WatchNotificationListener::class.java).create().get()
        val replacement = Robolectric.buildService(WatchNotificationListener::class.java).create().get()
        try {
            NotificationListenerHub.attach(first)
            for (filter in listOf(NotificationManager.INTERRUPTION_FILTER_PRIORITY, NotificationManager.INTERRUPTION_FILTER_ALARMS, NotificationManager.INTERRUPTION_FILTER_NONE)) {
                NotificationListenerHub.interruptionFilterChanged(first, filter); assertEquals(true, NotificationListenerHub.dnd.value)
            }
            NotificationListenerHub.interruptionFilterChanged(first, NotificationManager.INTERRUPTION_FILTER_ALL); assertEquals(false, NotificationListenerHub.dnd.value)
            NotificationListenerHub.interruptionFilterChanged(first, NotificationManager.INTERRUPTION_FILTER_UNKNOWN); assertNull(NotificationListenerHub.dnd.value)
            NotificationListenerHub.attach(replacement)
            NotificationListenerHub.interruptionFilterChanged(replacement, NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            NotificationListenerHub.detach(first)
            NotificationListenerHub.interruptionFilterChanged(first, NotificationManager.INTERRUPTION_FILTER_ALL)
            assertEquals(true, NotificationListenerHub.dnd.value)
            NotificationListenerHub.detach(replacement); assertNull(NotificationListenerHub.dnd.value)
        } finally { NotificationListenerHub.detach(first); NotificationListenerHub.detach(replacement) }
    }
}
