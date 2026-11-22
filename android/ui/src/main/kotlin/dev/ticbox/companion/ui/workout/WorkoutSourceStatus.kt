package dev.ticbox.companion.ui.workout

import androidx.annotation.StringRes
import dev.ticbox.companion.api.DeviceSettingsState
import dev.ticbox.companion.api.LocalHealthSyncPhase
import dev.ticbox.companion.api.LocalHealthSyncState
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.bridge.localBridgeMissing
import dev.ticbox.companion.ui.bridge.problems

/** Why the workout list can be empty, which decides what the user is told to do about it. */
enum class WorkoutSourceStatus(@StringRes val title: Int?, @StringRes val body: Int?) {
    /** Nothing conclusive yet, such as before the first sync. */
    UNKNOWN(null, null),
    BRIDGE_NOT_INSTALLED(R.string.workouts_no_bridge_title, R.string.workouts_no_bridge_body),

    /** The bridge didn't answer, and the watch's app list can't say whether it's installed. */
    BRIDGE_UNREACHABLE(R.string.workouts_unreachable_title, R.string.workouts_unreachable_body),
    NO_ROOT(R.string.workouts_no_root_title, R.string.workouts_no_root_body),
    NO_WORKOUTS(R.string.workouts_none_on_watch_title, R.string.workouts_none_on_watch_body),
    READ_FAILED(R.string.workouts_failed_title, R.string.workouts_failed_body);

    /** The Local Bridge page can fix these, unlike a watch with no workouts on it. */
    val ledByLocalBridgePage: Boolean
        get() = this == BRIDGE_NOT_INSTALLED || this == BRIDGE_UNREACHABLE
}

internal val LocalHealthSyncState.isRunning: Boolean
    get() =
        phase == LocalHealthSyncPhase.CONNECTING ||
            phase == LocalHealthSyncPhase.READING ||
            phase == LocalHealthSyncPhase.RETRYING

internal fun workoutSourceStatus(
    sync: LocalHealthSyncState,
    device: DeviceSettingsState,
): WorkoutSourceStatus {
    if (localBridgeMissing(sync, device)) return WorkoutSourceStatus.BRIDGE_NOT_INSTALLED
    val offered = sync.advertisedTables
    val problems = sync.problems()
    val fitness = problems[FITNESS_TABLE]
    return when {
        problems.values.any { it in CONNECTION_PROBLEMS } -> WorkoutSourceStatus.BRIDGE_UNREACHABLE
        fitness in ROOT_PROBLEMS -> WorkoutSourceStatus.NO_ROOT
        // Still reported by a watch running an older bridge.
        fitness == "fitness_database_missing" -> WorkoutSourceStatus.NO_WORKOUTS
        fitness != null -> WorkoutSourceStatus.READ_FAILED
        offered == null -> WorkoutSourceStatus.UNKNOWN
        // Without root the watch doesn't offer the private workout tables at all.
        FITNESS_TABLE !in offered -> WorkoutSourceStatus.NO_ROOT
        sync.phase == LocalHealthSyncPhase.COMPLETED ||
            sync.phase == LocalHealthSyncPhase.PARTIAL -> WorkoutSourceStatus.NO_WORKOUTS
        else -> WorkoutSourceStatus.UNKNOWN
    }
}

private const val FITNESS_TABLE = "fitness_record"
private val CONNECTION_PROBLEMS =
    setOf("bridge_connection_failed", "bridge_timeout", "bridge_disabled")
private val ROOT_PROBLEMS = setOf("root_not_enabled", "root_denied", "root_unavailable")
