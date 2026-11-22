package dev.ticbox.companion.ui.workout

import dev.ticbox.companion.api.HealthFitnessPoint
import dev.ticbox.companion.api.HealthFitnessPointQuery
import dev.ticbox.companion.api.HealthFitnessRecord
import dev.ticbox.companion.api.HealthFitnessRecordQuery
import dev.ticbox.companion.api.HealthPointType
import dev.ticbox.companion.api.HealthRawRecord
import dev.ticbox.companion.api.HealthRecordKind
import dev.ticbox.companion.api.HealthRecordQuery
import dev.ticbox.companion.api.HealthRecordReadStatus
import dev.ticbox.companion.ui.health.HealthReadState
import dev.ticbox.companion.ui.health.RecordAccess
import dev.ticbox.companion.ui.health.latestValue
import java.time.Instant
import java.time.Period
import java.time.ZoneId
import org.json.JSONException
import org.json.JSONObject

data class WorkoutList(
    /** Newest first. */
    val items: List<WorkoutSummary>,
    val state: HealthReadState,
    /** The items come from the watch's summary table, which holds no samples. */
    val summaryOnly: Boolean = false,
)

/** What is known about a workout's samples. */
enum class WorkoutSamples {
    AVAILABLE,

    /** Read successfully; the workout has no samples. */
    EMPTY,

    /** Some samples could not be read or the read stopped at its limit. */
    PARTIAL,

    /** The samples have not reached this phone yet. */
    NOT_SYNCED,

    /** Only the summary table holds this workout. */
    SUMMARY_ONLY,
    FAILED,
}

data class WorkoutDetail(
    val summary: WorkoutSummary,
    val record: HealthFitnessRecord?,
    val analysis: WorkoutAnalysis?,
    val samples: WorkoutSamples,
)

private const val PageSize = 200
private const val MaxListPages = 25
private const val MaxPointPages = 400

/** Reads every workout. Pages go from oldest to newest, so the newest needs the whole list. */
internal suspend fun RecordAccess.readWorkouts(): WorkoutList {
    val rows = mutableListOf<HealthFitnessRecord>()
    var offset = 0
    var limited = true
    var available = true
    for (index in 0 until MaxListPages) {
        val page =
            client.fitnessRecords(
                HealthFitnessRecordQuery(source, limit = PageSize, offset = offset)
            )
        rows += page.items
        if (page.status != HealthRecordReadStatus.AVAILABLE) available = false
        val next = page.nextOffset
        if (!available || next == null || next <= offset) {
            limited = available && next != null
            break
        }
        offset = next
    }
    if (rows.isEmpty()) {
        val summaries = readSummaries()
        if (summaries.isNotEmpty()) {
            return WorkoutList(summaries, HealthReadState.PARTIAL, summaryOnly = true)
        }
        return WorkoutList(
            emptyList(),
            if (available) HealthReadState.EMPTY else HealthReadState.UNAVAILABLE,
        )
    }
    val items =
        rows
            .filter { it.deleted != true }
            .map { it.toSummary() }
            .sortedByDescending { it.startMillis }
    val incomplete = limited || !available || rows.any { it.issues.isNotEmpty() }
    return WorkoutList(
        items,
        if (incomplete) HealthReadState.PARTIAL else HealthReadState.AVAILABLE,
    )
}

private suspend fun RecordAccess.readSummaries(): List<WorkoutSummary> {
    val rows = mutableListOf<WorkoutSummary>()
    var offset = 0
    repeat(MaxListPages) {
        val page =
            client.records(
                HealthRecordKind.SPORT,
                HealthRecordQuery(source, limit = PageSize, offset = offset),
            )
        rows += page.items.mapNotNull(::sportSummary)
        val next = page.nextOffset
        if (page.status != HealthRecordReadStatus.AVAILABLE || next == null || next <= offset) {
            return rows.sortedByDescending { it.startMillis }
        }
        offset = next
    }
    return rows.sortedByDescending { it.startMillis }
}

/** The ten-column summary row: time_from, time_to, type, during (ms), calorie, heart (average). */
private fun sportSummary(raw: HealthRawRecord): WorkoutSummary? =
    try {
        val columns = JSONObject(raw.json).getJSONObject("columns")
        fun number(name: String) =
            if (columns.isNull(name)) null else columns.optString(name).toDoubleOrNull()
        WorkoutSummary(
            key = raw.key,
            recordId = null,
            typeCode = number("type")?.toLong(),
            startMillis = number("time_from")?.toLong()?.takeIf { it > 0 },
            endMillis = number("time_to")?.toLong()?.takeIf { it > 0 },
            durationMillis = number("during")?.toLong()?.takeIf { it >= 0 },
            distanceMeters = null,
            calories = number("calorie")?.takeIf { it >= 0 },
            steps = null,
            averageHeartRate = number("heart")?.toLong()?.takeIf { it > 0 },
            autoDetected = null,
        )
    } catch (_: JSONException) {
        null
    }

internal suspend fun RecordAccess.readWorkout(key: String, zone: ZoneId): WorkoutDetail? {
    val page = client.fitnessRecords(HealthFitnessRecordQuery(source, recordKey = key, limit = 1))
    val record = page.items.firstOrNull()
    if (record == null) {
        val row =
            client
                .records(
                    HealthRecordKind.SPORT,
                    HealthRecordQuery(source, recordKey = key, limit = 1),
                )
                .items
                .firstOrNull()
        return row?.let(::sportSummary)?.let {
            WorkoutDetail(it, null, null, WorkoutSamples.SUMMARY_ONLY)
        }
    }
    val id =
        record.id ?: return WorkoutDetail(record.toSummary(), record, null, WorkoutSamples.FAILED)
    val (points, samples) = readSamples(id)
    return WorkoutDetail(
        record.toSummary(),
        record,
        analyzeWorkout(record, points, ageAt(record.startMillis, zone)),
        samples,
    )
}

private suspend fun RecordAccess.readSamples(
    recordId: Long
): Pair<List<HealthFitnessPoint>, WorkoutSamples> {
    val rows = linkedMapOf<String, HealthFitnessPoint>()
    var offset = 0
    repeat(MaxPointPages) {
        val page =
            client.fitnessPoints(
                HealthFitnessPointQuery(source, recordId, limit = 500, offset = offset)
            )
        page.items.forEach { rows[it.raw.key] = it }
        if (page.status != HealthRecordReadStatus.AVAILABLE) {
            val state = if (rows.isEmpty()) WorkoutSamples.NOT_SYNCED else WorkoutSamples.PARTIAL
            return rows.values.toList() to state
        }
        val next = page.nextOffset
        if (next == null || next <= offset) {
            val points = rows.values.toList()
            val state =
                when {
                    next != null || points.any { it.issues.isNotEmpty() } -> WorkoutSamples.PARTIAL
                    points.isEmpty() -> WorkoutSamples.EMPTY
                    else -> WorkoutSamples.AVAILABLE
                }
            return points to state
        }
        offset = next
    }
    return rows.values.toList() to WorkoutSamples.PARTIAL
}

/** The wearer's age on that day, for heart rate zones, if the watch stored a birthday. */
private suspend fun RecordAccess.ageAt(startMillis: Long?, zone: ZoneId): Int? {
    val birthday = latestValue(HealthPointType.INFO_BIRTHDAY) ?: return null
    val day = Instant.ofEpochMilli(startMillis ?: return null).atZone(zone).toLocalDate()
    val born = Instant.ofEpochSecond(birthday.toLong()).atZone(zone).toLocalDate()
    return Period.between(born, day).years
}
