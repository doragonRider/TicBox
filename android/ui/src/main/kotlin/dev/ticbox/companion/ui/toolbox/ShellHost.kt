package dev.ticbox.companion.ui.toolbox

import dev.ticbox.companion.api.ToolboxException
import dev.ticbox.companion.api.ToolboxTarget
import dev.ticbox.companion.api.ToolboxTerminalEvent
import dev.ticbox.companion.api.ToolboxTerminalSession
import dev.ticbox.companion.api.ToolboxTerminalSize
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.UiText
import dev.ticbox.companion.ui.common.toUiText
import dev.ticbox.companion.ui.common.uiText
import dev.ticbox.companion.ui.designsystem.BannerTone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class ShellPhase {
    /** There's no shell to show. */
    IDLE,
    STARTING,
    READY,
    ENDED,
}

data class ShellState(
    val phase: ShellPhase = ShellPhase.IDLE,
    /** How the last shell ended. */
    val ended: UiText? = null,
    val endTone: BannerTone = BannerTone.Info,
) {
    val running: Boolean
        get() = phase == ShellPhase.STARTING || phase == ShellPhase.READY
}

/**
 * The one shell and its screen. It belongs to the process, because leaving the terminal page
 * doesn't end it: its output keeps being read here, since a terminal's events can only be collected
 * once. A connection going away ends it, and nothing brings it back but someone asking for a new
 * one. What it prints and what is typed into it stays in memory: never in saved state or logs.
 */
class ShellHost(
    private val displays: TerminalDisplayFactory,
    private val scope: CoroutineScope,
) {
    private val current = MutableStateFlow(ShellState())
    val state: StateFlow<ShellState> = current.asStateFlow()

    private val size = MutableStateFlow<ToolboxTerminalSize?>(null)

    /** Only there while a shell can take it: nothing typed is kept for a later one. */
    private var input: Channel<ByteArray>? = null

    private var screen: TerminalDisplay? = null
    private var job: Job? = null
    private var pages = 0

    /** Whether nobody will read what's on the screen once it has stopped. */
    private var stopping = false

    private val showing = Channel<Unit>(Channel.CONFLATED)

    /** Someone asked for the terminal from outside the app's pages. */
    val showRequests: Flow<Unit> = showing.receiveAsFlow()

    /** The connection the shell was opened on. */
    var target: ToolboxTarget? = null
        private set

    val display: TerminalDisplay
        get() =
            screen
                ?: displays
                    .create(onInput = { input?.trySend(it) }, onSize = { size.value = it })
                    .also { screen = it }

    fun show() {
        showing.trySend(Unit)
    }

    fun pageOpened() {
        pages++
    }

    /** Output stays for the next visit only if nobody has seen how the shell ended yet. */
    fun pageClosed() {
        pages--
        if (pages == 0 && !current.value.running) release()
    }

    fun start(
        on: ToolboxTarget?,
        beforeInput: () -> Unit,
        open: suspend (ToolboxTerminalSize) -> ToolboxTerminalSession,
    ) {
        if (job?.isActive == true) return
        target = on
        current.value = ShellState(phase = ShellPhase.STARTING)
        val shown = display
        shown.ctrl = StickyKey.OFF
        shown.alt = StickyKey.OFF
        job = scope.launch { runShell(shown, beforeInput, open) }
    }

    /** Someone asked for this, unlike [end]. */
    fun stop() {
        if (job?.isActive != true) return
        stopping = true
        current.update { it.copy(ended = uiText(R.string.terminal_stopped)) }
        job?.cancel()
    }

    /** It went with its connection. */
    suspend fun end() {
        job?.cancelAndJoin()
    }

    private fun release() {
        screen?.close()
        screen = null
        size.value = null
        target = null
        current.value = ShellState()
    }

    private suspend fun runShell(
        shown: TerminalDisplay,
        beforeInput: () -> Unit,
        open: suspend (ToolboxTerminalSize) -> ToolboxTerminalSession,
    ) {
        try {
            val opening =
                withTimeoutOrNull(SIZE_WAIT_MILLIS) { size.filterNotNull().first() }
                    ?: ToolboxTerminalSize()
            // The last shell's programs may have left the screen in any mode.
            withContext(Dispatchers.Default) { shown.show(RESET) }
            reportingTimeouts { open(opening) }
                .use { session -> attend(session, opening, shown, beforeInput) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Exception) {
            current.update { it.copy(ended = problem.toUiText(), endTone = BannerTone.Error) }
        } finally {
            input = null
            // Around all of it: coming back from another dispatcher throws if this was cancelled.
            withContext(NonCancellable) {
                // Its screen stays to be read, and a blinking cursor would say it's taking keys.
                // Before the phase changes, as that's what lets another shell start and reset it.
                withContext(Dispatchers.Default) { shown.show(STILL_CURSOR) }
                // Nothing said why by now means it went with the connection.
                current.update {
                    it.copy(
                        phase = ShellPhase.ENDED,
                        ended = it.ended ?: uiText(R.string.terminal_disconnected),
                    )
                }
                if (stopping && pages == 0) release()
                stopping = false
            }
        }
    }

    private suspend fun attend(
        session: ToolboxTerminalSession,
        opened: ToolboxTerminalSize,
        shown: TerminalDisplay,
        beforeInput: () -> Unit,
    ) {
        val typed = Channel<ByteArray>(Channel.UNLIMITED)
        input = typed
        // Its opening is what says it's ready. A shell needn't print anything first.
        current.update { it.copy(phase = ShellPhase.READY) }
        coroutineScope {
            val sending = launch {
                untilClosed {
                    for (bytes in typed) {
                        beforeInput()
                        session.write(bytes)
                    }
                }
            }
            val resizing = launch {
                var sent = opened
                untilClosed {
                    size.filterNotNull().collect {
                        // Pixels alone change on every frame the keyboard opens, and no program
                        // needs to hear about those.
                        if (it.rows != sent.rows || it.columns != sent.columns) {
                            session.resize(it)
                            sent = it
                        }
                    }
                }
            }
            withContext(Dispatchers.Default) {
                session.events.collect { event ->
                    when (event) {
                        is ToolboxTerminalEvent.Output -> shown.show(event.bytes)
                        is ToolboxTerminalEvent.Exited -> ended(event)
                    }
                }
            }
            sending.cancel()
            resizing.cancel()
        }
    }

    /** Input can still be on its way as the shell exits, and its exit is what should be told. */
    private suspend fun untilClosed(block: suspend () -> Unit) {
        try {
            block()
        } catch (closed: ToolboxException) {
            if (closed.code != "terminal_closed") throw closed
        }
    }

    private fun ended(exit: ToolboxTerminalEvent.Exited) = current.update {
        if (exit.outputTruncated) {
            it.copy(
                ended = uiText(R.string.terminal_exited_truncated, exit.code),
                endTone = BannerTone.Warning,
            )
        } else {
            it.copy(ended = uiText(R.string.terminal_exited, exit.code), endTone = BannerTone.Info)
        }
    }

    private companion object {
        /** Only for a panel that never gets measured, which still deserves a shell. */
        const val SIZE_WAIT_MILLIS = 2_000L
        val RESET = "\u001bc".toByteArray()
        val STILL_CURSOR = "\u001b[?12l".toByteArray()
    }
}
