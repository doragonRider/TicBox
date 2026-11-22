package dev.ticbox.companion.protocol

import java.io.IOException
import kotlinx.serialization.json.*

/** Optional, value-free details of a UDP failure. The original IOException remains the cause. */
class ScreenUdpTransportException internal constructor(error: IOException, val observation: JsonObject?) : IOException(error.message, error)

object ScreenUdpFailureDiagnostics {
    private val reasons = setOf("screen_udp_unreachable", "screen_udp_connection_lost", "screen_udp_io_failed", "screen_udp_native_send_failed", "screen_udp_send_timeout", "screen_udp_not_connected", "screen_udp_closed")
    private val typeName = Regex("[A-Za-z0-9_$]{1,80}")
    private val socketCodes = setOf("EAGAIN", "ENOBUFS", "ENETUNREACH", "EHOSTUNREACH", "ECONNREFUSED", "ECONNRESET", "EBADF", "EIO", "EPERM", "EACCES", "EINTR", "EINVAL", "EMSGSIZE", "ETIMEDOUT")
    private val errno = Regex("\\b(?:" + socketCodes.joinToString("|") + ")\\b")
    private val numbers = listOf("receivedDatagrams", "authenticatedDatagrams", "rejectedDatagrams", "receivedPings", "receivedPongs", "mediaDatagrams", "ageAuthenticatedMillis", "ageMediaMillis", "agePingMillis", "agePongMillis", "ageLoopMillis", "maxLoopGapMillis")

    internal fun snapshot(error: IOException, stream: Int, server: Boolean, now: Long, counters: ScreenUdpReceiveCounters): JsonObject = buildJsonObject {
        val chain = generateSequence<Throwable>(error) { it.cause }.take(4).toList()
        put("kind", "screen_udp_failure"); put("version", 1)
        put("side", if (server) "sender" else "receiver"); put("stream", if (stream == 1) "video" else "audio")
        put("reason", error.message?.takeIf { it in reasons } ?: "screen_udp_io_failed")
        put("exception", error.javaClass.simpleName.takeIf { it.matches(typeName) } ?: "Exception")
        put("cause", chain.last().javaClass.simpleName.takeIf { it.matches(typeName) } ?: "Exception")
        chain.firstNotNullOfOrNull { it.message?.take(512)?.let { text -> errno.find(text)?.value } }?.let { put("socketCode", it) }
        put("receivedDatagrams", counters.received); put("authenticatedDatagrams", counters.authenticated); put("rejectedDatagrams", counters.rejected)
        put("receivedPings", counters.pings); put("receivedPongs", counters.pongs); put("mediaDatagrams", counters.media)
        put("maxLoopGapMillis", counters.maxLoopGap)
        fun age(key: String, at: Long) { if (at != Long.MIN_VALUE) put(key, (now - at).coerceAtLeast(0)) }
        age("ageAuthenticatedMillis", counters.lastAuthenticated); age("ageMediaMillis", counters.lastMedia)
        age("agePingMillis", counters.lastPing); age("agePongMillis", counters.lastPong); age("ageLoopMillis", counters.previousLoop)
    }

    /** Revalidate remote observations; never forward exception messages, addresses or arbitrary text. */
    fun parse(value: JsonObject): Map<String, String>? = runCatching {
        if (value.size > 24 || value.text("kind") != "screen_udp_failure" || value.number("version") != 1L) return null
        buildMap {
            put("side", value.text("side")?.takeIf { it in setOf("sender", "receiver") } ?: return null)
            put("stream", value.text("stream")?.takeIf { it in setOf("video", "audio") } ?: return null)
            put("reason", value.text("reason")?.takeIf { it in reasons } ?: return null)
            for (key in listOf("exception", "cause")) put(key, value.text(key)?.takeIf { it.matches(typeName) } ?: return null)
            value.text("socketCode")?.takeIf { it in socketCodes }?.let { put("socketCode", it) }
            for (key in numbers) if (key in value) put(key, (value.number(key)?.takeIf { it >= 0 } ?: return null).toString())
        }
    }.getOrNull()
}

/** Created only when diagnostics are selected; snapshots are best effort, not atomic traffic totals. */
internal class ScreenUdpReceiveCounters {
    @Volatile var received = 0L
    @Volatile var authenticated = 0L
    @Volatile var rejected = 0L
    @Volatile var pings = 0L
    @Volatile var pongs = 0L
    @Volatile var media = 0L
    @Volatile var lastAuthenticated = Long.MIN_VALUE
    @Volatile var lastMedia = Long.MIN_VALUE
    @Volatile var lastPing = Long.MIN_VALUE
    @Volatile var lastPong = Long.MIN_VALUE
    @Volatile var maxLoopGap = 0L
    @Volatile var previousLoop = Long.MIN_VALUE
        private set
    fun loop(now: Long) { if (previousLoop != Long.MIN_VALUE) maxLoopGap = maxOf(maxLoopGap, (now - previousLoop).coerceAtLeast(0)); previousLoop = now }
}
