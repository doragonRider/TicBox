package dev.ticbox.companion.toolboxagent

import dev.ticbox.companion.protocol.*
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.json.*

/**
 * Only the initial reference chain of each capture generation is acknowledged. Dimensions/acks
 * use the authenticated control lane; every image, including retries, still uses UDP. The producer
 * retains at most its current keyframe and cannot send dependent deltas until that key is accepted.
 * No extra worker, synthetic input, codec restart or unbounded GOP cache is needed on a static screen.
 */
internal class ScreenVideoBootstrap(
    private val announce: (JsonObject) -> Unit,
    private val transmit: (Int, ByteArray) -> Unit,
    private val decoderTimeoutMillis: Long = 10_000,
    private val keyRetryMillis: Long = 500,
    private val keyAttempts: Int = 5,
) : AutoCloseable {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var closed = false
    private var generation = 0L
    private var decoderReady = false
    private var keyAccepted = false
    private var keyPts: Long? = null

    init { require(decoderTimeoutMillis > 0 && keyRetryMillis > 0 && keyAttempts > 0) }

    /** One producer calls this serially, preserving the source's configuration/CSD/frame order. */
    fun send(type: Int, bytes: ByteArray) {
        if (type == ToolboxProtocol.VIDEO_CONFIG) {
            val value = ToolboxProtocol.Packet(type, bytes).objectValue()
            val codec = value.text("codec")?.takeIf { it in setOf("h264", "h265") } ?: mismatch()
            val width = value.number("width")?.takeIf { it in 1..65535 } ?: mismatch()
            val height = value.number("height")?.takeIf { it in 1..65535 } ?: mismatch()
            val next = lock.withLock {
                checkOpen()
                if (generation == Long.MAX_VALUE) mismatch()
                generation++; decoderReady = false; keyAccepted = false; keyPts = null
                generation
            }
            val configuration = buildJsonObject { put("codec", codec); put("width", width); put("height", height); put("generation", next) }
            announce(buildJsonObject {
                put("kind", "screen_video_start"); put("version", 1)
                configuration.forEach { (key, item) -> put(key, item) }
            })
            if (!await(decoderTimeoutMillis) { decoderReady }) throw ScreenRelayException("screen_udp_connect_failed")
            transmit(type, configuration.toString().toByteArray(Charsets.UTF_8))
            return
        }
        if (type != ToolboxProtocol.VIDEO_FRAME || bytes.size < 8) mismatch()
        val flags = ByteBuffer.wrap(bytes).long
        val confirm = lock.withLock {
            checkOpen()
            if (generation == 0L) mismatch()
            if (!keyAccepted && flags and Long.MIN_VALUE == 0L && flags and (1L shl 62) != 0L) {
                keyPts = flags and ((1L shl 62) - 1); true
            } else false
        }
        if (!confirm) { transmit(type, bytes); return }
        repeat(keyAttempts) {
            lock.withLock { checkOpen(); if (keyAccepted) return }
            // Each retry gets a fresh UDP message/packet sequence and nonce; source bytes and PTS
            // stay identical. The receiver acknowledges but does not decode duplicate bootstrap keys.
            transmit(type, bytes)
            if (await(keyRetryMillis) { keyAccepted }) return
        }
        throw ScreenRelayException("screen_udp_recovery_failed")
    }

    /** Consumed by the independent control reader, never forwarded as a scrcpy input command. */
    fun acknowledge(packet: ToolboxProtocol.Packet) {
        if (packet.type != ToolboxProtocol.PROGRESS || packet.bytes.size > 512) mismatch()
        val value = runCatching { packet.objectValue() }.getOrNull() ?: mismatch()
        if (value.text("kind") != "screen_video_ack" || value.number("version") != 1L) mismatch()
        val token = value.number("generation")?.takeIf { it > 0 } ?: mismatch()
        val phase = value.text("phase")?.takeIf { it in setOf("decoder", "key") } ?: mismatch()
        val pts = if (phase == "key") value.number("ptsMicros")?.takeIf { it in 0..((1L shl 62) - 1) } ?: mismatch() else null
        lock.withLock {
            if (closed || token != generation) return
            when (phase) {
                "decoder" -> decoderReady = true
                "key" -> if (decoderReady && keyPts != null && keyPts == pts) keyAccepted = true
            }
            changed.signalAll()
        }
    }

    private fun await(millis: Long, complete: () -> Boolean): Boolean = lock.withLock {
        var remaining = TimeUnit.MILLISECONDS.toNanos(millis)
        while (true) {
            checkOpen()
            if (complete()) return true
            if (remaining <= 0) return false
            try { remaining = changed.awaitNanos(remaining) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw InterruptedIOException("operation_cancelled") }
        }
        @Suppress("UNREACHABLE_CODE") false
    }
    override fun close() = lock.withLock { closed = true; changed.signalAll() }
    private fun checkOpen() { if (closed) throw InterruptedIOException("operation_cancelled") }
    private fun mismatch(): Nothing = throw ScreenRelayException("screen_protocol_mismatch")
}
