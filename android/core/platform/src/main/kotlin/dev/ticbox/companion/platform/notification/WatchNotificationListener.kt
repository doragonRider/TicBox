package dev.ticbox.companion.platform.notification

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.provider.Settings
import android.content.Intent
import android.util.Log
import java.lang.ref.WeakReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** System-bound listener; the connection service remains the only Bluetooth session owner. */
class WatchNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationListenerHub.attach(this)
    }

    override fun onListenerDisconnected() {
        NotificationListenerHub.detach(this)
        super.onListenerDisconnected()
        // One request per actual disconnect, never a timer/rebind loop. Android still controls
        // admission and will refuse this request if notification access has been withdrawn.
        runCatching { if (NotificationAccess.isGranted(this)) requestRebind(ComponentName(this, javaClass)) }
            .onFailure { Log.w("WatchNotification", "Listener rebind unavailable: ${it.javaClass.simpleName}") }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        NotificationListenerHub.changed(this)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        NotificationListenerHub.changed(this)
    }

    override fun onNotificationRankingUpdate(rankingMap: RankingMap) {
        NotificationListenerHub.changed(this)
    }

    override fun onInterruptionFilterChanged(interruptionFilter: Int) {
        NotificationListenerHub.interruptionFilterChanged(this, interruptionFilter)
    }

    override fun onDestroy() {
        NotificationListenerHub.detach(this)
        super.onDestroy()
    }
}

/** Foreground UI launches this settings Intent; granting access is always a system/user action. */
object NotificationAccess {
    fun isGranted(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(context, WatchNotificationListener::class.java))

    fun settingsIntent(context: Context): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(context, WatchNotificationListener::class.java).flattenToString())

    fun requestRebind(context: Context) {
        NotificationListenerHub.refreshAccess(context)
        if (isGranted(context)) NotificationListenerService.requestRebind(
            ComponentName(context, WatchNotificationListener::class.java))
    }
}

internal data class ListenerAvailability(val accessGranted: Boolean = false, val connected: Boolean = false)
internal data class ListenerSnapshot(val notifications: Array<StatusBarNotification>, val ranking: NotificationListenerService.RankingMap)

/**
 * Process-local rendezvous, including when Android starts the listener before the foreground
 * service. Only a weak reference to the system-bound service is retained. Callbacks are hints;
 * the source obtains an authoritative snapshot, so coalescing callbacks cannot lose removals.
 */
internal object NotificationListenerHub {
    @Volatile private var listener = WeakReference<WatchNotificationListener>(null)
    private val mutableAvailability = MutableStateFlow(ListenerAvailability())
    val availability: StateFlow<ListenerAvailability> = mutableAvailability
    private val mutableChanges = MutableStateFlow(0L)
    val changes: StateFlow<Long> = mutableChanges
    private val mutableDnd = MutableStateFlow<Boolean?>(null)
    val dnd: StateFlow<Boolean?> = mutableDnd

    fun refreshAccess(context: Context) {
        mutableAvailability.value = ListenerAvailability(NotificationAccess.isGranted(context), listener.get() != null)
        if (!mutableAvailability.value.accessGranted || !mutableAvailability.value.connected) mutableDnd.value = null
    }

    fun attach(service: WatchNotificationListener) {
        listener = WeakReference(service)
        mutableAvailability.value = ListenerAvailability(NotificationAccess.isGranted(service), true)
        mutableChanges.value += 1
        interruptionFilterChanged(service, service.currentInterruptionFilter)
    }

    fun detach(service: WatchNotificationListener) {
        if (listener.get() !== service) return
        listener.clear()
        mutableDnd.value = null
        mutableAvailability.value = ListenerAvailability(NotificationAccess.isGranted(service), false)
        mutableChanges.value += 1
    }

    fun changed(service: WatchNotificationListener) {
        if (listener.get() === service) mutableChanges.value += 1
    }

    fun interruptionFilterChanged(service: WatchNotificationListener, value: Int) {
        if (listener.get() !== service) return
        mutableDnd.value = when (value) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> false
            NotificationManager.INTERRUPTION_FILTER_PRIORITY, NotificationManager.INTERRUPTION_FILTER_ALARMS, NotificationManager.INTERRUPTION_FILTER_NONE -> true
            else -> null
        }
    }

    suspend fun active(): Array<StatusBarNotification> = withContext(Dispatchers.Main.immediate) {
        val service = listener.get() ?: throw NotificationListenerUnavailableException()
        if (!mutableAvailability.value.connected) throw NotificationListenerUnavailableException()
        service.activeNotifications ?: throw NotificationListenerUnavailableException()
    }

    suspend fun snapshot(): ListenerSnapshot = withContext(Dispatchers.Main.immediate) {
        val service = listener.get() ?: throw NotificationListenerUnavailableException()
        if (!mutableAvailability.value.connected) throw NotificationListenerUnavailableException()
        ListenerSnapshot(service.activeNotifications ?: throw NotificationListenerUnavailableException(), service.currentRanking)
    }

    suspend fun dismiss(key: String): Boolean = withContext(Dispatchers.Main.immediate) {
        val service = listener.get() ?: throw NotificationListenerUnavailableException()
        if (!mutableAvailability.value.connected) throw NotificationListenerUnavailableException()
        val current = service.getActiveNotifications(arrayOf(key)) ?: throw NotificationListenerUnavailableException()
        if (current.none { it.key == key }) return@withContext false
        service.cancelNotification(key)
        true // Android accepted the request; the later snapshot establishes actual removal.
    }
}

internal class NotificationListenerUnavailableException : IllegalStateException("Notification listener is unavailable")
