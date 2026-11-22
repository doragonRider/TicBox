package dev.ticbox.companion.ui.setup

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.api.ConnectionPhase
import dev.ticbox.companion.api.SetupAccountChoice
import dev.ticbox.companion.api.SetupActionKind
import dev.ticbox.companion.api.SetupCheckinChoice
import dev.ticbox.companion.api.SetupDecision
import dev.ticbox.companion.api.SetupFailureKind
import dev.ticbox.companion.api.SetupOptions
import dev.ticbox.companion.api.SetupPhase
import dev.ticbox.companion.api.SetupSnapshot
import dev.ticbox.companion.api.SetupStep
import dev.ticbox.companion.api.SetupStepState
import dev.ticbox.companion.api.SetupUserAction
import dev.ticbox.companion.api.SetupUserResponse
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.common.uiText
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.AppIcon
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.BannerTone
import dev.ticbox.companion.ui.designsystem.BusyBar
import dev.ticbox.companion.ui.designsystem.FadeSwitch
import dev.ticbox.companion.ui.designsystem.FadingText
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.GrowingGaps
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.PageColumn
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.Reveal
import dev.ticbox.companion.ui.designsystem.RevealValue
import dev.ticbox.companion.ui.designsystem.StatusBanner
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

data class SetupUiState(val setup: SetupSnapshot, val linkReady: Boolean) {
    val active: Boolean
        get() = setup.phase == SetupPhase.RUNNING || setup.phase == SetupPhase.WAITING_FOR_USER
}

class SetupViewModel(dependencies: CompanionDependencies) : SessionViewModel(dependencies) {
    val state: StateFlow<SetupUiState?> =
        sessionState(
            current = {
                SetupUiState(
                    it.services.setup.state.value,
                    it.client.state.value.connection.phase == ConnectionPhase.LINK_READY,
                )
            },
            updates = { session ->
                combine(session.services.setup.state, session.client.state) { setup, watch ->
                    SetupUiState(setup, watch.connection.phase == ConnectionPhase.LINK_READY)
                }
            },
        )

    fun start(checkinChoice: SetupCheckinChoice = SetupCheckinChoice.SKIP) =
        command(START) { session ->
            session.services.setup.start(
                SetupOptions(
                    accountChoice = SetupAccountChoice.SKIP,
                    checkinChoice = checkinChoice,
                    requireInitialDataPreparation = true,
                )
            )
            null
        }

    fun checkStatus() =
        command(PROBE) { session ->
            session.services.setup.probe()
            uiText(R.string.setup_status_checked)
        }

    fun respond(action: SetupUserAction, decision: SetupDecision, termsAccepted: Boolean) =
        command(RESPOND) { session ->
            val response =
                SetupUserResponse(
                    decision,
                    termsAccepted =
                        if (action.kind == SetupActionKind.REVIEW_TERMS) termsAccepted else null,
                    accountChoice = SetupAccountChoice.SKIP,
                )
            if (session.services.setup.resume(action.id, response)) null
            else uiText(R.string.setup_prompt_expired)
        }

    fun cancel() {
        session.value?.services?.setup?.cancel()
    }

    companion object {
        const val START = "setup_start"
        const val PROBE = "setup_probe"
        const val RESPOND = "setup_respond"
    }
}

@Composable
fun SetupScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val viewModel = companionViewModel { SetupViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    var attemptCheckin by rememberSaveable { mutableStateOf(false) }
    PageScaffold(stringResource(R.string.setup_title), onBack = onBack) { padding ->
        val current = state ?: return@PageScaffold
        val setup = current.setup
        PageColumn(padding) {
            Header(current)
            RevealValue(setup.failure) { failure ->
                StatusBanner(
                    stringResource(failure.kind.message),
                    tone = BannerTone.Error,
                    title = stringResource(R.string.setup_failed_title),
                )
            }
            RevealValue(setup.action) { action ->
                ActionCard(setup.runId, action, pending, viewModel)
            }
            Reveal(setup.progress.isNotEmpty()) { StepList(setup) }
            val stage =
                when {
                    setup.phase == SetupPhase.COMPLETED -> SetupStage.DONE
                    current.active -> SetupStage.RUNNING
                    else -> SetupStage.READY
                }
            FadeSwitch(stage) { shown ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = GrowingGaps(20.dp)) {
                    when (shown) {
                        SetupStage.DONE ->
                            Button(
                                onClick = onDone,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            ) {
                                Text(stringResource(R.string.action_done))
                            }
                        SetupStage.RUNNING ->
                            Reveal(
                                setup.action?.allowedDecisions?.contains(SetupDecision.CANCEL) !=
                                    true
                            ) {
                                OutlinedButton(
                                    onClick = viewModel::cancel,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.setup_cancel))
                                }
                            }
                        SetupStage.READY -> {
                            CheckinChoice(attemptCheckin) { attemptCheckin = it }
                            Button(
                                onClick = {
                                    viewModel.start(
                                        if (attemptCheckin) SetupCheckinChoice.ATTEMPT
                                        else SetupCheckinChoice.SKIP
                                    )
                                },
                                enabled = current.linkReady && SetupViewModel.START !in pending,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            ) {
                                Text(stringResource(R.string.setup_start))
                            }
                            OutlinedButton(
                                onClick = viewModel::checkStatus,
                                enabled = current.linkReady && SetupViewModel.PROBE !in pending,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.setup_check_status))
                            }
                            FootnoteText(stringResource(R.string.setup_account_skipped))
                        }
                    }
                }
            }
        }
    }
}

private enum class SetupStage {
    READY,
    RUNNING,
    DONE,
}

@Composable
private fun CheckinChoice(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.setup_checkin_option_title),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.setup_checkin_option_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Header(state: SetupUiState) {
    val setup = state.setup
    Column(Modifier.animateContentSize(), verticalArrangement = GrowingGaps(8.dp)) {
        FadingText(
            stringResource(
                when {
                    setup.phase == SetupPhase.COMPLETED -> R.string.setup_complete_title
                    state.active -> setup.currentStep?.label ?: R.string.setup_title
                    else -> R.string.setup_intro_title
                }
            ),
            style = MaterialTheme.typography.headlineSmall,
        )
        FadingText(
            stringResource(
                when (setup.phase) {
                    SetupPhase.COMPLETED ->
                        if (setup.watchCompleteObserved) R.string.setup_complete_body
                        else R.string.setup_complete_local_body
                    SetupPhase.WAITING_FOR_USER -> R.string.setup_waiting_body
                    SetupPhase.RUNNING -> R.string.setup_running_body
                    else ->
                        if (state.linkReady) R.string.setup_intro_body
                        else R.string.setup_needs_link
                }
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BusyBar(state.active)
    }
}

@Composable
private fun StepList(setup: SetupSnapshot) {
    // Several protocol steps share one user-facing label; show each label once, latest state.
    val rows =
        setup.progress.groupBy { it.step.label }.mapValues { (_, steps) -> steps.last().state }
    ListGroup(title = stringResource(R.string.setup_progress)) {
        rows.forEach { (label, state) ->
            ListItem(
                headlineContent = { Text(stringResource(label)) },
                supportingContent = { FadingText(stringResource(state.label)) },
                leadingContent = {
                    Crossfade(state, label = "setup_step") { shown ->
                        when (shown) {
                            SetupStepState.RUNNING ->
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            SetupStepState.SUCCEEDED ->
                                AppIcon(
                                    AppIcons.CheckCircle,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            SetupStepState.FAILED ->
                                AppIcon(
                                    AppIcons.Error,
                                    null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            else -> AppIcon(AppIcons.Schedule, null)
                        }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

@Composable
private fun ActionCard(
    runId: Long,
    action: SetupUserAction,
    pending: Set<Any>,
    viewModel: SetupViewModel,
) {
    var termsAccepted by rememberSaveable(runId, action.id) { mutableStateOf(false) }
    val busy = SetupViewModel.RESPOND in pending
    Card(
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(
                    if (action.kind == SetupActionKind.CHECKIN) R.string.setup_checkin_action_title
                    else R.string.setup_action_title
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(
                    if (action.kind == SetupActionKind.CHECKIN) {
                        if (action.reason == "watch_checkin_in_progress")
                            R.string.setup_checkin_wait_body
                        else R.string.setup_checkin_failed_body
                    } else action.kind.guidance
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(
                    if (SetupDecision.SKIP_OPTIONAL in action.allowedDecisions)
                        R.string.setup_action_skippable
                    else R.string.setup_action_required
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (action.kind == SetupActionKind.REVIEW_TERMS) {
                Row(
                    Modifier.fillMaxWidth().toggleable(termsAccepted, role = Role.Checkbox) {
                        termsAccepted = it
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = termsAccepted, onCheckedChange = null)
                    Text(
                        stringResource(R.string.setup_terms_accept),
                        Modifier.padding(start = 12.dp),
                    )
                }
            }
            val primary =
                listOf(SetupDecision.CONTINUE, SetupDecision.RETRY).firstOrNull {
                    it in action.allowedDecisions
                }
            action.allowedDecisions
                .sortedBy { if (it == primary) 0 else if (it == SetupDecision.CANCEL) 2 else 1 }
                .forEach { decision ->
                    val enabled =
                        !busy &&
                            (decision != SetupDecision.CONTINUE ||
                                action.kind != SetupActionKind.REVIEW_TERMS ||
                                termsAccepted)
                    val respond = {
                        if (decision == SetupDecision.CANCEL) viewModel.cancel()
                        else viewModel.respond(action, decision, termsAccepted)
                    }
                    if (decision == primary) {
                        Button(
                            onClick = respond,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(decision.label))
                        }
                    } else {
                        OutlinedButton(
                            onClick = respond,
                            enabled = decision == SetupDecision.CANCEL || enabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(
                                    if (
                                        action.kind == SetupActionKind.CHECKIN &&
                                            decision == SetupDecision.SKIP_OPTIONAL
                                    )
                                        R.string.setup_checkin_skip
                                    else decision.label
                                )
                            )
                        }
                    }
                }
        }
    }
}

private val SetupStep.label: Int
    get() =
        when (this) {
            SetupStep.CHANNEL,
            SetupStep.PROBE,
            SetupStep.WATCH_READY -> R.string.setup_step_check
            SetupStep.TIME,
            SetupStep.TETHER -> R.string.setup_step_time
            SetupStep.TERMS -> R.string.setup_step_terms
            SetupStep.ACCOUNT -> R.string.setup_step_account
            SetupStep.RESTORE -> R.string.setup_step_restore
            SetupStep.CHECKIN -> R.string.setup_checkin_step
            SetupStep.INITIAL_DATA_PREPARATION,
            SetupStep.DATA_SYNC_COMPLETION -> R.string.setup_step_data
            SetupStep.OPTIONAL -> R.string.setup_step_optional
            SetupStep.COMPLETE -> R.string.setup_step_finish
            SetupStep.OOBE -> R.string.setup_step_watch_finish
        }

private val SetupStepState.label: Int
    get() =
        when (this) {
            SetupStepState.RUNNING -> R.string.setup_state_running
            SetupStepState.WAITING -> R.string.setup_state_waiting
            SetupStepState.SUCCEEDED -> R.string.setup_state_done
            SetupStepState.SKIPPED -> R.string.setup_state_skipped
            SetupStepState.FAILED -> R.string.setup_state_failed
        }

private val SetupDecision.label: Int
    get() =
        when (this) {
            SetupDecision.CONTINUE -> R.string.action_continue
            SetupDecision.RETRY -> R.string.action_try_again
            SetupDecision.SKIP_OPTIONAL -> R.string.action_skip
            SetupDecision.CANCEL -> R.string.setup_cancel
        }

private val SetupActionKind.guidance: Int
    get() =
        when (this) {
            SetupActionKind.REVIEW_TERMS -> R.string.setup_guidance_terms
            SetupActionKind.CHOOSE_ACCOUNT,
            SetupActionKind.ACCOUNT_TRANSFER -> R.string.setup_guidance_account
            SetupActionKind.CHECKIN -> R.string.setup_checkin_failed_body
            SetupActionKind.INITIAL_DATA_PREPARATION -> R.string.setup_guidance_data
            SetupActionKind.DATA_SYNC_COMPLETION -> R.string.setup_guidance_sync
            SetupActionKind.OPTIONAL_STEP -> R.string.setup_guidance_optional
        }

private val SetupFailureKind.message: Int
    get() =
        when (this) {
            SetupFailureKind.LINK_LOST -> R.string.setup_failure_link
            SetupFailureKind.CHANNEL_CLOSED -> R.string.setup_failure_closed
            SetupFailureKind.TIMEOUT -> R.string.setup_failure_timeout
            SetupFailureKind.DEPENDENCY -> R.string.setup_failure_dependency
            SetupFailureKind.UNSUPPORTED_MODE -> R.string.setup_failure_unsupported
            else -> R.string.setup_failure_other
        }
