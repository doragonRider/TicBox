package dev.ticbox.companion.ui.toolbox

import android.content.ClipboardManager
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.api.ToolboxTarget
import dev.ticbox.companion.api.ToolboxTerminalOptions
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.common.asString
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.ActionIcon
import dev.ticbox.companion.ui.designsystem.AppIcon
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.DarkPage
import dev.ticbox.companion.ui.designsystem.FadeSwitch
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.RevealValue
import dev.ticbox.companion.ui.designsystem.StatusBanner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop

/** What the terminal page knows: the shell itself is [ShellHost]'s, and outlives the page. */
class TerminalViewModel(
    dependencies: CompanionDependencies,
    private val toolbox: ToolboxViewModel,
) : SessionViewModel(dependencies) {
    private val shell = dependencies.shell
    val state: StateFlow<ShellState> = shell.state
    val display: TerminalDisplay = shell.display

    /** The connection as this page has seen it. */
    private var seen: ToolboxTarget? = null

    init {
        shell.pageOpened()
    }

    fun start() {
        val memory = toolbox.memory()
        val on = seen
        // A shell can change anything, so nothing remembered can be trusted after it's typed into.
        shell.start(on, beforeInput = { memory?.forgetLists() }) { size ->
            toolbox.backend(on).openTerminal(ToolboxTerminalOptions(size = size))
        }
    }

    fun stop() = shell.stop()

    /**
     * A shell ends with the connection it ran on. The page opens one by itself only when there's
     * none to show, so anything after that is someone asking for it.
     */
    suspend fun connectionChanged(next: ToolboxTarget?) {
        val lost = seen != null && next != seen
        seen = next
        val replaced = next != null && shell.state.value.running && shell.target != next
        if (lost || replaced) shell.end()
        else if (next != null && shell.state.value.phase == ShellPhase.IDLE) start()
    }

    override fun onCleared() {
        super.onCleared()
        shell.pageClosed()
    }
}

/** Dark in both themes, since terminal text reads better on it, with the title bar to match. */
@Composable
fun TerminalScreen(graph: ViewModelStoreOwner, onBack: () -> Unit) {
    DarkPage { TerminalPage(graph, onBack) }
}

@Composable
private fun TerminalPage(graph: ViewModelStoreOwner, onBack: () -> Unit) {
    val toolbox = toolboxViewModel(graph)
    val viewModel = companionViewModel { TerminalViewModel(it, toolbox) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val access by toolbox.access.collectAsStateWithLifecycle()
    val connected = access?.connected == true
    val target = access?.target.takeIf { connected }
    LaunchedEffect(target) { viewModel.connectionChanged(target) }
    val context = LocalContext.current
    val paste = {
        context
            .getSystemService(ClipboardManager::class.java)
            .primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?.takeIf { it.isNotEmpty() }
            ?.let(viewModel.display::paste)
        Unit
    }
    PageScaffold(
        stringResource(R.string.tool_terminal_title),
        onBack = onBack,
        pageScrolls = false,
        compactTopBar = true,
        actions = {
            FadeSwitch(state.running) { running ->
                if (running) {
                    ActionIcon(
                        AppIcons.Stop,
                        stringResource(R.string.terminal_stop),
                        onClick = viewModel::stop,
                    )
                } else {
                    ActionIcon(
                        AppIcons.Restart,
                        stringResource(R.string.terminal_restart),
                        onClick = viewModel::start,
                        enabled = connected,
                    )
                }
            }
        },
        bottomBar = { ToolNeedsBackend(!connected, onBack, access?.deviceChanged == true) },
    ) { padding ->
        val colors = MaterialTheme.colorScheme
        val direction = LocalLayoutDirection.current
        val terminalFocus = remember { FocusRequester() }
        // Goes on under the system bars, so that a dark terminal doesn't end in a light strip.
        Surface(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            color = colors.surfaceContainerLowest,
            contentColor = colors.onSurface,
        ) {
            Column(
                Modifier.fillMaxSize()
                    .consumeWindowInsets(padding)
                    .padding(
                        start = padding.calculateStartPadding(direction),
                        end = padding.calculateEndPadding(direction),
                        bottom = padding.calculateBottomPadding(),
                    )
                    .imePadding()
            ) {
                TerminalPanel(
                    viewModel.display,
                    state.phase,
                    connected,
                    paste,
                    terminalFocus,
                    Modifier.weight(1f).fillMaxWidth(),
                )
                RevealValue(state.ended.takeIf { state.phase == ShellPhase.ENDED }) { ended ->
                    StatusBanner(
                        ended.asString(),
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        tone = state.endTone,
                        actionLabel =
                            stringResource(R.string.terminal_restart).takeIf { connected },
                        onAction = viewModel::start,
                    )
                }
                TerminalToolbar(
                    viewModel.display,
                    enabled = state.phase == ShellPhase.READY,
                    terminalFocus,
                )
            }
        }
    }
}

@Composable
private fun TerminalPanel(
    display: TerminalDisplay,
    phase: ShellPhase,
    connected: Boolean,
    onPaste: () -> Unit,
    focus: FocusRequester,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Box(modifier) {
        display.Content(
            background = colors.surfaceContainerLowest,
            foreground = colors.onSurface,
            keyboardEnabled = phase == ShellPhase.READY,
            onPaste = onPaste,
            focus = focus,
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
        )
        val notice =
            when {
                phase == ShellPhase.STARTING -> R.string.terminal_starting
                phase == ShellPhase.IDLE && !connected -> R.string.toolbox_no_backend
                else -> null
            }
        FadeSwitch(
            notice,
            Modifier.align(Alignment.Center).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) { shown ->
            if (shown != null) {
                Text(
                    stringResource(shown),
                    style =
                        MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Two pages: the keys a phone's keyboard doesn't have, and a row for text. The terminal takes keys
 * one at a time with nothing composing, so Chinese has to go through the row.
 */
@Composable
private fun TerminalToolbar(
    display: TerminalDisplay,
    enabled: Boolean,
    terminalFocus: FocusRequester,
) {
    val pager = rememberPagerState { 2 }
    // Not rememberTextFieldState: what's typed for a shell never goes in saved state.
    val text = remember { TextFieldState() }
    val textFocus = remember { FocusRequester() }
    val usable by rememberUpdatedState(enabled)
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }
            .drop(1)
            .collect { page ->
                if (usable) (if (page == 0) terminalFocus else textFocus).requestFocus()
            }
    }
    HorizontalPager(pager, Modifier.fillMaxWidth().height(80.dp)) { page ->
        if (page == 0) {
            TerminalKeys(display, enabled)
        } else {
            TerminalTextRow(text, display, enabled, textFocus)
        }
    }
}

@Composable
private fun TerminalKeys(display: TerminalDisplay, enabled: Boolean) {
    @Composable
    fun RowScope.Named(label: String, key: TerminalKey, repeats: Boolean = false) {
        TerminalKeyCap(enabled, onPress = { display.press(key) }, repeats = repeats) {
            Text(label)
        }
    }
    @Composable
    fun RowScope.Character(label: String, character: Char) {
        TerminalKeyCap(enabled, onPress = { display.press(character) }) { Text(label) }
    }
    @Composable
    fun RowScope.Arrow(key: TerminalKey, degrees: Float, description: Int) {
        TerminalKeyCap(enabled, onPress = { display.press(key) }, repeats = true) {
            AppIcon(
                AppIcons.ArrowUp,
                stringResource(description),
                Modifier.size(18.dp).rotate(degrees),
            )
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f)) {
            Named("ESC", TerminalKey.ESCAPE)
            Character("/", '/')
            Character("―", '-')
            Named("HOME", TerminalKey.HOME)
            Arrow(TerminalKey.UP, 0f, R.string.terminal_key_up)
            Named("END", TerminalKey.END)
            Named("PGUP", TerminalKey.PAGE_UP, repeats = true)
        }
        Row(Modifier.weight(1f)) {
            TerminalKeyCap(enabled, onPress = { display.press(TerminalKey.TAB) }) {
                AppIcon(
                    AppIcons.KeyboardTab,
                    stringResource(R.string.terminal_key_tab),
                    Modifier.size(18.dp),
                )
            }
            TerminalStickyKeyCap("CTRL", display.ctrl, { display.ctrl = it }, enabled)
            TerminalStickyKeyCap("ALT", display.alt, { display.alt = it }, enabled)
            Arrow(TerminalKey.LEFT, -90f, R.string.terminal_key_left)
            Arrow(TerminalKey.DOWN, 180f, R.string.terminal_key_down)
            Arrow(TerminalKey.RIGHT, 90f, R.string.terminal_key_right)
            Named("PGDN", TerminalKey.PAGE_DOWN, repeats = true)
        }
    }
}

/** A tap applies to the next key, holding keeps it on, and either turns it back off. */
@Composable
private fun RowScope.TerminalStickyKeyCap(
    label: String,
    state: StickyKey,
    onChange: (StickyKey) -> Unit,
    enabled: Boolean,
) {
    TerminalKeyCap(
        enabled,
        onPress = { onChange(if (state == StickyKey.OFF) StickyKey.NEXT else StickyKey.OFF) },
        Modifier.semantics { selected = state != StickyKey.OFF },
        onLongPress = {
            onChange(if (state == StickyKey.OFF) StickyKey.LOCKED else StickyKey.OFF)
        },
    ) {
        val color by
            animateColorAsState(
                if (state == StickyKey.OFF) LocalContentColor.current
                else MaterialTheme.colorScheme.primary
            )
        Text(
            label,
            color = color,
            textDecoration = TextDecoration.Underline.takeIf { state == StickyKey.LOCKED },
        )
    }
}

/** Without a border, and sharing the terminal's color, so only what it says stands out. */
@Composable
private fun RowScope.TerminalKeyCap(
    enabled: Boolean,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
    repeats: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val press by rememberUpdatedState {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        onPress()
    }
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    var repeated by remember { mutableStateOf(false) }
    val holdBeforeRepeating = LocalViewConfiguration.current.longPressTimeoutMillis
    if (repeats) {
        LaunchedEffect(pressed) {
            if (!pressed) return@LaunchedEffect
            repeated = false
            delay(holdBeforeRepeating)
            repeated = true
            while (true) {
                press()
                delay(KEY_REPEAT_MILLIS)
            }
        }
    }
    Box(
        modifier
            .weight(1f)
            .fillMaxHeight()
            .combinedClickable(
                interactionSource = interactions,
                indication = ripple(),
                enabled = enabled,
                role = Role.Button,
                onLongClick = onLongPress,
                onClick = { if (!repeated) press() },
            )
            .alpha(if (enabled) 1f else 0.38f),
        contentAlignment = Alignment.Center,
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge, content)
    }
}

/**
 * Return sends the text as it is, so that it can still be edited in the shell, and sends Enter when
 * the row is empty.
 */
@Composable
private fun TerminalTextRow(
    text: TextFieldState,
    display: TerminalDisplay,
    enabled: Boolean,
    focus: FocusRequester,
) {
    val foreground = LocalContentColor.current
    val description = stringResource(R.string.terminal_text_row)
    BasicTextField(
        text,
        Modifier.fillMaxSize().padding(horizontal = 12.dp).focusRequester(focus).semantics {
            contentDescription = description
        },
        enabled = enabled,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = foreground),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        onKeyboardAction = {
            if (text.text.isEmpty()) display.press(TerminalKey.ENTER)
            else display.type(text.text.toString())
            text.clearText()
        },
        lineLimits = TextFieldLineLimits.SingleLine,
        cursorBrush = SolidColor(foreground),
        decorator = { field ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                field()
                HorizontalDivider(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                    color = foreground.copy(alpha = 0.4f),
                )
            }
        },
    )
}

/** Termux's, which has shown itself right for holding down an arrow. */
private const val KEY_REPEAT_MILLIS = 80L
