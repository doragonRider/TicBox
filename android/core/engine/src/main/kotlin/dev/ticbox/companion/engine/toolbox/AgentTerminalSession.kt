package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal fun ToolboxTerminalSize.wireSize() = buildJsonObject {
    put("rows", rows); put("columns", columns); put("widthPixels", widthPixels); put("heightPixels", heightPixels)
}

/** A terminal's transport is already authenticated and its child has acknowledged successful exec. */
internal class AgentTerminalSession(
    private val link: AgentLink,
    worker: CoroutineScope,
    override val target: ToolboxTarget,
    private val validateTarget: () -> Unit,
    private val onClosed: (AgentTerminalSession) -> Unit = {},
) : ToolboxTerminalSession {
    private val output = Channel<ToolboxTerminalEvent>(16)
    private val collected = AtomicBoolean()
    private val finished = AtomicBoolean()
    private val released = AtomicBoolean()
    private val writing = Mutex()
    private val pendingInput = AtomicReference<CompletableDeferred<Unit>?>()
    private val exit = CompletableDeferred<ToolboxTerminalEvent.Exited>()
    override val events: Flow<ToolboxTerminalEvent> = flow {
        check(collected.compareAndSet(false, true)) { "terminal_events_already_collected" }
        try { emitAll(output.receiveAsFlow()) }
        finally { close() }
    }
    private val reading = worker.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
        try {
            while (true) {
                val packet = link.read()
                when (packet.type) {
                    ToolboxProtocol.STDOUT -> {
                        if (packet.bytes.size > ToolboxProtocol.CHUNK) throw ToolboxException("terminal_protocol_mismatch")
                        if (packet.bytes.isNotEmpty()) output.send(ToolboxTerminalEvent.Output(packet.bytes))
                    }
                    ToolboxProtocol.TERMINAL_ACK -> {
                        if (packet.bytes.isNotEmpty()) throw ToolboxException("terminal_protocol_mismatch")
                        val pending = pendingInput.getAndSet(null) ?: throw ToolboxException("terminal_protocol_mismatch")
                        pending.complete(Unit)
                    }
                    ToolboxProtocol.EXIT -> {
                        val value = packet.objectValue()
                        val code = value.number("code")
                        if (code == null || code !in 0L..255L) throw ToolboxException("terminal_protocol_mismatch")
                        val rawTruncated = value["outputTruncated"]
                        val truncated = if (rawTruncated == null) false else
                            (rawTruncated as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                                ?: throw ToolboxException("terminal_protocol_mismatch")
                        val result = ToolboxTerminalEvent.Exited(code.toInt(), outputTruncated = truncated)
                        finished.set(true)
                        exit.complete(result)
                        output.send(result)
                        output.close()
                        break
                    }
                    else -> throw ToolboxException("terminal_protocol_mismatch")
                }
            }
        } catch (error: Throwable) {
            finished.set(true)
            exit.completeExceptionally(error)
            output.close(error)
        } finally { release() }
    }
    init {
        // Even a parent scope cancelled before this lazy coroutine starts must release the link.
        reading.invokeOnCompletion { error ->
            if (error != null) {
                finished.set(true)
                exit.completeExceptionally(error)
                output.close(error)
            }
            release()
        }
        reading.start()
    }
    internal val isClosed: Boolean get() = released.get()
    private fun release() {
        if (released.compareAndSet(false, true)) {
            pendingInput.getAndSet(null)?.completeExceptionally(ToolboxException("terminal_closed"))
            try { link.closeAsync() } finally { onClosed(this) }
        }
    }
    private fun requireWritable() {
        validateTarget()
        if (finished.get() || released.get()) throw ToolboxException("terminal_closed")
    }
    override suspend fun write(bytes: ByteArray): Unit = sendInput {
        requireWritable()
        var offset = 0
        while (offset < bytes.size) {
            requireWritable()
            val end = minOf(bytes.size, offset + ToolboxProtocol.CHUNK)
            sendAndConfirm(ToolboxProtocol.STDIN, bytes.copyOfRange(offset, end))
            offset = end
        }
    }
    override suspend fun resize(size: ToolboxTerminalSize): Unit = sendInput {
        requireWritable()
        sendAndConfirm(ToolboxProtocol.TERMINAL_RESIZE, size.wireSize().toString().toByteArray(Charsets.UTF_8))
    }
    private suspend fun sendAndConfirm(type: Int, bytes: ByteArray) {
        val acknowledgement = CompletableDeferred<Unit>()
        check(pendingInput.compareAndSet(null, acknowledgement))
        try {
            requireWritable()
            link.send(type, bytes)
            acknowledgement.await()
        } finally { pendingInput.compareAndSet(acknowledgement, null) }
    }
    private suspend fun sendInput(block: suspend () -> Unit) {
        try { writing.withLock { block() } }
        catch (error: Throwable) { close(); throw error }
    }
    override suspend fun awaitExit() = exit.await()
    override fun close() {
        finished.set(true)
        output.cancel()
        exit.cancel()
        release()
        reading.cancel()
    }
}
