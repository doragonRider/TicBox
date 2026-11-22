package dev.ticbox.companion.ui.connection

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.api.ConnectionPhase
import dev.ticbox.companion.api.ConnectionRecoveryState
import dev.ticbox.companion.api.PairingPhase
import dev.ticbox.companion.api.WatchSnapshot
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.common.currentLocale
import dev.ticbox.companion.ui.common.formatDateTime
import dev.ticbox.companion.ui.common.formatNumber
import dev.ticbox.companion.ui.common.uiText
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.ActionIcon
import dev.ticbox.companion.ui.designsystem.AppIcon
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.BannerTone
import dev.ticbox.companion.ui.designsystem.EmptyState
import dev.ticbox.companion.ui.designsystem.FadeSwitch
import dev.ticbox.companion.ui.designsystem.FadingText
import dev.ticbox.companion.ui.designsystem.GrowingGaps
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.NavigationRow
import dev.ticbox.companion.ui.designsystem.PageColumn
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.RevealValue
import dev.ticbox.companion.ui.designsystem.StatusBanner
import dev.ticbox.companion.ui.designsystem.ValueRow
import dev.ticbox.companion.ui.navigation.DiagnosticsRoute
import dev.ticbox.companion.ui.navigation.PairingRoute
import dev.ticbox.companion.ui.navigation.PhoneTransferRoute
import dev.ticbox.companion.ui.navigation.WatchModuleRoute
import dev.ticbox.companion.ui.pairing.message
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

data class ConnectionUiState(
    val watch: WatchSnapshot,
    val recovery: ConnectionRecoveryState,
    val unpairBusy: Boolean = false,
)

class ConnectionViewModel(dependencies: CompanionDependencies) : SessionViewModel(dependencies) {
    val state: StateFlow<ConnectionUiState?> =
        sessionState(
            current = {
                ConnectionUiState(
                    it.client.state.value,
                    it.services.recovery.state.value,
                    it.services.unpair.state.value.busy,
                )
            },
            updates = {
                combine(it.client.state, it.services.recovery.state, it.services.unpair.state) {
                    watch,
                    recovery,
                    unpair ->
                    ConnectionUiState(watch, recovery, unpair.busy)
                }
            },
        )

    fun reconnect() =
        command(Action.RECONNECT) { session ->
            session.services.recovery.resume()
            null
        }

    fun disconnect() =
        command(Action.DISCONNECT) { session ->
            session.client.disconnect()
            uiText(R.string.connection_disconnected)
        }

    fun refreshBattery() =
        command(Action.BATTERY) { session ->
            session.client.refreshBattery()
            null
        }

    /** Asks Android to associate the saved watch with this app. Its Bluetooth pairing stays. */
    fun associate() =
        command(Action.ASSOCIATE) { session ->
            val peer = session.services.recovery.state.value.desiredPeer ?: return@command null
            val pairing = session.services.pairing
            val operation = pairing.associate(peer)
            val ended =
                pairing.state.first { it.operationId != operation || it.phase in PAIRING_ENDED }
            when {
                ended.operationId != operation || ended.phase == PairingPhase.CANCELLED -> null
                ended.phase == PairingPhase.COMPLETED -> uiText(R.string.connection_associated)
                else -> uiText(ended.failure?.kind?.message ?: R.string.pairing_failure_unexpected)
            }
        }

    enum class Action {
        RECONNECT,
        DISCONNECT,
        BATTERY,
        ASSOCIATE,
    }

    private companion object {
        val PAIRING_ENDED =
            setOf(PairingPhase.COMPLETED, PairingPhase.FAILED, PairingPhase.CANCELLED)
    }
}

@Composable
fun ConnectionScreen(onBack: () -> Unit, navigate: (Any) -> Unit) {
    val viewModel = companionViewModel { ConnectionViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }
    PageScaffold(stringResource(R.string.connection_title), onBack = onBack) { padding ->
        val current = state
        if (current == null) {
            EmptyState(
                AppIcons.BluetoothOff,
                stringResource(R.string.no_watch_title),
                stringResource(R.string.no_watch_body),
                Modifier.padding(padding),
            )
            return@PageScaffold
        }
        val connection = current.watch.connection
        val linkReady = connection.phase == ConnectionPhase.LINK_READY
        val hasWatch = connection.peer != null || current.recovery.desiredPeer != null
        PageColumn(padding) {
            StatusCard(current.watch)
            RevealValue(current.recovery.attention) {
                // A missing association is the one thing fixed here without pairing again.
                val associate = it == R.string.recovery_association_missing
                StatusBanner(
                    stringResource(it),
                    tone = BannerTone.Warning,
                    actionLabel =
                        if (associate) stringResource(R.string.connection_associate) else null,
                    onAction = if (associate) viewModel::associate else null,
                )
            }
            FadeSwitch(linkReady) { ready ->
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (!ready) {
                        Button(
                            onClick = viewModel::reconnect,
                            enabled =
                                hasWatch &&
                                    !current.unpairBusy &&
                                    ConnectionViewModel.Action.RECONNECT !in pending,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        ) {
                            Text(stringResource(R.string.connection_reconnect))
                        }
                    }
                    OutlinedButton(
                        onClick = { confirmDisconnect = true },
                        enabled =
                            hasWatch &&
                                !current.unpairBusy &&
                                ConnectionViewModel.Action.DISCONNECT !in pending,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) {
                        Text(stringResource(R.string.connection_disconnect))
                    }
                }
            }
            ListGroup(title = stringResource(R.string.connection_battery)) {
                val battery = current.watch.battery
                val locale = currentLocale()
                ListItem(
                    headlineContent = {
                        Text(
                            battery?.let {
                                stringResource(
                                    R.string.value_percent,
                                    formatNumber(it.value, locale),
                                )
                            } ?: "–"
                        )
                    },
                    supportingContent = {
                        Text(
                            battery?.let {
                                stringResource(
                                    if (it.stale) R.string.connection_battery_previous
                                    else R.string.connection_battery_updated,
                                    formatDateTime(it.receivedAtEpochMillis, locale),
                                )
                            } ?: stringResource(R.string.connection_battery_unknown)
                        )
                    },
                    leadingContent = { AppIcon(AppIcons.Battery, contentDescription = null) },
                    trailingContent = {
                        ActionIcon(
                            AppIcons.Refresh,
                            stringResource(R.string.connection_battery_refresh),
                            viewModel::refreshBattery,
                            enabled = linkReady && ConnectionViewModel.Action.BATTERY !in pending,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            ListGroup(title = stringResource(R.string.connection_background)) {
                ValueRow(
                    stringResource(current.recovery.summary),
                    stringResource(
                        if (current.recovery.durable) R.string.connection_choice_saved
                        else R.string.connection_choice_not_saved
                    ),
                    icon = AppIcons.Sync,
                )
            }
            ListGroup {
                NavigationRow(
                    stringResource(R.string.connection_pair_other),
                    onClick = { navigate(PairingRoute) },
                    enabled = !current.unpairBusy,
                    icon = AppIcons.Add,
                )
                NavigationRow(
                    stringResource(R.string.phone_transfer_title),
                    onClick = { navigate(PhoneTransferRoute) },
                    icon = AppIcons.Phone,
                )
                NavigationRow(
                    stringResource(R.string.module_title),
                    onClick = { navigate(WatchModuleRoute) },
                    icon = AppIcons.Extension,
                    summary = stringResource(R.string.module_entry_summary),
                    modifier = Modifier.testTag("connection_watch_module"),
                )
                NavigationRow(
                    stringResource(R.string.diagnostics_title),
                    onClick = { navigate(DiagnosticsRoute) },
                    icon = AppIcons.Diagnostics,
                )
            }
        }
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(stringResource(R.string.connection_disconnect_title)) },
            text = { Text(stringResource(R.string.connection_disconnect_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDisconnect = false
                        viewModel.disconnect()
                    }
                ) {
                    Text(stringResource(R.string.connection_disconnect))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun StatusCard(watch: WatchSnapshot) {
    val connection = watch.connection
    val linkReady = connection.phase == ConnectionPhase.LINK_READY
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor =
                    animateColorAsState(
                            if (linkReady) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer,
                            label = "connection_card",
                        )
                        .value
            )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Crossfade(linkReady, label = "connection_icon") { ready ->
                AppIcon(
                    if (ready) AppIcons.Bluetooth else AppIcons.BluetoothOff,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
            }
            Column(Modifier.animateContentSize(), verticalArrangement = GrowingGaps(4.dp)) {
                FadingText(
                    stringResource(connection.phase.label),
                    style = MaterialTheme.typography.titleLarge,
                )
                RevealValue(connection.peerName) {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                RevealValue(connection.failure) {
                    Text(
                        stringResource(it.kind.message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
