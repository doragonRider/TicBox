package dev.ticbox.companion.demo

import dev.ticbox.companion.api.MonitoringChoice
import dev.ticbox.companion.api.MonitoringCommandResult
import dev.ticbox.companion.api.MonitoringCommandStatus
import dev.ticbox.companion.api.MonitoringPhase
import dev.ticbox.companion.api.MonitoringSettingDescriptor
import dev.ticbox.companion.api.MonitoringSettingId
import dev.ticbox.companion.api.MonitoringSettingObservation
import dev.ticbox.companion.api.MonitoringSettingsClient
import dev.ticbox.companion.api.MonitoringSettingsState
import dev.ticbox.companion.api.MonitoringValue
import dev.ticbox.companion.api.MonitoringValueKind
import dev.ticbox.companion.api.PeerAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The real page's settings with sample values. Like the real one it starts out unread. */
internal class DemoMonitoring(peer: PeerAddress, private val delayMillis: Long) :
    MonitoringSettingsClient {
    private val values = DESCRIPTORS.associate { it.id to it.defaultValue }.toMutableMap()
    private val mutableState =
        MutableStateFlow(
            MonitoringSettingsState(sessionId = 1, peer = peer, phase = MonitoringPhase.READY)
        )
    override val state = mutableState.asStateFlow()

    override suspend fun refresh(): MonitoringCommandResult {
        delay(delayMillis)
        return publish(MonitoringCommandResult(MonitoringCommandStatus.READ))
    }

    override suspend fun set(
        id: MonitoringSettingId,
        value: MonitoringValue,
    ): MonitoringCommandResult {
        delay(delayMillis)
        values[id] = value
        if (
            id == MonitoringSettingId.REST_HEART_RATE_ALERT && value == MonitoringValue.Toggle(true)
        ) {
            values[MonitoringSettingId.BACKGROUND_HEART_RATE] = MonitoringValue.Toggle(true)
            values[MonitoringSettingId.BACKGROUND_HEART_RATE_MODE] = MonitoringValue.Number(1)
        }
        return publish(MonitoringCommandResult(MonitoringCommandStatus.STORED_AND_NOTIFIED, id))
    }

    /** Hiding the feature on a watch also turns monitoring off, which shows on the next read. */
    fun heartHealthHidden() {
        values[MonitoringSettingId.HEART_HEALTH] = MonitoringValue.Toggle(false)
    }

    override fun close() = Unit

    private fun publish(result: MonitoringCommandResult): MonitoringCommandResult {
        val settings = DESCRIPTORS.map { MonitoringSettingObservation(it, values[it.id]) }
        mutableState.update {
            it.copy(settings = settings, current = true, lastOperation = result)
        }
        return result
    }

    private companion object {
        fun toggle(
            id: MonitoringSettingId,
            default: Boolean,
            fixedIntervalSeconds: Long? = null,
            appliesOnNextExercise: Boolean = false,
            module: Boolean = false,
        ) =
            MonitoringSettingDescriptor(
                id,
                MonitoringValueKind.BOOLEAN,
                group = "demo",
                defaultValue = MonitoringValue.Toggle(default),
                fixedIntervalSeconds = fixedIntervalSeconds,
                appliesOnNextExercise = appliesOnNextExercise,
                requiresInProcessModule = module,
            )

        fun time(id: MonitoringSettingId, default: String) =
            MonitoringSettingDescriptor(
                id,
                MonitoringValueKind.TEXT,
                group = "demo",
                unit = "local_time",
                defaultValue = MonitoringValue.Text(default),
                requiresInProcessModule = true,
            )

        fun numbers(
            id: MonitoringSettingId,
            default: Long,
            choices: Map<Long, String>,
            unit: String? = null,
            module: Boolean = false,
        ) =
            MonitoringSettingDescriptor(
                id,
                MonitoringValueKind.INTEGER,
                group = "demo",
                unit = unit,
                choices =
                    choices.map { (v, meaning) ->
                        MonitoringChoice(MonitoringValue.Number(v), meaning)
                    },
                defaultValue = MonitoringValue.Number(default),
                requiresInProcessModule = module,
            )

        fun texts(
            id: MonitoringSettingId,
            default: String,
            choices: Map<String, String>,
            module: Boolean = false,
        ) =
            MonitoringSettingDescriptor(
                id,
                MonitoringValueKind.TEXT,
                group = "demo",
                choices =
                    choices.map { (v, meaning) ->
                        MonitoringChoice(MonitoringValue.Text(v), meaning)
                    },
                defaultValue = MonitoringValue.Text(default),
                requiresInProcessModule = module,
            )

        fun bpmChoices(values: Iterable<Int>) = values.associate { it.toLong() to "bpm_$it" }

        val DESCRIPTORS =
            listOf(
                toggle(MonitoringSettingId.BACKGROUND_HEART_RATE, true),
                numbers(
                    MonitoringSettingId.BACKGROUND_HEART_RATE_MODE,
                    0,
                    mapOf(0L to "normal_600_seconds", 1L to "realtime_1_second"),
                ),
                toggle(MonitoringSettingId.BLOOD_OXYGEN, true, fixedIntervalSeconds = 3600),
                toggle(MonitoringSettingId.PRESSURE, false, fixedIntervalSeconds = 1800),
                toggle(MonitoringSettingId.BODY_MIND, false),
                toggle(MonitoringSettingId.HEART_HEALTH, false),
                toggle(MonitoringSettingId.SLEEP, true),
                toggle(MonitoringSettingId.AUTO_EXERCISE, true),
                toggle(MonitoringSettingId.RESPIRATION, true, fixedIntervalSeconds = 1200),
                toggle(MonitoringSettingId.TEMPERATURE, true, fixedIntervalSeconds = 600),
                toggle(MonitoringSettingId.REST_HEART_RATE_ALERT, false),
                numbers(
                    MonitoringSettingId.REST_HEART_RATE_LOWER,
                    60,
                    bpmChoices(40..60 step 5),
                    unit = "beats_per_minute",
                ),
                numbers(
                    MonitoringSettingId.REST_HEART_RATE_UPPER,
                    100,
                    bpmChoices(100..150 step 10),
                    unit = "beats_per_minute",
                ),
                toggle(
                    MonitoringSettingId.EXERCISE_HEART_RATE_ALERT,
                    false,
                    appliesOnNextExercise = true,
                ),
                MonitoringSettingDescriptor(
                    MonitoringSettingId.EXERCISE_HEART_RATE_UPPER,
                    MonitoringValueKind.INTEGER,
                    group = "demo",
                    unit = "beats_per_minute",
                    minimum = 100,
                    maximum = 220,
                    defaultValue = MonitoringValue.Number(180),
                    appliesOnNextExercise = true,
                ),
                numbers(
                    MonitoringSettingId.SLEEP_BLOOD_OXYGEN_INTERVAL,
                    3600,
                    mapOf(3600L to "one_hour", 1800L to "thirty_minutes", 60L to "one_minute"),
                    unit = "seconds",
                ),
                texts(
                    MonitoringSettingId.TEMPERATURE_UNIT,
                    "0",
                    mapOf("0" to "celsius", "1" to "fahrenheit"),
                ),
                toggle(MonitoringSettingId.STEP_COUNTING, true),
                toggle(MonitoringSettingId.SEDENTARY_REMINDER, true, module = true),
                toggle(MonitoringSettingId.STEP_GOAL_APPROACH_REMINDER, true, module = true),
                toggle(MonitoringSettingId.DAILY_GOALS_REMINDER, true, module = true),
                time(MonitoringSettingId.HEALTH_QUIET_START, "22:00"),
                time(MonitoringSettingId.HEALTH_QUIET_END, "08:00"),
                toggle(MonitoringSettingId.SLEEP_BEDTIME_REMINDER, false, module = true),
                toggle(MonitoringSettingId.SLEEP_COMPLETE_REMINDER, false, module = true),
                toggle(MonitoringSettingId.SLEEP_WAKE_ALARM, false, module = true),
                numbers(
                    MonitoringSettingId.SLEEP_SMART_AWAKE_MINUTES,
                    0,
                    listOf(0L, 15L, 30L, 45L, 60L).associateWith { "minutes_$it" },
                    unit = "minutes",
                    module = true,
                ),
                texts(
                    MonitoringSettingId.SLEEP_ALARM_VIBRATION,
                    "once",
                    mapOf("never" to "never", "once" to "once", "always" to "always"),
                    module = true,
                ),
                toggle(MonitoringSettingId.SLEEP_ALARM_SOUND, true, module = true),
                time(MonitoringSettingId.SLEEP_EXPECTED_BEDTIME, "23:00"),
                time(MonitoringSettingId.SLEEP_EXPECTED_WAKE_TIME, "08:00"),
                toggle(MonitoringSettingId.SLEEP_EXCLUSION_ENABLED, false, module = true),
                time(MonitoringSettingId.SLEEP_EXCLUSION_START, "10:00"),
                time(MonitoringSettingId.SLEEP_EXCLUSION_END, "17:00"),
            )
    }
}
