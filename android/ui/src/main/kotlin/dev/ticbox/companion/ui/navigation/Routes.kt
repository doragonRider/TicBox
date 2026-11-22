package dev.ticbox.companion.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.diagnostics.DiagnosticsSection
import dev.ticbox.companion.ui.health.HealthLog
import dev.ticbox.companion.ui.health.HealthMetric
import kotlinx.serialization.Serializable

// Tab graphs.
@Serializable data object WatchGraph

@Serializable data object HealthGraph

@Serializable data object SettingsGraph

// Watch tab.
@Serializable data object HomeRoute

@Serializable data object ConnectionRoute

@Serializable data object PhoneTransferRoute

@Serializable data object PairingRoute

@Serializable data object SetupRoute

@Serializable data object WatchFacesRoute

@Serializable data object NotificationsRoute

@Serializable data object WatchPreferencesRoute

@Serializable data object FindDevicesRoute

@Serializable data object MediaRoute

@Serializable data object WatchAppsRoute

@Serializable data object WatchLockRoute

@Serializable data object PhotoFaceRoute

@Serializable data object PhotoFaceEditorRoute

@Serializable data object CallingRoute

/** [edit] names what the watch asked to edit; [sequence] keeps one request from opening twice. */
@Serializable data class SosRoute(val edit: String? = null, val sequence: Long = -1L)

@Serializable data object HealthMonitoringRoute

@Serializable data object HealthJournalRoute

@Serializable data object WatchModuleRoute

@Serializable data object LocalBridgeRoute

@Serializable data object AdbConnectRoute

// Toolbox, under Watch → Manage → Advanced. Its pages share a graph, and the view models it keeps.
@Serializable data object ToolboxGraph

@Serializable data object ToolboxRoute

@Serializable data object ApkInstallerRoute

@Serializable data object SplitInstallerRoute

@Serializable data object TerminalRoute

@Serializable data object RemoteScreenRoute

@Serializable data object RemoteScreenAdvancedRoute

@Serializable data object FileManagerRoute

@Serializable data object AppManagerRoute

@Serializable data object ClipboardRoute

// Health tab.
@Serializable data object HealthOverviewRoute

@Serializable data class HealthDetailRoute(val metric: HealthMetric)

@Serializable data object StepGoalRoute

@Serializable data object WorkoutsRoute

@Serializable data class WorkoutDetailRoute(val key: String)

@Serializable data class HealthLogRoute(val log: HealthLog)

@Serializable data object WatchProfileRoute

// Settings tab.
@Serializable data object SettingsRoute

@Serializable data object PrivacyRoute

@Serializable data object AppearanceRoute

@Serializable data object LanguageRoute

@Serializable data object DataMigrationRoute

@Serializable data object DiagnosticsRoute

@Serializable data class DiagnosticsDetailRoute(val section: DiagnosticsSection)

@Serializable data object ObservationsRoute

@Serializable data object StoredRecordsRoute

@Serializable data object AccountRoute

@Serializable data object CloudHealthRoute

@Serializable data object GoogleServicesRoute

enum class TopLevelDestination(
    val graph: Any,
    val start: Any,
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
    @param:DrawableRes val selectedIcon: Int,
) {
    WATCH(WatchGraph, HomeRoute, R.string.tab_watch, AppIcons.Watch, AppIcons.WatchFilled),
    HEALTH(
        HealthGraph,
        HealthOverviewRoute,
        R.string.tab_health,
        AppIcons.Heart,
        AppIcons.HeartFilled,
    ),
    SETTINGS(
        SettingsGraph,
        SettingsRoute,
        R.string.tab_settings,
        AppIcons.Settings,
        AppIcons.SettingsFilled,
    ),
}

fun NavDestination.topLevel(): TopLevelDestination? =
    TopLevelDestination.entries.firstOrNull { tab ->
        hierarchy.any { it.hasRoute(tab.graph::class) }
    }

fun NavDestination.isTopLevelStart(): Boolean =
    TopLevelDestination.entries.any { hasRoute(it.start::class) }

val NavBackStackEntry.tab: TopLevelDestination?
    get() = destination.topLevel()

/**
 * Switches tabs. Tabs are siblings, so the new one replaces the current one: a tab's start page has
 * nothing below it and back leaves the app from any tab, with the system's own animation. Each tab
 * keeps its pages and state for when it comes back.
 */
fun NavController.navigateToTab(tab: TopLevelDestination) {
    val current = currentDestination?.topLevel()
    navigate(tab.graph) {
        current?.let {
            popUpTo(it.graph) {
                inclusive = true
                saveState = true
            }
        }
        restoreState = true
    }
}

/**
 * Closes the current page. A second tap while the page is still closing finds no page below, where
 * navigateUp would restart the app on any tab but the first.
 */
fun NavController.closePage() {
    if (previousBackStackEntry != null) navigateUp()
}

/** Ends pairing and setup on the watch home. They can start on the Health tab, which is kept. */
fun NavController.returnToWatchHome() {
    if (popBackStack<HomeRoute>(inclusive = false)) return
    popBackStack<HealthOverviewRoute>(inclusive = false)
    navigateToTab(TopLevelDestination.WATCH)
}

/** Returns to the watch home with fresh back stacks, e.g. after switching to the demo watch. */
fun NavController.resetToHome() {
    clearBackStack<WatchGraph>()
    clearBackStack<HealthGraph>()
    clearBackStack<SettingsGraph>()
    navigate(HomeRoute) { popUpTo(graph.id) { inclusive = true } }
}
