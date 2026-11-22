package dev.ticbox.companion.ui.health

import androidx.annotation.DrawableRes
import androidx.annotation.Keep
import androidx.annotation.StringRes
import dev.ticbox.companion.api.HealthPointRecord
import dev.ticbox.companion.api.HealthPointType
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.designsystem.AppIcons
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt

/** How one day of a metric reads as a single number. */
enum class DailySummary {
    /** Interval increments; the day is their sum. */
    TOTAL,
    AVERAGE,
    LATEST,
}

/** Which backend field a metric charts. Rows that do not confirm it are never plotted. */
data class MetricField(val type: HealthPointType, val semantic: String, val unit: String?)

/** Also a navigation argument; kept so its serializer survives minification. */
@Keep
enum class HealthMetric(
    @param:StringRes val title: Int,
    @param:DrawableRes val icon: Int,
    val dailySummary: DailySummary,
    val field: MetricField?,
) {
    ACTIVITY(
        R.string.metric_steps,
        AppIcons.Steps,
        DailySummary.TOTAL,
        MetricField(HealthPointType.DELTA_STEP, "delta_step", "steps"),
    ),
    HEART(
        R.string.metric_heart_rate,
        AppIcons.Heart,
        DailySummary.AVERAGE,
        MetricField(HealthPointType.HEART_RATE, "heart_rate", "bpm"),
    ),
    SLEEP(R.string.metric_sleep, AppIcons.Sleep, DailySummary.TOTAL, null),
    OXYGEN(
        R.string.metric_blood_oxygen,
        AppIcons.Oxygen,
        DailySummary.AVERAGE,
        MetricField(HealthPointType.BLOOD_OXYGEN, "blood_oxygen", "%"),
    ),
    CALORIES(
        R.string.metric_calories,
        AppIcons.Calories,
        DailySummary.TOTAL,
        MetricField(HealthPointType.DELTA_CALORIE, "delta_calorie", "kcal"),
    ),
    DISTANCE(
        R.string.metric_distance,
        AppIcons.Distance,
        DailySummary.TOTAL,
        MetricField(HealthPointType.DELTA_DISTANCE, "delta_distance", "m"),
    ),
    EXERCISE(
        R.string.metric_exercise,
        AppIcons.Timer,
        DailySummary.TOTAL,
        MetricField(HealthPointType.DELTA_EXERCISE, "delta_exercise", "s"),
    ),
    CLIMBING(
        R.string.metric_climbing,
        AppIcons.Stairs,
        DailySummary.TOTAL,
        MetricField(HealthPointType.CLIMBING_STAIRS, "climbing_stairs", "m"),
    ),
    STRESS(
        R.string.metric_stress,
        AppIcons.Stress,
        DailySummary.AVERAGE,
        MetricField(HealthPointType.PRESSURE, "pressure", null),
    ),
    RESPIRATION(
        R.string.metric_respiration,
        AppIcons.Breath,
        DailySummary.AVERAGE,
        MetricField(HealthPointType.RESPIRATION_RATE, "respiration_rate", "breaths/min"),
    ),
    TEMPERATURE(
        R.string.metric_temperature,
        AppIcons.Temperature,
        DailySummary.LATEST,
        MetricField(HealthPointType.TEMPERATURE, "temperature", "°C"),
    ),
    SKIN_TEMPERATURE(
        R.string.metric_skin_temperature,
        AppIcons.SkinTemperature,
        DailySummary.AVERAGE,
        MetricField(HealthPointType.SKIN_TEMPERATURE, "skin_temperature", "°C"),
    ),
    VO2MAX(
        R.string.metric_vo2max,
        AppIcons.Speed,
        DailySummary.LATEST,
        MetricField(HealthPointType.VO2MAX, "vo2max", "ml/kg/min"),
    ),
    NOISE(
        R.string.metric_noise,
        AppIcons.Hearing,
        DailySummary.AVERAGE,
        MetricField(HealthPointType.NOISE_DECIBEL, "noise_decibel", "dB"),
    );

    companion object {
        /** The cards at the top of the health tab. */
        val primary = listOf(ACTIVITY, HEART, SLEEP, OXYGEN)
        val activity = listOf(CALORIES, DISTANCE, EXERCISE, CLIMBING)
        val body = listOf(STRESS, RESPIRATION, TEMPERATURE, SKIN_TEMPERATURE, VO2MAX, NOISE)

        /** Read as the past 24 hours today, since a calendar day cuts them off at midnight. */
        val pastDay = setOf(HEART, OXYGEN)
    }
}

enum class HealthPeriod(@param:StringRes val title: Int) {
    DAY(R.string.period_day),
    WEEK(R.string.period_week),
    MONTH(R.string.period_month);

    /** The days shown for [date], ending on it: the day itself, 7 days, or back to a month ago. */
    fun days(date: LocalDate): List<LocalDate> {
        val first =
            when (this) {
                DAY -> date
                WEEK -> date.minusDays(6)
                MONTH -> date.minusMonths(1).plusDays(1)
            }
        return generateSequence(first) { it.plusDays(1) }.takeWhile { it <= date }.toList()
    }
}

/** A sample, or a projected half-open aggregate whose time is the bucket start. */
data class HealthPoint(val time: Instant, val value: Double, val endExclusive: Instant? = null) {
    val plotTime: Instant
        get() = endExclusive?.let { time.plus(Duration.between(time, it).dividedBy(2)) } ?: time
}

enum class SleepStage(@param:StringRes val title: Int) {
    AWAKE(R.string.sleep_stage_awake),
    REM(R.string.sleep_stage_rem),
    LIGHT(R.string.sleep_stage_light),
    DEEP(R.string.sleep_stage_deep),
}

data class SleepSegment(val stage: SleepStage, val start: Instant, val end: Instant) {
    val durationMillis: Long
        get() = Duration.between(start, end).toMillis()
}

/**
 * One time asleep: a watch session, or several close enough to be one. [start] and [end] are the
 * sessions' own, so a stretch the watch did not classify stays a gap between [segments].
 */
data class SleepPeriod(val start: Instant, val end: Instant, val segments: List<SleepSegment>) {
    val asleepMillis: Long
        get() = segments.filter { it.stage != SleepStage.AWAKE }.sumOf { it.durationMillis }

    fun stageMillis(stage: SleepStage): Long =
        segments.filter { it.stage == stage }.sumOf { it.durationMillis }
}

/** One calendar day of charted data, already restricted to confirmed fields and units. */
data class HealthDay(
    val date: LocalDate,
    val series: Map<HealthMetric, List<HealthPoint>> = emptyMap(),
    /** Sleeps that end on this day: the main sleep, then naps by time. */
    val sleeps: List<SleepPeriod> = emptyList(),
    /** Step rows as stored, including ones that could not be charted. */
    val activityRecords: List<HealthPointRecord> = emptyList(),
) {
    val steps: List<HealthPoint>
        get() = points(HealthMetric.ACTIVITY)

    val heart: List<HealthPoint>
        get() = points(HealthMetric.HEART)

    val oxygen: List<HealthPoint>
        get() = points(HealthMetric.OXYGEN)

    val stepTotal: Int
        get() = steps.sumOf { it.value }.roundToInt()

    /** The day's longest sleep. */
    val mainSleep: SleepPeriod?
        get() = sleeps.firstOrNull()

    val naps: List<SleepPeriod>
        get() = sleeps.drop(1)

    /** Naps included, each to the minute as its own page shows it. */
    val asleepMinutes: Long
        get() = sleeps.sumOf { it.asleepMillis / 60_000 }

    fun points(metric: HealthMetric): List<HealthPoint> = series[metric].orEmpty()

    fun hasData(metric: HealthMetric): Boolean =
        if (metric == HealthMetric.SLEEP) sleeps.isNotEmpty() else points(metric).isNotEmpty()

    /** The day's total, average or latest value; hours asleep, naps included, for sleep. */
    fun summary(metric: HealthMetric): Double {
        if (metric == HealthMetric.SLEEP) return asleepMinutes / 60.0
        val values = points(metric).map { it.value }
        return when (metric.dailySummary) {
            DailySummary.TOTAL -> values.sum()
            DailySummary.AVERAGE -> values.average()
            DailySummary.LATEST -> values.lastOrNull() ?: Double.NaN
        }
    }
}

enum class HealthReadState {
    /** Everything read and charted. */
    AVAILABLE,

    /** Read successfully; nothing recorded. */
    EMPTY,

    /** Some rows could not be charted (unknown units, gaps, read limits). */
    PARTIAL,

    /** The phone has no readable copy of this data yet. */
    UNAVAILABLE,

    /** Reading failed; previous results, if any, are still shown. */
    FAILED,
}

/** [HealthMetric.pastDay] over the 24 hours before [until]. */
data class RecentSamples(
    val until: Instant,
    val series: Map<HealthMetric, List<HealthPoint>>,
    val states: Map<HealthMetric, HealthReadState>,
)

data class HealthDayResult(
    val day: HealthDay,
    val states: Map<HealthMetric, HealthReadState>,
    /** Only ever on today's result, whose [day] stays a calendar day for the week's totals. */
    val recent: RecentSamples? = null,
) {
    fun state(metric: HealthMetric): HealthReadState = states[metric] ?: HealthReadState.EMPTY

    /** Where [metric]'s 24 hours end, if it is showing them rather than a calendar day. */
    fun until(metric: HealthMetric): Instant? =
        recent?.until?.takeIf { metric in HealthMetric.pastDay }

    /** This result with [recent] in place of the calendar day's samples. */
    fun rolled(): HealthDayResult {
        val recent = recent ?: return this
        return copy(
            day = day.copy(series = day.series + recent.series),
            states = states + recent.states,
        )
    }
}
