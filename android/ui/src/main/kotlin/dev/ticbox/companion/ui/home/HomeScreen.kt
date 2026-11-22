package dev.ticbox.companion.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.ticbox.companion.api.PairingAssociation
import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.currentLocale
import dev.ticbox.companion.ui.common.formatNumber
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.connection.attention
import dev.ticbox.companion.ui.connection.label
import dev.ticbox.companion.ui.designsystem.ActionIcon
import dev.ticbox.companion.ui.designsystem.AppIcon
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.BannerTone
import dev.ticbox.companion.ui.designsystem.EmptyState
import dev.ticbox.companion.ui.designsystem.FadeSwitch
import dev.ticbox.companion.ui.designsystem.GrowingGaps
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.NavigationRow
import dev.ticbox.companion.ui.designsystem.PageColumn
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.ProgressBar
import dev.ticbox.companion.ui.designsystem.Reveal
import dev.ticbox.companion.ui.designsystem.RevealValue
import dev.ticbox.companion.ui.designsystem.StatusBanner
import dev.ticbox.companion.ui.designsystem.WatchIllustration
import dev.ticbox.companion.ui.navigation.CallingRoute
import dev.ticbox.companion.ui.navigation.ConnectionRoute
import dev.ticbox.companion.ui.navigation.FindDevicesRoute
import dev.ticbox.companion.ui.navigation.MediaRoute
import dev.ticbox.companion.ui.navigation.NotificationsRoute
import dev.ticbox.companion.ui.navigation.PairingRoute
import dev.ticbox.companion.ui.navigation.SosRoute
import dev.ticbox.companion.ui.navigation.ToolboxRoute
import dev.ticbox.companion.ui.navigation.WatchFacesRoute
import dev.ticbox.companion.ui.navigation.WatchPreferencesRoute
import dev.ticbox.companion.ui.session.SessionState
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(navigate: (Any) -> Unit) {
    val viewModel = companionViewModel { HomeViewModel(it) }
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val preview by viewModel.facePreview.collectAsStateWithLifecycle()
    val steps by viewModel.steps.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.keepBatteryCurrent() }
    }
    val current = state
    PageScaffold(
        title = stringResource(R.string.home_title),
        actions = {
            AnimatedVisibility(current?.paired == true, enter = fadeIn(), exit = fadeOut()) {
                ActionIcon(
                    AppIcons.Add,
                    stringResource(R.string.action_add_watch),
                    { navigate(PairingRoute) },
                )
            }
        },
    ) { padding ->
        FadeSwitch(
            sessionState to current,
            key = { (session, watch) ->
                when {
                    session == SessionState.Unavailable -> 0
                    watch == null || watch.loadingSavedWatch -> 1
                    !watch.paired -> 2
                    else -> 3
                }
            },
        ) { (session, watch) ->
            when {
                session == SessionState.Unavailable ->
                    PageColumn(padding) {
                        EmptyState(
                            AppIcons.Error,
                            stringResource(R.string.service_unavailable_title),
                            stringResource(R.string.service_unavailable_body),
                            actionLabel = stringResource(R.string.action_try_again),
                            onAction = viewModel::retry,
                        )
                    }
                // Connecting, or the saved watch is not known yet: no reason to show the welcome.
                watch == null || watch.loadingSavedWatch -> Loading(padding)
                !watch.paired ->
                    PageColumn(padding) {
                        RevealValue(watch.recovery.attention) {
                            StatusBanner(stringResource(it), tone = BannerTone.Warning)
                        }
                        Welcome(
                            onAdd = { navigate(PairingRoute) },
                            onDemo = viewModel::enterDemo,
                            paired = watch.recovery.pairedWatches,
                            onReconnect = viewModel::reconnect,
                            reconnectEnabled = HomeViewModel.RECONNECT !in pending,
                        )
                    }
                else -> PageColumn(padding) { WatchHome(watch, preview, steps, navigate) }
            }
        }
    }
}

/** Waits a moment before showing progress, so a quick start does not flash a spinner. */
@Composable
private fun Loading(padding: PaddingValues) {
    val visible by
        produceState(false) {
            delay(LoadingIndicatorDelayMillis)
            value = true
        }
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            CircularProgressIndicator()
        }
    }
}

private const val LoadingIndicatorDelayMillis = 500L

@Composable
private fun Welcome(
    onAdd: () -> Unit,
    onDemo: () -> Unit,
    paired: List<PairingAssociation>,
    onReconnect: (PeerAddress) -> Unit,
    reconnectEnabled: Boolean,
) {
    Column(
        Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        WatchIllustration(Modifier.fillMaxWidth().height(240.dp))
        Text(
            stringResource(R.string.welcome_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (paired.isNotEmpty()) {
            Text(
                stringResource(R.string.home_paired_watches),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.home_reconnect_existing_body),
                textAlign = TextAlign.Center,
            )
            for (association in paired) {
                val peer = association.peer ?: continue
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                association.displayName
                                    ?: stringResource(R.string.home_unnamed_watch)
                            )
                            Text(peer.value, style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            onClick = { onReconnect(peer) },
                            enabled = reconnectEnabled,
                            modifier = Modifier.testTag("reconnect_saved_watch_${association.id}"),
                        ) {
                            Text(stringResource(R.string.connection_reconnect))
                        }
                    }
                }
            }
        }
        Button(
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("start_setup"),
        ) {
            Text(stringResource(R.string.action_add_watch))
        }
        TextButton(onClick = onDemo, modifier = Modifier.testTag("try_demo")) {
            Text(stringResource(R.string.welcome_try_demo))
        }
    }
}

@Composable
private fun WatchHome(
    state: HomeState,
    preview: ImageBitmap?,
    steps: HomeSteps?,
    navigate: (Any) -> Unit,
) {
    val locale = currentLocale()
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WatchIllustration(
            Modifier.fillMaxWidth().height(256.dp),
            face = preview,
            faceDescription =
                state.currentFaceName?.let { stringResource(R.string.home_face_description, it) },
        )
        Text(
            state.name ?: stringResource(R.string.home_unnamed_watch),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { navigate(ConnectionRoute) },
                label = { Text(stringResource(state.watch.connection.phase.label)) },
                leadingIcon = {
                    AppIcon(
                        if (state.linkReady) AppIcons.Bluetooth else AppIcons.BluetoothOff,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
            state.watch.battery?.let { battery ->
                AssistChip(
                    onClick = { navigate(ConnectionRoute) },
                    label = {
                        Text(
                            stringResource(
                                if (battery.stale) R.string.home_battery_stale
                                else R.string.home_battery,
                                formatNumber(battery.value, locale),
                            )
                        )
                    },
                    leadingIcon = {
                        AppIcon(
                            AppIcons.Battery,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
        }
    }
    RevealValue(state.recovery.attention) { message ->
        StatusBanner(
            stringResource(message),
            tone = BannerTone.Warning,
            actionLabel = stringResource(R.string.action_review),
            onAction = { navigate(ConnectionRoute) },
        )
    }
    RevealValue(steps) { StepsCard(it.count, state.stepGoal ?: 0, it.lastKnown) }
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ShortcutCard(
            AppIcons.WatchFaces,
            stringResource(R.string.faces_title),
            Modifier.weight(1f).fillMaxHeight(),
        ) {
            navigate(WatchFacesRoute)
        }
        ShortcutCard(
            AppIcons.Notifications,
            stringResource(R.string.notifications_title),
            Modifier.weight(1f).fillMaxHeight(),
        ) {
            navigate(NotificationsRoute)
        }
    }
    ListGroup(title = stringResource(R.string.home_manage)) {
        NavigationRow(
            stringResource(R.string.preferences_title),
            onClick = { navigate(WatchPreferencesRoute) },
            icon = AppIcons.Tune,
            summary = stringResource(R.string.home_preferences_summary),
        )
        NavigationRow(
            stringResource(R.string.find_title),
            onClick = { navigate(FindDevicesRoute) },
            icon = AppIcons.FindDevices,
            summary = stringResource(R.string.home_find_summary),
        )
        NavigationRow(
            stringResource(R.string.media_title),
            onClick = { navigate(MediaRoute) },
            icon = AppIcons.Music,
            summary = stringResource(R.string.home_media_summary),
        )
        NavigationRow(
            stringResource(R.string.calling_title),
            onClick = { navigate(CallingRoute) },
            icon = AppIcons.Call,
            summary = stringResource(R.string.home_calling_summary),
        )
        NavigationRow(
            stringResource(R.string.sos_title),
            onClick = { navigate(SosRoute()) },
            icon = AppIcons.Sos,
            summary = stringResource(R.string.home_sos_summary),
        )
        NavigationRow(
            stringResource(R.string.toolbox_title),
            onClick = { navigate(ToolboxRoute) },
            icon = AppIcons.Advanced,
            summary = stringResource(R.string.home_advanced_summary),
        )
        NavigationRow(
            stringResource(R.string.connection_title),
            onClick = { navigate(ConnectionRoute) },
            icon = AppIcons.Bluetooth,
            summary = stringResource(state.watch.connection.phase.label),
        )
    }
}

@Composable
private fun StepsCard(steps: Int, goal: Int, stale: Boolean) {
    val locale = currentLocale()
    Card(
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            Modifier.fillMaxWidth().animateContentSize().padding(16.dp),
            verticalArrangement = GrowingGaps(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppIcon(
                    AppIcons.Steps,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.home_steps_today),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                pluralStringResource(R.plurals.value_steps, steps, formatNumber(steps, locale)),
                style = MaterialTheme.typography.headlineSmall,
            )
            Reveal(goal > 0, spacing = 8.dp) {
                ProgressBar((steps.toFloat() / goal).coerceIn(0f, 1f))
                Text(
                    stringResource(
                        if (stale) R.string.home_steps_goal_stale else R.string.home_steps_goal,
                        formatNumber(goal, locale),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ShortcutCard(
    @DrawableRes icon: Int,
    title: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), Arrangement.spacedBy(8.dp)) {
            AppIcon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
    }
}
