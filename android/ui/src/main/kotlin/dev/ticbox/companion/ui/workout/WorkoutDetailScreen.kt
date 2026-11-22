package dev.ticbox.companion.ui.workout

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.api.HealthFitnessExtraKind
import dev.ticbox.companion.api.HealthFitnessGroupState
import dev.ticbox.companion.api.HealthFitnessRecord
import dev.ticbox.companion.api.HealthFitnessScoreMeaning
import dev.ticbox.companion.api.HealthFitnessStroke
import dev.ticbox.companion.api.HealthFitnessTargetType
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.currentLocale
import dev.ticbox.companion.ui.common.formatDateTime
import dev.ticbox.companion.ui.common.formatNumber
import dev.ticbox.companion.ui.common.formatTime
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.AppIcon
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.BannerTone
import dev.ticbox.companion.ui.designsystem.EmptyState
import dev.ticbox.companion.ui.designsystem.FadeSwitch
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.LocalHealthColors
import dev.ticbox.companion.ui.designsystem.PageColumn
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.SectionTitle
import dev.ticbox.companion.ui.designsystem.StatusBanner
import dev.ticbox.companion.ui.health.AxisLabel
import dev.ticbox.companion.ui.health.ChartCard
import dev.ticbox.companion.ui.health.HealthMetric
import dev.ticbox.companion.ui.health.HealthPoint
import dev.ticbox.companion.ui.health.PlotChart
import dev.ticbox.companion.ui.health.SelectionReadout
import dev.ticbox.companion.ui.health.StatCard
import dev.ticbox.companion.ui.health.ValueRange
import dev.ticbox.companion.ui.health.metricFormatter
import java.time.Instant
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

@Composable
fun WorkoutDetailScreen(key: String, onBack: () -> Unit) {
    val viewModel = companionViewModel { WorkoutDetailViewModel(it, key) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val detail = state.detail
    PageScaffold(
        if (detail != null) sportName(detail.summary.typeCode)
        else stringResource(R.string.workouts_title),
        onBack = onBack,
    ) { padding ->
        FadeSwitch(
            state,
            key = {
                when {
                    it.detail != null -> 0
                    it.loading -> 1
                    else -> 2
                }
            },
        ) { shown ->
            val loaded = shown.detail
            when {
                loaded != null -> PageColumn(padding) { WorkoutContent(loaded) }
                shown.loading ->
                    Box(
                        Modifier.fillMaxSize().padding(padding),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                else ->
                    EmptyState(
                        AppIcons.Running,
                        stringResource(R.string.workout_missing_title),
                        stringResource(
                            if (shown.failed) R.string.workouts_failed_body
                            else R.string.workout_missing_body
                        ),
                        Modifier.padding(padding),
                    )
            }
        }
    }
}

@Composable
private fun WorkoutContent(detail: WorkoutDetail) {
    val analysis = detail.analysis ?: WorkoutAnalysis()
    Header(detail)
    SamplesBanner(detail)
    StatGrid(stats(detail, analysis))
    if (analysis.route.isNotEmpty()) RouteCard(analysis.route)
    val total = detail.summary.durationMillis ?: analysis.heart.lastOrNull()?.elapsedMillis ?: 0
    SeriesCards(detail, analysis, total)
    analysis.zones?.let { ZonesCard(it) }
    Tables(detail, analysis)
    Recovery(detail)
    detail.summary.typeCode?.let { code ->
        if (sportNameResource(code) == null) {
            FootnoteText(stringResource(R.string.workout_unknown_type_note))
        }
    }
}

@Composable
private fun Header(detail: WorkoutDetail) {
    val summary = detail.summary
    val locale = currentLocale()
    Row(
        Modifier.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppIcon(
            sportKind(summary.typeCode).icon,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = LocalHealthColors.current.activity,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val start = summary.startMillis
            val end = summary.endMillis
            Text(
                when {
                    start != null && end != null ->
                        stringResource(
                            R.string.time_range,
                            formatDateTime(start, locale),
                            formatTime(end, locale),
                        )
                    start != null -> formatDateTime(start, locale)
                    else -> stringResource(R.string.workouts_undated)
                },
                style = MaterialTheme.typography.titleMedium,
            )
            if (summary.autoDetected == true) {
                Text(
                    stringResource(R.string.workout_auto_detected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Why samples may be missing; each case needs a different answer from the user. */
@Composable
private fun SamplesBanner(detail: WorkoutDetail) {
    val open = detail.record?.endMillis?.let { it <= 0 } == true
    when {
        detail.samples == WorkoutSamples.SUMMARY_ONLY ->
            StatusBanner(stringResource(R.string.workout_samples_summary_only))
        detail.samples == WorkoutSamples.NOT_SYNCED ->
            StatusBanner(stringResource(R.string.workout_samples_not_synced))
        detail.samples == WorkoutSamples.EMPTY ->
            StatusBanner(stringResource(R.string.workout_samples_empty))
        detail.samples == WorkoutSamples.PARTIAL ->
            StatusBanner(
                stringResource(R.string.workout_samples_partial),
                tone = BannerTone.Warning,
            )
        detail.samples == WorkoutSamples.FAILED ->
            StatusBanner(
                stringResource(R.string.workout_samples_failed),
                tone = BannerTone.Warning,
            )
        open -> StatusBanner(stringResource(R.string.workout_open))
    }
}

private data class Stat(val title: String, val value: String)

@Composable
private fun stats(detail: WorkoutDetail, analysis: WorkoutAnalysis): List<Stat> {
    val summary = detail.summary
    val record = detail.record
    val kind = sportKind(summary.typeCode)
    val format = workoutFormatter()
    val metric = metricFormatter()
    val locale = currentLocale()
    val duration = summary.durationMillis
    val distance = summary.distanceMeters
    return buildList {
        duration?.let { add(Stat(stringResource(R.string.workout_duration), format.elapsed(it))) }
        if (distance != null && (distance > 0 || kind.showsDistance)) {
            add(
                Stat(
                    stringResource(R.string.metric_distance),
                    metric.value(HealthMetric.DISTANCE, distance.toDouble()),
                )
            )
        }
        // Averages come from the totals; a zero denominator has no value.
        if (duration != null && distance != null && distance > 0 && duration > 0) {
            if (kind.showsPace) {
                val pace = duration.toDouble() / distance
                add(Stat(stringResource(R.string.workout_average_pace), format.pace(pace)))
            }
            if (kind.showsSpeed) {
                val speed = distance * 1000.0 / duration
                add(Stat(stringResource(R.string.workout_average_speed), format.speed(speed)))
            }
        }
        summary.calories?.let {
            add(Stat(stringResource(R.string.metric_calories), metric.calories(it)))
        }
        summary.averageHeartRate?.let {
            add(
                Stat(
                    stringResource(R.string.workout_average_heart),
                    metric.value(HealthMetric.HEART, it.toDouble()),
                )
            )
        }
        analysis.maxHeartRate?.let {
            add(
                Stat(
                    stringResource(R.string.workout_max_heart),
                    metric.value(HealthMetric.HEART, it.toDouble()),
                )
            )
        }
        summary.steps
            ?.takeIf { it > 0 }
            ?.let {
                add(Stat(stringResource(R.string.metric_steps), formatNumber(it, locale)))
            }
        if (record != null) addAll(recordStats(record, analysis))
    }
}

/** Numbers only the full record holds, shown where the sport gives them a confirmed meaning. */
@Composable
private fun recordStats(record: HealthFitnessRecord, analysis: WorkoutAnalysis): List<Stat> {
    val format = workoutFormatter()
    val locale = currentLocale()
    return buildList {
        record.swimming?.let { swim ->
            swim.tripsDecimal
                ?.toDoubleOrNull()
                ?.takeIf { it > 0 }
                ?.let {
                    add(Stat(stringResource(R.string.workout_trips), format.number(it)))
                }
            swim.poolLengthMeters
                ?.takeIf { it > 0 }
                ?.let {
                    add(
                        Stat(
                            stringResource(R.string.workout_pool_length),
                            format.meters(it.toDouble()),
                        )
                    )
                }
            swim.strokeCount
                ?.takeIf { it > 0 }
                ?.let {
                    add(Stat(stringResource(R.string.workout_strokes), formatNumber(it, locale)))
                }
        }
        analysis.secondsPer100Meters?.let {
            add(Stat(stringResource(R.string.workout_pace_100m), format.pace100(it)))
        }
        record.land
            ?.vo2maxMlKgMinDecimal
            ?.toDoubleOrNull()
            ?.takeIf { it > 0 }
            ?.let {
                add(
                    Stat(
                        stringResource(R.string.metric_vo2max),
                        metricFormatter().value(HealthMetric.VO2MAX, it),
                    )
                )
            }
        val score =
            record.scoreDecimal?.toDoubleOrNull()?.takeIf { "SCORE" !in record.sentinelColumns }
        if (score != null) {
            when (record.scoreMeaning) {
                HealthFitnessScoreMeaning.ACTION_COUNT ->
                    add(Stat(stringResource(R.string.workout_actions), format.number(score, 0)))
                HealthFitnessScoreMeaning.RUNNING_AI_SCORE ->
                    add(Stat(stringResource(R.string.workout_ai_score), format.number(score)))
                HealthFitnessScoreMeaning.UNCONFIRMED -> Unit
            }
        }
        analysis.hiitGroups?.let {
            add(Stat(stringResource(R.string.workout_groups), formatNumber(it, locale)))
        }
        if (analysis.groups.isNotEmpty()) {
            val groups = analysis.groups.count { it.state == HealthFitnessGroupState.TRAIN }
            add(Stat(stringResource(R.string.workout_groups), formatNumber(groups, locale)))
        }
        val up = record.cumulativeUpMetersDecimal?.toDoubleOrNull()?.takeIf { it > 0 }
        val down = record.cumulativeDownMetersDecimal?.toDoubleOrNull()?.takeIf { it > 0 }
        // Hidden only when neither is positive.
        if (up != null || down != null) {
            add(Stat(stringResource(R.string.workout_elevation_up), format.meters(up ?: 0.0)))
            add(Stat(stringResource(R.string.workout_elevation_down), format.meters(down ?: 0.0)))
        }
        targetStat(record)?.let { add(it) }
    }
}

@Composable
private fun targetStat(record: HealthFitnessRecord): Stat? {
    if ("TARGET_VALUE" in record.sentinelColumns) return null
    val target = record.targetValueDecimal?.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
    val format = workoutFormatter()
    val metric = metricFormatter()
    val locale = currentLocale()
    val value =
        when (record.targetType) {
            HealthFitnessTargetType.DURATION -> format.elapsed(target.toLong())
            HealthFitnessTargetType.DISTANCE,
            HealthFitnessTargetType.SWIM_DISTANCE -> metric.value(HealthMetric.DISTANCE, target)
            HealthFitnessTargetType.CALORIE -> metric.calories(target)
            HealthFitnessTargetType.STEPS,
            HealthFitnessTargetType.GROUPS,
            HealthFitnessTargetType.SWIM_TRIPS -> formatNumber(target.toLong(), locale)
            else -> return null
        }
    return Stat(stringResource(R.string.workout_target), value)
}

@Composable
private fun StatGrid(stats: List<Stat>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        stats.chunked(2).forEach { row ->
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { StatCard(it.title, it.value, Modifier.weight(1f).fillMaxHeight()) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** The track's shape only: no base map, and coordinates are drawn as stored. */
@Composable
private fun RouteCard(route: List<List<RoutePoint>>) {
    val color = LocalHealthColors.current.activity
    val start = LocalHealthColors.current.oxygen
    val end = LocalHealthColors.current.heart
    val pointCount = route.sumOf { it.size }
    val description =
        pluralStringResource(R.plurals.workout_route_description, pointCount, pointCount)
    Section(stringResource(R.string.workout_route)) {
        ChartCard {
            Canvas(
                Modifier.fillMaxWidth().height(220.dp).semantics {
                    contentDescription = description
                }
            ) {
                val all = route.flatten()
                val south = all.minOf { it.latitude }
                val north = all.maxOf { it.latitude }
                val west = all.minOf { it.longitude }
                val east = all.maxOf { it.longitude }
                // Longitude degrees shrink away from the equator.
                val squeeze = cos(Math.toRadians((south + north) / 2))
                val spanX = max((east - west) * squeeze, 1e-6)
                val spanY = max(north - south, 1e-6)
                val inset = 12.dp.toPx()
                val scale = min((size.width - 2 * inset) / spanX, (size.height - 2 * inset) / spanY)
                val left = (size.width - spanX * scale) / 2
                val top = (size.height - spanY * scale) / 2
                fun place(point: RoutePoint) =
                    Offset(
                        (left + (point.longitude - west) * squeeze * scale).toFloat(),
                        (top + (north - point.latitude) * scale).toFloat(),
                    )
                route.forEach { segment ->
                    val path =
                        Path().apply {
                            segment.forEachIndexed { index, point ->
                                val at = place(point)
                                if (index == 0) moveTo(at.x, at.y) else lineTo(at.x, at.y)
                            }
                        }
                    drawPath(
                        path,
                        color,
                        style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
                drawCircle(start, 5.dp.toPx(), place(all.first()))
                drawCircle(end, 5.dp.toPx(), place(all.last()))
            }
        }
    }
    FootnoteText(stringResource(R.string.workout_route_note))
}

@Composable
private fun SeriesCards(detail: WorkoutDetail, analysis: WorkoutAnalysis, totalMillis: Long) {
    val colors = LocalHealthColors.current
    val format = workoutFormatter()
    val metric = metricFormatter()
    val kind = sportKind(detail.summary.typeCode)
    SeriesCard(
        stringResource(R.string.metric_heart_rate),
        analysis.heart,
        colors.heart,
        totalMillis,
        { metric.number(HealthMetric.HEART, it) },
        { metric.value(HealthMetric.HEART, it) },
    )
    if (kind.showsPace) {
        // Slower is lower on the chart; instantaneous pace is only ever shown, never averaged.
        val pace = analysis.speed.filter { it.value > 0 }.map { it.copy(value = 1000 / it.value) }
        SeriesCard(
            stringResource(R.string.workout_pace),
            pace,
            colors.activity,
            totalMillis,
            { format.paceAxis(it) },
            { format.pace(it) },
            inverted = true,
        )
    } else if (analysis.speed.any { it.value > 0 }) {
        SeriesCard(
            stringResource(R.string.workout_speed),
            analysis.speed.map { it.copy(value = it.value * 3.6) },
            colors.activity,
            totalMillis,
            { format.number(it) },
            { format.speed(it / 3.6) },
        )
    }
    SeriesCard(
        stringResource(R.string.workout_cadence),
        analysis.cadence,
        colors.oxygen,
        totalMillis,
        { format.number(it, 0) },
        { stringResource(R.string.value_steps_per_minute, format.number(it, 0)) },
        footnote = if (analysis.cadenceDerived) R.string.workout_derived_cadence else null,
    )
    SeriesCard(
        stringResource(R.string.workout_stride),
        analysis.strideMeters,
        colors.rem,
        totalMillis,
        { format.number(it, 2) },
        { stringResource(R.string.value_meters_per_step, format.number(it, 2)) },
        footnote = R.string.workout_derived_stride,
    )
    SeriesCard(
        stringResource(R.string.workout_elevation),
        analysis.elevation,
        colors.sleep,
        totalMillis,
        { format.number(it, 0) },
        { format.meters(it) },
    )
}

@Composable
private fun SeriesCard(
    title: String,
    series: List<ElapsedValue>,
    color: Color,
    totalMillis: Long,
    axisLabel: (Double) -> String,
    valueText: @Composable (Double) -> String,
    inverted: Boolean = false,
    footnote: Int? = null,
) {
    if (series.size < 2) return
    val points = remember(series) { thin(series) }
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val format = workoutFormatter()
    val end = max(totalMillis, points.last().time.toEpochMilli())
    val range =
        remember(points, inverted) {
            val low = points.minOf { it.value }
            val high = points.maxOf { it.value }
            val pad = max((high - low) * 0.1, 1e-3)
            if (inverted) ValueRange(high + pad, low - pad)
            else ValueRange(max(0.0, low - pad).takeIf { low >= 0 } ?: (low - pad), high + pad)
        }
    Section(title) {
        ChartCard {
            SelectionReadout(
                selected?.let(points::getOrNull)?.let {
                    stringResource(
                        R.string.chart_selection,
                        format.elapsed(it.time.toEpochMilli()),
                        valueText(it.value),
                    )
                }
            )
            PlotChart(
                points = points,
                color = color,
                range = range,
                gridLabel = axisLabel,
                axisStart = Instant.EPOCH,
                axisEnd = Instant.ofEpochMilli(end),
                labels =
                    listOf(0L, end / 2, end).map {
                        AxisLabel(Instant.ofEpochMilli(it), format.elapsed(it))
                    },
                bars = false,
                selected = selected,
                onSelect = { selected = it },
                description =
                    pluralStringResource(
                        R.plurals.workout_chart_description,
                        points.size,
                        title,
                        points.size,
                        valueText(points.minOf { it.value }),
                        valueText(points.maxOf { it.value }),
                    ),
                gapMillis = SeriesGapMillis,
            )
        }
    }
    footnote?.let { FootnoteText(stringResource(it)) }
}

private const val SeriesGapMillis = 120_000L
private const val MaxChartPoints = 300

/** Averages long series into at most [MaxChartPoints], keeping gaps visible. */
private fun thin(series: List<ElapsedValue>): List<HealthPoint> {
    val bucket = (series.size / MaxChartPoints) + 1
    return series.chunked(bucket).map { group ->
        HealthPoint(
            Instant.ofEpochMilli(group.last().elapsedMillis),
            group.map { it.value }.average(),
        )
    }
}

@Composable
private fun ZonesCard(zones: HeartZones) {
    val format = workoutFormatter()
    val metric = metricFormatter()
    val total = zones.millis.values.sum().coerceAtLeast(1)
    val color = LocalHealthColors.current.heart
    Section(stringResource(R.string.workout_zones)) {
        ChartCard {
            HeartZone.entries.forEachIndexed { index, zone ->
                val millis = zones.millis.getValue(zone)
                val share = millis.toFloat() / total
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            stringResource(
                                R.string.workout_zone_row,
                                stringResource(zone.title),
                                metric.value(
                                    HealthMetric.HEART,
                                    zones.thresholds.getValue(zone).toDouble(),
                                ),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(
                                R.string.workout_zone_share,
                                format.elapsed(millis),
                                (share * 100).toInt(),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        Modifier.fillMaxWidth()
                            .height(8.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                                RoundedCornerShape(4.dp),
                            )
                    ) {
                        Box(
                            Modifier.fillMaxWidth(share)
                                .height(8.dp)
                                .background(
                                    color.copy(alpha = 0.4f + 0.15f * index),
                                    RoundedCornerShape(4.dp),
                                )
                        )
                    }
                }
            }
        }
    }
    FootnoteText(
        stringResource(
            if (zones.assumedAge) R.string.workout_zones_note_assumed
            else R.string.workout_zones_note,
            zones.maximum,
        )
    )
}

private val HeartZone.title: Int
    get() =
        when (this) {
            HeartZone.WARM_UP -> R.string.workout_zone_warm_up
            HeartZone.FAT_BURN -> R.string.workout_zone_fat_burn
            HeartZone.AEROBIC -> R.string.workout_zone_aerobic
            HeartZone.ANAEROBIC -> R.string.workout_zone_anaerobic
            HeartZone.DANGER -> R.string.workout_zone_danger
        }

@Composable
private fun Tables(detail: WorkoutDetail, analysis: WorkoutAnalysis) {
    val format = workoutFormatter()
    val locale = currentLocale()
    val metric = metricFormatter()
    val kind = sportKind(detail.summary.typeCode)
    if (kind.showsSplits && analysis.splits.isNotEmpty()) {
        DataTable(
            stringResource(R.string.workout_splits),
            listOf(
                stringResource(R.string.workout_column_km),
                stringResource(R.string.workout_column_pace),
                stringResource(R.string.workout_column_change),
            ),
            analysis.splits.mapIndexed { index, split ->
                val previous = analysis.splits.getOrNull(index - 1)
                val change = previous?.let { split.secondsPerKm - it.secondsPerKm }
                listOf(
                    if (split.full) formatNumber(split.index, locale)
                    else stringResource(R.string.workout_split_short, split.index),
                    format.pace(split.secondsPerKm),
                    change?.let { signedSeconds(it) } ?: "–",
                )
            },
        )
    }
    if (analysis.laps.isNotEmpty()) {
        DataTable(
            stringResource(R.string.workout_laps),
            listOf(
                stringResource(R.string.workout_column_lap),
                stringResource(R.string.workout_column_distance),
                stringResource(R.string.workout_column_total_time),
                stringResource(R.string.workout_column_lap_time),
                stringResource(R.string.workout_column_pace),
            ),
            analysis.laps.map { lap ->
                listOf(
                    formatNumber(lap.number, locale),
                    lap.meters?.let { metric.value(HealthMetric.DISTANCE, it.toDouble()) } ?: "–",
                    format.elapsed(lap.cumulativeMillis),
                    lap.lapMillis?.let(format::elapsed) ?: "–",
                    lap.secondsPerKm?.let(format::pace) ?: "–",
                )
            },
        )
    }
    if (analysis.strokes.isNotEmpty()) {
        DataTable(
            stringResource(R.string.workout_stroke_distances),
            listOf(
                stringResource(R.string.workout_column_stroke),
                stringResource(R.string.workout_column_distance),
            ),
            analysis.strokes.map { stroke ->
                listOf(strokeName(stroke.stroke), format.meters(stroke.meters))
            },
        )
    }
    if (analysis.swimTrips.isNotEmpty()) {
        DataTable(
            stringResource(R.string.workout_swim_trips),
            listOf(
                stringResource(R.string.workout_column_trip),
                stringResource(R.string.workout_column_distance),
                stringResource(R.string.workout_column_swim_time),
                stringResource(R.string.workout_strokes),
                stringResource(R.string.workout_column_swolf),
            ),
            analysis.swimTrips.map { trip ->
                listOf(
                    formatNumber(trip.number, locale),
                    format.meters(trip.cumulativeMeters.toDouble()),
                    trip.swimSeconds?.let { format.number(it) } ?: "–",
                    trip.strokes?.let { formatNumber(it, locale) } ?: "–",
                    trip.swolf?.let { formatNumber(it, locale) } ?: "–",
                )
            },
        )
        FootnoteText(stringResource(R.string.workout_swim_note))
    }
    if (analysis.hiitActions.isNotEmpty()) {
        DataTable(
            stringResource(R.string.workout_hiit_actions),
            listOf(
                stringResource(R.string.workout_column_action),
                stringResource(R.string.workout_column_duration),
                stringResource(R.string.workout_column_times),
            ),
            analysis.hiitActions.map { action ->
                listOf(
                    sportName(action.typeCode),
                    format.elapsed(action.millis),
                    action.times?.let { formatNumber(it, locale) } ?: "–",
                )
            },
        )
    }
    val record = detail.record
    if (record?.extra?.kind == HealthFitnessExtraKind.HIIT && record.extra.stages.isNotEmpty()) {
        DataTable(
            stringResource(R.string.workout_hiit_timeline),
            listOf(
                stringResource(R.string.workout_column_start),
                stringResource(R.string.workout_column_action),
                stringResource(R.string.workout_column_duration),
                stringResource(R.string.workout_column_times),
            ),
            record.extra.stages.map { stage ->
                listOf(
                    stage.startElapsedMillis?.let(format::elapsed) ?: "–",
                    sportName(stage.typeCode),
                    stage.durationMillis?.takeIf { it >= 0 }?.let(format::elapsed) ?: "–",
                    stage.actionTimes?.takeIf { it >= 0 }?.let { formatNumber(it, locale) } ?: "–",
                )
            },
        )
    }
    if (analysis.groups.isNotEmpty()) {
        DataTable(
            stringResource(R.string.workout_group_table),
            listOf(
                stringResource(R.string.workout_column_group),
                stringResource(R.string.workout_column_state),
                stringResource(R.string.workout_column_duration),
            ),
            analysis.groups.map { group ->
                listOf(
                    formatNumber(group.number, locale),
                    stringResource(
                        if (group.state == HealthFitnessGroupState.TRAIN)
                            R.string.workout_group_train
                        else R.string.workout_group_rest
                    ),
                    group.millis?.let(format::elapsed) ?: "–",
                )
            },
        )
    }
}

private fun signedSeconds(seconds: Double): String {
    val whole = seconds.toLong()
    return if (whole > 0) "+$whole″" else "$whole″"
}

@Composable
private fun strokeName(stroke: HealthFitnessStroke?): String =
    stringResource(
        when (stroke) {
            HealthFitnessStroke.FREESTYLE -> R.string.workout_stroke_freestyle
            HealthFitnessStroke.BREASTSTROKE -> R.string.workout_stroke_breaststroke
            HealthFitnessStroke.BACKSTROKE -> R.string.workout_stroke_backstroke
            HealthFitnessStroke.BUTTERFLY -> R.string.workout_stroke_butterfly
            null -> R.string.workout_stroke_other
        }
    )

@Composable
private fun Recovery(detail: WorkoutDetail) {
    val millis = detail.record?.extra?.recovery?.durationMillis?.takeIf { it > 0 } ?: return
    val end = detail.summary.endMillis
    val locale = currentLocale()
    val duration = metricFormatter().durationMillis(millis)
    StatusBanner(
        if (end != null) {
            stringResource(
                R.string.workout_recovery_until,
                duration,
                formatDateTime(end + millis, locale),
            )
        } else {
            stringResource(R.string.workout_recovery, duration)
        },
        title = stringResource(R.string.workout_recovery_title),
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title)
        content()
    }
}

/** Evenly spaced columns; the first reads from the start, the rest line up at the end. */
@Composable
private fun DataTable(title: String, headers: List<String>, rows: List<List<String>>) {
    Section(title) {
        ChartCard {
            TableRow(headers, header = true)
            HorizontalDivider()
            rows.forEach { TableRow(it, header = false) }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, header: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        cells.forEachIndexed { index, cell ->
            Text(
                cell,
                Modifier.weight(1f),
                style =
                    if (header) MaterialTheme.typography.labelMedium
                    else MaterialTheme.typography.bodyMedium,
                color =
                    if (header) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (!header && index == 0) FontWeight.Medium else null,
                textAlign = if (index == 0) TextAlign.Start else TextAlign.End,
            )
        }
    }
}
