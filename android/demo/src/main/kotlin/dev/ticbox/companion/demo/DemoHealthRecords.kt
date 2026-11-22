package dev.ticbox.companion.demo

import dev.ticbox.companion.api.HealthFitnessPointPage
import dev.ticbox.companion.api.HealthFitnessPointQuery
import dev.ticbox.companion.api.HealthFitnessRecordPage
import dev.ticbox.companion.api.HealthFitnessRecordQuery
import dev.ticbox.companion.api.HealthMotionState
import dev.ticbox.companion.api.HealthMotionTransition
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
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Deterministic sample records for the last [days] calendar days, shaped like the backend's parsed
 * local rows (heart rate, blood oxygen, step increments and AutoSleep sessions with stages).
 */
internal class DemoHealthRecords(
    private val peer: PeerAddress,
    private val clock: Clock,
    private val days: Int = 14,
) : HealthRecordsClient {
    private val zone = clock.zone
    private val cache = ConcurrentHashMap<LocalDate, DemoDay>()

    override suspend fun sources(): List<HealthRecordSource> =
        listOf(HealthRecordSource.LocalDevice(DEMO_USER, peer))

    override suspend fun points(query: HealthPointQuery): HealthPointPage {
        if (query.source.peer != peer) return HealthPointPage(emptyList(), null)
        val matches =
            daysOverlapping(query.fromMillis, query.toMillis)
                .flatMap { day(it).points }
                .filter { query.typeCode == null || it.typeCode == query.typeCode }
                .filter { overlaps(it.startMillis, it.endMillis, query.fromMillis, query.toMillis) }
                .map { it.withSource(query.source) }
        return page(matches, query.offset, query.limit) { items, next ->
            HealthPointPage(items, next)
        }
    }

    override suspend fun sleepRecords(query: HealthSleepQuery): HealthSleepPage {
        if (query.source.peer != peer) return HealthSleepPage(emptyList(), null)
        val matches =
            daysOverlapping(query.fromMillis, query.toMillis)
                .flatMap { day(it).sleeps }
                .filter { overlaps(it.startMillis, it.endMillis, query.fromMillis, query.toMillis) }
                .map { session ->
                    session.copy(
                        raw = session.raw.copy(source = query.source),
                        relatedPoints = session.relatedPoints.map { it.withSource(query.source) },
                    )
                }
        return page(matches, query.offset, query.limit) { items, next ->
            HealthSleepPage(items, next)
        }
    }

    private val workouts = DemoWorkouts(clock, ::raw)

    override suspend fun fitnessRecords(query: HealthFitnessRecordQuery): HealthFitnessRecordPage {
        if (query.source.peer != peer) return HealthFitnessRecordPage(emptyList(), null)
        val matches =
            workouts
                .records(query.source)
                .filter { query.recordKey == null || it.raw.key == query.recordKey }
                .filter { query.typeCode == null || it.typeCode == query.typeCode }
                .filter { overlaps(it.startMillis, it.endMillis, query.fromMillis, query.toMillis) }
                .sortedBy { it.startMillis }
        return page(matches, query.offset, query.limit) { items, next ->
            HealthFitnessRecordPage(items, next)
        }
    }

    override suspend fun fitnessPoints(query: HealthFitnessPointQuery): HealthFitnessPointPage {
        if (query.source.peer != peer) return HealthFitnessPointPage(emptyList(), null)
        val matches =
            workouts.points(query.recordId, query.source).filter {
                overlaps(it.timestampMillis, it.timestampMillis, query.fromMillis, query.toMillis)
            }
        return page(matches, query.offset, query.limit) { items, next ->
            HealthFitnessPointPage(items, next)
        }
    }

    override suspend fun records(
        kind: HealthRecordKind,
        query: HealthRecordQuery,
    ): HealthRecordPage {
        val source = query.source
        if (source !is HealthRecordSource.LocalDevice || source.peer != peer) {
            return HealthRecordPage(emptyList(), null)
        }
        val raws =
            daysOverlapping(query.fromMillis, query.toMillis).flatMap { date ->
                val day = day(date)
                when (kind) {
                    HealthRecordKind.POINT -> day.points.map { it.raw }
                    HealthRecordKind.SESSION -> day.sleeps.map { it.raw }
                    else -> emptyList()
                }
            }
        val matches =
            raws
                .filter { overlaps(it.startMillis, it.endMillis, query.fromMillis, query.toMillis) }
                .map { it.copy(source = source) }
        return page(matches, query.offset, query.limit) { items, next ->
            HealthRecordPage(items, next)
        }
    }

    override fun close() = Unit

    /** Today's steps so far, so that what the demo watch reports agrees with its records. */
    fun stepsToday(): Int =
        day(today())
            .points
            .filter { it.typeCode == HealthPointType.DELTA_STEP.code }
            .sumOf { point -> point.values.sumOf { it.decimal?.toIntOrNull() ?: 0 } }

    private fun today() = LocalDate.now(clock)

    private fun daysOverlapping(from: Long?, to: Long?): List<LocalDate> {
        val today = today()
        return (days - 1 downTo 0)
            .map { today.minusDays(it.toLong()) }
            .filter { date ->
                val start = date.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                overlaps(start, end, from, to)
            }
    }

    private fun day(date: LocalDate): DemoDay = cache.getOrPut(date) { generate(date) }

    private fun generate(date: LocalDate): DemoDay {
        val random = Random(date.toEpochDay())
        val now = clock.millis()
        val daysAgo = ChronoUnit.DAYS.between(date, today()).toInt()
        fun at(minutes: Int) =
            date.atStartOfDay(zone).plusMinutes(minutes.toLong()).toInstant().toEpochMilli()

        val points = mutableListOf<HealthPointRecord>()
        for (slot in 0 until 96) {
            val time = at(slot * 15)
            if (time > now) break
            val hour = slot / 4
            val base =
                when (hour) {
                    in 0..6 -> 56
                    in 7..8 -> 74
                    in 17..18 -> 96
                    else -> 68
                }
            val bpm = base + sin(slot * 0.7 + daysAgo) * 5 + random.nextInt(-3, 4)
            points +=
                point(HealthPointType.HEART_RATE, time, time, bpm.roundToInt(), "heart_rate", "bpm")
        }
        for (hour in 0 until 24) {
            val time = at(hour * 60 + 30)
            if (time > now) break
            if (hour in 0..6 || hour % 3 == 0) {
                val spo2 = (97 + sin(hour * 0.9 + daysAgo) * 1.4).roundToInt().coerceIn(94, 99)
                points += point(HealthPointType.BLOOD_OXYGEN, time, time, spo2, "blood_oxygen", "%")
            }
        }
        val hourlySteps =
            listOf(
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                380,
                1150,
                420,
                300,
                520,
                900,
                260,
                310,
                440,
                380,
                1250,
                1320,
                640,
                280,
                160,
                60,
                0,
            )
        for (hour in 0 until 24) {
            val end = at(hour * 60 + 59)
            if (end > now) break
            val steps = (hourlySteps[hour] * (0.8 + random.nextDouble() * 0.5)).roundToInt()
            if (steps > 0) {
                points +=
                    point(
                        HealthPointType.DELTA_STEP,
                        at(hour * 60),
                        end,
                        steps,
                        "delta_step",
                        "steps",
                    )
            }
        }
        points += activityExtras(hourlySteps, ::at, now, random)
        points += bodyReadings(daysAgo, ::at, now, random)
        points += eventLogs(daysAgo, ::at, now)
        if (daysAgo == 1) points += profile(at(8 * 60))
        val sleeps =
            listOfNotNull(night(date, random), nap(date, random).takeIf { daysAgo % 3 == 2 })
        return DemoDay(points, sleeps.filter { it.endMillis!! <= now })
    }

    private fun activityExtras(
        hourlySteps: List<Int>,
        at: (Int) -> Long,
        now: Long,
        random: Random,
    ) = buildList {
        for (hour in 0 until 24) {
            val end = at(hour * 60 + 59)
            if (end > now || hourlySteps[hour] == 0) continue
            val start = at(hour * 60)
            val steps = hourlySteps[hour]
            add(
                point(
                    HealthPointType.DELTA_CALORIE,
                    start,
                    end,
                    steps / 22 + 3,
                    "delta_calorie",
                    "kcal",
                )
            )
            add(
                point(
                    HealthPointType.DELTA_DISTANCE,
                    start,
                    end,
                    (steps * 0.74).roundToInt(),
                    "delta_distance",
                    "m",
                )
            )
            if (steps > 800)
                add(
                    point(
                        HealthPointType.DELTA_EXERCISE,
                        start,
                        end,
                        steps / 2,
                        "delta_exercise",
                        "s",
                    )
                )
            if (hour % 4 == 0)
                add(
                    point(
                        HealthPointType.CLIMBING_STAIRS,
                        start,
                        end,
                        random.nextInt(3, 12),
                        "climbing_stairs",
                        "m",
                    )
                )
        }
    }

    private fun bodyReadings(daysAgo: Int, at: (Int) -> Long, now: Long, random: Random) =
        buildList {
            fun add(
                type: HealthPointType,
                time: Long,
                value: String,
                semantic: String,
                unit: String?,
            ) {
                if (time <= now) add(row(type, time, time, listOf(Slot(value, semantic, unit))))
            }
            for (hour in 7 until 22) {
                add(
                    HealthPointType.PRESSURE,
                    at(hour * 60),
                    (32 + sin(hour * 0.8) * 16 + random.nextInt(-4, 5)).roundToInt().toString(),
                    "pressure",
                    null,
                )
                add(
                    HealthPointType.SKIN_TEMPERATURE,
                    at(hour * 60 + 5),
                    "%.1f".format(Locale.ROOT, 33.1 + sin(hour * 0.5)),
                    "skin_temperature",
                    "°C",
                )
            }
            for (hour in 0 until 7) {
                add(
                    HealthPointType.RESPIRATION_RATE,
                    at(hour * 60 + 20),
                    "${14 + random.nextInt(0, 3)}",
                    "respiration_rate",
                    "breaths/min",
                )
            }
            add(
                HealthPointType.TEMPERATURE,
                at(9 * 60),
                "%.1f".format(Locale.ROOT, 36.4 + random.nextDouble() * 0.3),
                "temperature",
                "°C",
            )
            for (hour in listOf(10, 14, 19)) {
                val time = at(hour * 60)
                if (time <= now) {
                    val samples =
                        List(4) { Slot("${48 + random.nextInt(0, 24)}", "noise_decibel", "dB") }
                    add(row(HealthPointType.NOISE_DECIBEL, time, time, samples))
                }
            }
            if (daysAgo % 3 == 1) {
                val time = at(7 * 60 + 40)
                if (time <= now) {
                    val slots =
                        listOf(
                            Slot("47.5", "vo2max", "ml/kg/min"),
                            Slot("OutdoorRunning", "sport_type", null, HealthValueStatus.TEXT),
                            Slot("1800000", "duration", "ms"),
                            Slot("demo", "sport_id", null, HealthValueStatus.TEXT),
                        )
                    add(row(HealthPointType.VO2MAX, time, time, slots))
                }
            }
        }

    /** One of each event in turn, so every log has entries without filling up every day. */
    private fun eventLogs(daysAgo: Int, at: (Int) -> Long, now: Long) = buildList {
        fun event(minutes: Int, type: HealthPointType, slots: List<Slot>) {
            val time = at(minutes)
            if (time <= now) add(row(type, time, time, slots))
        }
        event(
            10 * 60,
            HealthPointType.BODY_MIND_STATE,
            listOf(
                Slot("${daysAgo % 2 + 1}", "body_mind_type", "body_mind_code"),
                Slot("${daysAgo % 5}", "level", "ordinal"),
            ),
        )
        if (daysAgo % 2 == 0) {
            event(
                12 * 60,
                HealthPointType.TODAY_MOOD,
                listOf(Slot("${daysAgo % 4 + 1}", "today_mood", "mood_code")),
            )
        }
        if (daysAgo % 3 == 0) {
            event(
                13 * 60,
                HealthPointType.HEART_HEALTH,
                listOf(
                    Slot("1", "heart_health_type", "heart_health_code"),
                    Slot("72", "heart_rate", "bpm"),
                ),
            )
            event(
                18 * 60,
                HealthPointType.HIGH_STRENGTH_TRAINING,
                listOf(
                    Slot("0", "sport_type", "sport_type_code"),
                    Slot("780000", "high_intensity_duration", "ms"),
                ),
            )
        }
        if (daysAgo == 4) {
            event(
                15 * 60,
                HealthPointType.HEART_RATE_WARNING,
                listOf(Slot("17", "heart_rate_warning", "heart_rate_warning_code")),
            )
        }
        if (daysAgo % 4 == 0) {
            event(
                9 * 60,
                HealthPointType.ARTY_DATA,
                listOf(
                    Slot("0", null, null),
                    Slot("78", "arty_score", "score/100"),
                    Slot("63", "tru_hr", "bpm"),
                    Slot("64", "ecap", "%"),
                    Slot("71", "hsx", "%"),
                    Slot("28", "arty_age_lower", "years"),
                    Slot("32", "arty_age_upper", "years"),
                ),
            )
        }
        if (daysAgo == 6) {
            event(
                8 * 60,
                HealthPointType.PERIOD_EVENT,
                listOf(Slot("1", "period_event", "period_event_code")),
            )
            event(
                8 * 60,
                HealthPointType.PERIOD_CYCLE_LENGTH,
                listOf(Slot("28", "period_cycle_length", "days")),
            )
            event(
                8 * 60,
                HealthPointType.PERIOD_MENSTRUATION_LENGTH,
                listOf(Slot("5", "period_menstruation_length", "days")),
            )
        }
    }

    private fun profile(time: Long) =
        listOf(
                Triple(HealthPointType.INFO_BIRTHDAY, "631152000", "epoch_s"),
                Triple(HealthPointType.INFO_HEIGHT, "172", "cm"),
                Triple(HealthPointType.INFO_WEIGHT, "64.5", "kg"),
                Triple(HealthPointType.GOAL_TODAY_STEP, "8000", "steps"),
                Triple(HealthPointType.GOAL_TODAY_ACTIVE, "12", "h"),
                Triple(HealthPointType.GOAL_TODAY_EXERCISE, "1800", "s"),
                Triple(HealthPointType.EXPECTED_SLEEP_TIME, "81000000", "ms_since_midnight"),
                Triple(HealthPointType.EXPECTED_GET_UP_TIME, "25200000", "ms_since_midnight"),
                Triple(HealthPointType.HEART_RATE_WARNING_FITNESS_UPPER_LIMIT, "170", "bpm"),
                Triple(HealthPointType.HEART_RATE_WARNING_REST_UPPER_LIMIT, "110", "bpm"),
                Triple(HealthPointType.HEART_RATE_WARNING_REST_LOWER_LIMIT, "45", "bpm"),
            )
            .map { (type, value, unit) ->
                row(type, time, time, listOf(Slot(value, type.wireName, unit)))
            }

    private fun night(date: LocalDate, random: Random): HealthSleepRecord {
        val stages =
            listOf(
                HealthMotionState.LIGHT_SLEEP to 25,
                HealthMotionState.DEEP_SLEEP to 65,
                HealthMotionState.LIGHT_SLEEP to 40,
                HealthMotionState.REM to 28,
                HealthMotionState.AWAKE to 8,
                HealthMotionState.LIGHT_SLEEP to 60,
                HealthMotionState.DEEP_SLEEP to 42,
                HealthMotionState.LIGHT_SLEEP to 45,
                HealthMotionState.REM to 50,
                HealthMotionState.AWAKE to 6,
                HealthMotionState.LIGHT_SLEEP to 35,
                HealthMotionState.REM to 18,
            )
        val start =
            date.minusDays(1).atTime(22, 50).atZone(zone).toInstant().toEpochMilli() +
                random.nextInt(0, 40) * 60_000L
        return sleepSession("demo-sleep-${date.toEpochDay()}", start, stages, random)
    }

    /** An early-afternoon nap, which the phone keeps apart from the night. */
    private fun nap(date: LocalDate, random: Random): HealthSleepRecord {
        val stages =
            listOf(
                HealthMotionState.LIGHT_SLEEP to 22,
                HealthMotionState.DEEP_SLEEP to 14,
                HealthMotionState.LIGHT_SLEEP to 18,
            )
        val start =
            date.atTime(13, 20).atZone(zone).toInstant().toEpochMilli() +
                random.nextInt(0, 20) * 60_000L
        return sleepSession("demo-nap-${date.toEpochDay()}", start, stages, random)
    }

    private fun sleepSession(
        key: String,
        start: Long,
        stages: List<Pair<HealthMotionState, Int>>,
        random: Random,
    ): HealthSleepRecord {
        var cursor = start
        val intervals = stages.mapIndexed { index, (state, minutes) ->
            val begin = cursor
            cursor += (minutes + random.nextInt(-4, 5)) * 60_000L
            val next = stages.getOrNull(index + 1)?.first ?: HealthMotionState.AWAKE
            motionPoint(begin, cursor, state, next)
        }
        val raw = raw(HealthRecordKind.SESSION, key, "sleep", start, cursor)
        return HealthSleepRecord(
            raw = raw,
            sessionId = key,
            deviceId = DEVICE_ID,
            vendorUserId = null,
            activityTypeCode = HealthSleepRecord.AUTO_SLEEP_TYPE_CODE,
            startMillis = start,
            endMillis = cursor,
            vendorSyncCode = 0,
            deletedCode = 0,
            relatedPoints = intervals,
            relatedPointsStatus = HealthRecordReadStatus.AVAILABLE,
            issues = emptySet(),
        )
    }

    private fun point(
        type: HealthPointType,
        start: Long,
        end: Long,
        value: Int,
        semantic: String,
        unit: String,
    ) = row(type, start, end, listOf(Slot(value.toString(), semantic, unit)))

    /** One column of a row's comma-separated values. */
    private class Slot(
        val value: String,
        val semantic: String?,
        val unit: String?,
        val status: HealthValueStatus = HealthValueStatus.NUMBER,
    )

    private fun row(type: HealthPointType, start: Long, end: Long, slots: List<Slot>) =
        HealthPointRecord(
            raw =
                raw(HealthRecordKind.POINT, "demo-${type.code}-$start", type.wireName, start, end),
            providerId = null,
            deviceId = DEVICE_ID,
            vendorUserId = null,
            typeCode = type.code,
            startMillis = start,
            endMillis = end,
            vendorSyncCode = 0,
            deletedCode = 0,
            rawValues = slots.joinToString(",") { it.value },
            values =
                slots.mapIndexed { index, slot ->
                    HealthPointValue(
                        index = index,
                        rawToken = slot.value,
                        decimal = slot.value.takeIf { slot.status == HealthValueStatus.NUMBER },
                        status = slot.status,
                        semantic = slot.semantic,
                        unit = slot.unit,
                    )
                },
            motion = null,
            issues = emptySet(),
        )

    private fun motionPoint(
        start: Long,
        end: Long,
        state: HealthMotionState,
        next: HealthMotionState,
    ) =
        HealthPointRecord(
            raw =
                raw(
                    HealthRecordKind.POINT,
                    "demo-motion-$start",
                    HealthPointType.MOTION.wireName,
                    start,
                    end,
                ),
            providerId = null,
            deviceId = DEVICE_ID,
            vendorUserId = null,
            typeCode = HealthPointType.MOTION.code,
            startMillis = start,
            endMillis = end,
            vendorSyncCode = 0,
            deletedCode = 0,
            rawValues = "${state.code},${next.code}",
            values = emptyList(),
            motion = HealthMotionTransition(state.code, next.code),
            issues = emptySet(),
        )

    private fun raw(kind: HealthRecordKind, key: String, dataType: String, start: Long, end: Long) =
        HealthRawRecord(
            source = HealthRecordSource.LocalDevice(DEMO_USER, peer),
            kind = kind,
            key = key,
            sourceName = SOURCE_NAME,
            dataType = dataType,
            startMillis = start,
            endMillis = end,
            timeZone = zone.id,
            deleted = false,
            updatedAtMillis = end,
            json = """{"demo":true,"key":"$key"}""",
        )

    private fun HealthPointRecord.withSource(source: HealthRecordSource) =
        copy(raw = raw.copy(source = source))

    private data class DemoDay(
        val points: List<HealthPointRecord>,
        val sleeps: List<HealthSleepRecord>,
    )

    private companion object {
        const val DEMO_USER = "demo"
        const val DEVICE_ID = "demo-watch"
        const val SOURCE_NAME = "demo"

        fun overlaps(start: Long?, end: Long?, from: Long?, to: Long?): Boolean =
            (from == null || end == null || end >= from) &&
                (to == null || start == null || start <= to)

        fun <T, P> page(items: List<T>, offset: Int, limit: Int, build: (List<T>, Int?) -> P): P {
            val slice = items.drop(offset).take(limit)
            val next = (offset + slice.size).takeIf { it < items.size }
            return build(slice, next)
        }
    }
}
