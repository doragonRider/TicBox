package dev.ticbox.companion.ui.health

import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.api.LocalHealthSyncClient
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.bridge.LocalBridgeBanner
import dev.ticbox.companion.ui.bridge.rememberLocalBridgeStatus
import dev.ticbox.companion.ui.common.currentLocale
import dev.ticbox.companion.ui.common.formatDateTime
import dev.ticbox.companion.ui.common.formatNumber
import dev.ticbox.companion.ui.designsystem.ActionIcon
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.EmptyState
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.NavigationRow
import dev.ticbox.companion.ui.designsystem.PageList
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.Reveal
import dev.ticbox.companion.ui.designsystem.StatusBanner
import dev.ticbox.companion.ui.navigation.HealthDetailRoute
import dev.ticbox.companion.ui.navigation.HealthLogRoute
import dev.ticbox.companion.ui.navigation.PairingRoute
import dev.ticbox.companion.ui.navigation.StepGoalRoute
import dev.ticbox.companion.ui.navigation.WatchProfileRoute
import dev.ticbox.companion.ui.navigation.WorkoutsRoute
import dev.ticbox.companion.ui.workout.WorkoutsEntry
import dev.ticbox.companion.ui.workout.WorkoutsViewModel

@Composable
fun HealthOverviewScreen(
    viewModel: HealthViewModel,
    workoutsViewModel: WorkoutsViewModel,
    navigate: (Any) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val workouts by workoutsViewModel.state.collectAsStateWithLifecycle()
    val bridge = rememberLocalBridgeStatus()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val watch by viewModel.session.collectAsStateWithLifecycle()
    val chosenGoal by viewModel.chosenStepGoal.collectAsStateWithLifecycle()
    val watchGoal = watch?.client?.state?.collectAsStateWithLifecycle()?.value?.steps?.value?.goal
    val goal = chosenGoal ?: watchGoal
    val syncing = state.sync is HealthSyncStatus.Syncing || HealthViewModel.SYNC in pending
    LifecycleStartEffect(viewModel) {
        viewModel.refreshRecent()
        onStopOrDispose {}
    }
    PageScaffold(
        title = stringResource(R.string.tab_health),
        actions = {
            if (state.hasSource) {
                ActionIcon(
                    AppIcons.Sync,
                    stringResource(R.string.health_sync_now),
                    viewModel::syncFromWatch,
                    enabled = !syncing,
                )
            }
        },
    ) { padding ->
        if (!state.hasSource) {
            Column(Modifier.padding(padding)) {
                EmptyState(
                    icon = AppIcons.Heart,
                    title = stringResource(R.string.health_no_watch_title),
                    body = stringResource(R.string.health_no_watch_body),
                    actionLabel = stringResource(R.string.action_add_watch),
                    onAction = { navigate(PairingRoute) },
                )
            }
            return@PageScaffold
        }
        PageList(padding, spacing = 16.dp) {
            if (bridge.needsSetup) {
                item(key = "bridge") {
                    LocalBridgeBanner(bridge, R.string.bridge_needed_health, navigate)
                }
            }
            state.deferredBeforeMillis?.let { before ->
                item(key = "window") {
                    val days = LocalHealthSyncClient.FIRST_SYNC_WINDOW_DAYS
                    val since = formatDateTime(before, currentLocale())
                    StatusBanner(
                        stringResource(R.string.health_window_body, since),
                        title = pluralStringResource(R.plurals.health_window_title, days, days),
                        actionLabel = stringResource(R.string.health_window_action),
                        onAction = viewModel::syncEverything,
                    )
                }
            }
            item(key = "date") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DateSelector(state.date, state.today, state.earliest, viewModel::selectDate)
                    SyncStatusLine(state.sync)
                }
            }
            if (HealthMetric.primary.any(state::shows)) {
                item(key = "metrics") {
                    MetricGrid(state) { metric -> navigate(HealthDetailRoute(metric)) }
                }
            }
            item(key = "workouts") { WorkoutsEntry(workouts) { navigate(WorkoutsRoute) } }
            if (HealthMetric.activity.any(state::shows)) {
                item(key = "activity") {
                    MetricRows(
                        R.string.health_group_activity,
                        HealthMetric.activity,
                        state,
                        navigate,
                    )
                }
            }
            if (HealthMetric.body.any(state::shows)) {
                item(key = "body") {
                    MetricRows(R.string.health_group_body, HealthMetric.body, state, navigate)
                }
            }
            if (HealthLog.entries.any(state::shows)) {
                item(key = "logs") {
                    ListGroup(title = stringResource(R.string.health_group_logs)) {
                        HealthLog.entries.forEach { log ->
                            Reveal(state.shows(log)) {
                                NavigationRow(
                                    stringResource(log.title),
                                    onClick = { navigate(HealthLogRoute(log)) },
                                    modifier = Modifier.testTag("log_" + log.name),
                                    icon = log.icon,
                                    summary = stringResource(log.summary),
                                )
                            }
                        }
                    }
                }
            }
            item(key = "goals") {
                ListGroup(title = stringResource(R.string.health_goals)) {
                    NavigationRow(
                        title = stringResource(R.string.step_goal_title),
                        summary =
                            goal?.let {
                                pluralStringResource(
                                    R.plurals.value_steps,
                                    it,
                                    formatNumber(it, currentLocale()),
                                )
                            } ?: stringResource(R.string.step_goal_unknown),
                        icon = AppIcons.Goal,
                        onClick = { navigate(StepGoalRoute) },
                    )
                    NavigationRow(
                        title = stringResource(R.string.profile_title),
                        summary = stringResource(R.string.profile_summary),
                        icon = AppIcons.Person,
                        onClick = { navigate(WatchProfileRoute) },
                    )
                }
            }
            item(key = "footnote") {
                FootnoteText(stringResource(R.string.health_stored_locally))
            }
        }
    }
}

@Composable
private fun MetricRows(
    @StringRes title: Int,
    metrics: List<HealthMetric>,
    state: HealthUiState,
    navigate: (Any) -> Unit,
) {
    ListGroup(title = stringResource(title)) {
        metrics.forEach { metric ->
            Reveal(state.shows(metric)) {
                MetricRow(
                    metric,
                    state.dayView(state.date),
                    state.isLoading(state.date),
                    onClick = { navigate(HealthDetailRoute(metric)) },
                )
            }
        }
    }
}

@Composable
private fun MetricGrid(state: HealthUiState, onOpen: (HealthMetric) -> Unit) {
    val result = state.dayView(state.date)
    val loading = state.isLoading(state.date)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 340.dp && LocalDensity.current.fontScale <= 1.3f) 2 else 1
        Column(Modifier.animateContentSize(), Arrangement.spacedBy(12.dp)) {
            HealthMetric.primary.filter(state::shows).chunked(columns).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { metric ->
                        MetricCard(
                            metric,
                            result,
                            loading,
                            onClick = { onOpen(metric) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                    if (row.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
