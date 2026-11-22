package dev.ticbox.companion.engine.health

import dev.ticbox.companion.api.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Artificial rows exercise storage contracts; no private device data is used here. */
class HealthFitnessParserTest {
    private val source = HealthRecordSource.LocalDevice("synthetic-profile", PeerAddress("AA:BB:CC:DD:EE:FF"))
    private fun raw(columns: JsonObject, point: Boolean = false, partial: Boolean = false,
        owner: HealthRecordSource.LocalDevice = source) = HealthRawRecord(owner,
        if (point) HealthRecordKind.FITNESS_POINT else HealthRecordKind.FITNESS_RECORD,
        "synthetic-key", null, null, null, null, null, false, 0,
        buildJsonObject { put("columns", columns); if (partial) put("partial", true) }.toString())

    private fun record(type: Long = 0, changes: JsonObjectBuilder.() -> Unit = {}): HealthFitnessRecord =
        HealthFitnessParser.parseRecord(raw(buildJsonObject {
            put("_id", 9_007_199_254_740_993L); put("HASH", "synthetic-opaque-training-key")
            put("TYPE", type); put("SYNCED", 0); put("DELETED", 0)
            put("START_TIME", 10_000); put("END_TIME", 70_000); put("DURATION", 50_000)
            changes()
        }))

    private fun point(changes: JsonObjectBuilder.() -> Unit = {}): HealthRawRecord = raw(buildJsonObject {
        put("_id", 9_007_199_254_741_019L); put("RECORD_ID", 9_007_199_254_740_993L)
        put("TIME", 30_000); put("TIMESTAMP", 55_000)
        put("LAT", "0"); put("LON", "0"); put("ACC", "0")
        changes()
    }, point = true)

    @Test fun `all record columns retain units precision identifiers flags and reserved values`() {
        val training = record {
            put("TARGET_TYPE", 5); put("TARGET_VALUE", "155.0000"); put("ACCOUNT_ID", "synthetic-account")
            put("SYNCED", 3); put("TIMESTAMP", 123_456); put("DISTANCE", 5_003)
            put("CALORIE", "123.456789012345678901"); put("STEP", 6000); put("HEART", 143)
            put("GROUPS", "10000,12000"); put("SWIM_TRIPS", "52.5"); put("SWIM_DISTANCE", 5)
            put("SWIM_POOL_LENGTH", 1000); put("SWIM_STROKE", 99_000)
            put("DEVICE_ID", "synthetic-device"); put("SESSION_MODE", 1)
            put("MAX_ELEVATION", "-2.5"); put("MIN_ELEVATION", "-25.75")
            put("CUMULATIVE_UP", "48.25"); put("CUMULATIVE_DOWN", "52.125")
            put("SCORE", "70.25"); put("EXTRA", "{\"type\":\"OutdoorRunning\",\"recoveryTime\":1000,\"future\":true}")
            put("FUTURE", buildJsonObject { put("value", "preserved") })
        }
        assertEquals(29, HealthFitnessParser.recordColumns.size)
        assertEquals(9_007_199_254_740_993L, training.id)
        assertEquals("synthetic-opaque-training-key", training.hash)
        assertEquals(HealthFitnessTargetType.HEART_RATE, training.targetType)
        assertEquals("bpm", training.targetUnit); assertEquals("155.0000", training.targetValueDecimal)
        assertEquals("synthetic-account", training.accountId); assertEquals(3L, training.vendorSyncCode)
        assertEquals(true, training.vendorSynced); assertEquals(false, training.deleted)
        assertEquals(10_000L, training.startMillis); assertEquals(70_000L, training.endMillis)
        assertEquals(50_000L, training.durationMillis); assertEquals(123_456L, training.timestampRaw)
        assertEquals(5_003L, training.distanceMeters); assertEquals("123.456789012345678901", training.calorieKcalDecimal)
        assertEquals(6000L, training.steps); assertEquals(143L, training.averageHeartRateBpm)
        assertEquals("10000,12000", training.rawGroups); assertEquals("synthetic-device", training.deviceId)
        assertEquals(1L, training.sessionModeCode); assertEquals(true, training.autoDetected)
        assertEquals("-2.5", training.maxElevationMetersDecimal); assertEquals("-25.75", training.minElevationMetersDecimal)
        assertEquals("48.25", training.cumulativeUpMetersDecimal); assertEquals("52.125", training.cumulativeDownMetersDecimal)
        assertEquals("70.25", training.scoreDecimal); assertEquals(HealthFitnessScoreMeaning.RUNNING_AI_SCORE, training.scoreMeaning)
        assertEquals(HealthFitnessMode.LAND, training.mode); assertNull(training.swimming)
        assertEquals(HealthFitnessLandRecord("52.5", 5, 1000, 99_000), training.land)
        assertEquals(HealthFitnessExtraKind.RECOVERY, training.extra.kind)
        assertEquals(1000L, training.extra.recovery?.durationMillis) // No stock 30-minute write threshold reapplied.
        assertEquals(setOf("FUTURE"), training.unknownColumns); assertTrue(training.issues.isEmpty())
        assertTrue("preserved" in training.raw.json); assertTrue("future" in training.rawExtra!!)
    }

    @Test fun `point fields keep active and wall clocks separate and interpret lap event from parent`() {
        val training = record()
        val sample = HealthFitnessParser.parsePoint(point {
            put("HEART", 150); put("STEP", -1); put("DISTANCE", -1); put("SPEED", "4.125")
            put("CALORIE", "12.25"); put("RESUME", 1); put("SWIM_TRIPS", "2.0")
            put("SWIM_DISTANCE", 123); put("SWIM_STROKE", 240_001); put("SWIM_TYPE", 1001)
            put("ELEVATION", "-10.5"); put("STRIDE_FREQ", 177); put("future_point", "opaque")
        }, training)
        assertEquals(19, HealthFitnessParser.pointColumns.size)
        assertEquals(9_007_199_254_741_019L, sample.id); assertEquals(training.id, sample.recordId)
        assertEquals(30_000L, sample.elapsedMillis); assertEquals(55_000L, sample.timestampMillis)
        assertEquals(150L, sample.heartRateBpm); assertEquals(-1L, sample.steps); assertEquals(-1L, sample.distanceMeters)
        assertEquals("4.125", sample.speedMetersPerSecondDecimal); assertEquals("12.25", sample.calorieKcalDecimal)
        assertEquals("0", sample.latitudeDecimal); assertEquals("0", sample.longitudeDecimal)
        assertEquals("0", sample.locationAuxiliaryDecimal); assertEquals(true, sample.resumed)
        assertEquals("2.0", sample.swimTripsDecimal); assertEquals(123L, sample.swimDistanceRaw)
        assertEquals(240_001L, sample.swimStrokeRaw); assertEquals(1001L, sample.swimTypeRaw)
        assertEquals("-10.5", sample.elevationMetersDecimal); assertEquals(177L, sample.strideFrequencyStepsPerMinute)
        assertNull(sample.swimming)
        assertEquals(HealthFitnessLapPoint(2, "2.0", 240_001, 1001), sample.lap)
        assertEquals(setOf("STEP", "DISTANCE"), sample.sentinelColumns)
        assertEquals(setOf("future_point"), sample.unknownColumns); assertTrue(sample.issues.isEmpty())
    }

    @Test fun `all swimming codes use fraction trips and raw swim distance never becomes lap`() {
        for (type in listOf(9L, 11L, 55L)) {
            val training = record(type) {
                put("SWIM_TRIPS", "3.5"); put("SWIM_DISTANCE", 777)
                put("SWIM_POOL_LENGTH", 25); put("SWIM_STROKE", 80)
            }
            assertEquals(HealthFitnessSwimmingRecord("3.5", 25, 80), training.swimming)
            assertNull(training.land); assertEquals(777L, training.swimDistanceRaw)
            val sample = HealthFitnessParser.parsePoint(point {
                put("SWIM_TRIPS", "2.5"); put("SWIM_STROKE", 40); put("SWIM_TYPE", 2)
            }, training)
            assertEquals(HealthFitnessSwimmingPoint("2.5", 40, 2), sample.swimming)
            assertEquals(HealthFitnessStroke.BREASTSTROKE, sample.swimming?.stroke)
            assertNull(sample.lap); assertTrue(sample.issues.isEmpty())
        }
    }

    @Test fun `unknown modes and missing or mismatched parents preserve fields without guessing lap or swim`() {
        val future = record(100_000) { put("SWIM_TRIPS", "4"); put("SWIM_DISTANCE", 10) }
        assertEquals(100_000L, future.typeCode); assertNull(future.sportName)
        assertEquals(HealthFitnessMode.UNCONFIRMED, future.mode); assertNull(future.land); assertNull(future.swimming)
        assertTrue(future.issues.any { it.kind == HealthFitnessIssueKind.UNKNOWN_SPORT_TYPE })
        val original = point { put("SWIM_TRIPS", "4"); put("SWIM_TYPE", 1000) }
        val missing = HealthFitnessParser.parsePoint(original)
        assertNull(missing.lap); assertNull(missing.swimming); assertEquals(1000L, missing.swimTypeRaw)
        assertTrue(missing.issues.any { it.kind == HealthFitnessIssueKind.PARENT_UNAVAILABLE })
        val otherId = HealthFitnessParser.parsePoint(original, record { put("_id", 77) })
        assertEquals(HealthFitnessMode.UNCONFIRMED, otherId.mode); assertNull(otherId.parentTypeCode)
        assertTrue(otherId.issues.any { it.kind == HealthFitnessIssueKind.PARENT_MISMATCH })
        val otherSource = record().copy(raw = record().raw.copy(source = source.copy(localUserId = "other")))
        assertTrue(HealthFitnessParser.parsePoint(original, otherSource).issues.any { it.kind == HealthFitnessIssueKind.PARENT_MISMATCH })
    }

    @Test fun `known placeholders remain in source typed fields and zero metrics stay present`() {
        val training = record {
            put("DISTANCE", 0); put("CALORIE", "0.00"); put("HEART", 0); put("STEP", 0)
            put("SWIM_TRIPS", -1); put("SWIM_DISTANCE", -1); put("SWIM_POOL_LENGTH", -1); put("SWIM_STROKE", -1)
            put("MAX_ELEVATION", -2_147_483_648L); put("MIN_ELEVATION", 2_147_483_648L)
        }
        assertEquals(0L, training.distanceMeters); assertEquals(0L, training.averageHeartRateBpm)
        assertEquals("0.00", training.calorieKcalDecimal); assertEquals("-1", training.swimTripsDecimal)
        assertEquals(-1L, training.swimDistanceRaw); assertNull(training.land?.vo2maxMlKgMinDecimal)
        assertTrue(training.sentinelColumns.containsAll(setOf("SWIM_TRIPS", "SWIM_DISTANCE", "SWIM_POOL_LENGTH", "SWIM_STROKE",
            "MAX_ELEVATION", "MIN_ELEVATION")))
        val sample = HealthFitnessParser.parsePoint(point {
            put("HEART", 0); put("STEP", 0); put("SPEED", 0); put("STRIDE_FREQ", 0); put("ELEVATION", -2_147_483_648L)
        }, training)
        assertEquals(0L, sample.heartRateBpm); assertEquals(0L, sample.strideFrequencyStepsPerMinute)
        assertEquals("0", sample.speedMetersPerSecondDecimal); assertEquals(setOf("ELEVATION"), sample.sentinelColumns)
        assertEquals("-2147483648", sample.elevationMetersDecimal)
    }

    @Test fun `malformed group boundary retains slot parity and valid values after the bad slot`() {
        val training = record(4) { put("GROUPS", "10000,bad,30000,30000,40000"); put("DURATION", 60_000) }
        val groups = training.groups!!
        assertEquals(listOf(10_000L, null, 30_000L, 30_000L, 40_000L), groups.boundaries.map { it.elapsedMillis })
        assertEquals("bad", groups.boundaries[1].rawToken)
        assertEquals(listOf(HealthFitnessGroupState.TRAIN, HealthFitnessGroupState.REST, HealthFitnessGroupState.TRAIN,
            HealthFitnessGroupState.REST, HealthFitnessGroupState.TRAIN, HealthFitnessGroupState.REST), groups.intervals.map { it.state })
        assertNull(groups.intervals[1].endElapsedMillis); assertNull(groups.intervals[2].startElapsedMillis)
        assertEquals(30_000L, groups.intervals[3].startElapsedMillis); assertEquals(30_000L, groups.intervals[3].endElapsedMillis)
        assertEquals(60_000L, groups.intervals.last().endElapsedMillis)
        assertEquals(3, groups.currentGroupNumber); assertEquals(HealthFitnessGroupState.REST, groups.currentState)
        assertTrue(training.issues.contains(HealthFitnessIssue("GROUPS[1]", HealthFitnessIssueKind.MALFORMED_GROUP_BOUNDARY)))
    }

    @Test fun `out of order groups and open training are diagnostic and do not erase rows`() {
        val training = record(140) { put("GROUPS", "10000,5000"); put("END_TIME", 0); put("DURATION", -1) }
        assertEquals(0L, training.endMillis); assertEquals(-1L, training.durationMillis)
        assertEquals(5000L, training.groups!!.intervals[1].endElapsedMillis)
        assertNull(training.groups!!.intervals.last().endElapsedMillis)
        assertTrue(training.issues.any { it.kind == HealthFitnessIssueKind.INVALID_TIME_RANGE })
        assertTrue(training.issues.any { it.kind == HealthFitnessIssueKind.OPEN_RECORD })
    }

    @Test fun `hiit preserves unknown fields malformed entries later good stages and open rest`() {
        val training = record(24) { put("EXTRA", """[
            {"type":25,"start_time":0,"target_duration":30000,"duration":29000,"times":12,"future":"kept"},
            "bad-stage",
            {"type":36,"start_time":29000,"target_duration":5000,"duration":-1,"times":0},
            {"type":100000,"start_time":34000,"target_duration":"bad","duration":1000,"times":5}
        ]""") }
        assertEquals(HealthFitnessExtraKind.HIIT, training.extra.kind)
        val stages = training.extra.stages
        assertEquals(4, stages.size); assertEquals("Burpee", stages[0].sportName)
        assertEquals(12L, stages[0].actionTimes); assertTrue("future" in stages[0].rawJson)
        assertEquals("\"bad-stage\"", stages[1].rawJson); assertNull(stages[1].typeCode)
        assertEquals(true, stages[2].rest); assertEquals(true, stages[2].open)
        assertEquals(-1L, stages[2].durationMillis); assertEquals(0L, stages[2].actionTimes)
        assertEquals(100000L, stages[3].typeCode); assertEquals(1000L, stages[3].durationMillis)
        assertNull(stages[3].targetDurationMillis); assertEquals(5L, stages[3].actionTimes)
        assertTrue(training.issues.any { it.path == "EXTRA[3].target_duration" && it.kind == HealthFitnessIssueKind.INVALID_INTEGER })
    }

    @Test fun `extension objects strings and nulls preserve unrecognized shapes`() {
        val unknown = record { put("EXTRA", "{\"newExtension\":{\"value\":42}}") }
        assertEquals(HealthFitnessExtraKind.UNRECOGNIZED, unknown.extra.kind)
        assertTrue("newExtension" in unknown.rawExtra!!)
        val malformed = record { put("EXTRA", "not:json:yet") }
        assertEquals(HealthFitnessExtraKind.UNRECOGNIZED, malformed.extra.kind)
        assertTrue(malformed.issues.any { it.kind == HealthFitnessIssueKind.MALFORMED_EXTENSION ||
            it.kind == HealthFitnessIssueKind.UNRECOGNIZED_EXTENSION })
        assertEquals(HealthFitnessExtraKind.NONE, record { put("EXTRA", JsonNull) }.extra.kind)
        val futureArray = record { put("EXTRA", "[\"future-format\",{\"unrelated\":7}]") }
        assertEquals(HealthFitnessExtraKind.UNRECOGNIZED, futureArray.extra.kind)
        assertTrue("future-format" in futureArray.rawExtra!!)
    }

    @Test fun `bad numeric and flag cells leave the remaining training and original cells usable`() {
        val payload = raw(buildJsonObject {
            put("_id", "1.5"); put("HASH", "keep-this-key"); put("TYPE", 0); put("SYNCED", true); put("DELETED", 7)
            put("DURATION", "NaN"); put("DISTANCE", "9223372036854775808"); put("CALORIE", "Infinity")
            put("HEART", 76); put("STEP", JsonNull); put("TARGET_VALUE", "bad-value")
            put("EXTRA", buildJsonObject { put("type", "OutdoorRunning"); put("recoveryTime", 1000) })
        }, partial = true)
        val training = HealthFitnessParser.parseRecord(payload)
        assertNull(training.id); assertNull(training.durationMillis); assertNull(training.distanceMeters)
        assertNull(training.calorieKcalDecimal); assertNull(training.steps)
        assertEquals(76L, training.averageHeartRateBpm); assertEquals("keep-this-key", training.hash)
        assertEquals(true, training.vendorSynced); assertEquals(7L, training.deletedCode); assertNull(training.deleted)
        assertEquals(1000L, training.extra.recovery?.durationMillis)
        assertTrue(training.issues.map { it.kind }.containsAll(setOf(HealthFitnessIssueKind.INVALID_INTEGER,
            HealthFitnessIssueKind.NON_FINITE_VALUE, HealthFitnessIssueKind.NONSTANDARD_FLAG,
            HealthFitnessIssueKind.VALUE_NOT_NUMERIC, HealthFitnessIssueKind.PARTIAL_SOURCE_ROW,
            HealthFitnessIssueKind.UNEXPECTED_CELL_KIND)))
        assertTrue("bad-value" in training.raw.json)
    }

    @Test fun `all explicit sport and target codes work without ordinal assumptions`() {
        assertEquals(152, HealthFitnessSportTypes.names.size)
        assertEquals("Unknown", HealthFitnessSportTypes.name(-1)); assertEquals("More", HealthFitnessSportTypes.name(-2))
        assertEquals("OutdoorRunning", HealthFitnessSportTypes.name(0)); assertEquals("Hiit", HealthFitnessSportTypes.name(24))
        assertEquals("Hike", HealthFitnessSportTypes.name(135)); assertEquals("Crossfit", HealthFitnessSportTypes.name(140))
        for (target in HealthFitnessTargetType.entries) {
            val training = record { put("TARGET_TYPE", target.code); put("TARGET_VALUE", "1.25") }
            assertEquals(target, training.targetType); assertEquals("1.25", training.targetValueDecimal)
            assertTrue(training.issues.none { it.kind == HealthFitnessIssueKind.UNKNOWN_TARGET_TYPE })
        }
        val future = record { put("TARGET_TYPE", 1000); put("TARGET_VALUE", "-0.125") }
        assertNull(future.targetType); assertEquals(1000L, future.targetTypeCode); assertEquals("-0.125", future.targetValueDecimal)
        assertNull(future.targetUnit); assertTrue(future.issues.any { it.kind == HealthFitnessIssueKind.UNKNOWN_TARGET_TYPE })
    }

    @Test fun `only five confirmed counting modes label score as action count`() {
        for (type in listOf(25L, 28L, 30L, 19L, 63L))
            assertEquals(HealthFitnessScoreMeaning.ACTION_COUNT, record(type) { put("SCORE", "123.5") }.scoreMeaning)
        assertEquals(HealthFitnessScoreMeaning.RUNNING_AI_SCORE, record(0).scoreMeaning)
        assertEquals(HealthFitnessScoreMeaning.UNCONFIRMED, record(24).scoreMeaning)
    }

    @Test fun `case variants and malformed rows are best effort instead of exceptions`() {
        val direct = raw(buildJsonObject {}).copy(json = """{"_id":"1.000","hash":"opaque","type":"135","synced":0,"deleted":0,"duration":"2000"}""")
        val training = HealthFitnessParser.parseRecord(direct)
        assertEquals(1L, training.id); assertEquals(135L, training.typeCode)
        assertEquals(2000L, training.durationMillis); assertTrue(training.unknownColumns.isEmpty())
        val broken = HealthFitnessParser.parseRecord(direct.copy(json = "[\"not-a-row\"]"))
        assertTrue(broken.issues.any { it.kind == HealthFitnessIssueKind.MALFORMED_ROW })
        assertNull(broken.typeCode)
    }

    @Test fun `encoders keep structured interpretations and metadata without toString leaking values`() {
        val training = record(24) { put("GROUPS", "10,20"); put("EXTRA", """[{"type":25,"start_time":0,"target_duration":30000,"duration":10000,"times":5}]""") }
        val encoded = Json.parseToJsonElement(HealthFitnessParser.encodeRecord(training)).jsonObject
        assertEquals("fitness-record-v1", encoded["schema"]?.jsonPrimitive?.content)
        assertEquals(training.id, encoded["id"]?.jsonPrimitive?.long)
        assertEquals(2, encoded["groups"]?.jsonObject?.get("boundaries")?.jsonArray?.size)
        assertEquals(5L, encoded["extra"]?.jsonObject?.get("stages")?.jsonArray?.single()?.jsonObject?.get("actionTimes")?.jsonPrimitive?.long)
        val sample = HealthFitnessParser.parsePoint(point { put("SWIM_TRIPS", 1); put("SWIM_TYPE", 1000); put("SWIM_STROKE", 4000) }, training)
        val savedPoint = Json.parseToJsonElement(HealthFitnessParser.encodePoint(sample)).jsonObject
        assertEquals(1000L, savedPoint["lap"]?.jsonObject?.get("distanceMeters")?.jsonPrimitive?.long)
        for (value in listOf(training, training.groups!!, training.groups!!.boundaries[0], training.extra,
            training.extra.stages[0], sample, sample.lap!!, HealthFitnessRecordQuery(source), HealthFitnessPointQuery(source, training.id!!))) {
            assertFalse("synthetic-opaque" in value.toString()); assertFalse("907199" in value.toString())
            assertTrue("redacted" in value.toString())
        }
    }
}
