package dev.ticbox.companion.ui.connection

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.api.ConnectionPhase
import dev.ticbox.companion.api.ConnectionRecoveryState
import dev.ticbox.companion.api.DeviceCommandStatus
import dev.ticbox.companion.api.FailureKind
import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.api.WatchSettingsScreen
import dev.ticbox.companion.api.WatchSnapshot
import dev.ticbox.companion.api.WatchUnpairOutcome
import dev.ticbox.companion.api.WatchUnpairPhase
import dev.ticbox.companion.api.WatchUnpairResult
import dev.ticbox.companion.api.WatchUnpairState
import dev.ticbox.companion.api.WatchUnpairSupport
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.common.uiText
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.BannerTone
import dev.ticbox.companion.ui.designsystem.BusyBar
import dev.ticbox.companion.ui.designsystem.EmptyState
import dev.ticbox.companion.ui.designsystem.PageColumn
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.Reveal
import dev.ticbox.companion.ui.designsystem.RevealValue
import dev.ticbox.companion.ui.designsystem.StatusBanner
import dev.ticbox.companion.ui.device.message
import dev.ticbox.companion.ui.session.WatchSession
import dev.ticbox.companion.ui.toolbox.StepBody
import dev.ticbox.companion.ui.toolbox.StepCard
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

data class PhoneTransferUiState(
    val unpair: WatchUnpairState,
    val linkReady: Boolean,
    /** The watch this phone is set up for, whether or not it can be reached now. */
    val peer: PeerAddress?,
    /** The watch said it moved on, which it only does once the new phone has finished. */
    val associationEnded: Boolean,
) {
    /** The last unpair result, unless it belongs to an earlier session or a different watch. */
    val result: WatchUnpairResult?
        get() =
            unpair.lastResult?.takeIf {
                val sameSession =
                    unpair.peer == null ||
                        (it.peer == unpair.peer && it.sessionId == unpair.sessionId)
                sameSession && (peer == null || it.peer == null || it.peer == peer)
            }

    val cleared: Boolean
        get() =
            result?.let {
                it.outcome == WatchUnpairOutcome.LOCAL_ONLY && it.localCleanupFailure == null
            } == true
}

private fun phoneTransferState(
    unpair: WatchUnpairState,
    watch: WatchSnapshot,
    recovery: ConnectionRecoveryState,
) =
    PhoneTransferUiState(
        unpair = unpair,
        linkReady = watch.connection.phase == ConnectionPhase.LINK_READY,
        peer = watch.connection.peer ?: recovery.desiredPeer,
        associationEnded = watch.connection.failure?.kind == FailureKind.ASSOCIATION_TERMINATED,
    )

private fun WatchSession.phoneTransferState() =
    phoneTransferState(
        services.unpair.state.value,
        client.state.value,
        services.recovery.state.value,
    )

class PhoneTransferViewModel(dependencies: CompanionDependencies) : SessionViewModel(dependencies) {
    val state: StateFlow<PhoneTransferUiState?> =
        sessionState(
            current = { it.phoneTransferState() },
            updates = {
                combine(
                    it.services.unpair.state,
                    it.client.state,
                    it.services.recovery.state,
                    ::phoneTransferState,
                )
            },
        )

    fun probe() =
        command(PROBE) { session ->
            val checked = session.services.unpair.probe()
            // The page shows the result, unless the link changed during the check and replaced it.
            val shown = session.services.unpair.state.value.checkNotice?.text
            checked.supportNotice.text.takeIf { it != shown }?.let { uiText(it) }
        }

    fun transfer() =
        command(TRANSFER) { session ->
            val result = session.services.deviceSettings.openSettings(WatchSettingsScreen.TRANSFER)
            if (result.status == DeviceCommandStatus.REMOTE_UI_OPENED)
                uiText(R.string.watch_transfer_opened)
            else result.message(opened = true)
        }

    /** Never asks the watch to unpair: by now it belongs to the new phone. */
    fun clearPairing() =
        command(CLEAR) { session ->
            session.phoneTransferState().peer?.let { session.services.unpair.clearLocalPairing(it) }
            null
        }

    companion object {
        const val PROBE = "watch_unpair_probe"
        const val TRANSFER = "watch_transfer"
        const val CLEAR = "watch_clear_pairing"
    }
}

@Composable
fun PhoneTransferScreen(onBack: () -> Unit) {
    val viewModel = companionViewModel { PhoneTransferViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    var confirm by rememberSaveable { mutableStateOf(false) }
    val current = state
    val busy = current?.unpair?.busy == true || pending.isNotEmpty()
    val canClear = current != null && !busy && !current.linkReady && current.peer != null
    PageScaffold(stringResource(R.string.phone_transfer_title), onBack = onBack) { padding ->
        if (current == null) {
            EmptyState(
                AppIcons.BluetoothOff,
                stringResource(R.string.no_watch_title),
                stringResource(R.string.no_watch_body),
                Modifier.padding(padding),
            )
            return@PageScaffold
        }
        val finished = current.associationEnded || current.cleared
        val attempted = current.result?.localCleanupAttempted == true
        PageColumn(padding) {
            Text(stringResource(R.string.phone_transfer_intro))
            Reveal(!finished && !attempted) {
                StatusBanner(
                    stringResource(R.string.phone_transfer_warning),
                    tone = BannerTone.Warning,
                )
            }
            Reveal(!current.linkReady && !finished && !attempted) {
                StatusBanner(stringResource(R.string.phone_transfer_disconnected))
            }
            StepCard(
                1,
                stringResource(R.string.phone_transfer_step_check),
                done = finished || current.unpair.support == WatchUnpairSupport.SUPPORTED,
            ) {
                StepBody(stringResource(R.string.phone_transfer_step_check_body))
                FilledTonalButton(
                    onClick = viewModel::probe,
                    enabled =
                        current.linkReady &&
                            !busy &&
                            current.unpair.support != WatchUnpairSupport.UNAVAILABLE,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.unpair_check_support))
                }
                BusyBar(PhoneTransferViewModel.PROBE in pending)
                RevealValue(current.unpair.checkNotice) {
                    StatusBanner(stringResource(it.text), tone = it.tone)
                }
            }
            StepCard(2, stringResource(R.string.phone_transfer_step_transfer), done = finished) {
                StepBody(stringResource(R.string.phone_transfer_step_transfer_body))
                FilledTonalButton(
                    onClick = viewModel::transfer,
                    enabled = current.linkReady && !busy && current.unpair.mayOpenTransfer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.watch_transfer_action))
                }
                BusyBar(PhoneTransferViewModel.TRANSFER in pending)
            }
            StepCard(
                3,
                stringResource(R.string.phone_transfer_step_clear),
                done = current.cleared,
            ) {
                StepBody(stringResource(R.string.phone_transfer_step_clear_body))
                OutlinedButton(
                    onClick = { confirm = true },
                    enabled = canClear,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.phone_transfer_clear_action))
                }
                BusyBar(
                    PhoneTransferViewModel.CLEAR in pending ||
                        current.unpair.phase == WatchUnpairPhase.CLEANING_UP
                )
                RevealValue(current.clearNotice) {
                    StatusBanner(stringResource(it.text), tone = it.tone)
                }
            }
        }
    }
    if (confirm && current != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.phone_transfer_clear_action)) },
            text = {
                Text(
                    stringResource(
                        if (current.associationEnded) R.string.phone_transfer_clear_confirm_ended
                        else R.string.phone_transfer_clear_confirm_offline
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        viewModel.clearPairing()
                    },
                    enabled = canClear,
                ) {
                    Text(stringResource(R.string.phone_transfer_clear_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

private class UnpairNotice(@StringRes val text: Int, val tone: BannerTone)

/** What the support check found, in step 1. */
private val WatchUnpairState.checkNotice: UnpairNotice?
    get() =
        when {
            support == WatchUnpairSupport.UNSUPPORTED || support == WatchUnpairSupport.SUPPORTED ->
                supportNotice
            phase == WatchUnpairPhase.IDLE && reason != "fresh_home_info_required" -> supportNotice
            else -> null
        }

/** Step 2 waits for step 1's answer, unless this client can't check at all. */
private val WatchUnpairState.mayOpenTransfer: Boolean
    get() =
        !phoneTransferUnsupported &&
            (checkNotice != null || support == WatchUnpairSupport.UNAVAILABLE)

/** Green only for the verified version actually observed; every other outcome means it wasn't. */
private val WatchUnpairState.supportNotice: UnpairNotice
    get() =
        when {
            phoneTransferUnsupported ->
                UnpairNotice(R.string.phone_transfer_unsupported, BannerTone.Warning)
            support == WatchUnpairSupport.SUPPORTED ->
                UnpairNotice(R.string.unpair_support_observed, BannerTone.Success)
            support == WatchUnpairSupport.UNSUPPORTED ->
                UnpairNotice(R.string.unpair_unsupported, BannerTone.Warning)
            support == WatchUnpairSupport.UNAVAILABLE ->
                UnpairNotice(R.string.unpair_check_interrupted, BannerTone.Error)
            reason == "fresh_home_info_timeout" ->
                UnpairNotice(R.string.unpair_check_timed_out, BannerTone.Error)
            else -> UnpairNotice(R.string.unpair_check_version_unavailable, BannerTone.Error)
        }

/** Where clearing this phone's side stands, in step 3. */
private val PhoneTransferUiState.clearNotice: UnpairNotice?
    get() {
        val result = result
        return when {
            result == null ->
                when {
                    associationEnded ->
                        UnpairNotice(R.string.phone_transfer_association_ended, BannerTone.Success)
                    linkReady -> UnpairNotice(R.string.phone_transfer_clear_linked, BannerTone.Info)
                    else -> null
                }
            result.localCleanupFailure != null ->
                UnpairNotice(R.string.unpair_local_cleanup_failed, BannerTone.Error)
            result.outcome == WatchUnpairOutcome.LOCAL_ONLY ->
                UnpairNotice(R.string.phone_transfer_cleared, BannerTone.Success)
            result.outcome == WatchUnpairOutcome.NOT_SENT ->
                UnpairNotice(R.string.phone_transfer_clear_refused, BannerTone.Warning)
            else -> null
        }
    }
