package dev.ticbox.companion.ui.diagnostics

import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.api.CompanionCallingState
import dev.ticbox.companion.api.ConnectionPhase
import dev.ticbox.companion.api.ConnectionRecoveryState
import dev.ticbox.companion.api.DataSyncState
import dev.ticbox.companion.api.LocalHealthSyncMode
import dev.ticbox.companion.api.LocalHealthSyncPhase
import dev.ticbox.companion.api.LocalHealthSyncState
import dev.ticbox.companion.api.LocalHealthSyncTiming
import dev.ticbox.companion.api.NetworkProxyState
import dev.ticbox.companion.api.WatchDeviceInfo
import dev.ticbox.companion.api.WatchSnapshot
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.common.currentLocale
import dev.ticbox.companion.ui.common.formatDateTime
import dev.ticbox.companion.ui.common.formatNumber
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.EmptyState
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.InlineValueRow
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.NavigationRow
import dev.ticbox.companion.ui.designsystem.PageColumn
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.ValueRow
import dev.ticbox.companion.ui.navigation.DiagnosticsDetailRoute
import dev.ticbox.companion.ui.navigation.HealthJournalRoute
import dev.ticbox.companion.ui.navigation.ObservationsRoute
import dev.ticbox.companion.ui.navigation.StoredRecordsRoute
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

data class DiagnosticsUiState(
    val watch: WatchSnapshot,
    val recovery: ConnectionRecoveryState,
    val healthSync: LocalHealthSyncState,
    val data: DataSyncState,
    val network: NetworkProxyState,
    val calling: CompanionCallingState,
    val device: WatchDeviceInfo?,
)

class DiagnosticsViewModel(dependencies: CompanionDependencies) : SessionViewModel(dependencies) {
    val state: StateFlow<DiagnosticsUiState?> =
        sessionState(
            current = {
                DiagnosticsUiState(
                    it.client.state.value,
                    it.services.recovery.state.value,
                    it.services.localHealth.state.value,
                    it.services.data.state.value,
                    it.services.network.value,
                    it.services.calling.state.value,
                    it.services.deviceSettings.state.value.info,
                )
            },
            updates = { session ->
                val services = session.services
                combine(
                    combine(
                        session.client.state,
                        services.recovery.state,
                        services.localHealth.state,
                        ::Triple,
                    ),
                    combine(
                        services.data.state,
                        services.network,
                        services.calling.state,
                        services.deviceSettings.state,
                    ) { data, network, calling, device ->
                        DiagnosticsUiState(
                            session.client.state.value,
                            services.recovery.state.value,
                            services.localHealth.state.value,
                            data,
                            network,
                            calling,
                            device.info,
                        )
                    },
                ) { (watch, recovery, health), rest ->
                    rest.copy(watch = watch, recovery = recovery, healthSync = health)
                }
            },
        )

    /** Asks the data layer to catch up. It reports no end of history, so only the counters tell. */
    fun requestDataSync() =
        command(DATA_SYNC) {
            it.services.data.requestSync()
            null
        }

    fun fullHealthSync() =
        command(FULL_HEALTH_SYNC) {
            it.services.localHealth.sync(LocalHealthSyncMode.FULL)
            null
        }

    companion object {
        const val DATA_SYNC = "diagnostics_data_sync"
        const val FULL_HEALTH_SYNC = "diagnostics_full_health_sync"
    }
}

/** What opens a page of its own, so the overview fits on about one screen. */
@Keep
enum class DiagnosticsSection(@StringRes val title: Int) {
    CONNECTION(R.string.diagnostics_connection),
    SYNC_TIMING(R.string.diagnostics_sync_timing),
    DATA_SYNC(R.string.diagnostics_data_sync),
    NETWORK(R.string.diagnostics_network),
    CALLING(R.string.diagnostics_calling),
    DEVICE(R.string.diagnostics_device_fields),
}

/** Technical details for troubleshooting. Values are shown as the backend reports them. */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit, navigate: (Any) -> Unit) {
    val viewModel = companionViewModel { DiagnosticsViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    PageScaffold(stringResource(R.string.diagnostics_title), onBack = onBack) { padding ->
        val current = state
        if (current == null) {
            NoWatch(padding)
            return@PageScaffold
        }
        val unknown = stringResource(R.string.diagnostics_unknown)
        val connection = current.watch.connection
        @Composable
        fun SectionRow(section: DiagnosticsSection, summary: String) =
            NavigationRow(
                stringResource(section.title),
                onClick = { navigate(DiagnosticsDetailRoute(section)) },
                summary = summary,
            )
        PageColumn(padding) {
            ListGroup(title = stringResource(R.string.diagnostics_services)) {
                SectionRow(
                    DiagnosticsSection.CONNECTION,
                    listOfNotNull(connection.phase.name, connection.failure?.kind?.name)
                        .joinToString(" · "),
                )
                SectionRow(DiagnosticsSection.DATA_SYNC, current.data.phase.name)
                SectionRow(DiagnosticsSection.NETWORK, current.network.phase.name)
                SectionRow(DiagnosticsSection.CALLING, current.calling.phase.name)
                current.device
                    ?.takeIf { it.fields.isNotEmpty() }
                    ?.let { SectionRow(DiagnosticsSection.DEVICE, it.model ?: unknown) }
            }
            HealthSyncGroup(
                current,
                fullSyncRequested = DiagnosticsViewModel.FULL_HEALTH_SYNC in pending,
                onFullSync = viewModel::fullHealthSync,
                onTiming = { navigate(DiagnosticsDetailRoute(DiagnosticsSection.SYNC_TIMING)) },
                onJournal = { navigate(HealthJournalRoute) },
            )
            ListGroup(title = stringResource(R.string.diagnostics_stored_data)) {
                NavigationRow(
                    stringResource(R.string.observations_title),
                    onClick = { navigate(ObservationsRoute) },
                    icon = AppIcons.History,
                    summary = stringResource(R.string.diagnostics_observations_summary),
                )
                NavigationRow(
                    stringResource(R.string.records_title),
                    onClick = { navigate(StoredRecordsRoute) },
                    icon = AppIcons.Database,
                    summary = stringResource(R.string.diagnostics_records_summary),
                )
            }
        }
    }
}

@Composable
fun DiagnosticsDetailScreen(section: DiagnosticsSection, onBack: () -> Unit) {
    val viewModel = companionViewModel { DiagnosticsViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    PageScaffold(stringResource(section.title), onBack = onBack) { padding ->
        val current = state
        if (current == null) {
            NoWatch(padding)
            return@PageScaffold
        }
        PageColumn(padding) {
            when (section) {
                DiagnosticsSection.CONNECTION -> ConnectionGroups(current)
                DiagnosticsSection.SYNC_TIMING -> SyncTiming(current.healthSync.lastRun)
                DiagnosticsSection.DATA_SYNC ->
                    DataSyncGroup(
                        current.data,
                        syncing = DiagnosticsViewModel.DATA_SYNC in pending,
                        onRequestSync = viewModel::requestDataSync,
                    )
                DiagnosticsSection.NETWORK -> NetworkGroup(current.network)
                DiagnosticsSection.CALLING -> CallingGroup(current.calling)
                DiagnosticsSection.DEVICE -> current.device?.let { DeviceFields(it) }
            }
        }
    }
}

@Composable
private fun NoWatch(padding: PaddingValues) {
    EmptyState(
        AppIcons.Diagnostics,
        stringResource(R.string.no_watch_title),
        stringResource(R.string.no_watch_body),
        Modifier.padding(padding),
    )
}

@Composable
private fun ConnectionGroups(current: DiagnosticsUiState) {
    val connection = current.watch.connection
    val unknown = stringResource(R.string.diagnostics_unknown)
    ListGroup {
        ValueRow(stringResource(R.string.diagnostics_phase), connection.phase.name)
        ValueRow(stringResource(R.string.diagnostics_address), connection.peer?.value ?: unknown)
        ValueRow(stringResource(R.string.diagnostics_session), connection.sessionId.toString())
        ValueRow(
            stringResource(R.string.diagnostics_protocol),
            connection.negotiatedWireVersion?.toString() ?: unknown,
        )
        ValueRow(stringResource(R.string.diagnostics_retries), connection.retryAttempt.toString())
        ValueRow(
            stringResource(R.string.diagnostics_failed_messages),
            connection.failedBusinessMessages.toString(),
        )
        ValueRow(
            stringResource(R.string.diagnostics_dropped),
            connection.droppedMessageEvents.toString(),
        )
        connection.failure?.let {
            ValueRow(
                stringResource(R.string.diagnostics_last_failure),
                it.kind.name + ": " + it.message,
            )
        }
    }
    ListGroup(title = stringResource(R.string.diagnostics_recovery)) {
        ValueRow(stringResource(R.string.diagnostics_phase), current.recovery.phase.name)
        ValueRow(stringResource(R.string.diagnostics_saved), current.recovery.durable.toString())
        current.recovery.reason?.let { ValueRow(stringResource(R.string.diagnostics_reason), it) }
    }
}

@Composable
private fun HealthSyncGroup(
    current: DiagnosticsUiState,
    fullSyncRequested: Boolean,
    onFullSync: () -> Unit,
    onTiming: () -> Unit,
    onJournal: () -> Unit,
) {
    val sync = current.healthSync
    val locale = currentLocale()
    val unknown = stringResource(R.string.diagnostics_unknown)
    val run = sync.lastRun
    val fullSyncActive =
        sync.mode == LocalHealthSyncMode.FULL &&
            sync.phase in
                setOf(
                    LocalHealthSyncPhase.CONNECTING,
                    LocalHealthSyncPhase.READING,
                    LocalHealthSyncPhase.RETRYING,
                )
    ListGroup(title = stringResource(R.string.diagnostics_health_sync)) {
        ValueRow(stringResource(R.string.diagnostics_phase), sync.phase.name)
        ValueRow(
            stringResource(R.string.diagnostics_health_sync_mode),
            stringResource(
                if (sync.mode == LocalHealthSyncMode.FULL) R.string.diagnostics_health_mode_full
                else R.string.diagnostics_health_mode_incremental
            ),
        )
        ValueRow(
            stringResource(R.string.diagnostics_records_saved),
            sync.recordsCommitted.toString(),
        )
        ValueRow(
            stringResource(R.string.diagnostics_last_success),
            sync.lastSuccessAtMillis?.let { formatDateTime(it, locale) } ?: unknown,
        )
        sync.reason?.let { ValueRow(stringResource(R.string.diagnostics_reason), it) }
        NavigationRow(
            stringResource(R.string.diagnostics_sync_timing),
            onClick = onTiming,
            summary =
                run?.let { millisText(it.totalMillis, locale) }
                    ?: stringResource(R.string.diagnostics_sync_timing_empty),
            enabled = run != null,
        )
        NavigationRow(
            stringResource(R.string.journal_title),
            onClick = onJournal,
            summary = stringResource(R.string.journal_summary),
        )
        NavigationRow(
            stringResource(R.string.diagnostics_health_full_sync),
            onClick = onFullSync,
            icon = AppIcons.Refresh,
            summary = stringResource(R.string.diagnostics_health_full_sync_summary),
            enabled =
                current.watch.connection.phase == ConnectionPhase.LINK_READY &&
                    !fullSyncActive &&
                    !fullSyncRequested,
            trailingIcon = null,
        )
    }
}

@Composable
private fun DataSyncGroup(data: DataSyncState, syncing: Boolean, onRequestSync: () -> Unit) {
    ListGroup {
        ValueRow(stringResource(R.string.diagnostics_phase), data.phase.name)
        ValueRow(stringResource(R.string.diagnostics_data_received), data.receivedItems.toString())
        ValueRow(stringResource(R.string.diagnostics_data_sent), data.sentItems.toString())
        ValueRow(
            stringResource(R.string.diagnostics_data_duplicates),
            data.duplicateItems.toString(),
        )
        ValueRow(
            stringResource(R.string.diagnostics_data_pending_assets),
            (data.pendingAssetFetches + data.pendingAssetSends).toString(),
        )
        ValueRow(
            stringResource(R.string.diagnostics_data_unavailable_assets),
            data.unavailableAssets.toString(),
        )
        ValueRow(stringResource(R.string.diagnostics_dropped), data.droppedEnvelopes.toString())
        data.lastIssue?.let {
            ValueRow(stringResource(R.string.diagnostics_last_failure), it.name)
        }
        NavigationRow(
            stringResource(R.string.diagnostics_data_request),
            onClick = onRequestSync,
            icon = AppIcons.Refresh,
            enabled = !syncing,
            trailingIcon = null,
        )
    }
    FootnoteText(stringResource(R.string.diagnostics_data_note))
}

@Composable
private fun NetworkGroup(network: NetworkProxyState) {
    val unknown = stringResource(R.string.diagnostics_unknown)
    ListGroup {
        ValueRow(stringResource(R.string.diagnostics_phase), network.phase.name)
        ValueRow(
            stringResource(R.string.diagnostics_address),
            network.peer?.value ?: unknown,
        )
        ValueRow(
            stringResource(R.string.diagnostics_network_connections),
            stringResource(
                R.string.diagnostics_network_connections_value,
                network.tcpConnections,
                network.udpConnections,
            ),
        )
        ValueRow(
            stringResource(R.string.diagnostics_network_metered),
            network.isMetered?.toString() ?: unknown,
        )
        network.failure?.let {
            ValueRow(
                stringResource(R.string.diagnostics_last_failure),
                it.kind.name + ": " + it.message,
            )
        }
    }
    FootnoteText(stringResource(R.string.diagnostics_network_note))
}

@Composable
private fun CallingGroup(calling: CompanionCallingState) {
    ListGroup {
        ValueRow(stringResource(R.string.diagnostics_phase), calling.phase.name)
        ValueRow(
            stringResource(R.string.diagnostics_calling_published),
            calling.messagesPublished.toString(),
        )
        ValueRow(
            stringResource(R.string.diagnostics_calling_commands),
            calling.commandsDispatched.toString(),
        )
        ValueRow(
            stringResource(R.string.diagnostics_failed_messages),
            calling.commandsFailed.toString(),
        )
        calling.lastIssue?.let { ValueRow(stringResource(R.string.diagnostics_reason), it) }
    }
}

/** Whatever the watch reports about itself, under the names it uses. */
@Composable
private fun DeviceFields(info: WatchDeviceInfo) {
    ListGroup {
        info.fields.toSortedMap().forEach { (name, value) -> ValueRow(name, value) }
    }
}

/** Where the last health sync spent its time, to analyse slow syncs. */
@Composable
private fun SyncTiming(run: LocalHealthSyncTiming?) {
    val locale = currentLocale()
    if (run == null) {
        FootnoteText(stringResource(R.string.diagnostics_sync_timing_empty))
        return
    }
    ListGroup {
        ValueRow(
            stringResource(R.string.diagnostics_sync_started),
            formatDateTime(run.startedAtMillis, locale),
        )
        ValueRow(
            stringResource(R.string.diagnostics_sync_total),
            millisText(run.totalMillis, locale),
        )
    }
    run.stages
        .groupBy { it.table }
        .forEach { (table, stages) ->
            // Time inside open is already part of open, so counting it again would mislead.
            val spent = stages.filterNot { it.name in OpenStages }.sumOf { it.millis }
            val title = listOfNotNull(table, millisText(spent, locale)).joinToString(" · ")
            ListGroup(title = title) {
                stages.forEach { stage ->
                    val count = if (stage.count > 1) " ×${stage.count}" else ""
                    InlineValueRow(
                        stage.name,
                        millisText(stage.millis, locale) + count,
                        if (stage.name in OpenStages) Modifier.padding(start = 16.dp) else Modifier,
                    )
                }
            }
        }
    FootnoteText(stringResource(R.string.diagnostics_sync_timing_note))
}

private val OpenStages = setOf("wake", "wait", "connect", "handshake")

@Composable
private fun millisText(millis: Long, locale: Locale) =
    stringResource(R.string.diagnostics_millis, formatNumber(millis, locale))
