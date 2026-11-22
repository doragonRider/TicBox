package dev.ticbox.companion.ui.calling

import android.telecom.Call
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.api.CallAuthorizationAction
import dev.ticbox.companion.api.CallCommand
import dev.ticbox.companion.api.CallCommandResult
import dev.ticbox.companion.api.CallCommandStatus
import dev.ticbox.companion.api.CallingAccess
import dev.ticbox.companion.api.CallingPhase
import dev.ticbox.companion.api.CompanionCall
import dev.ticbox.companion.api.CompanionCallingState
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.LocalSystemActions
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.common.UiText
import dev.ticbox.companion.ui.common.uiText
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.ActionIcon
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.BannerTone
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
import dev.ticbox.companion.ui.navigation.PairingRoute
import kotlinx.coroutines.flow.StateFlow

/** CallCommand.Action values. */
private const val ActionAnswer = 1
private const val ActionHangUp = 2
private const val ActionHold = 3
private const val ActionUnhold = 4
private const val ActionMute = 5
private const val ActionUnmute = 6
private const val ActionSilence = 7

class CallingViewModel(dependencies: CompanionDependencies) : SessionViewModel(dependencies) {
    val state: StateFlow<CompanionCallingState?> =
        sessionState(
            current = { it.services.calling.state.value },
            updates = { it.services.calling.state },
        )

    fun setEnabled(enabled: Boolean) =
        command(ENABLED) {
            it.services.calling.setEnabled(enabled)
            null
        }

    fun refreshAccess() =
        command(ACCESS) {
            it.services.calling.refreshAccess()
            null
        }

    fun resync() =
        command(RESYNC) {
            it.services.calling.resync()
            null
        }

    fun execute(call: CallCommand) = command(call) { it.services.calling.execute(call).message() }

    /** DISPATCHED means the system took the command; the call list shows what actually happened. */
    private fun CallCommandResult.message(): UiText? =
        when (status) {
            CallCommandStatus.DISPATCHED -> null
            CallCommandStatus.DEFERRED -> uiText(R.string.calling_result_deferred)
            CallCommandStatus.UNAVAILABLE -> uiText(R.string.calling_result_unavailable)
            CallCommandStatus.UNSUPPORTED -> uiText(R.string.calling_result_unsupported)
            CallCommandStatus.MALFORMED -> uiText(R.string.calling_result_malformed)
            CallCommandStatus.STALE_SESSION -> uiText(R.string.calling_result_stale)
            CallCommandStatus.DISABLED -> uiText(R.string.calling_result_disabled)
            CallCommandStatus.FAILED -> uiText(R.string.calling_result_failed)
        }

    companion object {
        const val ENABLED = "calling_enabled"
        const val ACCESS = "calling_access"
        const val RESYNC = "calling_resync"
    }
}

private val CallingPhase.status: Int
    get() =
        when (this) {
            CallingPhase.DISABLED -> R.string.calling_phase_disabled
            CallingPhase.AUTHORIZATION_REQUIRED -> R.string.calling_phase_authorization
            CallingPhase.DISCONNECTED -> R.string.calling_phase_disconnected
            CallingPhase.READY -> R.string.calling_phase_ready
            CallingPhase.ACTIVE -> R.string.calling_phase_active
            CallingPhase.FAILED -> R.string.calling_phase_failed
            CallingPhase.CLOSED -> R.string.calling_phase_closed
        }

private val CompanionCall.status: Int
    get() =
        when (stateValue) {
            Call.STATE_NEW,
            Call.STATE_CONNECTING,
            Call.STATE_SELECT_PHONE_ACCOUNT -> R.string.calling_state_connecting
            Call.STATE_DIALING -> R.string.calling_state_dialing
            Call.STATE_RINGING,
            Call.STATE_SIMULATED_RINGING -> R.string.calling_state_ringing
            Call.STATE_HOLDING -> R.string.calling_state_holding
            Call.STATE_ACTIVE,
            Call.STATE_AUDIO_PROCESSING -> R.string.calling_state_active
            Call.STATE_DISCONNECTING -> R.string.calling_state_disconnecting
            Call.STATE_DISCONNECTED -> R.string.calling_state_disconnected
            else -> R.string.calling_state_unknown
        }

@Composable
fun CallingScreen(onBack: () -> Unit, navigate: (Any) -> Unit) {
    val viewModel = companionViewModel { CallingViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    // Permissions can change in system settings while this page is away.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshAccess() }
    PageScaffold(
        stringResource(R.string.calling_title),
        onBack = onBack,
        actions = {
            ActionIcon(
                AppIcons.Refresh,
                stringResource(R.string.calling_resync),
                viewModel::resync,
                enabled = state?.enabled == true && CallingViewModel.RESYNC !in pending,
            )
        },
    ) { padding ->
        val current = state
        if (current == null) {
            EmptyState(
                AppIcons.Call,
                stringResource(R.string.no_watch_title),
                stringResource(R.string.no_watch_body),
                Modifier.padding(padding),
            )
            return@PageScaffold
        }
        PageColumn(padding) {
            ListGroup {
                SwitchRow(
                    stringResource(R.string.calling_enabled),
                    checked = current.enabled,
                    onCheckedChange = viewModel::setEnabled,
                    summary = stringResource(R.string.calling_enabled_summary),
                    icon = AppIcons.Call,
                    enabled = current.settingsLoaded && CallingViewModel.ENABLED !in pending,
                )
            }
            StatusBanner(
                stringResource(current.phase.status),
                tone =
                    if (current.phase == CallingPhase.FAILED) BannerTone.Error else BannerTone.Info,
            )
            Reveal(!current.settingsDurable) {
                StatusBanner(
                    stringResource(R.string.calling_not_durable),
                    tone = BannerTone.Warning,
                )
            }
            Reveal(current.enabled, spacing = 20.dp) {
                RevealValue(current.calls.takeIf { it.isNotEmpty() }, spacing = 20.dp) { calls ->
                    calls.forEach { call -> CallCard(call, current.muted, viewModel::execute) }
                }
                AudioGroup(current, viewModel::execute)
            }
            AccessGroup(current.access, navigate)
            FootnoteText(stringResource(R.string.calling_note))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CallCard(call: CompanionCall, muted: Boolean?, onCommand: (CallCommand) -> Unit) {
    var keypad by rememberSaveable(call.id) { mutableStateOf(false) }
    val state = call.stateValue
    val ringing = state == Call.STATE_RINGING || state == Call.STATE_SIMULATED_RINGING
    val active = state == Call.STATE_ACTIVE
    val action = { value: Int -> onCommand(CallCommand.Action(call.id, value)) }
    ListGroup {
        Column(
            Modifier.fillMaxWidth().animateContentSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                Text(
                    call.contactDisplayName
                        ?: call.callerDisplayName
                        ?: call.handle
                        ?: stringResource(R.string.calling_unknown_caller),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    listOfNotNull(
                            stringResource(call.status),
                            call.disconnectCause?.label?.takeIf {
                                state == Call.STATE_DISCONNECTED
                            },
                        )
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state != Call.STATE_DISCONNECTED) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (ringing) {
                        if (call.companionCanAnswer) {
                            Button(onClick = { action(ActionAnswer) }) {
                                Text(stringResource(R.string.calling_answer))
                            }
                        }
                        EndCallButton(R.string.calling_reject) {
                            onCommand(CallCommand.Reject(call.id))
                        }
                        FilledTonalButton(onClick = { action(ActionSilence) }) {
                            Text(stringResource(R.string.calling_silence))
                        }
                    } else {
                        EndCallButton(R.string.calling_hang_up) { action(ActionHangUp) }
                    }
                    if (active) {
                        FilledTonalButton(onClick = { action(ActionHold) }) {
                            Text(stringResource(R.string.calling_hold))
                        }
                        FilledTonalButton(onClick = { keypad = !keypad }) {
                            Text(stringResource(R.string.calling_keypad))
                        }
                    }
                    if (state == Call.STATE_HOLDING) {
                        FilledTonalButton(onClick = { action(ActionUnhold) }) {
                            Text(stringResource(R.string.calling_unhold))
                        }
                    }
                    if (active && muted != null) {
                        FilledTonalButton(
                            onClick = { action(if (muted) ActionUnmute else ActionMute) }
                        ) {
                            Text(
                                stringResource(
                                    if (muted) R.string.calling_unmute else R.string.calling_mute
                                )
                            )
                        }
                    }
                }
            }
            if (keypad && active) Keypad { onCommand(CallCommand.Dtmf(call.id, it)) }
        }
    }
}

@Composable
private fun EndCallButton(label: Int, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
    ) {
        Text(stringResource(label))
    }
}

@Composable
private fun Keypad(onKey: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("123", "456", "789", "*0#").forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                row.forEach { key ->
                    FilledTonalButton(
                        onClick = { onKey(key.toString()) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(key.toString())
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioGroup(state: CompanionCallingState, onCommand: (CallCommand) -> Unit) {
    RevealValue(state.takeIf { it.calls.isNotEmpty() || it.audioEndpoints.isNotEmpty() }) { audio ->
        ListGroup(Modifier.selectableGroup(), title = stringResource(R.string.calling_audio)) {
            audio.audioEndpoints.forEach { endpoint ->
                RadioRow(
                    endpoint.name,
                    selected = endpoint.id == audio.currentEndpointId,
                    onClick = { onCommand(CallCommand.SelectAudioEndpoint(endpoint.id)) },
                )
            }
            NavigationRow(
                stringResource(R.string.calling_route_to_watch),
                onClick = { onCommand(CallCommand.RouteAudioToWatch) },
                icon = AppIcons.VolumeUp,
                trailingIcon = null,
            )
        }
    }
}

@Composable
private fun AccessGroup(access: CallingAccess, navigate: (Any) -> Unit) {
    val actions = LocalSystemActions.current
    val granted = stringResource(R.string.calling_access_granted)
    val missing = stringResource(R.string.calling_access_missing)
    ListGroup(title = stringResource(R.string.calling_access)) {
        FadeSwitch(access.telecomAvailable to access.canManage) { (telecom, canManage) ->
            when {
                !telecom ->
                    ValueRow(
                        stringResource(R.string.calling_access_manage),
                        stringResource(R.string.calling_access_no_telecom),
                    )
                canManage -> ValueRow(stringResource(R.string.calling_access_manage), granted)
                else ->
                    NavigationRow(
                        stringResource(R.string.calling_access_manage),
                        onClick = { navigate(PairingRoute) },
                        summary = stringResource(R.string.calling_access_manage_missing),
                    )
            }
        }
        listOf(
                Triple(
                    R.string.calling_access_phone_state,
                    access.readPhoneState,
                    CallAuthorizationAction.READ_PHONE_STATE,
                ),
                Triple(
                    R.string.calling_access_call_phone,
                    access.callPhone,
                    CallAuthorizationAction.CALL_PHONE,
                ),
            )
            .forEach { (title, allowed, action) ->
                FadeSwitch(allowed) { isAllowed ->
                    if (isAllowed) {
                        ValueRow(stringResource(title), granted)
                    } else {
                        NavigationRow(
                            stringResource(title),
                            onClick = { actions.requestCallingAccess(action) },
                            summary = missing,
                        )
                    }
                }
            }
        Reveal(!access.readPhoneState || !access.callPhone) {
            NavigationRow(
                stringResource(R.string.calling_access_settings),
                onClick = { actions.requestCallingAccess(CallAuthorizationAction.APP_SETTINGS) },
                trailingIcon = AppIcons.OpenExternal,
            )
        }
    }
}
