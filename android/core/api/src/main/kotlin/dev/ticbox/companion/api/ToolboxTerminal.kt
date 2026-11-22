package dev.ticbox.companion.api

import kotlinx.coroutines.flow.Flow

/** Character-cell dimensions plus optional whole-window pixel dimensions (zero means unknown). */
data class ToolboxTerminalSize(
    val rows: Int = 24,
    val columns: Int = 80,
    val widthPixels: Int = 0,
    val heightPixels: Int = 0,
) {
    init {
        require(rows in 1..1000 && columns in 1..1000)
        require(widthPixels in 0..65535 && heightPixels in 0..65535)
    }
}

/** Starts the device's system shell interactively, with a controlling PTY and this TERM value. */
data class ToolboxTerminalOptions(
    val size: ToolboxTerminalSize = ToolboxTerminalSize(),
    val term: String = "xterm-256color",
) {
    init { require(term.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,63}"))) }
}

sealed interface ToolboxTerminalEvent {
    /** Raw merged terminal output, including echo, ANSI/VT controls, CR and arbitrary UTF-8 boundaries. */
    class Output(val bytes: ByteArray) : ToolboxTerminalEvent {
        override fun toString() = "TerminalOutput(bytes=${bytes.size})"
    }
    /**
     * Observed shell exit status; a terminating signal is encoded as 128 + signal.
     * [outputTruncated] means the post-exit drain budget was exhausted: additional terminal output
     * may have been discarded. It does not change the shell's actual exit code.
     */
    data class Exited(val code: Int, val outputTruncated: Boolean = false) : ToolboxTerminalEvent
}

/**
 * One live terminal on one physical device/connection. Never reconnects or replays input.
 *
 * The frontend must collect [events] exactly once and render its bytes with a terminal emulator;
 * don't strip escapes or echo locally. Output is bounded with backpressure: drain it while waiting
 * for exit. Cancelling the event collector closes the terminal. Terminal I/O errors fail the flow
 * and [awaitExit]; they do not invent a successful exit.
 *
 * Send Ctrl-C (0x03), Ctrl-D (0x04) and Ctrl-Z (0x1a) through [write]. Their effect follows the
 * terminal's current line discipline; Ctrl-D is not a transport half-close and in raw mode is an
 * ordinary byte. [close] hangs up the terminal without force-killing commands. Detached or
 * hangup-ignoring jobs may survive; automatic command replay is never performed.
 */
interface ToolboxTerminalSession : AutoCloseable {
    val target: ToolboxTarget
    val events: Flow<ToolboxTerminalEvent>
    /** Raw input. The frontend supplies Enter/escape sequences; no newline is appended. */
    suspend fun write(bytes: ByteArray)
    /** Sets the whole terminal window size and notifies its foreground application. */
    suspend fun resize(size: ToolboxTerminalSize)
    suspend fun awaitExit(): ToolboxTerminalEvent.Exited
    /** Idempotent; must also be called when a page/session is disposed before collecting events. */
    override fun close()
}
