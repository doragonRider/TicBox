package dev.ticbox.companion.ui.health

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.currentLocale
import dev.ticbox.companion.ui.common.formatNumber
import dev.ticbox.companion.ui.designsystem.AppIcon
import dev.ticbox.companion.ui.designsystem.AppIcons
import kotlin.math.max

/** The step goal in force: the one chosen in this app, otherwise the watch's own. */
@Composable
fun stepGoal(viewModel: HealthViewModel): Int? {
    val chosen by viewModel.chosenStepGoal.collectAsStateWithLifecycle()
    val watch by viewModel.session.collectAsStateWithLifecycle()
    val watchGoal = watch?.client?.state?.collectAsStateWithLifecycle()?.value?.steps?.value?.goal
    return (chosen ?: watchGoal)?.takeIf { it > 0 }
}

/** How far a day's steps are from [goal], or that it was reached. */
@Composable
fun StepsGoalStatus(steps: Int, goal: Int, style: TextStyle, modifier: Modifier = Modifier) {
    val remaining = goal - steps
    if (remaining > 0) {
        Text(
            pluralStringResource(
                R.plurals.steps_to_go,
                remaining,
                formatNumber(remaining, currentLocale()),
            ),
            modifier,
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val color = metricColor(HealthMetric.ACTIVITY)
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AppIcon(AppIcons.CheckCircle, contentDescription = null, Modifier.size(16.dp), tint = color)
        Text(stringResource(R.string.steps_goal_reached), style = style, color = color)
    }
}

/** A rounded bar filled up to [progress], which is capped at a full bar. */
@Composable
fun GoalBar(progress: Float, color: Color, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier.fillMaxWidth().height(10.dp).clearAndSetSemantics {}) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = radius)
        val filled = size.width * progress.coerceIn(0f, 1f)
        if (filled > 0f) {
            drawRoundRect(
                color,
                size = Size(max(filled, size.height), size.height),
                cornerRadius = radius,
            )
        }
    }
}

/** A ring filled clockwise from the top up to [progress], drawn in once when it first shows. */
@Composable
fun GoalRing(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    stroke: Dp = 14.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val target = progress.coerceIn(0f, 1f)
    val shown = remember { Animatable(0f) }
    LaunchedEffect(target) { shown.animateTo(target, tween(durationMillis = 700)) }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize().clearAndSetSemantics {}) {
            val width = stroke.toPx()
            val topLeft = Offset(width / 2, width / 2)
            val arc = Size(size.width - width, size.height - width)
            drawArc(track, 0f, 360f, false, topLeft, arc, style = Stroke(width))
            val sweep = 360f * shown.value
            if (sweep > 0f) {
                drawArc(
                    color,
                    -90f,
                    sweep,
                    false,
                    topLeft,
                    arc,
                    style = Stroke(width, cap = StrokeCap.Round),
                )
            }
        }
        content()
    }
}

/** The day's steps in a ring toward the goal, with the goal and what's left below it. */
@Composable
fun StepsHero(steps: Int, goal: Int, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        GoalRing(
            steps / goal.toFloat(),
            metricColor(HealthMetric.ACTIVITY),
            Modifier.size(184.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    formatNumber(steps, locale),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    pluralStringResource(R.plurals.steps_unit, steps),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(
                    R.string.steps_goal_of,
                    pluralStringResource(R.plurals.value_steps, goal, formatNumber(goal, locale)),
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            StepsGoalStatus(steps, goal, MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The day's distance, calories and exercise time, whichever were recorded. */
@Composable
fun ActivityStats(day: HealthDay, modifier: Modifier = Modifier) {
    val formatter = metricFormatter()
    val shown =
        listOf(HealthMetric.DISTANCE, HealthMetric.CALORIES, HealthMetric.EXERCISE)
            .filter(day::hasData)
    if (shown.isEmpty()) return
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        shown.forEach { metric ->
            StatCard(
                stringResource(metric.title),
                formatter.value(metric, day.summary(metric)),
                Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}
