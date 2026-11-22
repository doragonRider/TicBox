package dev.ticbox.companion.toolboxagent

import dev.ticbox.companion.protocol.*
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.*

/** Pipes retain binary stdout/stderr. Disconnect, interrupt and force are separate policies. */
internal class AgentCommand(private val wire: AgentWire, private val environment: AgentEnvironment) {
    fun run(command: String, timeoutMillis: Long) {
        require(timeoutMillis in 1..86_400_000)
        val process = startOwnedCommand(command, environment.shell)
        val hangingUp = AtomicBoolean()
        val detached = AtomicBoolean()
        fun hangup() {
            if (hangingUp.compareAndSet(false, true)) {
                hangupOwnedCommand(process)
                detached.set(true)
            }
        }
        val input = Thread({
            try {
                while (true) {
                    val packet = wire.read()
                    when (packet.type) {
                        ToolboxProtocol.STDIN -> { process.outputStream.write(packet.bytes); process.outputStream.flush() }
                        ToolboxProtocol.END -> process.outputStream.close() // EOF alone is not a hangup.
                        ToolboxProtocol.CANCEL -> signalOwnedCommand(process, CommandSignal.INTERRUPT)
                        ToolboxProtocol.FORCE_STOP -> {
                            if (process.isAlive && !signalOwnedCommand(process, CommandSignal.FORCE)) {
                                wire.json(ToolboxProtocol.ERROR, buildJsonObject { put("code", "process_identity_unavailable") })
                            }
                        }
                        else -> throw IOException("command_input_required")
                    }
                }
            } catch (_: Exception) { hangup() }
        }, "toolbox-command-input").apply { isDaemon = true; start() }
        val writing = AtomicInteger()
        val lastOutput = AtomicLong(System.nanoTime())
        fun drain(source: InputStream, type: Int) = Thread({
            var deliver = true
            try { source.use { stream ->
                val buffer = ByteArray(ToolboxProtocol.CHUNK)
                while (true) {
                    val count = stream.read(buffer); if (count < 0) break
                    lastOutput.set(System.nanoTime())
                    if (deliver) {
                        writing.incrementAndGet()
                        try { wire.send(type, buffer.copyOf(count)) }
                        catch (_: IOException) { deliver = false; hangup() }
                        finally { writing.decrementAndGet() }
                    }
                }
            } } catch (_: IOException) { hangup() }
        }, "toolbox-command-output").apply { isDaemon = true; start() }
        val stdout = drain(process.inputStream, ToolboxProtocol.STDOUT)
        val stderr = drain(process.errorStream, ToolboxProtocol.STDERR)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        var timedOut = false
        try {
            while (!detached.get() && process.isAlive) {
                val left = deadline - System.nanoTime()
                if (left <= 0) {
                    timedOut = true
                    signalOwnedCommand(process, CommandSignal.INTERRUPT)
                    process.waitFor(750, TimeUnit.MILLISECONDS)
                    break
                }
                process.waitFor(minOf(left, 100_000_000), TimeUnit.NANOSECONDS)
            }
            if (detached.get()) return
            // Preserve output backpressure. A background child retaining the pipes must not
            // keep an already-finished shell alive forever, and is never chased by PID/group.
            lastOutput.set(System.nanoTime())
            while (!detached.get() && (stdout.isAlive || stderr.isAlive)) {
                stdout.join(25); stderr.join(25)
                if (timedOut || writing.get() == 0 && System.nanoTime() - lastOutput.get() > 750_000_000) break
            }
            if (!detached.get()) wire.json(ToolboxProtocol.EXIT, buildJsonObject {
                put("code", if (timedOut) 124 else process.exitValue()); put("timedOut", timedOut)
            })
        } catch (error: Exception) {
            hangup()
            throw error
        } finally {
            retireCommandStreams(process)
            input.interrupt()
        }
    }
}
