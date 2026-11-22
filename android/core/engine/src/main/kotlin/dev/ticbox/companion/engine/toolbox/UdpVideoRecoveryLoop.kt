package dev.ticbox.companion.engine.toolbox

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/** Loss notifications wake the existing recovery worker; the timer only supplies retry deadlines. */
internal class UdpVideoRecoveryLoop(
    private val intervalMillis: Long,
    maximumAttempts: Int,
    private val state: () -> UdpVideoRecoveryState,
    private val request: suspend () -> Unit,
    private val failed: () -> Unit,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val restart: (suspend () -> Unit)? = null,
    restartTimeoutMillis: Long = 10_000,
) : AutoCloseable {
    private val policy = UdpVideoRecovery(intervalMillis, maximumAttempts, restartAllowed = restart != null, restartTimeoutMillis = restartTimeoutMillis)
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val closed = AtomicBoolean()

    /** Called after publishing a complete state, never during a temporary same-frame reset. */
    fun stateChanged() { if (!closed.get()) changes.trySend(Unit) }

    suspend fun run() {
        while (!closed.get() && currentCoroutineContext().isActive) {
            when (policy.poll(state(), nowMillis())) {
                UdpVideoRecovery.Action.NONE -> Unit
                UdpVideoRecovery.Action.REQUEST_KEYFRAME -> request()
                UdpVideoRecovery.Action.RESTART_VIDEO -> checkNotNull(restart).invoke()
                UdpVideoRecovery.Action.FAILED -> { failed(); return }
            }
            // No extra task or blocking control write on the receive thread. Changes during a
            // control write remain conflated and are checked against the current state/cooldown.
            // Wake when the cooldown ends too, so a request after a short spacing isn't a poll late.
            val left = policy.cooldownLeft(nowMillis()).takeIf { it > 0 } ?: Long.MAX_VALUE
            val wake = withTimeoutOrNull(minOf(100L, intervalMillis, left)) { changes.receiveCatching() }
            if (wake?.isClosed == true) return
        }
    }

    override fun close() { closed.set(true); changes.close() }
}
