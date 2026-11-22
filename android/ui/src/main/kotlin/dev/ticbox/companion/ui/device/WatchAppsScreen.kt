package dev.ticbox.companion.ui.device

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.api.ConnectionPhase
import dev.ticbox.companion.api.DeviceSettingsState
import dev.ticbox.companion.api.WatchInstalledApp
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.ActionIcon
import dev.ticbox.companion.ui.designsystem.AppIcon
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.EmptyState
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.PageList
import dev.ticbox.companion.ui.designsystem.PageScaffold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

data class WatchAppsUiState(val device: DeviceSettingsState, val linkReady: Boolean) {
    val apps: List<WatchInstalledApp>
        get() = device.apps.sortedBy { (it.label ?: it.packageName).lowercase() }
}

class WatchAppsViewModel(dependencies: CompanionDependencies) : SessionViewModel(dependencies) {
    private val icons = LruCache<String, ImageBitmap>(64)

    val state: StateFlow<WatchAppsUiState?> =
        sessionState(
            current = {
                WatchAppsUiState(
                    it.services.deviceSettings.state.value,
                    it.client.state.value.connection.phase == ConnectionPhase.LINK_READY,
                )
            },
            updates = { session ->
                combine(session.services.deviceSettings.state, session.client.state) { device, watch
                    ->
                    WatchAppsUiState(device, watch.connection.phase == ConnectionPhase.LINK_READY)
                }
            },
        )

    fun refresh() = command(REFRESH) { it.services.deviceSettings.refresh().message() }

    /** Opens the watch's own uninstall confirmation; the app leaves the list once it is gone. */
    fun uninstall(app: WatchInstalledApp) =
        command(app.packageName) {
            it.services.deviceSettings.requestUninstall(app.packageName).message(opened = true)
        }

    suspend fun icon(app: WatchInstalledApp): ImageBitmap? {
        icons.get(app.packageName)?.let {
            return it
        }
        val client = session.value?.services?.deviceSettings ?: return null
        return try {
            val bytes = client.appIcon(app.packageName) ?: return null
            withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
                ?.also { icons.put(app.packageName, it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val REFRESH = "watch_apps_refresh"
    }
}

@Composable
fun WatchAppsScreen(onBack: () -> Unit) {
    val viewModel = companionViewModel { WatchAppsViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    PageScaffold(
        stringResource(R.string.watch_apps_title),
        onBack = onBack,
        actions = {
            ActionIcon(
                AppIcons.Refresh,
                stringResource(R.string.preferences_refresh),
                viewModel::refresh,
                enabled = state?.linkReady == true && WatchAppsViewModel.REFRESH !in pending,
            )
        },
    ) { padding ->
        val current = state
        if (current == null || current.apps.isEmpty()) {
            EmptyState(
                AppIcons.Apps,
                stringResource(
                    if (current == null) R.string.no_watch_title
                    else R.string.watch_apps_empty_title
                ),
                stringResource(
                    if (current == null) R.string.no_watch_body else R.string.watch_apps_empty_body
                ),
                Modifier.padding(padding),
            )
            return@PageScaffold
        }
        PageList(padding, spacing = 8.dp) {
            if (current.device.appDirectoryLimited) {
                item(key = "limited") {
                    FootnoteText(stringResource(R.string.watch_apps_limited))
                }
            }
            items(current.apps, key = { it.packageName }) { app ->
                AppRow(
                    app,
                    uninstallEnabled = current.linkReady && app.packageName !in pending,
                    loadIcon = viewModel::icon,
                    onUninstall = { viewModel.uninstall(app) },
                )
            }
            item(key = "note") { FootnoteText(stringResource(R.string.watch_apps_note)) }
        }
    }
}

@Composable
private fun AppRow(
    app: WatchInstalledApp,
    uninstallEnabled: Boolean,
    loadIcon: suspend (WatchInstalledApp) -> ImageBitmap?,
    onUninstall: () -> Unit,
) {
    val icon by
        produceState<ImageBitmap?>(null, app.packageName, app.iconDigest) {
            value = loadIcon(app)
        }
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        ListItem(
            headlineContent = {
                Text(app.label ?: app.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Text(app.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            leadingContent = {
                Crossfade(icon, label = "app_icon") { image ->
                    if (image != null) {
                        Image(image, contentDescription = null, modifier = Modifier.size(40.dp))
                    } else {
                        // As big as the icon that may replace it, so the row's text stays put.
                        AppIcon(
                            AppIcons.Apps,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp).padding(8.dp),
                        )
                    }
                }
            },
            trailingContent = {
                IconButton(onClick = onUninstall, enabled = uninstallEnabled) {
                    AppIcon(
                        AppIcons.Delete,
                        stringResource(
                            R.string.watch_apps_uninstall,
                            app.label ?: app.packageName,
                        ),
                    )
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}
