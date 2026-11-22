package dev.ticbox.companion.ui.workout

import dev.ticbox.companion.api.DeviceSettingsState
import dev.ticbox.companion.api.LocalHealthSyncPhase
import dev.ticbox.companion.api.LocalHealthSyncState
import dev.ticbox.companion.api.WatchInstalledApp
import dev.ticbox.companion.ui.workout.WorkoutSourceStatus.BRIDGE_NOT_INSTALLED
import dev.ticbox.companion.ui.workout.WorkoutSourceStatus.BRIDGE_UNREACHABLE
import dev.ticbox.companion.ui.workout.WorkoutSourceStatus.NO_ROOT
import dev.ticbox.companion.ui.workout.WorkoutSourceStatus.NO_WORKOUTS
import dev.ticbox.companion.ui.workout.WorkoutSourceStatus.READ_FAILED
import dev.ticbox.companion.ui.workout.WorkoutSourceStatus.UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkoutSourceStatusTest {
    private val withRoot = setOf("data_session", "data_point", "fitness_record", "fitness_point")
    private val withoutRoot = setOf("data_session", "data_point", "fitness_summary")
    private val done = LocalHealthSyncPhase.COMPLETED

    private fun apps(vararg packages: String) =
        DeviceSettingsState(apps = packages.map { WatchInstalledApp(it, null, null, null) })

    private fun status(
        reason: String? = null,
        offered: Set<String>? = null,
        phase: LocalHealthSyncPhase = done,
        device: DeviceSettingsState = DeviceSettingsState(),
    ) =
        workoutSourceStatus(
            LocalHealthSyncState(phase = phase, reason = reason, advertisedTables = offered),
            device,
        )

    @Test
    fun aMissingBridgeIsToldApartFromOneThatDoesNotAnswer() {
        val failed = "data_session:bridge_connection_failed,data_point:bridge_connection_failed"
        assertEquals(BRIDGE_NOT_INSTALLED, status(failed, device = apps("com.other")))
        assertEquals(BRIDGE_UNREACHABLE, status(failed, device = apps("dev.ticbox.companion")))
        assertEquals(BRIDGE_UNREACHABLE, status(failed)) // The list simply hasn't arrived.
    }

    @Test
    fun aHandshakeOutranksAnAppListThatHasNotCaughtUp() {
        assertEquals(NO_WORKOUTS, status(offered = withRoot, device = apps("com.other")))
    }

    @Test
    fun noRootCoversTheSwitchBeingOffAndSuBeingRefused() {
        assertEquals(NO_ROOT, status(offered = withoutRoot))
        assertEquals(NO_ROOT, status("fitness_record:root_denied", withRoot))
        assertEquals(NO_ROOT, status("fitness_record:root_unavailable", withRoot))
    }

    @Test
    fun aWorkingBridgeWithNothingToReadMeansNoWorkouts() {
        assertEquals(NO_WORKOUTS, status(offered = withRoot))
        assertEquals(NO_WORKOUTS, status("fitness_record:fitness_database_missing", withRoot))
    }

    @Test
    fun otherProblemsAndAnUnfinishedSyncAreNotReportedAsAnEmptyWatch() {
        assertEquals(READ_FAILED, status("fitness_record:snapshot_failed", withRoot))
        assertEquals(UNKNOWN, status())
        assertEquals(UNKNOWN, status(offered = withRoot, phase = LocalHealthSyncPhase.FAILED))
    }
}
