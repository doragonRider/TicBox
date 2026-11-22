package dev.ticbox.companion.toolboxagent

import dev.ticbox.companion.protocol.ToolboxProtocol
import dev.ticbox.companion.protocol.number
import dev.ticbox.companion.protocol.text
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The terminal's wire dimensions are checked before narrowing them to native integers. */
internal data class TerminalSize(val rows: Int, val columns: Int, val widthPixels: Int, val heightPixels: Int) {
    companion object {
        fun from(args: JsonObject): TerminalSize {
            fun dimension(name: String, minimum: Long, maximum: Long): Int {
                val value = args.number(name) ?: throw IllegalArgumentException("missing_$name")
                require(value in minimum..maximum) { "invalid_$name" }
                return value.toInt()
            }
            return TerminalSize(dimension("rows", 1, 1000), dimension("columns", 1, 1000),
                dimension("widthPixels", 0, 65535), dimension("heightPixels", 0, 65535))
        }
    }
}

internal class TerminalUnavailableException : IOException("terminal_unavailable")
internal class TerminalStartException(cause: IOException) : IOException("terminal_start_failed", cause)

/**
 * A real controlling terminal inside the agent, leaving its outer framed transport binary-safe.
 * Terminal stdout/stderr and echo are one raw stream. Ctrl-C/Ctrl-D/Ctrl-Z are input bytes interpreted
 * by the terminal's current line discipline, rather than process signals synthesized by the phone.
 */
internal class AgentTerminal(private val wire: AgentWire, private val environment: AgentEnvironment) {
    fun run(args: JsonObject) {
        val size = TerminalSize.from(args)
        val term = args.text("term") ?: throw IllegalArgumentException("missing_term")
        require(TERM.matches(term)) { "invalid_term" }
        if (!PtyProcess.available()) throw TerminalUnavailableException()
        val process = try { PtyProcess.start(environment.shell, term, size.rows, size.columns, size.widthPixels, size.heightPixels) }
        catch (error: IOException) { throw TerminalStartException(error) }
        val closed = AtomicBoolean()
        val exit = AtomicReference<Exit?>()
        fun hangup() { if (closed.compareAndSet(false, true)) process.close() }
        val pendingInput = ArrayBlockingQueue<Input>(16)
        val pendingLock = ReentrantLock()
        val pendingFinished = pendingLock.newCondition()
        var accepting = true
        var pending = 0
        var input: Thread? = null
        var writer: Thread? = null
        var waiter: Thread? = null
        try {
            // A prompt (or any output) is not a readiness signal. Native start includes exec's
            // error pipe, so an invalid shell cannot be reported as a successfully opened terminal.
            wire.json(ToolboxProtocol.RESULT, buildJsonObject { put("started", true) })
            // Keep reading the transport even if the foreground application stops reading its
            // terminal. Otherwise a blocked PTY write would hide transport EOF indefinitely.
            input = Thread({
                try {
                    while (!closed.get()) {
                        val packet = wire.read()
                        val next = when (packet.type) {
                            ToolboxProtocol.STDIN -> {
                                require(packet.bytes.size <= ToolboxProtocol.CHUNK) { "terminal_input_size" }
                                Input.Bytes(packet.bytes)
                            }
                            ToolboxProtocol.TERMINAL_RESIZE -> {
                                require(packet.bytes.size <= 1024) { "terminal_resize_size" }
                                Input.Resize(TerminalSize.from(packet.objectValue()))
                            }
                            else -> throw IOException("terminal_input_required")
                        }
                        // At most 1 MiB queued, plus one in-flight write. Overflow closes
                        // this terminal; input is never dropped while pretending it was accepted.
                        pendingLock.withLock {
                            if (!accepting) return@Thread
                            if (!pendingInput.offer(next)) throw IOException("terminal_input_overflow")
                            pending++
                        }
                    }
                } catch (_: Exception) { hangup() }
            }, "toolbox-terminal-input").apply { isDaemon = true; start() }
            writer = Thread({
                try {
                    while (!closed.get()) {
                        val next = pendingInput.take()
                        if (closed.get()) break
                        try {
                            when (next) {
                                is Input.Bytes -> process.write(next.bytes)
                                is Input.Resize -> next.size.let { process.resize(it.rows, it.columns, it.widthPixels, it.heightPixels) }
                            }
                            // One outstanding client frame at a time bounds input and confirms that
                            // resize/write reached the PTY, without preventing the reader seeing EOF.
                            wire.send(ToolboxProtocol.TERMINAL_ACK)
                        } finally {
                            pendingLock.withLock { pending--; pendingFinished.signalAll() }
                        }
                    }
                } catch (_: Exception) { hangup() }
            }, "toolbox-terminal-write").apply { isDaemon = true; start() }
            waiter = Thread({
                try { exit.set(Exit(process.awaitExit(), System.nanoTime())) }
                catch (_: IOException) { hangup() }
            }, "toolbox-terminal-exit").apply { isDaemon = true; start() }
            val buffer = ByteArray(ToolboxProtocol.CHUNK)
            var lastOutput = System.nanoTime()
            var outputTruncated = false
            while (!closed.get()) {
                // Descendants can keep the slave open and produce output forever after the shell
                // has exited. This deadline never moves when more output arrives. Complete an
                // already-admitted wire frame, but do not keep reading new PTY bytes indefinitely.
                val exitedAt = exit.get()?.atNanos
                if (exitedAt != null && System.nanoTime() - exitedAt >= EXIT_DRAIN_LIMIT_NANOS) {
                    outputTruncated = true
                    break
                }
                val count = process.read(buffer, READ_POLL_MILLIS)
                if (count < 0) break
                if (count > 0) {
                    lastOutput = System.nanoTime()
                    wire.send(ToolboxProtocol.STDOUT, buffer.copyOf(count))
                }
                // Stop sooner when inherited descriptors become quiet. A blocked wire send still
                // applies transport backpressure; the hard limit above bounds further PTY draining,
                // not the time it takes an unavailable receiver to accept already-admitted output.
                val ended = exit.get()
                if (count == 0 && ended != null && System.nanoTime() - maxOf(ended.atNanos, lastOutput) >= EXIT_DRAIN_NANOS) break
            }
            if (!closed.get()) {
                val code = exit.get()?.code ?: process.awaitExit()
                // An input such as "exit\n" can end the shell before its ACK gets the output lock.
                // Freeze admission and let accepted operations finish before publishing EXIT.
                // An inherited slave must not leave this wait blocked indefinitely either.
                val deadline = System.nanoTime() + EXIT_ACK_NANOS
                val acknowledged = pendingLock.withLock {
                    accepting = false
                    while (pending > 0 && !closed.get() && System.nanoTime() < deadline) pendingFinished.await(100, TimeUnit.MILLISECONDS)
                    pending == 0
                }
                if (!closed.get() && acknowledged) wire.json(ToolboxProtocol.EXIT, buildJsonObject {
                    put("code", code); put("outputTruncated", outputTruncated)
                })
            }
        } finally {
            hangup()
            input?.interrupt()
            writer?.interrupt()
            waiter?.interrupt()
            pendingInput.clear()
        }
    }

    private sealed interface Input {
        class Bytes(val bytes: ByteArray) : Input
        class Resize(val size: TerminalSize) : Input
    }
    private data class Exit(val code: Int, val atNanos: Long)
    private companion object {
        val TERM = Regex("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,63}")
        const val READ_POLL_MILLIS = 100
        const val EXIT_DRAIN_NANOS = 750_000_000L
        const val EXIT_DRAIN_LIMIT_NANOS = 2_000_000_000L
        const val EXIT_ACK_NANOS = 5_000_000_000L
    }
}
