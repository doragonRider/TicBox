package dev.ticbox.companion.platform.diagnostics

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.DiagnosticJson
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Nonblocking producer; the owning scope controls the bounded journal worker's lifetime. */
internal class BufferedDiagnosticSink(
    scope: CoroutineScope,
    private val append: (String) -> Unit,
    private val mirror: (String) -> Unit = {},
    private val onFailure: (Throwable) -> Unit = {},
    capacity: Int = 256,
    private val now: () -> Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DiagnosticSink, AutoCloseable {
    init { require(capacity in 1..4_096) }
    private val pendingDropped = AtomicLong()
    private val totalDropped = AtomicLong()
    val droppedEntries: Long get() = totalDropped.get()
    private val queue = Channel<String>(capacity, onUndeliveredElement = {
        pendingDropped.saturatedIncrement()
        totalDropped.saturatedIncrement()
    })
    private val worker = scope.launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
        try {
            for (line in queue) {
                reportDrops()
                store(line)
            }
        } finally {
            queue.close()
            // The queue is now closed and finite; drain retained shutdown diagnostics off main.
            withContext(NonCancellable + dispatcher) {
                while (true) store(queue.tryReceive().getOrNull() ?: break)
                reportDrops()
            }
        }
    }

    override fun record(diagnostic: ProtocolDiagnostic) {
        try {
            if (!queue.trySend(DiagnosticJson.line(diagnostic, now())).isSuccess) {
                pendingDropped.saturatedIncrement()
                totalDropped.saturatedIncrement()
            }
        } catch (problem: Exception) { reportFailure(problem) }
    }

    override fun close() { queue.close() }
    suspend fun awaitClosed() { worker.join() }

    private fun reportDrops() {
        val count = pendingDropped.getAndSet(0)
        if (count > 0) store(DiagnosticJson.line(ProtocolDiagnostic(
            component = "diagnostics", event = "log_queue_overflow",
            details = mapOf("droppedEntries" to count.toString(), "decision" to "preserve_protocol_progress"),
        ), now()))
    }

    private fun store(line: String) {
        try { append(line) } catch (problem: Exception) { reportFailure(problem) }
        try { mirror(line) } catch (problem: Exception) { reportFailure(problem) }
    }

    private fun reportFailure(problem: Throwable) { runCatching { onFailure(problem) } }
    private fun AtomicLong.saturatedIncrement() { updateAndGet { if (it == Long.MAX_VALUE) it else it + 1 } }
}
