package dev.ticbox.companion.ui.device

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import dev.ticbox.companion.api.MonitoringChoice
import dev.ticbox.companion.api.MonitoringSettingId
import dev.ticbox.companion.api.MonitoringSettingObservation
import dev.ticbox.companion.api.MonitoringValue
import dev.ticbox.companion.api.MonitoringValueKind
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.asString
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.FadeSwitch
import dev.ticbox.companion.ui.designsystem.NavigationRow
import dev.ticbox.companion.ui.designsystem.RadioRow
import dev.ticbox.companion.ui.designsystem.SwitchRow
import java.util.Locale

private const val LOCAL_TIME = "local_time"
private const val BEATS_PER_MINUTE = "beats_per_minute"

@get:StringRes
private val MonitoringSettingId.label: Int
    get() =
        when (this) {
            MonitoringSettingId.BACKGROUND_HEART_RATE -> R.string.monitoring_background_heart_rate
            MonitoringSettingId.BACKGROUND_HEART_RATE_MODE ->
                R.string.monitoring_background_heart_rate_mode
            MonitoringSettingId.BLOOD_OXYGEN -> R.string.monitoring_blood_oxygen
            MonitoringSettingId.PRESSURE -> R.string.monitoring_pressure
            MonitoringSettingId.BODY_MIND -> R.string.monitoring_body_mind
            MonitoringSettingId.HEART_HEALTH -> R.string.preferences_heart_health_monitoring
            MonitoringSettingId.SLEEP -> R.string.monitoring_sleep
            MonitoringSettingId.AUTO_EXERCISE -> R.string.monitoring_auto_exercise
            MonitoringSettingId.RESPIRATION -> R.string.monitoring_respiration
            MonitoringSettingId.TEMPERATURE -> R.string.monitoring_temperature
            MonitoringSettingId.REST_HEART_RATE_ALERT -> R.string.monitoring_rest_alert
            MonitoringSettingId.REST_HEART_RATE_LOWER -> R.string.monitoring_rest_lower
            MonitoringSettingId.REST_HEART_RATE_UPPER -> R.string.monitoring_rest_upper
            MonitoringSettingId.EXERCISE_HEART_RATE_ALERT -> R.string.monitoring_exercise_alert
            MonitoringSettingId.EXERCISE_HEART_RATE_UPPER -> R.string.monitoring_exercise_upper
            MonitoringSettingId.SLEEP_BLOOD_OXYGEN_INTERVAL ->
                R.string.monitoring_sleep_oxygen_interval
            MonitoringSettingId.TEMPERATURE_UNIT -> R.string.monitoring_temperature_unit
            MonitoringSettingId.STEP_COUNTING -> R.string.monitoring_step_counting
            MonitoringSettingId.SEDENTARY_REMINDER -> R.string.monitoring_sedentary
            MonitoringSettingId.STEP_GOAL_APPROACH_REMINDER -> R.string.monitoring_step_goal_near
            MonitoringSettingId.DAILY_GOALS_REMINDER -> R.string.monitoring_daily_goals
            MonitoringSettingId.HEALTH_QUIET_START -> R.string.monitoring_quiet_start
            MonitoringSettingId.HEALTH_QUIET_END -> R.string.monitoring_quiet_end
            MonitoringSettingId.SLEEP_BEDTIME_REMINDER -> R.string.monitoring_bedtime_reminder
            MonitoringSettingId.SLEEP_COMPLETE_REMINDER -> R.string.monitoring_sleep_complete
            MonitoringSettingId.SLEEP_WAKE_ALARM -> R.string.monitoring_wake_alarm
            MonitoringSettingId.SLEEP_SMART_AWAKE_MINUTES -> R.string.monitoring_smart_wake
            MonitoringSettingId.SLEEP_ALARM_VIBRATION -> R.string.monitoring_alarm_vibration
            MonitoringSettingId.SLEEP_ALARM_SOUND -> R.string.monitoring_alarm_sound
            MonitoringSettingId.SLEEP_EXPECTED_BEDTIME -> R.string.monitoring_expected_bedtime
            MonitoringSettingId.SLEEP_EXPECTED_WAKE_TIME -> R.string.monitoring_expected_wake
            MonitoringSettingId.SLEEP_EXCLUSION_ENABLED -> R.string.monitoring_exclusion
            MonitoringSettingId.SLEEP_EXCLUSION_START -> R.string.monitoring_exclusion_start
            MonitoringSettingId.SLEEP_EXCLUSION_END -> R.string.monitoring_exclusion_end
        }

private val MonitoringSettingId.icon: Int
    get() =
        when (this) {
            MonitoringSettingId.BACKGROUND_HEART_RATE,
            MonitoringSettingId.REST_HEART_RATE_ALERT,
            MonitoringSettingId.EXERCISE_HEART_RATE_ALERT -> AppIcons.Heart
            MonitoringSettingId.HEART_HEALTH,
            MonitoringSettingId.BACKGROUND_HEART_RATE_MODE,
            MonitoringSettingId.REST_HEART_RATE_LOWER,
            MonitoringSettingId.REST_HEART_RATE_UPPER,
            MonitoringSettingId.EXERCISE_HEART_RATE_UPPER -> AppIcons.HeartMonitor
            MonitoringSettingId.BLOOD_OXYGEN,
            MonitoringSettingId.SLEEP_BLOOD_OXYGEN_INTERVAL -> AppIcons.Oxygen
            MonitoringSettingId.PRESSURE -> AppIcons.Stress
            MonitoringSettingId.BODY_MIND -> AppIcons.Mood
            MonitoringSettingId.RESPIRATION -> AppIcons.Breath
            MonitoringSettingId.TEMPERATURE,
            MonitoringSettingId.TEMPERATURE_UNIT -> AppIcons.Temperature
            MonitoringSettingId.AUTO_EXERCISE -> AppIcons.Running
            MonitoringSettingId.STEP_COUNTING,
            MonitoringSettingId.STEP_GOAL_APPROACH_REMINDER -> AppIcons.Steps
            MonitoringSettingId.DAILY_GOALS_REMINDER -> AppIcons.Goal
            MonitoringSettingId.SEDENTARY_REMINDER,
            MonitoringSettingId.SLEEP_BEDTIME_REMINDER,
            MonitoringSettingId.SLEEP_COMPLETE_REMINDER -> AppIcons.Notifications
            MonitoringSettingId.SLEEP_WAKE_ALARM,
            MonitoringSettingId.SLEEP_SMART_AWAKE_MINUTES -> AppIcons.Alarm
            MonitoringSettingId.SLEEP_ALARM_VIBRATION -> AppIcons.Vibration
            MonitoringSettingId.SLEEP_ALARM_SOUND -> AppIcons.VolumeUp
            MonitoringSettingId.SLEEP -> AppIcons.Sleep
            MonitoringSettingId.HEALTH_QUIET_START,
            MonitoringSettingId.HEALTH_QUIET_END,
            MonitoringSettingId.SLEEP_EXPECTED_BEDTIME,
            MonitoringSettingId.SLEEP_EXPECTED_WAKE_TIME,
            MonitoringSettingId.SLEEP_EXCLUSION_ENABLED,
            MonitoringSettingId.SLEEP_EXCLUSION_START,
            MonitoringSettingId.SLEEP_EXCLUSION_END -> AppIcons.Schedule
        }

/** A choice in words. The backend's stable meanings hold no text meant for people. */
@Composable
private fun choiceLabel(choice: MonitoringChoice): String {
    val meaning = choice.meaning
    return when {
        meaning == "normal_600_seconds" -> stringResource(R.string.monitoring_mode_normal)
        meaning == "realtime_1_second" -> stringResource(R.string.monitoring_mode_realtime)
        meaning == "one_hour" -> stringResource(R.string.monitoring_interval_hour)
        meaning == "thirty_minutes" -> stringResource(R.string.monitoring_interval_half_hour)
        meaning == "one_minute" -> stringResource(R.string.monitoring_interval_minute)
        meaning == "celsius" -> stringResource(R.string.monitoring_unit_celsius)
        meaning == "fahrenheit" -> stringResource(R.string.monitoring_unit_fahrenheit)
        meaning == "never" -> stringResource(R.string.monitoring_vibration_never)
        meaning == "once" -> stringResource(R.string.monitoring_vibration_once)
        meaning == "always" -> stringResource(R.string.monitoring_vibration_always)
        meaning == "minutes_0" -> stringResource(R.string.monitoring_value_off)
        meaning.startsWith("minutes_") ->
            stringResource(R.string.monitoring_minutes, meaning.removePrefix("minutes_").toInt())
        meaning.startsWith("bpm_") ->
            stringResource(R.string.monitoring_bpm, meaning.removePrefix("bpm_").toInt())
        else -> meaning
    }
}

/** The current value in words, or what stands in for one. */
@Composable
private fun valueText(setting: MonitoringSettingObservation): String {
    val value = setting.effectiveValue ?: return stringResource(unsetLabel(setting))
    val descriptor = setting.descriptor
    val choice = descriptor.choices.firstOrNull { it.value == value }
    return when {
        choice != null -> choiceLabel(choice)
        value is MonitoringValue.Text -> value.value
        value is MonitoringValue.Number && descriptor.unit == BEATS_PER_MINUTE ->
            stringResource(R.string.monitoring_bpm, value.value)
        value is MonitoringValue.Number -> value.value.toString()
        else -> stringResource(R.string.monitoring_value_unknown)
    }
}

/** A number can simply have no record. Other kinds without one have a value nobody can tell. */
@StringRes
private fun unsetLabel(setting: MonitoringSettingObservation): Int =
    if (setting.descriptor.kind == MonitoringValueKind.INTEGER && setting.available) {
        R.string.monitoring_value_unset
    } else {
        R.string.monitoring_value_unknown
    }

/** What's worth knowing about this setting beyond its value. */
@Composable
private fun settingNote(setting: MonitoringSettingObservation): String? {
    val descriptor = setting.descriptor
    val interval = descriptor.fixedIntervalSeconds
    val unavailableReason = setting.unavailableReason
    return when {
        !setting.available && !descriptor.requiresInProcessModule && unavailableReason != null ->
            monitoringMessage(unavailableReason).asString()
        !setting.available && descriptor.requiresInProcessModule ->
            stringResource(R.string.monitoring_row_needs_module)
        !setting.available -> stringResource(R.string.monitoring_row_unavailable)
        setting.unrecognizedStoredValue -> stringResource(R.string.monitoring_value_unrecognized)
        setting.firmwareFeatureEnabled == false -> stringResource(R.string.monitoring_feature_gated)
        descriptor.appliesOnNextExercise -> stringResource(R.string.monitoring_next_exercise)
        descriptor.id == MonitoringSettingId.REST_HEART_RATE_ALERT ->
            stringResource(R.string.monitoring_rest_alert_note)
        descriptor.id == MonitoringSettingId.SLEEP_EXCLUSION_ENABLED ->
            stringResource(R.string.monitoring_exclusion_note)
        interval != null ->
            pluralStringResource(
                R.plurals.monitoring_fixed_interval,
                (interval / 60).toInt(),
                interval / 60,
            )
        else -> null
    }
}

/** A switch only once the watch has given a position: an unknown value is not the same as off. */
@Composable
internal fun SettingRow(
    setting: MonitoringSettingObservation,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    FadeSwitch(setting, key = { it.available && it.effectiveValue is MonitoringValue.Toggle }) {
        shown ->
        val title = stringResource(shown.descriptor.id.label)
        val note = settingNote(shown)
        val value = shown.effectiveValue
        if (shown.available && value is MonitoringValue.Toggle) {
            SwitchRow(
                title,
                checked = value.enabled,
                onCheckedChange = onToggle,
                summary = note,
                icon = shown.descriptor.id.icon,
                enabled = enabled,
            )
        } else {
            NavigationRow(
                title,
                onClick = onEdit,
                icon = shown.descriptor.id.icon,
                summary =
                    listOfNotNull(valueText(shown).takeIf { shown.available }, note)
                        .joinToString("\n"),
                enabled = enabled,
                trailingIcon = null,
            )
        }
    }
}

@Composable
internal fun SettingEditor(
    setting: MonitoringSettingObservation,
    onSet: (MonitoringValue) -> Unit,
    onDismiss: () -> Unit,
) {
    val descriptor = setting.descriptor
    val title = stringResource(descriptor.id.label)
    val minimum = descriptor.minimum
    val maximum = descriptor.maximum
    when {
        descriptor.unit == LOCAL_TIME ->
            TimeDialog(
                title,
                (setting.effectiveValue as? MonitoringValue.Text)?.value,
                onSet,
                onDismiss,
            )
        descriptor.choices.isNotEmpty() ->
            ChoiceDialog(
                title,
                setting.effectiveValue,
                descriptor.choices.map { it.value to choiceLabel(it) },
                onSet,
                onDismiss,
            )
        descriptor.kind == MonitoringValueKind.BOOLEAN ->
            ChoiceDialog(
                title,
                setting.effectiveValue,
                listOf(
                    MonitoringValue.Toggle(true) to stringResource(R.string.monitoring_value_on),
                    MonitoringValue.Toggle(false) to stringResource(R.string.monitoring_value_off),
                ),
                onSet,
                onDismiss,
            )
        minimum != null && maximum != null ->
            NumberDialog(
                title,
                (setting.effectiveValue as? MonitoringValue.Number)?.value,
                minimum..maximum,
                onSet,
                onDismiss,
            )
    }
}

@Composable
private fun ChoiceDialog(
    title: String,
    current: MonitoringValue?,
    choices: List<Pair<MonitoringValue, String>>,
    onChoose: (MonitoringValue) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                choices.forEach { (value, label) ->
                    RadioRow(label, selected = value == current, onClick = { onChoose(value) })
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(
    title: String,
    current: String?,
    onChoose: (MonitoringValue) -> Unit,
    onDismiss: () -> Unit,
) {
    val parts = current?.split(':')?.mapNotNull(String::toIntOrNull).orEmpty()
    val picker =
        rememberTimePickerState(
            initialHour = parts.getOrElse(0) { 0 },
            initialMinute = parts.getOrElse(1) { 0 },
            is24Hour = DateFormat.is24HourFormat(LocalContext.current),
        )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(picker) },
        confirmButton = {
            TextButton(
                onClick = {
                    // The watch only takes ASCII digits, whatever the phone's language writes.
                    val time = String.format(Locale.ROOT, "%02d:%02d", picker.hour, picker.minute)
                    onChoose(MonitoringValue.Text(time))
                }
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun NumberDialog(
    title: String,
    current: Long?,
    range: LongRange,
    onChoose: (MonitoringValue) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(current?.toString().orEmpty()) }
    val value = text.toLongOrNull()?.takeIf { it in range }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { new -> text = new.filter { it in '0'..'9' }.take(4) },
                supportingText = {
                    Text(stringResource(R.string.monitoring_range, range.first, range.last))
                },
                isError = text.isNotEmpty() && value == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { value?.let { onChoose(MonitoringValue.Number(it)) } },
                enabled = value != null,
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
