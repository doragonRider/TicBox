package dev.ticbox.companion.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.ticbox.companion.api.SosWatchEditRequest
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.AroundPages
import dev.ticbox.companion.ui.designsystem.BottomNotice
import dev.ticbox.companion.ui.designsystem.CompanionTheme
import dev.ticbox.companion.ui.designsystem.Reveal
import dev.ticbox.companion.ui.navigation.CompanionNavHost
import dev.ticbox.companion.ui.navigation.NavigationBarHeight
import dev.ticbox.companion.ui.navigation.SosRoute
import dev.ticbox.companion.ui.navigation.TerminalRoute
import dev.ticbox.companion.ui.navigation.isTopLevelStart
import dev.ticbox.companion.ui.navigation.resetToHome
import dev.ticbox.companion.ui.session.WatchSession
import dev.ticbox.companion.ui.session.readySession
import dev.ticbox.companion.ui.toolbox.ShellHost
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/** Root of the phone UI. The host supplies dependencies and Activity-bound system actions. */
@Composable
fun CompanionApp(
    dependencies: CompanionDependencies,
    systemActions: SystemActions,
    navController: NavHostController = rememberNavController(),
) {
    CompositionLocalProvider(
        LocalCompanionDependencies provides dependencies,
        LocalSystemActions provides systemActions,
    ) {
        val appearance by dependencies.preferences.appearance.collectAsStateWithLifecycle()
        CompanionTheme(appearance) { AppShell(dependencies, navController) }
    }
}

/**
 * The watch's SOS page has no way to type, so it asks the phone. A request made while the app is
 * out of sight waits for it to come back, because a person then picks up the phone to do just that.
 */
@Composable
internal fun SosEditRequests(session: WatchSession?, open: (SosWatchEditRequest) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOpen by rememberUpdatedState(open)
    var handled by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(session, lifecycle) {
        val sos = session?.services?.sos ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            sos.state
                .map { it.lastWatchEditRequest }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { request ->
                    val key = "${request.sessionId}:${request.sequence}"
                    if (key != handled) {
                        handled = key
                        currentOpen(request)
                    }
                }
        }
    }
}

/** Keeps what the watch last said about the notification extension for the Notifications page. */
@Composable
private fun RememberNotificationAlerts(session: WatchSession?, preferences: AppPreferences) {
    LaunchedEffect(session, preferences) {
        if (session == null || session.isDemo) return@LaunchedEffect
        session.services.notificationAlertPatch.state.collect { patch ->
            if (patch.current)
                preferences.setNotificationAlertsApplied(patch.snapshot?.applied == true)
        }
    }
}

@Composable
private fun AppShell(dependencies: CompanionDependencies, navController: NavHostController) {
    val sessionState by dependencies.sessions.state.collectAsStateWithLifecycle()
    val session = sessionState.readySession
    val backStackEntry by navController.currentBackStackEntryAsState()
    val snackbar = remember { SnackbarHostState() }
    // The language can change while the app runs without recreating the Activity.
    val resources by rememberUpdatedState(LocalResources.current)

    LaunchedEffect(dependencies.messages) {
        dependencies.messages.messages.collect { snackbar.showSnackbar(it.resolve(resources)) }
    }

    // Switching between the service and the demo watch starts from a fresh home page.
    var shownSession by rememberSaveable { mutableStateOf(session?.key) }
    LaunchedEffect(session?.key) {
        val key = session?.key ?: return@LaunchedEffect
        if (shownSession != null && shownSession != key) navController.resetToHome()
        shownSession = key
    }

    AskAboutARunningShell(dependencies.shell, navController.previousBackStackEntry == null)
    LaunchedEffect(dependencies.shell) {
        dependencies.shell.showRequests.collect {
            navController.navigate(TerminalRoute) { launchSingleTop = true }
        }
    }

    RememberNotificationAlerts(session, dependencies.preferences)
    SosEditRequests(session) { request ->
        navController.navigate(SosRoute(request.kind.name, request.sequence)) {
            launchSingleTop = true
        }
    }

    val isDemo = session?.isDemo == true
    // Tab pages draw their own navigation bar; keep messages above it.
    val onTabPage = backStackEntry?.destination?.isTopLevelStart() ?: true
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = {
            SnackbarHost(
                snackbar,
                Modifier.then(if (isDemo) Modifier else Modifier.navigationBarsPadding())
                    .padding(bottom = if (onTabPage) NavigationBarHeight else 0.dp),
            )
        },
        bottomBar = {
            Reveal(isDemo) {
                AroundPages { DemoBanner(onExit = dependencies.sessions::exitDemo) }
            }
        },
    ) { padding ->
        CompanionNavHost(
            navController,
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
        )
    }
}

/**
 * Leaving the app is when a running shell needs a decision, and only Back can ask for one: Home and
 * the recent apps screen give no chance to, so `MainActivity` keeps the shell running for those.
 */
@Composable
private fun AskAboutARunningShell(shell: ShellHost, onFirstPage: Boolean) {
    val state by shell.state.collectAsStateWithLifecycle()
    val actions = LocalSystemActions.current
    val activity = LocalActivity.current
    var asking by remember { mutableStateOf(false) }
    BackHandler(onFirstPage && state.running) { asking = true }
    if (asking && state.running) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.terminal_exit_title)) },
            text = { Text(stringResource(R.string.terminal_exit_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        asking = false
                        actions.keepShellRunning()
                    }
                ) {
                    Text(stringResource(R.string.terminal_exit_keep))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        asking = false
                        shell.stop()
                        activity?.moveTaskToBack(true)
                    }
                ) {
                    Text(stringResource(R.string.terminal_exit_end))
                }
            },
        )
    }
}

/** Shown on every page while the demo watch is active. */
@Composable
private fun DemoBanner(onExit: () -> Unit) {
    BottomNotice(
        AppIcons.Demo,
        stringResource(R.string.demo_banner),
        stringResource(R.string.demo_exit),
        onExit,
        MaterialTheme.colorScheme.tertiaryContainer,
        MaterialTheme.colorScheme.onTertiaryContainer,
        Modifier.testTag("demo_banner"),
    )
}
