package dev.ticbox.companion.ui.home

import dev.ticbox.companion.api.StepSnapshot
import dev.ticbox.companion.api.WatchObservation
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class HomeStepsTest {
    private val today = LocalDate.of(2026, 11, 4)

    private fun reported(steps: Int, on: LocalDate = today, stale: Boolean = false) =
        WatchObservation(
            StepSnapshot(steps, 8_000),
            on.atTime(9, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
            stale,
        )

    private fun steps(
        stored: Int?,
        report: WatchObservation<StepSnapshot>?,
        linkReady: Boolean = true,
    ) = homeSteps(stored, report, linkReady, today, ZoneOffset.UTC)

    @Test
    fun storedRecordsWinOverAReportThatStayedBehind() {
        assertEquals(HomeSteps(96, lastKnown = false), steps(96, reported(0)))
    }

    @Test
    fun storedRecordsAreLastKnownWhileTheWatchIsAway() {
        assertEquals(HomeSteps(96, lastKnown = true), steps(96, reported(0), linkReady = false))
    }

    @Test
    fun aReportFromTodayFillsInWhileNothingIsStored() {
        assertEquals(HomeSteps(40, lastKnown = true), steps(null, reported(40, stale = true)))
    }

    @Test
    fun aReportFromAnotherDayIsNotTodaysCount() {
        assertNull(steps(null, reported(9_000, on = today.minusDays(1))))
        assertNull(steps(null, null))
    }
}
