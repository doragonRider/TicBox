package dev.ticbox.companion.ui.diagnostics

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.ticbox.companion.api.HealthPointQuery
import dev.ticbox.companion.api.HealthPointRecord
import dev.ticbox.companion.api.HealthRawRecord
import dev.ticbox.companion.api.HealthRecordKind
import dev.ticbox.companion.api.HealthRecordPage
import dev.ticbox.companion.api.HealthRecordQuery
import dev.ticbox.companion.api.HealthRecordReadStatus
import dev.ticbox.companion.api.HealthRecordSource
import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.common.currentLocale
import dev.ticbox.companion.ui.common.formatDateTime
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.BannerTone
import dev.ticbox.companion.ui.designsystem.EmptyState
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.PageList
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.RadioRow
import dev.ticbox.companion.ui.designsystem.StatusBanner
import dev.ticbox.companion.ui.session.WatchSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StoredRecord(val raw: HealthRawRecord, val point: HealthPointRecord?)

data class StoredRecordsState(
    val sources: List<HealthRecordSource> = emptyList(),
    val selected: HealthRecordSource? = null,
    val kind: HealthRecordKind = HealthRecordKind.POINT,
    val records: List<StoredRecord> = emptyList(),
    val nextOffset: Int? = null,
    val unavailable: Boolean = false,
    val loading: Boolean = true,
    val failed: Boolean = false,
)

/** Browses the raw health rows saved on this phone, for troubleshooting. */
@OptIn(ExperimentalCoroutinesApi::class)
class StoredRecordsViewModel(dependencies: CompanionDependencies) : SessionViewModel(dependencies) {
    private val mutable = MutableStateFlow(StoredRecordsState())
    val state: StateFlow<StoredRecordsState> = mutable.asStateFlow()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            session
                .flatMapLatest { current ->
                    if (current == null) flowOf(null)
                    else
                        combine(current.client.state, current.services.localHealth.state) {
                            watch,
                            sync ->
                            Triple(current, watch.connection.peer, sync.lastSuccessAtMillis)
                        }
                }
                .distinctUntilChanged()
                .collect { key -> key?.let { (current, peer, _) -> loadSources(current, peer) } }
        }
    }

    fun select(source: HealthRecordSource) {
        mutable.update { it.copy(selected = source) }
        loadRecords(reset = true)
    }

    fun selectKind(kind: HealthRecordKind) {
        mutable.update { it.copy(kind = kind) }
        loadRecords(reset = true)
    }

    fun loadMore() = loadRecords(reset = false)

    fun retry() = loadRecords(reset = state.value.records.isEmpty())

    private suspend fun loadSources(session: WatchSession, peer: PeerAddress?) {
        mutable.update { it.copy(loading = true, failed = false) }
        try {
            val sources = buildList {
                peer?.let { add(HealthRecordSource.LocalDevice(session.localProfileId, it)) }
                addAll(session.services.healthRecords.sources())
            }
                .distinct()
            mutable.update {
                it.copy(
                    sources = sources,
                    selected = it.selected?.takeIf { s -> s in sources } ?: sources.firstOrNull(),
                )
            }
            loadRecords(reset = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Exception) {
            Log.w(TAG, "Reading record sources failed", problem)
            mutable.update { it.copy(loading = false, failed = true) }
        }
    }

    private fun loadRecords(reset: Boolean) {
        val records = session.value?.services?.healthRecords ?: return
        val current = mutable.value
        val source = current.selected ?: return mutable.update { it.copy(loading = false) }
        val offset = if (reset) 0 else current.nextOffset ?: return
        loadJob?.cancel()
        mutable.update {
            it.copy(
                loading = true,
                failed = false,
                records = if (reset) emptyList() else it.records,
            )
        }
        loadJob = viewModelScope.launch {
            try {
                val page =
                    if (
                        current.kind == HealthRecordKind.POINT &&
                            source is HealthRecordSource.LocalDevice
                    ) {
                        val typed =
                            records.points(HealthPointQuery(source, offset = offset, limit = PAGE))
                        Page(
                            typed.items.map { StoredRecord(it.raw, it) },
                            typed.nextOffset,
                            typed.status,
                        )
                    } else {
                        records
                            .records(
                                current.kind,
                                HealthRecordQuery(source, offset = offset, limit = PAGE),
                            )
                            .toPage()
                    }
                mutable.update {
                    it.copy(
                        records =
                            (if (reset) page.items else it.records + page.items).distinctBy { record
                                ->
                                record.raw.key
                            },
                        nextOffset = page.next,
                        unavailable = page.status == HealthRecordReadStatus.SOURCE_UNAVAILABLE,
                        loading = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: Exception) {
                Log.w(TAG, "Reading stored records failed", problem)
                mutable.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    private class Page(
        val items: List<StoredRecord>,
        val next: Int?,
        val status: HealthRecordReadStatus,
    )

    private fun HealthRecordPage.toPage() =
        Page(items.map { StoredRecord(it, null) }, nextOffset, status)

    private companion object {
        const val TAG = "StoredRecordsViewModel"
        const val PAGE = 40
    }
}

@Composable
fun StoredRecordsScreen(onBack: () -> Unit) {
    val viewModel = companionViewModel { StoredRecordsViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    PageScaffold(stringResource(R.string.records_title), onBack = onBack) { padding ->
        if (session == null) {
            EmptyState(
                AppIcons.Database,
                stringResource(R.string.no_watch_title),
                stringResource(R.string.no_watch_body),
                Modifier.padding(padding),
            )
            return@PageScaffold
        }
        val locale = currentLocale()
        PageList(padding, spacing = 0.dp) {
            item(key = "intro") {
                FootnoteText(
                    stringResource(R.string.records_intro),
                    Modifier.padding(bottom = 16.dp),
                )
            }
            if (state.sources.isNotEmpty()) {
                item(key = "sources") {
                    ListGroup(
                        Modifier.selectableGroup().padding(bottom = 16.dp),
                        title = stringResource(R.string.records_source),
                    ) {
                        state.sources.forEachIndexed { index, source ->
                            RadioRow(
                                when (source) {
                                    is HealthRecordSource.LocalDevice ->
                                        stringResource(
                                            R.string.records_source_watch,
                                            source.peer.value,
                                        )
                                    is HealthRecordSource.MobvoiCloud ->
                                        stringResource(R.string.records_source_cloud, index + 1)
                                },
                                selected = source == state.selected,
                                onClick = { viewModel.select(source) },
                                enabled = !state.loading,
                            )
                        }
                    }
                }
            }
            item(key = "kinds") {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                            HealthRecordKind.POINT to R.string.records_kind_points,
                            HealthRecordKind.SESSION to R.string.records_kind_sessions,
                            HealthRecordKind.SPORT to R.string.records_kind_workouts,
                        )
                        .forEach { (kind, label) ->
                            FilterChip(
                                selected = state.kind == kind,
                                onClick = { viewModel.selectKind(kind) },
                                label = { Text(stringResource(label)) },
                                enabled = !state.loading,
                            )
                        }
                }
            }
            if (state.loading && state.records.isEmpty())
                item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state.failed) {
                item(key = "error") {
                    StatusBanner(
                        stringResource(R.string.records_failed),
                        tone = BannerTone.Error,
                        actionLabel = stringResource(R.string.action_try_again),
                        onAction = viewModel::retry,
                    )
                }
            }
            when {
                state.unavailable ->
                    item(key = "unavailable") {
                        FootnoteText(stringResource(R.string.records_unavailable))
                    }
                !state.loading && !state.failed && state.records.isEmpty() ->
                    item(key = "empty") { FootnoteText(stringResource(R.string.records_empty)) }
            }
            items(state.records, key = { it.raw.key }) { record ->
                val raw = record.raw
                val values =
                    record.point?.values?.joinToString(" · ") { value ->
                        listOfNotNull(value.decimal ?: value.rawToken.ifEmpty { "∅" }, value.unit)
                            .joinToString(" ")
                    }
                ListItem(
                    headlineContent = {
                        Text(
                            record.point?.type?.wireName
                                ?: raw.dataType
                                ?: stringResource(R.string.records_unknown_type)
                        )
                    },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                    raw.startMillis?.let { formatDateTime(it, locale) }
                                        ?: stringResource(R.string.records_unknown_time),
                                    values,
                                    record.point
                                        ?.issues
                                        ?.takeIf { it.isNotEmpty() }
                                        ?.let { stringResource(R.string.records_has_issues) },
                                )
                                .joinToString("\n"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            if (state.nextOffset != null && !state.failed) {
                item(key = "more") {
                    TextButton(
                        onClick = viewModel::loadMore,
                        enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.action_load_more))
                    }
                }
            }
        }
    }
}
