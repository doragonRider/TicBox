package dev.ticbox.companion.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ticbox.companion.ui.designsystem.AppIcon

/** Height of the Material 3 navigation bar, without the system navigation inset. */
val NavigationBarHeight = 80.dp

/**
 * The start page of a tab, with the navigation bar. The bar belongs to the page rather than the app
 * shell, so a page opened on top covers it and going back reveals it together with the page.
 */
@Composable
fun TabPage(
    tab: TopLevelDestination,
    onSelect: (TopLevelDestination) -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        bottomBar = { AppNavigationBar(tab, onSelect) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding)) { content() }
    }
}

@Composable
private fun AppNavigationBar(
    current: TopLevelDestination,
    onSelect: (TopLevelDestination) -> Unit,
) {
    NavigationBar(Modifier.testTag("main_navigation")) {
        TopLevelDestination.entries.forEach { tab ->
            val selected = tab == current
            NavigationBarItem(
                selected = selected,
                onClick = { if (!selected) onSelect(tab) },
                icon = {
                    AppIcon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null)
                },
                label = { Text(stringResource(tab.label)) },
                modifier = Modifier.testTag("tab_" + tab.name),
            )
        }
    }
}
