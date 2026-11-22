package dev.ticbox.companion.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Stable identifiers; presentation labels belong to the frontend. */
enum class MonitoringSettingId {
    BACKGROUND_HEART_RATE, BACKGROUND_HEART_RATE_MODE, BLOOD_OXYGEN, PRESSURE,
    BODY_MIND, HEART_HEALTH, SLEEP, AUTO_EXERCISE, RESPIRATION, TEMPERATURE,
    REST_HEART_RATE_ALERT, REST_HEART_RATE_LOWER, REST_HEART_RATE_UPPER,
    EXERCISE_HEART_RATE_ALERT, EXERCISE_HEART_RATE_UPPER, SLEEP_BLOOD_OXYGEN_INTERVAL, TEMPERATURE_UNIT,
    STEP_COUNTING,
    SEDENTARY_REMINDER, STEP_GOAL_APPROACH_REMINDER, DAILY_GOALS_REMINDER,
    HEALTH_QUIET_START, HEALTH_QUIET_END,
    SLEEP_BEDTIME_REMINDER, SLEEP_COMPLETE_REMINDER, SLEEP_WAKE_ALARM,
    SLEEP_SMART_AWAKE_MINUTES, SLEEP_ALARM_VIBRATION, SLEEP_ALARM_SOUND,
    SLEEP_EXPECTED_BEDTIME, SLEEP_EXPECTED_WAKE_TIME,
    SLEEP_EXCLUSION_ENABLED, SLEEP_EXCLUSION_START, SLEEP_EXCLUSION_END,
}
enum class MonitoringValueKind { BOOLEAN, INTEGER, TEXT }
sealed interface MonitoringValue {
    data class Toggle(val enabled: Boolean) : MonitoringValue
    data class Number(val value: Long) : MonitoringValue
    data class Text(val value: String) : MonitoringValue
}
data class MonitoringChoice(val value: MonitoringValue, val meaning: String)
/** Null defaults stay unknown, in particular when the OEM chooses a default by device model. */
data class MonitoringSettingDescriptor(
    val id: MonitoringSettingId,
    val kind: MonitoringValueKind,
    val group: String,
    val unit: String? = null,
    val choices: List<MonitoringChoice> = emptyList(),
    val minimum: Long? = null,
    val maximum: Long? = null,
    val defaultValue: MonitoringValue? = null,
    val deviceDependentDefault: Boolean = false,
    val writable: Boolean = true,
    /** Firmware-fixed cadence when enabled; not an editable arbitrary sampling interval. */
    val fixedIntervalSeconds: Long? = null,
    /** The OEM exercise service snapshots these settings when preparing the next exercise. */
    val appliesOnNextExercise: Boolean = false,
    /** Requires the optional pinned Zygisk module to be loaded in the OEM health process. */
    val requiresInProcessModule: Boolean = false,
)
data class MonitoringSettingObservation(
    val descriptor: MonitoringSettingDescriptor,
    /** null means no stored override, not false/zero. */
    val storedValue: MonitoringValue?,
    /** Retains unrecognized OEM values without making them writable choices. */
    val unrecognizedStoredValue: Boolean = false,
    /** One unavailable source must not hide the other readable settings. */
    val available: Boolean = true,
    val unavailableReason: String? = null,
    /** Separate from the saved preference: false means an OEM feature gate currently blocks it. */
    val firmwareFeatureEnabled: Boolean? = null,
) {
    val effectiveValue: MonitoringValue? get() = storedValue ?: descriptor.defaultValue.takeUnless { unrecognizedStoredValue }
}
data class MonitoringSource(val packageName: String, val versionName: String?, val versionCode: Long)
enum class MonitoringPhase { DISCONNECTED, READY, READING, WRITING, UNAVAILABLE, CLOSED }
enum class MonitoringCommandStatus {
    READ, UNCHANGED, STORED_AND_NOTIFIED, STORED_UNVERIFIED, INVALID_ARGUMENT, UNAVAILABLE, INTERRUPTED, FAILED,
}
data class MonitoringCommandResult(
    val status: MonitoringCommandStatus,
    val setting: MonitoringSettingId? = null,
    val reason: String? = null,
)
data class MonitoringSettingsState(
    val sessionId: Long = 0,
    val peer: PeerAddress? = null,
    val phase: MonitoringPhase = MonitoringPhase.DISCONNECTED,
    val source: MonitoringSource? = null,
    val settings: List<MonitoringSettingObservation> = emptyList(),
    /** Cleared on every physical connection change until a fresh root read succeeds. */
    val current: Boolean = false,
    val lastOperation: MonitoringCommandResult? = null,
)
/**
 * OEM settings through the selected watch's Local Bridge. This does not change sensor algorithms.
 * STORED_AND_NOTIFIED confirms persisted readback and observer notification, not a sensor sample.
 */
interface MonitoringSettingsClient : AutoCloseable {
    val state: StateFlow<MonitoringSettingsState>
    suspend fun refresh(): MonitoringCommandResult
    suspend fun set(id: MonitoringSettingId, value: MonitoringValue): MonitoringCommandResult
    override fun close()

    companion object {
        val UNAVAILABLE = object : MonitoringSettingsClient {
            override val state = MutableStateFlow(MonitoringSettingsState(phase = MonitoringPhase.UNAVAILABLE))
            override suspend fun refresh() = MonitoringCommandResult(MonitoringCommandStatus.UNAVAILABLE, reason = "monitoring_unavailable")
            override suspend fun set(id: MonitoringSettingId, value: MonitoringValue) = MonitoringCommandResult(MonitoringCommandStatus.UNAVAILABLE, id, "monitoring_unavailable")
            override fun close() = Unit
        }
    }
}
