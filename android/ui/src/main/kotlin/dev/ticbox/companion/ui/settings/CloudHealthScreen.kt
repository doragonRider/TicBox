package dev.ticbox.companion.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.ticbox.companion.api.CloudCapability
import dev.ticbox.companion.api.MobvoiAccountPhase
import dev.ticbox.companion.api.MobvoiCloudOwner
import dev.ticbox.companion.api.MobvoiHealthCacheQuery
import dev.ticbox.companion.api.MobvoiHealthDataSource
import dev.ticbox.companion.api.MobvoiHealthSyncPhase
import dev.ticbox.companion.api.MobvoiHealthSyncRequest
import dev.ticbox.companion.api.MobvoiHealthSyncState
import dev.ticbox.companion.api.MobvoiHealthUserProfile
import dev.ticbox.companion.api.MobvoiSportRecord
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.common.currentLocale
import dev.ticbox.companion.ui.common.formatDateTime
import dev.ticbox.companion.ui.common.formatNumber
import dev.ticbox.companion.ui.common.toUiText
import dev.ticbox.companion.ui.common.uiText
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.AppIcon
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
import dev.ticbox.companion.ui.designsystem.ValueRow
import dev.ticbox.companion.ui.navigation.AccountRoute
import dev.ticbox.companion.ui.navigation.PrivacyRoute
import dev.ticbox.companion.ui.workout.sportName
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class CloudHealthUiState(
    val cloudAllowed: Boolean,
    val signedIn: Boolean,
    val owner: MobvoiCloudOwner?,
    val sync: MobvoiHealthSyncState,
)

/** What this phone has kept from earlier syncs, for one account. */
data class CloudCache(
    val owner: MobvoiCloudOwner? = null,
    val profile: MobvoiHealthUserProfile? = null,
    val sources: List<MobvoiHealthDataSource> = emptyList(),
    val sports: List<MobvoiSportRecord> = emptyList(),
    val limited: Boolean = false,
)

class CloudHealthViewModel(dependencies: CompanionDependencies) : SessionViewModel(dependencies) {
    private val currentCache = MutableStateFlow(CloudCache())
    val cache = currentCache.asStateFlow()

    val state: StateFlow<CloudHealthUiState?> =
        sessionState(
            current = {
                val account = it.services.account.state.value
                CloudHealthUiState(
                    it.services.privacy.state.value.allows(CloudCapability.MOBVOI_CLOUD),
                    account.phase == MobvoiAccountPhase.SIGNED_IN,
                    account.profile?.owner,
                    it.services.cloudHealth.state.value,
                )
            },
            updates = { session ->
                combine(
                    session.services.privacy.state,
                    session.services.account.state,
                    session.services.cloudHealth.state,
                ) { privacy, account, sync ->
                    CloudHealthUiState(
                        privacy.allows(CloudCapability.MOBVOI_CLOUD),
                        account.phase == MobvoiAccountPhase.SIGNED_IN,
                        account.profile?.owner,
                        sync,
                    )
                }
            },
        )

    /** Reads only the local cache, so it works signed out and with the cloud switched off. */
    fun loadCache() {
        viewModelScope.launch {
            try {
                currentCache.value = readCache()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: Exception) {
                messages.show(problem.toUiText())
            }
        }
    }

    private suspend fun readCache(): CloudCache {
        val services = session.value?.services ?: return CloudCache()
        val health = services.cloudHealth
        val owner =
            services.account.state.value.profile?.owner
                ?: health.cachedOwners().firstOrNull()
                ?: return CloudCache()
        val sports = mutableListOf<MobvoiSportRecord>()
        var offset: Int? = 0
        while (offset != null && sports.size < MaxSports) {
            val page = health.sports(MobvoiHealthCacheQuery(owner, offset = offset))
            sports += page.items
            offset = page.nextOffset
        }
        return CloudCache(
            owner,
            health.cachedUserProfile(owner),
            health.sources(owner),
            // Oldest first in the cache; people look for the latest.
            sports.reversed(),
            limited = offset != null,
        )
    }

    fun sync(days: Int) =
        command(SYNC) { session ->
            val now = System.currentTimeMillis()
            val from = now - TimeUnit.DAYS.toMillis(days.toLong())
            val report =
                try {
                    session.services.cloudHealth.sync(MobvoiHealthSyncRequest(from, now))
                } finally {
                    currentCache.value = readCache()
                }
            uiText(if (report.partial) R.string.cloud_sync_partial else R.string.cloud_sync_done)
        }

    fun cancelSync() {
        session.value?.services?.cloudHealth?.cancelSync()
    }

    fun refreshProfile() =
        command(PROFILE) {
            it.services.cloudHealth.refreshUserProfile()
            currentCache.value = readCache()
            null
        }

    fun download(recordId: String) =
        command(recordId) {
            it.services.cloudHealth.downloadSportRecord(recordId)
            currentCache.value = readCache()
            uiText(R.string.cloud_sport_downloaded)
        }

    fun delete(recordId: String) =
        command(recordId) {
            it.services.cloudHealth.deleteSportRecord(recordId)
            currentCache.value = readCache()
            null
        }

    fun clearCache() {
        val owner = currentCache.value.owner ?: return
        command(CLEAR) {
            it.services.cloudHealth.clearCache(owner)
            currentCache.value = readCache()
            uiText(R.string.cloud_cache_cleared)
        }
    }

    companion object {
        const val SYNC = "cloud_sync"
        const val PROFILE = "cloud_profile"
        const val CLEAR = "cloud_clear"
        private const val MaxSports = 500
    }
}

private val MobvoiHealthSyncPhase.status: Int?
    get() =
        when (this) {
            MobvoiHealthSyncPhase.IDLE -> null
            MobvoiHealthSyncPhase.SYNCING -> R.string.cloud_phase_syncing
            MobvoiHealthSyncPhase.COMPLETED -> R.string.cloud_sync_done
            MobvoiHealthSyncPhase.PARTIAL -> R.string.cloud_sync_partial
            MobvoiHealthSyncPhase.FAILED -> R.string.cloud_phase_failed
            MobvoiHealthSyncPhase.CANCELLED -> R.string.cloud_phase_cancelled
            MobvoiHealthSyncPhase.CLOSED -> R.string.cloud_phase_closed
        }

private val SyncRanges = listOf(7, 30, 90)

@Composable
fun CloudHealthScreen(onBack: () -> Unit, navigate: (Any) -> Unit) {
    val viewModel = companionViewModel { CloudHealthViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cache by viewModel.cache.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    var clearing by rememberSaveable { mutableStateOf(false) }
    val owner = state?.owner
    LaunchedEffect(owner) { viewModel.loadCache() }
    PageScaffold(stringResource(R.string.cloud_title), onBack = onBack) { padding ->
        val current = state
        if (current == null) {
            EmptyState(
                AppIcons.Database,
                stringResource(R.string.no_watch_title),
                stringResource(R.string.no_watch_body),
                Modifier.padding(padding),
            )
            return@PageScaffold
        }
        PageColumn(padding) {
            Reveal(!current.cloudAllowed) {
                StatusBanner(
                    stringResource(R.string.account_cloud_disabled),
                    tone = BannerTone.Warning,
                    actionLabel = stringResource(R.string.account_open_privacy),
                    onAction = { navigate(PrivacyRoute) },
                )
            }
            Reveal(current.cloudAllowed && !current.signedIn) {
                StatusBanner(
                    stringResource(R.string.cloud_sign_in_first),
                    tone = BannerTone.Warning,
                    actionLabel = stringResource(R.string.account_sign_in),
                    onAction = { navigate(AccountRoute) },
                )
            }
            SyncGroup(
                sync = current.sync,
                enabled =
                    current.cloudAllowed &&
                        current.signedIn &&
                        CloudHealthViewModel.SYNC !in pending,
                onSync = viewModel::sync,
                onCancel = viewModel::cancelSync,
            )
            RevealValue(cache.profile) { CloudProfile(it) }
            Reveal(current.cloudAllowed && current.signedIn) {
                ListGroup {
                    NavigationRow(
                        stringResource(R.string.cloud_refresh_profile),
                        onClick = viewModel::refreshProfile,
                        icon = AppIcons.Refresh,
                        enabled = CloudHealthViewModel.PROFILE !in pending,
                        trailingIcon = null,
                    )
                }
            }
            CloudSports(
                cache,
                canUseCloud = current.cloudAllowed && current.signedIn,
                pending = pending,
                onDownload = viewModel::download,
                onDelete = viewModel::delete,
            )
            RevealValue(cache.sources.takeIf { it.isNotEmpty() }) { sources ->
                ListGroup(title = stringResource(R.string.cloud_sources)) {
                    sources.forEach { source ->
                        val title = source.device?.model ?: source.applicationPackage
                        if (title != null) ValueRow(title, source.name)
                        else ValueRow(stringResource(R.string.cloud_source_unnamed), source.name)
                    }
                }
            }
            Reveal(cache.owner != null) {
                ListGroup {
                    NavigationRow(
                        stringResource(R.string.cloud_clear_cache),
                        onClick = { clearing = true },
                        icon = AppIcons.Delete,
                        enabled = CloudHealthViewModel.CLEAR !in pending,
                    )
                }
            }
            FootnoteText(stringResource(R.string.cloud_note))
        }
    }
    if (clearing) {
        ConfirmDialog(
            stringResource(R.string.cloud_clear_cache),
            stringResource(R.string.cloud_clear_cache_body),
            onDismiss = { clearing = false },
            onConfirm = {
                clearing = false
                viewModel.clearCache()
            },
        )
    }
}

@Composable
private fun SyncGroup(
    sync: MobvoiHealthSyncState,
    enabled: Boolean,
    onSync: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    var days by rememberSaveable { mutableIntStateOf(SyncRanges.first()) }
    val syncing = sync.phase == MobvoiHealthSyncPhase.SYNCING
    ListGroup(Modifier.selectableGroup(), title = stringResource(R.string.cloud_sync)) {
        SyncRanges.forEach { range ->
            RadioRow(
                pluralStringResource(R.plurals.cloud_sync_days, range, range),
                selected = days == range,
                onClick = { days = range },
            )
        }
        FadeSwitch(syncing) { running ->
            if (running) {
                NavigationRow(
                    stringResource(R.string.action_cancel),
                    onClick = onCancel,
                    icon = AppIcons.Close,
                    trailingIcon = null,
                )
            } else {
                NavigationRow(
                    stringResource(R.string.cloud_sync_start),
                    onClick = { onSync(days) },
                    icon = AppIcons.Download,
                    enabled = enabled,
                    trailingIcon = null,
                )
            }
        }
    }
    BusyBar(syncing)
    RevealValue(sync.phase.status) { status ->
        StatusBanner(
            stringResource(
                R.string.cloud_sync_counts,
                sync.sourceCount,
                sync.pointCount,
                sync.sessionCount,
                sync.sportCount,
            ),
            title = stringResource(status),
            tone =
                when (sync.phase) {
                    MobvoiHealthSyncPhase.FAILED -> BannerTone.Error
                    MobvoiHealthSyncPhase.PARTIAL -> BannerTone.Warning
                    else -> BannerTone.Info
                },
        )
    }
}

/** The account service's own profile; values are shown as sent, with no unit guessed for them. */
@Composable
private fun CloudProfile(profile: MobvoiHealthUserProfile) {
    val locale = currentLocale()
    val rows =
        listOfNotNull(
            profile.birthday?.let { R.string.profile_birthday to it },
            profile.height?.let { R.string.profile_height to it },
            profile.weight?.let { R.string.profile_weight to it },
            profile.goalTodayStep?.let { R.string.profile_goal_steps to formatNumber(it, locale) },
            profile.goalTodayActive?.let {
                R.string.profile_goal_active to formatNumber(it, locale)
            },
            profile.goalTodayExercise?.let {
                R.string.profile_goal_exercise to formatNumber(it, locale)
            },
            profile.heartRateWarningFitnessUpperLimit?.let {
                R.string.profile_limit_fitness_upper to formatNumber(it, locale)
            },
            profile.heartRateWarningRestUpperLimit?.let {
                R.string.profile_limit_rest_upper to formatNumber(it, locale)
            },
            profile.heartRateWarningRestLowerLimit?.let {
                R.string.profile_limit_rest_lower to formatNumber(it, locale)
            },
        )
    if (rows.isEmpty()) return
    ListGroup(title = stringResource(R.string.cloud_profile)) {
        rows.forEach { (title, value) -> ValueRow(stringResource(title), value) }
    }
}

@Composable
private fun CloudSports(
    cache: CloudCache,
    canUseCloud: Boolean,
    pending: Set<Any>,
    onDownload: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    ListGroup(title = stringResource(R.string.cloud_sports)) {
        FadeSwitch(cache.sports, key = { sports -> sports.map { it.recordId } }) { sports ->
            if (sports.isEmpty()) {
                ValueRow(
                    stringResource(R.string.cloud_sports_empty),
                    stringResource(R.string.cloud_sports_empty_body),
                )
            } else {
                Column {
                    sports.forEach { sport ->
                        SportRow(
                            sport,
                            enabled = canUseCloud && pending.none { it == sport.recordId },
                            onDownload = onDownload,
                            onDelete = { deleting = it },
                        )
                    }
                }
            }
        }
    }
    Reveal(cache.limited) { FootnoteText(stringResource(R.string.cloud_sports_limited)) }
    deleting?.let { recordId ->
        ConfirmDialog(
            stringResource(R.string.cloud_delete_sport),
            stringResource(R.string.cloud_delete_sport_body),
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                onDelete(recordId)
            },
        )
    }
}

@Composable
private fun SportRow(
    sport: MobvoiSportRecord,
    enabled: Boolean,
    onDownload: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val locale = currentLocale()
    val recordId = sport.recordId
    val type = sport.type
    val name =
        type?.toLongOrNull()?.let { sportName(it) }
            ?: type
            ?: stringResource(R.string.sport_unknown)
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = {
            Text(
                listOfNotNull(
                        sport.startMillis?.let { formatDateTime(it, locale) },
                        sport.tracks
                            .takeIf { it.isNotEmpty() }
                            ?.let {
                                stringResource(
                                    R.string.cloud_sport_tracks,
                                    formatNumber(it.size, locale),
                                )
                            },
                    )
                    .joinToString(" · ")
            )
        },
        trailingContent =
            recordId?.let {
                {
                    Row {
                        AnimatedVisibility(sport.tracks.isEmpty()) {
                            IconButton(onClick = { onDownload(it) }, enabled = enabled) {
                                AppIcon(
                                    AppIcons.Download,
                                    stringResource(R.string.cloud_download_sport),
                                )
                            }
                        }
                        IconButton(onClick = { onDelete(it) }, enabled = enabled) {
                            AppIcon(AppIcons.Delete, stringResource(R.string.cloud_delete_sport))
                        }
                    }
                }
            },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
