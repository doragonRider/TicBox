package dev.ticbox.companion.ui.health

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.currentLocale
import dev.ticbox.companion.ui.common.formatShortDate
import dev.ticbox.companion.ui.common.formatTime
import dev.ticbox.companion.ui.designsystem.BannerTone
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.PageList
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.SectionTitle
import dev.ticbox.companion.ui.designsystem.StatusBanner
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@Composable
fun HealthDetailScreen(metric: HealthMetric, viewModel: HealthViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var period by rememberSaveable { mutableStateOf(HealthPeriod.DAY) }
    val week = remember(state.date) { (6 downTo 0).map { state.date.minusDays(it.toLong()) } }
    LaunchedEffect(period, state.date) {
        viewModel.ensureLoaded(if (period == HealthPeriod.WEEK) week else listOf(state.date))
    }
    var showAll by rememberSaveable(metric, period, state.date) { mutableStateOf(false) }
    val goal = if (metric == HealthMetric.ACTIVITY) stepGoal(viewModel) else null
    LifecycleStartEffect(viewModel) {
        viewModel.refreshRecent()
        onStopOrDispose {}
    }
    PageScaffold(title = stringResource(metric.title), onBack = onBack) { padding ->
        PageList(padding, spacing = 16.dp) {
            item(key = "date") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DateSelector(state.date, state.today, state.earliest, viewModel::selectDate)
                    PeriodSelector(period) { period = it }
                }
            }
            when (period) {
                HealthPeriod.DAY -> dayContent(metric, state, goal, showAll) { showAll = true }
                HealthPeriod.WEEK -> weekContent(metric, state, week, goal)
            }
            item(key = "about") { FootnoteText(stringResource(metric.explanation())) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodSelector(period: HealthPeriod, onSelect: (HealthPeriod) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        HealthPeriod.entries.forEachIndexed { index, item ->
            SegmentedButton(
                selected = item == period,
                onClick = { onSelect(item) },
                shape = SegmentedButtonDefaults.itemShape(index, HealthPeriod.entries.size),
                modifier = Modifier.testTag("period_" + item.name),
            ) {
                Text(stringResource(item.title))
            }
        }
    }
}

private fun HealthMetric.explanation() =
    when (this) {
        HealthMetric.ACTIVITY -> R.string.health_about_steps
        HealthMetric.HEART -> R.string.health_about_heart
        HealthMetric.SLEEP -> R.string.health_about_sleep
        HealthMetric.OXYGEN -> R.string.health_about_oxygen
        HealthMetric.CALORIES -> R.string.health_about_calories
        HealthMetric.DISTANCE -> R.string.health_about_distance
        HealthMetric.EXERCISE -> R.string.health_about_exercise
        HealthMetric.CLIMBING -> R.string.health_about_climbing
        HealthMetric.STRESS -> R.string.health_about_stress
        HealthMetric.RESPIRATION -> R.string.health_about_respiration
        HealthMetric.TEMPERATURE -> R.string.health_about_temperature
        HealthMetric.SKIN_TEMPERATURE -> R.string.health_about_skin_temperature
        HealthMetric.VO2MAX -> R.string.health_about_vo2max
        HealthMetric.NOISE -> R.string.health_about_noise
    }

private const val InitialRecords = 24
private const val MetersPerFloor = 3

private fun LazyListScope.dayContent(
    metric: HealthMetric,
    state: HealthUiState,
    goal: Int?,
    showAll: Boolean,
    onShowAll: () -> Unit,
) {
    val result = state.dayView(state.date)
    val day = result?.day
    item(key = "status") {
        ReadStateBanner(result?.state(metric), loading = state.isLoading(state.date))
    }
    if (day == null || !day.hasData(metric)) return
    if (metric == HealthMetric.SLEEP) {
        sleepDayContent(day, showAll, onShowAll)
        return
    }
    if (metric == HealthMetric.ACTIVITY) {
        stepsDayContent(day, state.today, goal)
        return
    }
    val points = day.points(metric)
    val until = result.until(metric)
    item(key = "summary") {
        val summary = day.summary(metric)
        Summary(
            summaryCaption(metric, pastDay = until != null),
            metricFormatter().value(metric, summary),
            if (metric == HealthMetric.CLIMBING) {
                // The vendor counts one floor per 3 m climbed; the value itself stays in meters.
                val floors = (summary / MetersPerFloor).toInt()
                pluralStringResource(R.plurals.health_climbing_floors, floors, floors)
            } else null,
        )
    }
    item(key = "chart") { DayChart(metric, day, points, until, state.today) }
    item(key = "range") {
        val formatter = metricFormatter()
        RangeRow(
            low = formatter.value(metric, points.minOf { it.value }),
            high = formatter.value(metric, points.maxOf { it.value }),
        )
    }
    val shown = if (showAll) points.asReversed() else points.asReversed().take(InitialRecords)
    item(key = "records_title") {
        SectionTitle(pluralStringResource(R.plurals.health_records_count, points.size, points.size))
    }
    itemsIndexed(
        shown,
        key = { index, point -> "point_" + point.time.toEpochMilli() + "_" + index },
    ) { _, point ->
        RecordRow(
            readingTime(point, state.today, rolling = until != null),
            metricFormatter().value(metric, point.value),
        )
    }
    if (shown.size < points.size) {
        item(key = "show_all") { ShowAllButton(points.size, onShowAll) }
    }
}

/**
 * A day of steps: the ring toward the goal, the day's other activity, and its steps by the hour.
 * Half-hour totals are too fine to read at a glance, and their lowest and highest say little.
 */
private fun LazyListScope.stepsDayContent(day: HealthDay, today: LocalDate, goal: Int?) {
    val metric = HealthMetric.ACTIVITY
    val zone = ZoneId.systemDefault()
    val hours =
        hourlyTotals(
            day.steps,
            day.date.atStartOfDay(zone).toInstant(),
            day.date.plusDays(1).atStartOfDay(zone).toInstant(),
        )
    item(key = "summary") {
        if (goal != null) StepsHero(day.stepTotal, goal)
        else Summary(summaryCaption(metric), metricFormatter().value(metric, day.summary(metric)))
    }
    item(key = "stats") { ActivityStats(day) }
    item(key = "chart") { DayChart(metric, day, hours, until = null, today, peakFirst = true) }
    item(key = "records_title") { SectionTitle(stringResource(R.string.health_by_hour)) }
    items(hours.asReversed(), key = { "hour_" + it.time.toEpochMilli() }) { point ->
        RecordRow(
            readingTime(point, today, rolling = false),
            metricFormatter().value(metric, point.value),
        )
    }
}

private fun LazyListScope.sleepDayContent(day: HealthDay, showAll: Boolean, onShowAll: () -> Unit) {
    val main = day.mainSleep ?: return
    val naps = day.naps.size
    item(key = "summary") {
        Summary(
            stringResource(R.string.health_caption_asleep),
            metricFormatter().duration(day.asleepMinutes),
            if (naps == 0) sleepRange(main)
            else pluralStringResource(R.plurals.sleep_naps_included, naps, naps),
        )
    }
    day.sleeps.forEachIndexed { index, sleep ->
        item(key = "chart_$index") { SleepCard(sleep, title = sleepTitle(index, naps)) }
    }
    day.sleeps.forEachIndexed { index, sleep ->
        val segments = sleep.segments
        val shown = if (showAll) segments else segments.take(InitialRecords)
        item(key = "records_title_$index") {
            val count =
                pluralStringResource(R.plurals.sleep_stage_count, segments.size, segments.size)
            val title = sleepTitle(index, naps)
            SectionTitle(
                if (title == null) count
                else stringResource(R.string.sleep_period_stages, title, count)
            )
        }
        items(shown, key = { "segment_" + it.start.toEpochMilli() }) { segment ->
            val locale = currentLocale()
            RecordRow(
                stringResource(
                    R.string.time_range,
                    formatTime(segment.start.toEpochMilli(), locale),
                    formatTime(segment.end.toEpochMilli(), locale),
                ),
                stringResource(
                    R.string.sleep_segment_value,
                    stringResource(segment.stage.title),
                    metricFormatter().durationMillis(segment.durationMillis),
                ),
            )
        }
        if (shown.size < segments.size) {
            item(key = "show_all_$index") { ShowAllButton(segments.size, onShowAll) }
        }
    }
}

/** What a day's sleep is called on its page; nothing when it is the only one. */
@Composable
private fun sleepTitle(index: Int, naps: Int): String? =
    when {
        naps == 0 -> null
        index == 0 -> stringResource(R.string.sleep_main)
        else -> stringResource(R.string.sleep_nap)
    }

@Composable
private fun sleepRange(sleep: SleepPeriod): String {
    val locale = currentLocale()
    return stringResource(
        R.string.time_range,
        formatTime(sleep.start.toEpochMilli(), locale),
        formatTime(sleep.end.toEpochMilli(), locale),
    )
}

@Composable
private fun SleepCard(sleep: SleepPeriod, title: String?) {
    val locale = currentLocale()
    val formatter = metricFormatter()
    val totals = SleepStage.entries.associateWith(sleep::stageMillis)
    ChartCard {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        sleepRange(sleep),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    formatter.durationMillis(sleep.asleepMillis),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        SleepTimeline(
            sleep,
            labels =
                listOf(sleep.start, sleep.end).map {
                    AxisLabel(it, formatTime(it.toEpochMilli(), locale))
                },
            description =
                stringResource(
                    R.string.chart_sleep_description,
                    SleepStage.entries.joinToString(", ") {
                        formatter.durationMillis(totals.getValue(it))
                    },
                ),
        )
        HorizontalDivider()
        SleepLegend(totals, formatter)
    }
}

private fun LazyListScope.weekContent(
    metric: HealthMetric,
    state: HealthUiState,
    week: List<LocalDate>,
    goal: Int?,
) {
    val daily = week.mapNotNull { date ->
        state.result(date)?.day?.takeIf { it.hasData(metric) }?.let { date to it.summary(metric) }
    }
    val loading = week.any { state.isLoading(it) }
    val incomplete = week.any { date ->
        state.result(date)?.state(metric).let {
            it == HealthReadState.FAILED || it == HealthReadState.PARTIAL
        }
    }
    item(key = "status") {
        when {
            loading && daily.isEmpty() -> ReadStateBanner(null, loading = true)
            incomplete -> ReadStateBanner(HealthReadState.PARTIAL, loading = false)
        }
    }
    if (daily.isEmpty()) {
        if (!loading)
            item(key = "empty") { ReadStateBanner(HealthReadState.EMPTY, loading = false) }
        return
    }
    val zone = ZoneId.systemDefault()
    val average = daily.map { it.second }.average()
    item(key = "summary") {
        Summary(
            stringResource(R.string.health_caption_daily_average),
            metricFormatter().value(metric, average),
            if (goal != null) {
                val met = daily.count { it.second >= goal }
                pluralStringResource(R.plurals.health_days_goal_met, daily.size, met, daily.size)
            } else {
                pluralStringResource(
                    R.plurals.health_days_recorded,
                    daily.size,
                    daily.size,
                    week.size,
                )
            },
        )
    }
    item(key = "chart") {
        val locale = currentLocale()
        val points = daily.map { (date, value) ->
            HealthPoint(
                date.atStartOfDay(zone).toInstant(),
                value,
                endExclusive = date.plusDays(1).atStartOfDay(zone).toInstant(),
            )
        }
        // The latest day is read out until a bar is picked; only a picked bar dims the others.
        var picked by remember(points) { mutableStateOf<Int?>(null) }
        val formatter = metricFormatter()
        val weekday =
            remember(locale) {
                DateTimeFormatter.ofPattern(
                    DateFormat.getBestDateTimePattern(locale, "EEEEE"),
                    locale,
                )
            }
        ChartCard {
            SelectionReadout(
                (picked ?: points.lastIndex).let(points::getOrNull)?.let {
                    stringResource(
                        R.string.chart_selection,
                        formatShortDate(it.time.atZone(zone).toLocalDate(), locale),
                        formatter.value(metric, it.value),
                    )
                }
            )
            TimeSeriesChart(
                points = points,
                metric = metric,
                axisStart = week.first().atStartOfDay(zone).toInstant(),
                axisEnd = week.last().plusDays(1).atStartOfDay(zone).toInstant(),
                labels =
                    week.map {
                        AxisLabel(it.atTime(12, 0).atZone(zone).toInstant(), weekday.format(it))
                    },
                bars = true,
                weekly = true,
                selected = picked,
                onSelect = { picked = it },
                description = chartDescription(metric, points, formatter),
                goal = goal?.toDouble(),
            )
        }
    }
    item(key = "range") {
        val formatter = metricFormatter()
        RangeRow(
            low = formatter.value(metric, daily.minOf { it.second }),
            high = formatter.value(metric, daily.maxOf { it.second }),
        )
    }
    item(key = "records_title") { SectionTitle(stringResource(R.string.health_daily_values)) }
    items(daily.asReversed(), key = { "day_" + it.first.toEpochDay() }) { (date, value) ->
        RecordRow(dayLabel(date, state.today), metricFormatter().value(metric, value))
    }
}

@Composable
private fun DayChart(
    metric: HealthMetric,
    day: HealthDay,
    points: List<HealthPoint>,
    until: Instant?,
    today: LocalDate,
    /** Read out the largest value until one is picked, rather than the latest. */
    peakFirst: Boolean = false,
) {
    val zone = ZoneId.systemDefault()
    val locale = currentLocale()
    val formatter = metricFormatter()
    val bars = metric.dailySummary == DailySummary.TOTAL
    // Bars are only dimmed around one the user picked; a line marks the reading it reads out.
    var picked by remember(points) { mutableStateOf<Int?>(null) }
    val peak = remember(points) { points.indices.maxByOrNull { points[it].value } }
    val shown = picked ?: if (peakFirst) peak else points.lastIndex
    val start = until?.minus(Duration.ofDays(1)) ?: day.date.atStartOfDay(zone).toInstant()
    val end = until ?: day.date.plusDays(1).atStartOfDay(zone).toInstant()
    val marks = sixHourMarks(start, end, zone)
    val hour =
        remember(locale) {
            DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "j"), locale)
        }
    ChartCard {
        SelectionReadout(
            shown?.let(points::getOrNull)?.let {
                stringResource(
                    if (picked == null && peakFirst) R.string.steps_most_active
                    else R.string.chart_selection,
                    readingTime(it, today, rolling = until != null),
                    formatter.value(metric, it.value),
                )
            }
        )
        TimeSeriesChart(
            points = points,
            metric = metric,
            axisStart = start,
            axisEnd = end,
            labels = marks.map { AxisLabel(it, hour.format(it.atZone(zone))) },
            bars = bars,
            selected = if (bars) picked else shown,
            onSelect = { picked = it },
            description = chartDescription(metric, points, formatter),
        )
    }
}

/** Midnight, 6, noon and 18 o'clock wherever they fall, as on a calendar day's chart. */
private fun sixHourMarks(start: Instant, end: Instant, zone: ZoneId): List<Instant> {
    val hour = start.atZone(zone).truncatedTo(ChronoUnit.HOURS)
    return (0L..26L)
        .map { hour.plusHours(it) }
        .filter { it.hour % 6 == 0 && it.toInstant() in start..end }
        .map { it.toInstant() }
}

/** An aggregate readout names the full half-open period, not a fabricated sample timestamp. */
@Composable
private fun readingTime(point: HealthPoint, today: LocalDate, rolling: Boolean): String {
    val start = sampleTime(point.time, today, rolling)
    return point.endExclusive?.let { end ->
        stringResource(R.string.time_range, start, sampleTime(end, today, rolling))
    } ?: start
}

/** A sample's time, which over the past 24 hours also has to say when it isn't today. */
@Composable
private fun sampleTime(time: Instant, today: LocalDate, rolling: Boolean): String {
    val zone = ZoneId.systemDefault()
    val local = time.atZone(zone)
    val ambiguous = zone.rules.getValidOffsets(local.toLocalDateTime()).size > 1
    val clock =
        formatTime(time.toEpochMilli(), currentLocale()) +
            if (ambiguous) " " + local.offset.id else ""
    val date = local.toLocalDate()
    if (!rolling || date == today) return clock
    return stringResource(R.string.health_day_time, dayLabel(date, today), clock)
}

@Composable
private fun chartDescription(
    metric: HealthMetric,
    points: List<HealthPoint>,
    formatter: MetricFormatter,
): String =
    stringResource(
        R.string.chart_description,
        stringResource(metric.title),
        pluralStringResource(R.plurals.chart_readings, points.size, points.size),
        formatter.value(metric, points.minOf { it.value }),
        formatter.value(metric, points.maxOf { it.value }),
    )

@Composable
internal fun ChartCard(content: @Composable () -> Unit) {
    Card(
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}

@Composable
internal fun SelectionReadout(text: String?) {
    Text(
        text ?: " ",
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun Summary(caption: String, value: String, detail: String? = null) {
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            caption,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Medium)
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RangeRow(low: String, high: String) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatCard(stringResource(R.string.health_lowest), low, Modifier.weight(1f).fillMaxHeight())
        StatCard(stringResource(R.string.health_highest), high, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
internal fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier,
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
internal fun RecordRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun ShowAllButton(count: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(pluralStringResource(R.plurals.health_show_all, count, count))
    }
}

@Composable
internal fun ReadStateBanner(state: HealthReadState?, loading: Boolean) {
    val text =
        when {
            loading -> return
            state == null || state == HealthReadState.AVAILABLE -> return
            state == HealthReadState.EMPTY -> stringResource(R.string.health_detail_empty)
            state == HealthReadState.PARTIAL -> stringResource(R.string.health_detail_partial)
            state == HealthReadState.UNAVAILABLE ->
                stringResource(R.string.health_detail_unavailable)
            else -> stringResource(R.string.health_detail_failed)
        }
    val tone = if (state == HealthReadState.EMPTY) BannerTone.Info else BannerTone.Warning
    StatusBanner(text, tone = tone)
}
