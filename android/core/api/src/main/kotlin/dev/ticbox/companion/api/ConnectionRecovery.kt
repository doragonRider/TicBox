package dev.ticbox.companion.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConnectionRecoveryPhase {
    IDLE, RESTORING, RUNNING, WAITING_BLUETOOTH, WAITING_PERMISSION,
    WAITING_ASSOCIATION, WAITING_BOND, WAITING_START_ALLOWED, FAILED, CLOSED,
}

/** Typed cause of a FAILED recovery phase; [ConnectionRecoveryState.reason] stays diagnostic. */
enum class ConnectionRecoveryIssue {
    CONNECTION_NOT_SAVED, SAVED_CONNECTION_UNREADABLE, DISCONNECT_NOT_SAVED, RESTORE_FAILED, SERVICE_OWNER_REQUIRED,
}

/** RUNNING means that SessionEngine owns connection/retry; consult WatchClient.state for link readiness. */
data class ConnectionRecoveryState(
    val phase: ConnectionRecoveryPhase = ConnectionRecoveryPhase.IDLE,
    val desiredPeer: PeerAddress? = null,
    val associationId: Int? = null,
    val reason: String? = null,
    /** False before the saved choice has been read (the initial IDLE state) and when reading or saving it failed. */
    val durable: Boolean = false,
    val issue: ConnectionRecoveryIssue? = null,
    /** A temporary disconnect retains [desiredPeer] while suppressing lifecycle/presence wakes. */
    val paused: Boolean = false,
    /** This app's existing watch associations with an observed Bluetooth bond. Never auto-selected. */
    val pairedWatches: List<PairingAssociation> = emptyList(),
)

/**
 * User connect/disconnect continue to use WatchClient. An explicit resume enables a paused saved
 * choice and retries it from a visible host. Lifecycle events must preserve the pause.
 */
interface ConnectionRecoveryClient {
    val state: StateFlow<ConnectionRecoveryState>
    suspend fun resume()

    companion object {
        /** Direct runtime instances do not own Android service lifecycle recovery. */
        val UNAVAILABLE: ConnectionRecoveryClient = object : ConnectionRecoveryClient {
            override val state = MutableStateFlow(ConnectionRecoveryState(
                ConnectionRecoveryPhase.FAILED, reason = "service_owner_required",
                issue = ConnectionRecoveryIssue.SERVICE_OWNER_REQUIRED,
            )).asStateFlow()
            override suspend fun resume() = Unit
        }
    }
}
