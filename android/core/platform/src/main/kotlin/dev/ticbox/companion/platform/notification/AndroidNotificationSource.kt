package dev.ticbox.companion.platform.notification

import android.content.Context
import android.service.notification.NotificationListenerService
import dev.ticbox.companion.api.DiagnosticSink
import dev.ticbox.companion.api.ProtocolDiagnostic
import dev.ticbox.companion.engine.notification.*
import dev.ticbox.companion.protocol.DataMapValue
import dev.ticbox.companion.protocol.NotificationContent
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Native notification access and PendingIntent ownership, separated from the wire/session engine. */
class AndroidNotificationSource(
    context: Context,
    scope: CoroutineScope,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
) : NotificationSource, AutoCloseable {
    private val context = context.applicationContext
    private val lifetime = SupervisorJob(scope.coroutineContext[Job])
    private val worker = CoroutineScope(scope.coroutineContext + lifetime)
    private val closed = AtomicBoolean()
    private val snapshotLock = Mutex()
    private val actions = AndroidNotificationActions(this.context, diagnostics)
    private val mapper = AndroidNotificationMapper(this.context, actions, diagnostics)
    private val mutableState = MutableStateFlow(NotificationSourceState())
    override val state: StateFlow<NotificationSourceState> = mutableState.asStateFlow()
    override val events: Flow<NotificationSourceEvent> = coalescedNotificationChanges(NotificationListenerHub.changes)

    init {
        worker.launch {
            try {
                NotificationListenerHub.refreshAccess(this@AndroidNotificationSource.context)
                if (!NotificationListenerHub.availability.value.connected) NotificationAccess.requestRebind(this@AndroidNotificationSource.context)
            } catch (problem: Exception) { metadata("listener_rebind_unavailable", problem) }
            NotificationListenerHub.availability.collect {
                mutableState.value = NotificationSourceState(it.accessGranted, it.connected)
                if (!it.accessGranted) actions.clear()
            }
        }
    }

    override suspend fun snapshot(): List<PhoneNotification> = snapshotLock.withLock {
        check(!closed.get())
        if (!state.value.available) throw NotificationListenerUnavailableException()
        val snapshot = NotificationListenerHub.snapshot() // Read failure is never an empty success.
        withContext(Dispatchers.Default) {
            val imageBudget = AndroidNotificationMapper.ImageBudget()
            val values = snapshot.notifications.map { notification ->
                ensureActive()
                try {
                    val ranking = NotificationListenerService.Ranking().takeIf { snapshot.ranking.getRanking(notification.key, it) }
                    mapper.map(notification, imageBudget, ranking)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (problem: Exception) {
                    metadata("notification_projection_partial", problem)
                    // Preserve the notification's existence if a vendor Parcelable/style cannot
                    // be decoded. Never turn one malformed entry into an authoritative removal.
                    PhoneNotification(notification.key, notification.packageName, notification.id, notification.tag,
                        notification.postTime, localOnly = notification.notification.flags and android.app.Notification.FLAG_LOCAL_ONLY != 0,
                        content = NotificationContent(mapOf("app_name" to DataMapValue.Text(notification.packageName),
                            "post_time_millis" to DataMapValue.LongNumber(notification.postTime))))
                }
            }
            actions.retainNotifications(values.mapTo(mutableSetOf()) { it.key })
            values
        }
    }

    override suspend fun execute(action: NotificationActionInvocation): NotificationActionOutcome {
        if (closed.get() || !state.value.available) return NotificationActionOutcome(false, NotificationActionFailure.UNAVAILABLE)
        val result = actions.execute(action.intentId, action.replies)
        return NotificationActionOutcome(result.successful, if (result.successful) null else when (result.reason) {
            "intent_unavailable" -> NotificationActionFailure.UNKNOWN_ACTION
            "notification_removed" -> NotificationActionFailure.NO_LONGER_ACTIVE
            "intent_cancelled" -> NotificationActionFailure.CANCELLED
            "listener_unavailable", "permission_denied" -> NotificationActionFailure.UNAVAILABLE
            "immutable_reply_intent", "reply_input_unavailable" -> NotificationActionFailure.UNSUPPORTED
            else -> NotificationActionFailure.FAILED
        })
    }

    override suspend fun dismiss(key: String): Boolean {
        if (closed.get() || !state.value.available) return false
        return NotificationListenerHub.dismiss(key)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        lifetime.cancel()
        actions.clear()
        mutableState.value = NotificationSourceState()
    }

    private fun metadata(event: String, failure: Exception) {
        runCatching { diagnostics.record(ProtocolDiagnostic("notifications", event,
            details = mapOf("exception" to failure.javaClass.simpleName))) }
    }
}

/** A sustained stream still progresses; no polling timer runs when Android sends no changes. */
internal fun coalescedNotificationChanges(changes: StateFlow<Long>): Flow<NotificationSourceEvent> = changes.map {
    // StateFlow retains the latest change during this short batching window. A debounce would
    // starve forever under continuous progress/ranking updates; a sampling timer wakes while idle.
    delay(100)
    NotificationSourceEvent.Invalidated
}
