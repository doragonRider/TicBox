package dev.ticbox.companion.engine.toolbox

import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine

interface ToolboxChannel : AutoCloseable {
    val input: InputStream
    val output: OutputStream
    /** Observed encryption of this exact ADB shell channel, never inferred from its address. */
    val secureScreenUdp: Boolean get() = false
    /** Must unblock both streams; must not wait for the command to finish. */
    override fun close()
}
interface ToolboxAgentConnector : AutoCloseable {
    val device: String?
    /** The selected wireless ADB host; absent on transports without direct IP media. */
    val screenUdpHost: String? get() = null
    suspend fun prepare()
    suspend fun open(): ToolboxChannel
    /**
     * Permit recovery only when the failed transport is already dead. Called before any business REQUEST;
     * true permits one new agent/READY attempt, never replaying an operation or a live stream.
     */
    fun recoverBeforeRequest(error: java.io.IOException): Boolean = false
    override fun close()
}

/** Cancellation closes the actual resource, including a blocking Bluetooth/ADB read. */
suspend fun <T> toolboxIo(onCancel: () -> Unit = {}, onDiscard: (T) -> Unit = {}, block: () -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching(onCancel) }
        Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable {
            if (!continuation.isActive) return@Runnable
            try { continuation.resume(block()) { _, value, _ -> runCatching { onDiscard(value) } } }
            catch (error: Throwable) { if (continuation.isActive) continuation.resumeWithException(error) }
        })
    }
