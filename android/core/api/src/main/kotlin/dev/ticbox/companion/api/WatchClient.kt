package dev.ticbox.companion.api

import kotlinx.coroutines.flow.StateFlow

data class StepSnapshot(val currentSteps: Int, val goal: Int)

/** Phone receive time, not a sensor timestamp or a claim of a historical record. */
data class WatchObservation<T>(val value: T, val receivedAtEpochMillis: Long, val stale: Boolean)

enum class WatchDataIssue { INVALID_BATTERY, INVALID_STEPS }

data class WatchSnapshot(
    val connection: ConnectionState = ConnectionState(),
    val battery: WatchObservation<Int>? = null,
    val steps: WatchObservation<StepSnapshot>? = null,
    val lastDataIssue: WatchDataIssue? = null,
)

/**
 * Frontend boundary: typed commands and observed state; no wire paths or payload bytes.
 * The service owns this instance. Screens subscribe and submit commands without closing it.
 * Suspend command completion is a local outcome; remote observations arrive independently.
 */
interface WatchClient : AutoCloseable {
    val state: StateFlow<WatchSnapshot>
    /** Submit the selected peer session; observe connection state for actual LINK_READY/failure. */
    suspend fun connect(peer: PeerAddress)
    /**
     * Stop the current link/reconnect loop. A service-owned client keeps its selected watch paused
     * for explicit recovery.resume() or connect(peer). Does not unpair, disassociate or reset it.
     */
    suspend fun disconnect()

    /** Request battery status. Returning only confirms the local write, not a watch response. */
    suspend fun refreshBattery()

    /** Stock handlers also start their cloud synchronization; this is not a history export. */
    suspend fun refreshHealth()

    /**
     * The displayed goal changes only when a later watch observation confirms it.
     * Stock handlers also try to sync with the cloud, whatever the cloud settings here: warn first.
     */
    suspend fun setStepGoal(goal: Int)
    /** Owner-only permanent shutdown. A UI leaving/unbinding uses neither this nor stopService. */
    override fun close()
}
