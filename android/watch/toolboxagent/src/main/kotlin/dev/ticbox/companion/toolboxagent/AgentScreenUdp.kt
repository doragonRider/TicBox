package dev.ticbox.companion.toolboxagent

import dev.ticbox.companion.protocol.ScreenUdpTransport
import dev.ticbox.companion.protocol.ScreenUdpTransportException
import dev.ticbox.companion.protocol.ToolboxProtocol
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** ADB carries setup/control; these two authenticated sockets carry media directly over the LAN. */
internal class AgentScreenUdp(
    private val wire: AgentWire,
    audio: Boolean,
    private val closed: AtomicBoolean,
    private val stop: () -> Unit,
    diagnostics: Boolean = false,
    videoProtection: ScreenUdpTransport.VideoProtection = ScreenUdpTransport.VideoProtection.NONE,
    private val lightKeyframe: Boolean = false,
    private val startupGate: Boolean = false,
    private val security: ScreenUdpTransport.SecurityMode = ScreenUdpTransport.SecurityMode.AES_GCM,
) : AutoCloseable {
    private val protection = videoProtection
    private val ready = AtomicBoolean()
    private val terminalPending = AtomicBoolean()
    private val terminalDelivered = CountDownLatch(1)
    private val key = ByteArray(32).also(SecureRandom()::nextBytes)
    private val controls = ArrayBlockingQueue<ToolboxProtocol.Packet>(64)
    private val queuedBytes = AtomicInteger()
    private val video = ScreenUdpTransport.listen(key, ScreenUdpTransport.VIDEO, onFailure = ::failed, protection = videoProtection, security = security, collectDiagnostics = diagnostics,
        accelerationFactory = if (System.getProperty("java.vm.name") == "Dalvik") NativeScreenAcceleration::open else null)
    private val sound = try {
        if (audio) ScreenUdpTransport.listen(key, ScreenUdpTransport.AUDIO, onFailure = ::failed, security = security, collectDiagnostics = diagnostics).also { it.setSendRate(131_072) } else null
    } catch (error: Exception) { video.close(); throw error }
    private val senderDiagnostics = if (diagnostics) AgentScreenSenderDiagnostics({ snapshot ->
        wire.json(ToolboxProtocol.PROGRESS, snapshot)
    }, timing = true) else null
    private var lastVideoSend: ScreenUdpTransport.SendStats? = null
    private val videoSink = ScreenUdpMediaSink(video = true) { type, bytes ->
        if (senderDiagnostics != null) lastVideoSend = video.sendMeasured(type, bytes)
        else video.send(type, bytes)
    }
    private val bootstrap = if (startupGate) ScreenVideoBootstrap(
        announce = { wire.json(ToolboxProtocol.PROGRESS, it) }, transmit = ::sendVideoNow,
    ) else null
    private val audioSink = sound?.let { ScreenUdpMediaSink(video = false, it::send) }

    fun establish(timeoutMillis: Long = 5_000) {
        // This reader also watches cancellation during UDP negotiation and scrcpy startup. It is
        // the only reader for the ADB control channel throughout the lifetime of this session.
        Thread({
            try {
                while (!closed.get()) {
                    val packet = wire.read()
                    if (packet.type == ToolboxProtocol.CANCEL) break
                    if (packet.type == ToolboxProtocol.PROGRESS && bootstrap != null) { bootstrap.acknowledge(packet); continue }
                    if (packet.type != ToolboxProtocol.CONTROL || packet.bytes.size > 1_048_576) {
                        throw ScreenRelayException("screen_protocol_mismatch")
                    }
                    if (packet.bytes.firstOrNull()?.toInt() == 127 && (!lightKeyframe || packet.bytes.size != 1)) {
                        throw ScreenRelayException("screen_protocol_mismatch")
                    }
                    val total = queuedBytes.addAndGet(packet.bytes.size)
                    if (total > 2_097_152 || !controls.offer(packet)) {
                        throw ScreenRelayException("screen_control_overflow")
                    }
                }
            } catch (failure: ScreenRelayException) {
                terminate(ToolboxProtocol.ERROR, failure.code, failure.javaClass.simpleName)
            } catch (_: IOException) {
                // The authenticated ADB owner has gone away. Do not leave LAN listeners or the
                // encoder running, even if UDP itself remains reachable.
            } finally { stop() }
        }, "toolbox-screen-udp-control").apply { isDaemon = true; start() }
        wire.json(ToolboxProtocol.SCREEN_UDP_READY, buildJsonObject {
            put("version", security.wireVersion(protection))
            put("mediaSecurity", security.wireName)
            put("videoProtection", protection.wireName)
            put("lightKeyframe", lightKeyframe)
            put("startupGate", startupGate)
            put("key", key.joinToString("") { "%02x".format(it) })
            put("videoPort", video.port)
            sound?.let { put("audioPort", it.port) }
        })
        try {
            // Both deadlines share one startup budget; neither stream silently changes transport.
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            video.awaitConnected(timeoutMillis)
            sound?.awaitConnected(TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1))
            if (closed.get()) throw InterruptedIOException("operation_cancelled")
            ready.set(true)
        } catch (error: IOException) {
            if (!closed.get()) throw ScreenRelayException("screen_udp_connect_failed")
            throw error
        }
    }

    fun reportEncoder(value: kotlinx.serialization.json.JsonObject) { senderDiagnostics?.event(value) }

    /** Diagnostics only: each change of the encoder's target, without blocking the video relay. */
    fun rateReporter(base: Int): ((String, Int, Long) -> Unit)? = senderDiagnostics?.let { diagnostics ->
        { reason, target, queueMicros ->
            diagnostics.event(buildJsonObject {
                put("kind", RATE_KIND); put("version", 1); put("reason", reason)
                put("targetBitrate", target); put("baseBitrate", base); put("queueMicros", queueMicros)
            })
        }
    }

    /** Diagnostics only: what the request became within the encoder's declared limits. */
    fun reportLimit(request: ScreenRequest) {
        senderDiagnostics?.event(buildJsonObject {
            put("kind", LIMIT_KIND); put("version", 1); put("limitToEncoder", request.limitToEncoder)
            put("codec", request.codec); put("fps", request.fps); put("bitRate", request.bitRate)
            put("requestedCodec", request.requestedCodec); put("requestedFps", request.requestedFps); put("requestedBitRate", request.requestedBitRate)
            if (request.encoderDefaults.isNotEmpty()) put("encoderDefaults", request.encoderDefaults)
        })
    }

    fun sendVideo(type: Int, bytes: ByteArray) {
        if (bootstrap != null) bootstrap.send(type, bytes) else sendVideoNow(type, bytes)
    }

    private fun sendVideoNow(type: Int, bytes: ByteArray) {
        val ingress = if (senderDiagnostics != null) System.nanoTime() else null
        lastVideoSend = null
        videoSink.send(type, bytes, ingress?.div(1_000))
        if (senderDiagnostics != null && type == ToolboxProtocol.VIDEO_FRAME && bytes.size > 8) {
            val flags = ByteBuffer.wrap(bytes).long
            if (flags and Long.MIN_VALUE == 0L) lastVideoSend?.let { stats ->
                senderDiagnostics?.record(bytes.size - 8, flags and (1L shl 62) != 0L, stats, flags and ((1L shl 62) - 1), ingress)
            }
        }
    }
    fun sendAudio(type: Int, bytes: ByteArray) = checkNotNull(audioSink).send(type, bytes)

    fun nextControl(): ToolboxProtocol.Packet? {
        while (!closed.get()) {
            controls.poll(200, TimeUnit.MILLISECONDS)?.let {
                queuedBytes.addAndGet(-it.bytes.size)
                return it
            }
        }
        return null
    }

    private fun failed(error: IOException) = terminate(
        ToolboxProtocol.ERROR,
        if (ready.get()) "screen_udp_disconnected" else "screen_udp_connect_failed",
        error.javaClass.simpleName,
        (error as? ScreenUdpTransportException)?.observation,
    )

    /** Media/control source failures use the same bounded terminal delivery as UDP failures. */
    fun relay(action: () -> Unit) {
        try { action(); terminate(ToolboxProtocol.END) }
        catch (error: Exception) {
            if (error is ScreenUdpTransportException) failed(error)
            else terminate(ToolboxProtocol.ERROR, screenRelayErrorCode(error), error.javaClass.simpleName)
        }
    }

    private fun terminate(type: Int, code: String? = null, errorType: String? = null, transport: kotlinx.serialization.json.JsonObject? = null) {
        if (closed.get() || !terminalPending.compareAndSet(false, true)) return
        // stdout can block behind an unavailable ADB transport. Release the encoder and both UDP
        // sockets first, then allow only a short best-effort delivery window before Agent exit.
        stop()
        Thread({
            try {
                if (type == ToolboxProtocol.ERROR) wire.json(type, buildJsonObject { put("code", code); put("type", errorType); transport?.let { put("transport", it) } })
                else wire.send(type)
            } catch (_: Exception) { /* The authenticated owner may already have disconnected. */ }
            finally { terminalDelivered.countDown() }
        }, "toolbox-screen-udp-result").apply { isDaemon = true; start() }
    }

    fun awaitTerminalReport(timeoutMillis: Long = 250) {
        if (!terminalPending.get()) return
        try { terminalDelivered.await(timeoutMillis, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    override fun close() { bootstrap?.close(); senderDiagnostics?.close(); video.close(); sound?.close(); key.fill(0); controls.clear() }

    companion object {
        const val RATE_KIND = "screen_udp_rate"
        const val LIMIT_KIND = "screen_encoder_limit"
    }
}

/**
 * Each datagram message holds normal framed Toolbox media packets. Every video keyframe carries
 * its session header and codec initialization, so losing a prior datagram never makes subsequent
 * keyframes undecodable. Opus initialization is small and accompanies every audio packet.
 */
internal class ScreenUdpMediaSink(private val video: Boolean, private val sendMessage: (Int, ByteArray) -> Unit) {
    private val configType = if (video) ToolboxProtocol.VIDEO_CONFIG else ToolboxProtocol.AUDIO_CONFIG
    private val frameType = if (video) ToolboxProtocol.VIDEO_FRAME else ToolboxProtocol.AUDIO_FRAME
    private var configuration: ByteArray? = null
    private var configurationObject: kotlinx.serialization.json.JsonObject? = null
    private val codecConfig = mutableListOf<ByteArray>()
    private var collectingCodecConfig = false
    private var configBytes = 0

    fun send(type: Int, bytes: ByteArray, sourceRelayMicros: Long? = null) {
        when (type) {
            configType -> {
                configuration = bytes; configurationObject = null
                codecConfig.clear(); collectingCodecConfig = false; configBytes = 0
                // Advertise video dimensions immediately so the phone can prepare its codec
                // before any reference frames arrive. Audio absence is explicit too.
                emit(emptyList(), sourceRelayMicros)
            }
            frameType -> {
                if (bytes.size < 8 || configuration == null) throw ScreenRelayException("screen_protocol_mismatch")
                val flags = ByteBuffer.wrap(bytes).long
                val isConfig = flags and Long.MIN_VALUE != 0L
                if (isConfig) {
                    if (!video) {
                        if (bytes.size > 4104) throw ScreenRelayException("screen_udp_frame_too_large")
                        if (bytes.size < 27 || !bytes.copyOfRange(8, 16).contentEquals("OpusHead".toByteArray(Charsets.US_ASCII))) {
                            throw ScreenRelayException("screen_protocol_mismatch")
                        }
                    }
                    if (!collectingCodecConfig) { codecConfig.clear(); configBytes = 0 }
                    collectingCodecConfig = true
                    if (codecConfig.none { it.contentEquals(bytes) }) {
                        configBytes += bytes.size + 5
                        if (codecConfig.size >= 16 || configBytes > 262_144) throw ScreenRelayException("screen_udp_frame_too_large")
                        codecConfig += bytes
                    }
                    emit(codecConfig, sourceRelayMicros)
                } else {
                    collectingCodecConfig = false
                    val includeConfig = !video || flags and (1L shl 62) != 0L
                    emit(if (includeConfig) codecConfig + listOf(bytes) else listOf(bytes), sourceRelayMicros)
                }
            }
            else -> throw ScreenRelayException("screen_protocol_mismatch")
        }
    }

    private fun emit(frames: List<ByteArray>, sourceRelayMicros: Long?) {
        val original = configuration ?: throw ScreenRelayException("screen_protocol_mismatch")
        // No timing field, JSON allocation or clock sampling when diagnostics are off. The source
        // timestamp is used only for same-frame interval differences; the two clocks need not align.
        val config = if (sourceRelayMicros == null) original else {
            val metadata = configurationObject ?: ToolboxProtocol.Packet(configType, original).objectValue().also { configurationObject = it }
            buildJsonObject { metadata.forEach { (key, value) -> put(key, value) }; put("sourceRelayMicros", sourceRelayMicros) }.toString().toByteArray(Charsets.UTF_8)
        }
        val size = config.size.toLong() + 5 + frames.sumOf { it.size.toLong() + 5 }
        if (size > if (video) ToolboxProtocol.MAX_PACKET else ScreenUdpTransport.MAX_AUDIO_MESSAGE) throw ScreenRelayException("screen_udp_frame_too_large")
        val output = ByteArrayOutputStream(size.toInt())
        ToolboxProtocol.write(output, configType, config)
        frames.forEach { ToolboxProtocol.write(output, frameType, it) }
        sendMessage(frameType, output.toByteArray())
    }
}
