package dev.ticbox.companion.ui.common

import android.util.Log
import dev.ticbox.companion.api.BackendClosedException
import dev.ticbox.companion.api.CloudFeatureDisabledException
import dev.ticbox.companion.api.MigrationException
import dev.ticbox.companion.api.MobvoiCloudException
import dev.ticbox.companion.api.MobvoiCloudIssueKind
import dev.ticbox.companion.api.NotConnectedException
import dev.ticbox.companion.api.PairingBusyException
import dev.ticbox.companion.api.SetupBusyException
import dev.ticbox.companion.api.ToolboxException
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.settings.migrationMessage
import dev.ticbox.companion.ui.toolbox.toolboxMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** App-wide snackbar queue. View models post; the app scaffold shows them in order. */
class UserMessages {
    private val queue = Channel<UiText>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: Flow<UiText> = queue.receiveAsFlow()

    fun show(text: UiText) {
        queue.trySend(text)
    }
}

/**
 * Runs backend commands in the application scope, so leaving a screen does not cancel a write in
 * progress. Each command has a key; while it runs the key is in [pending] and repeats are ignored.
 * Other commands stay available.
 */
class CommandRunner(private val scope: CoroutineScope, private val messages: UserMessages) {
    private val running = MutableStateFlow<Set<Any>>(emptySet())
    val pending: StateFlow<Set<Any>> = running.asStateFlow()

    fun launch(key: Any, block: suspend () -> UiText?) {
        var started = false
        running.update { current ->
            started = key !in current
            if (started) current + key else current
        }
        if (!started) return
        scope.launch {
            try {
                block()?.let(messages::show)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: Exception) {
                Log.w(TAG, "Command $key failed", problem)
                messages.show(problem.toUiText())
            } finally {
                running.update { it - key }
            }
        }
    }

    private companion object {
        const val TAG = "CompanionCommand"
    }
}

fun Throwable.toUiText(): UiText =
    if (this is ToolboxException) {
        toolboxMessage(code)
    } else if (this is MigrationException) {
        migrationMessage(code)
    } else {
        uiText(
            when (this) {
                is CloudFeatureDisabledException -> R.string.error_cloud_disabled
                is MobvoiCloudException -> issue.kind.message
                is NotConnectedException -> R.string.error_not_connected
                is BackendClosedException -> R.string.error_service_closed
                is PairingBusyException -> R.string.error_pairing_busy
                is SetupBusyException -> R.string.error_setup_busy
                is SecurityException -> R.string.error_not_permitted
                is IllegalArgumentException -> R.string.error_invalid_input
                else -> R.string.error_generic
            }
        )
    }

private val MobvoiCloudIssueKind.message: Int
    get() =
        when (this) {
            MobvoiCloudIssueKind.AUTHENTICATION -> R.string.error_cloud_authentication
            MobvoiCloudIssueKind.EXPIRED -> R.string.error_cloud_expired
            MobvoiCloudIssueKind.HTTP -> R.string.error_cloud_http
            MobvoiCloudIssueKind.NETWORK -> R.string.error_cloud_network
            MobvoiCloudIssueKind.MALFORMED_RESPONSE -> R.string.error_cloud_response
            MobvoiCloudIssueKind.STORAGE -> R.string.error_cloud_storage
            MobvoiCloudIssueKind.CANCELLED -> R.string.error_cloud_cancelled
            MobvoiCloudIssueKind.CONSENT_REQUIRED -> R.string.error_cloud_consent
            MobvoiCloudIssueKind.RESOURCE_LIMIT -> R.string.error_cloud_limit
        }
