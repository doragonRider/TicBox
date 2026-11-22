package dev.ticbox.companion.ui.device

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.api.ConnectionPhase
import dev.ticbox.companion.api.DeviceCommandResult
import dev.ticbox.companion.api.DeviceCommandStatus
import dev.ticbox.companion.api.DeviceSettingsIssueKind
import dev.ticbox.companion.api.DeviceSettingsState
import dev.ticbox.companion.api.WatchSettingsScreen
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.common.UiText
import dev.ticbox.companion.ui.common.currentLocale
import dev.ticbox.companion.ui.common.formatNumber
import dev.ticbox.companion.ui.common.uiText
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.ActionIcon
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.BannerTone
import dev.ticbox.companion.ui.designsystem.EmptyState
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.NavigationRow
import dev.ticbox.companion.ui.designsystem.PageColumn
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.RadioRow
import dev.ticbox.companion.ui.designsystem.Reveal
import dev.ticbox.companion.ui.designsystem.RevealValue
import dev.ticbox.companion.ui.designsystem.StatusBanner
import dev.ticbox.companion.ui.designsystem.ValueRow
import dev.ticbox.companion.ui.navigation.HealthMonitoringRoute
import dev.ticbox.companion.ui.navigation.WatchAppsRoute
import dev.ticbox.companion.ui.navigation.WatchLockRoute
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

data class WatchPreferencesUiState(val device: DeviceSettingsState, val linkReady: Boolean)

class WatchPreferencesViewModel(dependencies: CompanionDependencies) :
    SessionViewModel(dependencies) {
    val state: StateFlow<WatchPreferencesUiState?> =
        sessionState(
            current = {
                WatchPreferencesUiState(
                    it.services.deviceSettings.state.value,
                    it.client.state.value.connection.phase == ConnectionPhase.LINK_READY,
                )
            },
            updates = { session ->
                combine(session.services.deviceSettings.state, session.client.state) { device, watch
                    ->
                    WatchPreferencesUiState(
                        device,
                        watch.connection.phase == ConnectionPhase.LINK_READY,
                    )
                }
            },
        )

    fun setTimeFormat(is24Hour: Boolean?) =
        command(TIME) { it.services.deviceSettings.set24HourFormat(is24Hour).message() }

    fun setLanguage(tags: String?) =
        command(LANGUAGE) { it.services.deviceSettings.setLanguageTags(tags).message() }

    fun open(screen: WatchSettingsScreen) =
        command(screen) { it.services.deviceSettings.openSettings(screen).message(opened = true) }

    fun refresh() = command(REFRESH) { it.services.deviceSettings.refresh().message() }

    companion object {
        const val TIME = "time_format"
        const val LANGUAGE = "watch_language"
        const val REFRESH = "device_refresh"
    }
}

/** What a device command's result means for the user; null when the new state speaks for it. */
internal fun DeviceCommandResult.message(opened: Boolean = false): UiText? =
    when (status) {
        DeviceCommandStatus.PERSISTED_AND_SUBMITTED,
        DeviceCommandStatus.REMOTE_CONFIRMED -> null
        DeviceCommandStatus.SUBMITTED_UNVERIFIED -> uiText(R.string.preferences_sent)
        DeviceCommandStatus.REMOTE_UI_OPENED ->
            if (opened) uiText(R.string.preferences_opened_on_watch) else null
        DeviceCommandStatus.REJECTED -> uiText(R.string.preferences_rejected)
        DeviceCommandStatus.UNAVAILABLE -> uiText(R.string.preferences_unavailable)
        DeviceCommandStatus.TIMED_OUT -> uiText(R.string.preferences_timed_out)
        DeviceCommandStatus.FAILED -> uiText(R.string.preferences_failed)
    }

@Composable
fun WatchPreferencesScreen(onBack: () -> Unit, navigate: (Any) -> Unit) {
    val viewModel = companionViewModel { WatchPreferencesViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    PageScaffold(
        stringResource(R.string.preferences_title),
        onBack = onBack,
        actions = {
            ActionIcon(
                AppIcons.Refresh,
                stringResource(R.string.preferences_refresh),
                viewModel::refresh,
                enabled = state?.linkReady == true && WatchPreferencesViewModel.REFRESH !in pending,
            )
        },
    ) { padding ->
        val current = state
        if (current == null) {
            EmptyState(
                AppIcons.Tune,
                stringResource(R.string.no_watch_title),
                stringResource(R.string.no_watch_body),
                Modifier.padding(padding),
            )
            return@PageScaffold
        }
        val preferences = current.device.preferences
        val ready = current.device.preferencesReady
        PageColumn(padding) {
            AboutWatch(current.device)
            RevealValue(current.device.lastIssue) { issue ->
                StatusBanner(
                    stringResource(
                        when (issue.kind) {
                            DeviceSettingsIssueKind.PREFERENCES_RESTORE ->
                                R.string.preferences_issue_restore
                            DeviceSettingsIssueKind.REPLY_DECODE -> R.string.preferences_issue_reply
                            DeviceSettingsIssueKind.COMMAND -> R.string.preferences_issue_command
                        }
                    ),
                    tone = BannerTone.Warning,
                )
            }
            ListGroup(
                Modifier.selectableGroup(),
                title = stringResource(R.string.preferences_time_format),
            ) {
                listOf(
                        null to R.string.preferences_follow_phone,
                        true to R.string.preferences_24_hour,
                        false to R.string.preferences_12_hour,
                    )
                    .forEach { (value, label) ->
                        RadioRow(
                            stringResource(label),
                            selected = preferences.is24HourFormat == value,
                            onClick = { viewModel.setTimeFormat(value) },
                            enabled = ready && WatchPreferencesViewModel.TIME !in pending,
                        )
                    }
            }
            ListGroup(title = stringResource(R.string.preferences_language)) {
                Column(Modifier.selectableGroup()) {
                    listOf(
                            null to R.string.preferences_follow_phone,
                            "zh-CN" to R.string.preferences_language_zh,
                            "en-US" to R.string.preferences_language_en,
                        )
                        .forEach { (value, label) ->
                            RadioRow(
                                stringResource(label),
                                selected = preferences.languageTags == value,
                                onClick = { viewModel.setLanguage(value) },
                                enabled = ready && WatchPreferencesViewModel.LANGUAGE !in pending,
                            )
                        }
                }
                NavigationRow(
                    stringResource(R.string.preferences_language_on_watch),
                    onClick = { viewModel.open(WatchSettingsScreen.LANGUAGE) },
                    icon = AppIcons.Language,
                    enabled = current.linkReady && WatchSettingsScreen.LANGUAGE !in pending,
                    trailingIcon = AppIcons.OpenExternal,
                )
            }
            FootnoteText(stringResource(R.string.preferences_apply_note))
            DndSyncSection(navigate)
            ListGroup(title = stringResource(R.string.preferences_manage)) {
                NavigationRow(
                    stringResource(R.string.watch_apps_title),
                    onClick = { navigate(WatchAppsRoute) },
                    icon = AppIcons.Apps,
                    summary = stringResource(R.string.watch_apps_summary),
                )
                NavigationRow(
                    stringResource(R.string.watch_lock_title),
                    onClick = { navigate(WatchLockRoute) },
                    icon = AppIcons.Lock,
                    summary = stringResource(R.string.watch_lock_summary),
                )
                NavigationRow(
                    stringResource(R.string.monitoring_title),
                    onClick = { navigate(HealthMonitoringRoute) },
                    icon = AppIcons.HeartMonitor,
                    summary = stringResource(R.string.monitoring_summary),
                )
            }
            ListGroup(title = stringResource(R.string.preferences_open_on_watch)) {
                listOf(
                        WatchSettingsScreen.MAIN to R.string.preferences_screen_main,
                        WatchSettingsScreen.DISPLAY to R.string.preferences_screen_display,
                        WatchSettingsScreen.SOUND to R.string.preferences_screen_sound,
                        WatchSettingsScreen.WIFI to R.string.preferences_screen_wifi,
                        WatchSettingsScreen.BLUETOOTH to R.string.preferences_screen_bluetooth,
                        WatchSettingsScreen.LANGUAGE to R.string.preferences_screen_language,
                        WatchSettingsScreen.DATE_TIME to R.string.preferences_screen_date_time,
                        WatchSettingsScreen.BATTERY to R.string.preferences_screen_battery,
                        WatchSettingsScreen.APPS to R.string.preferences_screen_apps,
                        WatchSettingsScreen.ABOUT to R.string.preferences_screen_about,
                    )
                    .forEach { (screen, label) ->
                        NavigationRow(
                            stringResource(label),
                            onClick = { viewModel.open(screen) },
                            enabled = current.linkReady && screen !in pending,
                            trailingIcon = AppIcons.OpenExternal,
                        )
                    }
            }
        }
    }
}

@Composable
private fun AboutWatch(device: DeviceSettingsState) {
    val info = device.info
    val battery = device.batteryPercent
    Reveal(info?.model != null || info?.sdkVersion != null || battery != null) {
        ListGroup(title = stringResource(R.string.preferences_about)) {
            RevealValue(info?.model) { ValueRow(stringResource(R.string.preferences_model), it) }
            RevealValue(info?.sdkVersion) {
                ValueRow(
                    stringResource(R.string.preferences_android_api),
                    formatNumber(it, currentLocale()),
                )
            }
            RevealValue(battery) {
                ValueRow(
                    stringResource(R.string.preferences_battery),
                    stringResource(R.string.value_percent, formatNumber(it, currentLocale())),
                )
            }
        }
    }
}
