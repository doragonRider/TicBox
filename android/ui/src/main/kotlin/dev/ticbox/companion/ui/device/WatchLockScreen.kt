package dev.ticbox.companion.ui.device

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.api.ConnectionPhase
import dev.ticbox.companion.api.WatchLockPhase
import dev.ticbox.companion.api.WatchLockState
import dev.ticbox.companion.api.WatchLockType
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.EmptyState
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.NavigationRow
import dev.ticbox.companion.ui.designsystem.PageColumn
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.RevealValue
import dev.ticbox.companion.ui.designsystem.StatusBanner
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

data class WatchLockUiState(val lock: WatchLockState, val linkReady: Boolean)

class WatchLockViewModel(dependencies: CompanionDependencies) : SessionViewModel(dependencies) {
    val state: StateFlow<WatchLockUiState?> =
        sessionState(
            current = {
                WatchLockUiState(
                    it.services.deviceSettings.state.value.lock,
                    it.client.state.value.connection.phase == ConnectionPhase.LINK_READY,
                )
            },
            updates = { session ->
                combine(session.services.deviceSettings.state, session.client.state) { device, watch
                    ->
                    WatchLockUiState(
                        device.lock,
                        watch.connection.phase == ConnectionPhase.LINK_READY,
                    )
                }
            },
        )

    fun querySupport() = command(QUERY) { it.services.deviceSettings.queryLockSupport().message() }

    /** Opens the watch's own screen; the code or pattern is only ever entered there. */
    fun configure(type: WatchLockType) =
        command(type) { it.services.deviceSettings.configureLock(type).message(opened = true) }

    fun cancel() =
        command(CANCEL) { it.services.deviceSettings.cancelLockConfiguration().message() }

    companion object {
        const val QUERY = "lock_query"
        const val CANCEL = "lock_cancel"
    }
}

private val WatchLockType.label: Int
    get() =
        when (this) {
            WatchLockType.NONE -> R.string.watch_lock_none
            WatchLockType.PIN -> R.string.watch_lock_pin
            WatchLockType.GESTURE -> R.string.watch_lock_pattern
        }

private val WatchLockPhase.status: Int?
    get() =
        when (this) {
            WatchLockPhase.IDLE -> null
            WatchLockPhase.CONFIGURATION_SCREEN -> R.string.watch_lock_phase_configuring
            WatchLockPhase.VERIFICATION_SCREEN -> R.string.watch_lock_phase_verifying
            WatchLockPhase.WRONG_CREDENTIAL -> R.string.watch_lock_phase_wrong
            WatchLockPhase.UNLOCKED -> R.string.watch_lock_phase_unlocked
            WatchLockPhase.CONFIGURED -> R.string.watch_lock_phase_configured
            WatchLockPhase.REMOVED -> R.string.watch_lock_phase_removed
            WatchLockPhase.CANCELLED -> R.string.watch_lock_phase_cancelled
            WatchLockPhase.UNKNOWN -> R.string.watch_lock_phase_unknown
        }

@Composable
fun WatchLockScreen(onBack: () -> Unit) {
    val viewModel = companionViewModel { WatchLockViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val linkReady = state?.linkReady == true
    LaunchedEffect(linkReady) { if (linkReady) viewModel.querySupport() }
    PageScaffold(stringResource(R.string.watch_lock_title), onBack = onBack) { padding ->
        val current = state
        if (current == null) {
            EmptyState(
                AppIcons.Lock,
                stringResource(R.string.no_watch_title),
                stringResource(R.string.no_watch_body),
                Modifier.padding(padding),
            )
            return@PageScaffold
        }
        val phase = current.lock.phase
        val inProgress =
            phase == WatchLockPhase.CONFIGURATION_SCREEN ||
                phase == WatchLockPhase.VERIFICATION_SCREEN ||
                phase == WatchLockPhase.WRONG_CREDENTIAL
        PageColumn(padding) {
            RevealValue(phase.status) { status ->
                StatusBanner(
                    stringResource(status),
                    actionLabel = if (inProgress) stringResource(R.string.action_cancel) else null,
                    onAction =
                        if (inProgress && WatchLockViewModel.CANCEL !in pending) viewModel::cancel
                        else null,
                )
            }
            // An empty list means the watch has not said; its own screen decides then.
            val supported = current.lock.supportedTypes
            val types =
                WatchLockType.entries.filter { supported.isEmpty() || it.wireValue in supported }
            ListGroup(title = stringResource(R.string.watch_lock_choose)) {
                types.forEach { type ->
                    NavigationRow(
                        stringResource(type.label),
                        onClick = { viewModel.configure(type) },
                        enabled = current.linkReady && type !in pending,
                        trailingIcon = AppIcons.OpenExternal,
                    )
                }
            }
            FootnoteText(stringResource(R.string.watch_lock_note))
        }
    }
}
