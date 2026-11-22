package dev.ticbox.companion.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.api.CloudCapability
import dev.ticbox.companion.api.CloudPrivacySettings
import dev.ticbox.companion.api.ConnectionPhase
import dev.ticbox.companion.api.GoogleAccountTransferPhase
import dev.ticbox.companion.api.GoogleServiceResult
import dev.ticbox.companion.api.GoogleServiceStatus
import dev.ticbox.companion.api.GoogleServicesState
import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.common.UiText
import dev.ticbox.companion.ui.common.uiText
import dev.ticbox.companion.ui.companionViewModel
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
import dev.ticbox.companion.ui.designsystem.Reveal
import dev.ticbox.companion.ui.designsystem.RevealValue
import dev.ticbox.companion.ui.designsystem.StatusBanner
import dev.ticbox.companion.ui.navigation.PrivacyRoute
import dev.ticbox.companion.ui.session.WatchSession
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

data class GoogleUiState(
    val privacy: CloudPrivacySettings,
    val google: GoogleServicesState,
    val linkReady: Boolean,
)

class GoogleServicesViewModel(dependencies: CompanionDependencies) :
    SessionViewModel(dependencies) {
    val state: StateFlow<GoogleUiState?> =
        sessionState(
            current = {
                GoogleUiState(
                    it.services.privacy.state.value,
                    it.services.google.state.value,
                    it.client.state.value.connection.phase == ConnectionPhase.LINK_READY,
                )
            },
            updates = { session ->
                combine(
                    session.services.privacy.state,
                    session.services.google.state,
                    session.client.state,
                ) { privacy, google, watch ->
                    GoogleUiState(
                        privacy,
                        google,
                        watch.connection.phase == ConnectionPhase.LINK_READY,
                    )
                }
            },
        )

    fun transferAccount() =
        watchCommand(TRANSFER) { session, peer, sessionId ->
            session.services.google.transferAccount(peer, sessionId)
        }

    fun abortTransfer() {
        session.value?.services?.google?.abortAccountTransfer()
    }

    fun setCloudSync(enabled: Boolean) =
        watchCommand(CLOUD_SYNC) { session, peer, sessionId ->
            session.services.google.setCloudSyncSetting(peer, sessionId, enabled)
        }

    private fun watchCommand(
        key: String,
        run: suspend (WatchSession, PeerAddress, Long) -> GoogleServiceResult,
    ) =
        command(key) { session ->
            val connection = session.client.state.value.connection
            val peer = connection.peer ?: return@command uiText(R.string.error_not_connected)
            run(session, peer, connection.sessionId).message()
        }

    companion object {
        const val TRANSFER = "google_transfer"
        const val CLOUD_SYNC = "google_cloud_sync"
    }
}

private fun GoogleServiceResult.message(): UiText = uiText(status.label)

private val GoogleServiceStatus.label: Int
    get() =
        when (this) {
            GoogleServiceStatus.COMPLETE -> R.string.google_status_complete
            GoogleServiceStatus.PARTIAL -> R.string.google_status_partial
            GoogleServiceStatus.SKIPPED -> R.string.google_status_skipped
            GoogleServiceStatus.UNAVAILABLE -> R.string.google_status_unavailable
            GoogleServiceStatus.PERMISSION_DENIED -> R.string.google_status_denied
            GoogleServiceStatus.CANCELLED -> R.string.google_status_cancelled
            GoogleServiceStatus.TIMED_OUT -> R.string.google_status_timed_out
            GoogleServiceStatus.FAILED -> R.string.google_status_failed
        }

private val GoogleAccountTransferPhase.status: Int?
    get() =
        when (this) {
            GoogleAccountTransferPhase.IDLE,
            GoogleAccountTransferPhase.CLOSED -> null
            GoogleAccountTransferPhase.OPENING_CHANNEL,
            GoogleAccountTransferPhase.WAITING_FOR_WATCH -> R.string.google_phase_waiting
            GoogleAccountTransferPhase.CONNECTING_GOOGLE -> R.string.google_phase_connecting
            GoogleAccountTransferPhase.TRANSFERRING -> R.string.google_phase_transferring
            GoogleAccountTransferPhase.AWAITING_USER -> R.string.google_phase_awaiting_user
            GoogleAccountTransferPhase.COMPLETE -> R.string.google_phase_complete
            GoogleAccountTransferPhase.FAILED -> R.string.google_phase_failed
            GoogleAccountTransferPhase.CANCELLED -> R.string.google_phase_cancelled
        }

private val GoogleAccountTransferPhase.inProgress: Boolean
    get() =
        this == GoogleAccountTransferPhase.OPENING_CHANNEL ||
            this == GoogleAccountTransferPhase.WAITING_FOR_WATCH ||
            this == GoogleAccountTransferPhase.CONNECTING_GOOGLE ||
            this == GoogleAccountTransferPhase.TRANSFERRING ||
            this == GoogleAccountTransferPhase.AWAITING_USER

@Composable
fun GoogleServicesScreen(onBack: () -> Unit, navigate: (Any) -> Unit) {
    val viewModel = companionViewModel { GoogleServicesViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    PageScaffold(stringResource(R.string.google_title), onBack = onBack) { padding ->
        val current = state
        if (current == null) {
            EmptyState(
                AppIcons.Login,
                stringResource(R.string.no_watch_title),
                stringResource(R.string.no_watch_body),
                Modifier.padding(padding),
            )
            return@PageScaffold
        }
        val transferAllowed = current.privacy.allows(CloudCapability.GOOGLE_ACCOUNT_TRANSFER)
        val syncAllowed = current.privacy.allows(CloudCapability.GOOGLE_CLOUD_SYNC)
        PageColumn(padding) {
            Reveal(!transferAllowed || !syncAllowed) {
                StatusBanner(
                    stringResource(R.string.google_disabled),
                    tone = BannerTone.Warning,
                    actionLabel = stringResource(R.string.account_open_privacy),
                    onAction = { navigate(PrivacyRoute) },
                )
            }
            AccountTransfer(
                current,
                enabled =
                    transferAllowed &&
                        current.linkReady &&
                        GoogleServicesViewModel.TRANSFER !in pending,
                onStart = viewModel::transferAccount,
                onAbort = viewModel::abortTransfer,
            )
            CloudSync(
                enabled =
                    syncAllowed &&
                        current.linkReady &&
                        GoogleServicesViewModel.CLOUD_SYNC !in pending,
                onSet = viewModel::setCloudSync,
            )
            FootnoteText(stringResource(R.string.google_note))
        }
    }
}

@Composable
private fun AccountTransfer(
    state: GoogleUiState,
    enabled: Boolean,
    onStart: () -> Unit,
    onAbort: () -> Unit,
) {
    val phase = state.google.accountTransfer
    ListGroup(title = stringResource(R.string.google_account)) {
        FadeSwitch(phase.inProgress) { running ->
            if (running) {
                NavigationRow(
                    stringResource(R.string.action_cancel),
                    onClick = onAbort,
                    icon = AppIcons.Close,
                    trailingIcon = null,
                )
            } else {
                NavigationRow(
                    stringResource(R.string.google_transfer),
                    onClick = onStart,
                    icon = AppIcons.Account,
                    summary = stringResource(R.string.google_transfer_summary),
                    enabled = enabled,
                    trailingIcon = null,
                )
            }
        }
    }
    BusyBar(phase.inProgress)
    RevealValue(phase.status) { status ->
        val result = state.google.lastAccountResult
        StatusBanner(
            result?.let { transferResult(it) } ?: stringResource(status),
            title = if (result != null) stringResource(status) else null,
            tone =
                if (phase == GoogleAccountTransferPhase.FAILED) BannerTone.Error
                else BannerTone.Info,
        )
    }
}

/** What actually happened, not what was asked for: a denied transfer must read as denied. */
@Composable
private fun transferResult(result: GoogleServiceResult): String =
    stringResource(
        R.string.google_transfer_result,
        stringResource(result.status.label),
        result.transferredAccounts,
        result.failedAccounts,
    )

/** Write-only: the watch reports no current value, so there is nothing to show as on or off. */
@Composable
private fun CloudSync(enabled: Boolean, onSet: (Boolean) -> Unit) {
    ListGroup(title = stringResource(R.string.google_cloud_sync)) {
        NavigationRow(
            stringResource(R.string.google_cloud_sync_on),
            onClick = { onSet(true) },
            enabled = enabled,
            trailingIcon = null,
        )
        NavigationRow(
            stringResource(R.string.google_cloud_sync_off),
            onClick = { onSet(false) },
            enabled = enabled,
            trailingIcon = null,
        )
    }
}
