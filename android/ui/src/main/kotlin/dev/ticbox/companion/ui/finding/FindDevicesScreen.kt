package dev.ticbox.companion.ui.finding

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.api.ConnectionPhase
import dev.ticbox.companion.api.FindDeviceState
import dev.ticbox.companion.api.FindDevicesClient
import dev.ticbox.companion.api.FindDevicesIssueKind
import dev.ticbox.companion.api.FindWatchStatus
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.bridge.LocalBridgeBanner
import dev.ticbox.companion.ui.bridge.rememberLocalBridgeStatus
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.AppIcon
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.BannerTone
import dev.ticbox.companion.ui.designsystem.EmptyState
import dev.ticbox.companion.ui.designsystem.FadeSwitch
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.PageColumn
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.RevealValue
import dev.ticbox.companion.ui.designsystem.StatusBanner
import dev.ticbox.companion.ui.designsystem.SwitchRow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

data class FindDevicesUiState(val finding: FindDeviceState, val linkReady: Boolean)

class FindDevicesViewModel(dependencies: CompanionDependencies) : SessionViewModel(dependencies) {
    val state: StateFlow<FindDevicesUiState?> =
        sessionState(
            current = {
                FindDevicesUiState(
                    it.services.finding.state.value,
                    it.client.state.value.connection.phase == ConnectionPhase.LINK_READY,
                )
            },
            updates = { session ->
                combine(session.services.finding.state, session.client.state) { finding, watch ->
                    FindDevicesUiState(
                        finding,
                        watch.connection.phase == ConnectionPhase.LINK_READY,
                    )
                }
            },
        )

    fun setEnabled(enabled: Boolean) = finding(Action.ENABLE) { it.setEnabled(enabled) }

    fun ringPhone() = finding(Action.PHONE) { it.ringPhone() }

    fun stopPhone() = finding(Action.PHONE_STOP) { it.stopPhone() }

    fun ringWatch() = finding(Action.WATCH) { it.ringWatch() }

    fun stopWatch() = finding(Action.WATCH_STOP) { it.stopWatch() }

    private fun finding(action: Action, block: suspend (FindDevicesClient) -> Unit) =
        command(action) { session ->
            block(session.services.finding)
            null
        }

    enum class Action {
        ENABLE,
        PHONE,
        PHONE_STOP,
        WATCH,
        WATCH_STOP,
    }
}

@Composable
fun FindDevicesScreen(onBack: () -> Unit, navigate: (Any) -> Unit) {
    val viewModel = companionViewModel { FindDevicesViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    PageScaffold(stringResource(R.string.find_title), onBack = onBack) { padding ->
        val current = state
        if (current == null) {
            EmptyState(
                AppIcons.FindDevices,
                stringResource(R.string.no_watch_title),
                stringResource(R.string.no_watch_body),
                Modifier.padding(padding),
            )
            return@PageScaffold
        }
        val finding = current.finding
        val allowed = finding.settingsReady && finding.settings.enabled
        PageColumn(padding) {
            ListGroup {
                SwitchRow(
                    stringResource(R.string.find_allow),
                    checked = finding.settings.enabled,
                    onCheckedChange = viewModel::setEnabled,
                    summary = stringResource(R.string.find_allow_summary),
                    enabled =
                        finding.settingsReady && FindDevicesViewModel.Action.ENABLE !in pending,
                )
            }
            val phoneActive = finding.phone.active || finding.phone.preparingAudio
            DeviceCard(
                icon = AppIcons.Phone,
                title = stringResource(R.string.find_phone_title),
                status =
                    stringResource(
                        when {
                            finding.phone.audioPlaybackStarted -> R.string.find_phone_ringing
                            phoneActive -> R.string.find_phone_starting
                            else -> R.string.find_phone_idle
                        }
                    ),
                actionLabel =
                    stringResource(
                        if (phoneActive) R.string.find_stop else R.string.find_phone_test
                    ),
                actionEnabled =
                    if (phoneActive) FindDevicesViewModel.Action.PHONE_STOP !in pending
                    else allowed && FindDevicesViewModel.Action.PHONE !in pending,
                stop = phoneActive,
                onAction = if (phoneActive) viewModel::stopPhone else viewModel::ringPhone,
            )
            val watchRinging = finding.watchStopAvailable
            DeviceCard(
                icon = AppIcons.Watch,
                title = stringResource(R.string.find_watch_title),
                status =
                    stringResource(
                        when (finding.watchStatus) {
                            FindWatchStatus.IDLE -> R.string.find_watch_idle
                            FindWatchStatus.STARTING -> R.string.find_watch_starting
                            FindWatchStatus.RINGING ->
                                if (finding.watchAudioPlaybackStarted) R.string.find_watch_ringing
                                else R.string.find_watch_vibrating
                            FindWatchStatus.STOPPING -> R.string.find_watch_stopping
                            FindWatchStatus.STOPPED -> R.string.find_watch_stopped
                            FindWatchStatus.UNAVAILABLE -> R.string.find_watch_unavailable
                            FindWatchStatus.TIMED_OUT -> R.string.find_watch_timeout
                            FindWatchStatus.DISCONNECTED -> R.string.watch_not_connected
                            FindWatchStatus.FAILED -> R.string.find_watch_failed
                        }
                    ),
                actionLabel =
                    stringResource(
                        if (watchRinging) R.string.find_stop else R.string.find_watch_ring
                    ),
                actionEnabled =
                    current.linkReady &&
                        finding.watchStatus != FindWatchStatus.STOPPING &&
                        if (watchRinging) FindDevicesViewModel.Action.WATCH_STOP !in pending
                        else allowed && FindDevicesViewModel.Action.WATCH !in pending,
                stop = watchRinging,
                onAction = if (watchRinging) viewModel::stopWatch else viewModel::ringWatch,
            )
            RevealValue(finding.lastIssue) { issue ->
                StatusBanner(
                    stringResource(
                        when (issue.kind) {
                            FindDevicesIssueKind.SETTINGS_RESTORE -> R.string.find_issue_settings
                            FindDevicesIssueKind.PHONE_SOUND -> R.string.find_issue_sound
                            FindDevicesIssueKind.PHONE_RING -> R.string.find_issue_ring
                            FindDevicesIssueKind.WATCH_FIND -> R.string.find_issue_watch
                            FindDevicesIssueKind.RING_REPLY -> R.string.find_issue_reply
                        }
                    ),
                    tone = BannerTone.Warning,
                )
            }
            FootnoteText(stringResource(R.string.find_watch_note))
            LocalBridgeBanner(
                rememberLocalBridgeStatus(),
                R.string.bridge_needed_find,
                navigate,
            )
        }
    }
}

@Composable
private fun DeviceCard(
    @DrawableRes icon: Int,
    title: String,
    status: String,
    actionLabel: String,
    actionEnabled: Boolean,
    stop: Boolean,
    onAction: () -> Unit,
) {
    Card(
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppIcon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        status,
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FadeSwitch(stop) { stopping ->
                if (stopping) {
                    Button(
                        onClick = onAction,
                        enabled = actionEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(actionLabel)
                    }
                } else {
                    FilledTonalButton(
                        onClick = onAction,
                        enabled = actionEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(actionLabel)
                    }
                }
            }
        }
    }
}
