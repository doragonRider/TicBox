package dev.ticbox.companion.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class NotificationAlertPatchMode { OFF, OBSERVE, PROBE, ALL }

/** What root can see of the module's optional SystemUI extension. Never proof an alert played. */
data class NotificationAlertPatchSnapshot(
    /** The module's opt-in marker exists where the next boot will look for it. */
    val optedIn: Boolean,
    /** The marker is older than this boot, so a process start has already had the chance to load. */
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
 * The module's documented opt-in and control commands over root Local Bridge, for explicit requests
 * only. Never reboots, never repeats a write, and only [optOut] or the watch's own Magisk undo it.
 */
interface NotificationAlertPatchClient : AutoCloseable {
    val state: StateFlow<NotificationAlertPatchState>
    suspend fun refresh()
    /** Creates the marker, in the staged copy when there is one. Loading takes a normal reboot. */
    suspend fun optIn()
    /**
     * Independently attempts OFF and marker removal, then reads status. A missing control socket
     * does not prevent cancelling the next boot's opt-in. Check reason and the observed mode:
     * removing the marker alone does not prove the currently loaded extension is passive.
     */
    suspend fun optOut()
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
