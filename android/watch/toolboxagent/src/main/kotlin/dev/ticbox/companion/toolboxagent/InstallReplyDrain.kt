package dev.ticbox.companion.toolboxagent

import dev.ticbox.companion.protocol.ToolboxProtocol
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.serialization.json.*

/**
 * A streaming installer may already have sent another split when PackageManager rejects one.
 * Keep the terminal reply readable while discarding that in-flight request, instead of closing
 * a socket with unread bytes (which can reset the peer). No further package operation is run.
 * EOF is the receipt, so existing v3+ clients need no additional frame or handshake.
 */
internal fun AgentWire.awaitInstallReceipt(args: JsonObject, timeoutMillis: Long = 5_000) {
    val parts = args["parts"] as? JsonArray ?: return
    if (parts.isEmpty() || parts.size > 1024) return
    val total = runCatching {
        parts.fold(0L) { sum, value ->
            val size = (value as JsonObject).requiredLong("size").also { require(it > 0) }
            Math.addExact(sum, size)
        }
    }.getOrNull() ?: return
    val finished = CountDownLatch(1)
    thread(name = "toolbox-install-receipt", isDaemon = true) {
        try {
            var remaining = total
            var ends = parts.size
            while (true) {
                val packet = read()
                when (packet.type) {
                    ToolboxProtocol.DATA -> {
                        if (packet.bytes.isEmpty() || packet.bytes.size.toLong() > remaining) break
                        remaining -= packet.bytes.size
                    }
                    ToolboxProtocol.END -> if (packet.bytes.isNotEmpty() || --ends < 0) break
                    else -> break // Includes CANCEL; no unrelated request is admitted.
                }
            }
        } catch (_: Exception) {
            // The definitive result was already written; EOF/close must not add another ERROR.
        } finally { finished.countDown() }
    }
    var complete = false
    try { complete = finished.await(timeoutMillis, TimeUnit.MILLISECONDS) }
    catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    finally {
        // A daemon drainer also bounds the agent's lifetime for input streams whose blocking
        // read cannot be interrupted by close. The standalone agent exits after this returns.
        if (!complete) runCatching(input::close)
    }
}
