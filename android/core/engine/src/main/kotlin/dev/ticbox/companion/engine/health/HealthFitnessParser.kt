package dev.ticbox.companion.engine.health

import dev.ticbox.companion.api.*
import java.math.BigDecimal
import kotlinx.serialization.json.*

/**
 * Read-only interpretation of TicFitness RECORD(29)/POINT(19), never the protected summary
 * Provider or TicHealth's CSV points. Source: docs/spec/fitness-database.md. No vendor filters,
 * physiology bounds, sensor smoothing or coordinate conversions are reapplied to stored data.
 */
object HealthFitnessParser {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }
    val recordColumns: Set<String> = setOf("_id", "HASH", "TYPE", "TARGET_TYPE", "TARGET_VALUE", "ACCOUNT_ID",
        "SYNCED", "DELETED", "START_TIME", "END_TIME", "TIMESTAMP", "DURATION", "DISTANCE", "CALORIE",
        "STEP", "HEART", "GROUPS", "SWIM_TRIPS", "SWIM_DISTANCE", "SWIM_POOL_LENGTH", "SWIM_STROKE",
        "DEVICE_ID", "SESSION_MODE", "MAX_ELEVATION", "MIN_ELEVATION", "CUMULATIVE_UP", "CUMULATIVE_DOWN", "SCORE", "EXTRA")
    val pointColumns: Set<String> = setOf("_id", "TIME", "HEART", "STEP", "LAT", "LON", "ACC", "DISTANCE",
        "SPEED", "CALORIE", "RECORD_ID", "RESUME", "SWIM_TRIPS", "SWIM_DISTANCE", "SWIM_STROKE", "SWIM_TYPE",
        "TIMESTAMP", "ELEVATION", "STRIDE_FREQ")

    fun parseRecord(raw: HealthRawRecord): HealthFitnessRecord {
        val f = Fields(raw, recordColumns)
        val id = f.integer("_id", required = true) ?: f.envelopeId()
        val hash = f.text("HASH")
        val type = f.integer("TYPE", required = true)
        val mode = mode(type)
        if (mode == HealthFitnessMode.UNCONFIRMED) f.issue("TYPE", HealthFitnessIssueKind.UNKNOWN_SPORT_TYPE)
        val targetType = f.integer("TARGET_TYPE")
        val targetValue = f.decimal("TARGET_VALUE", minusOne = true)
        if (targetType != null && targetType != 0L && HealthFitnessTargetType.fromCode(targetType) == null)
            f.issue("TARGET_TYPE", HealthFitnessIssueKind.UNKNOWN_TARGET_TYPE)
        val account = f.text("ACCOUNT_ID")
        val synced = f.flagCode("SYNCED", required = true, bitField = true)
        val deleted = f.flagCode("DELETED", required = true)
        val start = f.integer("START_TIME", minusOne = true)
        val end = f.integer("END_TIME", minusOne = true)
        val timestamp = f.integer("TIMESTAMP")
        val duration = f.integer("DURATION", required = true, minusOne = true)
        if (end != null && end <= 0) f.issue("END_TIME", HealthFitnessIssueKind.OPEN_RECORD)
        else if (start != null && end != null && end < start) f.issue("END_TIME", HealthFitnessIssueKind.INVALID_TIME_RANGE)
        val distance = f.integer("DISTANCE", minusOne = true)
        val calories = f.decimal("CALORIE", minusOne = true)
        val steps = f.integer("STEP", minusOne = true)
        val heart = f.integer("HEART", minusOne = true)
        val rawGroups = f.text("GROUPS")
        val groups = parseGroups(rawGroups, duration, f)
        val trips = f.decimal("SWIM_TRIPS", minusOne = true)
        val swimDistance = f.integer("SWIM_DISTANCE", minusOne = mode == HealthFitnessMode.LAND)
        val poolLength = f.integer("SWIM_POOL_LENGTH", minusOne = true)
        val stroke = f.integer("SWIM_STROKE", minusOne = true)
        val device = f.text("DEVICE_ID")
        val session = f.integer("SESSION_MODE")
        val maximum = f.decimal("MAX_ELEVATION", sentinel = LOW_ELEVATION)
        val minimum = f.decimal("MIN_ELEVATION", sentinel = HIGH_ELEVATION)
        val up = f.decimal("CUMULATIVE_UP", minusOne = true)
        val down = f.decimal("CUMULATIVE_DOWN", minusOne = true)
        val score = f.decimal("SCORE", minusOne = true)
        val rawExtra = f.text("EXTRA")
        val extra = parseExtra(rawExtra, type, f)
        val swimming = if (mode == HealthFitnessMode.SWIMMING) HealthFitnessSwimmingRecord(
            trips.unlessSentinel("SWIM_TRIPS", f), poolLength.unlessSentinel("SWIM_POOL_LENGTH", f),
            stroke.unlessSentinel("SWIM_STROKE", f)) else null
        val land = if (mode == HealthFitnessMode.LAND) HealthFitnessLandRecord(
            trips.unlessSentinel("SWIM_TRIPS", f), swimDistance.unlessSentinel("SWIM_DISTANCE", f),
            poolLength.unlessSentinel("SWIM_POOL_LENGTH", f), stroke.unlessSentinel("SWIM_STROKE", f)) else null
        return HealthFitnessRecord(raw, id, hash, type, targetType, targetValue, account, synced, deleted,
            start, end, timestamp, duration, distance, calories, steps, heart, rawGroups,
            trips, swimDistance, poolLength, stroke, device, session, maximum, minimum, up, down, score,
            rawExtra, mode, swimming, land, groups, extra, f.sentinels.toSet(), f.unknownColumns, f.issues.toSet())
    }

    fun parsePoint(raw: HealthRawRecord, parent: HealthFitnessRecord? = null): HealthFitnessPoint {
        val f = Fields(raw, pointColumns)
        val id = f.integer("_id", required = true) ?: f.envelopeId()
        val recordId = f.integer("RECORD_ID")
        val mismatch = parent != null && (parent.raw.source != raw.source ||
            (recordId != null && parent.id != null && recordId != parent.id))
        if (parent == null) f.issue("RECORD_ID", HealthFitnessIssueKind.PARENT_UNAVAILABLE)
        if (mismatch) f.issue("RECORD_ID", HealthFitnessIssueKind.PARENT_MISMATCH)
        val parentType = if (!mismatch) parent?.typeCode else null
        val mode = mode(parentType)
        val timestamp = f.integer("TIMESTAMP", required = true, minusOne = true)
        val elapsed = f.integer("TIME", required = true, minusOne = true)
        val heart = f.integer("HEART", minusOne = true)
        val steps = f.integer("STEP", minusOne = true)
        val latitude = f.decimal("LAT", required = true)
        val longitude = f.decimal("LON", required = true)
        val auxiliary = f.decimal("ACC", required = true, minusOne = true)
        val distance = f.integer("DISTANCE", minusOne = true)
        val speed = f.decimal("SPEED", minusOne = true)
        val calories = f.decimal("CALORIE", minusOne = true)
        val resume = f.flagCode("RESUME")
        val trips = f.decimal("SWIM_TRIPS", minusOne = true)
        val swimDistance = f.integer("SWIM_DISTANCE")
        val stroke = f.integer("SWIM_STROKE", minusOne = true)
        val swimType = f.integer("SWIM_TYPE", minusOne = true)
        val elevation = f.decimal("ELEVATION", sentinel = LOW_ELEVATION)
        val cadence = f.integer("STRIDE_FREQ", minusOne = true)
        val swimming = if (mode == HealthFitnessMode.SWIMMING) HealthFitnessSwimmingPoint(
            trips.unlessSentinel("SWIM_TRIPS", f), stroke.unlessSentinel("SWIM_STROKE", f),
            swimType.unlessSentinel("SWIM_TYPE", f)) else null
        val lap = if (mode == HealthFitnessMode.LAND && trips != null && BigDecimal(trips).signum() > 0) {
            val number = healthInteger(trips)
            if (number == null) f.issue("SWIM_TRIPS", HealthFitnessIssueKind.INVALID_INTEGER)
            HealthFitnessLapPoint(number, trips, stroke.unlessSentinel("SWIM_STROKE", f),
                swimType.unlessSentinel("SWIM_TYPE", f))
        } else null
        return HealthFitnessPoint(raw, id, recordId, timestamp, elapsed, heart, steps, latitude, longitude,
            auxiliary, distance, speed, calories, resume, trips, swimDistance, stroke, swimType, elevation,
            cadence, parentType, mode, swimming, lap, f.sentinels.toSet(), f.unknownColumns, f.issues.toSet())
    }

    private fun mode(type: Long?): HealthFitnessMode = when {
        type in setOf(9L, 11L, 55L) -> HealthFitnessMode.SWIMMING
        type != null && type >= 0 && HealthFitnessSportTypes.name(type) != null -> HealthFitnessMode.LAND
        else -> HealthFitnessMode.UNCONFIRMED
    }

    private fun parseGroups(raw: String?, duration: Long?, f: Fields): HealthFitnessGroups? {
        if (raw.isNullOrBlank()) return null
        val boundaries = raw.split(',').mapIndexed { index, token ->
            val elapsed = healthInteger(token)
            if (elapsed == null) f.issue("GROUPS[$index]", HealthFitnessIssueKind.MALFORMED_GROUP_BOUNDARY)
            HealthFitnessGroupBoundary(index, token, elapsed)
        }
        val intervals = (0..boundaries.size).map { index ->
            val start = if (index == 0) 0L else boundaries[index - 1].elapsedMillis
            val end = if (index < boundaries.size) boundaries[index].elapsedMillis else duration?.takeIf { it >= 0 }
            if (start != null && end != null && end < start)
                f.issue("GROUPS[$index]", HealthFitnessIssueKind.INVALID_TIME_RANGE)
            HealthFitnessGroupInterval(index, index / 2 + 1, groupState(index), start, end)
        }
        return HealthFitnessGroups(boundaries, intervals, boundaries.size / 2 + 1, groupState(boundaries.size))
    }

    private fun groupState(index: Int) = if (index % 2 == 0) HealthFitnessGroupState.TRAIN else HealthFitnessGroupState.REST

    private fun parseExtra(raw: String?, typeCode: Long?, f: Fields): HealthFitnessExtra {
        if (raw.isNullOrBlank()) return HealthFitnessExtra(HealthFitnessExtraKind.NONE, null, emptyList())
        val element = try { json.parseToJsonElement(raw) } catch (_: IllegalArgumentException) {
            f.issue("EXTRA", HealthFitnessIssueKind.MALFORMED_EXTENSION); null
        }
        if (element is JsonObject && "recoveryTime" in element) {
            val nested = Fields(element, "EXTRA", f.issues)
            val recovery = HealthFitnessRecovery(element.toString(), nested.text("type", required = true),
                nested.integer("recoveryTime", required = true))
            return HealthFitnessExtra(HealthFitnessExtraKind.RECOVERY, recovery, emptyList())
        }
        val stageKeys = setOf("type", "start_time", "target_duration", "duration", "times")
        if (element is JsonArray && (typeCode == 24L || element.any { entry ->
                (entry as? JsonObject)?.keys?.any { it in stageKeys } == true
            })) {
            val stages = element.mapIndexed { index, entry ->
                val path = "EXTRA[$index]"
                val issues = linkedSetOf<HealthFitnessIssue>()
                val nested = Fields(entry as? JsonObject ?: JsonObject(emptyMap()), path, issues)
                if (entry !is JsonObject) nested.issue("", HealthFitnessIssueKind.MALFORMED_EXTENSION)
                val type = nested.integer("type", required = true)
                val start = nested.integer("start_time", required = true)
                val target = nested.integer("target_duration", required = true)
                val duration = nested.integer("duration", required = true)
                val times = nested.integer("times", required = true)
                if (type != null && HealthFitnessSportTypes.name(type) == null)
                    nested.issue("type", HealthFitnessIssueKind.UNKNOWN_SPORT_TYPE)
                if (duration == -1L) nested.issue("duration", HealthFitnessIssueKind.OPEN_HIIT_STAGE)
                f.issues.addAll(issues)
                HealthFitnessHiitStage(index, entry.toString(), type, start, target, duration, times, issues.toSet())
            }
            return HealthFitnessExtra(HealthFitnessExtraKind.HIIT, null, stages)
        }
        if (element != null) f.issue("EXTRA", HealthFitnessIssueKind.UNRECOGNIZED_EXTENSION)
        return HealthFitnessExtra(HealthFitnessExtraKind.UNRECOGNIZED, null, emptyList())
    }

    private fun <T> T?.unlessSentinel(column: String, f: Fields): T? = takeUnless { column in f.sentinels }

    private val LOW_ELEVATION = BigDecimal.valueOf(Int.MIN_VALUE.toFloat().toDouble())
    private val HIGH_ELEVATION = BigDecimal.valueOf(Int.MAX_VALUE.toFloat().toDouble())
    private val nonFinite = setOf("nan", "infinity", "+infinity", "-infinity", "inf", "+inf", "-inf")

    private class Fields {
        private val columns: JsonObject
        private val root: JsonObject?
        private val prefix: String
        val issues: MutableSet<HealthFitnessIssue>
        val sentinels = linkedSetOf<String>()
        val unknownColumns: Set<String>

        constructor(raw: HealthRawRecord, knownColumns: Set<String>) {
            issues = linkedSetOf()
            prefix = ""
            root = try { json.parseToJsonElement(raw.json) as? JsonObject } catch (_: IllegalArgumentException) { null }
            columns = root?.get("columns") as? JsonObject ?: root ?: JsonObject(emptyMap())
            if (root == null || (root["columns"] != null && root["columns"] !is JsonObject))
                issue("row", HealthFitnessIssueKind.MALFORMED_ROW)
            if (root.lacksSourceCells()) issue("row", HealthFitnessIssueKind.PARTIAL_SOURCE_ROW)
            unknownColumns = columns.keys.filterTo(linkedSetOf()) { name -> knownColumns.none { it.equals(name, true) } }
        }

        constructor(columns: JsonObject, prefix: String, issues: MutableSet<HealthFitnessIssue>) {
            this.columns = columns; this.prefix = prefix; this.issues = issues
            root = null; unknownColumns = emptySet()
        }

        fun issue(name: String, kind: HealthFitnessIssueKind) {
            issues += HealthFitnessIssue(if (prefix.isEmpty()) name else if (name.isEmpty()) prefix else "$prefix.$name", kind)
        }
        private fun cell(name: String): JsonElement? = columns[name] ?: columns.entries.firstOrNull { it.key.equals(name, true) }?.value
        fun text(name: String, required: Boolean = false): String? {
            val cell = cell(name)
            if (cell == null || cell is JsonNull) {
                if (required) issue(name, HealthFitnessIssueKind.MISSING_FIELD)
                return null
            }
            if (cell is JsonPrimitive) return cell.content
            issue(name, HealthFitnessIssueKind.UNEXPECTED_CELL_KIND)
            // Preserve and attempt structured text such as an already-decoded EXTRA object.
            return cell.toString()
        }
        fun envelopeId() = (root?.get("id") as? JsonPrimitive)?.contentOrNull?.let(::healthInteger)
        fun integer(name: String, required: Boolean = false, minusOne: Boolean = false): Long? {
            val token = text(name, required) ?: return null
            val value = healthInteger(token)
            if (value == null) issue(name, if (token.trim().lowercase() in nonFinite)
                HealthFitnessIssueKind.NON_FINITE_VALUE else HealthFitnessIssueKind.INVALID_INTEGER)
            if (minusOne && value == -1L) sentinels += name
            return value
        }
        fun decimal(name: String, required: Boolean = false, minusOne: Boolean = false, sentinel: BigDecimal? = null): String? {
            val token = text(name, required) ?: return null
            val value = try { BigDecimal(token.trim().removePrefix("\uFEFF")) } catch (_: NumberFormatException) { null }
            if (value == null) issue(name, if (token.trim().lowercase() in nonFinite)
                HealthFitnessIssueKind.NON_FINITE_VALUE else HealthFitnessIssueKind.VALUE_NOT_NUMERIC)
            if (value != null && ((minusOne && value.compareTo(BigDecimal.ONE.negate()) == 0) ||
                    (sentinel != null && value.compareTo(sentinel) == 0))) sentinels += name
            return value?.toString()
        }
        fun flagCode(name: String, required: Boolean = false, bitField: Boolean = false): Long? {
            val primitive = cell(name) as? JsonPrimitive
            val boolean = primitive?.takeUnless { it is JsonNull }?.booleanOrNull
            val value = if (boolean != null) (if (boolean) 1L else 0L) else integer(name, required)
            if (!bitField && value != null && value !in 0L..1L) issue(name, HealthFitnessIssueKind.NONSTANDARD_FLAG)
            return value
        }
    }

    /** Structured parsed payload for the same transaction that stores the complete original row. */
    fun encodeRecord(value: HealthFitnessRecord): String = buildJsonObject {
        put("schema", "fitness-record-v1")
        putOptional("id", value.id); putOptional("hash", value.hash); putOptional("typeCode", value.typeCode)
        putOptional("sportName", value.sportName); putOptional("targetTypeCode", value.targetTypeCode)
        putOptional("targetType", value.targetType?.name); putOptional("targetUnit", value.targetUnit)
        putOptional("targetValueDecimal", value.targetValueDecimal); putOptional("accountId", value.accountId)
        putOptional("vendorSyncCode", value.vendorSyncCode); putOptional("deletedCode", value.deletedCode)
        putOptional("startMillis", value.startMillis); putOptional("endMillis", value.endMillis)
        putOptional("timestampRaw", value.timestampRaw); putOptional("durationMillis", value.durationMillis)
        putOptional("distanceMeters", value.distanceMeters); putOptional("calorieKcalDecimal", value.calorieKcalDecimal)
        putOptional("steps", value.steps); putOptional("averageHeartRateBpm", value.averageHeartRateBpm)
        putOptional("rawGroups", value.rawGroups); putOptional("swimTripsDecimal", value.swimTripsDecimal)
        putOptional("swimDistanceRaw", value.swimDistanceRaw); putOptional("swimPoolLengthRaw", value.swimPoolLengthRaw)
        putOptional("swimStrokeRaw", value.swimStrokeRaw); putOptional("deviceId", value.deviceId)
        putOptional("sessionModeCode", value.sessionModeCode)
        putOptional("maxElevationMetersDecimal", value.maxElevationMetersDecimal)
        putOptional("minElevationMetersDecimal", value.minElevationMetersDecimal)
        putOptional("cumulativeUpMetersDecimal", value.cumulativeUpMetersDecimal)
        putOptional("cumulativeDownMetersDecimal", value.cumulativeDownMetersDecimal)
        putOptional("scoreDecimal", value.scoreDecimal); put("scoreMeaning", value.scoreMeaning.name)
        putOptional("rawExtra", value.rawExtra); put("mode", value.mode.name)
        value.swimming?.let { swimming -> put("swimming", buildJsonObject {
            putOptional("tripsDecimal", swimming.tripsDecimal); putOptional("poolLengthMeters", swimming.poolLengthMeters)
            putOptional("strokeCount", swimming.strokeCount)
        }) }
        value.land?.let { land -> put("land", buildJsonObject {
            putOptional("vo2maxMlKgMinDecimal", land.vo2maxMlKgMinDecimal); putOptional("lapCount", land.lapCount)
            putOptional("lastLapDistanceMeters", land.lastLapDistanceMeters); putOptional("lastLapDurationMillis", land.lastLapDurationMillis)
        }) }
        value.groups?.let { groups -> put("groups", buildJsonObject {
            put("currentGroupNumber", groups.currentGroupNumber); put("currentState", groups.currentState.name)
            put("boundaries", buildJsonArray { groups.boundaries.forEach { b -> add(buildJsonObject {
                put("index", b.index); put("rawToken", b.rawToken); putOptional("elapsedMillis", b.elapsedMillis)
            }) } })
            put("intervals", buildJsonArray { groups.intervals.forEach { interval -> add(buildJsonObject {
                put("index", interval.index); put("groupNumber", interval.groupNumber); put("state", interval.state.name)
                putOptional("startElapsedMillis", interval.startElapsedMillis); putOptional("endElapsedMillis", interval.endElapsedMillis)
            }) } })
        }) }
        put("extra", buildJsonObject {
            put("kind", value.extra.kind.name)
            value.extra.recovery?.let { recovery -> put("recovery", buildJsonObject {
                put("rawJson", recovery.rawJson); putOptional("sportName", recovery.sportName)
                putOptional("durationMillis", recovery.durationMillis)
            }) }
            put("stages", buildJsonArray { value.extra.stages.forEach { stage -> add(buildJsonObject {
                put("index", stage.index); put("rawJson", stage.rawJson); putOptional("typeCode", stage.typeCode)
                putOptional("sportName", stage.sportName); putOptional("startElapsedMillis", stage.startElapsedMillis)
                putOptional("targetDurationMillis", stage.targetDurationMillis); putOptional("durationMillis", stage.durationMillis)
                putOptional("actionTimes", stage.actionTimes); putIssues(stage.issues)
            }) } })
        })
        putAnnotations(value.sentinelColumns, value.unknownColumns, value.issues)
    }.toString()

    fun encodePoint(value: HealthFitnessPoint): String = buildJsonObject {
        put("schema", "fitness-point-v1")
        putOptional("id", value.id); putOptional("recordId", value.recordId); putOptional("parentTypeCode", value.parentTypeCode)
        putOptional("timestampMillis", value.timestampMillis); putOptional("elapsedMillis", value.elapsedMillis)
        putOptional("heartRateBpm", value.heartRateBpm); putOptional("steps", value.steps)
        putOptional("latitudeDecimal", value.latitudeDecimal); putOptional("longitudeDecimal", value.longitudeDecimal)
        putOptional("locationAuxiliaryDecimal", value.locationAuxiliaryDecimal); putOptional("distanceMeters", value.distanceMeters)
        putOptional("speedMetersPerSecondDecimal", value.speedMetersPerSecondDecimal); putOptional("calorieKcalDecimal", value.calorieKcalDecimal)
        putOptional("resumeCode", value.resumeCode); putOptional("swimTripsDecimal", value.swimTripsDecimal)
        putOptional("swimDistanceRaw", value.swimDistanceRaw); putOptional("swimStrokeRaw", value.swimStrokeRaw)
        putOptional("swimTypeRaw", value.swimTypeRaw); putOptional("elevationMetersDecimal", value.elevationMetersDecimal)
        putOptional("strideFrequencyStepsPerMinute", value.strideFrequencyStepsPerMinute); put("mode", value.mode.name)
        value.swimming?.let { swimming -> put("swimming", buildJsonObject {
            putOptional("tripsDecimal", swimming.tripsDecimal); putOptional("strokeCount", swimming.strokeCount)
            putOptional("strokeTypeCode", swimming.strokeTypeCode); putOptional("stroke", swimming.stroke?.name)
        }) }
        value.lap?.let { lap -> put("lap", buildJsonObject {
            putOptional("number", lap.number); putOptional("numberDecimal", lap.numberDecimal)
            putOptional("durationMillis", lap.durationMillis); putOptional("distanceMeters", lap.distanceMeters)
        }) }
        putAnnotations(value.sentinelColumns, value.unknownColumns, value.issues)
    }.toString()

    private fun JsonObjectBuilder.putOptional(key: String, value: String?) { if (value != null) put(key, value) }
    private fun JsonObjectBuilder.putOptional(key: String, value: Long?) { if (value != null) put(key, value) }
    private fun JsonObjectBuilder.putIssues(issues: Set<HealthFitnessIssue>) {
        put("issues", buildJsonArray { issues.forEach { issue -> add(buildJsonObject {
            put("path", issue.path); put("kind", issue.kind.name)
        }) } })
    }
    private fun JsonObjectBuilder.putAnnotations(sentinels: Set<String>, unknown: Set<String>, issues: Set<HealthFitnessIssue>) {
        put("sentinelColumns", JsonArray(sentinels.map(::JsonPrimitive)))
        put("unknownColumns", JsonArray(unknown.map(::JsonPrimitive)))
        putIssues(issues)
    }
}
