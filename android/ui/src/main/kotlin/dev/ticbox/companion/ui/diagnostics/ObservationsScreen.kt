package dev.ticbox.companion.ui.diagnostics

import android.util.Log
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.api.WatchHistorySample
import dev.ticbox.companion.api.WatchSampleKind
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.common.currentLocale
import dev.ticbox.companion.ui.common.formatDateTime
import dev.ticbox.companion.ui.common.formatNumber
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.BannerTone
import dev.ticbox.companion.ui.designsystem.EmptyState
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.PageList
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.StatusBanner
import dev.ticbox.companion.ui.designsystem.ValueRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ObservationsState(
    val peer: PeerAddress? = null,
    val samples: List<WatchHistorySample> = emptyList(),
    val loading: Boolean = false,
    val hasMore: Boolean = false,
    val failed: Boolean = false,
)

/** Battery and step readings as received by this phone, newest first. */
class ObservationsViewModel(dependencies: CompanionDependencies) : SessionViewModel(dependencies) {
    private val mutable = MutableStateFlow(ObservationsState())
    val state: StateFlow<ObservationsState> = mutable.asStateFlow()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            session
                .map { it?.client?.state?.value?.connection?.peer }
                .distinctUntilChanged()
                .collect { peer ->
                    mutable.value = ObservationsState(peer = peer)
                    if (peer != null) load(reset = true)
                }
        }
    }

    fun reload() = load(reset = true)

    fun loadMore() = load(reset = false)

    private fun load(reset: Boolean) {
        val history = session.value?.services?.history ?: return
        val current = mutable.value
        val peer = current.peer ?: return
        if (current.loading && !reset) return
        loadJob?.cancel()
        mutable.update { it.copy(loading = true, failed = false) }
        loadJob = viewModelScope.launch {
            try {
                val before = if (reset) null else current.samples.lastOrNull()?.id
                val page = history.recent(peer, limit = PAGE, beforeId = before)
                mutable.update {
                    it.copy(
                        samples =
                            if (reset) page
                            else (it.samples + page).distinctBy { sample -> sample.id },
                        hasMore = page.size == PAGE,
                        loading = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: Exception) {
                Log.w(TAG, "Reading observations failed", problem)
                mutable.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    private companion object {
        const val TAG = "ObservationsViewModel"
        const val PAGE = 40
    }
}

@Composable
fun ObservationsScreen(onBack: () -> Unit) {
    val viewModel = companionViewModel { ObservationsViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locale = currentLocale()
    PageScaffold(stringResource(R.string.observations_title), onBack = onBack) { padding ->
        if (state.peer == null) {
            EmptyState(
                AppIcons.History,
                stringResource(R.string.no_watch_title),
                stringResource(R.string.no_watch_body),
                Modifier.padding(padding),
            )
            return@PageScaffold
        }
        PageList(padding, spacing = 0.dp) {
            item(key = "intro") {
                FootnoteText(
                    stringResource(R.string.observations_intro),
                    Modifier.padding(bottom = 12.dp),
                )
            }
            if (state.loading && state.samples.isEmpty())
                item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state.failed) {
                item(key = "error") {
                    StatusBanner(
                        stringResource(R.string.observations_failed),
                        tone = BannerTone.Error,
                        actionLabel = stringResource(R.string.action_try_again),
                        onAction =
                            if (state.samples.isEmpty()) viewModel::reload else viewModel::loadMore,
                    )
                }
            }
            if (!state.loading && !state.failed && state.samples.isEmpty()) {
                item(key = "empty") { FootnoteText(stringResource(R.string.observations_empty)) }
            }
            items(state.samples, key = { it.id }) { sample ->
                ValueRow(
                    when (sample.kind) {
                        WatchSampleKind.BATTERY ->
                            stringResource(
                                R.string.observations_battery,
                                sample.batteryPercent?.let { formatNumber(it, locale) } ?: "–",
                            )
                        WatchSampleKind.STEPS ->
                            sample.steps?.let {
                                pluralStringResource(
                                    R.plurals.observations_steps,
                                    it.currentSteps,
                                    formatNumber(it.currentSteps, locale),
                                )
                            } ?: stringResource(R.string.observations_steps_unknown)
                    },
                    stringResource(
                        R.string.observations_received,
                        formatDateTime(sample.receivedAtEpochMillis, locale),
                    ),
                    icon =
                        if (sample.kind == WatchSampleKind.BATTERY) AppIcons.Battery
                        else AppIcons.Steps,
                )
            }
            if (state.hasMore && !state.failed) {
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
