package dev.ticbox.companion.ui.device

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.api.ConnectionPhase
import dev.ticbox.companion.api.DeviceCommandStatus
import dev.ticbox.companion.api.MonitoringCommandStatus
import dev.ticbox.companion.api.MonitoringPhase
import dev.ticbox.companion.api.MonitoringSettingId
import dev.ticbox.companion.api.MonitoringSettingObservation
import dev.ticbox.companion.api.MonitoringSettingsState
import dev.ticbox.companion.api.MonitoringValue
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.bridge.LocalBridgeBanner
import dev.ticbox.companion.ui.bridge.LocalBridgeStatus
import dev.ticbox.companion.ui.bridge.localBridgeStatus
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.common.UiText
import dev.ticbox.companion.ui.common.asString
import dev.ticbox.companion.ui.common.uiText
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.ActionIcon
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.BannerTone
import dev.ticbox.companion.ui.designsystem.BusyBar
import dev.ticbox.companion.ui.designsystem.EmptyState
import dev.ticbox.companion.ui.designsystem.FadeSwitch
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.GrowingGaps
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.NavigationRow
import dev.ticbox.companion.ui.designsystem.PageColumn
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.Reveal
import dev.ticbox.companion.ui.designsystem.RevealValue
import dev.ticbox.companion.ui.designsystem.StatusBanner
import dev.ticbox.companion.ui.navigation.WatchModuleRoute
import dev.ticbox.companion.ui.toolbox.toolboxMessage
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

data class HealthMonitoringUiState(
    val monitoring: MonitoringSettingsState,
    val linkReady: Boolean,
    val bridge: LocalBridgeStatus,
)

class HealthMonitoringViewModel(dependencies: CompanionDependencies) :
    SessionViewModel(dependencies) {
    val state: StateFlow<HealthMonitoringUiState?> =
        sessionState(
            current = {
                HealthMonitoringUiState(
                    it.services.monitoring.state.value,
                    it.client.state.value.connection.phase == ConnectionPhase.LINK_READY,
                    it.localBridgeStatus(),
                )
            },
            updates = { session ->
                combine(
                    session.services.monitoring.state,
                    session.client.state,
                    session.services.localHealth.state,
                    session.services.deviceSettings.state,
                ) { monitoring, watch, _, _ ->
                    HealthMonitoringUiState(
                        monitoring,
                        watch.connection.phase == ConnectionPhase.LINK_READY,
                        session.localBridgeStatus(),
                    )
                }
            },
        )

    private var readForSession: Long? = null

    /**
     * Opening this page is asking for these values, so it reads them once per connection. A read
     * that fails stays failed until asked again, and nothing here ever writes on its own.
     */
    fun readOnEntry() {
        val current = state.value ?: return
        val monitoring = current.monitoring
        if (monitoring.current || monitoring.phase != MonitoringPhase.READY) return
        if (current.bridge == LocalBridgeStatus.NOT_INSTALLED) return
        if (readForSession == monitoring.sessionId) return
        readForSession = monitoring.sessionId
        read()
    }

    fun read() =
        command(READ) {
            it.services.monitoring.refresh()
            null
        }

    fun set(id: MonitoringSettingId, value: MonitoringValue) =
        command(id) {
            val result = it.services.monitoring.set(id, value)
            // Every other outcome shows in the setting or in the page's notice.
            uiText(R.string.monitoring_value_rejected).takeIf {
                result.status == MonitoringCommandStatus.INVALID_ARGUMENT
            }
        }

    fun setHeartHealthFeatureVisible(visible: Boolean) =
        command(HEART_HEALTH_FEATURE) {
            val result = it.services.deviceSettings.setHeartHealthFeatureVisible(visible)
            // Hiding also turns monitoring off, which makes a value on show wrong.
            val monitoring = it.services.monitoring
            val sent = result.status == DeviceCommandStatus.SUBMITTED_UNVERIFIED
            if (!visible && sent && monitoring.state.value.current) monitoring.refresh()
            result.message()
        }

    companion object {
        const val READ = "monitoring_read"
        const val HEART_HEALTH_FEATURE = "heart_health_feature"
    }
}

private class MonitoringSection(
    @param:StringRes val title: Int,
    val ids: List<MonitoringSettingId>,
    @param:StringRes val note: Int? = null,
)

private val BasicSections =
    listOf(
        MonitoringSection(
            R.string.monitoring_section_heart_rate,
            listOf(
                MonitoringSettingId.BACKGROUND_HEART_RATE,
                MonitoringSettingId.BACKGROUND_HEART_RATE_MODE,
                MonitoringSettingId.REST_HEART_RATE_ALERT,
                MonitoringSettingId.REST_HEART_RATE_LOWER,
                MonitoringSettingId.REST_HEART_RATE_UPPER,
            ),
        ),
        MonitoringSection(
            R.string.monitoring_section_blood_oxygen,
            listOf(
                MonitoringSettingId.BLOOD_OXYGEN,
                MonitoringSettingId.SLEEP_BLOOD_OXYGEN_INTERVAL,
            ),
        ),
        MonitoringSection(
            R.string.monitoring_section_wellness,
            listOf(MonitoringSettingId.PRESSURE, MonitoringSettingId.BODY_MIND),
        ),
        MonitoringSection(
            R.string.monitoring_section_sleep,
            listOf(
                MonitoringSettingId.SLEEP,
                MonitoringSettingId.RESPIRATION,
                MonitoringSettingId.TEMPERATURE,
                MonitoringSettingId.TEMPERATURE_UNIT,
            ),
        ),
        MonitoringSection(
            R.string.monitoring_section_activity,
            listOf(
                MonitoringSettingId.STEP_COUNTING,
                MonitoringSettingId.AUTO_EXERCISE,
                MonitoringSettingId.EXERCISE_HEART_RATE_ALERT,
                MonitoringSettingId.EXERCISE_HEART_RATE_UPPER,
            ),
        ),
    )

/** Everything here needs the optional Magisk module on the watch. */
private val ModuleSections =
    listOf(
        MonitoringSection(
            R.string.monitoring_section_activity_reminders,
            listOf(
                MonitoringSettingId.SEDENTARY_REMINDER,
                MonitoringSettingId.STEP_GOAL_APPROACH_REMINDER,
                MonitoringSettingId.DAILY_GOALS_REMINDER,
                MonitoringSettingId.HEALTH_QUIET_START,
                MonitoringSettingId.HEALTH_QUIET_END,
            ),
            note = R.string.monitoring_quiet_note,
        ),
        MonitoringSection(
            R.string.monitoring_section_sleep_reminders,
            listOf(
                MonitoringSettingId.SLEEP_BEDTIME_REMINDER,
                MonitoringSettingId.SLEEP_COMPLETE_REMINDER,
                MonitoringSettingId.SLEEP_WAKE_ALARM,
                MonitoringSettingId.SLEEP_SMART_AWAKE_MINUTES,
                MonitoringSettingId.SLEEP_ALARM_VIBRATION,
                MonitoringSettingId.SLEEP_ALARM_SOUND,
            ),
        ),
        MonitoringSection(
            R.string.monitoring_section_sleep_schedule,
            listOf(
                MonitoringSettingId.SLEEP_EXPECTED_BEDTIME,
                MonitoringSettingId.SLEEP_EXPECTED_WAKE_TIME,
                MonitoringSettingId.SLEEP_EXCLUSION_ENABLED,
                MonitoringSettingId.SLEEP_EXCLUSION_START,
                MonitoringSettingId.SLEEP_EXCLUSION_END,
            ),
        ),
    )

/** What the module's refusals and the bridge's failures mean for this page. */
internal fun monitoringMessage(reason: String): UiText =
    when (reason) {
        "health_module_period_conflict" -> uiText(R.string.monitoring_period_conflict)
        "monitoring_module_update_required" -> uiText(R.string.monitoring_provider_update_module)
        "monitoring_module_unavailable",
        "monitoring_provider_permission_denied" -> uiText(R.string.monitoring_provider_needs_module)
        "monitoring_module_provider_permission_denied" ->
            uiText(R.string.monitoring_provider_denied)
        else -> toolboxMessage(reason)
    }

/** Null once the values on show are the watch's own. */
private fun MonitoringSettingsState.notice(linkReady: Boolean, reading: Boolean): UiText? {
    val reason = lastOperation?.reason
    return when {
        !linkReady -> uiText(R.string.error_not_connected)
        reading -> uiText(R.string.monitoring_reading)
        current -> null
        lastOperation?.status == MonitoringCommandStatus.STORED_UNVERIFIED ->
            uiText(R.string.monitoring_unverified)
        reason != null -> monitoringMessage(reason)
        settings.isNotEmpty() -> uiText(R.string.monitoring_stale)
        else -> uiText(R.string.monitoring_needs_root)
    }
}

@Composable
fun HealthMonitoringScreen(onBack: () -> Unit, navigate: (Any) -> Unit) {
    val viewModel = companionViewModel { HealthMonitoringViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val current = state
    var editing by remember { mutableStateOf<MonitoringSettingId?>(null) }
    LaunchedEffect(current?.monitoring?.sessionId, current?.monitoring?.phase, current?.bridge) {
        viewModel.readOnEntry()
    }
    PageScaffold(
        stringResource(R.string.monitoring_title),
        onBack = onBack,
        actions = {
            ActionIcon(
                AppIcons.Refresh,
                stringResource(R.string.monitoring_read_again),
                viewModel::read,
                enabled = current?.linkReady == true && HealthMonitoringViewModel.READ !in pending,
            )
        },
    ) { padding ->
        if (current == null) {
            EmptyState(
                AppIcons.HeartMonitor,
                stringResource(R.string.no_watch_title),
                stringResource(R.string.no_watch_body),
                Modifier.padding(padding),
            )
            return@PageScaffold
        }
        val monitoring = current.monitoring
        val reading =
            HealthMonitoringViewModel.READ in pending || monitoring.phase == MonitoringPhase.READING
        val settings = monitoring.settings.associateBy { it.descriptor.id }
        val editable: (MonitoringSettingObservation) -> Boolean = {
            current.linkReady &&
                monitoring.current &&
                !reading &&
                it.descriptor.writable &&
                it.descriptor.id !in pending
        }
        PageColumn(padding) {
            BusyBar(reading || monitoring.phase == MonitoringPhase.WRITING)
            LocalBridgeBanner(current.bridge, R.string.monitoring_needs_bridge, navigate)
            RevealValue(monitoring.notice(current.linkReady, reading)) { notice ->
                StatusBanner(
                    notice.asString(),
                    tone = if (reading) BannerTone.Info else BannerTone.Warning,
                    actionLabel =
                        stringResource(R.string.monitoring_read_again).takeIf {
                            current.linkReady && !reading
                        },
                    onAction = viewModel::read,
                )
            }
            val providerIssue =
                settings.values
                    .firstOrNull {
                        !it.available &&
                            !it.descriptor.requiresInProcessModule &&
                            it.unavailableReason in
                                setOf(
                                    "monitoring_module_update_required",
                                    "monitoring_module_unavailable",
                                    "monitoring_provider_permission_denied",
                                )
                    }
                    ?.unavailableReason
            RevealValue(providerIssue) { reason ->
                StatusBanner(
                    monitoringMessage(reason).asString(),
                    tone = BannerTone.Warning,
                    actionLabel = stringResource(R.string.module_open),
                    onAction = { navigate(WatchModuleRoute) },
                )
            }
            RevealValue(monitoring.readOnlyVersion()) { version ->
                StatusBanner(stringResource(R.string.monitoring_read_only, version))
            }
            HeartHealthSection(
                setting = settings[MonitoringSettingId.HEART_HEALTH],
                editable = editable,
                linkReady = current.linkReady,
                featureBusy = HealthMonitoringViewModel.HEART_HEALTH_FEATURE in pending,
                onSet = viewModel::set,
                onEdit = { editing = it },
                onSetFeatureVisible = viewModel::setHeartHealthFeatureVisible,
            )
            Reveal(settings.isNotEmpty(), spacing = 20.dp) {
                BasicSections.forEach { section ->
                    SettingsGroup(section, settings, editable, viewModel::set) { editing = it }
                }
                val moduleSettings = ModuleSections.flatMap { it.ids }.mapNotNull(settings::get)
                FadeSwitch(moduleSettings.none { it.available }) { noModule ->
                    if (noModule) {
                        StatusBanner(
                            stringResource(R.string.monitoring_no_module),
                            title = stringResource(R.string.monitoring_no_module_title),
                            actionLabel = stringResource(R.string.module_open),
                            onAction = { navigate(WatchModuleRoute) },
                        )
                    } else {
                        Column(Modifier.fillMaxWidth(), GrowingGaps(20.dp)) {
                            ModuleSections.forEach { section ->
                                SettingsGroup(section, settings, editable, viewModel::set) {
                                    editing = it
                                }
                            }
                        }
                    }
                }
            }
        }
        val edited = editing?.let(settings::get)
        if (edited != null) {
            SettingEditor(
                edited,
                onSet = {
                    viewModel.set(edited.descriptor.id, it)
                    editing = null
                },
                onDismiss = { editing = null },
            )
        }
    }
}

/** Set when the watch's health app is a version whose writes haven't been checked. */
private fun MonitoringSettingsState.readOnlyVersion(): String? =
    source
        ?.takeIf { current && settings.any { it.available && !it.descriptor.writable } }
        ?.let { it.versionName ?: it.versionCode.toString() }

@Composable
private fun SettingsGroup(
    section: MonitoringSection,
    settings: Map<MonitoringSettingId, MonitoringSettingObservation>,
    editable: (MonitoringSettingObservation) -> Boolean,
    onSet: (MonitoringSettingId, MonitoringValue) -> Unit,
    onEdit: (MonitoringSettingId) -> Unit,
) {
    val shown = section.ids.mapNotNull(settings::get)
    if (shown.isEmpty()) return
    ListGroup(title = stringResource(section.title)) {
        shown.forEach { setting ->
            SettingRow(
                setting,
                enabled = editable(setting),
                onToggle = { onSet(setting.descriptor.id, MonitoringValue.Toggle(it)) },
                onEdit = { onEdit(setting.descriptor.id) },
            )
        }
    }
    section.note?.let { FootnoteText(stringResource(it)) }
}

/**
 * Monitoring is a real setting, read through Local Bridge with root. Whether the watch offers the
 * feature is a message that needs neither and that the watch never answers, so it can be asked for
 * but has no state to show.
 */
@Composable
private fun HeartHealthSection(
    setting: MonitoringSettingObservation?,
    editable: (MonitoringSettingObservation) -> Boolean,
    linkReady: Boolean,
    featureBusy: Boolean,
    onSet: (MonitoringSettingId, MonitoringValue) -> Unit,
    onEdit: (MonitoringSettingId) -> Unit,
    onSetFeatureVisible: (Boolean) -> Unit,
) {
    ListGroup(title = stringResource(R.string.preferences_heart_health)) {
        RevealValue(setting) {
            SettingRow(
                it,
                enabled = editable(it),
                onToggle = { on -> onSet(it.descriptor.id, MonitoringValue.Toggle(on)) },
                onEdit = { onEdit(it.descriptor.id) },
            )
        }
        NavigationRow(
            stringResource(R.string.preferences_heart_health_show),
            onClick = { onSetFeatureVisible(true) },
            icon = AppIcons.Visible,
            enabled = linkReady && !featureBusy,
            trailingIcon = null,
        )
        NavigationRow(
            stringResource(R.string.preferences_heart_health_hide),
            onClick = { onSetFeatureVisible(false) },
            icon = AppIcons.Hidden,
            enabled = linkReady && !featureBusy,
            trailingIcon = null,
        )
    }
    FootnoteText(stringResource(R.string.preferences_heart_health_note))
}
