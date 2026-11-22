package dev.ticbox.companion.toolboxagent

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** completed=false is loss of a definitive reply, not proof that PackageManager rejected it. */
internal data class CapturedCommand(val code: Int, val output: String, val completed: Boolean = true)
internal interface PackageCommands {
    fun run(command: List<String>, input: ((OutputStream) -> Unit)? = null, timeoutMillis: Long = 60_000): CapturedCommand
}
internal object SystemPackageCommands : PackageCommands {
    override fun run(command: List<String>, input: ((OutputStream) -> Unit)?, timeoutMillis: Long) = capture(command, input, timeoutMillis)
}
internal fun capture(command: List<String>, input: ((OutputStream) -> Unit)? = null, timeoutMillis: Long = 60_000): CapturedCommand {
    val process = startPackageCommand(command)
    val bytes = ByteArrayOutputStream()
    val draining = Thread({ runCatching { process.inputStream.use { stream ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer); if (count < 0) break
            if (bytes.size() < 131072) bytes.write(buffer, 0, minOf(count, 131072 - bytes.size()))
        }
    } } }, "toolbox-package-output").apply { isDaemon = true; start() }
    try {
        process.outputStream.use { output -> input?.invoke(output) }
        val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) hangupOwnedCommand(process)
        else draining.join(2000)
        return CapturedCommand(if (finished) process.exitValue() else 124, bytes.toString(Charsets.UTF_8.name()), finished)
    } finally {
        if (process.isAlive) hangupOwnedCommand(process)
        else retireCommandStreams(process)
    }
}
