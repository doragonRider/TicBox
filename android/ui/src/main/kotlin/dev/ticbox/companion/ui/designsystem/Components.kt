package dev.ticbox.companion.ui.designsystem

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ticbox.companion.ui.R

/** Content is capped on wide screens and centered, with at least this side gutter. */
val PageGutter = 16.dp
val MaxContentWidth = 640.dp
private val GroupShape = RoundedCornerShape(20.dp)

/** As low as it goes with its buttons still the size of a touch. */
private val CompactTopBarHeight = 48.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    /** Off when only part of the page scrolls, as nothing then passes under the title bar. */
    pageScrolls: Boolean = true,
    /** Off when the page takes the whole screen for a while. */
    showTopBar: Boolean = true,
    /** For a page that needs the room more than it needs a large title. */
    compactTopBar: Boolean = false,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = if (pageScrolls) TopAppBarDefaults.pinnedScrollBehavior() else null
    Scaffold(
        modifier =
            if (scrollBehavior != null) modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
            else modifier,
        topBar = {
            AnimatedVisibility(
                showTopBar,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                TopAppBar(
                    title = {
                        Text(
                            title,
                            style =
                                if (compactTopBar) MaterialTheme.typography.titleMedium
                                else LocalTextStyle.current,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = { onBack?.let { BackButton(it) } },
                    actions = actions,
                    expandedHeight =
                        if (compactTopBar) CompactTopBarHeight
                        else TopAppBarDefaults.TopAppBarExpandedHeight,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        bottomBar = bottomBar,
        content = content,
    )
}

/**
 * A bottom sheet whose top stays below the status bar. Left to itself, [ModalBottomSheet] pads its
 * content for the status bar only while its top is under it. That makes it taller, which moves
 * where it rests, so a sheet thrown up that far keeps chasing its resting place and never stops
 * bouncing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBottomSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier =
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = { BottomSheetDefaults.windowInsets.only(WindowInsetsSides.Bottom) },
        content = content,
    )
}

/** A strip along the bottom of a page: what holds for all of it, and what to do about that. */
@Composable
fun BottomNotice(
    @DrawableRes icon: Int,
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Surface(color = container, contentColor = content) {
        Row(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 16.dp, end = 8.dp)
                .then(modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppIcon(icon, contentDescription = null)
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        AppIcon(AppIcons.Back, contentDescription = stringResource(R.string.action_back))
    }
}

@Composable
fun ActionIcon(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, enabled = enabled) { AppIcon(icon, contentDescription = label) }
}

/** Scrolling page body for short pages. It ends above the keyboard, so a field can scroll clear. */
@Composable
fun PageColumn(
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        Modifier.fillMaxSize().consumeWindowInsets(padding).imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier
                .widthIn(max = MaxContentWidth)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = PageGutter, vertical = 8.dp),
            verticalArrangement = GrowingGaps(20.dp),
        ) {
            content()
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Lazy page body for long or growing lists. It ends above the keyboard, like [PageColumn]. */
@Composable
fun PageList(
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    spacing: Dp = 20.dp,
    content: LazyListScope.() -> Unit,
) {
    val direction = LocalLayoutDirection.current
    BoxWithConstraints(modifier.fillMaxSize().consumeWindowInsets(padding).imePadding()) {
        val gutter = maxOf(PageGutter, (maxWidth - MaxContentWidth) / 2)
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = padding.calculateStartPadding(direction) + gutter,
                    end = padding.calculateEndPadding(direction) + gutter,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = { AnimatedItems(this).content() },
        )
    }
}

/** Rows fade in and out and slide to their new places when a page list changes. */
private class AnimatedItems(private val list: LazyListScope) : LazyListScope by list {
    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        list.item(key, contentType) { Box(Modifier.animateItem()) { content() } }
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
    ) {
        list.items(count, key, contentType) { index ->
            Box(Modifier.animateItem()) { itemContent(index) }
        }
    }
}

/**
 * Like [Arrangement.spacedBy], except that something growing from nothing brings its gap in with
 * it. Otherwise everything below a [Reveal] would first jump down by the whole gap.
 */
@Immutable
internal data class GrowingGaps(override val spacing: Dp) : Arrangement.Vertical {
    override fun Density.arrange(totalSize: Int, sizes: IntArray, outPositions: IntArray) {
        val gap = spacing.roundToPx()
        var position = 0
        sizes.forEachIndexed { index, size ->
            outPositions[index] = position
            position += size + minOf(size, gap)
        }
    }
}

/** Unrolls [content] and fades it in, with whatever is below moving along. */
@Composable
fun Reveal(
    visible: Boolean,
    modifier: Modifier = Modifier,
    spacing: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(
        visible,
        modifier,
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
    ) {
        Column(Modifier.fillMaxWidth(), GrowingGaps(spacing), content = content)
    }
}

/** A [Reveal] for something that may be absent, which is still there to see as it leaves. */
@Composable
fun <T : Any> RevealValue(
    value: T?,
    modifier: Modifier = Modifier,
    spacing: Dp = 0.dp,
    content: @Composable ColumnScope.(T) -> Unit,
) {
    var last by remember { mutableStateOf(value) }
    if (value != null) last = value
    Reveal(value != null, modifier, spacing) { last?.let { content(it) } }
}

/**
 * Fades between different layouts as [state] moves to a different [key], with the space they take
 * following along. Each keeps the state it had, so one on its way out doesn't show the next's data.
 */
@Composable
fun <T> FadeSwitch(
    state: T,
    modifier: Modifier = Modifier,
    key: (T) -> Any? = { it },
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable (T) -> Unit,
) {
    AnimatedContent(
        state,
        modifier,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        contentAlignment = contentAlignment,
        label = "fade_switch",
        contentKey = key,
    ) {
        content(it)
    }
}

/** Text that fades to what it says next. */
@Composable
fun FadingText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    FadeSwitch(text, modifier) { Text(it, style = style, color = color) }
}

/** An unmeasured bar that comes and goes along with the work it stands for. */
@Composable
fun BusyBar(busy: Boolean, modifier: Modifier = Modifier) {
    Reveal(busy, modifier) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
}

/**
 * How far along something is, or just that it's busy while that's null. Progress is reported in
 * steps, and the bar travels between them instead of jumping.
 */
@Composable
fun ProgressBar(fraction: Float?, modifier: Modifier = Modifier) {
    val shown by
        animateFloatAsState(
            fraction ?: 0f,
            ProgressIndicatorDefaults.ProgressAnimationSpec,
            label = "progress",
        )
    Crossfade(fraction != null, modifier.fillMaxWidth(), label = "progress_kind") { measured ->
        if (measured) {
            LinearProgressIndicator(progress = { shown }, Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(start = 16.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** A titled card of list rows. */
@Composable
fun ListGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) SectionTitle(title)
        Surface(
            shape = GroupShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 4.dp), content = content)
        }
    }
}

private val rowColors
    @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

/**
 * A label and its value on one line, for long lists of short values. A value too long for that goes
 * on lines of its own under the label, so neither ends up squeezed into a narrow column.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InlineValueRow(title: String, value: String, modifier: Modifier = Modifier) {
    ListItem(
        headlineContent = {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(title, Modifier.padding(end = 16.dp))
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        colors = rowColors,
        modifier = modifier,
    )
}

/** Fades while drawing, so a theme change doesn't recompose every row on every frame. */
@Composable
private fun Modifier.dimmedUnless(enabled: Boolean): Modifier {
    val alpha by animateFloatAsState(if (enabled) 1f else 0.38f, label = "row_enabled")
    return graphicsLayer { this.alpha = alpha }
}

@Composable
private fun RowIcon(@DrawableRes icon: Int?, enabled: Boolean) {
    if (icon == null) return
    AppIcon(
        icon,
        contentDescription = null,
        modifier = Modifier.dimmedUnless(enabled),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RowText(text: String, enabled: Boolean, secondary: Boolean = false) {
    Text(
        text,
        Modifier.dimmedUnless(enabled),
        color =
            if (secondary) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
    )
}

/** Opens another page or a system screen, or runs an action when [trailingIcon] is null. */
@Composable
fun NavigationRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    summary: String? = null,
    enabled: Boolean = true,
    @DrawableRes trailingIcon: Int? = AppIcons.ChevronRight,
    /** For an icon that comes and goes: keeps its place in the row and fades. */
    fadeTrailingIcon: Boolean = false,
    /** A short value that sits on the title's line instead of taking a line of its own. */
    value: String? = null,
) {
    ListItem(
        headlineContent = { RowText(title, enabled) },
        supportingContent =
            summary?.let {
                {
                    AnimatedContent(
                        it,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "row_summary",
                    ) { text ->
                        RowText(text, enabled, secondary = true)
                    }
                }
            },
        leadingContent = icon?.let { { RowIcon(it, enabled) } },
        trailingContent =
            when {
                fadeTrailingIcon -> {
                    {
                        Crossfade(trailingIcon, label = "row_trailing") {
                            if (it != null) RowIcon(it, enabled) else Spacer(Modifier.size(24.dp))
                        }
                    }
                }
                value != null -> {
                    {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                value,
                                Modifier.widthIn(max = 144.dp).dimmedUnless(enabled),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            trailingIcon?.let { RowIcon(it, enabled) }
                        }
                    }
                }
                trailingIcon != null -> {
                    { RowIcon(trailingIcon, enabled) }
                }
                else -> null
            },
        colors = rowColors,
        modifier = modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    )
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
) {
    // A switch often changes what its summary says, and a ListItem going between one line and
    // several moves its switch and changes height in a single frame. Sized like one otherwise.
    Row(
        modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .heightIn(min = if (summary == null) 56.dp else 72.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon, enabled)
        Column(Modifier.weight(1f)) {
            ProvideTextStyle(MaterialTheme.typography.bodyLarge) { RowText(title, enabled) }
            summary?.let { text ->
                ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                    FadeSwitch(text, Modifier.fillMaxWidth()) {
                        RowText(it, enabled, secondary = true)
                    }
                }
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun RadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    /** For the option that leads to a page of its own. */
    @DrawableRes trailingIcon: Int? = null,
) {
    ListItem(
        headlineContent = { RowText(title, enabled) },
        supportingContent =
            summary?.let { text ->
                { FadeSwitch(text) { RowText(it, enabled, secondary = true) } }
            },
        leadingContent = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
        trailingContent = trailingIcon?.let { { RowIcon(it, enabled) } },
        colors = rowColors,
        modifier =
            modifier.selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            ),
    )
}

/** A read-only label and value. */
@Composable
fun ValueRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        leadingContent = icon?.let { { RowIcon(it, enabled = true) } },
        colors = rowColors,
        modifier = modifier,
    )
}

enum class BannerTone {
    /**
     * Something checked out. Green whatever the colour scheme, as a colour alone must carry this.
     */
    Success,
    Info,
    Warning,
    Error,
}

/** An inline notice for something the user should know or fix. Use sparingly. */
@Composable
fun StatusBanner(
    text: String,
    modifier: Modifier = Modifier,
    tone: BannerTone = BannerTone.Info,
    title: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val (container, content) =
        when (tone) {
            BannerTone.Success ->
                if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
                    Color(0xFF285020) to Color(0xFFC0EFB1)
                } else {
                    Color(0xFFC0EFB1) to Color(0xFF002201)
                }
            BannerTone.Info ->
                MaterialTheme.colorScheme.secondaryContainer to
                    MaterialTheme.colorScheme.onSecondaryContainer
            BannerTone.Warning ->
                MaterialTheme.colorScheme.tertiaryContainer to
                    MaterialTheme.colorScheme.onTertiaryContainer
            BannerTone.Error ->
                MaterialTheme.colorScheme.errorContainer to
                    MaterialTheme.colorScheme.onErrorContainer
        }
    val icon =
        when (tone) {
            BannerTone.Success -> AppIcons.CheckCircle
            BannerTone.Info -> AppIcons.Info
            BannerTone.Warning -> AppIcons.Warning
            BannerTone.Error -> AppIcons.Error
        }
    Surface(
        modifier.fillMaxWidth(),
        shape = GroupShape,
        color = animateColorAsState(container, label = "banner_container").value,
        contentColor = animateColorAsState(content, label = "banner_content").value,
    ) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Crossfade(icon, label = "banner_icon") { AppIcon(it, contentDescription = null) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (title != null) {
                        FadingText(title, style = MaterialTheme.typography.titleSmall)
                    }
                    FadingText(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction, modifier = Modifier.align(Alignment.End)) {
                    Text(actionLabel)
                }
            } else {
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** Centered placeholder for an empty or unavailable page. */
@Composable
fun EmptyState(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(64.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                AppIcon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * Small explanatory text under a group, set in as far as the text inside the group. Inside a card
 * or a sheet that already has its own margins, use [CaptionText].
 */
@Composable
fun FootnoteText(text: String, modifier: Modifier = Modifier) {
    CaptionText(text, modifier.padding(horizontal = 16.dp))
}

/** Small explanatory text that lines up with what's above it, such as a progress bar in a card. */
@Composable
fun CaptionText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
