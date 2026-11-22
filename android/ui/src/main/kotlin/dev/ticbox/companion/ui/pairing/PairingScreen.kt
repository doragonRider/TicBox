package dev.ticbox.companion.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import dev.ticbox.companion.api.ConnectionPhase
import dev.ticbox.companion.api.PairingFailureKind
import dev.ticbox.companion.api.PairingOptions
import dev.ticbox.companion.api.PairingPermissionTransfer
import dev.ticbox.companion.api.PairingPhase
import dev.ticbox.companion.api.PairingSnapshot
import dev.ticbox.companion.api.SetupPhase
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.BannerTone
import dev.ticbox.companion.ui.designsystem.FadeSwitch
import dev.ticbox.companion.ui.designsystem.GrowingGaps
import dev.ticbox.companion.ui.designsystem.PageColumn
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.Reveal
import dev.ticbox.companion.ui.designsystem.RevealValue
import dev.ticbox.companion.ui.designsystem.StatusBanner
import dev.ticbox.companion.ui.designsystem.WatchIllustration
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

data class PairingUiState(
    val pairing: PairingSnapshot,
    val linkReady: Boolean,
    val setupRunning: Boolean,
    val isDemo: Boolean,
) {
    val active: Boolean
        get() = pairing.phase !in IdlePhases
}

private val IdlePhases =
    setOf(PairingPhase.IDLE, PairingPhase.COMPLETED, PairingPhase.CANCELLED, PairingPhase.FAILED)

class PairingViewModel(
    dependencies: CompanionDependencies,
    private val saved: SavedStateHandle,
) : SessionViewModel(dependencies) {
    private val sessions = dependencies.sessions

    val state: StateFlow<PairingUiState?> =
        sessionState(
            current = { session ->
                PairingUiState(
                    session.services.pairing.state.value,
                    session.client.state.value.connection.phase == ConnectionPhase.LINK_READY,
                    session.services.setup.state.value.phase.isRunning(),
                    session.isDemo,
                )
            },
            updates = { session ->
                combine(
                    session.services.pairing.state,
                    session.client.state,
                    session.services.setup.state,
                ) { pairing, watch, setup ->
                    PairingUiState(
                        pairing,
                        watch.connection.phase == ConnectionPhase.LINK_READY,
                        setup.phase.isRunning(),
                        session.isDemo,
                    )
                }
            },
        )

    /** The operation this page started; when it completes with a link, setup comes next. */
    val requestedOperation: StateFlow<Long?> = saved.getStateFlow(REQUESTED, null)

    fun choose(reuseAssociation: Boolean = true) =
        command(START) { session ->
            saved[REQUESTED] =
                session.services.pairing.start(
                    PairingOptions(reuseAssociation = reuseAssociation, syncPermissions = false)
                )
            null
        }

    fun syncPermissions() =
        command(PERMISSIONS) { session ->
            session.client.state.value.connection.peer?.let {
                session.services.pairing.syncPermissions(it)
            }
            null
        }

    fun cancel() {
        session.value?.services?.pairing?.cancel(state.value?.pairing?.operationId ?: return)
    }

    fun consumeRequest() {
        saved[REQUESTED] = null
    }

    fun enterDemo() = sessions.enterDemo()

    companion object {
        const val START = "pairing_start"
        const val PERMISSIONS = "pairing_permissions"
        private const val REQUESTED = "requested_operation"
    }
}

private fun SetupPhase.isRunning() =
    this == SetupPhase.RUNNING || this == SetupPhase.WAITING_FOR_USER

@Composable
fun PairingScreen(onBack: () -> Unit, onPaired: () -> Unit) {
    val viewModel = companionViewModel { PairingViewModel(it, createSavedStateHandle()) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val requested by viewModel.requestedOperation.collectAsStateWithLifecycle()
    val current = state
    LaunchedEffect(current, requested) {
        if (
            current != null &&
                requested == current.pairing.operationId &&
                current.pairing.phase == PairingPhase.COMPLETED &&
                current.linkReady
        ) {
            viewModel.consumeRequest()
            onPaired()
        }
    }
    PageScaffold(stringResource(R.string.pairing_title), onBack = onBack) { padding ->
        PageColumn(padding) {
            WatchIllustration(Modifier.fillMaxWidth().height(180.dp))
            FadeSwitch(
                current,
                key = {
                    when {
                        it == null -> 0
                        it.active -> 1
                        it.linkReady -> 2
                        else -> 3
                    }
                },
            ) { shown ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = GrowingGaps(20.dp)) {
                    when {
                        shown == null ->
                            StatusBanner(stringResource(R.string.pairing_service_starting))
                        shown.active -> InProgress(shown.pairing.phase, viewModel::cancel)
                        shown.linkReady -> Connected(shown, pending, viewModel, onSetup = onPaired)
                        else ->
                            Instructions(
                                shown,
                                PairingViewModel.START in pending,
                                viewModel::choose,
                            )
                    }
                }
            }
            Reveal(current?.isDemo == false && !current.active) {
                TextButton(
                    onClick = viewModel::enterDemo,
                    modifier = Modifier.align(Alignment.CenterHorizontally).testTag("try_demo"),
                ) {
                    Text(stringResource(R.string.welcome_try_demo))
                }
            }
        }
    }
}

@Composable
private fun Instructions(state: PairingUiState, starting: Boolean, onChoose: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf(
                R.string.pairing_step_nearby,
                R.string.pairing_step_choose,
                R.string.pairing_step_confirm,
            )
            .forEachIndexed { index, text -> Step(index + 1, stringResource(text)) }
    }
    RevealValue(state.pairing.failure) { failure ->
        StatusBanner(
            stringResource(failure.kind.message),
            tone = BannerTone.Error,
            title = stringResource(R.string.pairing_failed_title),
        )
    }
    Text(
        stringResource(R.string.pairing_existing_bond_notice),
        style = MaterialTheme.typography.bodySmall,
    )
    Button(
        onClick = onChoose,
        enabled = !starting && !state.setupRunning,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("choose_watch"),
    ) {
        Text(
            stringResource(
                if (state.pairing.phase == PairingPhase.FAILED) R.string.action_try_again
                else R.string.pairing_choose
            )
        )
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(32.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(number.toString(), style = MaterialTheme.typography.labelLarge)
            }
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun InProgress(phase: PairingPhase, onCancel: () -> Unit) {
    Card(
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(phase.message),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleMedium,
            )
            LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

@Composable
private fun Connected(
    state: PairingUiState,
    pending: Set<Any>,
    viewModel: PairingViewModel,
    onSetup: () -> Unit,
) {
    StatusBanner(
        stringResource(R.string.pairing_connected_body),
        title = stringResource(R.string.pairing_connected_title),
    )
    Button(onClick = onSetup, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(stringResource(R.string.pairing_continue_setup))
    }
    Reveal(state.pairing.permissionTransfer != PairingPermissionTransfer.COMPLETED) {
        FilledTonalButton(
            onClick = viewModel::syncPermissions,
            enabled = PairingViewModel.PERMISSIONS !in pending && !state.setupRunning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.pairing_sync_permissions))
        }
    }
    OutlinedButton(
        onClick = { viewModel.choose(reuseAssociation = false) },
        enabled = PairingViewModel.START !in pending && !state.setupRunning,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.pairing_choose_other))
    }
}

private val PairingPhase.message: Int
    get() =
        when (this) {
            PairingPhase.PREPARING -> R.string.pairing_phase_preparing
            PairingPhase.ASSOCIATING -> R.string.pairing_phase_associating
            PairingPhase.CLEARING_OLD_BOND -> R.string.pairing_phase_clearing_old_bond
            PairingPhase.BONDING -> R.string.pairing_phase_bonding
            PairingPhase.CONNECTING -> R.string.pairing_phase_connecting
            else -> R.string.pairing_phase_system
        }

internal val PairingFailureKind.message: Int
    get() =
        when (this) {
            PairingFailureKind.PLATFORM_UNAVAILABLE -> R.string.pairing_failure_platform
            PairingFailureKind.PERMISSION_DENIED -> R.string.pairing_failure_permission
            PairingFailureKind.ASSOCIATION -> R.string.pairing_failure_association
            PairingFailureKind.BOND -> R.string.pairing_failure_bond
            PairingFailureKind.LINK -> R.string.pairing_failure_link
            PairingFailureKind.PERMISSION_TRANSFER -> R.string.pairing_failure_transfer
            PairingFailureKind.TIMEOUT -> R.string.pairing_failure_timeout
            PairingFailureKind.UNEXPECTED -> R.string.pairing_failure_unexpected
        }
