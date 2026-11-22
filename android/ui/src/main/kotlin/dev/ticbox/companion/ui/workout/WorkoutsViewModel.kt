package dev.ticbox.companion.ui.workout

import android.util.Log
import androidx.lifecycle.viewModelScope
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.health.HealthReadState
import dev.ticbox.companion.ui.health.LocalHealthViewModel
import dev.ticbox.companion.ui.health.RecordAccess
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WorkoutsUiState(
    val hasSource: Boolean,
    val loading: Boolean = true,
    val list: WorkoutList? = null,
    val failed: Boolean = false,
    val source: WorkoutSourceStatus = WorkoutSourceStatus.UNKNOWN,
) {
    val items: List<WorkoutSummary>
        get() = list?.items.orEmpty()

    val readState: HealthReadState?
        get() = if (failed) HealthReadState.FAILED else list?.state
}

/** The workout list; shared by the health overview and the list page. */
class WorkoutsViewModel(dependencies: CompanionDependencies) : LocalHealthViewModel(dependencies) {
    private val current = MutableStateFlow(WorkoutsUiState(hasSource = source.value != null))
    val state = current.asStateFlow()

    private val sourceStatus =
        sessionState(
            current = {
                workoutSourceStatus(
                    it.services.localHealth.state.value,
                    it.services.deviceSettings.state.value,
                )
            },
            updates = { session ->
                combine(
                        session.services.localHealth.state,
                        session.services.deviceSettings.state,
                        ::Pair,
                    )
                    // A running sync has no verdict yet; the page keeps showing the last one.
                    .scan(WorkoutSourceStatus.UNKNOWN) { last, (sync, device) ->
                        if (sync.isRunning) last else workoutSourceStatus(sync, device)
                    }
                    .drop(1)
            },
        )

    init {
        viewModelScope.launch { recordAccess.collectLatest { load(it) } }
        viewModelScope.launch {
            sourceStatus.collect { status ->
                current.update { it.copy(source = status ?: WorkoutSourceStatus.UNKNOWN) }
            }
        }
    }

    private suspend fun load(access: RecordAccess?) {
        if (access == null) {
            current.update {
                it.copy(hasSource = false, loading = false, list = null, failed = false)
            }
            return
        }
        current.update { it.copy(hasSource = true, loading = true, failed = false) }
        try {
            val list = access.readWorkouts()
            current.update { it.copy(hasSource = true, loading = false, list = list) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Exception) {
            Log.w(TAG, "Reading workouts failed", problem)
            current.update { it.copy(loading = false, failed = true) }
        }
    }

    private companion object {
        const val TAG = "Workouts"
    }
}

data class WorkoutDetailUiState(
    val loading: Boolean = true,
    val detail: WorkoutDetail? = null,
    val failed: Boolean = false,
)

class WorkoutDetailViewModel(
    dependencies: CompanionDependencies,
    private val key: String,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : LocalHealthViewModel(dependencies) {
    private val current = MutableStateFlow(WorkoutDetailUiState())
    val state = current.asStateFlow()

    init {
        viewModelScope.launch { recordAccess.collectLatest { load(it) } }
    }

    private suspend fun load(access: RecordAccess?) {
        if (access == null) {
            current.value = WorkoutDetailUiState(loading = false)
            return
        }
        try {
            // Long workouts have many thousands of samples to go through.
            val detail = withContext(Dispatchers.Default) { access.readWorkout(key, zone) }
            current.value = WorkoutDetailUiState(loading = false, detail = detail)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Exception) {
            Log.w(TAG, "Reading a workout failed", problem)
            current.update { it.copy(loading = false, failed = true) }
        }
    }

    private companion object {
        const val TAG = "WorkoutDetail"
    }
}
