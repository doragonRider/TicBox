package dev.ticbox.companion.ui.health

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.api.ConnectionPhase
import dev.ticbox.companion.api.WatchSnapshot
import dev.ticbox.companion.ui.AppPreferences
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.common.currentLocale
import dev.ticbox.companion.ui.common.formatNumber
import dev.ticbox.companion.ui.common.uiText
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.EmptyState
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.NavigationRow
import dev.ticbox.companion.ui.designsystem.PageColumn
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.Reveal
import dev.ticbox.companion.ui.designsystem.RevealValue
import dev.ticbox.companion.ui.designsystem.StatusBanner
import dev.ticbox.companion.ui.designsystem.ValueRow
import dev.ticbox.companion.ui.session.WatchSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

private val NoGoal = MutableStateFlow<Int?>(null).asStateFlow()

/** The goal chosen in this app for [session]. The demo watch keeps its goal to itself. */
internal fun AppPreferences.stepGoalFor(session: WatchSession): StateFlow<Int?> =
    if (session.isDemo) NoGoal else stepGoal

data class StepGoalState(
    /** Chosen in this app, used for the progress shown here. */
    val chosen: Int?,
    /** What the watch last said its own goal is. */
    val onWatch: Int?,
    val connected: Boolean,
) {
    val goal: Int?
        get() = chosen ?: onWatch
}

class StepGoalViewModel(dependencies: CompanionDependencies) : SessionViewModel(dependencies) {
    private val preferences = dependencies.preferences

    val state: StateFlow<StepGoalState?> =
        sessionState(
            current = { stateOf(preferences.stepGoalFor(it).value, it.client.state.value) },
            updates = { combine(preferences.stepGoalFor(it), it.client.state, ::stateOf) },
        )

    private val submittedGoal = MutableStateFlow<Int?>(null)
    val submitted: StateFlow<Int?> = submittedGoal.asStateFlow()

    /** Kept on this phone, so it needs neither the watch nor the cloud. */
    fun save(goal: Int) {
        command(SAVE) { session ->
            if (session.isDemo) session.client.setStepGoal(goal) else preferences.setStepGoal(goal)
            uiText(R.string.step_goal_saved)
        }
    }

    fun useWatchGoal() = preferences.setStepGoal(null)

    fun sendToWatch(goal: Int) {
        command(SEND) { session ->
            session.client.setStepGoal(goal)
            submittedGoal.value = goal
            uiText(R.string.step_goal_sent)
        }
    }

    private fun stateOf(chosen: Int?, watch: WatchSnapshot) =
        StepGoalState(
            chosen = chosen,
            onWatch = watch.steps?.value?.goal,
            connected = watch.connection.phase == ConnectionPhase.LINK_READY,
        )

    companion object {
        const val SAVE = "save_goal"
        const val SEND = "send_goal"
    }
}

fun parseStepGoal(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..100_000 }

@Composable
fun StepGoalScreen(onBack: () -> Unit) {
    val viewModel = companionViewModel { StepGoalViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val submitted by viewModel.submitted.collectAsStateWithLifecycle()
    PageScaffold(stringResource(R.string.step_goal_title), onBack = onBack) { padding ->
        val current = state
        if (current == null) {
            EmptyState(
                AppIcons.Goal,
                stringResource(R.string.no_watch_title),
                stringResource(R.string.no_watch_body),
                Modifier.fillMaxWidth(),
            )
            return@PageScaffold
        }
        PageColumn(padding) {
            val goal = current.goal
            ListGroup {
                ValueRow(
                    stringResource(R.string.step_goal_current),
                    stepsText(goal),
                    icon = AppIcons.Goal,
                )
                Reveal(current.chosen != null) {
                    ValueRow(
                        stringResource(R.string.step_goal_on_watch),
                        stepsText(current.onWatch),
                        icon = AppIcons.Watch,
                    )
                }
            }
            var text by rememberSaveable(goal) { mutableStateOf(goal?.toString().orEmpty()) }
            val value = parseStepGoal(text)
            val canSave = value != null && value != goal && StepGoalViewModel.SAVE !in pending
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(6) },
                label = { Text(stringResource(R.string.step_goal_new)) },
                supportingText = { Text(stringResource(R.string.step_goal_range)) },
                isError = text.isNotEmpty() && value == null,
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canSave) viewModel.save(value) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { value?.let(viewModel::save) },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_save))
            }
            FootnoteText(stringResource(R.string.step_goal_note))
            RevealValue(current.chosen) { chosen ->
                ListGroup {
                    NavigationRow(
                        stringResource(R.string.step_goal_use_watch),
                        onClick = viewModel::useWatchGoal,
                        trailingIcon = null,
                    )
                    Reveal(chosen != current.onWatch) {
                        SendToWatchRow(
                            connected = current.connected,
                            sending = StepGoalViewModel.SEND in pending,
                            onSend = { viewModel.sendToWatch(chosen) },
                        )
                    }
                }
            }
            Reveal(submitted != null && submitted == current.onWatch) {
                StatusBanner(stringResource(R.string.step_goal_confirmed))
            }
            Reveal(submitted != null && submitted != current.onWatch) {
                FootnoteText(stringResource(R.string.step_goal_waiting))
            }
        }
    }
}

@Composable
private fun stepsText(steps: Int?): String =
    steps?.let {
        pluralStringResource(R.plurals.value_steps, it, formatNumber(it, currentLocale()))
    } ?: stringResource(R.string.step_goal_unknown)

/** The watch's stock software tries to sync with the cloud on a new goal, so warn first. */
@Composable
private fun SendToWatchRow(connected: Boolean, sending: Boolean, onSend: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.step_goal_send)
    NavigationRow(
        title,
        onClick = { confirm = true },
        summary =
            stringResource(
                if (connected) R.string.step_goal_send_note else R.string.watch_not_connected
            ),
        enabled = connected && !sending,
        trailingIcon = null,
    )
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(title) },
            text = { Text(stringResource(R.string.step_goal_send_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        onSend()
                    },
                    enabled = connected,
                ) {
                    Text(stringResource(R.string.action_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
