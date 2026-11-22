package dev.ticbox.companion.ui.workout

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.currentLocale
import dev.ticbox.companion.ui.common.formatDateTime
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.BannerTone
import dev.ticbox.companion.ui.designsystem.EmptyState
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.NavigationRow
import dev.ticbox.companion.ui.designsystem.PageList
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.StatusBanner
import dev.ticbox.companion.ui.health.HealthMetric
import dev.ticbox.companion.ui.health.HealthReadState
import dev.ticbox.companion.ui.health.metricFormatter
import dev.ticbox.companion.ui.navigation.LocalBridgeRoute
import dev.ticbox.companion.ui.navigation.WorkoutDetailRoute
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The health overview's way into the workout list. */
@Composable
fun WorkoutsEntry(state: WorkoutsUiState, onOpen: () -> Unit) {
    val latest = state.items.firstOrNull()
    ListGroup(title = stringResource(R.string.workouts_title)) {
        NavigationRow(
            if (latest != null) sportName(latest.typeCode)
            else stringResource(R.string.workouts_entry),
            onClick = onOpen,
            icon = latest?.let { sportKind(it.typeCode).icon } ?: AppIcons.Running,
            summary =
                when {
                    latest != null -> workoutLine(latest)
                    state.loading -> stringResource(R.string.health_state_loading)
                    state.failed -> stringResource(R.string.health_state_failed)
                    state.source.title != null -> stringResource(state.source.title)
                    state.readState == HealthReadState.UNAVAILABLE ->
                        stringResource(R.string.workouts_state_unavailable)
                    else -> stringResource(R.string.workouts_state_empty)
                },
        )
    }
}

/** When it was and its main numbers. */
@Composable
private fun workoutLine(workout: WorkoutSummary): String {
    val formatter = metricFormatter()
    val workoutFormat = workoutFormatter()
    val locale = currentLocale()
    val numbers =
        listOfNotNull(
            workout.durationMillis?.let { workoutFormat.elapsed(it) },
            workout.distanceMeters
                ?.takeIf { it > 0 }
                ?.let {
                    formatter.value(HealthMetric.DISTANCE, it.toDouble())
                },
            workout.calories?.takeIf { it > 0 }?.let { formatter.calories(it) },
        )
    return listOfNotNull(
            workout.startMillis?.let { formatDateTime(it, locale) },
            numbers.joinToString(" · ").ifEmpty { null },
        )
        .joinToString("\n")
}

@Composable
fun WorkoutsScreen(viewModel: WorkoutsViewModel, navigate: (Any) -> Unit, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PageScaffold(stringResource(R.string.workouts_title), onBack = onBack) { padding ->
        if (!state.hasSource) {
            EmptyState(
                AppIcons.Running,
                stringResource(R.string.health_no_watch_title),
                stringResource(R.string.health_no_watch_body),
                Modifier.padding(padding),
            )
            return@PageScaffold
        }
        val list = state.list
        if (state.items.isEmpty() && !state.loading) {
            val unavailable = state.readState == HealthReadState.UNAVAILABLE
            val source = state.source
            EmptyState(
                AppIcons.Running,
                stringResource(
                    when {
                        state.failed -> R.string.workouts_failed_title
                        source.title != null -> source.title
                        unavailable -> R.string.workouts_unavailable_title
                        else -> R.string.workouts_empty_title
                    }
                ),
                stringResource(
                    when {
                        state.failed -> R.string.workouts_failed_body
                        source.body != null -> source.body
                        unavailable -> R.string.workouts_unavailable_body
                        else -> R.string.workouts_empty_body
                    }
                ),
                Modifier.padding(padding),
                actionLabel =
                    if (source.ledByLocalBridgePage) stringResource(R.string.bridge_set_up)
                    else null,
                onAction = { navigate(LocalBridgeRoute) },
            )
            return@PageScaffold
        }
        val locale = currentLocale()
        val month =
            DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "yMMMM"), locale)
        val zone = ZoneId.systemDefault()
        val months =
            state.items.groupBy { item ->
                item.startMillis?.let {
                    month.format(Instant.ofEpochMilli(it).atZone(zone))
                }
            }
        PageList(padding) {
            item(key = "status") {
                Column(Modifier.fillMaxWidth()) {
                    when {
                        state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        list?.summaryOnly == true ->
                            StatusBanner(stringResource(R.string.workouts_summary_only))
                        state.readState == HealthReadState.PARTIAL ->
                            StatusBanner(
                                stringResource(R.string.workouts_partial),
                                tone = BannerTone.Warning,
                            )
                    }
                }
            }
            items(months.entries.toList(), key = { it.key ?: "undated" }) { (title, workouts) ->
                ListGroup(title = title ?: stringResource(R.string.workouts_undated)) {
                    workouts.forEach { workout ->
                        NavigationRow(
                            sportName(workout.typeCode),
                            onClick = { navigate(WorkoutDetailRoute(workout.key)) },
                            icon = sportKind(workout.typeCode).icon,
                            summary = workoutLine(workout),
                        )
                    }
                }
            }
            item(key = "note") { FootnoteText(stringResource(R.string.workouts_note)) }
        }
    }
}
