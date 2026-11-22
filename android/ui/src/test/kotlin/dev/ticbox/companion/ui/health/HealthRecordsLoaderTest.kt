package dev.ticbox.companion.ui.health

import dev.ticbox.companion.api.HealthMotionState
import dev.ticbox.companion.api.HealthMotionTransition
import dev.ticbox.companion.api.HealthParseIssue
import dev.ticbox.companion.api.HealthPointPage
import dev.ticbox.companion.api.HealthPointQuery
import dev.ticbox.companion.api.HealthPointRecord
import dev.ticbox.companion.api.HealthPointType
import dev.ticbox.companion.api.HealthPointValue
import dev.ticbox.companion.api.HealthRawRecord
import dev.ticbox.companion.api.HealthRecordKind
import dev.ticbox.companion.api.HealthRecordPage
import dev.ticbox.companion.api.HealthRecordQuery
import dev.ticbox.companion.api.HealthRecordReadStatus
import dev.ticbox.companion.api.HealthRecordSource
import dev.ticbox.companion.api.HealthRecordsClient
import dev.ticbox.companion.api.HealthSleepPage
import dev.ticbox.companion.api.HealthSleepQuery
import dev.ticbox.companion.api.HealthSleepRecord
import dev.ticbox.companion.api.HealthValueStatus
import dev.ticbox.companion.api.PeerAddress
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class HealthRecordsLoaderTest {
    private val source = HealthRecordSource.LocalDevice("profile", PeerAddress("AA:BB:CC:DD:EE:FF"))
    private val date = LocalDate.of(2026, 11, 2)

    private fun time(value: String) = Instant.parse(value).toEpochMilli()

    private fun raw(key: String, kind: HealthRecordKind = HealthRecordKind.POINT) =
        HealthRawRecord(source, kind, key, null, null, null, null, null, false, 0, "{}")

    private fun point(
        key: String,
        type: HealthPointType,
        value: String,
        at: String,
        semantic: String? = null,
        unit: String? = null,
        start: String? = null,
        motion: HealthMotionState? = null,
    ) =
        HealthPointRecord(
            raw(key),
            1,
            "watch",
            null,
            type.code,
            start?.let(::time) ?: time(at),
            time(at),
            0,
            0,
            value,
            listOf(HealthPointValue(0, value, value, HealthValueStatus.NUMBER, semantic, unit)),
            motion?.let { HealthMotionTransition(it.code, HealthMotionState.AWAKE.code) },
            emptySet(),
        )

    private fun heart(key: String, value: String, at: String) =
        point(key, HealthPointType.HEART_RATE, value, at, "heart_rate", "bpm")

    private class Records : HealthRecordsClient {
        var rows: List<HealthPointRecord> = emptyList()
        var sleeps: List<HealthSleepRecord> = emptyList()
        var pointStatus = HealthRecordReadStatus.AVAILABLE
        var failingType: Long? = null
        var repeatOffset = false
        val queries = mutableListOf<HealthPointQuery>()

        override suspend fun sources() = emptyList<HealthRecordSource>()

        override suspend fun records(
            kind: HealthRecordKind,
            query: HealthRecordQuery,
        ): HealthRecordPage = error("Charts use typed queries")

        override suspend fun points(query: HealthPointQuery): HealthPointPage {
            queries += query
            if (query.typeCode == failingType) error("fixture storage failure")
            val selected = rows.filter {
                it.typeCode == query.typeCode &&
                    (query.fromMillis == null || it.endMillis!! >= query.fromMillis!!) &&
                    (query.toMillis == null || it.startMillis!! <= query.toMillis!!)
            }
            val next =
                if (repeatOffset) query.offset
                else (query.offset + query.limit).takeIf { it < selected.size }
            return HealthPointPage(selected.drop(query.offset).take(query.limit), next, pointStatus)
        }

        override suspend fun sleepRecords(query: HealthSleepQuery) =
            HealthSleepPage(sleeps.drop(query.offset).take(query.limit), null)

        override suspend fun fitnessRecords(
            query: dev.ticbox.companion.api.HealthFitnessRecordQuery
        ) =
            dev.ticbox.companion.api.HealthFitnessRecordPage(
                emptyList(),
                null,
                HealthRecordReadStatus.SOURCE_UNAVAILABLE,
            )

        override suspend fun fitnessPoints(
            query: dev.ticbox.companion.api.HealthFitnessPointQuery
        ) =
            dev.ticbox.companion.api.HealthFitnessPointPage(
                emptyList(),
                null,
                HealthRecordReadStatus.SOURCE_UNAVAILABLE,
            )

        override fun close() = Unit
    }

    private suspend fun load(records: Records, vararg dates: LocalDate) =
        HealthRecordsLoader(records, source, ZoneOffset.UTC).load(dates.toList())

    @Test
    fun todayHoldsThePast24HoursBesideItsCalendarDay() = runTest {
        val noon = Instant.parse("2026-11-02T12:00:00Z")
        val records =
            Records().apply {
                rows =
                    listOf(
                        heart("too-old", "50", "2026-11-01T11:59:00Z"),
                        heart("last-night", "60", "2026-11-01T23:00:00Z"),
                        heart("this-morning", "70", "2026-11-02T06:00:00Z"),
                        heart("watch-ahead", "80", "2026-11-02T12:01:00Z"),
                        point(
                            "steps",
                            HealthPointType.DELTA_STEP,
                            "100",
                            "2026-11-01T23:00:00Z",
                            "delta_step",
                            "steps",
                        ),
                    )
            }
        val results =
            HealthRecordsLoader(records, source, ZoneOffset.UTC) { noon }
                .load(listOf(date.minusDays(1), date))

        val today = results.getValue(date)
        assertEquals(listOf(70.0, 80.0), today.day.heart.map { it.value })
        val rolled = today.rolled()
        assertEquals(listOf(60.0, 70.0, 80.0), rolled.day.heart.map { it.value })
        assertEquals(noon, rolled.until(HealthMetric.HEART))
        assertEquals(noon, rolled.until(HealthMetric.OXYGEN))
        assertEquals(HealthReadState.AVAILABLE, rolled.state(HealthMetric.HEART))
        assertEquals(null, rolled.until(HealthMetric.ACTIVITY))
        assertTrue(rolled.day.steps.isEmpty())

        val yesterday = results.getValue(date.minusDays(1))
        assertEquals(null, yesterday.recent)
        assertEquals(listOf(50.0, 60.0), yesterday.day.heart.map { it.value })
    }

    @Test
    fun onlyTodayReachesBackBeforeItsMidnight() = runTest {
        val records = Records()
        val noon = Instant.parse("2026-11-02T12:00:00Z")
        val loader = HealthRecordsLoader(records, source, ZoneOffset.UTC) { noon }
        fun oldest(type: HealthPointType) =
            records.queries.last { it.typeCode == type.code }.fromMillis

        loader.load(listOf(date))
        assertEquals(time("2026-11-01T12:00:00Z"), oldest(HealthPointType.HEART_RATE))
        assertEquals(time("2026-11-02T00:00:00Z"), oldest(HealthPointType.DELTA_STEP))

        loader.load(listOf(date.minusDays(1)))
        assertEquals(time("2026-11-01T00:00:00Z"), oldest(HealthPointType.HEART_RATE))
    }

    @Test
    fun aMetricCountsAsRecordedOnAnyRowOfItsOwnOrWhenItsReadFails() = runTest {
        val long = "2020-01-01T00:00:00Z"
        val records =
            Records().apply {
                rows =
                    listOf(
                        heart("long-ago", "70", long),
                        point(
                            "steps",
                            HealthPointType.STEP_COUNT,
                            "5",
                            long,
                            "step_count",
                            "steps",
                        ),
                    )
                failingType = HealthPointType.VO2MAX.code
            }
        assertEquals(
            setOf(HealthMetric.HEART, HealthMetric.ACTIVITY, HealthMetric.VO2MAX),
            HealthRecordsLoader(records, source, ZoneOffset.UTC).recordedMetrics(),
        )
    }

    @Test
    fun aLogCountsOnlyWhenItsPageWouldListSomething() = runTest {
        val noon = Instant.parse("2026-11-02T12:00:00Z")
        val records =
            Records().apply {
                rows =
                    listOf(
                        point("mood", HealthPointType.TODAY_MOOD, "1", "2026-10-30T08:00:00Z"),
                        point("cycle", HealthPointType.PERIOD_EVENT, "0", "2026-07-01T08:00:00Z"),
                    )
                failingType = HealthPointType.ARTY_DATA.code
            }
        assertEquals(
            setOf(HealthLog.MOOD, HealthLog.ARTY),
            HealthRecordsLoader(records, source, ZoneOffset.UTC) { noon }.recordedLogs(),
        )
    }

    @Test
    fun confirmedValuesAreChartedPerDayAndOtherRowsMakeTheDayPartial() = runTest {
        val records =
            Records().apply {
                rows =
                    listOf(
                        heart("heart", "75", "2026-11-02T01:00:00Z"),
                        heart("old", "65", "2026-11-01T23:59:00Z"),
                        heart("next", "90", "2026-11-03T00:00:00Z"),
                        heart("other-profile", "80", "2026-11-02T03:00:00Z").let {
                            it.copy(
                                raw =
                                    it.raw.copy(
                                        source =
                                            HealthRecordSource.LocalDevice("another", source.peer)
                                    )
                            )
                        },
                        point(
                            "oxygen",
                            HealthPointType.BLOOD_OXYGEN,
                            "255",
                            "2026-11-02T01:30:00Z",
                            "blood_oxygen",
                            "%",
                        ),
                        point(
                            "unknown-step",
                            HealthPointType.STEP_COUNT,
                            "9000",
                            "2026-11-02T02:00:00Z",
                        ),
                    )
            }
        val days = load(records, date.minusDays(1), date)
        val today = days.getValue(date)
        assertEquals(listOf(75.0), today.day.heart.map { it.value })
        // Values are not judged against physiological ranges.
        assertEquals(listOf(255.0), today.day.oxygen.map { it.value })
        assertEquals(listOf(65.0), days.getValue(date.minusDays(1)).day.heart.map { it.value })
        assertTrue(today.day.steps.isEmpty())
        assertEquals(1, today.day.activityRecords.size)
        assertEquals(HealthReadState.PARTIAL, today.state(HealthMetric.ACTIVITY))
        assertTrue(
            records.queries.all {
                it.source == source && it.toMillis == time("2026-11-02T23:59:59.999Z")
            }
        )
    }

    @Test
    fun aDaysStepsMatchTheTotalOfTheFullLoad() = runTest {
        fun step(key: String, value: String, at: String) =
            point(key, HealthPointType.DELTA_STEP, value, at, "delta_step", "steps")
        val records =
            Records().apply {
                rows =
                    listOf(
                        step("morning", "60", "2026-11-02T01:00:00Z"),
                        step("noon", "36", "2026-11-02T12:00:00Z"),
                        step("yesterday", "500", "2026-11-01T23:59:00Z"),
                    )
            }
        val loader = HealthRecordsLoader(records, source, ZoneOffset.UTC)
        assertEquals(96, loader.loadSteps(date))
        assertEquals(load(records, date).getValue(date).day.stepTotal, loader.loadSteps(date))
        assertEquals(null, loader.loadSteps(date.plusDays(1)))
    }

    @Test
    fun aRowWithSeveralConfirmedSlotsChartsTheirAverage() = runTest {
        val noise =
            point(
                "noise",
                HealthPointType.NOISE_DECIBEL,
                "40",
                "2026-11-02T01:00:00Z",
                "noise_decibel",
                "dB",
            )
        fun slot(index: Int, value: String, unit: String?) =
            HealthPointValue(index, value, value, HealthValueStatus.NUMBER, "noise_decibel", unit)
        val records =
            Records().apply {
                rows =
                    listOf(
                        noise.copy(values = listOf(slot(0, "40", "dB"), slot(1, "50", "dB"))),
                        // An unconfirmed slot does not count, and leaves nothing to chart here.
                        noise.copy(
                            raw = raw("noise-other"),
                            values = listOf(slot(0, "90", null)),
                        ),
                    )
            }
        val today = load(records, date).getValue(date)
        assertEquals(listOf(45.0), today.day.points(HealthMetric.NOISE).map { it.value })
        assertEquals(HealthReadState.PARTIAL, today.state(HealthMetric.NOISE))
    }

    @Test
    fun aNightBelongsToTheDayItEndsAndUnknownStagesStayGaps() = runTest {
        val light =
            point(
                "motion",
                HealthPointType.MOTION,
                "9,7",
                "2026-11-02T06:00:00Z",
                start = "2026-11-01T23:00:00Z",
                motion = HealthMotionState.LIGHT_SLEEP,
            )
        val unknown =
            point(
                "unknown",
                HealthPointType.MOTION,
                "99,7",
                "2026-11-02T07:00:00Z",
                start = "2026-11-02T06:00:00Z",
                motion = HealthMotionState.UNDEFINED,
            )
        val session =
            HealthSleepRecord(
                raw("night", HealthRecordKind.SESSION),
                "night",
                "watch",
                null,
                100,
                time("2026-11-01T23:30:00Z"),
                time("2026-11-02T07:00:00Z"),
                0,
                0,
                listOf(light, unknown),
                HealthRecordReadStatus.AVAILABLE,
                emptySet(),
            )
        val open =
            session.copy(
                raw = raw("open", HealthRecordKind.SESSION),
                endMillis = 0,
                issues = setOf(HealthParseIssue.OPEN_SESSION),
            )
        val records = Records().apply { sleeps = listOf(session, open) }
        val days = load(records, date.minusDays(1), date)
        val night = days.getValue(date)
        assertEquals(390, night.day.asleepMinutes)
        val sleep = night.day.sleeps.single()
        assertEquals(SleepStage.LIGHT, sleep.segments.single().stage)
        assertEquals(Instant.parse("2026-11-01T23:30:00Z"), sleep.segments.single().start)
        assertEquals(Instant.parse("2026-11-02T07:00:00Z"), sleep.end)
        assertTrue(days.getValue(date.minusDays(1)).day.sleeps.isEmpty())
        assertEquals(HealthReadState.PARTIAL, night.state(HealthMetric.SLEEP))
    }

    private fun sleepSession(key: String, from: String, to: String, stage: HealthMotionState) =
        HealthSleepRecord(
            raw(key, HealthRecordKind.SESSION),
            key,
            "watch",
            null,
            100,
            time(from),
            time(to),
            0,
            0,
            listOf(point(key, HealthPointType.MOTION, "9,7", to, start = from, motion = stage)),
            HealthRecordReadStatus.AVAILABLE,
            emptySet(),
        )

    @Test
    fun aNapStaysApartFromTheNightAndASplitNightStaysWhole() = runTest {
        val records =
            Records().apply {
                sleeps =
                    listOf(
                        sleepSession(
                            "nap",
                            "2026-11-02T13:00:00Z",
                            "2026-11-02T16:00:00Z",
                            HealthMotionState.LIGHT_SLEEP,
                        ),
                        sleepSession(
                            "after-waking",
                            "2026-11-02T03:40:00Z",
                            "2026-11-02T06:00:00Z",
                            HealthMotionState.LIGHT_SLEEP,
                        ),
                        sleepSession(
                            "night",
                            "2026-11-01T23:00:00Z",
                            "2026-11-02T03:00:00Z",
                            HealthMotionState.DEEP_SLEEP,
                        ),
                    )
            }
        val day = load(records, date).getValue(date)
        assertEquals(
            listOf(
                "2026-11-01T23:00:00Z" to "2026-11-02T06:00:00Z",
                "2026-11-02T13:00:00Z" to "2026-11-02T16:00:00Z",
            ),
            day.day.sleeps.map { it.start.toString() to it.end.toString() },
        )
        assertEquals(380 + 180, day.day.asleepMinutes)
        assertEquals(HealthReadState.AVAILABLE, day.state(HealthMetric.SLEEP))

        // The longest is the main sleep wherever it falls in the day.
        fun light(from: String, to: String): SleepPeriod {
            val start = Instant.parse(from)
            val end = Instant.parse(to)
            return SleepPeriod(start, end, listOf(SleepSegment(SleepStage.LIGHT, start, end)))
        }
        val doze = light("2026-11-02T00:00:00Z", "2026-11-02T01:00:00Z")
        val shift = light("2026-11-02T13:00:00Z", "2026-11-02T20:00:00Z")
        assertEquals(listOf(shift, doze) to false, sleepPeriods(listOf(doze, shift)))
    }

    @Test
    fun failuresUnavailableSourcesAndEmptyResultsStayDistinct() = runTest {
        val records =
            Records().apply {
                failingType = HealthPointType.HEART_RATE.code
                pointStatus = HealthRecordReadStatus.SOURCE_UNAVAILABLE
            }
        val first = load(records, date).getValue(date)
        assertEquals(HealthReadState.FAILED, first.state(HealthMetric.HEART))
        assertEquals(HealthReadState.UNAVAILABLE, first.state(HealthMetric.OXYGEN))
        assertEquals(HealthReadState.EMPTY, first.state(HealthMetric.SLEEP))

        records.failingType = null
        records.pointStatus = HealthRecordReadStatus.AVAILABLE
        records.repeatOffset = true
        records.rows = listOf(heart("heart", "75", "2026-11-02T01:00:00Z"))
        val second = load(records, date).getValue(date)
        // A page that does not advance stops reading and marks the result as limited.
        assertEquals(HealthReadState.PARTIAL, second.state(HealthMetric.HEART))
        assertEquals(1, second.day.heart.size)
    }

    @Test
    fun allPagesAreReadAndDuplicateNightsCountOnce() = runTest {
        val records =
            Records().apply { rows = (0..502).map { heart("h$it", "75", "2026-11-02T01:00:00Z") } }
        val deep =
            point(
                "motion",
                HealthPointType.MOTION,
                "10,7",
                "2026-11-02T07:00:00Z",
                start = "2026-11-02T01:00:00Z",
                motion = HealthMotionState.DEEP_SLEEP,
            )
        val session =
            HealthSleepRecord(
                raw("night", HealthRecordKind.SESSION),
                "night",
                "watch",
                null,
                100,
                time("2026-11-02T01:00:00Z"),
                time("2026-11-02T07:00:00Z"),
                0,
                0,
                listOf(deep),
                HealthRecordReadStatus.AVAILABLE,
                emptySet(),
            )
        records.sleeps =
            listOf(session, session.copy(raw = raw("same-time", HealthRecordKind.SESSION)))
        val day = load(records, date).getValue(date)
        assertEquals(503, day.day.heart.size)
        assertTrue(records.queries.any { it.offset == 500 })
        assertEquals(360, day.day.asleepMinutes)
    }

    @Test
    fun conflictingStagesLeaveAnUnknownGapRegardlessOfOrder() {
        val start = Instant.parse("2026-11-02T01:00:00Z")
        val light = SleepSegment(SleepStage.LIGHT, start, start.plusSeconds(7200))
        val awake =
            SleepSegment(SleepStage.AWAKE, start.plusSeconds(3600), start.plusSeconds(10800))
        val expected =
            listOf(
                light.copy(end = start.plusSeconds(3600)),
                awake.copy(start = start.plusSeconds(7200)),
            )
        assertEquals(expected to true, mergeSleepIntervals(listOf(light, awake)))
        assertEquals(expected to true, mergeSleepIntervals(listOf(awake, light)))
    }

    @Test
    fun activityTotalsAndReadoutsUseTheSameHalfHourIntervals() = runTest {
        val records =
            Records().apply {
                rows =
                    listOf(
                        point(
                            "s1",
                            HealthPointType.DELTA_STEP,
                            "60",
                            "2026-11-02T00:20:00Z",
                            "delta_step",
                            "steps",
                            start = "2026-11-02T00:10:00Z",
                        ),
                        point(
                            "s2",
                            HealthPointType.DELTA_STEP,
                            "120",
                            "2026-11-02T02:00:00Z",
                            "delta_step",
                            "steps",
                            start = "2026-11-02T01:00:00Z",
                        ),
                        point(
                            "c1",
                            HealthPointType.DELTA_CALORIE,
                            "6",
                            "2026-11-02T00:40:00Z",
                            "delta_calorie",
                            "kcal",
                            start = "2026-11-02T00:20:00Z",
                        ),
                        point(
                            "d1",
                            HealthPointType.DELTA_DISTANCE,
                            "20",
                            "2026-11-02T00:40:00Z",
                            "delta_distance",
                            "m",
                            start = "2026-11-02T00:20:00Z",
                        ),
                        point(
                            "e1",
                            HealthPointType.DELTA_EXERCISE,
                            "1200",
                            "2026-11-02T00:40:00Z",
                            "delta_exercise",
                            "s",
                            start = "2026-11-02T00:20:00Z",
                        ),
                        point(
                            "climb",
                            HealthPointType.CLIMBING_STAIRS,
                            "3.5",
                            "2026-11-02T00:40:00Z",
                            "climbing_stairs",
                            "m",
                            start = "2026-11-02T00:20:00Z",
                        ),
                    )
            }
        val result = load(records, date).getValue(date)
        assertEquals(listOf(60.0, 60.0, 60.0), result.day.steps.map { it.value })
        assertEquals(180, result.day.stepTotal)
        assertEquals(180, HealthRecordsLoader(records, source, ZoneOffset.UTC).loadSteps(date))
        assertTrue(result.day.steps.all { it.endExclusive != null })
        assertEquals(2, result.day.points(HealthMetric.CALORIES).size)
        result.day.points(HealthMetric.CALORIES).forEach { assertEquals(3.0, it.value, 1e-9) }
        assertEquals(2, result.day.points(HealthMetric.DISTANCE).size)
        result.day.points(HealthMetric.DISTANCE).forEach { assertEquals(10.0, it.value, 1e-9) }
        assertEquals(2, result.day.points(HealthMetric.EXERCISE).size)
        result.day.points(HealthMetric.EXERCISE).forEach { assertEquals(600.0, it.value, 1e-9) }
        assertEquals(listOf(3.5), result.day.points(HealthMetric.CLIMBING).map { it.value })
        assertEquals(
            Instant.parse("2026-11-02T00:30:00Z"),
            result.day.points(HealthMetric.CLIMBING).single().time,
        )
        assertEquals(HealthReadState.AVAILABLE, result.state(HealthMetric.ACTIVITY))
    }

    @Test
    fun crossMidnightStepsArePresentOnBothDaysAndUnknownValuesNeverBecomeZero() = runTest {
        val records =
            Records().apply {
                rows =
                    listOf(
                        point(
                            "span",
                            HealthPointType.DELTA_STEP,
                            "60",
                            "2026-11-02T00:15:00Z",
                            "delta_step",
                            "steps",
                            start = "2026-11-01T23:45:00Z",
                        ),
                        point(
                            "unknown",
                            HealthPointType.DELTA_STEP,
                            "bad",
                            "2026-11-02T05:00:00Z",
                            "delta_step",
                            "steps",
                        ),
                    )
            }
        val days = load(records, date.minusDays(1), date)
        assertEquals(30, days.getValue(date.minusDays(1)).day.stepTotal)
        assertEquals(30, days.getValue(date).day.stepTotal)
        assertEquals(1, days.getValue(date.minusDays(1)).day.activityRecords.size)
        assertEquals(
            HealthReadState.AVAILABLE,
            days.getValue(date.minusDays(1)).state(HealthMetric.ACTIVITY),
        )
        assertEquals(HealthReadState.PARTIAL, days.getValue(date).state(HealthMetric.ACTIVITY))
        assertEquals(1, days.getValue(date).day.steps.size)
    }

    @Test
    fun offWristIsObservedNonSleepButUnknownMissingAndConflictingIntervalsStayPartial() = runTest {
        val light =
            point(
                "light",
                HealthPointType.MOTION,
                "9,6",
                "2026-11-02T02:00:00Z",
                start = "2026-11-02T01:00:00Z",
                motion = HealthMotionState.LIGHT_SLEEP,
            )
        val offWrist =
            point(
                "off",
                HealthPointType.MOTION,
                "6,10",
                "2026-11-02T02:15:00Z",
                start = "2026-11-02T02:00:00Z",
                motion = HealthMotionState.OFF_WRIST,
            )
        val deep =
            point(
                    "deep",
                    HealthPointType.MOTION,
                    "10,99",
                    "2026-11-02T03:00:00Z",
                    start = "2026-11-02T02:15:00Z",
                    motion = HealthMotionState.DEEP_SLEEP,
                )
                .copy(motion = HealthMotionTransition(10, 99))
        val session =
            HealthSleepRecord(
                raw("night", HealthRecordKind.SESSION),
                "night",
                "watch",
                null,
                100,
                time("2026-11-02T01:00:00Z"),
                time("2026-11-02T03:00:00Z"),
                0,
                0,
                listOf(light, offWrist, deep),
                HealthRecordReadStatus.AVAILABLE,
                emptySet(),
            )
        val records = Records().apply { sleeps = listOf(session) }
        val complete = load(records, date).getValue(date)
        assertEquals(HealthReadState.AVAILABLE, complete.state(HealthMetric.SLEEP))
        assertEquals(105, complete.day.asleepMinutes)
        assertEquals(2, checkNotNull(complete.day.mainSleep).segments.size)
        assertEquals(
            Instant.parse("2026-11-02T02:00:00Z"),
            checkNotNull(complete.day.mainSleep).segments.first().end,
        )
        assertEquals(
            Instant.parse("2026-11-02T02:15:00Z"),
            checkNotNull(complete.day.mainSleep).segments.last().start,
        )

        records.sleeps = listOf(session.copy(relatedPoints = listOf(light, deep)))
        assertEquals(
            HealthReadState.PARTIAL,
            load(records, date).getValue(date).state(HealthMetric.SLEEP),
        )
        records.sleeps =
            listOf(
                session.copy(
                    relatedPoints =
                        listOf(light, offWrist.copy(motion = HealthMotionTransition(99, 10)), deep)
                )
            )
        assertEquals(
            HealthReadState.PARTIAL,
            load(records, date).getValue(date).state(HealthMetric.SLEEP),
        )
        val conflict =
            point(
                "conflict",
                HealthPointType.MOTION,
                "6,9",
                "2026-11-02T02:45:00Z",
                start = "2026-11-02T02:30:00Z",
                motion = HealthMotionState.OFF_WRIST,
            )
        records.sleeps = listOf(session.copy(relatedPoints = session.relatedPoints + conflict))
        val conflicted = load(records, date).getValue(date)
        assertEquals(HealthReadState.PARTIAL, conflicted.state(HealthMetric.SLEEP))
        assertEquals(90, conflicted.day.asleepMinutes)
    }

    @Test
    fun offWristFromAnotherSessionStillMasksTheNightWhileNapsStaySeparate() = runTest {
        val night =
            sleepSession(
                "night",
                "2026-11-02T01:00:00Z",
                "2026-11-02T03:00:00Z",
                HealthMotionState.LIGHT_SLEEP,
            )
        val offWrist =
            sleepSession(
                "off-wrist-observation",
                "2026-11-02T02:00:00Z",
                "2026-11-02T02:15:00Z",
                HealthMotionState.OFF_WRIST,
            )
        val nap =
            sleepSession(
                "nap",
                "2026-11-02T13:00:00Z",
                "2026-11-02T14:00:00Z",
                HealthMotionState.DEEP_SLEEP,
            )
        val records = Records().apply { sleeps = listOf(nap, offWrist, night) }
        val result = load(records, date).getValue(date)
        assertEquals(HealthReadState.PARTIAL, result.state(HealthMetric.SLEEP))
        val main = checkNotNull(result.day.mainSleep)
        assertEquals(105 * 60_000L, main.asleepMillis)
        assertEquals(Instant.parse("2026-11-02T02:00:00Z"), main.segments.first().end)
        assertEquals(Instant.parse("2026-11-02T02:15:00Z"), main.segments.last().start)
        assertEquals(60 * 60_000L, result.day.naps.single().asleepMillis)
        assertEquals(165, result.day.asleepMinutes)
        records.sleeps = listOf(night, offWrist, nap)
        assertEquals(result.day.sleeps, load(records, date).getValue(date).day.sleeps)
    }

    @Test
    fun anOldUnfinishedOrBrokenNightDoesNotMarkEveryLoadedDayPartial() = runTest {
        val session =
            HealthSleepRecord(
                raw("old", HealthRecordKind.SESSION),
                "old",
                "watch",
                null,
                100,
                time("2026-11-01T01:00:00Z"),
                0,
                0,
                0,
                emptyList(),
                HealthRecordReadStatus.AVAILABLE,
                setOf(HealthParseIssue.OPEN_SESSION),
            )
        val records = Records().apply { sleeps = listOf(session) }
        val days = load(records, date.minusDays(1), date)
        assertEquals(
            HealthReadState.PARTIAL,
            days.getValue(date.minusDays(1)).state(HealthMetric.SLEEP),
        )
        assertEquals(HealthReadState.EMPTY, days.getValue(date).state(HealthMetric.SLEEP))
        records.sleeps =
            listOf(
                session.copy(
                    startMillis = null,
                    endMillis = time("2026-11-01T08:00:00Z"),
                    issues = setOf(HealthParseIssue.MISSING_FIELD),
                )
            )
        assertEquals(
            HealthReadState.EMPTY,
            load(records, date).getValue(date).state(HealthMetric.SLEEP),
        )
    }
}
