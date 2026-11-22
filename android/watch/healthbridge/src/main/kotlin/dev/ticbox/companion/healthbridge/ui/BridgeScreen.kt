package dev.ticbox.companion.healthbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CheckboxButton
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.dynamicColorScheme
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight

/** The phone app's dark palette, on the black a watch screen expects. */
private val BridgeColors =
    ColorScheme(
        primary = Color(0xFFA8CCDA),
        primaryDim = Color(0xFF8DB1BE),
        primaryContainer = Color(0xFF284B57),
        onPrimary = Color(0xFF0E3540),
        onPrimaryContainer = Color(0xFFC3E8F7),
        secondary = Color(0xFFBEC8CD),
        secondaryDim = Color(0xFFA3ADB1),
        secondaryContainer = Color(0xFF3E484C),
        onSecondary = Color(0xFF283236),
        onSecondaryContainer = Color(0xFFDAE4E9),
        tertiary = Color(0xFFF0BE7F),
        tertiaryDim = Color(0xFFD2A367),
        tertiaryContainer = Color(0xFF62400C),
        onTertiary = Color(0xFF462A00),
        onTertiaryContainer = Color(0xFFFFDDB6),
        surfaceContainerLow = Color(0xFF191C1D),
        surfaceContainer = Color(0xFF1D2021),
        surfaceContainerHigh = Color(0xFF272A2C),
        onSurface = Color(0xFFE1E3E4),
        onSurfaceVariant = Color(0xFFC1C8CB),
        outline = Color(0xFF8B9295),
        outlineVariant = Color(0xFF41484B),
    )

/** Follows the watch face's colours where the system offers them (Wear OS 6 and later). */
@Composable
internal fun BridgeTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = remember(context) { dynamicColorScheme(context) } ?: BridgeColors
    MaterialTheme(colorScheme = colors, content = content)
}

/** What a list row needs to shrink and fade at the round screen's edges. */
internal class BridgeRow(
    val item: TransformingLazyColumnItemScope,
    val modifier: Modifier,
    val transformation: SurfaceTransformation,
)

internal class BridgeList(
    private val scope: TransformingLazyColumnScope,
    private val spec: TransformationSpec,
) {
    fun row(key: Any? = null, content: @Composable BridgeRow.() -> Unit) {
        scope.item(key = key) {
            val row =
                BridgeRow(
                    item = this,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                )
            row.content()
        }
    }
}

/**
 * A scrolling page shaped for a round screen, with the crown scrolling it. [edgeButton] hugs the
 * bottom of the screen and holds the page's main action.
 */
@Composable
internal fun BridgeScreen(
    state: TransformingLazyColumnState = rememberTransformingLazyColumnState(),
    edgeButton: (@Composable BoxScope.() -> Unit)? = null,
    content: BridgeList.() -> Unit,
) {
    val spec = rememberTransformationSpec()
    val overscroll = rememberOverscrollEffect()
    val list: @Composable BoxScope.(PaddingValues) -> Unit = { padding ->
        TransformingLazyColumn(
            state = state,
            contentPadding = padding,
            overscrollEffect = overscroll,
        ) {
            BridgeList(this, spec).content()
        }
    }
    AppScaffold {
        if (edgeButton != null) {
            ScreenScaffold(
                scrollState = state,
                edgeButton = edgeButton,
                overscrollEffect = overscroll,
                content = list,
            )
        } else {
            ScreenScaffold(scrollState = state, overscrollEffect = overscroll, content = list)
        }
    }
}

/** The page's title, at the top of its list. */
@Composable
internal fun BridgeRow.Title(text: String) {
    val padded =
        with(item) {
            modifier.minimumVerticalContentPadding(
                ListHeaderDefaults.minimumTopListContentPadding,
                ListHeaderDefaults.minimumBottomListContentPadding,
            )
        }
    ListHeader(modifier = padded, transformation = transformation) {
        Text(text, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun BridgeRow.Section(text: String) {
    ListSubHeader(modifier = modifier, transformation = transformation) { Text(text) }
}

/** A short explanation. Centred, so a round screen's edges cut off as little as possible. */
@Composable
internal fun BridgeRow.Note(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text = text,
        modifier =
            modifier.padding(horizontal = 8.dp, vertical = 4.dp).graphicsLayer {
                with(transformation) {
                    applyContainerTransformation()
                    applyContentTransformation()
                }
            },
        color = color,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
internal fun BridgeRow.Toggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
) {
    SwitchButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        transformation = transformation,
        secondaryLabel = summary?.let { { Text(it) } },
        label = { Text(label) },
    )
}

@Composable
internal fun BridgeRow.Check(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    CheckboxButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        transformation = transformation,
        label = { Text(label) },
    )
}

@Composable
internal fun BridgeRow.Action(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.filledTonalButtonColors(),
        transformation = transformation,
        label = { Text(label) },
    )
}

/** A labelled text field. Wear's Material components have none; typing goes to the system's IME. */
@Composable
internal fun BridgeRow.Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    hint: String? = null,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val focus = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            modifier.graphicsLayer {
                with(transformation) {
                    applyContainerTransformation()
                    applyContentTransformation()
                }
            },
        enabled = enabled,
        textStyle = typography.labelMedium.copy(color = colors.onSurface),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        singleLine = true,
        cursorBrush = SolidColor(colors.primary),
        decorationBox = { field ->
            Column(
                Modifier.fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(colors.surfaceContainer)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(label, color = colors.onSurfaceVariant, style = typography.labelSmall)
                Box {
                    if (value.isEmpty()) {
                        Text(placeholder, color = colors.outline, style = typography.labelMedium)
                    }
                    field()
                }
                if (hint != null) {
                    Text(hint, color = colors.onSurfaceVariant, style = typography.labelSmall)
                }
            }
        },
    )
}
