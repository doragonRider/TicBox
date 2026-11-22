package dev.ticbox.companion.ui.health

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.currentLocale
import dev.ticbox.companion.ui.common.formatDecimal as decimal
import dev.ticbox.companion.ui.common.formatNumber
import dev.ticbox.companion.ui.designsystem.LocalHealthColors
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** One precision and unit policy for summaries, charts, tables and accessibility text. */
class MetricFormatter(private val resources: Resources, private val locale: Locale) {
    fun value(metric: HealthMetric, value: Double): String =
        when (metric) {
            HealthMetric.ACTIVITY -> {
                val steps = value.roundToInt()
                resources.getQuantityString(
                    R.plurals.value_steps,
                    steps,
                    formatNumber(steps, locale),
                )
            }
            HealthMetric.HEART ->
                resources.getString(R.string.value_bpm, formatNumber(value.roundToInt(), locale))
            HealthMetric.OXYGEN ->
                resources.getString(
                    R.string.value_percent,
                    formatNumber(value.roundToInt(), locale),
                )
            HealthMetric.SLEEP -> duration((value * 60).roundToLong())
            HealthMetric.CALORIES -> calories(value)
            HealthMetric.DISTANCE -> distance(value)
            HealthMetric.EXERCISE -> duration((value / 60).roundToLong())
            HealthMetric.CLIMBING ->
                resources.getString(R.string.value_meters, decimal(value, locale))
            HealthMetric.STRESS -> formatNumber(value.roundToInt(), locale)
            HealthMetric.RESPIRATION ->
                resources.getString(R.string.value_breaths, decimal(value, locale))
            HealthMetric.TEMPERATURE,
            HealthMetric.SKIN_TEMPERATURE ->
                resources.getString(R.string.value_celsius, decimal(value, locale))
            HealthMetric.VO2MAX ->
                resources.getString(R.string.value_vo2max, decimal(value, locale))
            HealthMetric.NOISE ->
                resources.getString(
                    R.string.value_decibels,
                    formatNumber(value.roundToInt(), locale),
                )
        }

    /** A bare number for axes and tables whose header already names the unit. */
    fun number(metric: HealthMetric, value: Double): String =
        when (metric) {
            HealthMetric.SLEEP -> duration((value * 60).roundToLong())
            HealthMetric.DISTANCE -> distance(value)
            HealthMetric.EXERCISE -> formatNumber((value / 60).roundToInt(), locale)
            HealthMetric.CLIMBING,
            HealthMetric.RESPIRATION,
            HealthMetric.TEMPERATURE,
            HealthMetric.SKIN_TEMPERATURE,
            HealthMetric.VO2MAX -> decimal(value, locale)
            else -> formatNumber(value.roundToInt(), locale)
        }

    fun calories(kcal: Double): String =
        resources.getString(R.string.value_kcal, formatNumber(kcal.roundToInt(), locale))

    /** Meters below 1 km, kilometers from there on. */
    fun distance(meters: Double): String =
        if (meters < 1000) {
            resources.getString(R.string.value_meters, formatNumber(meters.roundToInt(), locale))
        } else {
            resources.getString(R.string.value_kilometers, decimal(meters / 1000, locale, 2))
        }

    fun duration(minutes: Long): String =
        if (minutes < 60) resources.getString(R.string.duration_minutes, minutes.toInt())
        else
            resources.getString(
                R.string.duration_hours_minutes,
                (minutes / 60).toInt(),
                (minutes % 60).toInt(),
            )

    fun durationMillis(millis: Long): String =
        if (millis in 1 until 60_000) resources.getString(R.string.duration_under_minute)
        else duration(millis / 60_000)
}

@Composable
@ReadOnlyComposable
fun metricFormatter() = MetricFormatter(LocalResources.current, currentLocale())

@Composable
@ReadOnlyComposable
fun metricColor(metric: HealthMetric): Color {
    val colors = LocalHealthColors.current
    return when (metric) {
        HealthMetric.ACTIVITY,
        HealthMetric.CALORIES,
        HealthMetric.DISTANCE,
        HealthMetric.EXERCISE,
        HealthMetric.CLIMBING -> colors.activity
        HealthMetric.HEART -> colors.heart
        HealthMetric.SLEEP,
        HealthMetric.STRESS -> colors.sleep
        HealthMetric.OXYGEN,
        HealthMetric.RESPIRATION,
        HealthMetric.VO2MAX,
        HealthMetric.NOISE -> colors.oxygen
        HealthMetric.TEMPERATURE,
        HealthMetric.SKIN_TEMPERATURE -> colors.awake
    }
}

@Composable
@ReadOnlyComposable
fun stageColor(stage: SleepStage): Color {
    val colors = LocalHealthColors.current
    return when (stage) {
        SleepStage.AWAKE -> colors.awake
        SleepStage.REM -> colors.rem
        SleepStage.LIGHT -> colors.lightSleep
        SleepStage.DEEP -> colors.deepSleep
    }
}
