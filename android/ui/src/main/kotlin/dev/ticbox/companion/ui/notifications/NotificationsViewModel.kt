package dev.ticbox.companion.ui.notifications

import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.ticbox.companion.api.NotificationAlertMode
import dev.ticbox.companion.api.NotificationAlertPatchPhase
import dev.ticbox.companion.api.NotificationAlertPatchState
import dev.ticbox.companion.api.NotificationFilterMode
import dev.ticbox.companion.api.NotificationSyncSettings
import dev.ticbox.companion.api.NotificationSyncState
import dev.ticbox.companion.ui.AppSort
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.PhoneApp
import dev.ticbox.companion.ui.PhoneAppsSource
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.common.UiText
import dev.ticbox.companion.ui.filterPhoneApps
import dev.ticbox.companion.ui.parsePackageList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface InstalledApps {
    data object Loading : InstalledApps

    data object Failed : InstalledApps

    data class Loaded(val apps: List<PhoneApp>) : InstalledApps
}

data class AppFilters(
    val query: String = "",
    val sort: AppSort = AppSort.SELECTED_FIRST,
    val showSystem: Boolean = false,
    val showOffline: Boolean = false,
)

data class NotificationsUiState(
    val sync: NotificationSyncState,
    val apps: InstalledApps,
    val filters: AppFilters,
    val visible: List<PhoneApp>,
    /** Packages in the current list that are not installed on this phone. */
    val missing: List<String>,
    val alerts: NotificationAlertsUi = NotificationAlertsUi(),
) {
    val settings: NotificationSyncSettings
        get() = sync.settings

    fun sends(packageName: String) = settings.packageAllowed(packageName)
}

/** Whether per-app alerts are worth offering. Works from memory, so it holds offline as well. */
data class NotificationAlertsUi(
    /** The watch last said it applies these settings. Without that they'd have no effect. */
    val applied: Boolean = false,
    /** Someone is partway through setting this up. */
    val setupStarted: Boolean = false,
    /** Only someone who has this set up gets asked about, which takes root on the watch. */
    val needsRead: Boolean = false,
)

internal fun notificationAlertsUi(
    patch: NotificationAlertPatchState,
    remembered: Boolean,
): NotificationAlertsUi {
    val snapshot = patch.snapshot
    val applied = if (patch.current) snapshot?.applied == true else remembered
    return NotificationAlertsUi(
        applied = applied,
        setupStarted = patch.current && snapshot?.optedIn == true && !applied,
        needsRead =
            remembered &&
                patch.phase == NotificationAlertPatchPhase.READY &&
                snapshot == null &&
                patch.reason == null,
    )
}

/** A pending replacement of the current list, confirmed by the user first. */
data class ListChange(
    val packages: Set<String>,
    val added: Int,
    val removed: Int,
    val ignored: Int = 0,
)

class NotificationsViewModel(
    dependencies: CompanionDependencies,
    private val saved: SavedStateHandle,
) : SessionViewModel(dependencies) {
    private val phoneApps: PhoneAppsSource = dependencies.phoneApps
    private val preferences = dependencies.preferences
    private val installed = MutableStateFlow<InstalledApps>(InstalledApps.Loading)
    private val filters =
        MutableStateFlow(
            AppFilters(
                query = saved[QUERY] ?: "",
                sort = saved.get<String>(SORT)?.let(AppSort::valueOf) ?: AppSort.SELECTED_FIRST,
                showSystem = saved[SYSTEM] ?: false,
                showOffline = saved[OFFLINE] ?: false,
            )
        )

    /**
     * Sorting by "listed first" uses the list as it was when the view was arranged, so a row does
     * not jump away right after it is toggled.
     */
    private val arrangedSelection = MutableStateFlow<Set<String>?>(null)

    private val sync: StateFlow<NotificationSyncState?> =
        sessionState(
            current = { it.services.notifications.state.value },
            updates = { it.services.notifications.state },
        )

    private val alerts: StateFlow<NotificationAlertsUi?> =
        sessionState(
            current = {
                notificationAlertsUi(
                    it.services.notificationAlertPatch.state.value,
                    !it.isDemo && preferences.notificationAlertsApplied.value,
                )
            },
            updates = { session ->
                combine(
                    session.services.notificationAlertPatch.state,
                    preferences.notificationAlertsApplied,
                ) { patch, remembered ->
                    notificationAlertsUi(patch, !session.isDemo && remembered)
                }
            },
        )

    val state: StateFlow<NotificationsUiState?> =
        combine(sync, installed, filters, arrangedSelection, alerts) {
                sync,
                apps,
                filters,
                arranged,
                alerts ->
                sync ?: return@combine null
                val selected = sync.settings.selectedPackages
                val list = (apps as? InstalledApps.Loaded)?.apps.orEmpty()
                val installedNames = list.mapTo(HashSet()) { it.packageName }
                NotificationsUiState(
                    sync = sync,
                    apps = apps,
                    filters = filters,
                    visible =
                        filterPhoneApps(
                            list,
                            filters.query,
                            filters.showSystem,
                            filters.showOffline,
                            filters.sort,
                            arranged ?: selected,
                            // Also as arranged, so an app taken off the list stays under the
                            // finger.
                            alwaysShown = selected + arranged.orEmpty(),
                        ),
                    missing =
                        selected
                            .filter {
                                it !in installedNames &&
                                    it.contains(filters.query, ignoreCase = true)
                            }
                            .sorted(),
                    alerts = alerts ?: NotificationAlertsUi(),
                )
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val proposal = MutableStateFlow<ListChange?>(null)
    val pendingChange: StateFlow<ListChange?> = proposal

    init {
        reloadApps()
    }

    fun reloadApps() {
        installed.value = InstalledApps.Loading
        viewModelScope.launch {
            installed.value =
                try {
                    InstalledApps.Loaded(phoneApps.installedApps())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (problem: Exception) {
                    Log.w(TAG, "Loading installed apps failed", problem)
                    InstalledApps.Failed
                }
            rearrange()
        }
    }

    suspend fun icon(packageName: String): ImageBitmap? =
        try {
            phoneApps.icon(packageName)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    fun updateFilters(change: (AppFilters) -> AppFilters) {
        val next = change(filters.value)
        filters.value = next
        saved[QUERY] = next.query
        saved[SORT] = next.sort.name
        saved[SYSTEM] = next.showSystem
        saved[OFFLINE] = next.showOffline
        rearrange()
    }

    fun setEnabled(enabled: Boolean) =
        command(ENABLED) { session ->
            session.services.notifications.setEnabled(enabled)
            null
        }

    /** Checked means the app's notifications reach the watch, whatever the list mode. */
    fun setSends(packageName: String, sends: Boolean) =
        command("app:$packageName") { session ->
            session.services.notifications.setPackageEnabled(packageName, sends)
            null
        }

    fun readAlerts() =
        command(ALERTS_READ) {
            it.services.notificationAlertPatch.refresh()
            null
        }

    fun setSoundEnabled(enabled: Boolean) =
        command(SOUND) {
            it.services.notifications.setSoundEnabled(enabled)
            null
        }

    fun setVibrationEnabled(enabled: Boolean) =
        command(VIBRATION) {
            it.services.notifications.setVibrationEnabled(enabled)
            null
        }

    fun setAppSound(packageName: String, mode: NotificationAlertMode) =
        command("sound:$packageName") {
            it.services.notifications.setPackageSoundMode(packageName, mode)
            null
        }

    fun setAppVibration(packageName: String, mode: NotificationAlertMode) =
        command("vibration:$packageName") {
            it.services.notifications.setPackageVibrationMode(packageName, mode)
            null
        }

    fun setAllVisible(sends: Boolean) {
        val current = state.value ?: return
        val packages = current.visible.map { it.packageName } + current.missing
        replaceList(listFor(current.settings, packages.associateWith { sends }))
    }

    fun setMode(mode: NotificationFilterMode) {
        val settings = state.value?.settings ?: return
        if (settings.mode == mode) return
        command(FILTER) { session ->
            val list =
                if (mode == NotificationFilterMode.ALLOWLIST) settings.allowedPackages
                else settings.blockedPackages
            session.services.notifications.setPackageFilter(mode, list)
            rearrange()
            null
        }
    }

    /** Suggests sending regular apps (not system, with internet access) and muting the rest. */
    fun proposeSuggestion() {
        val current = state.value ?: return
        val apps = (current.apps as? InstalledApps.Loaded)?.apps ?: return
        val decisions = apps.associate { it.packageName to (!it.system && it.internet) }
        propose(listFor(current.settings, decisions))
    }

    fun proposeImport(text: String) {
        val parsed = parsePackageList(text)
        if (parsed.tooLarge) {
            messages.show(UiText.Resource(R.string.notifications_import_too_large))
            return
        }
        if (parsed.packages.isEmpty()) {
            messages.show(UiText.Resource(R.string.notifications_import_empty))
            return
        }
        propose(parsed.packages, ignored = parsed.ignored)
    }

    fun confirmChange() {
        val change = proposal.value ?: return
        proposal.value = null
        replaceList(change.packages)
    }

    fun dismissChange() {
        proposal.value = null
    }

    /** The current list as plain text, one package per line. */
    fun exportList(): String =
        state.value?.settings?.selectedPackages?.sorted()?.joinToString("\n").orEmpty()

    fun resync() =
        command(RESYNC) { session ->
            session.services.notifications.resync()
            UiText.Resource(R.string.notifications_rechecked)
        }

    private fun propose(packages: Set<String>, ignored: Int = 0) {
        val current = state.value?.settings?.selectedPackages ?: return
        proposal.value =
            ListChange(packages, (packages - current).size, (current - packages).size, ignored)
    }

    private fun replaceList(packages: Set<String>) {
        val mode = state.value?.settings?.mode ?: return
        command(FILTER) { session ->
            session.services.notifications.setPackageFilter(mode, packages)
            UiText.Resource(R.string.notifications_saved)
        }
    }

    private fun rearrange() {
        arrangedSelection.value = sync.value?.settings?.selectedPackages
    }

    companion object {
        const val ENABLED = "notifications_enabled"
        const val FILTER = "notifications_filter"
        const val RESYNC = "notifications_resync"
        const val ALERTS_READ = "notifications_alerts_read"
        const val SOUND = "notifications_sound"
        const val VIBRATION = "notifications_vibration"
        private const val TAG = "NotificationsViewModel"
        private const val QUERY = "query"
        private const val SORT = "sort"
        private const val SYSTEM = "system"
        private const val OFFLINE = "offline"
    }
}

/** The mode's list after applying "sends" decisions for some packages. */
internal fun listFor(
    settings: NotificationSyncSettings,
    decisions: Map<String, Boolean>,
): Set<String> {
    val list = settings.selectedPackages.toMutableSet()
    decisions.forEach { (packageName, sends) ->
        val listed =
            when (settings.mode) {
                NotificationFilterMode.ALLOWLIST -> sends
                NotificationFilterMode.BLOCKLIST -> !sends
            }
        if (listed) list += packageName else list -= packageName
    }
    return list
}
