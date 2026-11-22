package dev.ticbox.companion.ui.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ticbox.companion.api.NotificationFilterMode
import dev.ticbox.companion.ui.AppSort
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.designsystem.AppBottomSheet
import dev.ticbox.companion.ui.designsystem.AppIcon
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.RadioRow
import dev.ticbox.companion.ui.designsystem.SectionTitle

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotificationFilterSheet(
    mode: NotificationFilterMode,
    filters: AppFilters,
    modeEnabled: Boolean,
    onMode: (NotificationFilterMode) -> Unit,
    onFilters: ((AppFilters) -> AppFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    AppBottomSheet(onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                stringResource(R.string.notifications_filter),
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            ListGroup(
                Modifier.selectableGroup(),
                title = stringResource(R.string.notifications_new_apps),
            ) {
                RadioRow(
                    stringResource(R.string.notifications_new_apps_send),
                    selected = mode == NotificationFilterMode.BLOCKLIST,
                    onClick = { onMode(NotificationFilterMode.BLOCKLIST) },
                    summary = stringResource(R.string.notifications_mode_blocklist_summary),
                    enabled = modeEnabled,
                )
                RadioRow(
                    stringResource(R.string.notifications_new_apps_mute),
                    selected = mode == NotificationFilterMode.ALLOWLIST,
                    onClick = { onMode(NotificationFilterMode.ALLOWLIST) },
                    summary = stringResource(R.string.notifications_mode_allowlist_summary),
                    enabled = modeEnabled,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(stringResource(R.string.notifications_sort))
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppSort.entries.forEach { sort ->
                        FilterChip(
                            selected = filters.sort == sort,
                            onClick = { onFilters { it.copy(sort = sort) } },
                            label = { Text(stringResource(sort.label)) },
                            leadingIcon = { AppIcon(sort.icon, null, Modifier.size(18.dp)) },
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(stringResource(R.string.notifications_show))
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = filters.showSystem,
                        onClick = { onFilters { it.copy(showSystem = !it.showSystem) } },
                        label = { Text(stringResource(R.string.notifications_show_system)) },
                    )
                    FilterChip(
                        selected = filters.showOffline,
                        onClick = { onFilters { it.copy(showOffline = !it.showOffline) } },
                        label = { Text(stringResource(R.string.notifications_show_offline)) },
                    )
                }
            }
        }
    }
}

private val AppSort.label: Int
    get() =
        when (this) {
            AppSort.SELECTED_FIRST -> R.string.notifications_sort_listed
            AppSort.NAME -> R.string.notifications_sort_name
            AppSort.INSTALL_TIME -> R.string.notifications_sort_installed
        }

private val AppSort.icon: Int
    get() =
        when (this) {
            AppSort.SELECTED_FIRST -> AppIcons.Sort
            AppSort.NAME -> AppIcons.SortByName
            AppSort.INSTALL_TIME -> AppIcons.Schedule
        }
