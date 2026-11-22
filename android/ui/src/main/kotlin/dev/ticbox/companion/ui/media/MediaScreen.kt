package dev.ticbox.companion.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.api.MediaControlsIssue
import dev.ticbox.companion.api.MediaControlsState
import dev.ticbox.companion.api.MediaSessionSummary
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.LocalSystemActions
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.AppIcon
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.BannerTone
import dev.ticbox.companion.ui.designsystem.EmptyState
import dev.ticbox.companion.ui.designsystem.FadeSwitch
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.PageColumn
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.RadioRow
import dev.ticbox.companion.ui.designsystem.Reveal
import dev.ticbox.companion.ui.designsystem.RevealValue
import dev.ticbox.companion.ui.designsystem.StatusBanner
import dev.ticbox.companion.ui.designsystem.SwitchRow
import kotlinx.coroutines.flow.StateFlow

class MediaViewModel(dependencies: CompanionDependencies) : SessionViewModel(dependencies) {
    val state: StateFlow<MediaControlsState?> =
        sessionState(
            current = { it.services.media.state.value },
            updates = { it.services.media.state },
        )

    fun setEnabled(enabled: Boolean) =
        command(ENABLED) { session ->
            session.services.media.setEnabled(enabled)
            null
        }

    fun setAutoLaunch(enabled: Boolean) =
        command(AUTO_LAUNCH) { session ->
            session.services.media.setAutoLaunch(enabled)
            null
        }

    fun setPreferred(packageName: String?) =
        command(PREFERRED) { session ->
            session.services.media.setPreferredPackage(packageName)
            null
        }

    companion object {
        const val ENABLED = "media_enabled"
        const val AUTO_LAUNCH = "media_auto_launch"
        const val PREFERRED = "media_preferred"
    }
}

/** One entry per app: the selected session, else a playing one, else the first. */
internal fun MediaControlsState.appSessions(): List<MediaSessionSummary> =
    sessions
        .groupBy { it.packageName }
        .values
        .map { group ->
            group.firstOrNull { it.id == selectedSessionId }
                ?: group.firstOrNull { it.playing }
                ?: group.first()
        }

@Composable
fun MediaScreen(onBack: () -> Unit) {
    val viewModel = companionViewModel { MediaViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val actions = LocalSystemActions.current
    PageScaffold(stringResource(R.string.media_title), onBack = onBack) { padding ->
        val current = state
        if (current == null) {
            EmptyState(
                AppIcons.Music,
                stringResource(R.string.no_watch_title),
                stringResource(R.string.no_watch_body),
                Modifier.padding(padding),
            )
            return@PageScaffold
        }
        val apps = current.appSessions()
        PageColumn(padding) {
            Reveal(!current.accessGranted) {
                StatusBanner(
                    stringResource(R.string.media_access_required),
                    tone = BannerTone.Warning,
                    actionLabel = stringResource(R.string.notifications_grant_access),
                    onAction = actions::openNotificationAccess,
                )
            }
            NowPlaying(
                apps.firstOrNull { it.id == current.selectedSessionId }
                    ?: apps.firstOrNull { it.playing }
            )
            ListGroup {
                SwitchRow(
                    stringResource(R.string.media_enable),
                    checked = current.settings.enabled,
                    onCheckedChange = viewModel::setEnabled,
                    summary = stringResource(R.string.media_enable_summary),
                    enabled = MediaViewModel.ENABLED !in pending,
                )
                SwitchRow(
                    stringResource(R.string.media_auto_launch),
                    checked = current.settings.autoLaunch,
                    onCheckedChange = viewModel::setAutoLaunch,
                    summary = stringResource(R.string.media_auto_launch_summary),
                    enabled = current.settings.enabled && MediaViewModel.AUTO_LAUNCH !in pending,
                )
            }
            ListGroup(Modifier.selectableGroup(), title = stringResource(R.string.media_source)) {
                val enabled = current.settings.enabled && MediaViewModel.PREFERRED !in pending
                RadioRow(
                    stringResource(R.string.media_source_automatic),
                    selected = current.settings.preferredPackage == null,
                    onClick = { viewModel.setPreferred(null) },
                    summary = stringResource(R.string.media_source_automatic_summary),
                    enabled = enabled,
                )
                apps.forEach { session ->
                    RadioRow(
                        session.applicationLabel,
                        selected = current.settings.preferredPackage == session.packageName,
                        onClick = { viewModel.setPreferred(session.packageName) },
                        summary = session.title,
                        enabled = enabled,
                    )
                }
            }
            RevealValue(current.lastIssue) { issue ->
                StatusBanner(
                    stringResource(
                        when (issue) {
                            MediaControlsIssue.SOURCE -> R.string.media_issue_source
                            MediaControlsIssue.STORAGE -> R.string.media_issue_storage
                            MediaControlsIssue.PUBLISH -> R.string.media_issue_publish
                            MediaControlsIssue.COMMAND -> R.string.media_issue_command
                            MediaControlsIssue.MALFORMED_MESSAGE -> R.string.media_issue_message
                            MediaControlsIssue.RESOURCE_LIMIT -> R.string.media_issue_limit
                        }
                    ),
                    tone = BannerTone.Warning,
                )
            }
        }
    }
}

@Composable
private fun NowPlaying(session: MediaSessionSummary?) {
    Card(
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppIcon(
                if (session?.playing == true) AppIcons.Playing else AppIcons.Paused,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.media_now_playing),
                    style = MaterialTheme.typography.labelMedium,
                )
                FadeSwitch(session, key = { it?.let { song -> song.title to song.artist } }) { shown
                    ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (shown == null) {
                            Text(
                                stringResource(R.string.media_nothing_playing),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        } else {
                            Text(
                                shown.title ?: shown.applicationLabel,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val source = listOfNotNull(shown.artist, shown.applicationLabel)
                            Text(
                                source.joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
