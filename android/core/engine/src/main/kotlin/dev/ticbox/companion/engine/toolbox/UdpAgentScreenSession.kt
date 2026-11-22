package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.flow.receiveAsFlow

/** ADB is the authenticated bootstrap/control lane; media must arrive over the two UDP sockets. */
internal class UdpAgentScreenSession(
    private val link: AgentLink,
    private val worker: CoroutineScope,
    private val host: String,
    private val audio: Boolean,
    private val startupTimeoutMillis: Long = 30_000,
    private val recoveryIntervalMillis: Long = 500,
    private val recoveryAttempts: Int = 3,
    private val trace: ScreenDiagnostics? = null,
    maximumFps: Int = 20,
    override val requiresVideoDecoderReady: Boolean = false,
    private val startupGate: Boolean = false,
    private val encryption: Boolean = true,
    private val collectDiagnostics: Boolean = false,
) : ToolboxScreenSession {
    private val videoReassemblyMessages = ScreenVideoQueue.reassemblyCapacityForFps(maximumFps)
    private val security = if (encryption) ScreenUdpTransport.SecurityMode.AES_GCM else ScreenUdpTransport.SecurityMode.AUTH_ONLY
    private val closed = AtomicBoolean()
    private val pendingTransportFailure = AtomicReference<ToolboxException?>()
    @Volatile private var transportFailureJob: Job? = null
    private val controls = Channel<ToolboxScreenEvent>(16)
    private val startupAcks = Channel<ByteArray>(Channel.CONFLATED)
    private val decoded = ScreenUdpMedia(observer = if (collectDiagnostics) { name, details -> trace?.event("video_$name", details) } else null, maxFps = maximumFps, requireDecoderReady = requiresVideoDecoderReady, requireBootstrap = startupGate, onBootstrapKey = if (startupGate) { token, pts -> startupAcknowledgement(token, "key", pts) } else null)
    private val recoveryLoop = UdpVideoRecoveryLoop(recoveryIntervalMillis, recoveryAttempts,
        state = { decoded.recoveryState }, request = ::requestKeyFrame,
        failed = { trace?.event("video_recovery_failed"); fail(ToolboxException("screen_udp_recovery_failed")) },
        restart = if (startupGate) ::restartVideoCapture else null)
    override val events = controls.receiveAsFlow()
    override val media = decoded.media
    private val firstFrame = CompletableDeferred<Unit>()
    private val mediaReady = CompletableDeferred<Unit>()
    private val sequence = AtomicLong()
    private val transports = mutableListOf<ScreenUdpTransport>()
    @Volatile private var videoTransport: ScreenUdpTransport? = null
    @Volatile private var sender: Pair<Long?, Long>? = null
    private val reading = worker.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
        try {
            val ready = link.read()
            if (ready.type != ToolboxProtocol.SCREEN_UDP_READY) throw ToolboxException("screen_udp_protocol_mismatch")
            val value = ready.objectValue()
            if (value.number("version") != security.wireVersion(ScreenUdpTransport.VideoProtection.RS_16_4).toLong() || value.text("videoProtection") != "rs_16_4" || value.flag("lightKeyframe") != true ||
                (value.text("mediaSecurity") ?: ScreenUdpTransport.SecurityMode.AES_GCM.wireName) != security.wireName ||
                (startupGate && value.flag("startupGate") != true)) {
                throw ToolboxException("screen_udp_protocol_mismatch")
            }
            val encodedKey = value.text("key") ?: throw ToolboxException("screen_udp_protocol_mismatch")
            if (!encodedKey.matches(Regex("[0-9a-f]{64}"))) throw ToolboxException("screen_udp_protocol_mismatch")
            val key = ByteArray(32) { encodedKey.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            fun port(name: String): Int = value.number(name)?.takeIf { it in 1..65535 }?.toInt()
                ?: throw ToolboxException("screen_udp_protocol_mismatch")
            val address = toolboxIo { InetAddress.getByName(host) }
            suspend fun connect(stream: Int, port: Int): ScreenUdpTransport = toolboxIo(onCancel = ::close, onDiscard = ScreenUdpTransport::close) {
                val transport = ScreenUdpTransport.connect(address, port, key, stream,
                    onMessage = { message ->
                        if (!closed.get() && pendingTransportFailure.get() == null) try {
                            if (stream == ScreenUdpTransport.VIDEO) {
                                if (observeRecovery { decoded.video(message) }) firstFrame.complete(Unit)
                            } else decoded.audio(message)
                        } catch (error: Exception) { fail(ToolboxException("screen_udp_protocol_mismatch", error)) }
                    },
                    onFailure = ::transportFailed,
                    timing = ScreenUdpTransport.Timing(videoMaxMessages = videoReassemblyMessages),
                    protection = if (stream == ScreenUdpTransport.VIDEO) ScreenUdpTransport.VideoProtection.RS_16_4 else ScreenUdpTransport.VideoProtection.NONE, security = security, collectDiagnostics = collectDiagnostics,
                    independentVideoMessage = if (stream == ScreenUdpTransport.VIDEO) decoded::canRestartAt else null)
                synchronized(transports) {
                    if (closed.get()) { transport.close(); throw ToolboxException("connection_closed") }
                    transports += transport
                }
                transport
            }
            val (video, sound) = try {
                connect(ScreenUdpTransport.VIDEO, port("videoPort")) to
                    if (audio) connect(ScreenUdpTransport.AUDIO, port("audioPort")) else null
            } finally { key.fill(0) }
            videoTransport = video
            // The transport owns its heartbeat/read loops. Do not block ADB control reception on
            // UDP startup: the peer may send a precise launch error before a handshake completes.
            worker.launch(Dispatchers.IO) {
                try { video.awaitConnected(); sound?.awaitConnected(); trace?.event("udp_connected"); mediaReady.complete(Unit) }
                catch (error: IOException) { fail(ToolboxException("screen_udp_connect_failed", error)) }
            }
            while (!closed.get()) {
                val packet = link.read(if (collectDiagnostics) ::remoteFailureObserved else null)
                when (packet.type) {
                    ToolboxProtocol.CLIPBOARD -> emitControl(ToolboxScreenEvent.Clipboard(packet.bytes.toString(Charsets.UTF_8)))
                    ToolboxProtocol.CLIPBOARD_ACK -> {
                        if (packet.bytes.size != 8) throw ToolboxException("invalid_clipboard_ack")
                        emitControl(ToolboxScreenEvent.ClipboardAcknowledged(ByteBuffer.wrap(packet.bytes).long))
                    }
                    ToolboxProtocol.PROGRESS -> {
                        val metadata = if (startupGate && packet.bytes.size <= 512) runCatching { packet.objectValue() }.getOrNull() else null
                        if (metadata?.text("kind") == "screen_video_start") {
                            if (metadata.number("version") != 1L) throw ToolboxException("screen_udp_protocol_mismatch")
                            val token = metadata.number("generation")?.takeIf { it > 0 } ?: throw ToolboxException("screen_udp_protocol_mismatch")
                            val codec = metadata.text("codec")?.takeIf { it in setOf("h264", "h265") } ?: throw ToolboxException("screen_udp_protocol_mismatch")
                            val width = metadata.number("width")?.takeIf { it in 1..65535 }?.toInt() ?: throw ToolboxException("screen_udp_protocol_mismatch")
                            val height = metadata.number("height")?.takeIf { it in 1..65535 }?.toInt() ?: throw ToolboxException("screen_udp_protocol_mismatch")
                            observeRecovery { decoded.prepareBootstrap(ToolboxScreenEvent.Configuration(codec, width, height, token)) }
                        } else if (collectDiagnostics) {
                            ScreenEncoderDiagnostics.parse(packet.bytes)?.let { encoder ->
                                if (encoder.text("stage") == "vendor_parameters") {
                                    trace?.event("video_encoder_vendor_parameters", screenVendorParameterDetails(encoder))
                                    return@let
                                }
                                val event = when (encoder.text("stage")) {
                                    "output_sample" -> "video_encoder_output"
                                    "input_sample" -> "video_encoder_input"
                                    else -> "video_encoder_configuration"
                                }
                                trace?.event(event, encoder.filterKeys { it != "kind" && it != "version" && it != "capabilities" }.mapValues { (_, v) -> (v as kotlinx.serialization.json.JsonPrimitive).content })
                                (encoder["capabilities"] as? kotlinx.serialization.json.JsonObject)?.let { capabilities ->
                                    val details = capabilities.mapValues { (_, v) -> (v as kotlinx.serialization.json.JsonPrimitive).content }.toMutableMap()
                                    encoder.text("encoder")?.let { details["encoder"] = it }
                                    trace?.event("video_encoder_capabilities", details)
                                }
                            }
                            ScreenUdpSenderDiagnostics.parse(packet)?.let { (name, details) -> trace?.event(name, details) }
                            ScreenUdpSenderDiagnostics.parseSocket(packet)?.let { (name, details) -> trace?.event(name, details) }
                            ScreenUdpSenderDiagnostics.parseRate(packet)?.let { (name, details) -> trace?.event(name, details) }
                            ScreenUdpSenderDiagnostics.parseLimit(packet)?.let { (name, details) -> trace?.event(name, details) }
                            ScreenUdpSenderDiagnostics.parseTiming(packet)?.let {
                                sender = it.micros to System.nanoTime()
                                trace?.event("video_sender_timing", it.details)
                            }
                        }
                    }
                    ToolboxProtocol.END, ToolboxProtocol.EXIT -> { finish(pendingTransportFailure.get()); break }
                    // Reliable-stream media is a protocol violation, never an implicit fallback.
                    else -> throw ToolboxException("screen_udp_protocol_mismatch")
                }
            }
        } catch (error: Throwable) {
            if (!closed.get()) fail(if (error is ToolboxException) error else pendingTransportFailure.get() ?: ToolboxException("screen_udp_stream_failed", error))
        } finally { if (pendingTransportFailure.get() == null) finish(null) }
    }
    private val startup = worker.launch(start = CoroutineStart.LAZY) {
        try { withTimeout(startupTimeoutMillis) { firstFrame.await() } }
        catch (error: TimeoutCancellationException) {
            // Connected, yet no picture ever came: the target's encoder made none, not a network fault.
            val connected = mediaReady.isCompleted && !mediaReady.isCancelled
            if (currentCoroutineContext().isActive) fail(ToolboxException(if (connected) "screen_video_no_frames" else "screen_udp_connect_failed", error))
        }
    }
    private val recovery = worker.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
        try {
            mediaReady.await()
            if (!closed.get()) recoveryLoop.run()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { if (!closed.get()) fail(ToolboxException("screen_udp_recovery_failed", error)) }
    }
    private val acknowledging = worker.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
        try {
            for (packet in startupAcks) sendBounded(ToolboxProtocol.PROGRESS, packet)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (!closed.get()) fail(ToolboxException("screen_udp_recovery_failed")) }
    }
    init {
        require(!startupGate || requiresVideoDecoderReady)
        require(startupTimeoutMillis > 0 && recoveryIntervalMillis > 0 && recoveryAttempts > 0)
        acknowledging.start(); reading.start(); startup.start(); recovery.start()
    }

    private inline fun <T> observeRecovery(action: () -> T): T {
        val before = decoded.recoveryState
        try { return action() } finally {
            if (before != decoded.recoveryState) recoveryLoop.stateChanged()
        }
    }

    private suspend fun requestKeyFrame() {
        trace?.event("video_keyframe_requested")
        sendBounded(ToolboxProtocol.CONTROL, ScrcpyControl.requestKeyFrame())
    }

    private suspend fun restartVideoCapture() {
        trace?.event("video_encoder_restart_requested", mapOf("maximumRestarts" to "1", "confirmationTimeoutMillis" to "10000"))
        sendBounded(ToolboxProtocol.CONTROL, ScrcpyControl.resetVideo())
    }

    private fun startupAcknowledgement(token: Long, phase: String, pts: Long? = null) {
        if (closed.get()) return
        val bytes = buildJsonObject {
            put("kind", "screen_video_ack"); put("version", 1); put("generation", token); put("phase", phase)
            pts?.let { put("ptsMicros", it) }
        }.toString().toByteArray(Charsets.UTF_8)
        startupAcks.trySend(bytes)
        trace?.event("video_bootstrap_ack_queued", mapOf("generation" to token.toString(), "phase" to phase))
    }

    private suspend fun sendBounded(type: Int, bytes: ByteArray) = coroutineScope {
        val write = async(start = CoroutineStart.LAZY) { link.send(type, bytes) }
        val deadline = launch {
            delay(2_000)
            // Publish the typed failure and retire the link before cancellation touches its I/O.
            fail(ToolboxException("screen_udp_recovery_failed"))
            write.cancel()
        }
        try { write.start(); write.await() } finally { deadline.cancel() }
    }

    private fun emitControl(event: ToolboxScreenEvent) {
        if (controls.trySend(event).isFailure && !closed.get()) throw ToolboxException("screen_udp_stream_failed")
    }
    private fun remoteFailureObserved(value: kotlinx.serialization.json.JsonObject) {
        (value["transport"] as? kotlinx.serialization.json.JsonObject)?.let { ScreenUdpFailureDiagnostics.parse(it) }
            ?.let { trace?.event("udp_transport_failure", it) }
        val code = value.text("code")?.takeIf { it in setOf("screen_udp_connect_failed", "screen_udp_disconnected", "screen_udp_recovery_failed", "screen_protocol_mismatch", "screen_stream_failed", "screen_encoder_unavailable") } ?: "other_remote_error"
        trace?.event("udp_remote_failure", mapOf("reason" to code))
    }

    private fun transportFailed(error: IOException) {
        val failure = ToolboxException(if (firstFrame.isCompleted) "screen_udp_disconnected" else "screen_udp_connect_failed", error)
        if (closed.get() || !pendingTransportFailure.compareAndSet(null, failure)) return
        recoveryLoop.close()
        // The Agent stops UDP before its bounded terminal write. Keep ADB readable briefly so
        // a precise remote failure is not replaced by the resulting ICMP/closed-port exception.
        val grace = worker.launch(start = CoroutineStart.UNDISPATCHED) {
            try { delay(300) } finally { finish(failure) }
        }
        transportFailureJob = grace
        if (closed.get()) grace.cancel()
        if (collectDiagnostics) (error as? ScreenUdpTransportException)?.observation?.let { ScreenUdpFailureDiagnostics.parse(it) }
            ?.let { trace?.event("udp_transport_failure", it) }
    }

    private fun fail(error: ToolboxException) { finish(error) }
    private fun finish(error: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        trace?.finish(error)
        transportFailureJob?.cancel()
        controls.close(error); decoded.close(error); startupAcks.close(); recoveryLoop.close()
        firstFrame.cancel(); mediaReady.cancel()
        synchronized(transports) { transports.forEach(ScreenUdpTransport::close); transports.clear() }
        link.closeAsync()
    }
    override fun videoDecoderReady(configuration: ToolboxScreenEvent.Configuration, initializationMillis: Long) {
        if (!closed.get()) observeRecovery { decoded.decoderReady(configuration, initializationMillis) }?.let { startupAcknowledgement(it, "decoder") }
    }
    override fun reportPlayback(sample: ToolboxScreenPlayback) { trace?.playback(sample) }
    override fun timing(): ToolboxScreenTiming {
        if (!collectDiagnostics) return ToolboxScreenTiming(null, null)
        val now = System.nanoTime()
        val sent = sender?.takeIf { now - it.second <= TIMING_MAX_AGE_NANOS }?.first
        return ToolboxScreenTiming(videoTransport?.roundTripNanos(now)?.div(1_000), sent)
    }
    override fun reportControlQueued(action: Int, waitMillis: Long) { trace?.controlQueued(action, waitMillis) }
    override suspend fun input(event: ToolboxScreenInput) {
        suspend fun send() {
            if (event is ToolboxScreenInput.Text) ScrcpyControl.textFrames(event.text).forEach { link.send(ToolboxProtocol.CONTROL, it) }
            else link.send(ToolboxProtocol.CONTROL, ScrcpyControl.encode(event))
        }
        if (trace != null) trace.control(event, ::send) else send()
    }
    override suspend fun getClipboard() { link.send(ToolboxProtocol.CONTROL, byteArrayOf(8, 0)) }
    override suspend fun setClipboard(text: String, paste: Boolean): Long {
        val next = sequence.incrementAndGet()
        link.send(ToolboxProtocol.CONTROL, ScrcpyControl.clipboard(text, paste, next))
        return next
    }
    override fun close() { finish(null); startup.cancel(); recovery.cancel(); reading.cancel(); acknowledging.cancel(); startupAcks.cancel(); controls.cancel(); decoded.cancel() }
    private companion object { const val TIMING_MAX_AGE_NANOS = 3_000_000_000L }
}
