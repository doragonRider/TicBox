package dev.ticbox.companion.ui.connection

import androidx.annotation.StringRes
import dev.ticbox.companion.api.ConnectionPhase
import dev.ticbox.companion.api.ConnectionRecoveryIssue
import dev.ticbox.companion.api.ConnectionRecoveryPhase
import dev.ticbox.companion.api.ConnectionRecoveryState
import dev.ticbox.companion.api.FailureKind
import dev.ticbox.companion.ui.R

@get:StringRes
val ConnectionPhase.label: Int
    get() =
        when (this) {
            ConnectionPhase.IDLE -> R.string.connection_not_connected
            ConnectionPhase.CONNECTING -> R.string.connection_connecting
            ConnectionPhase.NEGOTIATING -> R.string.connection_securing
            ConnectionPhase.LINK_READY -> R.string.connection_connected
            ConnectionPhase.RETRYING -> R.string.connection_reconnecting
            ConnectionPhase.FAILED -> R.string.connection_failed
        }

@get:StringRes
val FailureKind.message: Int
    get() =
        when (this) {
            FailureKind.TRANSPORT -> R.string.connection_failure_transport
            FailureKind.TIMEOUT -> R.string.connection_failure_timeout
            FailureKind.PROTOCOL -> R.string.connection_failure_protocol
            FailureKind.IDENTITY -> R.string.connection_failure_identity
            FailureKind.ASSOCIATION_TERMINATED -> R.string.connection_failure_association_terminated
            FailureKind.PERMISSION_REQUIRED -> R.string.connection_failure_permission
            FailureKind.UNEXPECTED -> R.string.connection_failure_unexpected
        }

/** What the user has to do for background reconnection to work, or null if nothing. */
@get:StringRes
val ConnectionRecoveryState.attention: Int?
    get() =
        when (phase) {
            ConnectionRecoveryPhase.WAITING_BLUETOOTH -> R.string.recovery_waiting_bluetooth
            ConnectionRecoveryPhase.WAITING_PERMISSION -> R.string.recovery_waiting_permission
            ConnectionRecoveryPhase.WAITING_ASSOCIATION -> R.string.recovery_waiting_association
            ConnectionRecoveryPhase.WAITING_BOND -> R.string.recovery_waiting_bond
            ConnectionRecoveryPhase.WAITING_START_ALLOWED -> R.string.recovery_waiting_start
            ConnectionRecoveryPhase.FAILED ->
                when (issue) {
                    ConnectionRecoveryIssue.DISCONNECT_NOT_SAVED ->
                        R.string.recovery_disconnect_not_saved
                    ConnectionRecoveryIssue.CONNECTION_NOT_SAVED ->
                        R.string.recovery_connection_not_saved
                    ConnectionRecoveryIssue.SAVED_CONNECTION_UNREADABLE ->
                        R.string.recovery_saved_unreadable
                    ConnectionRecoveryIssue.SERVICE_OWNER_REQUIRED ->
                        R.string.recovery_service_required
                    ConnectionRecoveryIssue.RESTORE_FAILED,
                    null -> R.string.recovery_restore_failed
                }
            ConnectionRecoveryPhase.RUNNING ->
                if (associationMissing) R.string.recovery_association_missing else null
            else -> null
        }

/**
 * The saved watch is kept connected, but Android holds no association between it and this app, as
 * after its identity was imported into a newly installed app. Android then never wakes the app for
 * the watch, and the watch can't control calls.
 */
val ConnectionRecoveryState.associationMissing: Boolean
    get() =
        phase == ConnectionRecoveryPhase.RUNNING &&
            !paused &&
            desiredPeer != null &&
            associationId == null

@get:StringRes
val ConnectionRecoveryState.summary: Int
    get() =
        attention
            ?: if (paused) R.string.recovery_paused
            else
                when (phase) {
                    ConnectionRecoveryPhase.RUNNING -> R.string.recovery_running
                    ConnectionRecoveryPhase.RESTORING -> R.string.recovery_restoring
                    ConnectionRecoveryPhase.CLOSED -> R.string.recovery_closed
                    else ->
                        if (desiredPeer == null) R.string.recovery_idle_no_watch
                        else R.string.recovery_idle
                }
