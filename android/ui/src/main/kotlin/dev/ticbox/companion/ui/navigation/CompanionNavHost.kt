package dev.ticbox.companion.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.ViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import dev.ticbox.companion.ui.bridge.HealthJournalScreen
import dev.ticbox.companion.ui.bridge.LocalBridgeScreen
import dev.ticbox.companion.ui.bridge.WatchModuleScreen
import dev.ticbox.companion.ui.calling.CallingScreen
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.connection.ConnectionScreen
import dev.ticbox.companion.ui.connection.PhoneTransferScreen
import dev.ticbox.companion.ui.device.HealthMonitoringScreen
import dev.ticbox.companion.ui.device.WatchAppsScreen
import dev.ticbox.companion.ui.device.WatchLockScreen
import dev.ticbox.companion.ui.device.WatchPreferencesScreen
import dev.ticbox.companion.ui.diagnostics.DiagnosticsDetailScreen
import dev.ticbox.companion.ui.diagnostics.DiagnosticsScreen
import dev.ticbox.companion.ui.diagnostics.ObservationsScreen
import dev.ticbox.companion.ui.diagnostics.StoredRecordsScreen
import dev.ticbox.companion.ui.faces.PhotoFaceEditorScreen
import dev.ticbox.companion.ui.faces.PhotoFaceScreen
import dev.ticbox.companion.ui.faces.PhotoFaceViewModel
import dev.ticbox.companion.ui.faces.WatchFacesScreen
import dev.ticbox.companion.ui.faces.rememberPhotoFaceStore
import dev.ticbox.companion.ui.finding.FindDevicesScreen
import dev.ticbox.companion.ui.health.HealthDetailScreen
import dev.ticbox.companion.ui.health.HealthLogScreen
import dev.ticbox.companion.ui.health.HealthOverviewScreen
import dev.ticbox.companion.ui.health.HealthViewModel
import dev.ticbox.companion.ui.health.StepGoalScreen
import dev.ticbox.companion.ui.health.WatchProfileScreen
import dev.ticbox.companion.ui.home.HomeScreen
import dev.ticbox.companion.ui.media.MediaScreen
import dev.ticbox.companion.ui.notifications.NotificationsScreen
import dev.ticbox.companion.ui.pairing.PairingScreen
import dev.ticbox.companion.ui.settings.AccountScreen
import dev.ticbox.companion.ui.settings.AppearanceScreen
import dev.ticbox.companion.ui.settings.CloudHealthScreen
import dev.ticbox.companion.ui.settings.DataMigrationScreen
import dev.ticbox.companion.ui.settings.GoogleServicesScreen
import dev.ticbox.companion.ui.settings.LanguageScreen
import dev.ticbox.companion.ui.settings.PrivacyScreen
import dev.ticbox.companion.ui.settings.SettingsScreen
import dev.ticbox.companion.ui.setup.SetupScreen
import dev.ticbox.companion.ui.sos.SosScreen
import dev.ticbox.companion.ui.toolbox.AdbConnectScreen
import dev.ticbox.companion.ui.toolbox.AppManagerScreen
import dev.ticbox.companion.ui.toolbox.ClipboardScreen
import dev.ticbox.companion.ui.toolbox.FileManagerScreen
import dev.ticbox.companion.ui.toolbox.InstallerScreen
import dev.ticbox.companion.ui.toolbox.RemoteScreenAdvancedScreen
import dev.ticbox.companion.ui.toolbox.RemoteScreenScreen
import dev.ticbox.companion.ui.toolbox.TerminalScreen
import dev.ticbox.companion.ui.toolbox.ToolboxScreen
import dev.ticbox.companion.ui.workout.WorkoutDetailScreen
import dev.ticbox.companion.ui.workout.WorkoutsScreen
import dev.ticbox.companion.ui.workout.WorkoutsViewModel

/** All pages. Motion is described in PageMotion.kt. */
@Composable
fun CompanionNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    val navigate: (Any) -> Unit = { route -> navController.navigate(route) }
    val back: () -> Unit = { navController.closePage() }
    val selectTab: (TopLevelDestination) -> Unit = { tab -> navController.navigateToTab(tab) }
    val motion = remember(navController) { PageMotion(navController) }
    val events = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    LaunchedEffect(events, motion) { events?.transitionState?.collect(motion::onGesture) }
    NavHost(
        navController = navController,
        startDestination = WatchGraph,
        modifier =
            modifier.graphicsLayer {
                compositingStrategy =
                    if (motion.tabFading) CompositingStrategy.Offscreen
                    else CompositingStrategy.Auto
            },
        enterTransition = { motion.enter(this) },
        exitTransition = { motion.exit(this) },
        popEnterTransition = { motion.popEnter(this) },
        popExitTransition = { motion.popExit(this) },
        predictivePopEnterTransition = { edge -> motion.popEnter(this, edge) },
        predictivePopExitTransition = { edge -> motion.popExit(this, edge) },
    ) {
        navigation<WatchGraph>(startDestination = HomeRoute) {
            page<HomeRoute>(motion) {
                TabPage(TopLevelDestination.WATCH, selectTab) { HomeScreen(navigate) }
            }
            page<ConnectionRoute>(motion) { ConnectionScreen(back, navigate) }
            page<PhoneTransferRoute>(motion) { PhoneTransferScreen(back) }
            page<PairingRoute>(motion) {
                PairingScreen(
                    onBack = back,
                    onPaired = {
                        navController.navigate(SetupRoute) {
                            popUpTo<PairingRoute> { inclusive = true }
                        }
                    },
                )
            }
            page<SetupRoute>(motion) {
                SetupScreen(onBack = back, onDone = { navController.returnToWatchHome() })
            }
            page<WatchFacesRoute>(motion) { WatchFacesScreen(back, navigate) }
            page<PhotoFaceRoute>(motion) { entry ->
                PhotoFaceScreen(photoFaceViewModel(navController, entry), back) {
                    navigate(PhotoFaceEditorRoute)
                }
            }
            page<PhotoFaceEditorRoute>(motion) { entry ->
                PhotoFaceEditorScreen(photoFaceViewModel(navController, entry), back) {
                    navController.popBackStack<PhotoFaceEditorRoute>(inclusive = true)
                }
            }
            page<NotificationsRoute>(motion) { NotificationsScreen(back, navigate) }
            page<WatchPreferencesRoute>(motion) { WatchPreferencesScreen(back, navigate) }
            page<HealthMonitoringRoute>(motion) { HealthMonitoringScreen(back, navigate) }
            page<WatchAppsRoute>(motion) { WatchAppsScreen(back) }
            page<WatchLockRoute>(motion) { WatchLockScreen(back) }
            page<FindDevicesRoute>(motion) { FindDevicesScreen(back, navigate) }
            page<MediaRoute>(motion) { MediaScreen(back) }
            page<CallingRoute>(motion) { CallingScreen(back, navigate) }
            page<SosRoute>(motion) { entry ->
                val route = entry.toRoute<SosRoute>()
                SosScreen(route.edit, route.sequence, back)
            }
            page<LocalBridgeRoute>(motion) { LocalBridgeScreen(back, navigate) }
            page<HealthJournalRoute>(motion) { HealthJournalScreen(back) }
            page<WatchModuleRoute>(motion) { WatchModuleScreen(back, navigate) }
            page<AdbConnectRoute>(motion) { AdbConnectScreen(back) }
            navigation<ToolboxGraph>(startDestination = ToolboxRoute) {
                page<ToolboxRoute>(motion) { entry ->
                    ToolboxScreen(toolboxGraph(navController, entry), back, navigate)
                }
                page<ApkInstallerRoute>(motion) { entry ->
                    InstallerScreen(toolboxGraph(navController, entry), split = false, back)
                }
                page<SplitInstallerRoute>(motion) { entry ->
                    InstallerScreen(toolboxGraph(navController, entry), split = true, back)
                }
                page<TerminalRoute>(motion) { entry ->
                    TerminalScreen(toolboxGraph(navController, entry), back)
                }
                page<RemoteScreenRoute>(motion) { entry ->
                    RemoteScreenScreen(toolboxGraph(navController, entry), back) {
                        navigate(RemoteScreenAdvancedRoute)
                    }
                }
                page<RemoteScreenAdvancedRoute>(motion) { entry ->
                    RemoteScreenAdvancedScreen(toolboxGraph(navController, entry), back)
                }
                page<FileManagerRoute>(motion) { entry ->
                    FileManagerScreen(toolboxGraph(navController, entry), back)
                }
                page<AppManagerRoute>(motion) { entry ->
                    AppManagerScreen(toolboxGraph(navController, entry), back)
                }
                page<ClipboardRoute>(motion) { entry ->
                    ClipboardScreen(toolboxGraph(navController, entry), back)
                }
            }
        }
        navigation<HealthGraph>(startDestination = HealthOverviewRoute) {
            page<HealthOverviewRoute>(motion) { entry ->
                TabPage(TopLevelDestination.HEALTH, selectTab) {
                    HealthOverviewScreen(
                        healthViewModel(navController, entry),
                        workoutsViewModel(navController, entry),
                        navigate,
                    )
                }
            }
            page<HealthDetailRoute>(motion) { entry ->
                HealthDetailScreen(
                    entry.toRoute<HealthDetailRoute>().metric,
                    healthViewModel(navController, entry),
                    back,
                )
            }
            page<StepGoalRoute>(motion) { StepGoalScreen(back) }
            page<WorkoutsRoute>(motion) { entry ->
                WorkoutsScreen(workoutsViewModel(navController, entry), navigate, back)
            }
            page<WorkoutDetailRoute>(motion) { entry ->
                WorkoutDetailScreen(entry.toRoute<WorkoutDetailRoute>().key, back)
            }
            page<HealthLogRoute>(motion) { entry ->
                HealthLogScreen(entry.toRoute<HealthLogRoute>().log, back)
            }
            page<WatchProfileRoute>(motion) { WatchProfileScreen(back) }
        }
        navigation<SettingsGraph>(startDestination = SettingsRoute) {
            page<SettingsRoute>(motion) {
                TabPage(TopLevelDestination.SETTINGS, selectTab) { SettingsScreen(navigate) }
            }
            page<PrivacyRoute>(motion) { PrivacyScreen(back) }
            page<AppearanceRoute>(motion) { AppearanceScreen(back) }
            page<LanguageRoute>(motion) { LanguageScreen(back) }
            page<DataMigrationRoute>(motion) { DataMigrationScreen(back) }
            page<DiagnosticsRoute>(motion) { DiagnosticsScreen(back, navigate) }
            page<DiagnosticsDetailRoute>(motion) { entry ->
                DiagnosticsDetailScreen(entry.toRoute<DiagnosticsDetailRoute>().section, back)
            }
            page<ObservationsRoute>(motion) { ObservationsScreen(back) }
            page<StoredRecordsRoute>(motion) { StoredRecordsScreen(back) }
            page<AccountRoute>(motion) { AccountScreen(back, navigate) }
            page<CloudHealthRoute>(motion) { CloudHealthScreen(back, navigate) }
            page<GoogleServicesRoute>(motion) { GoogleServicesScreen(back, navigate) }
        }
    }
}

/** A destination that draws its own motion with [AnimatedPage]. */
private inline fun <reified T : Any> NavGraphBuilder.page(
    motion: PageMotion,
    noinline content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable<T> { entry -> AnimatedPage(entry, motion) { content(entry) } }
}

/** The toolbox's pages share a connection choice, and tools that outlast a look elsewhere. */
@Composable
private fun toolboxGraph(
    navController: NavHostController,
    entry: NavBackStackEntry,
): ViewModelStoreOwner = remember(entry) { navController.getBackStackEntry<ToolboxGraph>() }

/** The health pages share one view model, so the selected date and loaded days carry over. */
@Composable
private fun healthViewModel(
    navController: NavHostController,
    entry: NavBackStackEntry,
): HealthViewModel {
    val graphEntry = remember(entry) { navController.getBackStackEntry<HealthGraph>() }
    return companionViewModel(graphEntry) { HealthViewModel(it) }
}

/** The photo face page and its editor share one view model, which holds the photo being edited. */
@Composable
private fun photoFaceViewModel(
    navController: NavHostController,
    entry: NavBackStackEntry,
): PhotoFaceViewModel {
    val pageEntry = remember(entry) { navController.getBackStackEntry<PhotoFaceRoute>() }
    val store = rememberPhotoFaceStore()
    return companionViewModel(pageEntry) { PhotoFaceViewModel(it, store) }
}

/** The overview and the workout list share one view model, so the list is read only once. */
@Composable
private fun workoutsViewModel(
    navController: NavHostController,
    entry: NavBackStackEntry,
): WorkoutsViewModel {
    val graphEntry = remember(entry) { navController.getBackStackEntry<HealthGraph>() }
    return companionViewModel(graphEntry) { WorkoutsViewModel(it) }
}
