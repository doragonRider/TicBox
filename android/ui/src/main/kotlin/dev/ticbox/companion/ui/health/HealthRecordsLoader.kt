package dev.ticbox.companion.ui.health

import android.util.Log
import dev.ticbox.companion.api.HealthMotionState
import dev.ticbox.companion.api.HealthPointQuery
import dev.ticbox.companion.api.HealthPointRecord
import dev.ticbox.companion.api.HealthPointType
import dev.ticbox.companion.api.HealthRecordReadStatus
import dev.ticbox.companion.api.HealthRecordSource
import dev.ticbox.companion.api.HealthRecordsClient
import dev.ticbox.companion.api.HealthSleepQuery
import dev.ticbox.companion.api.HealthSleepRecord
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap
import kotlinx.coroutines.CancellationException

/**
 * Reads backend-parsed local records and turns them into chartable days. Only values whose semantic
 * and unit the backend confirmed are charted; anything else marks the day as partial. Daily
 * statistics (totals, averages, sleep stages) are a presentation concern and live here.
 */
class HealthRecordsLoader(
    private val records: HealthRecordsClient,
    private val source: HealthRecordSource.LocalDevice,
    private val zone: ZoneId,
    private val clock: () -> Instant = Instant::now,
) {
    suspend fun load(dates: Collection<LocalDate>): Map<LocalDate, HealthDayResult> {
        if (dates.isEmpty()) return emptyMap()
        val first = dates.min()
        val last = dates.max()
        val from = first.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val now = clock()
        val today = now.atZone(zone).toLocalDate()
        val dayAgo = now.minus(Duration.ofDays(1))
        // One failing table must not hide the others.
        val series =
            HealthMetric.entries
                .mapNotNull { metric -> metric.field?.let { metric to it } }
                .associate { (metric, field) ->
                    val rolling = today in dates && metric in HealthMetric.pastDay
                    val oldest = if (rolling) minOf(from, dayAgo.toEpochMilli()) else from
                    metric to (field to read { points(field.type, oldest, to) })
                }
        val stepCounts = read { points(HealthPointType.STEP_COUNT, from, to) }
        val sleep = read { sleepSessions(from, to) }
        return dates.associateWith { date ->
            val start = date.atStartOfDay(zone).toInstant()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant()
            val charted = series.mapValues { (_, read) ->
                val (field, rows) = read
                numeric(rows, start, end, field)
            }
            val stepPoints = charted.getValue(HealthMetric.ACTIVITY).first
            val stepState = charted.getValue(HealthMetric.ACTIVITY).second
            val stepDeltas = series.getValue(HealthMetric.ACTIVITY).second
            val activityRows =
                (stepCounts?.items.orEmpty() + stepDeltas?.items.orEmpty())
                    .distinctBy { it.raw.key }
                    .filter { it.isCurrent() && it.overlaps(start, end) }
            val (sleeps, sleepState) = sleepDay(sleep, date)
            HealthDayResult(
                HealthDay(date, charted.mapValues { it.value.first }, sleeps, activityRows),
                charted.mapValues { it.value.second } +
                    mapOf(
                        HealthMetric.ACTIVITY to
                            if (activityRows.isNotEmpty() && stepPoints.isEmpty())
                                HealthReadState.PARTIAL
                            else stepState,
                        HealthMetric.SLEEP to sleepState,
                    ),
                recent =
                    if (date != today) null
                    else {
                        // To the end of the day, not to now: a watch's clock can run a little
                        // ahead.
                        val read =
                            HealthMetric.pastDay.associateWith { metric ->
                                val (field, rows) = series.getValue(metric)
                                numeric(rows, dayAgo, end, field)
                            }
                        RecentSamples(
                            now,
                            read.mapValues { it.value.first },
                            read.mapValues { it.value.second },
                        )
                    },
            )
        }
    }

    /**
     * What this watch has ever recorded. A read that fails counts as recorded: leaving a metric out
     * takes knowing there is nothing to show.
     */
    suspend fun recordedMetrics(): Set<HealthMetric> =
        HealthMetric.entries.filterTo(mutableSetOf()) { metric ->
            val field = metric.field
            if (field == null) hasSleep()
            else {
                hasPoints(field.type) ||
                    (metric == HealthMetric.ACTIVITY && hasPoints(HealthPointType.STEP_COUNT))
            }
        }

    /** Logs with something to list over the window their page shows, on the same terms. */
    suspend fun recordedLogs(): Set<HealthLog> {
        val from = clock().minus(Duration.ofDays(HealthLog.WINDOW_DAYS)).toEpochMilli()
        return HealthLog.entries.filterTo(mutableSetOf()) { log ->
            log.types.any { hasPoints(it, from) }
        }
    }

    private suspend fun hasPoints(type: HealthPointType, from: Long? = null): Boolean {
        val query = HealthPointQuery(source, fromMillis = from, typeCode = type.code, limit = 1)
        val page = read { records.points(query) } ?: return true
        return page.status != HealthRecordReadStatus.AVAILABLE ||
            page.nextOffset != null ||
            page.items.any { it.isCurrent() }
    }

    private suspend fun hasSleep(): Boolean {
        val page = read { records.sleepRecords(HealthSleepQuery(source, limit = 1)) } ?: return true
        return page.status != HealthRecordReadStatus.AVAILABLE ||
            page.nextOffset != null ||
            page.items.any { it.raw.source == source && !it.raw.deleted && it.deleted != true }
    }

    /** The day's step total, as [load] works it out. Null if there is none to show. */
    suspend fun loadSteps(date: LocalDate): Int? {
        val field = checkNotNull(HealthMetric.ACTIVITY.field)
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        val rows = read { points(field.type, start.toEpochMilli(), end.toEpochMilli() - 1) }
        val (points, _) = numeric(rows, start, end, field)
        return if (points.isEmpty()) null
        else HealthDay(date, mapOf(HealthMetric.ACTIVITY to points)).stepTotal
    }

    private suspend fun <T> read(block: suspend () -> T): T? =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Exception) {
            Log.w(TAG, "Reading local health records failed", problem)
            null
        }

    private suspend fun points(type: HealthPointType, from: Long, to: Long) =
        records.pointRows(source, type, from, to, MAX_POINT_PAGES)

    private suspend fun sleepSessions(from: Long, to: Long): Rows<HealthSleepRecord> {
        val rows = linkedMapOf<String, HealthSleepRecord>()
        var offset = 0
        repeat(MAX_SLEEP_PAGES) {
            val page =
                records.sleepRecords(
                    HealthSleepQuery(source, from, to, limit = 50, offset = offset)
                )
            page.items.forEach { rows[it.raw.key] = it }
            if (page.status != HealthRecordReadStatus.AVAILABLE) {
                return Rows(rows.values.toList(), available = false, limited = false)
            }
            val next = page.nextOffset ?: return Rows(rows.values.toList(), true, limited = false)
            if (next <= offset) return Rows(rows.values.toList(), true, limited = true)
            offset = next
        }
        return Rows(rows.values.toList(), available = true, limited = true)
    }

    private fun numeric(
        rows: Rows<HealthPointRecord>?,
        start: Instant,
        end: Instant,
        field: MetricField,
    ): Pair<List<HealthPoint>, HealthReadState> {
        if (rows == null) return emptyList<HealthPoint>() to HealthReadState.FAILED
        var partial = rows.limited
        val intervalIncrements = field.type in INTERVAL_INCREMENTS
        val bucketed = intervalIncrements || field.type == HealthPointType.CLIMBING_STAIRS
        val readings =
            rows.items
                .filter { it.isCurrent() }
                .mapNotNull { record ->
                    val time = record.endMillis?.let(Instant::ofEpochMilli)
                    if (time == null) {
                        partial = true
                        return@mapNotNull null
                    }
                    val begin = record.startMillis?.let(Instant::ofEpochMilli)
                    if (intervalIncrements && begin != null && begin < time) {
                        if (begin >= end || time <= start) return@mapNotNull null
                    } else if (time < start || time >= end) return@mapNotNull null
                    if (intervalIncrements && (begin == null || begin > time)) {
                        partial = true
                        return@mapNotNull null
                    }
                    val number = record.confirmedValue(field)
                    if (number == null || (bucketed && number < 0)) {
                        partial = true
                        return@mapNotNull null
                    }
                    if (record.issues.isNotEmpty()) partial = true
                    if (intervalIncrements && begin != null && begin < time)
                        HealthPoint(begin, number, endExclusive = time)
                    else HealthPoint(time, number)
                }
                .sortedBy { it.time }
        val points = if (bucketed) activityBuckets(readings, start, end) else readings
        val state =
            when {
                !rows.available ->
                    if (points.isEmpty()) HealthReadState.UNAVAILABLE else HealthReadState.PARTIAL
                partial -> HealthReadState.PARTIAL
                points.isEmpty() -> HealthReadState.EMPTY
                else -> HealthReadState.AVAILABLE
            }
        return points to state
    }

    private fun sleepDay(
        rows: Rows<HealthSleepRecord>?,
        date: LocalDate,
    ): Pair<List<SleepPeriod>, HealthReadState> {
        if (rows == null) return emptyList<SleepPeriod>() to HealthReadState.FAILED
        var partial = rows.limited
        val sessions = mutableListOf<ObservedSleepPeriod>()
        rows.items.forEach { session ->
            if (session.raw.source != source || session.raw.deleted || session.deleted == true) {
                return@forEach
            }
            val begin = session.startMillis?.let(Instant::ofEpochMilli)
            val finish = session.endMillis?.takeIf { it > 0 }?.let(Instant::ofEpochMilli)
            // A completed night belongs to its end date. An unresolved row with a known start
            // affects that date only; it must not make every requested day look incomplete.
            val knownDate = (finish ?: begin)?.atZone(zone)?.toLocalDate()
            if (knownDate != null && knownDate != date) return@forEach
            if (begin == null || finish == null || finish <= begin) {
                partial = true
                return@forEach
            }
            if (
                session.issues.isNotEmpty() ||
                    session.relatedPointsStatus != HealthRecordReadStatus.AVAILABLE
            )
                partial = true
            val intervals = mutableListOf<ObservedSleepInterval>()
            val coverage = mutableListOf<ObservedSleepInterval>()
            session.motionIntervals.forEach motion@{ point ->
                if (!point.isCurrent()) return@motion
                val a = point.startMillis?.let(Instant::ofEpochMilli)
                val b = point.endMillis?.let(Instant::ofEpochMilli)
                if (a == null || b == null || b <= a) {
                    partial = true
                    return@motion
                }
                val clippedStart = maxOf(a, begin)
                val clippedEnd = minOf(b, finish)
                if (clippedEnd <= clippedStart) return@motion
                val state = point.motion?.state
                val interval =
                    ObservedSleepInterval(state?.toSleepStage(), clippedStart, clippedEnd)
                intervals += interval
                if (
                    state == null ||
                        state == HealthMotionState.UNKNOWN ||
                        state == HealthMotionState.UNDEFINED
                ) {
                    partial = true
                } else {
                    // Off-wrist and other recognized non-sleep observations cover time but do
                    // not become sleep or awake. Their blank spans are intentional, not lost data.
                    coverage += interval
                }
                if (point.issues.isNotEmpty()) partial = true
            }
            if (!covers(coverage, begin, finish)) partial = true
            sessions += ObservedSleepPeriod(begin, finish, intervals)
        }
        val (sleeps, conflicting) = observedSleepPeriods(sessions)
        val state =
            when {
                !rows.available ->
                    if (sleeps.isEmpty()) HealthReadState.UNAVAILABLE else HealthReadState.PARTIAL
                partial || conflicting -> HealthReadState.PARTIAL
                sleeps.isEmpty() -> HealthReadState.EMPTY
                else -> HealthReadState.AVAILABLE
            }
        return sleeps to state
    }

    private fun HealthPointRecord.isCurrent() =
        raw.source == source && !raw.deleted && deleted != true

    private fun HealthPointRecord.overlaps(start: Instant, end: Instant): Boolean {
        val finish = endMillis?.let(Instant::ofEpochMilli) ?: return false
        val begin = startMillis?.let(Instant::ofEpochMilli) ?: finish
        return if (begin < finish) begin < end && finish > start
        else finish >= start && finish < end
    }

    private companion object {
        const val TAG = "HealthRecordsLoader"
        const val MAX_POINT_PAGES = 40
        const val MAX_SLEEP_PAGES = 6
        val INTERVAL_INCREMENTS =
            setOf(
                HealthPointType.DELTA_STEP,
                HealthPointType.DELTA_CALORIE,
                HealthPointType.DELTA_DISTANCE,
                HealthPointType.DELTA_EXERCISE,
            )
    }
}

/** Rows read from the store: [available] is false without a source, [limited] if cut short. */
internal class Rows<T>(val items: List<T>, val available: Boolean, val limited: Boolean)

/** Reads every page of one point type, up to a limit that keeps a page from loading forever. */
internal suspend fun HealthRecordsClient.pointRows(
    source: HealthRecordSource.LocalDevice,
    type: HealthPointType,
    from: Long?,
    to: Long?,
    maxPages: Int,
): Rows<HealthPointRecord> {
    val rows = linkedMapOf<String, HealthPointRecord>()
    var offset = 0
    repeat(maxPages) {
        val page =
            points(HealthPointQuery(source, from, to, type.code, limit = 500, offset = offset))
        page.items.forEach { rows[it.raw.key] = it }
        if (page.status != HealthRecordReadStatus.AVAILABLE) {
            return Rows(rows.values.toList(), available = false, limited = false)
        }
        val next = page.nextOffset ?: return Rows(rows.values.toList(), true, limited = false)
        if (next <= offset) return Rows(rows.values.toList(), true, limited = true)
        offset = next
    }
    return Rows(rows.values.toList(), available = true, limited = true)
}

/**
 * The row's value for [field], or null unless the backend confirmed its meaning and unit. A row may
 * hold several slots of one field (noise); their average is a display value.
 */
private fun HealthPointRecord.confirmedValue(field: MetricField): Double? {
    val slots = values.filter { it.semantic == field.semantic && it.unit == field.unit }
    val numbers = slots.map { slot ->
        slot.decimal?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    }
    return if (numbers.isEmpty()) null else numbers.average()
}

private fun HealthMotionState.toSleepStage(): SleepStage? =
    when (this) {
        HealthMotionState.AWAKE -> SleepStage.AWAKE
        HealthMotionState.REM -> SleepStage.REM
        HealthMotionState.LIGHT_SLEEP -> SleepStage.LIGHT
        HealthMotionState.DEEP_SLEEP -> SleepStage.DEEP
        else -> null
    }

private data class ObservedSleepInterval(
    val stage: SleepStage?,
    val start: Instant,
    val end: Instant,
)

private data class ObservedSleepPeriod(
    val start: Instant,
    val end: Instant,
    val intervals: List<ObservedSleepInterval>,
)

private fun covers(
    intervals: List<ObservedSleepInterval>,
    begin: Instant,
    finish: Instant,
): Boolean {
    var coveredUntil = begin
    for (interval in intervals.sortedBy { it.start }) {
        if (interval.start > coveredUntil) return false
        coveredUntil = maxOf(coveredUntil, interval.end)
    }
    return coveredUntil >= finish
}

/**
 * Sessions as sleeps. Ones that overlap or are under [SleepGap] apart are one sleep: a night the
 * watch split in two, or the same night stored twice. The longest sleep comes first as the main
 * one, and the rest, naps, follow in time order. The flag is true if stages conflict anywhere.
 */
internal fun sleepPeriods(sessions: List<SleepPeriod>): Pair<List<SleepPeriod>, Boolean> =
    observedSleepPeriods(
        sessions.map { session ->
            ObservedSleepPeriod(
                session.start,
                session.end,
                session.segments.map { ObservedSleepInterval(it.stage, it.start, it.end) },
            )
        }
    )

/** Keep non-sleep observations until overlapping sessions have been reconciled. */
private fun observedSleepPeriods(
    sessions: List<ObservedSleepPeriod>
): Pair<List<SleepPeriod>, Boolean> {
    val groups = mutableListOf<MutableList<ObservedSleepPeriod>>()
    sessions
        .sortedBy { it.start }
        .forEach { session ->
            val group = groups.lastOrNull()
            if (group != null && session.start <= group.maxOf { it.end } + SleepGap) {
                group += session
            } else {
                groups += mutableListOf(session)
            }
        }
    var conflicting = false
    val sleeps = groups.mapNotNull { group ->
        val (segments, clash) =
            mergeObservedSleepIntervals(group.flatMap { it.intervals }.distinct())
        if (clash) conflicting = true
        if (segments.isEmpty()) null
        else SleepPeriod(group.minOf { it.start }, group.maxOf { it.end }, segments)
    }
    val main =
        sleeps.maxByOrNull { it.asleepMillis } ?: return emptyList<SleepPeriod>() to conflicting
    return listOf(main) + sleeps.filter { it !== main } to conflicting
}

private val SleepGap = Duration.ofHours(1)

/** Sweeps intervals: same-stage overlaps merge; overlapping different stages stay unclassified. */
internal fun mergeSleepIntervals(
    candidates: List<SleepSegment>
): Pair<List<SleepSegment>, Boolean> =
    mergeObservedSleepIntervals(
        candidates.map { ObservedSleepInterval(it.stage, it.start, it.end) }
    )

/** A non-sleep/unknown observation masks an overlapping sleep claim instead of adding duration. */
private fun mergeObservedSleepIntervals(
    candidates: List<ObservedSleepInterval>
): Pair<List<SleepSegment>, Boolean> {
    val events = TreeMap<Instant, IntArray>()
    candidates.forEach { interval ->
        events
            .getOrPut(interval.start) { IntArray(SleepStage.entries.size + 1) }[
                interval.stage?.ordinal ?: SleepStage.entries.size]++
        events
            .getOrPut(interval.end) { IntArray(SleepStage.entries.size + 1) }[
                interval.stage?.ordinal ?: SleepStage.entries.size]--
    }
    val active = IntArray(SleepStage.entries.size + 1)
    val output = mutableListOf<SleepSegment>()
    var previous: Instant? = null
    var conflicting = false
    events.forEach { (time, changes) ->
        val start = previous
        if (start != null && time > start) {
            val stages = active.indices.filter { active[it] > 0 }
            if (stages.size > 1) {
                conflicting = true
            } else if (stages.size == 1 && stages.single() < SleepStage.entries.size) {
                val stage = SleepStage.entries[stages.single()]
                val last = output.lastOrNull()
                if (last?.stage == stage && last.end == start) {
                    output[output.lastIndex] = last.copy(end = time)
                } else {
                    output += SleepSegment(stage, start, time)
                }
            }
        }
        changes.indices.forEach { active[it] += changes[it] }
        previous = time
    }
    return output to conflicting
}
