package dev.ticbox.companion.ui.sos

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.api.ConnectionPhase
import dev.ticbox.companion.api.SosAssistancePhase
import dev.ticbox.companion.api.SosAssistanceState
import dev.ticbox.companion.api.SosCommandResult
import dev.ticbox.companion.api.SosCommandStatus
import dev.ticbox.companion.api.SosEmergencyContact
import dev.ticbox.companion.api.SosFallDetectionMode
import dev.ticbox.companion.api.SosLimits
import dev.ticbox.companion.api.SosPhase
import dev.ticbox.companion.api.SosPhoneCapabilities
import dev.ticbox.companion.api.SosSettingField
import dev.ticbox.companion.api.SosState
import dev.ticbox.companion.api.SosWatchEditKind
import dev.ticbox.companion.api.SosWatchSettings
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.LocalSystemActions
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.SosAccess
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.common.UiText
import dev.ticbox.companion.ui.common.asString
import dev.ticbox.companion.ui.common.currentLocale
import dev.ticbox.companion.ui.common.formatNumber
import dev.ticbox.companion.ui.common.formatTime
import dev.ticbox.companion.ui.common.uiText
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.ActionIcon
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.BannerTone
import dev.ticbox.companion.ui.designsystem.BusyBar
import dev.ticbox.companion.ui.designsystem.EmptyState
import dev.ticbox.companion.ui.designsystem.FadeSwitch
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.NavigationRow
import dev.ticbox.companion.ui.designsystem.PageColumn
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.RadioRow
import dev.ticbox.companion.ui.designsystem.Reveal
import dev.ticbox.companion.ui.designsystem.RevealValue
import dev.ticbox.companion.ui.designsystem.StatusBanner
import dev.ticbox.companion.ui.designsystem.SwitchRow
import dev.ticbox.companion.ui.designsystem.ValueRow
import dev.ticbox.companion.ui.toolbox.ConfirmDialog
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

data class SosUiState(
    val sos: SosState,
    val assistance: SosAssistanceState,
    val linkReady: Boolean,
    val isDemo: Boolean,
) {
    /** The watch checks each change against what it last reported, so that has to be current. */
    val canEdit: Boolean
        get() = linkReady && sos.settingsCurrent

    /** An alert in progress: changing the SIM or turning assistance off would end it. */
    val alertRunning: Boolean
        get() =
            assistance.phase == SosAssistancePhase.STARTING ||
                assistance.phase == SosAssistancePhase.ACTIVE
}

class SosViewModel(dependencies: CompanionDependencies) : SessionViewModel(dependencies) {
    val state: StateFlow<SosUiState?> =
        sessionState(
            current = {
                SosUiState(
                    it.services.sos.state.value,
                    it.services.sos.phoneAssistance.state.value,
                    it.client.state.value.connection.phase == ConnectionPhase.LINK_READY,
                    it.isDemo,
                )
            },
            updates = { session ->
                combine(
                    session.services.sos.state,
                    session.services.sos.phoneAssistance.state,
                    session.client.state,
                ) { sos, assistance, watch ->
                    SosUiState(
                        sos,
                        assistance,
                        watch.connection.phase == ConnectionPhase.LINK_READY,
                        session.isDemo,
                    )
                }
            },
        )

    fun refresh() =
        command(REFRESH) {
            it.services.sos.phoneAssistance.refreshCapabilities()
            it.services.sos.refresh().message()
        }

    /** After returning from a permission prompt or the system's settings. */
    fun refreshAccess() =
        command(ACCESS) {
            it.services.sos.phoneAssistance.refreshCapabilities()
            null
        }

    fun setEmergencyNumber(number: String) =
        command(SosSettingField.EMERGENCY_NUMBER) {
            it.services.sos.setEmergencyNumber(number).message()
        }

    fun setContacts(contacts: List<SosEmergencyContact>) =
        command(SosSettingField.CONTACTS) { it.services.sos.setContacts(contacts).message() }

    fun setButtonTrigger(enabled: Boolean) =
        command(SosSettingField.BUTTON_TRIGGER) {
            it.services.sos.setButtonTrigger(enabled).message()
        }

    fun setShareLocation(enabled: Boolean) =
        command(SosSettingField.SHARE_LOCATION) {
            it.services.sos.setShareLocation(enabled).message()
        }

    fun setFallDetection(mode: SosFallDetectionMode) =
        command(SosSettingField.FALL_DETECTION) {
            it.services.sos.setFallDetection(mode).message()
        }

    fun setAssistanceEnabled(enabled: Boolean) =
        command(ASSISTANCE) {
            val assistance = it.services.sos.phoneAssistance
            assistance.configure(assistance.state.value.config.copy(enabled = enabled))
            null
        }

    fun setSubscription(id: Int?) =
        command(ASSISTANCE) {
            val assistance = it.services.sos.phoneAssistance
            assistance.configure(assistance.state.value.config.copy(subscriptionId = id))
            null
        }

    fun stopAssistance() =
        command(STOP) {
            it.services.sos.phoneAssistance.stop()
            null
        }

    companion object {
        const val REFRESH = "sos_refresh"
        const val ACCESS = "sos_access"
        const val ASSISTANCE = "sos_assistance"
        const val STOP = "sos_stop"
    }
}

/** Null when the watch read the value back as sent, which the page then shows. */
private fun SosCommandResult.message(): UiText? =
    when (status) {
        SosCommandStatus.CONFIRMED -> null
        SosCommandStatus.TIMED_OUT -> uiText(R.string.sos_result_timeout)
        SosCommandStatus.READ_BACK_MISMATCH -> uiText(R.string.sos_result_mismatch)
        SosCommandStatus.INVALID_ARGUMENT -> uiText(R.string.sos_result_invalid)
        SosCommandStatus.FAILED -> uiText(R.string.preferences_failed)
        SosCommandStatus.UNAVAILABLE ->
            when (reason) {
                "previous_write_outcome_unknown_reconnect_required",
                "readback_correlation_lost_reconnect_required" ->
                    uiText(R.string.sos_result_reconnect)
                else -> uiText(R.string.preferences_unavailable)
            }
    }

private fun sosIssueMessage(reason: String, skipped: Int): UiText =
    when (reason) {
        "sms_permission_required" -> uiText(R.string.sos_issue_sms_permission)
        "sms_not_supported" -> uiText(R.string.sos_issue_no_sms)
        "sms_subscription_required",
        "sms_unavailable" -> uiText(R.string.sos_issue_no_sim)
        "sms_subscription_temporarily_unavailable" -> uiText(R.string.sos_issue_sim_waiting)
        "sms_subscription_changed" -> uiText(R.string.sos_issue_sim_changed)
        "sms_submission_failed" -> uiText(R.string.sos_issue_submit_failed)
        "sms_submission_partial" -> uiText(R.string.sos_issue_submit_partial)
        "sms_previous_submission_unconfirmed" -> uiText(R.string.sos_issue_unconfirmed)
        "foreground_location_permission_required",
        "background_location_permission_required" -> uiText(R.string.sos_issue_location_permission)
        LOCATION_DISABLED -> uiText(R.string.sos_issue_location_off)
        "location_provider_unavailable",
        "location_unavailable" -> uiText(R.string.sos_issue_location_unavailable)
        "no_emergency_contacts" -> uiText(R.string.sos_issue_no_contacts)
        "sos_contacts_invalid" -> uiText(R.string.sos_issue_contacts_invalid)
        "sos_contacts_partially_skipped" -> uiText(R.string.sos_skipped, skipped)
        "phone_assistance_disabled" -> uiText(R.string.sos_issue_disabled)
        "sos_settings_not_current",
        "sos_settings_unavailable",
        "sos_settings_refresh_timeout",
        "watch_contacts_unreadable",
        "readback_correlation_lost_reconnect_required" -> uiText(R.string.sos_issue_settings)
        "sos_event_expired" -> uiText(R.string.sos_issue_expired)
        "sos_execution_failed",
        "sos_storage_unavailable",
        "phone_assistance_failed" -> uiText(R.string.sos_issue_failed)
        else ->
            if (reason.startsWith("sos_foreground_")) uiText(R.string.sos_issue_failed)
            else uiText(R.string.sos_issue_code, reason)
    }

private const val LOCATION_DISABLED = "location_disabled"

/** What's open on top of the page. Numbers stay in memory: neither logged nor saved. */
internal sealed interface SosDialog {
    data object Number : SosDialog

    data class Contact(val index: Int?) : SosDialog

    data class Sim(val ids: List<Int>) : SosDialog

    data object ConfirmAssistance : SosDialog

    data object ConfirmStop : SosDialog
}

/** The editor for what the watch asked to change. */
internal fun sosEditorFor(edit: String): SosDialog =
    if (edit == SosWatchEditKind.CONTACTS.name) SosDialog.Contact(null) else SosDialog.Number

private fun SosUiState.notice(reading: Boolean): UiText? =
    when {
        !linkReady ->
            if (sos.settings != null) uiText(R.string.sos_stale)
            else uiText(R.string.error_not_connected)
        reading -> uiText(R.string.sos_reading)
        sos.settingsCurrent -> null
        else -> uiText(R.string.sos_not_read)
    }

@Composable
fun SosScreen(edit: String?, sequence: Long, onBack: () -> Unit) {
    val viewModel = companionViewModel { SosViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val system = LocalSystemActions.current
    var dialog by remember { mutableStateOf<SosDialog?>(null) }
    var handledEdit by rememberSaveable { mutableLongStateOf(-1L) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshAccess() }
    LaunchedEffect(sequence) {
        if (edit != null && sequence != handledEdit) {
            handledEdit = sequence
            dialog = sosEditorFor(edit)
        }
    }
    PageScaffold(
        stringResource(R.string.sos_title),
        onBack = onBack,
        actions = {
            ActionIcon(
                AppIcons.Refresh,
                stringResource(R.string.monitoring_read_again),
                viewModel::refresh,
                enabled = state?.linkReady == true && SosViewModel.REFRESH !in pending,
            )
        },
    ) { padding ->
        val current = state
        if (current == null) {
            EmptyState(
                AppIcons.Sos,
                stringResource(R.string.no_watch_title),
                stringResource(R.string.no_watch_body),
                Modifier.padding(padding),
            )
            return@PageScaffold
        }
        val sos = current.sos
        val reading = SosViewModel.REFRESH in pending || sos.phase == SosPhase.READING
        val fieldReady: (SosSettingField) -> Boolean = { current.canEdit && it !in pending }
        PageColumn(padding) {
            AssistanceStatus(
                current.assistance,
                onStop = { dialog = SosDialog.ConfirmStop },
                onOpenLocationSettings = { system.requestSosAccess(SosAccess.LOCATION_SETTINGS) },
            )
            // The other operations are about keeping this page's data and show as its state.
            RevealValue(sos.lastIssue?.takeIf { it.operation == "emergency" }) { issue ->
                StatusBanner(
                    sosIssueMessage(issue.reason, current.assistance.skippedRecipientCount)
                        .asString(),
                    tone = BannerTone.Warning,
                )
            }
            RevealValue(current.notice(reading)) { notice ->
                StatusBanner(
                    notice.asString(),
                    tone = if (reading) BannerTone.Info else BannerTone.Warning,
                    actionLabel =
                        stringResource(R.string.monitoring_read_again).takeIf {
                            current.linkReady && !reading
                        },
                    onAction = viewModel::refresh,
                )
            }
            BusyBar(reading || sos.phase == SosPhase.WRITING)
            RevealValue(sos.settings, spacing = 20.dp) { settings ->
                WatchGroup(
                    settings,
                    numberEnabled = fieldReady(SosSettingField.EMERGENCY_NUMBER),
                    buttonEnabled = fieldReady(SosSettingField.BUTTON_TRIGGER),
                    onEditNumber = { dialog = SosDialog.Number },
                    onSetButton = viewModel::setButtonTrigger,
                )
                FallGroup(
                    settings,
                    enabled = fieldReady(SosSettingField.FALL_DETECTION),
                    onSet = viewModel::setFallDetection,
                )
                ContactsGroup(
                    settings,
                    enabled = fieldReady(SosSettingField.CONTACTS),
                    onEdit = { dialog = SosDialog.Contact(it) },
                )
            }
            AssistanceGroup(
                current,
                busy = SosViewModel.ASSISTANCE in pending,
                shareLocationEnabled = fieldReady(SosSettingField.SHARE_LOCATION),
                onSetEnabled = { on ->
                    if (on) dialog = SosDialog.ConfirmAssistance
                    else viewModel.setAssistanceEnabled(false)
                },
                onSetShareLocation = viewModel::setShareLocation,
                onRequest = system::requestSosAccess,
                onPickSim = {
                    val ids = system.sosSmsSubscriptions()
                    if (ids.isEmpty()) system.requestSosAccess(SosAccess.SIM_SELECTION)
                    else dialog = SosDialog.Sim(ids)
                },
            )
            FootnoteText(stringResource(R.string.sos_note))
        }
        val close = { dialog = null }
        val contacts = sos.settings?.contacts.orEmpty()
        when (val open = dialog) {
            null -> Unit
            SosDialog.Number ->
                EmergencyNumberDialog(
                    sos.settings?.emergencyNumber.orEmpty(),
                    onSave = {
                        viewModel.setEmergencyNumber(it)
                        close()
                    },
                    onDismiss = close,
                )
            is SosDialog.Contact ->
                ContactDialog(
                    open.index?.let(contacts::getOrNull),
                    onSave = { contact ->
                        viewModel.setContacts(contacts.withContact(open.index, contact))
                        close()
                    },
                    onDelete =
                        open.index?.let { index ->
                            {
                                viewModel.setContacts(contacts.withoutContact(index))
                                close()
                            }
                        },
                    onDismiss = close,
                )
            is SosDialog.Sim ->
                SimDialog(
                    open.ids,
                    current.assistance.config.subscriptionId,
                    onChoose = {
                        viewModel.setSubscription(it)
                        close()
                    },
                    onDismiss = close,
                )
            SosDialog.ConfirmAssistance ->
                ConfirmDialog(
                    title = stringResource(R.string.sos_assistance_confirm_title),
                    body = stringResource(R.string.sos_assistance_confirm_body),
                    confirmLabel = stringResource(R.string.action_turn_on),
                    onConfirm = {
                        viewModel.setAssistanceEnabled(true)
                        val capabilities = current.assistance.capabilities
                        if (!capabilities.smsPermissionGranted) {
                            system.requestSosAccess(SosAccess.SMS)
                        }
                        close()
                    },
                    onDismiss = close,
                )
            SosDialog.ConfirmStop ->
                ConfirmDialog(
                    title = stringResource(R.string.sos_stop_confirm_title),
                    body = stringResource(R.string.sos_stop_confirm_body),
                    confirmLabel = stringResource(R.string.sos_stop),
                    onConfirm = {
                        viewModel.stopAssistance()
                        close()
                    },
                    onDismiss = close,
                )
        }
    }
}

/**
 * Says only what the phone knows. Texts were handed to the system, never that anyone received them.
 */
@Composable
private fun AssistanceStatus(
    assistance: SosAssistanceState,
    onStop: () -> Unit,
    onOpenLocationSettings: () -> Unit,
) {
    val running =
        assistance.phase == SosAssistancePhase.STARTING ||
            assistance.phase == SosAssistancePhase.ACTIVE
    Reveal(running) {
        val expires = assistance.expiresAtMillis
        StatusBanner(
            if (assistance.phase == SosAssistancePhase.ACTIVE && expires != null) {
                stringResource(
                    R.string.sos_active_body,
                    assistance.smsPartsSubmitted,
                    assistance.recipientCount,
                    formatTime(expires, currentLocale()),
                )
            } else {
                stringResource(R.string.sos_starting)
            },
            title = stringResource(R.string.sos_active_title),
            tone = BannerTone.Warning,
            actionLabel = stringResource(R.string.sos_stop),
            onAction = onStop,
        )
    }
    Reveal(assistance.phase == SosAssistancePhase.STOPPED && assistance.issue == null) {
        StatusBanner(stringResource(R.string.sos_stopped))
    }
    RevealValue(assistance.issue) { issue ->
        StatusBanner(
            sosIssueMessage(issue.reason, assistance.skippedRecipientCount).asString(),
            tone = BannerTone.Warning,
            actionLabel =
                stringResource(R.string.sos_open_location_settings).takeIf {
                    issue.reason == LOCATION_DISABLED
                },
            onAction = onOpenLocationSettings,
        )
    }
}

@Composable
private fun WatchGroup(
    settings: SosWatchSettings,
    numberEnabled: Boolean,
    buttonEnabled: Boolean,
    onEditNumber: () -> Unit,
    onSetButton: (Boolean) -> Unit,
) {
    ListGroup(title = stringResource(R.string.sos_watch_group)) {
        NavigationRow(
            stringResource(R.string.sos_number),
            onClick = onEditNumber,
            icon = AppIcons.Call,
            enabled = numberEnabled,
            trailingIcon = AppIcons.Edit,
            value = settings.emergencyNumber.ifEmpty { stringResource(R.string.sos_number_unset) },
        )
        SwitchRow(
            stringResource(R.string.sos_button),
            checked = settings.buttonTriggerEnabled,
            onCheckedChange = onSetButton,
            summary = stringResource(R.string.sos_button_summary),
            icon = AppIcons.TouchControl,
            enabled = buttonEnabled,
        )
    }
}

@Composable
private fun FallGroup(
    settings: SosWatchSettings,
    enabled: Boolean,
    onSet: (SosFallDetectionMode) -> Unit,
) {
    ListGroup(Modifier.selectableGroup(), title = stringResource(R.string.sos_fall_group)) {
        listOf(
                SosFallDetectionMode.OFF to R.string.sos_fall_off,
                SosFallDetectionMode.ALWAYS to R.string.sos_fall_always,
                SosFallDetectionMode.EXERCISE_ONLY to R.string.sos_fall_exercise,
            )
            .forEach { (mode, label) ->
                RadioRow(
                    stringResource(label),
                    selected = settings.fallDetectionMode == mode,
                    onClick = { onSet(mode) },
                    enabled = enabled,
                )
            }
    }
    RevealValue(settings.fallDetectionValue.takeIf { settings.fallDetectionMode == null }) {
        FootnoteText(stringResource(R.string.sos_fall_unknown, it))
    }
    FootnoteText(stringResource(R.string.sos_fall_note))
}

@Composable
private fun ContactsGroup(settings: SosWatchSettings, enabled: Boolean, onEdit: (Int?) -> Unit) {
    val contacts = settings.contacts
    ListGroup(title = stringResource(R.string.sos_contacts_group)) {
        contacts?.forEachIndexed { index, contact ->
            NavigationRow(
                contact.name,
                onClick = { onEdit(index) },
                icon = AppIcons.Person,
                summary = contact.number,
                enabled = enabled,
                trailingIcon = AppIcons.Edit,
            )
        }
        Reveal(contacts.orEmpty().size < SosLimits.MAX_CONTACTS) {
            NavigationRow(
                stringResource(R.string.sos_contact_add),
                onClick = { onEdit(null) },
                icon = AppIcons.Add,
                enabled = enabled,
                trailingIcon = null,
            )
        }
    }
    Reveal(contacts == null) {
        StatusBanner(
            stringResource(R.string.sos_contacts_unreadable),
            tone = BannerTone.Warning,
        )
    }
}

@Composable
private fun AssistanceGroup(
    state: SosUiState,
    busy: Boolean,
    shareLocationEnabled: Boolean,
    onSetEnabled: (Boolean) -> Unit,
    onSetShareLocation: (Boolean) -> Unit,
    onRequest: (SosAccess) -> Unit,
    onPickSim: () -> Unit,
) {
    val assistance = state.assistance
    val capabilities = assistance.capabilities
    val enabled = assistance.config.enabled
    val shareLocation = state.sos.settings?.shareLocation
    ListGroup(title = stringResource(R.string.sos_assistance_group)) {
        SwitchRow(
            stringResource(R.string.sos_assistance_switch),
            checked = enabled,
            onCheckedChange = onSetEnabled,
            summary = stringResource(R.string.sos_assistance_summary),
            icon = AppIcons.Sms,
            enabled = !busy && !state.alertRunning,
        )
        RevealValue(shareLocation) {
            SwitchRow(
                stringResource(R.string.sos_share_location),
                checked = it,
                onCheckedChange = onSetShareLocation,
                summary = stringResource(R.string.sos_share_location_summary),
                icon = AppIcons.Location,
                enabled = shareLocationEnabled,
            )
        }
        Reveal(enabled) {
            AccessRows(capabilities, shareLocation == true, onRequest)
            if (!state.isDemo) {
                NavigationRow(
                    stringResource(R.string.sos_sim),
                    onClick = onPickSim,
                    icon = AppIcons.SimCard,
                    summary =
                        when {
                            !capabilities.usableSmsSubscription ->
                                stringResource(R.string.sos_sim_none)
                            else ->
                                assistance.config.subscriptionId?.let {
                                    stringResource(R.string.sos_sim_id, it)
                                } ?: stringResource(R.string.sos_sim_default)
                        },
                    enabled = !busy && !state.alertRunning,
                    trailingIcon = null,
                )
            }
        }
        RevealValue(state.sos.emergencyEventsReceived.takeIf { it > 0 }) {
            ValueRow(
                stringResource(R.string.sos_events),
                formatNumber(it, currentLocale()),
                icon = AppIcons.Sos,
            )
        }
    }
}

@Composable
private fun AccessRows(
    capabilities: SosPhoneCapabilities,
    usesLocation: Boolean,
    onRequest: (SosAccess) -> Unit,
) {
    val foreground = capabilities.foregroundLocationPermissionGranted
    AccessRow(
        stringResource(R.string.sos_access_sms),
        AppIcons.Sms,
        capabilities.smsPermissionGranted,
        unavailable =
            stringResource(R.string.sos_access_sms_unsupported).takeUnless {
                capabilities.smsSupported
            },
    ) {
        onRequest(SosAccess.SMS)
    }
    AccessRow(
        stringResource(R.string.sos_access_notifications),
        AppIcons.Notifications,
        capabilities.notificationPermissionGranted,
    ) {
        onRequest(SosAccess.NOTIFICATIONS)
    }
    Reveal(usesLocation) {
        AccessRow(stringResource(R.string.sos_access_location), AppIcons.Location, foreground) {
            onRequest(SosAccess.LOCATION)
        }
        // The system only offers this one once the app can already use location.
        AccessRow(
            stringResource(R.string.sos_access_background_location),
            AppIcons.Location,
            capabilities.backgroundLocationPermissionGranted,
            enabled = foreground,
        ) {
            onRequest(SosAccess.BACKGROUND_LOCATION)
        }
    }
    val missing =
        (capabilities.smsSupported && !capabilities.smsPermissionGranted) ||
            !capabilities.notificationPermissionGranted ||
            (usesLocation && (!foreground || !capabilities.backgroundLocationPermissionGranted))
    // A prompt refused twice doesn't show again, which leaves this as the way to change it.
    Reveal(missing) {
        NavigationRow(
            stringResource(R.string.sos_access_app_settings),
            onClick = { onRequest(SosAccess.APP_SETTINGS) },
            icon = AppIcons.OpenExternal,
            trailingIcon = null,
        )
    }
}

@Composable
private fun AccessRow(
    title: String,
    icon: Int,
    granted: Boolean,
    unavailable: String? = null,
    enabled: Boolean = true,
    onRequest: () -> Unit,
) {
    FadeSwitch(granted) { allowed ->
        if (allowed) {
            ValueRow(title, stringResource(R.string.sos_access_granted), icon = icon)
        } else {
            NavigationRow(
                title,
                onClick = onRequest,
                icon = icon,
                summary = unavailable ?: stringResource(R.string.sos_access_missing),
                enabled = enabled && unavailable == null,
                trailingIcon = null,
            )
        }
    }
}
