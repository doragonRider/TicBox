package dev.ticbox.companion.ui.toolbox

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.sp
import dev.ticbox.companion.api.ToolboxTerminalSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.connectbot.terminal.ModifierManager
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalDimensions
import org.connectbot.terminal.TerminalEmulatorFactory
import org.connectbot.terminal.VTermKey

/** The keys a phone's keyboard usually doesn't have, and Enter for the text row. */
enum class TerminalKey(internal val code: Int) {
    ENTER(VTermKey.ENTER),
    ESCAPE(VTermKey.ESCAPE),
    TAB(VTermKey.TAB),
    UP(VTermKey.UP),
    DOWN(VTermKey.DOWN),
    LEFT(VTermKey.LEFT),
    RIGHT(VTermKey.RIGHT),
    HOME(VTermKey.HOME),
    END(VTermKey.END),
    PAGE_UP(VTermKey.PAGEUP),
    PAGE_DOWN(VTermKey.PAGEDOWN),
}

/** Ctrl or Alt: off, on for the next key, or on until turned off. */
enum class StickyKey {
    OFF,
    NEXT,
    LOCKED,
}

/**
 * The terminal emulator as the terminal page sees it. Keys go in here rather than straight to the
 * shell because how one is encoded depends on what the running program asked for, and all of it
 * comes back out in one order through the factory's `onInput`.
 */
interface TerminalDisplay : AutoCloseable {
    /** Applies to keys from here and from the keyboard alike. */
    var ctrl: StickyKey

    var alt: StickyKey

    /** What the terminal sent, control sequences included. Can block, so not on the main thread. */
    fun show(output: ByteArray)

    fun press(key: TerminalKey)

    fun press(character: Char)

    /** Text as written, for which [ctrl] and [alt] don't apply. */
    fun type(text: String)

    fun paste(text: String)

    @Composable
    fun Content(
        background: Color,
        foreground: Color,
        keyboardEnabled: Boolean,
        onPaste: () -> Unit,
        focus: FocusRequester,
        modifier: Modifier,
    )
}

fun interface TerminalDisplayFactory {
    /** Both callbacks run on the main thread. [onSize] says what the panel really has room for. */
    fun create(onInput: (ByteArray) -> Unit, onSize: (ToolboxTerminalSize) -> Unit): TerminalDisplay
}

internal val EmulatorTerminalDisplays = TerminalDisplayFactory(::EmulatorDisplay)

/** libvterm's values, which the library keeps private. */
private const val VTERM_MOD_ALT = 2

private const val VTERM_MOD_CTRL = 4

private class EmulatorDisplay(
    onInput: (ByteArray) -> Unit,
    onSize: (ToolboxTerminalSize) -> Unit,
) : TerminalDisplay, ModifierManager {
    // Clipboard writes and inline images stay at their defaults, off: a program printing something
    // shouldn't reach the phone's clipboard or be decoded here.
    private val emulator =
        TerminalEmulatorFactory.create(
            onKeyboardInput = onInput,
            onResize = { onSize(it.toTerminalSize()) },
        )

    /** Both main thread only. */
    private var attached = 0
    private var closing = false

    /** Taken around anything from another thread, which a closed emulator would throw back at. */
    private val lifetime = Any()
    private var closed = false

    private inline fun whileOpen(action: () -> Unit) =
        synchronized(lifetime) { if (!closed) action() }

    private fun closeIfUnused() {
        if (closing && attached == 0) {
            synchronized(lifetime) {
                closed = true
                emulator.close()
            }
        }
    }

    override var ctrl by mutableStateOf(StickyKey.OFF)
    override var alt by mutableStateOf(StickyKey.OFF)

    override fun isCtrlActive() = ctrl != StickyKey.OFF

    override fun isAltActive() = alt != StickyKey.OFF

    override fun isShiftActive() = false

    override fun clearTransients() {
        if (ctrl == StickyKey.NEXT) ctrl = StickyKey.OFF
        if (alt == StickyKey.NEXT) alt = StickyKey.OFF
    }

    /** What the sticky keys add to one key press, which uses up those on for just the next key. */
    private fun takeModifiers(): Int {
        val modifiers =
            (if (isCtrlActive()) VTERM_MOD_CTRL else 0) or (if (isAltActive()) VTERM_MOD_ALT else 0)
        clearTransients()
        return modifiers
    }

    override fun show(output: ByteArray) = whileOpen { emulator.writeInput(output) }

    override fun press(key: TerminalKey) = emulator.dispatchKey(takeModifiers(), key.code)

    override fun press(character: Char) =
        emulator.dispatchCharacter(takeModifiers(), character.code)

    override fun type(text: String) =
        text.codePoints().forEach { emulator.dispatchCharacter(0, it) }

    override fun paste(text: String) = emulator.pasteText(text)

    /** The library wants its view gone first, and a page can be cleared while still leaving. */
    override fun close() {
        closing = true
        closeIfUnused()
    }

    @Composable
    override fun Content(
        background: Color,
        foreground: Color,
        keyboardEnabled: Boolean,
        onPaste: () -> Unit,
        focus: FocusRequester,
        modifier: Modifier,
    ) {
        DisposableEffect(Unit) {
            attached++
            onDispose {
                attached--
                closeIfUnused()
            }
        }
        LaunchedEffect(background, foreground) {
            withContext(Dispatchers.Default) {
                whileOpen { emulator.setDefaultColors(foreground.toArgb(), background.toArgb()) }
            }
        }
        Terminal(
            terminalEmulator = emulator,
            modifier = modifier,
            initialFontSize = 12.sp,
            backgroundColor = background,
            foregroundColor = foreground,
            keyboardEnabled = keyboardEnabled,
            focusRequester = focus,
            modifierManager = this,
            onPasteRequest = onPaste,
        )
    }
}

private fun TerminalDimensions.toTerminalSize() =
    ToolboxTerminalSize(
        rows = rows.coerceIn(1, 1000),
        columns = columns.coerceIn(1, 1000),
        widthPixels = widthPixels.coerceIn(0, 65535),
        heightPixels = heightPixels.coerceIn(0, 65535),
    )
