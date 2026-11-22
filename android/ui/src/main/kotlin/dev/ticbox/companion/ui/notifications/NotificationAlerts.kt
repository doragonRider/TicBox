package dev.ticbox.companion.ui.notifications

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ticbox.companion.api.NotificationAlertMode
import dev.ticbox.companion.api.NotificationAppAlertSettings
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.designsystem.AppIcon
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.FadingText
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.NavigationRow
import dev.ticbox.companion.ui.designsystem.Reveal
import dev.ticbox.companion.ui.designsystem.SectionTitle
import kotlin.math.roundToInt

private const val DIMMED = 0.38f
private val TileRadiusOff = 16.dp
private val TileRadiusOn = 28.dp
private val TrackRadius = 20.dp
private val TrackPadding = 4.dp

/**
 * The firmware rings and vibrates for everything until the module's extension is applied, so these
 * controls would do nothing before that and aren't offered; the row says what it would take.
 */
@Composable
internal fun AlertsSetupRow(setupStarted: Boolean, onOpen: () -> Unit) {
    ListGroup {
        NavigationRow(
            stringResource(R.string.notifications_alerts_row),
            onClick = onOpen,
            icon = AppIcons.VolumeUp,
            summary =
                stringResource(
                    if (setupStarted) R.string.notifications_alerts_row_pending
                    else R.string.notifications_alerts_row_summary
                ),
            modifier = Modifier.testTag("alerts_setup"),
        )
    }
}

/** Two independent global switches: off beats any app's choice, on doesn't force anything. */
@Composable
internal fun AlertTiles(
    soundOn: Boolean,
    vibrationOn: Boolean,
    enabled: Boolean,
    onSound: (Boolean) -> Unit,
    onVibration: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.notifications_alerts_title))
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AlertTile(
                if (soundOn) AppIcons.VolumeUp else AppIcons.VolumeOff,
                stringResource(R.string.notifications_alerts_sound),
                soundOn,
                enabled,
                onSound,
                Modifier.weight(1f).testTag("alerts_sound"),
            )
            AlertTile(
                if (vibrationOn) AppIcons.Vibration else AppIcons.Block,
                stringResource(R.string.notifications_alerts_vibration),
                vibrationOn,
                enabled,
                onVibration,
                Modifier.weight(1f).testTag("alerts_vibration"),
            )
        }
        FootnoteText(stringResource(R.string.notifications_alerts_note))
    }
}

@Composable
private fun AlertTile(
    @DrawableRes icon: Int,
    title: String,
    on: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val container by
        animateColorAsState(
            if (on) colors.primaryContainer else colors.surfaceContainerHigh,
            label = "tile_container",
        )
    val content by
        animateColorAsState(
            if (on) colors.onPrimaryContainer else colors.onSurfaceVariant,
            label = "tile_content",
        )
    val radius by
        animateDpAsState(
            if (on) TileRadiusOn else TileRadiusOff,
            spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow),
            label = "tile_radius",
        )
    val alpha by animateFloatAsState(if (enabled) 1f else DIMMED, label = "tile_alpha")
    Row(
        modifier
            .fillMaxHeight()
            .heightIn(min = 72.dp)
            .graphicsLayer { this.alpha = alpha }
            .clip(RoundedCornerShape(radius))
            .background(container)
            .toggleable(on, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Crossfade(icon, label = "tile_icon") { AppIcon(it, null, tint = content) }
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = content)
            FadingText(
                stringResource(
                    if (on) R.string.notifications_alerts_on else R.string.notifications_alerts_off
                ),
                style = MaterialTheme.typography.bodySmall,
                color = content,
            )
        }
    }
}

/** What a row needs to offer its app's own choices, which keep their place in the settings. */
internal class AppAlertControls(
    val settings: NotificationAppAlertSettings,
    val soundOn: Boolean,
    val vibrationOn: Boolean,
    val expanded: Boolean,
    val onExpandedChange: (Boolean) -> Unit,
    val onSound: (NotificationAlertMode) -> Unit,
    val onVibration: (NotificationAlertMode) -> Unit,
)

@StringRes
private fun soundLabel(mode: NotificationAlertMode): Int =
    when (mode) {
        NotificationAlertMode.FOLLOW_APP -> R.string.notifications_alert_follow_app
        NotificationAlertMode.NEVER -> R.string.notifications_alert_sound_never
        NotificationAlertMode.ALWAYS -> R.string.notifications_alert_sound_always
    }

@StringRes
private fun vibrationLabel(mode: NotificationAlertMode): Int =
    when (mode) {
        NotificationAlertMode.FOLLOW_APP -> R.string.notifications_alert_follow_app
        NotificationAlertMode.NEVER -> R.string.notifications_alert_vibration_never
        NotificationAlertMode.ALWAYS -> R.string.notifications_alert_vibration_always
    }

@DrawableRes
private fun soundIcon(mode: NotificationAlertMode): Int =
    when (mode) {
        NotificationAlertMode.FOLLOW_APP -> AppIcons.Apps
        NotificationAlertMode.NEVER -> AppIcons.VolumeOff
        NotificationAlertMode.ALWAYS -> AppIcons.VolumeUp
    }

@DrawableRes
private fun vibrationIcon(mode: NotificationAlertMode): Int =
    when (mode) {
        NotificationAlertMode.FOLLOW_APP -> AppIcons.Apps
        NotificationAlertMode.NEVER -> AppIcons.Block
        NotificationAlertMode.ALWAYS -> AppIcons.Vibration
    }

/** The app's choices that count right now, or null while it just follows the app. */
@Composable
internal fun AppAlertControls.summary(): String? {
    val sound = settings.sound.takeIf { soundOn && it != NotificationAlertMode.FOLLOW_APP }
    val vibration =
        settings.vibration.takeIf { vibrationOn && it != NotificationAlertMode.FOLLOW_APP }
    val soundText = sound?.let { stringResource(soundLabel(it)) }
    val vibrationText = vibration?.let { stringResource(vibrationLabel(it)) }
    return listOfNotNull(soundText, vibrationText).joinToString(" · ").ifEmpty { null }
}

/** The way into an app's choices, next to the checkbox that decides whether it sends at all. */
@Composable
internal fun AppAlertButton(
    label: String,
    expanded: Boolean,
    customised: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val container by
        animateColorAsState(
            if (expanded) colors.secondaryContainer else colors.secondaryContainer.copy(alpha = 0f),
            label = "tune_container",
        )
    val content by
        animateColorAsState(
            when {
                expanded -> colors.onSecondaryContainer
                customised -> colors.primary
                else -> colors.onSurfaceVariant
            },
            label = "tune_content",
        )
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(container)
            .toggleable(expanded, role = Role.Button, onValueChange = onChange),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(AppIcons.Tune, label, tint = content)
    }
}

@Composable
internal fun AppAlertPanel(controls: AppAlertControls, packageName: String) {
    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AlertDimension(
            stringResource(R.string.notifications_alerts_sound),
            stringResource(R.string.notifications_alert_sound_off),
            controls.settings.sound,
            controls.soundOn,
            ::soundIcon,
            ::soundLabel,
            controls.onSound,
            "alert_sound_$packageName",
        )
        AlertDimension(
            stringResource(R.string.notifications_alerts_vibration),
            stringResource(R.string.notifications_alert_vibration_off),
            controls.settings.vibration,
            controls.vibrationOn,
            ::vibrationIcon,
            ::vibrationLabel,
            controls.onVibration,
            "alert_vibration_$packageName",
        )
    }
}

@Composable
private fun AlertDimension(
    title: String,
    offNote: String,
    selected: NotificationAlertMode,
    enabled: Boolean,
    icon: (NotificationAlertMode) -> Int,
    label: (NotificationAlertMode) -> Int,
    onSelect: (NotificationAlertMode) -> Unit,
    tag: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AlertModeSelector(selected, enabled, icon, label, onSelect, tag)
        Reveal(!enabled) {
            Text(
                offNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One of three equal choices, with a pill that slides to the saved one. It follows the saved value
 * and nothing else, so a failed save leaves it where it was. Placing the pill reads the animation
 * only during layout, which spares the labels a recomposition on every frame.
 */
@Composable
private fun AlertModeSelector(
    selected: NotificationAlertMode,
    enabled: Boolean,
    icon: (NotificationAlertMode) -> Int,
    label: (NotificationAlertMode) -> Int,
    onSelect: (NotificationAlertMode) -> Unit,
    tag: String,
) {
    val modes = NotificationAlertMode.entries
    val position by
        animateFloatAsState(
            modes.indexOf(selected).toFloat(),
            spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
            label = "selector_position",
        )
    val alpha by animateFloatAsState(if (enabled) 1f else DIMMED, label = "selector_alpha")
    val colors = MaterialTheme.colorScheme
    val tileShape = RoundedCornerShape(TrackRadius - TrackPadding)
    Box(
        Modifier.fillMaxWidth()
            .height(IntrinsicSize.Min)
            .graphicsLayer { this.alpha = alpha }
            .background(colors.surfaceContainerHighest, RoundedCornerShape(TrackRadius))
            .padding(TrackPadding)
    ) {
        Box(
            Modifier.fillMaxWidth(1f / modes.size)
                .fillMaxHeight()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        placeable.placeRelative((placeable.width * position).roundToInt(), 0)
                    }
                }
                .background(colors.primaryContainer, tileShape)
        )
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            modes.forEach { mode ->
                val isSelected = mode == selected
                val content by
                    animateColorAsState(
                        if (isSelected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                        label = "selector_content",
                    )
                Column(
                    Modifier.weight(1f)
                        .fillMaxHeight()
                        .clip(tileShape)
                        .selectable(
                            isSelected,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { if (!isSelected) onSelect(mode) },
                        )
                        .padding(horizontal = 4.dp, vertical = 8.dp)
                        .testTag("${tag}_${mode.name}"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    AppIcon(icon(mode), null, Modifier.size(20.dp), tint = content)
                    Text(
                        stringResource(label(mode)),
                        style = MaterialTheme.typography.labelMedium,
                        color = content,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}
