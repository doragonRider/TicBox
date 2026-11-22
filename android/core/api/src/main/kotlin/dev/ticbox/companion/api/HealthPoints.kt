package dev.ticbox.companion.api

/** Stable numeric identity from the watch DataType enum; unknown codes remain usable in queries. */
enum class HealthPointType(val code: Long, val wireName: String) {
    HEART_RATE(1, "heart_rate"), MOTION(2, "sleep_motion"), LOCATION(3, ""),
    CALORIE(4, "calorie"), DISTANCE(5, "distance"), STEP_COUNT(6, "step_count"), EXERCISE(7, "exercise"),
    DELTA_CALORIE(8, "delta_calorie"), DELTA_DISTANCE(9, "delta_distance"), DELTA_STEP(10, "delta_step"), DELTA_EXERCISE(11, "delta_exercise"),
    PERIOD_EVENT(12, "period_event"), PERIOD_CYCLE_LENGTH(13, "period_cycle_length"), PERIOD_MENSTRUATION_LENGTH(14, "period_menstruation_length"),
    HEART_RATE_WARNING(15, "heart_rate_warning"), HEART_RATE_WARNING_FITNESS_UPPER_LIMIT(16, "heart_rate_warning_fitness_upper_limit"),
    HEART_RATE_WARNING_REST_UPPER_LIMIT(17, "heart_rate_warning_rest_upper_limit"), HEART_RATE_WARNING_REST_LOWER_LIMIT(18, "heart_rate_warning_rest_lower_limit"),
    GOAL_TODAY_STEP(19, "goal_today_step"), GOAL_TODAY_ACTIVE(20, "goal_today_active"), GOAL_TODAY_EXERCISE(21, "goal_today_exercise"),
    TODAY_MOOD(22, "today_mood"), INFO_GENDER(23, "info_gender"), INFO_BIRTHDAY(24, "info_birthday"), INFO_HEIGHT(25, "info_height"), INFO_WEIGHT(26, "info_weight"),
    NOISE_DECIBEL(27, "noise_decibel"), BLOOD_OXYGEN(28, "blood_oxygen"), PRESSURE(29, "pressure"), HIGH_STRENGTH_TRAINING(30, "high_strength_training"),
    AI_SPORT_GRADE(31, "ai_sport_grade"), TEMPERATURE(32, "temperature"), EXPECTED_SLEEP_TIME(33, "expected_sleep_time"),
    EXPECTED_GET_UP_TIME(34, "expected_get_up_time"), ARTY_DATA(35, "arty_data"), HEART_HEALTH(36, "heart_health"),
    BODY_MIND_STATE(37, "body_mind_state"), RESPIRATION_RATE(38, "respiration_rate"), CLIMBING_STAIRS(39, "climbing_stairs"),
    VO2MAX(41, "vo2max"), SKIN_TEMPERATURE(42, "skin_temperature");
    companion object { fun fromCode(code: Long?) = entries.firstOrNull { it.code == code } }
}

enum class HealthMotionState(val code: Long) {
    UNKNOWN(-1), STATIC(0), WALK(1), FAST_WALK(2), RUN(3), FAST_RUN(4), BIKE(5), OFF_WRIST(6),
    AWAKE(7), REM(8), LIGHT_SLEEP(9), DEEP_SLEEP(10), SWIMMING(11), UNDEFINED(99), FREE_TRAIN(100);
    companion object { fun fromCode(code: Long?) = entries.firstOrNull { it.code == code } }
}
enum class HealthValueStatus { NUMBER, EMPTY, NON_FINITE, TEXT }
/**
 * Decimal preserves int64/decimal precision; rawToken retains spaces and empty slots.
 * Confirmed textual fields (for example VO2max sport_id) remain TEXT even when they contain digits.
 * semantic/unit annotate observed positions without rescaling. Code units are not physical units;
 * null unit does not imply missing data. See docs/spec/health.md for layouts and source variants.
 */
data class HealthPointValue(val index: Int, val rawToken: String, val decimal: String?, val status: HealthValueStatus,
    val semantic: String? = null, val unit: String? = null) {
    override fun toString() = "HealthPointValue(contents=<redacted>)"
}
/** First code is the state during the interval; second is the new state at the end boundary. */
data class HealthMotionTransition(val stateCode: Long?, val nextStateCode: Long?) {
    val state get() = HealthMotionState.fromCode(stateCode)
    val nextState get() = HealthMotionState.fromCode(nextStateCode)
}
enum class HealthParseIssue {
    MISSING_FIELD, INVALID_INTEGER, NONSTANDARD_FLAG, UNKNOWN_TYPE, VALUE_NOT_NUMERIC,
    NON_FINITE_VALUE, UNCONFIRMED_VALUE_LAYOUT, UNEXPECTED_VALUE_COUNT, UNKNOWN_MOTION_STATE, INVALID_TIME_RANGE, PARTIAL_SOURCE_ROW,
    MALFORMED_ROW, OPEN_SESSION,
}

/** Backend-parsed watch row. All source values remain in raw; no age/physiology thresholds reject rows. */
data class HealthPointRecord(
    val raw: HealthRawRecord,
    val providerId: Long?, val deviceId: String?, val vendorUserId: String?, val typeCode: Long?,
    val startMillis: Long?, val endMillis: Long?, val vendorSyncCode: Long?, val deletedCode: Long?,
    val rawValues: String?, val values: List<HealthPointValue>, val motion: HealthMotionTransition?,
    val issues: Set<HealthParseIssue>,
) {
    val type get() = HealthPointType.fromCode(typeCode)
    /** Vendor cloud bookkeeping only, independent of our local synchronization checkpoint. */
    val vendorSynced: Boolean? get() = when (vendorSyncCode) { 0L -> false; 1L -> true; else -> null }
    val deleted: Boolean? get() = when (deletedCode) { 0L -> false; 1L -> true; else -> null }
    override fun toString() = "HealthPointRecord(type=$type, contents=<redacted>)"
}
/** Same inclusive overlap, order and offset pagination as HealthRecordQuery; limit is 1..500. */
data class HealthPointQuery(
    val source: HealthRecordSource.LocalDevice,
    val fromMillis: Long? = null, val toMillis: Long? = null,
    val typeCode: Long? = null, val includeDeleted: Boolean = false,
    val offset: Int = 0, val limit: Int = 200, val recordKey: String? = null,
) {
    override fun toString() = "HealthPointQuery(filters=<redacted>, offset=$offset, limit=$limit)"
}
data class HealthPointPage(val items: List<HealthPointRecord>, val nextOffset: Int?,
    val status: HealthRecordReadStatus = HealthRecordReadStatus.AVAILABLE)
