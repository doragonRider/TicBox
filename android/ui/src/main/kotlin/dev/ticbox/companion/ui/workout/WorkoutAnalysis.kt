package dev.ticbox.companion.ui.workout

import dev.ticbox.companion.api.HealthFitnessExtraKind
import dev.ticbox.companion.api.HealthFitnessGroupState
import dev.ticbox.companion.api.HealthFitnessPoint
import dev.ticbox.companion.api.HealthFitnessRecord
import dev.ticbox.companion.api.HealthFitnessStroke
import kotlin.math.ceil

/** A value on the workout's effective-time axis. */
data class ElapsedValue(val elapsedMillis: Long, val value: Double)

data class RoutePoint(val latitude: Double, val longitude: Double)

/** A stretch of about one kilometer, ending at the first sample past the boundary. */
data class WorkoutSplit(
    val index: Int,
    val meters: Long,
    val millis: Long,
    val secondsPerKm: Double,
    /** False for the shorter last stretch. */
    val full: Boolean,
)

data class WorkoutLap(
    val number: Long,
    val meters: Long?,
    val lapMillis: Long?,
    val cumulativeMillis: Long,
    val secondsPerKm: Double?,
)

enum class HeartZone {
    WARM_UP,
    FAT_BURN,
    AEROBIC,
    ANAEROBIC,
    DANGER,
}

data class HeartZones(
    val maximum: Int,
    /** The wearer's age was unknown, so the formula used a default. */
    val assumedAge: Boolean,
    val thresholds: Map<HeartZone, Int>,
    val millis: Map<HeartZone, Long>,
)

data class SwimTrip(
    val number: Long,
    val cumulativeMeters: Long,
    val swimSeconds: Double?,
    val strokes: Long?,
    val swolf: Long?,
    val stroke: HealthFitnessStroke?,
)

data class StrokeDistance(val stroke: HealthFitnessStroke?, val meters: Double)

data class HiitAction(val typeCode: Long, val millis: Long, val times: Long?)

data class TrainingGroup(val state: HealthFitnessGroupState, val number: Int, val millis: Long?)

/** Series and tables derived from one workout. Nothing here is written back or stored. */
data class WorkoutAnalysis(
    val heart: List<ElapsedValue> = emptyList(),
    val maxHeartRate: Long? = null,
    val speed: List<ElapsedValue> = emptyList(),
    val elevation: List<ElapsedValue> = emptyList(),
    val cadence: List<ElapsedValue> = emptyList(),
    val cadenceDerived: Boolean = false,
    val strideMeters: List<ElapsedValue> = emptyList(),
    val route: List<List<RoutePoint>> = emptyList(),
    val splits: List<WorkoutSplit> = emptyList(),
    val laps: List<WorkoutLap> = emptyList(),
    val zones: HeartZones? = null,
    val swimTrips: List<SwimTrip> = emptyList(),
    val strokes: List<StrokeDistance> = emptyList(),
    val secondsPer100Meters: Double? = null,
    val hiitActions: List<HiitAction> = emptyList(),
    val hiitGroups: Int? = null,
    val groups: List<TrainingGroup> = emptyList(),
)

private const val MaxStrideMeters = 3.0
private const val RouteGapMillis = 10_000L
private const val DefaultAge = 25
private const val TreadmillRunning = 132L
private const val GroupRest = 36L
private const val MetersPerKm = 1000L
private val StrokeOrder = listOf(1L, 2L, 3L, 4L, 0L)

private fun HealthFitnessPoint.elapsed() = elapsedMillis?.takeIf { it >= 0 }

private fun HealthFitnessPoint.distance() = distanceMeters?.takeIf { it >= 0 }

private fun HealthFitnessPoint.stepCount() = steps?.takeIf { it >= 0 }

private fun HealthFitnessPoint.speedMps() =
    speedMetersPerSecondDecimal?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }

/**
 * Derives what the workout pages show, following the formulas in docs/spec/health-display.md.
 * Distance, steps and calories on samples are cumulative, so they are differenced, never summed.
 */
fun analyzeWorkout(
    record: HealthFitnessRecord,
    points: List<HealthFitnessPoint>,
    age: Int?,
): WorkoutAnalysis {
    val samples = points.filter { it.elapsed() != null }.sortedBy { it.elapsedMillis }
    val heart = samples.mapNotNull { sample ->
        val time = sample.elapsed()?.takeIf { it > 0 } ?: return@mapNotNull null
        val bpm = sample.heartRateBpm?.takeIf { it > 0 } ?: return@mapNotNull null
        ElapsedValue(time, bpm.toDouble())
    }
    val (cadence, cadenceDerived) = cadence(record, samples)
    val swim = swimming(record, samples)
    return WorkoutAnalysis(
        heart = heart,
        maxHeartRate = heart.maxOfOrNull { it.value.toLong() },
        speed =
            samples.mapNotNull { s -> s.speedMps()?.let { ElapsedValue(s.elapsedOrZero(), it) } },
        elevation =
            samples.mapNotNull { s ->
                if ("ELEVATION" in s.sentinelColumns) return@mapNotNull null
                s.elevationMetersDecimal
                    ?.toDoubleOrNull()
                    ?.takeIf { it.isFinite() }
                    ?.let {
                        ElapsedValue(s.elapsedOrZero(), it)
                    }
            },
        cadence = cadence,
        cadenceDerived = cadenceDerived,
        strideMeters = strides(samples),
        route = route(samples),
        splits = splits(samples),
        laps = laps(points),
        zones = heartZones(heart, age),
        swimTrips = swim.trips,
        strokes = swim.strokes,
        secondsPer100Meters = swim.secondsPer100Meters,
        hiitActions = hiitActions(record),
        hiitGroups = hiitGroups(record),
        groups = groups(record),
    )
}

private fun HealthFitnessPoint.elapsedOrZero() = elapsed() ?: 0

/** Measured stride frequency first; otherwise steps per minute between samples. */
private fun cadence(
    record: HealthFitnessRecord,
    samples: List<HealthFitnessPoint>,
): Pair<List<ElapsedValue>, Boolean> {
    if (record.typeCode != TreadmillRunning) {
        val measured = samples.mapNotNull { s ->
            s.strideFrequencyStepsPerMinute
                ?.takeIf { it > 0 }
                ?.let {
                    ElapsedValue(s.elapsedOrZero(), it.toDouble())
                }
        }
        if (measured.isNotEmpty()) return measured to false
    }
    val derived =
        stepWindows(samples).mapNotNull { (previous, sample) ->
            val minutes = (sample.elapsedOrZero() - previous.elapsedOrZero()) / 60_000.0
            val steps = checkNotNull(sample.stepCount()) - checkNotNull(previous.stepCount())
            if (minutes <= 0 || steps < 0) null
            else ElapsedValue(sample.elapsedOrZero(), steps / minutes)
        }
    return derived to true
}

private fun stepWindows(samples: List<HealthFitnessPoint>) =
    samples.filter { it.stepCount() != null }.zipWithNext()

private fun strides(samples: List<HealthFitnessPoint>): List<ElapsedValue> =
    samples
        .filter { it.stepCount() != null && it.distance() != null }
        .zipWithNext()
        .mapNotNull { (previous, sample) ->
            val steps = checkNotNull(sample.stepCount()) - checkNotNull(previous.stepCount())
            val meters = checkNotNull(sample.distance()) - checkNotNull(previous.distance())
            if (steps <= 0 || meters < 0) return@mapNotNull null
            val stride = meters.toDouble() / steps
            if (stride >= MaxStrideMeters) null else ElapsedValue(sample.elapsedOrZero(), stride)
        }

/** Connects locations only across short gaps and never across a pause. */
private fun route(samples: List<HealthFitnessPoint>): List<List<RoutePoint>> {
    val segments = mutableListOf<MutableList<RoutePoint>>()
    var pendingResume = false
    var previousTime: Long? = null
    var count = 0
    samples.forEach { sample ->
        if (sample.resumed == true) pendingResume = true
        val point = sample.location() ?: return@forEach
        val time = sample.elapsedOrZero()
        val previous = previousTime
        if (previous == null || pendingResume || time - previous >= RouteGapMillis) {
            segments += mutableListOf(point)
        } else {
            segments.last() += point
        }
        pendingResume = false
        previousTime = time
        count++
    }
    return if (count >= 2) segments else emptyList()
}

private fun HealthFitnessPoint.location(): RoutePoint? {
    val auxiliary = locationAuxiliaryDecimal?.toDoubleOrNull()?.takeIf { it >= 0 } ?: return null
    if (!auxiliary.isFinite()) return null
    val latitude = latitudeDecimal?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    val longitude = longitudeDecimal?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    // Exactly 0, 0 is how a sample without a fix reads. The row itself stays usable elsewhere.
    if (latitude == 0.0 && longitude == 0.0) return null
    return RoutePoint(latitude, longitude)
}

private fun splits(samples: List<HealthFitnessPoint>): List<WorkoutSplit> {
    val rows = mutableListOf<WorkoutSplit>()
    var boundary = MetersPerKm
    var fromMeters = 0L
    var fromMillis = 0L
    fun add(meters: Long, millis: Long, full: Boolean) {
        val span = meters - fromMeters
        rows +=
            WorkoutSplit(
                rows.size + 1,
                span,
                millis - fromMillis,
                (millis - fromMillis).toDouble() / span,
                full,
            )
        fromMeters = meters
        fromMillis = millis
    }
    val located = samples.filter { it.distance() != null }
    located.forEach { sample ->
        val meters = checkNotNull(sample.distance())
        if (meters >= boundary) {
            add(meters, sample.elapsedOrZero(), full = true)
            boundary = (meters / MetersPerKm + 1) * MetersPerKm
        }
    }
    val last = located.lastOrNull()
    if (last != null && checkNotNull(last.distance()) > fromMeters) {
        add(checkNotNull(last.distance()), last.elapsedOrZero(), full = false)
    }
    return rows
}

private fun laps(points: List<HealthFitnessPoint>): List<WorkoutLap> {
    val rows = mutableListOf<WorkoutLap>()
    var cumulative = 0L
    var lastNumber = 0L
    points
        .filter { it.lap != null }
        .sortedBy { it.elapsedMillis ?: Long.MAX_VALUE }
        .forEach { point ->
            val lap = checkNotNull(point.lap)
            val number = lap.number?.takeIf { it > lastNumber } ?: return@forEach
            val millis = lap.durationMillis?.takeIf { it >= 0 }
            val meters = lap.distanceMeters?.takeIf { it >= 0 }
            cumulative += millis ?: 0
            lastNumber = number
            val pace =
                if (millis != null && meters != null && meters > 0) millis.toDouble() / meters
                else null
            rows += WorkoutLap(number, meters, millis, cumulative, pace)
        }
    return rows
}

/** Time between two samples counts for the later one. Below the first zone is not counted. */
private fun heartZones(heart: List<ElapsedValue>, age: Int?): HeartZones? {
    if (heart.size < 2) return null
    val usable = age?.takeIf { it in 1 until 220 }
    val maximum = 220 - (usable ?: DefaultAge)
    val thresholds =
        mapOf(
            HeartZone.WARM_UP to ceil(maximum * 0.5).toInt(),
            HeartZone.FAT_BURN to ceil(maximum * 0.6).toInt(),
            HeartZone.AEROBIC to ceil(maximum * 0.7).toInt(),
            HeartZone.ANAEROBIC to ceil(maximum * 0.9).toInt(),
            HeartZone.DANGER to maximum,
        )
    val millis = HeartZone.entries.associateWith { 0L }.toMutableMap()
    heart.zipWithNext().forEach { (previous, sample) ->
        val zone = HeartZone.entries.lastOrNull { sample.value >= thresholds.getValue(it) }
        if (zone != null) {
            millis[zone] = millis.getValue(zone) + (sample.elapsedMillis - previous.elapsedMillis)
        }
    }
    return HeartZones(maximum, usable == null, thresholds, millis)
}

private class Swimming(
    val trips: List<SwimTrip> = emptyList(),
    val strokes: List<StrokeDistance> = emptyList(),
    val secondsPer100Meters: Double? = null,
)

private fun swimming(record: HealthFitnessRecord, samples: List<HealthFitnessPoint>): Swimming {
    val pool = record.swimming?.poolLengthMeters?.takeIf { it > 0 } ?: return Swimming()
    val trips = mutableListOf<SwimTrip>()
    val byStroke = linkedMapOf<Long, Double>()
    var lastTrips = 0.0
    var lastStrokes = 0L
    samples.forEach { sample ->
        val swim = sample.swimming ?: return@forEach
        val count = swim.tripsDecimal?.toDoubleOrNull()?.takeIf { it > lastTrips } ?: return@forEach
        val seconds = sample.speedMps()?.takeIf { it > 0 }?.let { pool / it }
        val strokes = swim.strokeCount?.let { it - lastStrokes }?.takeIf { it >= 0 }
        val number = ceil(count).toLong()
        trips +=
            SwimTrip(
                number = number,
                cumulativeMeters = number * pool,
                swimSeconds = seconds,
                strokes = strokes,
                swolf =
                    if (seconds != null && strokes != null) seconds.toLong() + strokes else null,
                stroke = swim.stroke,
            )
        val code = swim.stroke?.code ?: 0L
        byStroke[code] = (byStroke[code] ?: 0.0) + (count - lastTrips) * pool
        lastTrips = count
        swim.strokeCount?.let { lastStrokes = it }
    }
    val total = trips.lastOrNull()?.cumulativeMeters
    val timed = trips.mapNotNull { it.swimSeconds }
    return Swimming(
        trips,
        StrokeOrder.mapNotNull { code ->
            byStroke[code]?.let { StrokeDistance(HealthFitnessStroke.fromCode(code), it) }
        },
        if (total != null && total > 0 && timed.size == trips.size) timed.sum() * 100 / total
        else null,
    )
}

/** Time and count per action, leaving out rests. A stage that never finished adds no time. */
private fun hiitActions(record: HealthFitnessRecord): List<HiitAction> {
    if (record.extra.kind != HealthFitnessExtraKind.HIIT) return emptyList()
    return record.extra.stages
        .filter { it.rest == false }
        .groupBy { checkNotNull(it.typeCode) }
        .map { (type, stages) ->
            HiitAction(
                type,
                stages.sumOf { stage -> stage.durationMillis?.takeIf { it > 0 } ?: 0 },
                stages
                    .mapNotNull { stage -> stage.actionTimes?.takeIf { it >= 0 } }
                    .takeIf { it.isNotEmpty() }
                    ?.sum(),
            )
        }
}

private fun hiitGroups(record: HealthFitnessRecord): Int? =
    if (record.extra.kind != HealthFitnessExtraKind.HIIT) null
    else record.extra.stages.count { it.typeCode == GroupRest } + 1

/** Train and rest stretches; the last one runs to the end of the workout. */
private fun groups(record: HealthFitnessRecord): List<TrainingGroup> =
    record.groups?.intervals.orEmpty().map { interval ->
        val end = interval.endElapsedMillis ?: record.durationMillis?.takeIf { it >= 0 }
        val start = interval.startElapsedMillis
        TrainingGroup(
            interval.state,
            interval.groupNumber,
            if (start != null && end != null && end >= start) end - start else null,
        )
    }
