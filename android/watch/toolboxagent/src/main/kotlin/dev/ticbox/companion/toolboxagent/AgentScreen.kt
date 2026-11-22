package dev.ticbox.companion.toolboxagent

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.wifi.WifiManager
import dev.ticbox.companion.protocol.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*

/** What the phone asked for, checked, and how the pinned server is told. */
internal class ScreenRequest(args: JsonObject) {
    val video = args.flag("video") != false
    val audio = video && args.flag("audio") == true
    /** Whether this session reaches the phone over this device's Wi-Fi. */
    val holdWifi = args.flag("holdWifi") == true
    val udp = args.flag("udp") == true
    val videoProtection = if ("udpFec" !in args) ScreenUdpTransport.VideoProtection.NONE else
        ScreenUdpTransport.VideoProtection.entries.firstOrNull { it.wireName == args.text("udpFec") }
            ?: throw ScreenRelayException("screen_udp_unavailable")
    val udpLightKeyframe = args.flag("udpLightKeyframe") == true
    val udpStartupGate = args.flag("udpStartupGate") == true
    val udpEncryption = if ("udpEncryption" in args) args.flag("udpEncryption") ?: throw ScreenRelayException("screen_protocol_mismatch") else true
    val udpDiagnostics = udp && args.flag("udpDiagnostics") == true
    fun validateTransport(uid: Int) {
        if (udp && (uid != 2000 || !holdWifi || !video)) throw ScreenRelayException("screen_udp_unavailable")
        if (!udp && (videoProtection != ScreenUdpTransport.VideoProtection.NONE || udpLightKeyframe || udpStartupGate || !udpEncryption)) throw ScreenRelayException("screen_udp_unavailable")
    }
    val maxSize = (args.number("maxSize") ?: 960).toInt().also { require(it in 64..4096) }
    val requestedFps = (args.number("maxFps") ?: 20).toInt().also { require(it in 1..120) }
    val requestedBitRate = (args.number("bitRate") ?: 1_500_000).toInt().also { require(it in MIN_BIT_RATE..MAX_BIT_RATE) }
    val requestedCodec = (args.text("videoCodec") ?: "h264").also { require(it == "h264" || it == "h265") }
    /** Stay within what this device's encoder declares; see [limit]. */
    val limitToEncoder = args.flag("limitToEncoder") == true
    /** The phone's experimental MediaCodec options, after ours so that they win; see [screenCodecOptions]. */
    val extraCodecOptions = args.text("videoCodecOptions")?.let { screenCodecOptions(it) ?: throw ScreenRelayException("screen_codec_options_invalid") }.orEmpty()
    var fps = requestedFps
        private set
    var bitRate = requestedBitRate
        private set
    var codec = requestedCodec
        private set
    // Two requested frame periods, capped at scrcpy's original 100ms. Only UDP opts in;
    // this repeats an existing frame when input stops, never invents new animation frames.
    internal val repeatAfterMicros get() = ((2_000_000L + fps - 1) / fps).coerceIn(16_667, 100_000)
    // One frame apart, before the encoder's own repeat, which then never doubles one. MediaTek gives a
    // picture back only after a few more inputs, so a pause of the screen must not slow them down.
    internal val tailRepeatMicros get() = (1_000_000L + fps - 1) / fps

    /** Lowers the frame rate and bit rate to the encoder's declared limits; H.265 without an encoder becomes H.264. */
    /** Our options for the encoder that will run, before the phone's; see [screenEncoderDefaults]. */
    var encoderDefaults = ""
        private set

    /**
     * Chooses [encoderDefaults] for the codec, frame rate and bit rate left after [limit]. UDP only:
     * that's where they were needed and tried, and where pictures are held back if the encoder
     * overshoots; Bluetooth and ADB's own stream have neither the room nor that guard.
     */
    fun tune(lookup: ((String) -> ScreenEncoderLimit)?) {
        encoderDefaults = lookup?.takeIf { udp }?.let { screenEncoderDefaults(it(codec), bitRate, fps) }.orEmpty()
    }

    fun limit(lookup: ((String) -> ScreenEncoderLimit)?) {
        if (!limitToEncoder || lookup == null) return
        var chosen = lookup(codec)
        if (!chosen.supported && codec == "h265") { codec = "h264"; chosen = lookup(codec) }
        if (!chosen.supported) return
        chosen.maxFps?.let { fps = minOf(fps, it) }
        chosen.maxBitRate?.let { bitRate = minOf(bitRate, it).coerceAtLeast(MIN_BIT_RATE) }
    }

    // Video sessions keep reporting user activity while open, including on battery.
    // stay_awake only works while charging; keep_active changes no persistent settings.
    // No B-frames: output order is decode order, which the phone declares to its H.264 decoder.
    fun serverOptions(id: String) =
        "scid=$id log_level=warn tunnel_forward=true audio=$audio audio_codec=opus control=true video=$video power_on=true keep_active=$video " +
            "video_codec=$codec video_bit_rate=$bitRate max_fps=$fps max_size=$maxSize " +
            "send_dummy_byte=false send_device_meta=false send_stream_meta=true send_frame_meta=true clipboard_autosync=false cleanup=false" +
            if (udp) " video_prefer_cbr=true video_diagnostics=$udpDiagnostics video_tail_repeat_us=$tailRepeatMicros video_codec_options=" +
                "i-frame-interval=${if (videoProtection == ScreenUdpTransport.VideoProtection.RS_16_4 && udpLightKeyframe) 5 else 1}," +
                "frame-rate=$fps,repeat-previous-frame-after:long=$repeatAfterMicros,max-bframes=0$defaultOptions$extraOptions"
            else " video_codec_options=max-bframes=0$defaultOptions$extraOptions"

    private val defaultOptions get() = if (encoderDefaults.isEmpty()) "" else ",$encoderDefaults"
    private val extraOptions get() = if (extraCodecOptions.isEmpty()) "" else ",$extraCodecOptions"

    companion object {
        const val MIN_BIT_RATE = 64_000
        const val MAX_BIT_RATE = 1_000_000_000
    }
}

/**
 * Keeps this device's Wi-Fi out of power saving for the session. This process is never a foreground
 * app, so it needs the lock that doesn't ask for one. The system releases it if this process dies.
 */
@Suppress("DEPRECATION")
internal fun holdWifi(sdk: Int, commands: PackageCommands, context: () -> Context): WifiManager.WifiLock? = runCatching {
    val lock = context().getSystemService(WifiManager::class.java)
        .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ticbox:remote-screen")
        .apply { setReferenceCounted(false) }
    if (sdk >= 34) withHighPerfLocksAllowed(commands) { lock.acquire() } else lock.acquire()
    lock
}.getOrNull()

/**
 * From Android 14 a high-performance lock becomes a low-latency one, which needs the foreground,
 * unless this flag says otherwise. It's only read while acquiring, so it goes straight back and the
 * device is left as it was. Its new value reaches the Wi-Fi service a moment later, hence the wait.
 */
internal fun withHighPerfLocksAllowed(commands: PackageCommands, settleMillis: Long = 500, acquire: () -> Unit) {
    fun flag(vararg command: String) = commands.run(listOf("device_config", *command), timeoutMillis = 5_000)
    val before = flag("get", "wifi", "high_perf_lock_deprecated").output.trim()
    val changed = before != "false" && flag("put", "wifi", "high_perf_lock_deprecated", "false").code == 0
    if (changed) Thread.sleep(settleMillis)
    try { acquire() } finally {
        if (changed) {
            if (before == "true") flag("put", "wifi", "high_perf_lock_deprecated", "true")
            else flag("delete", "wifi", "high_perf_lock_deprecated")
        }
    }
}

/** Owns a pinned scrcpy server and its video, audio and control sockets; independent of adbd. */
internal class AgentScreen(private val wire: AgentWire, private val environment: AgentEnvironment) {
    fun run(args: JsonObject) {
        val request = ScreenRequest(args)
        request.validateTransport(environment.uid)
        if (request.video) {
            val encoders = AgentScreenEncoders.lookup(environment, request.maxSize)
            if (request.limitToEncoder) request.limit(encoders)
            request.tune(encoders)
        }
        val original = File(environment.scrcpy)
        require(original.isFile && sha256(original) == SERVER_SHA256) { "invalid_scrcpy_server" }
        val closed = AtomicBoolean()
        val owned = ScreenResources(closed)
        fun stop() = owned.close()
        var temporary: File? = null
        var udp: AgentScreenUdp? = null
        var wifi: WifiManager.WifiLock? = null
        try {
            if (request.udp) {
                udp = owned.add(AgentScreenUdp(wire, request.audio, closed, ::stop,
                    diagnostics = request.udpDiagnostics, videoProtection = request.videoProtection, lightKeyframe = request.udpLightKeyframe, startupGate = request.udpStartupGate,
                    security = if (request.udpEncryption) ScreenUdpTransport.SecurityMode.AES_GCM else ScreenUdpTransport.SecurityMode.AUTH_ONLY))
                udp.establish()
            }
            if (closed.get()) return
            // scrcpy switches root launches to shell for Binder/clipboard attribution.
            temporary = if (environment.uid == 0) File.createTempFile("ticbox-screen-", ".jar", File("/data/local/tmp")).also {
                original.inputStream().use { input -> it.outputStream().use(input::copyTo) }
                Files.setAttribute(it.toPath(), "unix:mode", 420)
            } else null
            val jar = temporary ?: original
            val id = SecureRandom().nextInt(Int.MAX_VALUE).toString(16).padStart(8, '0')
            val socketName = "scrcpy_$id"
            val command = "CLASSPATH=${ToolboxProtocol.quote(jar.path)} exec app_process / com.genymobile.scrcpy.Server ${ToolboxProtocol.SCRCPY_VERSION} " +
                request.serverOptions(id)
            val process = startOwnedCommand(command, environment.shell)
            owned.add(AutoCloseable { hangupOwnedCommand(process) })
            // Discard server logs; only an opt-in, bounded encoder metadata record can be forwarded.
            listOf(false to process.inputStream, true to process.errorStream).forEach { (isError, stream) -> Thread({ runCatching { stream.use { input ->
                if (request.udpDiagnostics && isError) {
                    drainScreenEncoderDiagnostics(input) { udp?.reportEncoder(it) }
                } else {
                    val buffer = ByteArray(8192); while (input.read(buffer) >= 0) { }
                }
            } } }, "toolbox-screen-log").apply { isDaemon = true; start() } }
            fun connect(): LocalSocket {
                val deadline = System.nanoTime() + 15_000_000_000L
                while (process.isAlive && System.nanoTime() < deadline && !closed.get()) {
                    val socket = LocalSocket()
                    try {
                        socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
                        return owned.add(socket)
                    } catch (_: IOException) { socket.close(); Thread.sleep(75) }
                }
                throw IOException("screen_server_unavailable")
            }
            wifi = if (request.holdWifi) holdWifi(environment.sdk, environment.commands) { environment.context } else null
            // scrcpy accepts video/audio/control in this order. Each UDP relay owns a separate
            // socket and producer; no video write lock or queue is shared with audio or control.
            val videoSocket = if (request.video) connect() else null
            val audioSocket = if (request.audio) connect() else null
            val controlSocket = connect()
            val control = ScrcpyControl(controlSocket.outputStream)
            val media = udp
            // UDP only: a picture that waits here is one the network couldn't take yet.
            val bitRates = if (media != null) ScreenBitRateController(request.bitRate, apply = { runCatching { control.setVideoBitRate(it) } },
                report = media.rateReporter(request.bitRate)) else null
            media?.reportLimit(request)
            fun relay(action: () -> Unit) {
                if (media != null) media.relay(action) else runScreenRelay(wire, closed, ::stop, action)
            }
            if (videoSocket != null) Thread({
                runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE) }
                relay {
                    if (media != null) relayScrcpyVideo(videoSocket.inputStream) { type, bytes ->
                        if (bitRates != null && type == ToolboxProtocol.VIDEO_FRAME && bytes.size > 8) {
                            val flags = ByteBuffer.wrap(bytes).long
                            if (flags and Long.MIN_VALUE == 0L) bitRates.frame(flags and ((1L shl 62) - 1), System.nanoTime() / 1_000)
                        }
                        media.sendVideo(type, bytes)
                    }
                    else relayScrcpyVideo(videoSocket.inputStream, wire)
                }
            }, "toolbox-screen-video").apply { isDaemon = true; start() }
            if (audioSocket != null) Thread({
                try {
                    if (media != null) relayScrcpyAudio(audioSocket.inputStream, media::sendAudio)
                    else relayScrcpyAudio(audioSocket.inputStream, wire)
                } catch (failure: ScreenRelayException) {
                    relay { throw failure }
                } catch (_: IOException) {
                    // Only the sound ended. UDP reachability failures have their own callback.
                }
            }, "toolbox-screen-audio").apply { isDaemon = true; start() }
            Thread({
                relay {
                    val input = DataInputStream(controlSocket.inputStream)
                    while (!closed.get()) when (input.readUnsignedByte()) {
                        0 -> { val sizeBytes = input.readInt(); require(sizeBytes in 0..1_048_576); val text = ByteArray(sizeBytes); input.readFully(text); wire.send(ToolboxProtocol.CLIPBOARD, text) }
                        1 -> wire.send(ToolboxProtocol.CLIPBOARD_ACK, ByteBuffer.allocate(8).putLong(input.readLong()).array())
                        else -> throw IOException("unexpected_screen_message")
                    }
                }
            }, "toolbox-screen-control").apply { isDaemon = true; start() }
            while (!closed.get()) {
                val packet = if (media != null) media.nextControl() ?: break else wire.read()
                if (packet.type == ToolboxProtocol.CANCEL) break
                require(packet.type == ToolboxProtocol.CONTROL && packet.bytes.size <= 1_048_576)
                control.write(packet.bytes)
            }
        } catch (error: Exception) {
            // A UDP failure/cancellation callback already reported and stopped the session.
            if (!closed.get()) throw error
        } finally {
            runCatching { wifi?.release() }
            stop()
            udp?.awaitTerminalReport()
            if (udp != null) runCatching { wire.input.close() }
            temporary?.delete()
        }
    }
    private fun sha256(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream -> val bytes = ByteArray(65536); while (true) { val n = stream.read(bytes); if (n < 0) break; hash.update(bytes, 0, n) } }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    companion object { const val SERVER_SHA256 = "f5ae23cb377fc366950e549b01789c5370b45a1982359af12190807f170bf5fb" }
}

/** Resource registration and cancellation may race while the UDP peers are negotiating. */
internal class ScreenResources(private val closed: AtomicBoolean) : AutoCloseable {
    private val resources = mutableListOf<AutoCloseable>()
    fun <T : AutoCloseable> add(resource: T): T {
        synchronized(resources) {
            if (!closed.get()) { resources += resource; return resource }
        }
        runCatching(resource::close)
        throw InterruptedIOException("operation_cancelled")
    }
    override fun close() {
        val snapshot = synchronized(resources) {
            closed.set(true)
            resources.toList().also { resources.clear() }
        }
        snapshot.asReversed().forEach { runCatching(it::close) }
    }
}

private val CODEC_OPTION = Regex("([A-Za-z][A-Za-z0-9._-]{0,127})(?::(int|long|float|string))?=([A-Za-z0-9._+-]{1,64})")

/**
 * scrcpy's `key[:type]=value,...` with nothing that could leave `video_codec_options`: the same
 * rule as the phone's `ToolboxScreenCodecOptions`, checked again here. Null when it's broken.
 */
internal fun screenCodecOptions(text: String): String? {
    val entries = text.split(',').map { it.trim() }
    if (entries.size == 1 && entries[0].isEmpty()) return ""
    if (entries.size > 16) return null
    for (entry in entries) {
        val (_, type, value) = CODEC_OPTION.matchEntire(entry)?.destructured ?: return null
        val valid = when (type) {
            "", "int" -> value.toIntOrNull() != null
            "long" -> value.toLongOrNull() != null
            "float" -> value.toFloatOrNull()?.isFinite() == true
            else -> true
        }
        if (!valid) return null
    }
    return entries.joinToString(",").takeIf { it.length <= 512 }
}
