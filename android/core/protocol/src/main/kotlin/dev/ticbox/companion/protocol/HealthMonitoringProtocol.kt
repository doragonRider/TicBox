package dev.ticbox.companion.protocol

/** Fixed OEM settings, never caller-selected provider URIs, preference files, or SQL. */
object HealthMonitoringProtocol {
    const val OPERATION = "health_monitoring"
    const val CAPABILITY = "HEALTH_MONITORING"
    const val AUTHORITY = "com.mobvoi.health.provider.settings"
    const val PACKAGE = "com.mobvoi.wear.health.at"
    const val STEP_AUTHORITY = "com.mobvoi.ticwear.steps"
    const val TABLE = "app_ctrl"
    val ANALYZED_VERSIONS = setOf(50_503_292L, 50_603_234L)
    val ANALYZED_APK_SHA256 = mapOf(
        50_503_292L to "39969a876dcd716bd9e868a446626f762f8c23c7c90d567a061df9e405666e25",
        50_603_234L to "276264832680b5da9fc7bae260ff521e7df6d49c7e04fa40c00f64e3ef96c4db",
    )
    const val PRIVATE_SOCKET = "ticbox-health-v1"
    const val PRIVATE_VERSION = 1
    const val PRIVATE_MAX_BYTES = 65_536

    data class Spec(
        val id: String,
        val key: String,
        val kind: String,
        val group: String,
        val default: String? = null,
        val choices: Map<String, String> = emptyMap(),
        val deviceDependentDefault: Boolean = false,
        val unit: String? = null,
        val fixedIntervalSeconds: Long? = null,
        val minimum: Long? = null,
        val maximum: Long? = null,
        val providerPreference: Boolean = true,
        val inProcess: Boolean = false,
    ) {
        fun accepts(value: String): Boolean = when {
            value.length > 128 -> false
            choices.isNotEmpty() -> value in choices
            unit == "local_time" -> LOCAL_TIME.matches(value)
            kind == "BOOLEAN" -> value == "true" || value == "false"
            kind == "INTEGER" && minimum != null && maximum != null -> value.toLongOrNull()?.let {
                it in minimum..maximum && it.toString() == value
            } == true
            else -> false
        }
    }
    private fun toggle(id: String, key: String, group: String, default: Boolean? = null,
        fixedIntervalSeconds: Long? = null) = Spec(id, key, "BOOLEAN", group, default?.toString(),
            deviceDependentDefault = default == null, fixedIntervalSeconds = fixedIntervalSeconds)
    private fun privateToggle(id: String, key: String, group: String, default: Boolean) =
        Spec(id, key, "BOOLEAN", group, default.toString(), providerPreference = false, inProcess = true)
    private fun privateTime(id: String, key: String, group: String, default: String) =
        Spec(id, key, "TEXT", group, default, unit = "local_time", providerPreference = false, inProcess = true)
    private val LOCAL_TIME = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")
    val SETTINGS = listOf(
        toggle("BACKGROUND_HEART_RATE", "background_hrm", "heart_rate"),
        Spec("BACKGROUND_HEART_RATE_MODE", "background_hrm_mode", "INTEGER", "heart_rate", "0",
            mapOf("0" to "normal_600_seconds", "1" to "realtime_1_second")),
        toggle("BLOOD_OXYGEN", "auto_detect_blood_oxygen", "blood_oxygen", fixedIntervalSeconds = 3600),
        toggle("PRESSURE", "auto_detect_pressure", "pressure", false, 1800),
        toggle("BODY_MIND", "auto_detect_body_mind_state", "body_mind", false),
        toggle("HEART_HEALTH", "auto_detect_heart_health", "heart_health", false),
        toggle("SLEEP", "auto_detect_sleep", "sleep", true),
        toggle("AUTO_EXERCISE", "auto_detect_fitness", "exercise"),
        toggle("RESPIRATION", "detect_respiration_rate", "sleep", true, 1200),
        toggle("TEMPERATURE", "detect_temperature", "sleep", true, 600),
        toggle("REST_HEART_RATE_ALERT", "rest_hrm_warning", "heart_rate_alert", false),
        Spec("REST_HEART_RATE_LOWER", "rest_hrm_warning_lower", "INTEGER", "heart_rate_alert", "60",
            (40..60 step 5).associate { it.toString() to "bpm_$it" }, unit = "beats_per_minute"),
        Spec("REST_HEART_RATE_UPPER", "rest_hrm_warning_upper", "INTEGER", "heart_rate_alert", "100",
            (100..150 step 10).associate { it.toString() to "bpm_$it" }, unit = "beats_per_minute"),
        toggle("EXERCISE_HEART_RATE_ALERT", "fitness_hrm_warning", "exercise", false),
        Spec("EXERCISE_HEART_RATE_UPPER", "exercise_heart_rate_upper", "INTEGER", "exercise",
            deviceDependentDefault = true, unit = "beats_per_minute", minimum = 100, maximum = 220, providerPreference = false),
        Spec("SLEEP_BLOOD_OXYGEN_INTERVAL", "sleep_detect_blood_oxygen_frequency", "INTEGER", "blood_oxygen", "3600",
            mapOf("3600" to "one_hour", "1800" to "thirty_minutes", "60" to "one_minute"), unit = "seconds"),
        Spec("TEMPERATURE_UNIT", "detect_sleep_temperature_unit", "TEXT", "sleep", choices = mapOf("0" to "celsius", "1" to "fahrenheit"), deviceDependentDefault = true),
        toggle("STEP_COUNTING", "step_pedometer", "activity", true),
        privateToggle("SEDENTARY_REMINDER", "alert_sit_long", "activity_reminders", true),
        privateToggle("STEP_GOAL_APPROACH_REMINDER", "alert_step_enable_when_80", "activity_reminders", true),
        privateToggle("DAILY_GOALS_REMINDER", "alert_health_time_done", "activity_reminders", true),
        privateTime("HEALTH_QUIET_START", "health_not_disturb_start_new", "activity_reminders", "22:00"),
        privateTime("HEALTH_QUIET_END", "health_not_disturb_end_new", "activity_reminders", "08:00"),
        privateToggle("SLEEP_BEDTIME_REMINDER", "sleep_notification", "sleep_reminders", false),
        privateToggle("SLEEP_COMPLETE_REMINDER", "sleep_complete_notification", "sleep_reminders", false),
        privateToggle("SLEEP_WAKE_ALARM", "sleep_alarm", "sleep_alarm", false),
        Spec("SLEEP_SMART_AWAKE_MINUTES", "smart_awake", "INTEGER", "sleep_alarm", "0",
            listOf(0, 15, 30, 45, 60).associate { it.toString() to "minutes_$it" }, unit = "minutes", providerPreference = false, inProcess = true),
        Spec("SLEEP_ALARM_VIBRATION", "vibration_mode", "TEXT", "sleep_alarm", choices =
            mapOf("never" to "never", "once" to "once", "always" to "always"), deviceDependentDefault = true, providerPreference = false, inProcess = true),
        privateToggle("SLEEP_ALARM_SOUND", "sound_switch", "sleep_alarm", true),
        privateTime("SLEEP_EXPECTED_BEDTIME", "sleep_time", "sleep_schedule", "23:00"),
        privateTime("SLEEP_EXPECTED_WAKE_TIME", "getup_time", "sleep_schedule", "08:00"),
        privateToggle("SLEEP_EXCLUSION_ENABLED", "exclusion_time_open_status", "sleep_schedule", false),
        privateTime("SLEEP_EXCLUSION_START", "exclusion_start_time", "sleep_schedule", "10:00"),
        privateTime("SLEEP_EXCLUSION_END", "exclusion_end_time", "sleep_schedule", "17:00"),
    )
    fun spec(id: String): Spec = SETTINGS.singleOrNull { it.id == id } ?: throw IllegalArgumentException("unknown_monitoring_setting")

    /** Derive before writing anything: unknown device defaults must not silently disable tracking. */
    fun changes(id: String, value: String, stored: Map<String, String?>,
        defaults: Map<String, String> = emptyMap(), autoMotionFeatureEnabled: Boolean? = null): LinkedHashMap<String, String> {
        val spec = spec(id)
        require(spec.accepts(value)) { "invalid_monitoring_value" }
        val changes = linkedMapOf<String, String>()
        if (id == "REST_HEART_RATE_ALERT" && value == "true") {
            changes["background_hrm"] = "true"
            changes["background_hrm_mode"] = "1"
        }
        changes[spec.key] = value
        if (spec.key in setOf("auto_detect_sleep", "auto_detect_fitness", "rest_hrm_warning")) {
            val flags = listOf("auto_detect_fitness", "auto_detect_sleep", "rest_hrm_warning").map { key ->
                val raw = changes[key] ?: stored[key] ?: defaults[key] ?: SETTINGS.single { it.key == key }.default
                val enabled = when (raw) { "true" -> true; "false" -> false; else -> null }
                if (key == "rest_hrm_warning") enabled else when {
                    autoMotionFeatureEnabled == false || enabled == false -> false
                    autoMotionFeatureEnabled == true -> enabled
                    else -> null
                }
            }
            val active = when { true in flags -> true; flags.all { it == false } -> false; else -> throw IllegalArgumentException("monitoring_dependent_default_unknown") }
            changes["auto_motion"] = active.toString()
        }
        return changes
    }
}
