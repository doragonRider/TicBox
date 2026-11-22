package dev.ticbox.companion.ui.bridge

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.api.ConnectionPhase
import dev.ticbox.companion.api.LocalHealthJournalException
import dev.ticbox.companion.api.LocalHealthJournalSource
import dev.ticbox.companion.api.LocalHealthJournalStatus
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.common.UiText
import dev.ticbox.companion.ui.common.asString
import dev.ticbox.companion.ui.common.uiText
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.ActionIcon
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.BannerTone
import dev.ticbox.companion.ui.designsystem.BusyBar
import dev.ticbox.companion.ui.designsystem.FadeSwitch
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.NavigationRow
import dev.ticbox.companion.ui.designsystem.PageColumn
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.Reveal
import dev.ticbox.companion.ui.designsystem.StatusBanner
import dev.ticbox.companion.ui.designsystem.SwitchRow
import dev.ticbox.companion.ui.session.WatchSession
import dev.ticbox.companion.ui.toolbox.ConfirmDialog
import dev.ticbox.companion.ui.toolbox.ToolHero
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** What's known about one database's log. [problem] says why [status] couldn't be read. */
data class JournalSourceUi(
    val status: LocalHealthJournalStatus? = null,
    val problem: String? = null,
)

class HealthJournalViewModel(dependencies: CompanionDependencies) : SessionViewModel(dependencies) {
    val linkReady: StateFlow<Boolean?> =
        sessionState(
            current = { it.client.state.value.connection.phase == ConnectionPhase.LINK_READY },
            updates = { session ->
                session.client.state
                    .map { it.connection.phase == ConnectionPhase.LINK_READY }
                    .distinctUntilChanged()
            },
        )

    private val mutableSources =
        MutableStateFlow(emptyMap<LocalHealthJournalSource, JournalSourceUi>())
    val sources = mutableSources.asStateFlow()

    /** One at a time: the backend refuses journal operations that overlap. */
    fun readAll() =
        command(READ) { session ->
            LocalHealthJournalSource.entries.forEach { show(it, load(session, it)) }
            null
        }

    /**
     * Never repeats a write on its own. A write whose result is unknown may have gone through, so
     * all that happens next is finding out where it stands.
     */
    fun setEnabled(source: LocalHealthJournalSource, enabled: Boolean) =
        command(source) { session ->
            try {
                val status = session.services.localHealth.setJournalEnabled(source, enabled)
                show(source, JournalSourceUi(status))
                null
            } catch (problem: LocalHealthJournalException) {
                if (problem.code == UNCONFIRMED) show(source, load(session, source))
                journalMessage(problem.code)
            }
        }

    private suspend fun load(session: WatchSession, source: LocalHealthJournalSource) =
        try {
            JournalSourceUi(session.services.localHealth.journalStatus(source))
        } catch (problem: LocalHealthJournalException) {
            JournalSourceUi(problem = problem.code)
        }

    private fun show(source: LocalHealthJournalSource, shown: JournalSourceUi) {
        mutableSources.update { it + (source to shown) }
    }

    companion object {
        const val READ = "journal_read"
        const val UNCONFIRMED = "journal_operation_unconfirmed"
    }
}

private fun journalMessage(code: String): UiText =
    when (code) {
        "journal_sync_in_progress" -> uiText(R.string.journal_error_syncing)
        "journal_bridge_update_required" -> uiText(R.string.journal_error_bridge_update)
        "journal_not_connected" -> uiText(R.string.error_not_connected)
        "journal_connection_replaced" -> uiText(R.string.journal_error_connection)
        "journal_root_not_enabled" -> uiText(R.string.journal_error_root_switch)
        "journal_root_unavailable",
        "journal_permission_denied" -> uiText(R.string.toolbox_error_root)
        HealthJournalViewModel.UNCONFIRMED -> uiText(R.string.journal_error_unconfirmed)
        "journal_provider_unsupported",
        "journal_schema_unsupported",
        "journal_source_unsupported",
        "journal_provider_integrity_mismatch",
        "journal_provider_identity_mismatch" -> uiText(R.string.journal_error_unsupported)
        "journal_source_not_installed",
        "journal_database_missing" -> uiText(R.string.journal_error_source_missing)
        "journal_incomplete",
        "journal_namespace_unknown" -> uiText(R.string.journal_error_broken)
        else -> uiText(R.string.toolbox_error_code, code)
    }

/** Reading the state writes nothing, so an unknown result only means the state wasn't read. */
private fun statusMessage(code: String): UiText =
    if (code == HealthJournalViewModel.UNCONFIRMED) uiText(R.string.journal_error_status_unread)
    else journalMessage(code)

@get:StringRes
private val LocalHealthJournalSource.label: Int
    get() =
        when (this) {
            LocalHealthJournalSource.HEALTH -> R.string.journal_source_health
            LocalHealthJournalSource.FITNESS -> R.string.journal_source_fitness
        }

@Composable
fun HealthJournalScreen(onBack: () -> Unit) {
    val viewModel = companionViewModel { HealthJournalViewModel(it) }
    val linkReady by viewModel.linkReady.collectAsStateWithLifecycle()
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val ready = linkReady == true
    val reading = HealthJournalViewModel.READ in pending
    var confirming by remember { mutableStateOf<LocalHealthJournalSource?>(null) }
    LaunchedEffect(ready) { if (ready) viewModel.readAll() }
    PageScaffold(
        stringResource(R.string.journal_title),
        onBack = onBack,
        actions = {
            ActionIcon(
                AppIcons.Refresh,
                stringResource(R.string.journal_read),
                viewModel::readAll,
                enabled = ready && !reading,
            )
        },
    ) { padding ->
        PageColumn(padding) {
            ToolHero(AppIcons.Database, stringResource(R.string.journal_intro))
            Reveal(!ready) {
                StatusBanner(
                    stringResource(R.string.error_not_connected),
                    tone = BannerTone.Warning,
                )
            }
            BusyBar(reading)
            LocalHealthJournalSource.entries.forEach { source ->
                JournalGroup(
                    source,
                    sources[source],
                    enabled = ready && !reading && pending.none { it is LocalHealthJournalSource },
                    onRead = viewModel::readAll,
                    onSet = { on ->
                        if (on) confirming = source else viewModel.setEnabled(source, false)
                    },
                )
            }
            FootnoteText(stringResource(R.string.journal_note))
        }
    }
    confirming?.let { source ->
        ConfirmDialog(
            title = stringResource(R.string.journal_enable_title),
            body = stringResource(R.string.journal_enable_body),
            confirmLabel = stringResource(R.string.action_turn_on),
            onConfirm = {
                viewModel.setEnabled(source, true)
                confirming = null
            },
            onDismiss = { confirming = null },
        )
    }
}

/** No switch until the watch has said: an unread log isn't the same as one that's off. */
@Composable
private fun JournalGroup(
    source: LocalHealthJournalSource,
    shown: JournalSourceUi?,
    enabled: Boolean,
    onRead: () -> Unit,
    onSet: (Boolean) -> Unit,
) {
    val status = shown?.status
    val installed = status?.installed
    val leftover = status != null && !status.enabledForSync && installed == true
    ListGroup(title = stringResource(source.label)) {
        FadeSwitch(shown, key = { it?.status?.installed != null }) { current ->
            val known = current?.status?.takeIf { it.installed != null }
            if (known != null) {
                SwitchRow(
                    stringResource(R.string.journal_switch),
                    checked = known.enabledForSync,
                    onCheckedChange = onSet,
                    summary =
                        when {
                            known.enabledForSync && known.installed == false ->
                                stringResource(R.string.journal_missing)
                            !known.enabledForSync && known.installed == true ->
                                stringResource(R.string.journal_leftover)
                            else -> null
                        },
                    icon = AppIcons.Database,
                    enabled = enabled,
                )
            } else {
                NavigationRow(
                    stringResource(R.string.journal_read),
                    onClick = onRead,
                    icon = AppIcons.Refresh,
                    summary =
                        (current?.problem ?: current?.status?.reason)?.let {
                            statusMessage(it).asString()
                        },
                    enabled = enabled,
                    trailingIcon = null,
                )
            }
        }
        Reveal(leftover) {
            NavigationRow(
                stringResource(R.string.journal_remove),
                onClick = { onSet(false) },
                icon = AppIcons.Delete,
                enabled = enabled,
                trailingIcon = null,
            )
        }
    }
}
