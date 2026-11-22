package dev.ticbox.companion.ui.health

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ActivityBucketsTest {
    private val start = Instant.parse("2026-01-02T00:00:00Z")
    private val end = start.plus(Duration.ofDays(1))

    private fun at(minutes: Long) = start.plusSeconds(minutes * 60)

    private fun interval(from: Long, to: Long, value: Double) = HealthPoint(at(from), value, at(to))

    @Test
    fun minuteReadingsShareOneHalfHourAndMissingBucketsAreNotInvented() {
        val readings = listOf(interval(2, 3, 5.0), interval(3, 4, 12.0), interval(10, 11, 8.0))
        val points = activityBuckets(readings, start, end)
        assertEquals(listOf(HealthPoint(start, 25.0, at(30))), points)
        assertEquals(at(15), points.single().plotTime)
    }

    @Test
    fun intervalBoundariesAndLongIntervalsPreserveContributionsWithoutPerBucketRounding() {
        val readings = listOf(interval(15, 45, 3.0), interval(60, 180, 240.0))
        val points = activityBuckets(readings, start, end)
        assertEquals(listOf(1.5, 1.5, 60.0, 60.0, 60.0, 60.0), points.map { it.value })
        assertEquals(243.0, points.sumOf { it.value }, 1e-9)
        assertEquals(listOf(0L, 30L, 60L, 90L, 120L, 150L).map(::at), points.map { it.time })
        assertTrue(points.all { Duration.between(it.time, it.endExclusive).toMinutes() == 30L })
    }

    @Test
    fun midnightSplitsTheSameReadingAcrossDaysWithoutMovingItAllToItsEndDate() {
        val reading = interval(23 * 60 + 45, 24 * 60 + 15, 60.0)
        val before = activityBuckets(listOf(reading), start, end)
        val after = activityBuckets(listOf(reading), end, end.plus(Duration.ofDays(1)))
        assertEquals(listOf(HealthPoint(at(23 * 60 + 30), 30.0, end)), before)
        assertEquals(listOf(HealthPoint(end, 30.0, at(24 * 60 + 30))), after)
        assertEquals(reading.value, (before + after).sumOf { it.value }, 1e-9)
    }

    @Test
    fun springAndAutumnCalendarDaysKeepTheirActualElapsedDuration() {
        val zone = ZoneId.of("America/New_York")
        listOf(LocalDate.of(2026, 3, 8) to 46, LocalDate.of(2026, 11, 1) to 50).forEach {
            (date, count) ->
            val a = date.atStartOfDay(zone).toInstant()
            val b = date.plusDays(1).atStartOfDay(zone).toInstant()
            val points = activityBuckets(listOf(HealthPoint(a, count * 50.0, b)), a, b)
            assertEquals(count, points.size)
            points.forEach { assertEquals(50.0, it.value, 1e-9) }
            assertEquals(count * 50.0, points.sumOf { it.value }, 1e-9)
            assertEquals(a, points.first().time)
            assertEquals(b, points.last().endExclusive)
            if (count == 46) assertTrue(points.none { it.time.atZone(zone).hour == 2 })
            else assertEquals(4, points.count { it.time.atZone(zone).hour == 1 })
        }
    }

    @Test
    fun veryLongIntervalsAreClippedBeforeBucketing() {
        val reading =
            HealthPoint(start.minus(Duration.ofDays(365)), 367 * 24.0, end.plus(Duration.ofDays(1)))
        val points = activityBuckets(listOf(reading), start, end)
        assertEquals(48, points.size)
        assertEquals(24.0, points.sumOf { it.value }, 1e-9)
        points.forEach { assertEquals(0.5, it.value, 1e-9) }
    }

    @Test
    fun overlappingSourceIntervalsUseTheLargerContributionInsteadOfDoubleCounting() {
        val readings =
            listOf(interval(0, 60, 100.0), interval(0, 30, 100.0), interval(0, 60, 100.0))
        val expected = listOf(HealthPoint(start, 100.0, at(30)), HealthPoint(at(30), 50.0, at(60)))
        assertEquals(expected, activityBuckets(readings, start, end))
        assertEquals(expected, activityBuckets(readings.reversed(), start, end))
    }

    @Test
    fun timestampReadingsUseHalfOpenBucketsAndKnownZeroIsDifferentFromNoReading() {
        val readings =
            listOf(
                HealthPoint(start, 0.0),
                HealthPoint(at(30), 2.5),
                HealthPoint(at(31), 1.5),
                HealthPoint(end, 99.0),
            )
        assertEquals(
            listOf(HealthPoint(start, 0.0, at(30)), HealthPoint(at(30), 4.0, at(60))),
            activityBuckets(readings, start, end),
        )
        assertTrue(activityBuckets(emptyList(), start, end).isEmpty())
    }
}
