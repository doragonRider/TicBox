package dev.ticbox.companion.ui.notifications

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import dev.ticbox.companion.api.NotificationFilterMode
import dev.ticbox.companion.api.NotificationSyncIssue
import dev.ticbox.companion.ui.LocalSystemActions
import dev.ticbox.companion.ui.PhoneApp
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.ActionIcon
import dev.ticbox.companion.ui.designsystem.AppIcon
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.BannerTone
import dev.ticbox.companion.ui.designsystem.EmptyState
import dev.ticbox.companion.ui.designsystem.FadeSwitch
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.NavigationRow
import dev.ticbox.companion.ui.designsystem.PageList
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.Reveal
import dev.ticbox.companion.ui.designsystem.SectionTitle
import dev.ticbox.companion.ui.designsystem.StatusBanner
import dev.ticbox.companion.ui.designsystem.SwitchRow
import dev.ticbox.companion.ui.navigation.WatchModuleRoute
import kotlinx.coroutines.delay

/** About how long a row takes to unroll, after which it's worth scrolling all of it into view. */
private const val UNROLL_MILLIS = 320L

@Composable
fun NotificationsScreen(onBack: () -> Unit, navigate: (Any) -> Unit) {
    val viewModel = companionViewModel { NotificationsViewModel(it, createSavedStateHandle()) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val change by viewModel.pendingChange.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var expandedApp by rememberSaveable { mutableStateOf<String?>(null) }
    val needsAlertsRead = state?.alerts?.needsRead == true
    LaunchedEffect(needsAlertsRead) { if (needsAlertsRead) viewModel.readAlerts() }
    PageScaffold(
        stringResource(R.string.notifications_title),
        onBack = onBack,
        actions = {
            if (state != null) {
                ActionIcon(
                    AppIcons.Filter,
                    stringResource(R.string.notifications_filter),
                    { showFilters = true },
                )
                Box {
                    ActionIcon(AppIcons.More, stringResource(R.string.action_more), { menu = true })
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.notifications_suggest)) },
                            leadingIcon = { AppIcon(AppIcons.Suggest, null) },
                            onClick = {
                                menu = false
                                viewModel.proposeSuggestion()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.notifications_copy_list)) },
                            leadingIcon = { AppIcon(AppIcons.Copy, null) },
                            onClick = {
                                menu = false
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText("packages", viewModel.exportList())
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.notifications_import_list)) },
                            leadingIcon = { AppIcon(AppIcons.Paste, null) },
                            onClick = {
                                menu = false
                                val text =
                                    clipboard.primaryClip
                                        ?.takeIf { it.itemCount > 0 }
                                        ?.getItemAt(0)
                                        ?.coerceToText(context)
                                viewModel.proposeImport(text?.toString().orEmpty())
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.notifications_reload_apps)) },
                            leadingIcon = { AppIcon(AppIcons.Refresh, null) },
                            onClick = {
                                menu = false
                                viewModel.reloadApps()
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val current = state
        if (current == null) {
            EmptyState(
                AppIcons.Notifications,
                stringResource(R.string.no_watch_title),
                stringResource(R.string.no_watch_body),
                Modifier.padding(padding),
            )
            return@PageScaffold
        }
        val alertsFor: (String) -> AppAlertControls? = { name ->
            if (!current.alerts.applied || !current.sends(name)) null
            else
                AppAlertControls(
                    settings = current.settings.alertsFor(name),
                    soundOn = current.settings.soundEnabled,
                    vibrationOn = current.settings.vibrationEnabled,
                    expanded = expandedApp == name,
                    onExpandedChange = { open -> expandedApp = name.takeIf { open } },
                    onSound = { viewModel.setAppSound(name, it) },
                    onVibration = { viewModel.setAppVibration(name, it) },
                )
        }
        PageList(padding, spacing = 4.dp) {
            item(key = "sync") { SyncSection(current, pending, viewModel) }
            item(key = "alerts") {
                FadeSwitch(current.alerts.applied, Modifier.padding(top = 12.dp)) { applied ->
                    if (applied) {
                        AlertTiles(
                            soundOn = current.settings.soundEnabled,
                            vibrationOn = current.settings.vibrationEnabled,
                            enabled = current.settings.enabled,
                            onSound = viewModel::setSoundEnabled,
                            onVibration = viewModel::setVibrationEnabled,
                        )
                    } else {
                        AlertsSetupRow(current.alerts.setupStarted) { navigate(WatchModuleRoute) }
                    }
                }
            }
            item(key = "mode") {
                ListGroup(Modifier.padding(vertical = 12.dp)) {
                    NavigationRow(
                        stringResource(R.string.notifications_new_apps),
                        onClick = { showFilters = true },
                        icon =
                            if (current.settings.mode == NotificationFilterMode.BLOCKLIST)
                                AppIcons.Notifications
                            else AppIcons.Block,
                        summary =
                            stringResource(
                                if (current.settings.mode == NotificationFilterMode.BLOCKLIST)
                                    R.string.notifications_new_apps_send
                                else R.string.notifications_new_apps_mute
                            ),
                    )
                }
            }
            stickyHeader(key = "search") { SearchHeader(current, pending, viewModel) }
            when (current.apps) {
                InstalledApps.Loading ->
                    item(key = "loading") {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    }
                InstalledApps.Failed ->
                    item(key = "failed") {
                        StatusBanner(
                            stringResource(R.string.notifications_apps_failed),
                            tone = BannerTone.Error,
                            actionLabel = stringResource(R.string.action_try_again),
                            onAction = viewModel::reloadApps,
                        )
                    }
                is InstalledApps.Loaded ->
                    if (current.visible.isEmpty() && current.missing.isEmpty()) {
                        item(key = "none") {
                            Text(
                                stringResource(R.string.notifications_no_match),
                                Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
            }
            items(current.visible, key = { it.packageName }) { app ->
                AppRow(
                    app,
                    sends = current.sends(app.packageName),
                    enabled =
                        "app:${app.packageName}" !in pending &&
                            NotificationsViewModel.FILTER !in pending,
                    loadIcon = viewModel::icon,
                    alerts = alertsFor(app.packageName),
                ) {
                    viewModel.setSends(app.packageName, it)
                    if (!it && expandedApp == app.packageName) expandedApp = null
                }
            }
            if (current.missing.isNotEmpty() && current.apps is InstalledApps.Loaded) {
                item(key = "missing_title") {
                    SectionTitle(
                        stringResource(R.string.notifications_not_installed),
                        Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                }
                items(current.missing, key = { "missing_$it" }) { name ->
                    AppRow(
                        PhoneApp(name, name),
                        sends = current.sends(name),
                        enabled =
                            "app:$name" !in pending && NotificationsViewModel.FILTER !in pending,
                        loadIcon = { null },
                        alerts = alertsFor(name),
                    ) {
                        viewModel.setSends(name, it)
                        if (!it && expandedApp == name) expandedApp = null
                    }
                }
            }
        }
    }
    if (showFilters) {
        state?.let { current ->
            NotificationFilterSheet(
                mode = current.settings.mode,
                filters = current.filters,
                modeEnabled = NotificationsViewModel.FILTER !in pending,
                onMode = viewModel::setMode,
                onFilters = viewModel::updateFilters,
                onDismiss = { showFilters = false },
            )
        }
    }
    change?.let { proposed ->
        AlertDialog(
            onDismissRequest = viewModel::dismissChange,
            title = { Text(stringResource(R.string.notifications_change_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        pluralStringResource(
                            R.plurals.notifications_change_added,
                            proposed.added,
                            proposed.added,
                        )
                    )
                    Text(
                        pluralStringResource(
                            R.plurals.notifications_change_removed,
                            proposed.removed,
                            proposed.removed,
                        )
                    )
                    if (proposed.ignored > 0) {
                        Text(
                            pluralStringResource(
                                R.plurals.notifications_change_ignored,
                                proposed.ignored,
                                proposed.ignored,
                            )
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmChange) {
                    Text(stringResource(R.string.action_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissChange) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SyncSection(
    state: NotificationsUiState,
    pending: Set<Any>,
    viewModel: NotificationsViewModel,
) {
    val actions = LocalSystemActions.current
    val sync = state.sync
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ListGroup {
            SwitchRow(
                stringResource(R.string.notifications_send),
                checked = state.settings.enabled,
                onCheckedChange = viewModel::setEnabled,
                summary = stringResource(R.string.notifications_send_summary),
                enabled = NotificationsViewModel.ENABLED !in pending,
            )
        }
        when {
            !sync.accessGranted ->
                StatusBanner(
                    stringResource(R.string.notifications_access_required),
                    tone = BannerTone.Warning,
                    actionLabel = stringResource(R.string.notifications_grant_access),
                    onAction = actions::openNotificationAccess,
                )
            !sync.listenerConnected ->
                StatusBanner(
                    stringResource(R.string.notifications_listener_waiting),
                    actionLabel = stringResource(R.string.notifications_recheck),
                    onAction = viewModel::resync,
                )
            sync.lastIssue == NotificationSyncIssue.STORAGE ->
                StatusBanner(
                    stringResource(R.string.notifications_storage_failed),
                    tone = BannerTone.Error,
                )
            sync.pendingRemovalCount > 0 || sync.lastIssue != null ->
                StatusBanner(
                    stringResource(R.string.notifications_sync_issue),
                    tone = BannerTone.Warning,
                    actionLabel = stringResource(R.string.notifications_recheck),
                    onAction = viewModel::resync,
                )
        }
    }
}

@Composable
private fun SearchHeader(
    state: NotificationsUiState,
    pending: Set<Any>,
    viewModel: NotificationsViewModel,
) {
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 8.dp)
    ) {
        OutlinedTextField(
            value = state.filters.query,
            onValueChange = { query -> viewModel.updateFilters { it.copy(query = query) } },
            placeholder = {
                Text(
                    stringResource(R.string.notifications_search),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingIcon = { AppIcon(AppIcons.Search, null) },
            trailingIcon = {
                if (state.filters.query.isNotEmpty()) {
                    ActionIcon(
                        AppIcons.Close,
                        stringResource(R.string.action_clear),
                        { viewModel.updateFilters { it.copy(query = "") } },
                    )
                }
            },
            singleLine = true,
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth().testTag("app_search"),
        )
        val loaded = state.apps is InstalledApps.Loaded
        val shown = state.visible.map { it.packageName } + state.missing
        val allSend = shown.isNotEmpty() && shown.all(state::sends)
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                pluralStringResource(R.plurals.notifications_showing, shown.size, shown.size),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { viewModel.setAllVisible(!allSend) },
                enabled = loaded && shown.isNotEmpty() && NotificationsViewModel.FILTER !in pending,
                modifier = Modifier.testTag("apps_select_all"),
            ) {
                Text(
                    stringResource(
                        if (allSend) R.string.notifications_mute_all
                        else R.string.notifications_send_all
                    )
                )
            }
        }
    }
}

@Composable
private fun AppRow(
    app: PhoneApp,
    sends: Boolean,
    enabled: Boolean,
    loadIcon: suspend (String) -> ImageBitmap?,
    alerts: AppAlertControls?,
    onChange: (Boolean) -> Unit,
) {
    val icon by
        produceState<ImageBitmap?>(null, app.packageName) { value = loadIcon(app.packageName) }
    // Still there to see on the way out, after the app stops sending.
    var shownAlerts by remember { mutableStateOf(alerts) }
    if (alerts != null) shownAlerts = alerts
    val expanded = alerts?.expanded == true
    val summary = alerts?.summary()
    val colors = MaterialTheme.colorScheme
    val background by
        animateColorAsState(
            if (expanded) colors.surfaceContainer else colors.surfaceContainer.copy(alpha = 0f),
            label = "app_background",
        )
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(expanded) {
        if (expanded) {
            delay(UNROLL_MILLIS)
            requester.bringIntoView()
        }
    }
    Column(
        Modifier.fillMaxWidth()
            .bringIntoViewRequester(requester)
            .clip(RoundedCornerShape(20.dp))
            .background(background)
    ) {
        Row(
            Modifier.fillMaxWidth()
                .toggleable(
                    sends,
                    enabled = enabled,
                    role = Role.Checkbox,
                    onValueChange = onChange,
                )
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("app_" + app.packageName),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val image = icon
            if (image != null) {
                Image(image, contentDescription = null, modifier = Modifier.size(40.dp))
            } else {
                Box(
                    Modifier.size(40.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    AppIcon(AppIcons.Apps, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FadeSwitch(summary ?: app.packageName) {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedVisibility(
                    alerts != null,
                    enter = fadeIn() + expandHorizontally(),
                    exit = fadeOut() + shrinkHorizontally(),
                ) {
                    shownAlerts?.let {
                        AppAlertButton(
                            stringResource(R.string.notifications_alert_customize, app.label),
                            expanded,
                            customised = summary != null,
                            it.onExpandedChange,
                            Modifier.padding(end = 8.dp).testTag("alerts_" + app.packageName),
                        )
                    }
                }
                Checkbox(checked = sends, onCheckedChange = null, enabled = enabled)
            }
        }
        Reveal(expanded) { shownAlerts?.let { AppAlertPanel(it, app.packageName) } }
    }
}
