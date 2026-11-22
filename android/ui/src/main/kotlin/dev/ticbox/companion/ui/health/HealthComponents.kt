package dev.ticbox.companion.ui.health

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.MINUTE_MILLIS
import dev.ticbox.companion.ui.common.currentLocale
import dev.ticbox.companion.ui.common.formatDayLabel
import dev.ticbox.companion.ui.common.formatRelative
import dev.ticbox.companion.ui.common.rememberMinuteClock
import dev.ticbox.companion.ui.designsystem.AppIcon
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.FadingText
import dev.ticbox.companion.ui.designsystem.NavigationRow
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun dayLabel(date: LocalDate, today: LocalDate): String =
    when (date) {
        today -> stringResource(R.string.date_today)
        today.minusDays(1) -> stringResource(R.string.date_yesterday)
        else -> formatDayLabel(date, currentLocale())
    }

/** The size of M3's date picker dialog. */
private val DatePickerWidth = 360.dp
private val DatePickerMaxHeight = 568.dp

/** Previous / date / next, with a calendar behind the date. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateSelector(
    date: LocalDate,
    today: LocalDate,
    earliest: LocalDate,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onSelect(date.minusDays(1)) }, enabled = date > earliest) {
            AppIcon(AppIcons.ChevronLeft, stringResource(R.string.date_previous))
        }
        TextButton(
            onClick = { picking = true },
            modifier = Modifier.weight(1f).testTag("health_date"),
        ) {
            AppIcon(AppIcons.Calendar, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                dayLabel(date, today),
                Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        IconButton(onClick = { onSelect(date.plusDays(1)) }, enabled = date < today) {
            AppIcon(AppIcons.ChevronRight, stringResource(R.string.date_next))
        }
    }
    if (picking) {
        val picker =
            rememberDatePickerState(
                initialSelectedDateMillis = date.toUtcMillis(),
                selectableDates =
                    object : SelectableDates {
                        override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                            val day = utcTimeMillis.toUtcDate()
                            return day in earliest..today
                        }

                        override fun isSelectableYear(year: Int) = year in earliest.year..today.year
                    },
            )
        DatePickerWindow(
            onDismissRequest = { picking = false },
            buttons = {
                TextButton(onClick = { picking = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
                TextButton(
                    onClick = {
                        picker.selectedDateMillis?.let { onSelect(it.toUtcDate()) }
                        picking = false
                    }
                ) {
                    Text(stringResource(R.string.action_ok))
                }
            },
        ) {
            DatePicker(picker)
        }
    }
}

/**
 * Like M3's DatePickerDialog, except that the window keeps the height of the screen and only the
 * card inside it changes. DatePicker animates its height when switching to text input and back; a
 * window following it would be resized by the system on every frame, which stutters.
 *
 * The system moves a smaller dialog window out of the keyboard's way. This window is too tall for
 * that, so with decorFitsSystemWindows off the insets reach Compose and the card moves instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerWindow(
    onDismissRequest: () -> Unit,
    buttons: @Composable RowScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    BasicAlertDialog(
        onDismissRequest,
        Modifier.fillMaxSize(),
        DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(contentAlignment = Alignment.Center) {
            // To Dialog the whole window is inside it, so taps outside the card are handled here.
            Box(
                Modifier.matchParentSize().pointerInput(onDismissRequest) {
                    detectTapGestures { onDismissRequest() }
                }
            )
            Surface(
                Modifier.safeDrawingPadding()
                    .requiredWidth(DatePickerWidth)
                    .heightIn(max = DatePickerMaxHeight),
                shape = DatePickerDefaults.shape,
                color = DatePickerDefaults.colors().containerColor,
                tonalElevation = DatePickerDefaults.TonalElevation,
            ) {
                Column {
                    Box(Modifier.weight(1f, fill = false)) { content() }
                    Row(
                        Modifier.align(Alignment.End).padding(bottom = 8.dp, end = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        content = buttons,
                    )
                }
            }
        }
    }
}

private fun LocalDate.toUtcMillis() = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toUtcDate() =
    java.time.Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

/** "Synced just now" for the first minute, then "Synced 5 minutes ago", kept current. */
@Composable
private fun syncedText(atMillis: Long?): String {
    if (atMillis == null) return stringResource(R.string.health_not_synced)
    val now = rememberMinuteClock(atMillis)
    return if (now - atMillis < MINUTE_MILLIS) stringResource(R.string.health_synced_recently)
    else stringResource(R.string.health_synced_at, formatRelative(atMillis, now))
}

/** One line telling where the shown data comes from and whether it is fresh. */
@Composable
fun SyncStatusLine(status: HealthSyncStatus, modifier: Modifier = Modifier) {
    val (icon, text) =
        when (status) {
            HealthSyncStatus.NoWatch -> return
            HealthSyncStatus.Disconnected ->
                AppIcons.BluetoothOff to stringResource(R.string.health_sync_disconnected)
            HealthSyncStatus.Failed ->
                AppIcons.Warning to stringResource(R.string.health_sync_failed)
            is HealthSyncStatus.Syncing ->
                null to
                    pluralStringResource(R.plurals.health_syncing, status.records, status.records)
            is HealthSyncStatus.Synced -> AppIcons.CheckCircle to syncedText(status.atMillis)
        }
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics {
            liveRegion = LiveRegionMode.Polite
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Crossfade(icon, label = "sync_icon") { shown ->
            if (shown == null) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                AppIcon(
                    shown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FadingText(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun readStateText(state: HealthReadState, hasData: Boolean): String? =
    when (state) {
        HealthReadState.AVAILABLE -> null
        HealthReadState.EMPTY -> stringResource(R.string.health_state_empty)
        HealthReadState.PARTIAL ->
            stringResource(
                if (hasData) R.string.health_state_partial else R.string.health_state_unreadable
            )
        HealthReadState.UNAVAILABLE -> stringResource(R.string.health_state_unavailable)
        HealthReadState.FAILED -> stringResource(R.string.health_state_failed)
    }

@Composable
fun summaryCaption(metric: HealthMetric, pastDay: Boolean = false): String =
    stringResource(
        when {
            pastDay -> R.string.health_caption_past_day_average
            metric == HealthMetric.SLEEP -> R.string.health_caption_asleep
            metric.dailySummary == DailySummary.TOTAL -> R.string.health_caption_total
            metric.dailySummary == DailySummary.LATEST -> R.string.health_caption_latest
            else -> R.string.health_caption_average
        }
    )

/** A metric as one list row: its value for the day, or why there is none. */
@Composable
fun MetricRow(
    metric: HealthMetric,
    result: HealthDayResult?,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val day = result?.day
    val hasData = day?.hasData(metric) == true
    val status =
        when {
            loading -> stringResource(R.string.health_state_loading)
            result == null -> null
            else -> readStateText(result.state(metric), hasData)
        }
    val value = if (hasData) metricFormatter().value(metric, day.summary(metric)) else null
    NavigationRow(
        stringResource(metric.title),
        onClick = onClick,
        modifier = Modifier.testTag("metric_" + metric.name),
        icon = metric.icon,
        summary = listOfNotNull(value, status).joinToString(" · ").ifEmpty { "–" },
    )
}

@Composable
fun MetricCard(
    metric: HealthMetric,
    result: HealthDayResult?,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formatter = metricFormatter()
    val day = result?.day
    val hasData = day?.hasData(metric) == true
    val until = result?.until(metric)
    Card(
        onClick = onClick,
        modifier = modifier.testTag("metric_" + metric.name),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 156.dp).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppIcon(
                    metric.icon,
                    contentDescription = null,
                    tint = metricColor(metric),
                    modifier = Modifier.size(20.dp),
                )
                Text(stringResource(metric.title), style = MaterialTheme.typography.titleSmall)
            }
            Text(
                if (hasData) formatter.value(metric, day.summary(metric)) else "–",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            val status =
                when {
                    loading -> stringResource(R.string.health_state_loading)
                    result == null -> null
                    else -> readStateText(result.state(metric), hasData)
                }
            val naps = if (metric == HealthMetric.SLEEP) day?.naps?.size ?: 0 else 0
            Text(
                status
                    ?: if (naps > 0) pluralStringResource(R.plurals.sleep_naps_included, naps, naps)
                    else summaryCaption(metric, pastDay = until != null),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hasData) MiniChart(day, metric, until)
        }
    }
}
