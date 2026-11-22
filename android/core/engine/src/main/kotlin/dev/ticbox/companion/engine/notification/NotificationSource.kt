package dev.ticbox.companion.engine.notification

import dev.ticbox.companion.api.ApplicationRoute
import dev.ticbox.companion.api.DataScope
import dev.ticbox.companion.api.NotificationSyncSettings
import dev.ticbox.companion.protocol.NotificationContent
import dev.ticbox.companion.protocol.NotificationReply
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class NotificationSourceState(val accessGranted: Boolean = false, val connected: Boolean = false) {
    val available: Boolean get() = accessGranted && connected
}

data class PhoneNotification(
    val key: String,
    val packageName: String,
    val id: Int,
    val tag: String? = null,
    val postTimeMillis: Long,
    val localOnly: Boolean = false,
    val content: NotificationContent = NotificationContent(),
)

/**
 * Candidates whose notification reference was durably replaced or removed. The store must still
 * check all live DataItem references and other owners before discarding only their cached bytes.
 */
fun interface NotificationAssetRetirement {
    suspend fun retire(scope: DataScope, route: ApplicationRoute, digests: Set<String>)

    companion object { val NONE = NotificationAssetRetirement { _, _, _ -> } }
}

/** Callbacks invalidate the active snapshot; their payloads never become an unbounded queue. */
sealed interface NotificationSourceEvent {
    data object Invalidated : NotificationSourceEvent
    data class Posted(val key: String) : NotificationSourceEvent
    data class Removed(val key: String) : NotificationSourceEvent
}

data class NotificationActionInvocation(
    val intentId: String,
    val replies: Map<String, NotificationReply> = emptyMap(),
    val notificationKey: String? = null,
    val remoteStreamItemId: String? = null,
    val actionIndex: Int? = null,
    val isWearableAction: Boolean? = null,
)

enum class NotificationActionFailure { UNKNOWN_ACTION, NO_LONGER_ACTIVE, CANCELLED, UNAVAILABLE, UNSUPPORTED, FAILED }
/** Successful means the native operation was accepted, not that an app delivered a reply. */
data class NotificationActionOutcome(val successful: Boolean, val failure: NotificationActionFailure? = null)

interface NotificationSource {
    val state: StateFlow<NotificationSourceState>
    val events: Flow<NotificationSourceEvent>
    /** Complete active snapshot. Throw if unavailable; an unreadable snapshot must not look empty. */
    suspend fun snapshot(): List<PhoneNotification>
    /** Resolve the actual opaque ID. Stream path/index are advisory metadata, not substitute IDs. */
    suspend fun execute(action: NotificationActionInvocation): NotificationActionOutcome
    /** True only if the system accepted the cancellation request. */
    suspend fun dismiss(key: String): Boolean
}

interface NotificationSettingsStore {
    suspend fun load(): NotificationSyncSettings
    suspend fun save(settings: NotificationSyncSettings)
}

class MemoryNotificationSettingsStore(initial: NotificationSyncSettings = NotificationSyncSettings()) : NotificationSettingsStore {
    private var value = initial.copy(blockedPackages = initial.blockedPackages.toSet(), allowedPackages = initial.allowedPackages.toSet(), appAlerts = initial.appAlerts.toMap())
    override suspend fun load(): NotificationSyncSettings = synchronized(this) { value }
    override suspend fun save(settings: NotificationSyncSettings) { synchronized(this) { value = settings.copy(blockedPackages = settings.blockedPackages.toSet(), allowedPackages = settings.allowedPackages.toSet(), appAlerts = settings.appAlerts.toMap()) } }
}

/** Separate global notification-event counter; never a DataApi cursor or a timestamp. */
fun interface NotificationRevisionStore { suspend fun next(): Long }

class MemoryNotificationRevisionStore(initial: Long = 0) : NotificationRevisionStore {
    private var value = initial
    override suspend fun next(): Long = synchronized(this) { value++ }
}
