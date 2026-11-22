package dev.ticbox.companion.ui.health

import androidx.lifecycle.viewModelScope
import dev.ticbox.companion.api.ConnectionPhase
import dev.ticbox.companion.api.HealthRecordSource
import dev.ticbox.companion.api.LocalHealthSyncMode
import dev.ticbox.companion.api.LocalHealthSyncPhase
import dev.ticbox.companion.api.LocalHealthSyncState
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.session.WatchSession
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface HealthSyncStatus {
    data object NoWatch : HealthSyncStatus

    data object Disconnected : HealthSyncStatus

    data class Syncing(val records: Int) : HealthSyncStatus

    data class Synced(val atMillis: Long?) : HealthSyncStatus

    data object Failed : HealthSyncStatus
}

data class HealthUiState(
    val date: LocalDate,
    val today: LocalDate,
    val hasSource: Boolean = false,
    val days: Map<LocalDate, HealthDayResult> = emptyMap(),
    val loading: Set<LocalDate> = emptySet(),
    val sync: HealthSyncStatus = HealthSyncStatus.NoWatch,
    /** With empty metrics hidden, the ones known to have something to show. */
    val onlyMetrics: Set<HealthMetric>? = null,
    /** Likewise for logs. */
    val onlyLogs: Set<HealthLog>? = null,
    /** The first sync left older details on the watch; null while a full sync brings them over. */
    val deferredBeforeMillis: Long? = null,
) {
    val earliest: LocalDate
        get() = today.minusYears(10)

    /** The day by the calendar, as a week adds it up. */
    fun result(date: LocalDate): HealthDayResult? = days[date]

    /** The day as its own page shows it, which for today is partly the past 24 hours. */
    fun dayView(date: LocalDate): HealthDayResult? =
        days[date]?.let { if (date == today) it.rolled() else it.copy(recent = null) }

    /** A metric only goes once known to be empty, so a failed read or a day's data keeps it. */
    fun shows(metric: HealthMetric): Boolean {
        val only = onlyMetrics ?: return true
        if (metric in only) return true
        val view = dayView(date) ?: return false
        return view.day.hasData(metric) || view.state(metric) != HealthReadState.EMPTY
    }

    fun shows(log: HealthLog): Boolean = onlyLogs?.contains(log) ?: true

    fun isLoading(date: LocalDate) = date in loading && date !in days
}

/** Shared by the health overview and detail pages (scoped to the health tab). */
class HealthViewModel(
    dependencies: CompanionDependencies,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : LocalHealthViewModel(dependencies) {
    private val selectedDate = MutableStateFlow(today())
    private val days = MutableStateFlow<Map<LocalDate, HealthDayResult>>(emptyMap())
    private val loading = MutableStateFlow<Set<LocalDate>>(emptySet())
    private val loads = Mutex()
    private val recorded = MutableStateFlow<Recorded?>(null)

    /** Chosen in this app; otherwise the watch's own goal applies. */
    val chosenStepGoal: StateFlow<Int?> =
        sessionState(
            current = { dependencies.preferences.stepGoalFor(it).value },
            updates = dependencies.preferences::stepGoalFor,
        )

    private val syncState: StateFlow<Pair<LocalHealthSyncState, Boolean>?> =
        sessionState(
            current = { it.services.localHealth.state.value to it.linkReady() },
            updates = { session ->
                combine(session.services.localHealth.state, session.client.state) { sync, watch ->
                    sync to (watch.connection.phase == ConnectionPhase.LINK_READY)
                }
            },
        )

    val state: StateFlow<HealthUiState> =
        combine(selectedDate, days, loading, source, syncState) { date, days, loading, source, sync
                ->
                HealthUiState(
                    date,
                    today(),
                    source != null,
                    days,
                    loading,
                    syncStatus(source, sync),
                    deferredBeforeMillis = deferredBefore(source, sync),
                )
            }
            .combine(
                combine(dependencies.preferences.hideEmptyHealthMetrics, recorded) { hide, known ->
                    if (hide) known ?: Recorded(emptySet(), emptySet()) else null
                }
            ) { state, only ->
                state.copy(onlyMetrics = only?.metrics, onlyLogs = only?.logs)
            }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                HealthUiState(
                    date = today(),
                    today = today(),
                    hasSource = source.value != null,
                    sync = syncStatus(source.value, syncState.value),
                    deferredBeforeMillis = deferredBefore(source.value, syncState.value),
                    onlyMetrics =
                        emptySet<HealthMetric>().takeIf {
                            dependencies.preferences.hideEmptyHealthMetrics.value
                        },
                    onlyLogs =
                        emptySet<HealthLog>().takeIf {
                            dependencies.preferences.hideEmptyHealthMetrics.value
                        },
                ),
            )

    val date: StateFlow<LocalDate> = selectedDate.asStateFlow()

    init {
        viewModelScope.launch {
            source.collect { current ->
                days.value = emptyMap()
                recorded.value = null
                if (current != null) {
                    launch { loadRecorded() }
                    load(listOf(selectedDate.value))
                }
            }
        }
        // A completed watch sync may have added rows; refresh what is on screen.
        viewModelScope.launch {
            syncState
                .map { it?.first?.lastSuccessAtMillis }
                .distinctUntilChanged()
                .drop(1)
                .filterNotNull()
                .collect { reload() }
        }
    }

    fun selectDate(date: LocalDate) {
        val today = today()
        val clamped = if (date > today) today else date
        selectedDate.value = clamped
        ensureLoaded(listOf(clamped))
    }

    /** Loads days that are not cached yet (e.g. the week shown by a detail page). */
    fun ensureLoaded(dates: List<LocalDate>) {
        val missing = dates.filter { it !in days.value && it !in loading.value && it <= today() }
        if (missing.isNotEmpty()) viewModelScope.launch { load(missing) }
    }

    /** Pulls new records from the watch; the overview reloads when the sync completes. */
    fun syncFromWatch() {
        command(SYNC) { session ->
            session.services.localHealth.sync()
            null
        }
    }

    /** Also brings over what the first sync left on the watch. */
    fun syncEverything() {
        command(SYNC) { session ->
            session.services.localHealth.sync(LocalHealthSyncMode.FULL)
            null
        }
    }

    /** The past 24 hours keep moving; coming back to a page catches up with them. */
    fun refreshRecent() {
        val today = today()
        val until = days.value[today]?.recent?.until ?: return
        if (Duration.between(until, Instant.now()) < RECENT_FOR) return
        viewModelScope.launch { load(listOf(today)) }
    }

    private fun reload() {
        viewModelScope.launch { loadRecorded() }
        val cached = days.value.keys.toList()
        if (cached.isNotEmpty()) viewModelScope.launch { load(cached) }
    }

    private suspend fun loadRecorded() {
        val current = source.value ?: return
        val records = session.value?.services?.healthRecords ?: return
        val loader = HealthRecordsLoader(records, current, zone)
        val known = Recorded(loader.recordedMetrics(), loader.recordedLogs())
        if (source.value == current) recorded.value = known
    }

    private data class Recorded(val metrics: Set<HealthMetric>, val logs: Set<HealthLog>)

    private suspend fun load(dates: List<LocalDate>) {
        val current = source.value ?: return
        val records = session.value?.services?.healthRecords ?: return
        loading.update { it + dates }
        try {
            loads.withLock {
                val results = HealthRecordsLoader(records, current, zone).load(dates)
                if (source.value != current) return
                days.update { cached ->
                    val merged = results.mapValues { (date, result) ->
                        keepOnFailure(cached[date], result)
                    }
                    evict(cached + merged)
                }
            }
        } finally {
            loading.update { it - dates.toSet() }
        }
    }

    /** A failed read keeps the last good values for that metric. */
    private fun keepOnFailure(previous: HealthDayResult?, next: HealthDayResult): HealthDayResult {
        if (previous == null) return next
        var day = next.day
        HealthMetric.entries
            .filter { next.state(it) == HealthReadState.FAILED }
            .forEach { metric ->
                day =
                    when (metric) {
                        HealthMetric.SLEEP -> day.copy(sleeps = previous.day.sleeps)
                        else ->
                            day.copy(
                                series = day.series + (metric to previous.day.points(metric)),
                                activityRecords =
                                    if (metric == HealthMetric.ACTIVITY)
                                        previous.day.activityRecords
                                    else day.activityRecords,
                            )
                    }
            }
        val recent =
            next.recent?.let { fresh ->
                val failed = fresh.states.filterValues { it == HealthReadState.FAILED }.keys
                val kept = failed.associateWith { previous.recent?.series?.get(it).orEmpty() }
                fresh.copy(series = fresh.series + kept)
            }
        return next.copy(day = day, recent = recent)
    }

    private fun evict(cached: Map<LocalDate, HealthDayResult>): Map<LocalDate, HealthDayResult> {
        if (cached.size <= MAX_CACHED_DAYS) return cached
        val anchor = selectedDate.value
        return cached.entries
            .sortedBy { abs(it.key.toEpochDay() - anchor.toEpochDay()) }
            .take(MAX_CACHED_DAYS)
            .associate { it.key to it.value }
    }

    private fun today(): LocalDate = LocalDate.now(zone)

    private fun WatchSession.linkReady() =
        client.state.value.connection.phase == ConnectionPhase.LINK_READY

    private fun syncStatus(
        source: HealthRecordSource.LocalDevice?,
        sync: Pair<LocalHealthSyncState, Boolean>?,
    ): HealthSyncStatus {
        if (source == null || sync == null) return HealthSyncStatus.NoWatch
        val (state, linkReady) = sync
        return when (state.phase) {
            LocalHealthSyncPhase.CONNECTING,
            LocalHealthSyncPhase.READING,
            LocalHealthSyncPhase.RETRYING -> HealthSyncStatus.Syncing(state.recordsCommitted)
            LocalHealthSyncPhase.FAILED,
            LocalHealthSyncPhase.UNAVAILABLE -> HealthSyncStatus.Failed
            else ->
                if (!linkReady && state.lastSuccessAtMillis == null) HealthSyncStatus.Disconnected
                else HealthSyncStatus.Synced(state.lastSuccessAtMillis)
        }
    }

    private fun deferredBefore(
        source: HealthRecordSource.LocalDevice?,
        sync: Pair<LocalHealthSyncState, Boolean>?,
    ): Long? {
        val state = sync?.first?.takeIf { source != null } ?: return null
        val running =
            state.phase == LocalHealthSyncPhase.CONNECTING ||
                state.phase == LocalHealthSyncPhase.READING ||
                state.phase == LocalHealthSyncPhase.RETRYING
        val full = running && state.mode == LocalHealthSyncMode.FULL
        return state.deferredBeforeMillis.takeUnless { full }
    }

    companion object {
        const val SYNC = "health_sync"
        private const val MAX_CACHED_DAYS = 45
        private val RECENT_FOR = Duration.ofMinutes(1)
    }
}
