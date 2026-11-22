package dev.ticbox.companion.engine.health

import dev.ticbox.companion.api.HealthPointType

/** Observed CSV positions, not sensor formulas. Evidence: docs/spec/health.md. */
internal object HealthPointLayouts {
    data class Field(val semantic: String, val unit: String? = null, val text: Boolean = false)
    data class Layout(val fields: List<Field?>, val repeated: Field? = null, val expectedCount: Int? = null) {
        fun field(index: Int) = fields.getOrNull(index) ?: repeated
    }

    private fun scalar(type: HealthPointType, unit: String? = null) =
        Layout(listOf(Field(type.wireName, unit)))

    private val layouts = buildMap {
        mapOf(
            HealthPointType.HEART_RATE to "bpm",
            HealthPointType.CALORIE to "kcal", HealthPointType.DISTANCE to "m",
            HealthPointType.STEP_COUNT to "steps", HealthPointType.EXERCISE to "s",
            HealthPointType.DELTA_CALORIE to "kcal", HealthPointType.DELTA_DISTANCE to "m",
            HealthPointType.DELTA_STEP to "steps", HealthPointType.DELTA_EXERCISE to "s",
            HealthPointType.PERIOD_EVENT to "period_event_code",
            HealthPointType.PERIOD_CYCLE_LENGTH to "days", HealthPointType.PERIOD_MENSTRUATION_LENGTH to "days",
            HealthPointType.HEART_RATE_WARNING to "heart_rate_warning_code",
            HealthPointType.HEART_RATE_WARNING_FITNESS_UPPER_LIMIT to "bpm",
            HealthPointType.HEART_RATE_WARNING_REST_UPPER_LIMIT to "bpm",
            HealthPointType.HEART_RATE_WARNING_REST_LOWER_LIMIT to "bpm",
            HealthPointType.GOAL_TODAY_STEP to "steps", HealthPointType.GOAL_TODAY_ACTIVE to "h",
            HealthPointType.GOAL_TODAY_EXERCISE to "s", HealthPointType.TODAY_MOOD to "mood_code",
            HealthPointType.INFO_GENDER to "gender_code", HealthPointType.INFO_BIRTHDAY to "epoch_s",
            HealthPointType.INFO_HEIGHT to "cm", HealthPointType.INFO_WEIGHT to "kg",
            HealthPointType.BLOOD_OXYGEN to "%", HealthPointType.PRESSURE to null,
            HealthPointType.TEMPERATURE to "°C",
            HealthPointType.EXPECTED_SLEEP_TIME to "ms_since_midnight",
            HealthPointType.EXPECTED_GET_UP_TIME to "ms_since_midnight",
            HealthPointType.RESPIRATION_RATE to "breaths/min", HealthPointType.CLIMBING_STAIRS to "m",
            HealthPointType.SKIN_TEMPERATURE to "°C",
        ).forEach { (type, unit) -> put(type, scalar(type, unit)) }
        put(HealthPointType.MOTION, Layout(listOf(
            Field("interval_state", "motion_code"), Field("next_state", "motion_code")), expectedCount = 2))
        // All samples in this variable-length vector are decibels. Their individual sample times are not exposed.
        put(HealthPointType.NOISE_DECIBEL, Layout(emptyList(), repeated = Field("noise_decibel", "dB")))
        put(HealthPointType.HIGH_STRENGTH_TRAINING, Layout(listOf(
            Field("sport_type", "sport_type_code"), Field("high_intensity_duration", "ms")), expectedCount = 2))
        // The phone skips slot0; its meaning is not established by the observed consumer.
        put(HealthPointType.ARTY_DATA, Layout(listOf(null,
            Field("arty_score", "score/100"), Field("tru_hr", "bpm"),
            Field("ecap", "%"), Field("hsx", "%"),
            Field("arty_age_lower", "years"), Field("arty_age_upper", "years"))))
        put(HealthPointType.HEART_HEALTH, Layout(listOf(
            Field("heart_health_type", "heart_health_code"), Field("heart_rate", "bpm")), expectedCount = 2))
        put(HealthPointType.BODY_MIND_STATE, Layout(listOf(
            Field("body_mind_type", "body_mind_code"), Field("level", "ordinal")), expectedCount = 2))
        put(HealthPointType.VO2MAX, Layout(listOf(
            Field("vo2max", "ml/kg/min"), Field("sport_type", text = true),
            Field("duration", "ms"), Field("sport_id", text = true)), expectedCount = 4))
    }

    fun forType(type: HealthPointType?) = layouts[type]
}
