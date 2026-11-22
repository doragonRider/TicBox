package dev.ticbox.companion.ui.device

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.api.DndSyncMode
import dev.ticbox.companion.api.DndSyncState
import dev.ticbox.companion.api.ToolboxPhase
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.LocalSystemActions
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.bridge.ALLOW_DND_COMMAND
import dev.ticbox.companion.ui.bridge.LocalBridgeBanner
import dev.ticbox.companion.ui.bridge.rememberLocalBridgeStatus
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.common.uiText
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.BannerTone
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.GrowingGaps
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.RadioRow
import dev.ticbox.companion.ui.designsystem.Reveal
import dev.ticbox.companion.ui.designsystem.RevealValue
import dev.ticbox.companion.ui.designsystem.StatusBanner
import dev.ticbox.companion.ui.navigation.AdbConnectRoute
import dev.ticbox.companion.ui.toolbox.reportingTimeouts
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

data class DndSyncUiState(val sync: DndSyncState, val adbConnected: Boolean)

class DndSyncViewModel(dependencies: CompanionDependencies) : SessionViewModel(dependencies) {
    val state: StateFlow<DndSyncUiState?> =
        sessionState(
            current = {
                DndSyncUiState(
                    it.services.dnd.state.value,
                    it.services.toolbox.adb.state.value.phase == ToolboxPhase.CONNECTED,
                )
            },
            updates = { session ->
                combine(session.services.dnd.state, session.services.toolbox.adb.state) { sync, adb
                    ->
                    DndSyncUiState(sync, adb.phase == ToolboxPhase.CONNECTED)
                }
            },
        )

    fun setMode(mode: DndSyncMode) =
        command(MODE) {
            it.services.dnd.setMode(mode)
            null
        }

    fun retry() =
        command(RETRY) {
            it.services.dnd.refresh()
            null
        }

    /** Local Bridge asks Magisk for this itself; without root, a shell can grant it instead. */
    fun allowOverAdb() =
        command(ALLOW) { session ->
            val result = reportingTimeouts {
                session.services.toolbox.adb.execute(ALLOW_DND_COMMAND)
            }
            if (result.successful) {
                session.services.dnd.refresh()
                null
            } else {
                uiText(R.string.dnd_allow_failed)
            }
        }

    companion object {
        const val MODE = "dnd_mode"
        const val RETRY = "dnd_retry"
        const val ALLOW = "dnd_allow"
    }
}

/** Either leaves the watch alone or has it follow the phone's Do Not Disturb. */
@Composable
fun DndSyncSection(navigate: (Any) -> Unit, modifier: Modifier = Modifier) {
    val viewModel = companionViewModel { DndSyncViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val current = state ?: return
    val sync = current.sync
    Column(modifier.animateContentSize(), verticalArrangement = GrowingGaps(8.dp)) {
        ListGroup(Modifier.selectableGroup(), title = stringResource(R.string.dnd_title)) {
            RadioRow(
                stringResource(R.string.dnd_off),
                selected = sync.mode == DndSyncMode.OFF,
                onClick = { viewModel.setMode(DndSyncMode.OFF) },
                enabled = DndSyncViewModel.MODE !in pending,
            )
            RadioRow(
                stringResource(R.string.dnd_follow_phone),
                selected = sync.mode == DndSyncMode.FOLLOW_PHONE,
                onClick = { viewModel.setMode(DndSyncMode.FOLLOW_PHONE) },
                summary = stringResource(R.string.dnd_follow_phone_summary),
                enabled = DndSyncViewModel.MODE !in pending,
            )
        }
        Reveal(sync.mode == DndSyncMode.FOLLOW_PHONE, spacing = 8.dp) {
            LocalBridgeBanner(rememberLocalBridgeStatus(), R.string.dnd_needs_bridge, navigate)
            RevealValue(current.sync.error) { error ->
                DndProblem(error, current, pending, viewModel, navigate)
            }
            RevealValue(dndStatus(sync)) { FootnoteText(stringResource(it)) }
        }
    }
}

@Composable
private fun DndProblem(
    error: String,
    state: DndSyncUiState,
    pending: Set<Any>,
    viewModel: DndSyncViewModel,
    navigate: (Any) -> Unit,
) {
    val actions = LocalSystemActions.current
    when (error) {
        "notification_listener_unavailable" ->
            StatusBanner(
                stringResource(R.string.dnd_phone_access_required),
                tone = BannerTone.Warning,
                actionLabel = stringResource(R.string.notifications_grant_access),
                onAction = actions::openNotificationAccess,
            )
        "notification_policy_access_required" ->
            StatusBanner(
                stringResource(R.string.dnd_watch_access_required),
                tone = BannerTone.Warning,
                actionLabel =
                    stringResource(
                        if (state.adbConnected) R.string.dnd_allow_over_adb
                        else R.string.dnd_connect_adb
                    ),
                onAction =
                    when {
                        !state.adbConnected -> ({ navigate(AdbConnectRoute) })
                        DndSyncViewModel.ALLOW in pending -> ({})
                        else -> viewModel::allowOverAdb
                    },
            )
        else ->
            StatusBanner(
                stringResource(R.string.dnd_sync_failed),
                tone = BannerTone.Warning,
                actionLabel = stringResource(R.string.action_try_again),
                onAction = viewModel::retry,
            )
    }
}

private fun dndStatus(sync: DndSyncState): Int? =
    when {
        sync.error != null -> null
        !sync.connected -> R.string.dnd_status_waiting_for_watch
        sync.pending -> R.string.dnd_status_syncing
        sync.phoneDnd == true && sync.watchRuleActive == true -> R.string.dnd_status_both_on
        sync.phoneDnd == false -> R.string.dnd_status_phone_off
        else -> null
    }
