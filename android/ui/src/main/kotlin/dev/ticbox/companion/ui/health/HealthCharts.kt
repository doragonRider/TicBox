package dev.ticbox.companion.ui.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.ticbox.companion.ui.R
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

/** A labelled position on the time axis. */
data class AxisLabel(val time: Instant, val text: String)

data class ValueRange(val min: Double, val max: Double) {
    fun fraction(value: Double) = ((value - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
}

/**
 * Bounds that start from a sensible baseline for each metric and end on a round number. [daily]
 * values are whole days, as a week or a month charts them.
 */
fun valueRange(metric: HealthMetric, values: List<Double>, daily: Boolean): ValueRange {
    val low = values.minOrNull() ?: 0.0
    val high = values.maxOrNull() ?: 0.0
    return when (metric) {
        HealthMetric.HEART ->
            ValueRange(min(40.0, floor(low / 10) * 10), max(120.0, ceil(high / 20) * 20))
        HealthMetric.OXYGEN -> ValueRange(min(90.0, floor(low)), 100.0)
        HealthMetric.ACTIVITY -> ValueRange(0.0, niceCeil(max(high, if (daily) 4_000.0 else 50.0)))
        HealthMetric.SLEEP -> ValueRange(0.0, max(10.0, ceil(high)))
        HealthMetric.CALORIES -> ValueRange(0.0, niceCeil(max(high, if (daily) 400.0 else 50.0)))
        HealthMetric.DISTANCE -> ValueRange(0.0, niceCeil(max(high, if (daily) 4_000.0 else 500.0)))
        HealthMetric.EXERCISE -> ValueRange(0.0, niceCeil(max(high, if (daily) 3_600.0 else 600.0)))
        HealthMetric.CLIMBING -> ValueRange(0.0, niceCeil(max(high, if (daily) 30.0 else 10.0)))
        HealthMetric.STRESS -> ValueRange(0.0, max(100.0, ceil(high / 10) * 10))
        HealthMetric.RESPIRATION -> ValueRange(min(10.0, floor(low)), max(30.0, ceil(high)))
        HealthMetric.TEMPERATURE,
        HealthMetric.SKIN_TEMPERATURE -> ValueRange(floor(low) - 1, ceil(high) + 1)
        HealthMetric.VO2MAX -> ValueRange(floor(low / 10) * 10, ceil(high / 10) * 10 + 10)
        HealthMetric.NOISE -> ValueRange(min(30.0, floor(low / 10) * 10), max(90.0, ceil(high)))
    }
}

internal fun niceCeil(value: Double): Double {
    if (value <= 0) return 1.0
    val magnitude = 10.0.pow(floor(log10(value)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { it * magnitude >= value }
    return step * magnitude
}

/** [PlotChart] with a health metric's color, bounds and labels. */
@Composable
fun TimeSeriesChart(
    points: List<HealthPoint>,
    metric: HealthMetric,
    axisStart: Instant,
    axisEnd: Instant,
    labels: List<AxisLabel>,
    bars: Boolean,
    selected: Int?,
    onSelect: (Int) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    formatter: MetricFormatter = metricFormatter(),
    days: Int = 0,
    goal: Double? = null,
) {
    val range =
        remember(points, metric, days, goal) {
            valueRange(metric, points.map { it.value } + listOfNotNull(goal), daily = days > 0)
        }
    PlotChart(
        points = points,
        color = metricColor(metric),
        range = range,
        gridLabel = { formatter.number(metric, it) },
        axisStart = axisStart,
        axisEnd = axisEnd,
        labels = labels,
        bars = bars,
        selected = selected,
        onSelect = onSelect,
        description = description,
        modifier = modifier,
        days = days,
        goal = goal,
    )
}

/**
 * Line (samples) or bar (totals) chart on a fixed time axis. Tapping or dragging selects the
 * nearest point; the caller shows the selected value next to the chart. A line breaks where two
 * samples are more than [gapMillis] apart, and a lone sample shows as a dot. With a [goal], a
 * dashed line marks it and bars below it are lighter.
 *
 * Over [days] days, each day is a rounded bar of its own. Within a day, bars cover their whole
 * interval and meet edge to edge.
 */
@Composable
fun PlotChart(
    points: List<HealthPoint>,
    color: Color,
    range: ValueRange,
    gridLabel: (Double) -> String,
    axisStart: Instant,
    axisEnd: Instant,
    labels: List<AxisLabel>,
    bars: Boolean,
    selected: Int?,
    onSelect: (Int) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    days: Int = 0,
    gapMillis: Long = Long.MAX_VALUE,
    goal: Double? = null,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelStyle =
        MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val measurer = rememberTextMeasurer()
    val gridValues = listOf(range.min, (range.min + range.max) / 2, range.max)
    val gridLabels = gridValues.map(gridLabel)
    val span = (axisEnd.toEpochMilli() - axisStart.toEpochMilli()).coerceAtLeast(1)

    fun fractionOf(time: Instant) =
        ((time.toEpochMilli() - axisStart.toEpochMilli()).toFloat() / span).coerceIn(0f, 1f)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(200.dp)
            .semantics { contentDescription = description }
            .pointerInput(points) {
                fun select(x: Float) {
                    if (points.isEmpty()) return
                    val plotStart = PlotInsetStart.toPx()
                    val fraction = ((x - plotStart) / (size.width - plotStart)).coerceIn(0f, 1f)
                    onSelect(
                        points.indices.minBy { abs(fractionOf(points[it].plotTime) - fraction) }
                    )
                }
                detectTapGestures { select(it.x) }
            }
            .pointerInput(points) {
                detectHorizontalDragGestures { change, _ ->
                    if (points.isEmpty()) return@detectHorizontalDragGestures
                    val plotStart = PlotInsetStart.toPx()
                    val fraction =
                        ((change.position.x - plotStart) / (size.width - plotStart)).coerceIn(
                            0f,
                            1f,
                        )
                    onSelect(
                        points.indices.minBy { abs(fractionOf(points[it].plotTime) - fraction) }
                    )
                }
            }
    ) {
        val plotLeft = PlotInsetStart.toPx()
        val plotBottom = size.height - PlotInsetBottom.toPx()
        val plotTop = 8.dp.toPx()
        val plotHeight = plotBottom - plotTop
        val plotWidth = size.width - plotLeft

        fun x(time: Instant) = plotLeft + fractionOf(time) * plotWidth

        fun y(value: Double) = plotBottom - range.fraction(value) * plotHeight

        // The goal's value takes the place of a grid value too close to it.
        val goalY = goal?.let(::y)
        val labelRoom = 14.dp.toPx()
        gridValues.forEachIndexed { index, value ->
            val lineY = y(value)
            drawLine(grid, Offset(plotLeft, lineY), Offset(size.width, lineY), 1.dp.toPx())
            if (goalY == null || abs(goalY - lineY) >= labelRoom) {
                drawLabel(
                    measurer,
                    gridLabels[index],
                    labelStyle,
                    Offset(0f, lineY),
                    centerY = true,
                )
            }
        }
        labels.forEach { label ->
            drawLabel(
                measurer,
                label.text,
                labelStyle,
                Offset(x(label.time), plotBottom + 4.dp.toPx()),
                centerX = true,
            )
        }

        if (bars) {
            val slot = if (days > 0) plotWidth / days else plotWidth / 24f
            points.forEachIndexed { index, point ->
                val reached = goal == null || point.value >= goal
                val alpha =
                    when {
                        selected == null || selected == index -> if (reached) 1f else 0.55f
                        else -> if (reached) 0.45f else 0.25f
                    }
                // A value too small to see still shows, growing up from the axis, never below it.
                if (days > 0) {
                    val intervalWidth = point.endExclusive?.let { x(it) - x(point.time) }
                    val barWidth = ((intervalWidth ?: slot) * 0.6f).coerceAtLeast(1.dp.toPx())
                    val center =
                        if (point.endExclusive != null) x(point.plotTime)
                        else x(point.time) + slot / 2
                    val height =
                        (plotBottom - y(point.value)).coerceAtLeast(min(barWidth, 4.dp.toPx()))
                    drawRoundRect(
                        color.copy(alpha = alpha),
                        topLeft = Offset(center - barWidth / 2, plotBottom - height),
                        size = Size(barWidth, height),
                        cornerRadius = CornerRadius(barWidth.coerceAtMost(12.dp.toPx()) / 2),
                    )
                } else if (point.value > range.min) {
                    // Whole pixels, so two bars that meet leave no faint seam between them.
                    val from =
                        point.endExclusive?.let { x(point.time) } ?: (x(point.time) - slot / 2)
                    val to = point.endExclusive?.let(::x) ?: (x(point.time) + slot / 2)
                    val left = round(from)
                    val top = min(y(point.value), plotBottom - 2.dp.toPx())
                    drawRect(
                        color.copy(alpha = alpha),
                        topLeft = Offset(left, top),
                        size = Size(max(round(to) - left, 1f), plotBottom - top),
                    )
                }
            }
        } else {
            lineSegments(points, gapMillis).forEach { segment ->
                val line =
                    Path().apply {
                        segment.forEachIndexed { index, point ->
                            if (index == 0) moveTo(x(point.time), y(point.value))
                            else lineTo(x(point.time), y(point.value))
                        }
                    }
                if (segment.size == 1) {
                    val only = segment.single()
                    drawCircle(color, 3.dp.toPx(), Offset(x(only.time), y(only.value)))
                    return@forEach
                }
                val area =
                    Path().apply {
                        addPath(line)
                        lineTo(x(segment.last().time), plotBottom)
                        lineTo(x(segment.first().time), plotBottom)
                        close()
                    }
                drawPath(
                    area,
                    Brush.verticalGradient(
                        listOf(color.copy(alpha = 0.24f), color.copy(alpha = 0f)),
                        plotTop,
                        plotBottom,
                    ),
                )
                drawPath(
                    line,
                    color,
                    style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }

        if (goal != null && goalY != null) {
            val dash = 4.dp.toPx()
            drawLine(
                color,
                Offset(plotLeft, goalY),
                Offset(size.width, goalY),
                1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash * 1.5f, dash)),
            )
            drawLabel(
                measurer,
                gridLabel(goal),
                labelStyle.copy(color = color),
                Offset(0f, goalY),
                centerY = true,
            )
        }

        selected?.let(points::getOrNull)?.let { point ->
            val center =
                when {
                    point.endExclusive != null -> x(point.plotTime)
                    bars && days > 0 -> x(point.time) + plotWidth / days / 2
                    else -> x(point.time)
                }
            drawLine(
                color.copy(alpha = 0.5f),
                Offset(center, plotTop),
                Offset(center, plotBottom),
                1.dp.toPx(),
            )
            if (!bars) {
                drawCircle(Color.White, 6.dp.toPx(), Offset(center, y(point.value)))
                drawCircle(color, 4.dp.toPx(), Offset(center, y(point.value)))
            }
        }
    }
}

private fun lineSegments(points: List<HealthPoint>, gapMillis: Long): List<List<HealthPoint>> {
    val segments = mutableListOf<MutableList<HealthPoint>>()
    points.forEach { point ->
        val last = segments.lastOrNull()?.last()
        if (last == null || point.time.toEpochMilli() - last.time.toEpochMilli() > gapMillis) {
            segments += mutableListOf(point)
        } else {
            segments.last() += point
        }
    }
    return segments
}

private val PlotInsetStart = 40.dp
private val PlotInsetBottom = 22.dp

private fun DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    anchor: Offset,
    centerX: Boolean = false,
    centerY: Boolean = false,
) {
    val layout = measurer.measure(text, style)
    val left = if (centerX) anchor.x - layout.size.width / 2f else anchor.x
    val top = if (centerY) anchor.y - layout.size.height / 2f else anchor.y
    drawText(
        layout,
        topLeft =
            Offset(
                left.coerceIn(0f, max(0f, size.width - layout.size.width)),
                top.coerceAtLeast(0f),
            ),
    )
}

/** A compact preview for metric cards: no axes, no interaction. */
@Composable
fun MiniChart(
    day: HealthDay,
    metric: HealthMetric,
    until: Instant?,
    modifier: Modifier = Modifier,
) {
    if (metric == HealthMetric.SLEEP) {
        SleepStrips(day.sleeps, modifier.fillMaxWidth().height(12.dp))
        return
    }
    val points = day.points(metric)
    val color = metricColor(metric)
    val start =
        remember(day.date, until) {
            until?.minus(Duration.ofDays(1))
                ?: day.date.atStartOfDay(ZoneId.systemDefault()).toInstant()
        }
    val range =
        remember(points, metric) { valueRange(metric, points.map { it.value }, daily = false) }
    // Totals show by the hour, which a card this small can still tell apart.
    val hours =
        remember(points, day.date) {
            if (metric.dailySummary != DailySummary.TOTAL) emptyList()
            else {
                val zone = ZoneId.systemDefault()
                val dayStart = day.date.atStartOfDay(zone).toInstant()
                hourlyTotals(points, dayStart, day.date.plusDays(1).atStartOfDay(zone).toInstant())
            }
        }
    val hourly =
        remember(hours, metric) { valueRange(metric, hours.map { it.value }, daily = false) }
    Canvas(modifier.fillMaxWidth().height(40.dp).clearAndSetSemantics {}) {
        val span = 86_400_000f
        fun x(time: Instant) =
            ((time.toEpochMilli() - start.toEpochMilli()) / span).coerceIn(0f, 1f) * size.width
        fun y(value: Double) = size.height - range.fraction(value) * size.height
        if (metric.dailySummary == DailySummary.TOTAL) {
            val width = (size.width / 24f * 0.6f).coerceAtLeast(1.dp.toPx())
            hours.forEach { point ->
                val top = hourly.let { size.height - it.fraction(point.value) * size.height }
                drawRoundRect(
                    color,
                    Offset(x(point.plotTime) - width / 2, top),
                    Size(width, (size.height - top).coerceAtLeast(width)),
                    CornerRadius(width / 2),
                )
            }
        } else if (points.isNotEmpty()) {
            val path =
                Path().apply {
                    points.forEachIndexed { index, point ->
                        if (index == 0) moveTo(x(point.time), y(point.value))
                        else lineTo(x(point.time), y(point.value))
                    }
                }
            drawPath(
                path,
                color,
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

/**
 * Each sleep as its own strip of stages, in time order and as wide as it is long; the space between
 * them is not time. A stretch the watch did not classify shows as the bare track.
 */
@Composable
fun SleepStrips(sleeps: List<SleepPeriod>, modifier: Modifier = Modifier) {
    if (sleeps.isEmpty()) return
    val colors = SleepStage.entries.associateWith { stageColor(it) }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val ordered = remember(sleeps) { sleeps.sortedBy { it.start } }
    Canvas(modifier.clearAndSetSemantics {}) {
        val gap = 4.dp.toPx()
        val widths =
            stripWidths(
                ordered.map { it.end.toEpochMilli() - it.start.toEpochMilli() },
                size.width - gap * ordered.lastIndex,
                minimum = size.height * 2,
            )
        var left = 0f
        ordered.forEachIndexed { index, sleep ->
            val width = widths[index]
            val start = sleep.start.toEpochMilli()
            val span = (sleep.end.toEpochMilli() - start).toFloat().coerceAtLeast(1f)
            fun x(time: Instant) = floor(left + (time.toEpochMilli() - start) / span * width)
            val outline =
                Path().apply {
                    addRoundRect(
                        RoundRect(
                            left,
                            0f,
                            left + width,
                            size.height,
                            CornerRadius(size.height / 2),
                        )
                    )
                }
            clipPath(outline) {
                drawRect(track, Offset(left, 0f), Size(width, size.height))
                sleep.segments.forEach { segment ->
                    val from = x(segment.start)
                    drawRect(
                        colors.getValue(segment.stage),
                        Offset(from, 0f),
                        Size(max(x(segment.end) - from, 1f), size.height),
                    )
                }
            }
            left += width + gap
        }
    }
}

/**
 * Widths that share [total] in proportion to [spans], except that none goes under [minimum] while
 * there is room for it.
 */
internal fun stripWidths(spans: List<Long>, total: Float, minimum: Float): List<Float> {
    val least = min(minimum, total / spans.size)
    val sum = spans.sum().coerceAtLeast(1).toFloat()
    val short = spans.map { total * it / sum < least }
    val rest = spans.filterIndexed { index, _ -> !short[index] }.sum().coerceAtLeast(1).toFloat()
    val left = total - least * short.count { it }
    return spans.mapIndexed { index, span -> if (short[index]) least else left * span / rest }
}

/** Hypnogram of one sleep: one row per stage, its time across. */
@Composable
fun SleepTimeline(
    sleep: SleepPeriod,
    labels: List<AxisLabel>,
    description: String,
    modifier: Modifier = Modifier,
) {
    val colors = SleepStage.entries.associateWith { stageColor(it) }
    val stageNames = SleepStage.entries.map { stringResource(it.title) }
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelStyle =
        MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(180.dp).semantics { contentDescription = description }) {
        val start = sleep.start.toEpochMilli()
        val span = (sleep.end.toEpochMilli() - start).toFloat().coerceAtLeast(1f)
        val plotLeft = 56.dp.toPx()
        val plotBottom = size.height - PlotInsetBottom.toPx()
        val rowHeight = plotBottom / SleepStage.entries.size
        val plotWidth = size.width - plotLeft
        SleepStage.entries.forEachIndexed { row, stage ->
            val centerY = rowHeight * row + rowHeight / 2
            drawLine(grid, Offset(plotLeft, centerY), Offset(size.width, centerY), 1.dp.toPx())
            drawLabel(
                measurer,
                stageNames[stage.ordinal],
                labelStyle,
                Offset(0f, centerY),
                centerY = true,
            )
        }
        sleep.segments.forEach { segment ->
            val left = plotLeft + (segment.start.toEpochMilli() - start) / span * plotWidth
            val right = plotLeft + (segment.end.toEpochMilli() - start) / span * plotWidth
            val row = segment.stage.ordinal
            drawRoundRect(
                colors.getValue(segment.stage),
                topLeft = Offset(left, rowHeight * row + rowHeight * 0.2f),
                size = Size(max(right - left, 1.dp.toPx()), rowHeight * 0.6f),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
        }
        labels.forEach { label ->
            val x =
                plotLeft + ((label.time.toEpochMilli() - start) / span).coerceIn(0f, 1f) * plotWidth
            drawLabel(
                measurer,
                label.text,
                labelStyle,
                Offset(x, plotBottom + 4.dp.toPx()),
                centerX = true,
            )
        }
    }
}

/** Color key with total time per stage. */
@Composable
fun SleepLegend(
    totals: Map<SleepStage, Long>,
    formatter: MetricFormatter,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val all = totals.values.sum().coerceAtLeast(1)
        SleepStage.entries.forEach { stage ->
            val millis = totals[stage] ?: 0
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(Modifier.size(12.dp).background(stageColor(stage), CircleShape))
                Text(
                    stringResource(stage.title),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(
                        R.string.sleep_stage_share,
                        formatter.durationMillis(millis),
                        (millis * 100 / all).toInt(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
