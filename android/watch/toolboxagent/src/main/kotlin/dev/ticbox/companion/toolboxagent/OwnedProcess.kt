package dev.ticbox.companion.toolboxagent

import java.io.Closeable
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

internal enum class CommandSignal(val number: Int, val option: String) {
    HANGUP(1, "-HUP"), INTERRUPT(2, "-INT"), FORCE(9, "-KILL"), CONTINUE(18, "-CONT"),
}

internal data class ProcessIdentity(val pid: Long, val started: Long, val isolated: Boolean)
private val ownedCommands = WeakHashMap<Process, ProcessIdentity>()

internal fun processStartTicks(stat: String): Long? = stat.substringAfterLast(") ", "").split(' ').getOrNull(19)?.toLongOrNull()
internal fun processId(process: Process): Long? = runCatching { Process::class.java.getMethod("pid").invoke(process) as Long }.getOrNull()
    ?: runCatching { process.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(process).toLong() }.getOrNull()

private fun rememberProcess(process: Process, isolated: Boolean): Process = process.also {
    val pid = processId(it) ?: return@also
    if (pid !in 2..Int.MAX_VALUE.toLong()) return@also
    val started = runCatching { processStartTicks(File("/proc/$pid/stat").readText()) }.getOrNull() ?: return@also
    synchronized(ownedCommands) { ownedCommands[it] = ProcessIdentity(pid, started, isolated) }
}

internal fun startOwnedCommand(command: String, shell: String): Process {
    val setsid = listOf("/system/bin/setsid", "/usr/bin/setsid", "/bin/setsid").firstOrNull { File(it).canExecute() }
    return rememberProcess(ProcessBuilder(if (setsid != null) listOf(setsid, shell, "-c", command) else listOf(shell, "-c", command)).start(), setsid != null)
}

internal fun startPackageCommand(command: List<String>): Process = rememberProcess(ProcessBuilder(command).redirectErrorStream(true).start(), false)

/** No post-exit PID/group signalling: only a still-owned child with the recorded start time. */
internal fun signalOwnedCommand(process: Process, signal: CommandSignal): Boolean {
    val identity = synchronized(ownedCommands) { ownedCommands[process] } ?: return false
    if (!process.isAlive) return false
    val stat = runCatching { File("/proc/${identity.pid}/stat").readText() }.getOrNull() ?: return false
    if (processStartTicks(stat) != identity.started) return false
    val fields = stat.substringAfterLast(") ", "").split(' ')
    val group = fields.getOrNull(2)?.toLongOrNull()
    val session = fields.getOrNull(3)?.toLongOrNull()
    val target = if (identity.isolated && group == identity.pid && session == identity.pid) -identity.pid else identity.pid
    return try { android.system.Os.kill(Math.toIntExact(target), signal.number); true }
    catch (_: Exception) {
        val kill = listOf("/system/bin/kill", "/bin/kill").firstOrNull { File(it).canExecute() } ?: return false
        runCatching {
            val sent = ProcessBuilder(kill, signal.option, "--", target.toString()).start()
            try { sent.waitFor(2, TimeUnit.SECONDS) && sent.exitValue() == 0 }
            finally { if (sent.isAlive) sent.destroyForcibly() }
        }.getOrDefault(false)
    }
}

/** HUP is catchable/ignorable. A stopped command also needs CONT to observe its pending HUP. */
internal fun hangupOwnedCommand(process: Process) {
    signalOwnedCommand(process, CommandSignal.HANGUP)
    signalOwnedCommand(process, CommandSignal.CONTINUE)
    retireCommandStreams(process)
}

/** Pipe close can wait on a writer's monitor; never let it turn disconnect into a forced kill. */
internal fun retireCommandStreams(process: Process) {
    listOf<Closeable>(process.outputStream, process.inputStream, process.errorStream).forEach { stream ->
        Thread({ runCatching { stream.close() } }, "toolbox-command-pipe-close").apply { isDaemon = true; start() }
    }
}
