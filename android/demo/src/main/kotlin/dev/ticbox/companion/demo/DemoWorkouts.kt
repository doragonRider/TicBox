package dev.ticbox.companion.demo

import dev.ticbox.companion.api.HealthFitnessExtra
import dev.ticbox.companion.api.HealthFitnessExtraKind
import dev.ticbox.companion.api.HealthFitnessGroupBoundary
import dev.ticbox.companion.api.HealthFitnessGroupInterval
import dev.ticbox.companion.api.HealthFitnessGroupState
import dev.ticbox.companion.api.HealthFitnessGroups
import dev.ticbox.companion.api.HealthFitnessHiitStage
import dev.ticbox.companion.api.HealthFitnessLandRecord
import dev.ticbox.companion.api.HealthFitnessLapPoint
import dev.ticbox.companion.api.HealthFitnessMode
import dev.ticbox.companion.api.HealthFitnessPoint
import dev.ticbox.companion.api.HealthFitnessRecord
import dev.ticbox.companion.api.HealthFitnessRecovery
import dev.ticbox.companion.api.HealthFitnessSwimmingPoint
import dev.ticbox.companion.api.HealthFitnessSwimmingRecord
import dev.ticbox.companion.api.HealthFitnessTargetType
import dev.ticbox.companion.api.HealthRawRecord
import dev.ticbox.companion.api.HealthRecordKind
import dev.ticbox.companion.api.HealthRecordSource
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

private const val OutdoorRun = 0L
private const val AutoWalk = 5L
private const val FreeWorkout = 4L
private const val PoolSwim = 9L
private const val Hiit = 24L
private const val Burpee = 25L
private const val SquatJumps = 28L
private const val Plank = 29L
private const val Rest = 35L
private const val GroupRest = 36L
private const val Minute = 60_000L

/** Sample workouts shaped like parsed rows, so the workout pages can be tried without a watch. */
internal class DemoWorkouts(
    private val clock: Clock,
    private val rawRecord: (HealthRecordKind, String, String, Long, Long) -> HealthRawRecord,
) {
    private class Workout(val record: HealthFitnessRecord, val points: List<HealthFitnessPoint>)

    private val workouts by lazy {
        listOf(run(1), swim(3), hiit(5), groups(7), autoWalk(9)).associateBy {
            checkNotNull(it.record.id)
        }
    }

    fun records(source: HealthRecordSource): List<HealthFitnessRecord> =
        workouts.values.map { it.record.copy(raw = it.record.raw.copy(source = source)) }

    fun points(recordId: Long, source: HealthRecordSource): List<HealthFitnessPoint> =
        workouts[recordId]?.points.orEmpty().map { it.copy(raw = it.raw.copy(source = source)) }

    private fun start(daysAgo: Int, hour: Int): Long =
        LocalDate.now(clock)
            .minusDays(daysAgo.toLong())
            .atTime(LocalTime.of(hour, 10))
            .atZone(clock.zone)
            .toInstant()
            .toEpochMilli()

    private fun run(daysAgo: Int): Workout {
        val id = daysAgo.toLong()
        val startMillis = start(daysAgo, 7)
        val duration = 30 * Minute
        val samples =
            // Five seconds apart: a gap of ten seconds or more breaks the route.
            (0..360).map { tick ->
                val elapsed = tick * 5_000L
                val progress = tick / 360.0
                val angle = 2 * PI * progress
                val speed = 2.9 + sin(angle * 3) * 0.2
                landPoint(
                    id,
                    tick.toLong(),
                    startMillis + elapsed,
                    elapsed,
                    heart = (118 + 42 * progress + sin(angle * 5) * 6).roundToLong(),
                    steps = (tick * 14.25).roundToLong(),
                    meters = (progress * 5_240).roundToLong(),
                    speed = "%.2f".format(Locale.ROOT, speed),
                    elevation = "%.1f".format(Locale.ROOT, 32 + sin(angle * 2) * 9),
                    latitude = "%.6f".format(Locale.ROOT, 51.5 + sin(angle) * 0.008),
                    longitude = "%.6f".format(Locale.ROOT, -0.12 + cos(angle) * 0.012),
                    stride = (168 + sin(angle * 4) * 6).roundToLong(),
                )
            }
        val laps =
            (1L..5L).map { number ->
                val elapsed = number * 340_000L
                landPoint(id, 1_000 + number, startMillis + elapsed, elapsed)
                    .copy(
                        lap =
                            HealthFitnessLapPoint(
                                number,
                                number.toString(),
                                330_000L + number * 4_000L,
                                1_000L,
                            ),
                        swimTripsDecimal = number.toString(),
                    )
            }
        return Workout(
            record(id, OutdoorRun, startMillis, duration)
                .copy(
                    distanceMeters = 5_240,
                    calorieKcalDecimal = "386",
                    steps = 5_130,
                    averageHeartRateBpm = 142,
                    maxElevationMetersDecimal = "41.0",
                    minElevationMetersDecimal = "23.0",
                    cumulativeUpMetersDecimal = "64.0",
                    cumulativeDownMetersDecimal = "63.0",
                    scoreDecimal = "82",
                    targetTypeCode = HealthFitnessTargetType.DISTANCE.code,
                    targetValueDecimal = "5000",
                    land = HealthFitnessLandRecord("47.5", 5, 1_000, 350_000),
                    extra =
                        HealthFitnessExtra(
                            HealthFitnessExtraKind.RECOVERY,
                            HealthFitnessRecovery("{}", "OutdoorRunning", 18 * 60 * Minute),
                            emptyList(),
                        ),
                ),
            samples + laps,
        )
    }

    private fun swim(daysAgo: Int): Workout {
        val id = daysAgo.toLong()
        val startMillis = start(daysAgo, 18)
        val trips = 40
        val samples =
            (1..trips).map { trip ->
                val elapsed = trip * 34_000L
                val stroke = if (trip <= 28) 1L else 2L
                point(id, trip.toLong(), startMillis + elapsed, elapsed, HealthFitnessMode.SWIMMING)
                    .copy(
                        heartRateBpm = 120L + trip % 18,
                        speedMetersPerSecondDecimal = if (stroke == 1L) "0.86" else "0.72",
                        swimTripsDecimal = trip.toString(),
                        swimming =
                            HealthFitnessSwimmingPoint(
                                trip.toString(),
                                trip * 19L,
                                stroke,
                            ),
                    )
            }
        return Workout(
            record(id, PoolSwim, startMillis, trips * 34_000L)
                .copy(
                    mode = HealthFitnessMode.SWIMMING,
                    distanceMeters = 1_000,
                    calorieKcalDecimal = "294",
                    averageHeartRateBpm = 128,
                    swimming = HealthFitnessSwimmingRecord(trips.toString(), 25, trips * 19L),
                    swimTripsDecimal = trips.toString(),
                    swimPoolLengthRaw = 25,
                    swimStrokeRaw = trips * 19L,
                ),
            samples,
        )
    }

    private fun hiit(daysAgo: Int): Workout {
        val id = daysAgo.toLong()
        val startMillis = start(daysAgo, 19)
        val plan =
            listOf(
                Triple(Burpee, 40_000L, 22L),
                Triple(Rest, 20_000L, null),
                Triple(SquatJumps, 40_000L, 30L),
                Triple(Rest, 20_000L, null),
                Triple(Plank, 45_000L, null),
                Triple(GroupRest, 60_000L, null),
                Triple(Burpee, 40_000L, 18L),
                Triple(Rest, 20_000L, null),
                Triple(SquatJumps, 40_000L, 26L),
            )
        var elapsed = 0L
        val stages = plan.mapIndexed { index, (type, millis, times) ->
            val stage =
                HealthFitnessHiitStage(
                    index,
                    "{}",
                    type,
                    elapsed,
                    millis,
                    millis,
                    times,
                    emptySet(),
                )
            elapsed += millis
            stage
        }
        val samples =
            (0..elapsed / 15_000L).map { tick ->
                val time = tick * 15_000L
                val bpm = 126 + sin(tick * 0.6) * 24
                point(id, tick, startMillis + time, time, HealthFitnessMode.LAND)
                    .copy(heartRateBpm = bpm.roundToLong())
            }
        return Workout(
            record(id, Hiit, startMillis, elapsed)
                .copy(
                    calorieKcalDecimal = "71",
                    averageHeartRateBpm = 134,
                    extra = HealthFitnessExtra(HealthFitnessExtraKind.HIIT, null, stages),
                ),
            samples,
        )
    }

    private fun groups(daysAgo: Int): Workout {
        val id = daysAgo.toLong()
        val startMillis = start(daysAgo, 20)
        val boundaries = (1..7).map { it * 90_000L }
        val intervals =
            (0..7).map { index ->
                HealthFitnessGroupInterval(
                    index,
                    index / 2 + 1,
                    if (index % 2 == 0) HealthFitnessGroupState.TRAIN
                    else HealthFitnessGroupState.REST,
                    index * 90_000L,
                    if (index == 7) null else (index + 1) * 90_000L,
                )
            }
        return Workout(
            record(id, FreeWorkout, startMillis, 12 * Minute)
                .copy(
                    calorieKcalDecimal = "104",
                    averageHeartRateBpm = 118,
                    rawGroups = boundaries.joinToString(","),
                    groups =
                        HealthFitnessGroups(
                            boundaries.mapIndexed { index, millis ->
                                HealthFitnessGroupBoundary(index, millis.toString(), millis)
                            },
                            intervals,
                            currentGroupNumber = 4,
                            currentState = HealthFitnessGroupState.REST,
                        ),
                ),
            emptyList(),
        )
    }

    /** A summary with no samples, the way auto-detected walks often arrive. */
    private fun autoWalk(daysAgo: Int): Workout {
        val id = daysAgo.toLong()
        val startMillis = start(daysAgo, 12)
        return Workout(
            record(id, AutoWalk, startMillis, 21 * Minute)
                .copy(
                    distanceMeters = 1_780,
                    steps = 2_260,
                    calorieKcalDecimal = "92",
                    sessionModeCode = 1,
                ),
            emptyList(),
        )
    }

    private fun record(id: Long, type: Long, startMillis: Long, duration: Long) =
        HealthFitnessRecord(
            raw =
                rawRecord(
                    HealthRecordKind.SPORT,
                    "demo-workout-$id",
                    "workout",
                    startMillis,
                    startMillis + duration,
                ),
            id = id,
            hash = "demo-$id",
            typeCode = type,
            targetTypeCode = null,
            targetValueDecimal = null,
            accountId = null,
            vendorSyncCode = 0,
            deletedCode = 0,
            startMillis = startMillis,
            endMillis = startMillis + duration,
            timestampRaw = startMillis,
            durationMillis = duration,
            distanceMeters = null,
            calorieKcalDecimal = null,
            steps = null,
            averageHeartRateBpm = null,
            rawGroups = null,
            swimTripsDecimal = null,
            swimDistanceRaw = null,
            swimPoolLengthRaw = null,
            swimStrokeRaw = null,
            deviceId = "demo-watch",
            sessionModeCode = 0,
            maxElevationMetersDecimal = null,
            minElevationMetersDecimal = null,
            cumulativeUpMetersDecimal = null,
            cumulativeDownMetersDecimal = null,
            scoreDecimal = null,
            rawExtra = null,
            mode = HealthFitnessMode.LAND,
            swimming = null,
            land = null,
            groups = null,
            extra = HealthFitnessExtra(HealthFitnessExtraKind.NONE, null, emptyList()),
            sentinelColumns = emptySet(),
            unknownColumns = emptySet(),
            issues = emptySet(),
        )

    private fun landPoint(
        recordId: Long,
        pointId: Long,
        time: Long,
        elapsed: Long,
        heart: Long? = null,
        steps: Long? = null,
        meters: Long? = null,
        speed: String? = null,
        elevation: String? = null,
        latitude: String? = null,
        longitude: String? = null,
        stride: Long? = null,
    ) =
        point(recordId, pointId, time, elapsed, HealthFitnessMode.LAND)
            .copy(
                heartRateBpm = heart,
                steps = steps,
                distanceMeters = meters,
                speedMetersPerSecondDecimal = speed,
                elevationMetersDecimal = elevation,
                latitudeDecimal = latitude,
                longitudeDecimal = longitude,
                locationAuxiliaryDecimal = latitude?.let { "90" },
                strideFrequencyStepsPerMinute = stride,
            )

    private fun point(
        recordId: Long,
        pointId: Long,
        time: Long,
        elapsed: Long,
        mode: HealthFitnessMode,
    ) =
        HealthFitnessPoint(
            raw =
                rawRecord(
                    HealthRecordKind.POINT,
                    "demo-workout-$recordId-$pointId",
                    "workout_point",
                    time,
                    time,
                ),
            id = recordId * 100_000 + pointId,
            recordId = recordId,
            timestampMillis = time,
            elapsedMillis = elapsed,
            heartRateBpm = null,
            steps = null,
            latitudeDecimal = null,
            longitudeDecimal = null,
            locationAuxiliaryDecimal = null,
            distanceMeters = null,
            speedMetersPerSecondDecimal = null,
            calorieKcalDecimal = null,
            resumeCode = 0,
            swimTripsDecimal = null,
            swimDistanceRaw = null,
            swimStrokeRaw = null,
            swimTypeRaw = null,
            elevationMetersDecimal = null,
            strideFrequencyStepsPerMinute = null,
            parentTypeCode = null,
            mode = mode,
            swimming = null,
            lap = null,
            sentinelColumns = emptySet(),
            unknownColumns = emptySet(),
            issues = emptySet(),
        )
}
