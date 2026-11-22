package dev.ticbox.companion.ui.toolbox

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.AppIcon
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.BottomNotice
import dev.ticbox.companion.ui.designsystem.FadeSwitch
import dev.ticbox.companion.ui.designsystem.GrowingGaps
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Every toolbox page shares this one, kept by the toolbox's navigation graph. */
@Composable
internal fun toolboxViewModel(graph: ViewModelStoreOwner): ToolboxViewModel =
    companionViewModel(graph) { ToolboxViewModel(it) }

/**
 * Why a tool's controls are grey, along the bottom of its page. Back is the way to setting one up.
 */
@Composable
internal fun ToolNeedsBackend(
    visible: Boolean,
    onSetUp: () -> Unit,
    deviceChanged: Boolean = false,
) {
    AnimatedVisibility(
        visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        BottomNotice(
            if (deviceChanged) AppIcons.Warning else AppIcons.Info,
            stringResource(
                if (deviceChanged) R.string.toolbox_device_changed
                else R.string.toolbox_needs_backend
            ),
            stringResource(R.string.toolbox_set_up),
            onSetUp,
            if (deviceChanged) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer,
            if (deviceChanged) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** A package name has no spaces, so without this a line ends wherever it happens to fill up. */
internal fun String.breaksAtDots(): String = replace(".", ".\u200B")

/** A tool's first screen: what it does, and the one thing to press to begin. */
@Composable
internal fun ToolStart(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    enabled: Boolean = true,
    onAction: () -> Unit = {},
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppIcon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint =
                if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null) {
            Button(onClick = onAction, enabled = enabled) { Text(actionLabel) }
        }
    }
}

/** The scalloped badge behind the toolbox's icons. */
@Composable
internal fun ToolBadge(
    @DrawableRes icon: Int,
    container: Color,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    Box(
        modifier.size(size).background(container, ScallopShape),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(icon, contentDescription = null, Modifier.size(size / 2), tint = tint)
    }
}

internal val ScallopShape = GenericShape { size, _ ->
    val outer = min(size.width, size.height) / 2
    (0..SCALLOP_STEPS).forEach { step ->
        val angle = 2 * PI * step / SCALLOP_STEPS
        val radius = outer * (0.94f + 0.06f * cos(SCALLOP_LOBES * angle).toFloat())
        val x = size.width / 2 + radius * cos(angle).toFloat()
        val y = size.height / 2 + radius * sin(angle).toFloat()
        if (step == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private const val SCALLOP_LOBES = 9
private const val SCALLOP_STEPS = 180

/** A one-line welcome at the top of a tool that opens straight into its controls. */
@Composable
internal fun ToolHero(@DrawableRes icon: Int, text: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(36.dp),
        color = colors.tertiaryContainer,
    ) {
        Row(
            Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ToolBadge(
                icon,
                container = colors.onTertiaryContainer,
                tint = colors.tertiaryContainer,
                size = 56.dp,
            )
            Text(
                text,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onTertiaryContainer,
            )
        }
    }
}

/** A titled block of controls inside a tool. */
@Composable
internal fun ToolPanel(
    @DrawableRes icon: Int,
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = colors.surfaceContainerLow,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = GrowingGaps(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    Modifier.size(48.dp).background(colors.surface, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    AppIcon(icon, contentDescription = null, tint = colors.primary)
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    body?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
            content()
        }
    }
}

/** A row of the toolbox's long lists, as a card lined up with everything else on the page. */
@Composable
internal fun ToolListItem(
    headline: @Composable () -> Unit,
    supporting: @Composable () -> Unit,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = headline,
        supportingContent = supporting,
        leadingContent = leading,
        trailingContent = trailing,
        colors =
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick),
    )
}

/** A pill button with an icon, filled for the main action and tonal for the others. */
@Composable
internal fun ToolButton(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tonal: Boolean = false,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
) {
    val base =
        if (tonal) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.buttonColors()
    val container by
        animateColorAsState(
            if (enabled) base.containerColor else base.disabledContainerColor,
            label = "button_container",
        )
    val foreground by
        animateColorAsState(
            if (enabled) base.contentColor else base.disabledContentColor,
            label = "button_content",
        )
    Button(
        onClick,
        modifier,
        enabled,
        colors = ButtonColors(container, foreground, container, foreground),
        contentPadding = contentPadding,
    ) {
        Crossfade(icon, label = "button_icon") { AppIcon(it, contentDescription = null) }
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        FadeSwitch(label) {
            Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}
