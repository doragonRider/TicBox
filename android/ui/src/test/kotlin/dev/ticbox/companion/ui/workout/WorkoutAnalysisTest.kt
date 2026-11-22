package dev.ticbox.companion.ui.workout

import dev.ticbox.companion.api.HealthFitnessExtra
import dev.ticbox.companion.api.HealthFitnessExtraKind
import dev.ticbox.companion.api.HealthFitnessGroupInterval
import dev.ticbox.companion.api.HealthFitnessGroupState
import dev.ticbox.companion.api.HealthFitnessGroups
import dev.ticbox.companion.api.HealthFitnessHiitStage
import dev.ticbox.companion.api.HealthFitnessLapPoint
import dev.ticbox.companion.api.HealthFitnessMode
import dev.ticbox.companion.api.HealthFitnessPoint
import dev.ticbox.companion.api.HealthFitnessRecord
import dev.ticbox.companion.api.HealthFitnessSportTypes
import dev.ticbox.companion.api.HealthFitnessStroke
import dev.ticbox.companion.api.HealthFitnessSwimmingPoint
import dev.ticbox.companion.api.HealthFitnessSwimmingRecord
import dev.ticbox.companion.api.HealthRawRecord
import dev.ticbox.companion.api.HealthRecordKind
import dev.ticbox.companion.api.HealthRecordSource
import dev.ticbox.companion.api.PeerAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class WorkoutAnalysisTest {
    private val raw =
        HealthRawRecord(
            HealthRecordSource.LocalDevice("profile", PeerAddress("AA:BB:CC:DD:EE:FF")),
            HealthRecordKind.SPORT,
            "key",
            null,
            null,
            null,
            null,
            null,
            false,
            0,
            "{}",
        )

    private fun record(
        type: Long = 0,
        durationMillis: Long? = null,
        swimming: HealthFitnessSwimmingRecord? = null,
        groups: HealthFitnessGroups? = null,
        extra: HealthFitnessExtra =
            HealthFitnessExtra(HealthFitnessExtraKind.NONE, null, emptyList()),
    ) =
        HealthFitnessRecord(
            raw,
            1,
            null,
            type,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            durationMillis,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            HealthFitnessMode.LAND,
            swimming,
            null,
            groups,
            extra,
            emptySet(),
            emptySet(),
            emptySet(),
        )

    private fun point(
        elapsed: Long?,
        heart: Long? = null,
        steps: Long? = null,
        latitude: String? = null,
        longitude: String? = null,
        auxiliary: String? = null,
        meters: Long? = null,
        speed: String? = null,
        resume: Long? = null,
        elevation: String? = null,
        strideFrequency: Long? = null,
        swimming: HealthFitnessSwimmingPoint? = null,
        lap: HealthFitnessLapPoint? = null,
        sentinels: Set<String> = emptySet(),
    ) =
        HealthFitnessPoint(
            raw,
            null,
            1,
            null,
            elapsed,
            heart,
            steps,
            latitude,
            longitude,
            auxiliary,
            meters,
            speed,
            null,
            resume,
            null,
            null,
            null,
            null,
            elevation,
            strideFrequency,
            null,
            HealthFitnessMode.LAND,
            swimming,
            lap,
            sentinels,
            emptySet(),
            emptySet(),
        )

    private fun located(elapsed: Long, latitude: String = "51.5", longitude: String = "-0.1") =
        point(elapsed, latitude = latitude, longitude = longitude, auxiliary = "90")

    private fun analyze(vararg points: HealthFitnessPoint, age: Int? = null) =
        analyzeWorkout(record(), points.toList(), age)

    @Test
    fun aSplitEndsAtTheFirstSamplePastEachKilometerAndTheRestIsNotAFullOne() {
        val splits =
            analyze(
                    point(0, meters = 0),
                    point(300_000, meters = 1_010),
                    point(600_000, meters = 1_500),
                    point(700_000, meters = 2_300),
                    point(800_000, meters = 2_600),
                )
                .splits
        assertEquals(listOf(1_010L, 1_290L, 300L), splits.map { it.meters })
        assertEquals(listOf(300_000L, 400_000L, 100_000L), splits.map { it.millis })
        assertEquals(listOf(true, true, false), splits.map { it.full })
        assertEquals(300_000.0 / 1_010, splits.first().secondsPerKm)
    }

    @Test
    fun lapsAccumulateTimeAndSkipNumbersThatDoNotIncrease() {
        val laps =
            analyze(
                    point(1, lap = HealthFitnessLapPoint(1, "1", 300_000, 1_000)),
                    point(2, lap = HealthFitnessLapPoint(1, "1", 999_000, 999)),
                    point(3, lap = HealthFitnessLapPoint(2, "2", null, 500)),
                    point(4, lap = HealthFitnessLapPoint(3, "3", 200_000, 0)),
                )
                .laps
        assertEquals(listOf(1L, 2L, 3L), laps.map { it.number })
        assertEquals(listOf(300_000L, 300_000L, 500_000L), laps.map { it.cumulativeMillis })
        assertEquals(listOf(300.0, null, null), laps.map { it.secondsPerKm })
    }

    @Test
    fun aRouteBreaksAtLongGapsAndResumesAndIgnoresSamplesWithoutAFix() {
        val route =
            analyze(
                    located(0),
                    located(5_000),
                    // Exactly ten seconds is already a gap.
                    located(15_000),
                    // A resume on a sample without a location still applies to the next one.
                    point(17_000, resume = 1),
                    located(20_000),
                    located(22_000, "0", "0"),
                    point(23_000, latitude = "51.5", longitude = "-0.1", auxiliary = "-1"),
                    located(25_000),
                )
                .route
        assertEquals(listOf(2, 1, 2), route.map { it.size })
    }

    @Test
    fun aSingleLocationIsNotARoute() {
        assertTrue(analyze(located(0)).route.isEmpty())
    }

    @Test
    fun zonesCountTheTimeBeforeEachSampleForThatSample() {
        val zones =
            analyze(
                    // A reading at the very start has no meaningful heart rate yet.
                    point(0, heart = 190),
                    point(1_000, heart = 100),
                    point(2_000, heart = 96),
                    point(5_000, heart = 120),
                    point(6_000, heart = 190),
                    point(8_000, heart = 80),
                    age = 30,
                )
                .zones
        assertNotNull(zones)
        assertEquals(190, zones.maximum)
        assertFalse(zones.assumedAge)
        assertEquals(171, zones.thresholds[HeartZone.ANAEROBIC])
        assertEquals(
            mapOf(
                HeartZone.WARM_UP to 1_000L,
                HeartZone.FAT_BURN to 3_000L,
                HeartZone.AEROBIC to 0L,
                HeartZone.ANAEROBIC to 0L,
                HeartZone.DANGER to 1_000L,
            ),
            zones.millis,
        )
    }

    @Test
    fun zonesSayWhenTheAgeWasAssumedAndNeedTwoReadings() {
        val assumed = analyze(point(1_000, heart = 100), point(2_000, heart = 110)).zones
        assertNotNull(assumed)
        assertEquals(195, assumed.maximum)
        assertTrue(assumed.assumedAge)
        assertNull(analyze(point(1_000, heart = 100)).zones)
    }

    @Test
    fun cadenceIsMeasuredExceptOnATreadmillWhereStepsAreMoreReliable() {
        val samples =
            listOf(
                point(0, steps = 0, strideFrequency = 150),
                point(60_000, steps = 100, strideFrequency = 160),
            )
        val outdoor = analyzeWorkout(record(type = 0), samples, null)
        assertFalse(outdoor.cadenceDerived)
        assertEquals(listOf(150.0, 160.0), outdoor.cadence.map { it.value })
        val treadmill = analyzeWorkout(record(type = 132), samples, null)
        assertTrue(treadmill.cadenceDerived)
        assertEquals(listOf(100.0), treadmill.cadence.map { it.value })
    }

    @Test
    fun implausibleStridesAndPlaceholderElevationsAreLeftOut() {
        val analysis =
            analyze(
                point(0, steps = 0, meters = 0, elevation = "10"),
                point(60_000, steps = 100, meters = 90, elevation = "-1000"),
                point(120_000, steps = 110, meters = 190, elevation = "12")
                    .copy(sentinelColumns = setOf("ELEVATION")),
            )
        assertEquals(listOf(0.9), analysis.strideMeters.map { it.value })
        assertEquals(listOf(10.0, -1000.0), analysis.elevation.map { it.value })
    }

    @Test
    fun swimmingDifferencesCumulativeCountsPerLength() {
        val samples =
            listOf(
                swimSample(1_000, "1.0", 20, "1.0"),
                // Not a new length: it must not add a row.
                swimSample(2_000, "1.0", 21, "1.0"),
                swimSample(3_000, "2.0", 42, "1.0"),
            )
        val pool = record(swimming = HealthFitnessSwimmingRecord(null, 25, null))
        val analysis = analyzeWorkout(pool, samples, null)
        assertEquals(listOf(25L, 50L), analysis.swimTrips.map { it.cumulativeMeters })
        assertEquals(listOf(20L, 22L), analysis.swimTrips.map { it.strokes })
        assertEquals(listOf(45L, 47L), analysis.swimTrips.map { it.swolf })
        assertEquals(100.0, analysis.secondsPer100Meters)
        assertEquals(
            listOf(StrokeDistance(HealthFitnessStroke.FREESTYLE, 50.0)),
            analysis.strokes,
        )
    }

    @Test
    fun aLengthWithoutSpeedHasNoSwolfAndNoAveragePace() {
        val pool = record(swimming = HealthFitnessSwimmingRecord(null, 25, null))
        val analysis = analyzeWorkout(pool, listOf(swimSample(1_000, "1.0", 20, null)), null)
        assertNull(analysis.swimTrips.single().swolf)
        assertNull(analysis.secondsPer100Meters)
    }

    private fun swimSample(elapsed: Long, trips: String, strokes: Long, speed: String?) =
        point(elapsed, speed = speed, swimming = HealthFitnessSwimmingPoint(trips, strokes, 1))

    @Test
    fun hiitMergesActionsLeavesOutRestsAndCountsGroupsFromGroupRests() {
        fun stage(type: Long, millis: Long?, times: Long?) =
            HealthFitnessHiitStage(0, "{}", type, 0, null, millis, times, emptySet())
        val extra =
            HealthFitnessExtra(
                HealthFitnessExtraKind.HIIT,
                null,
                listOf(
                    stage(40, 30_000, 10),
                    stage(35, 20_000, null),
                    stage(36, 60_000, null),
                    stage(40, 20_000, 5),
                    // Never finished: the watch writes -1.
                    stage(41, -1, null),
                ),
            )
        val analysis = analyzeWorkout(record(extra = extra), emptyList(), null)
        assertEquals(
            listOf(HiitAction(40, 50_000, 15), HiitAction(41, 0, null)),
            analysis.hiitActions,
        )
        assertEquals(2, analysis.hiitGroups)
    }

    @Test
    fun theLastTrainingGroupRunsToTheEndOfTheWorkout() {
        val groups =
            HealthFitnessGroups(
                emptyList(),
                listOf(
                    HealthFitnessGroupInterval(0, 1, HealthFitnessGroupState.TRAIN, 0, 100),
                    HealthFitnessGroupInterval(1, 1, HealthFitnessGroupState.REST, 100, null),
                ),
                1,
                HealthFitnessGroupState.REST,
            )
        assertEquals(
            listOf(100L, 200L),
            analyzeWorkout(record(durationMillis = 300, groups = groups), emptyList(), null)
                .groups
                .map { it.millis },
        )
        assertEquals(
            listOf(100L, null),
            analyzeWorkout(record(groups = groups), emptyList(), null).groups.map { it.millis },
        )
    }

    @Test
    fun everyListedSportHasADisplayName() {
        val missing =
            HealthFitnessSportTypes.names.keys.filter { it >= 0 && sportNameResource(it) == null }
        assertEquals(emptyList(), missing)
    }
}
