package dev.ticbox.companion.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class NotificationAlertPatchMode { OFF, OBSERVE, PROBE, ALL }

/** What root can see of the module's optional SystemUI extension. Never proof an alert played. */
data class NotificationAlertPatchSnapshot(
    /** No record of turning it off where the next boot will look, so it loads there: it is on unless turned off. */
    val optedIn: Boolean,
    /** It has been on since before this boot, so a process start has already had the chance to load it. */
    val optInSurvivedReboot: Boolean,
    /** The control socket answered with the exact supported SystemUI identity. */
    val running: Boolean,
    /** Both wrappers were written and read back; see the patch document for what that leaves open. */
    val hooked: Boolean,
    val mode: NotificationAlertPatchMode?,
    val issue: String?,
) {
    val applied: Boolean get() = running && hooked && mode == NotificationAlertPatchMode.ALL
}
enum class NotificationAlertPatchPhase { DISCONNECTED, READY, BUSY, UNAVAILABLE, CLOSED }
data class NotificationAlertPatchState(
    val phase: NotificationAlertPatchPhase = NotificationAlertPatchPhase.DISCONNECTED,
    val snapshot: NotificationAlertPatchSnapshot? = null,
    /** False means [snapshot] says nothing about the current connection. */
    val current: Boolean = false,
    /** The last operation's failure. A failed write still reads, so [snapshot] can be current. */
    val reason: String? = null,
)
/**
 * The module's documented on/off and control commands over root Local Bridge, for explicit requests
 * only. Never reboots and never repeats a write. Only turning it off leaves a record on the watch.
 */
interface NotificationAlertPatchClient : AutoCloseable {
    val state: StateFlow<NotificationAlertPatchState>
    suspend fun refresh()
    /** Removes the record of turning it off from every copy. Loading takes a restart of the system UI. */
    suspend fun optIn()
    /**
     * Independently attempts OFF and the record of turning it off, then reads status. A missing
     * control socket does not stop the record. Check reason and the observed mode: the record
     * alone does not prove the currently loaded extension is passive.
     */
    suspend fun optOut()
    /** Only the record, into every copy, for when a restart follows that unloads whatever runs now. */
    suspend fun keepOff() = optOut()
    suspend fun applyToAll()
    override fun close()
    companion object {
        val UNAVAILABLE = object : NotificationAlertPatchClient {
            override val state = MutableStateFlow(NotificationAlertPatchState(phase = NotificationAlertPatchPhase.UNAVAILABLE))
            override suspend fun refresh() = Unit
            override suspend fun optIn() = Unit
            override suspend fun optOut() = Unit
            override suspend fun applyToAll() = Unit
            override fun close() = Unit
        }
    }
}
