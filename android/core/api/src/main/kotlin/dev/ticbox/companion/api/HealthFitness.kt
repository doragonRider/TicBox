package dev.ticbox.companion.api

/** Fitness RECORD/POINT are independent of TicHealth data_point and the ten-column SPORT summary. */
enum class HealthFitnessIssueKind {
    MALFORMED_ROW, PARTIAL_SOURCE_ROW, MISSING_FIELD, INVALID_INTEGER, VALUE_NOT_NUMERIC,
    NON_FINITE_VALUE, UNEXPECTED_CELL_KIND, NONSTANDARD_FLAG, UNKNOWN_SPORT_TYPE,
    UNKNOWN_TARGET_TYPE, INVALID_TIME_RANGE, OPEN_RECORD, MALFORMED_GROUP_BOUNDARY,
    MALFORMED_EXTENSION, UNRECOGNIZED_EXTENSION, OPEN_HIIT_STAGE, PARENT_UNAVAILABLE, PARENT_MISMATCH,
}

/** A column/path and a decision category, never the offending health value or identifier. */
data class HealthFitnessIssue(val path: String, val kind: HealthFitnessIssueKind) {
    override fun toString() = "HealthFitnessIssue(kind=$kind, contents=<redacted>)"
}

enum class HealthFitnessMode { SWIMMING, LAND, UNCONFIRMED }
enum class HealthFitnessScoreMeaning { ACTION_COUNT, RUNNING_AI_SCORE, UNCONFIRMED }

/** Explicit storage codes from the target translator, not SportDataType ordinals. */
enum class HealthFitnessTargetType(val code: Long, val unit: String?) {
    DURATION(1, "ms"), DISTANCE(2, "m"), CALORIE(3, "kcal"), STEPS(4, "steps"),
    HEART_RATE(5, "bpm"), SPEED(6, "m/s"), PACE(7, null), GPS_STATUS(8, "code"),
    LOCATION(9, null), GROUPS(10, "groups"), SWIM_TRIPS(11, "trips"),
    SWIM_DISTANCE(12, "m"), SWIM_PACE(13, null);

    // The encoder supports 5 and 13 even though the stock decoder maps them to Unknown.
    companion object { fun fromCode(code: Long?) = entries.firstOrNull { it.code == code } }
}

enum class HealthFitnessStroke(val code: Long) {
    FREESTYLE(1), BREASTSTROKE(2), BACKSTROKE(3), BUTTERFLY(4);
    companion object { fun fromCode(code: Long?) = entries.firstOrNull { it.code == code } }
}

enum class HealthFitnessGroupState { TRAIN, REST }

/** Original position is retained even if one boundary is unreadable, preserving later state parity. */
data class HealthFitnessGroupBoundary(val index: Int, val rawToken: String, val elapsedMillis: Long?) {
    override fun toString() = "HealthFitnessGroupBoundary(index=$index, contents=<redacted>)"
}
data class HealthFitnessGroupInterval(
    val index: Int, val groupNumber: Int, val state: HealthFitnessGroupState,
    val startElapsedMillis: Long?, val endElapsedMillis: Long?,
) {
    override fun toString() = "HealthFitnessGroupInterval(index=$index, state=$state, contents=<redacted>)"
}
/** An effective-training-time axis, independent of wall clock and pauses. */
data class HealthFitnessGroups(
    val boundaries: List<HealthFitnessGroupBoundary>, val intervals: List<HealthFitnessGroupInterval>,
    val currentGroupNumber: Int, val currentState: HealthFitnessGroupState,
) {
    override fun toString() = "HealthFitnessGroups(boundaries=${boundaries.size}, contents=<redacted>)"
}

data class HealthFitnessRecovery(val rawJson: String, val sportName: String?, val durationMillis: Long?) {
    override fun toString() = "HealthFitnessRecovery(contents=<redacted>)"
}
/** Includes malformed array entries with their original JSON and nullable recognized fields. */
data class HealthFitnessHiitStage(
    val index: Int, val rawJson: String, val typeCode: Long?, val startElapsedMillis: Long?,
    val targetDurationMillis: Long?, val durationMillis: Long?, val actionTimes: Long?,
    val issues: Set<HealthFitnessIssue>,
) {
    val sportName get() = HealthFitnessSportTypes.name(typeCode)
    val open: Boolean? get() = durationMillis?.let { it == -1L }
    val rest: Boolean? get() = typeCode?.let { it == 35L || it == 36L }
    override fun toString() = "HealthFitnessHiitStage(index=$index, contents=<redacted>)"
}
enum class HealthFitnessExtraKind { NONE, RECOVERY, HIIT, UNRECOGNIZED }
data class HealthFitnessExtra(
    val kind: HealthFitnessExtraKind, val recovery: HealthFitnessRecovery?,
    val stages: List<HealthFitnessHiitStage>,
) {
    override fun toString() = "HealthFitnessExtra(kind=$kind, contents=<redacted>)"
}

data class HealthFitnessSwimmingRecord(
    val tripsDecimal: String?, val poolLengthMeters: Long?, val strokeCount: Long?,
) {
    override fun toString() = "HealthFitnessSwimmingRecord(contents=<redacted>)"
}
data class HealthFitnessLandRecord(
    val vo2maxMlKgMinDecimal: String?, val lapCount: Long?,
    val lastLapDistanceMeters: Long?, val lastLapDurationMillis: Long?,
) {
    override fun toString() = "HealthFitnessLandRecord(contents=<redacted>)"
}
data class HealthFitnessSwimmingPoint(
    val tripsDecimal: String?, val strokeCount: Long?, val strokeTypeCode: Long?,
) {
    val stroke get() = HealthFitnessStroke.fromCode(strokeTypeCode)
    override fun toString() = "HealthFitnessSwimmingPoint(contents=<redacted>)"
}
data class HealthFitnessLapPoint(
    /** Exact count; a non-integral vendor value stays in numberDecimal and the original row. */
    val number: Long?, val numberDecimal: String?, val durationMillis: Long?, val distanceMeters: Long?,
) {
    override fun toString() = "HealthFitnessLapPoint(contents=<redacted>)"
}

/**
 * Every recognized RECORD column is represented here. Decimal strings retain precision and all
 * original columns, SQL NULLs, malformed values and future fields remain in [raw]. Known vendor
 * placeholders are annotated in [sentinelColumns] while the typed source values stay unchanged.
 * Swimming/land views omit those annotated placeholders; they do not rewrite the source row.
 */
data class HealthFitnessRecord(
    val raw: HealthRawRecord,
    val id: Long?, val hash: String?, val typeCode: Long?, val targetTypeCode: Long?,
    val targetValueDecimal: String?, val accountId: String?, val vendorSyncCode: Long?, val deletedCode: Long?,
    val startMillis: Long?, val endMillis: Long?,
    /** Reserved RECORD.TIMESTAMP; the observed translator does not establish its time semantics. */
    val timestampRaw: Long?,
    val durationMillis: Long?, val distanceMeters: Long?, val calorieKcalDecimal: String?,
    val steps: Long?, val averageHeartRateBpm: Long?, val rawGroups: String?,
    val swimTripsDecimal: String?, val swimDistanceRaw: Long?, val swimPoolLengthRaw: Long?, val swimStrokeRaw: Long?,
    val deviceId: String?, val sessionModeCode: Long?,
    val maxElevationMetersDecimal: String?, val minElevationMetersDecimal: String?,
    val cumulativeUpMetersDecimal: String?, val cumulativeDownMetersDecimal: String?,
    val scoreDecimal: String?, val rawExtra: String?,
    val mode: HealthFitnessMode, val swimming: HealthFitnessSwimmingRecord?, val land: HealthFitnessLandRecord?,
    val groups: HealthFitnessGroups?, val extra: HealthFitnessExtra,
    val sentinelColumns: Set<String>, val unknownColumns: Set<String>, val issues: Set<HealthFitnessIssue>,
) {
    val sportName get() = HealthFitnessSportTypes.name(typeCode)
    val targetType get() = HealthFitnessTargetType.fromCode(targetTypeCode)
    val targetUnit get() = targetType?.unit
    val scoreMeaning get() = when (typeCode) {
        25L, 28L, 30L, 19L, 63L -> HealthFitnessScoreMeaning.ACTION_COUNT
        0L -> HealthFitnessScoreMeaning.RUNNING_AI_SCORE
        else -> HealthFitnessScoreMeaning.UNCONFIRMED
    }
    /** Stock RECORD.SYNCED uses bit 0. This does not represent this client's sync checkpoint. */
    val vendorSynced: Boolean? get() = vendorSyncCode?.let { (it and 1L) != 0L }
    val deleted: Boolean? get() = when (deletedCode) { 0L -> false; 1L -> true; else -> null }
    val autoDetected: Boolean? get() = when (sessionModeCode) { 0L -> false; 1L -> true; else -> null }
    override fun toString() = "HealthFitnessRecord(contents=<redacted>)"
}

/**
 * Sparse fitness POINT with both clocks retained. distance/steps/calorie are cumulative, not deltas.
 * ACC is [locationAuxiliaryDecimal]: the observed HealthServices writer uses bearing; older paths
 * and the coordinate reference system remain unconfirmed. Zero coordinates do not invalidate a row.
 * POINT.SWIM_DISTANCE is retained as a reserved/raw field, not invented as a measured distance.
 */
data class HealthFitnessPoint(
    val raw: HealthRawRecord,
    val id: Long?, val recordId: Long?, val timestampMillis: Long?, val elapsedMillis: Long?,
    val heartRateBpm: Long?, val steps: Long?, val latitudeDecimal: String?, val longitudeDecimal: String?,
    val locationAuxiliaryDecimal: String?, val distanceMeters: Long?, val speedMetersPerSecondDecimal: String?,
    val calorieKcalDecimal: String?, val resumeCode: Long?, val swimTripsDecimal: String?,
    val swimDistanceRaw: Long?, val swimStrokeRaw: Long?, val swimTypeRaw: Long?,
    val elevationMetersDecimal: String?, val strideFrequencyStepsPerMinute: Long?,
    val parentTypeCode: Long?, val mode: HealthFitnessMode,
    val swimming: HealthFitnessSwimmingPoint?, val lap: HealthFitnessLapPoint?,
    val sentinelColumns: Set<String>, val unknownColumns: Set<String>, val issues: Set<HealthFitnessIssue>,
) {
    val resumed: Boolean? get() = when (resumeCode) { 0L -> false; 1L -> true; else -> null }
    override fun toString() = "HealthFitnessPoint(contents=<redacted>)"
}

/** Inclusive wall-clock overlap, same source isolation/order as HealthRecordQuery; no I/O acquisition. */
data class HealthFitnessRecordQuery(
    val source: HealthRecordSource.LocalDevice,
    val fromMillis: Long? = null, val toMillis: Long? = null, val typeCode: Long? = null,
    val includeDeleted: Boolean = false, val offset: Int = 0, val limit: Int = 100, val recordKey: String? = null,
) {
    override fun toString() = "HealthFitnessRecordQuery(filters=<redacted>, offset=$offset, limit=$limit)"
}
/** Selects one database RECORD._id, never its possibly repeated HASH; time filters use TIMESTAMP. */
data class HealthFitnessPointQuery(
    val source: HealthRecordSource.LocalDevice, val recordId: Long,
    val fromMillis: Long? = null, val toMillis: Long? = null, val offset: Int = 0, val limit: Int = 200,
) {
    override fun toString() = "HealthFitnessPointQuery(filters=<redacted>, offset=$offset, limit=$limit)"
}
data class HealthFitnessRecordPage(val items: List<HealthFitnessRecord>, val nextOffset: Int?,
    val status: HealthRecordReadStatus = HealthRecordReadStatus.AVAILABLE)
data class HealthFitnessPointPage(val items: List<HealthFitnessPoint>, val nextOffset: Int?,
    val status: HealthRecordReadStatus = HealthRecordReadStatus.AVAILABLE)

/** Explicit source codes. Unknown numbers remain in the record instead of becoming Unknown/-1. */
object HealthFitnessSportTypes {
    // Source: docs/spec/health-types.json enums.sport_type.
    val names: Map<Long, String> = mapOf(
        -1L to "Unknown",
        -2L to "More",
        0L to "OutdoorRunning",
        1L to "OutdoorWalk",
        2L to "IndoorRunning",
        3L to "OutdoorBike",
        4L to "FreeWorkout",
        5L to "AutoWalking",
        6L to "AutoRunning",
        7L to "BandRunning",
        8L to "BandAutoWalking",
        9L to "Swimming",
        10L to "AutoCycling",
        11L to "AutoSwimming",
        12L to "IndoorCycling",
        13L to "RowingMachine",
        14L to "EllipticalMachine",
        15L to "Yoga",
        16L to "BodyMechanics",
        17L to "Climbing",
        18L to "OffRoad",
        19L to "RopeSkipping",
        20L to "Gymnastics",
        21L to "Football",
        22L to "Basketball",
        23L to "Spinning",
        24L to "Hiit",
        25L to "Burpee",
        26L to "MountainClimber",
        27L to "HighKnees",
        28L to "SquatJumps",
        29L to "Plank",
        30L to "JumpingJack",
        31L to "TwistingMountainClimber",
        32L to "ReverseCrunch",
        33L to "SquatPunch",
        34L to "Bridge",
        35L to "Rest",
        36L to "GroupRest",
        37L to "HiitOther",
        50L to "Pilates",
        51L to "Taekwondo",
        52L to "Badminton",
        53L to "TableTennis",
        54L to "Skating",
        55L to "OpenWaterSwimming",
        56L to "Tennis",
        57L to "Baseball",
        58L to "Volleyball",
        59L to "Cricket",
        60L to "Rugby",
        61L to "Hockey",
        62L to "Dance",
        63L to "AbdominalCurl",
        64L to "Crawl",
        65L to "BendOverAndClimbTheRope",
        66L to "OneLegBalance",
        67L to "ChestExpandingExercise",
        68L to "WristRotation",
        69L to "SideStretch",
        70L to "SideLift",
        71L to "Recommend",
        72L to "Pingju",
        73L to "Strike",
        74L to "Swing",
        75L to "StraightBoxing",
        76L to "BowToRow",
        77L to "Rowing",
        78L to "Paddle",
        79L to "InclinedLowerSwingArm",
        80L to "HandDepression",
        81L to "HandDownAndBackSwing",
        82L to "BodyFlexionAndRotation",
        83L to "BendingLift",
        84L to "HardPull",
        85L to "UpperAndLowerSwingArms",
        86L to "OneHandedSmash",
        87L to "HipHighFive",
        88L to "PullUp",
        89L to "BackPullUp",
        90L to "HighLegRun",
        91L to "SittingPullDown",
        92L to "GoFishing",
        93L to "HuLaHoop",
        94L to "Riding",
        95L to "Polo",
        96L to "Golf",
        97L to "IceHockey",
        98L to "Squash",
        99L to "Archery",
        100L to "Weightlifting",
        101L to "PaddlePaddle",
        102L to "Sailboat",
        103L to "Surfing",
        104L to "AMartialArt",
        105L to "Shooting",
        106L to "TugOfWar",
        107L to "DifficultRockClimbing",
        108L to "FieldRockClimbing",
        109L to "Bouldering",
        110L to "RollerSkating",
        111L to "Fencing",
        112L to "OffRoadVehicle",
        113L to "AtvQuadFrenzy",
        114L to "Skydiving",
        115L to "BungeeJumping",
        116L to "Squat",
        117L to "Skate",
        118L to "TaijiBoxing",
        119L to "WaterPolo",
        120L to "BeachVolleyball",
        121L to "Snorkeling",
        122L to "InclineDumbbellPress",
        123L to "StandingOutsideStretch",
        124L to "MilitaryPress",
        125L to "ArnoldRecommended",
        126L to "ShoulderWrap",
        127L to "AlternatingFrontFlatLift",
        128L to "UpwardInclinedDumbbellCurl",
        129L to "FigureSkating",
        130L to "BattlingRopes",
        131L to "LegPress",
        132L to "TreadmillRunning",
        133L to "MountainBiking",
        134L to "Kayaking",
        135L to "Hike",
        136L to "InlineSkating",
        137L to "CoreTraining",
        138L to "StairStepper",
        139L to "Boxing",
        140L to "Crossfit",
        141L to "BackExtension",
        142L to "BenchPress",
        143L to "BicepsCurl",
        144L to "LatPullDown",
        145L to "MedicineBallSlam",
        146L to "ReverseFly",
        147L to "ShoulderPress",
        148L to "TricepsPushDown",
        149L to "BearCrawl",
        150L to "Crunch",
        151L to "GluteBridge",
        152L to "RussianTwists",
        153L to "SingleLegBalance",
        154L to "CrossCountrySkiing",
        155L to "IceSkating",
        156L to "AmericanFootball",
        157L to "AustralianFootball",
        158L to "RacquetBall",
        159L to "Softball",
        160L to "Flexibility",
        161L to "Stretching",
    )
    fun name(code: Long?): String? = names[code]
}
