package dev.ticbox.companion.api

import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ToolboxBackendKind { ADB, ROOT_BRIDGE }
enum class ToolboxPhase { DISCONNECTED, CONNECTING, CONNECTED, FAILED, CLOSED }
enum class ToolboxCapability {
    INSTALL_APK, INSTALL_SPLITS, APPS, CLIPBOARD, COMMAND, SCREEN, FILES,
    /** These are actual additional root operations, not aliases for the shell operations. */
    PRIVATE_APP_DATA, CLEAR_APP_CACHE, FILE_OWNERSHIP,
    /** Fixed OEM health settings through the privileged Local Bridge provider client. */
    HEALTH_MONITORING, HEALTH_MODULE,
    /** Interactive shell with a real controlling terminal; separate from COMMAND pipes. */
    TERMINAL,
    /** Content-verified file snapshots and retained, atomic resumable uploads. */
    FILE_RESUME,
    /** Authenticated direct UDP video/audio, available through wireless ADB only. */
    SCREEN_UDP,
    /** Negotiated authenticated Reed–Solomon video shards (UDP wire version 2). */
    SCREEN_UDP_FEC,
    /** Explicit authenticated plaintext UDP media; ADB bootstrap remains encrypted. */
    SCREEN_UDP_AUTH_ONLY,
    /** Reliable decoder/reference readiness metadata with bounded UDP-only first-key retries. */
    SCREEN_UDP_STARTUP,
    /** Pinned server supports a sync-frame request without restarting capture. */
    SCREEN_KEYFRAME,
    /** Reports the target's encoder limits and keeps a stream within them when asked. */
    SCREEN_ENCODER_LIMITS,
    /** Extra MediaCodec options for the screen encoder; see [ToolboxScreenCodecOptions]. */
    SCREEN_CODEC_OPTIONS,
}
data class ToolboxCapabilities(val uid: Int, val sdk: Int, val features: Set<ToolboxCapability>, val protectedPackages: Set<String> = emptySet()) {
    val root: Boolean get() = uid == 0
}
/** Opaque physical identity and this backend's connection generation; neither is an endpoint. */
data class ToolboxTarget(val deviceId: String, val connectionId: Long, val kind: ToolboxBackendKind)
data class ToolboxBackendState(
    val kind: ToolboxBackendKind,
    val phase: ToolboxPhase = ToolboxPhase.DISCONNECTED,
    val device: String? = null,
    val capabilities: ToolboxCapabilities? = null,
    val error: String? = null,
    val target: ToolboxTarget? = null,
    /** The previously accepted device, until an explicit confirmation accepts this target. */
    val previousDeviceId: String? = null,
)
data class AdbEndpoint(val host: String, val port: Int) {
    init { require(host.isNotBlank() && host.none { it == '\u0000' || it.isWhitespace() }); require(port in 1..65535) }
}
/** Latest authentication observation with this app's ADB identity; discovery alone is UNKNOWN. */
enum class AdbPairingStatus { UNKNOWN, PAIRED, REQUIRED }
data class AdbDiscoveredEndpoint(
    val name: String, val endpoint: AdbEndpoint, val pairing: Boolean,
    val pairingStatus: AdbPairingStatus = AdbPairingStatus.UNKNOWN,
    /**
     * The device connected to last, which may be connected to again without asking: same service
     * name, or same host if typed in. Never after the person disconnected, until they connect again.
     */
    val autoConnect: Boolean = false,
)

/** A fresh stream is opened for each transfer; the backend closes it. Size is mandatory for PM sessions. */
class ToolboxApk(val name: String, val size: Long, val open: () -> InputStream) {
    init { require(name.isNotBlank() && '\u0000' !in name); require(size > 0) }
    override fun toString() = "ToolboxApk(size=$size)"
}
data class ToolboxProgress(val completedBytes: Long, val totalBytes: Long?, val item: Int = 0)
data class ToolboxInstallOptions(val replace: Boolean = true, val allowDowngrade: Boolean = false, val grantRuntimePermissions: Boolean = false, val userId: Int = 0)
data class ToolboxInstallResult(val success: Boolean, val output: String)

/**
 * What a package file (.apks, .xapk, .apkm, .apk+) holds, with the APKs this watch needs picked out.
 * Nothing has reached the watch yet.
 */
class ToolboxBundle(
    val packageName: String, val label: String?, val versionName: String?,
    val apks: List<ToolboxApk>, val skippedApks: Int, val data: List<ToolboxBundleData>,
) {
    val apkBytes: Long get() = apks.sumOf { it.size }
    val dataBytes: Long get() = data.sumOf { it.size }
    override fun toString() = "ToolboxBundle(apks=${apks.size}, skippedApks=$skippedApks, data=${data.size})"
}
/** A file the app expects beside it, such as an OBB. The path is relative to shared storage. */
class ToolboxBundleData(val path: String, val size: Long, val open: () -> InputStream) {
    override fun toString() = "ToolboxBundleData(size=$size)"
}
/** The install's own result, and the code of what stopped its data being copied afterwards. */
data class ToolboxBundleResult(val install: ToolboxInstallResult, val dataFailure: String? = null)

data class ToolboxApp(
    val packageName: String, val label: String, val versionName: String?, val versionCode: Long,
    val uid: Int, val enabled: Boolean, val system: Boolean, val apkPaths: List<String>, val dataDirectory: String?,
    val target: ToolboxTarget? = null,
)
enum class ToolboxAppAction { START, FORCE_STOP, ENABLE, DISABLE, UNINSTALL, CLEAR_DATA, CLEAR_CACHE, SUSPEND, UNSUSPEND }
data class ToolboxAppRequest(val packageName: String, val action: ToolboxAppAction, val userId: Int = 0, val keepData: Boolean = false)
data class ToolboxCommandResult(val exitCode: Int, val stdout: ByteArray, val stderr: ByteArray, val truncated: Boolean = false, val timedOut: Boolean = false) {
    val successful: Boolean get() = exitCode == 0 && !timedOut
    override fun toString() = "ToolboxCommandResult(exitCode=$exitCode, stdoutBytes=${stdout.size}, stderrBytes=${stderr.size}, truncated=$truncated, timedOut=$timedOut)"
}
data class ToolboxCommandOptions(val timeoutMillis: Long = 60_000, val maxCaptureBytes: Int = 1_048_576) {
    init { require(timeoutMillis in 1..86_400_000); require(maxCaptureBytes in 1..67_108_864) }
}
sealed interface ToolboxCommandEvent {
    class Stdout(val bytes: ByteArray) : ToolboxCommandEvent { override fun toString() = "Stdout(bytes=${bytes.size})" }
    class Stderr(val bytes: ByteArray) : ToolboxCommandEvent { override fun toString() = "Stderr(bytes=${bytes.size})" }
    data class Exited(val code: Int, val timedOut: Boolean = false) : ToolboxCommandEvent
}
/** One event collector; closing hangs up the owned command session without forced termination. */
interface ToolboxCommandSession : AutoCloseable {
    val events: Flow<ToolboxCommandEvent>
    suspend fun write(bytes: ByteArray)
    suspend fun closeInput()
    suspend fun awaitExit(): ToolboxCommandEvent.Exited
    /** Request an interrupt (SIGINT); the command may handle or ignore it. */
    suspend fun cancel(): Unit = throw ToolboxException("command_interrupt_unavailable")
    /** Explicitly request forced termination of this session's owned process/group. */
    suspend fun forceStop(): Unit = throw ToolboxException("command_force_stop_unavailable")
}

enum class ToolboxFileKind { FILE, DIRECTORY, SYMLINK, OTHER }
data class ToolboxFile(val path: String, val name: String, val kind: ToolboxFileKind, val size: Long, val modifiedMillis: Long, val mode: Int, val uid: Int, val gid: Int, val linkTarget: String? = null, val target: ToolboxTarget? = null)
data class ToolboxWriteOptions(val overwrite: Boolean = false, val atomic: Boolean = true, val mode: Int? = null)
enum class ToolboxFileAction { MAKE_DIRECTORY, DELETE, MOVE, COPY, CHMOD, CHOWN, SYMLINK }
data class ToolboxFileRequest(
    val action: ToolboxFileAction, val path: String, val destination: String? = null,
    val recursive: Boolean = false, val overwrite: Boolean = false,
    val mode: Int? = null, val uid: Int? = null, val gid: Int? = null,
)

enum class ToolboxVideoCodec { H264, H265 }

data class ToolboxScreenOptions(
    val video: Boolean = true,
    val maxSize: Int = 960,
    val maxFps: Int = 20,
    val bitRate: Int = 1_500_000,
    val codec: ToolboxVideoCodec = ToolboxVideoCodec.H264,
    /** Asks for the device's sound as well. A device that can't capture it still sends the picture. */
    val audio: Boolean = false,
    /** Direct authenticated UDP media. Failure is terminal; never retries over the reliable stream. */
    val udp: Boolean = false,
    /** Only UDP media: false exposes audio/video while retaining authentication and replay checks. */
    val udpEncryption: Boolean = true,
    /** Opt in to frame statistics and Remote Screen diagnostics for this session. */
    val collectDiagnostics: Boolean = false,
    /**
     * Lower the frame rate and bit rate to what the target's encoder declares, and use H.264 where
     * it has no H.265 encoder. Ignored by a target without [ToolboxCapability.SCREEN_ENCODER_LIMITS].
     */
    val limitToEncoder: Boolean = false,
    /**
     * For experiments: MediaCodec options the target's encoder gets after the backend's own, so a
     * key given here wins. Normalised by [ToolboxScreenCodecOptions]; ignored by a target without
     * [ToolboxCapability.SCREEN_CODEC_OPTIONS].
     */
    val videoCodecOptions: String = "",
) {
    init {
        require(maxSize in 64..4096); require(maxFps in 1..120); require(bitRate in 64_000..1_000_000_000)
        require(ToolboxScreenCodecOptions.normalize(videoCodecOptions) == videoCodecOptions)
    }
}

/**
 * scrcpy's `video_codec_options`: `key[:type]=value` entries separated by commas, type `int` when
 * left out, or `long`, `float`, `string`. Only letters, digits and `._-` (values also `+`), so an
 * entry can't reach the rest of the server's command line. The target checks the same rule.
 */
object ToolboxScreenCodecOptions {
    const val MAX_ENTRIES = 16
    const val MAX_LENGTH = 512
    private val ENTRY = Regex("([A-Za-z][A-Za-z0-9._-]{0,127})(?::(int|long|float|string))?=([A-Za-z0-9._+-]{1,64})")

    /** The options without spaces, "" for none, or null when any entry breaks the rule. */
    fun normalize(text: String): String? {
        val entries = text.split(',').map { it.trim() }
        if (entries.size == 1 && entries[0].isEmpty()) return ""
        if (entries.size > MAX_ENTRIES) return null
        for (entry in entries) {
            val (_, type, value) = ENTRY.matchEntire(entry)?.destructured ?: return null
            val valid = when (type) {
                "", "int" -> value.toIntOrNull() != null
                "long" -> value.toLongOrNull() != null
                "float" -> value.toFloatOrNull()?.isFinite() == true
                else -> true
            }
            if (!valid) return null
        }
        return entries.joinToString(",").takeIf { it.length <= MAX_LENGTH }
    }
}
/**
 * What the target's default encoder for [codec] declares at the size a stream of the requested long
 * side would use. Declared, not measured: a device may fall short of it under load.
 */
data class ToolboxScreenEncoder(
    val codec: ToolboxVideoCodec,
    val supported: Boolean,
    val name: String? = null,
    val hardware: Boolean? = null,
    val width: Int? = null,
    val height: Int? = null,
    val maxFps: Int? = null,
    val maxBitRate: Int? = null,
)
sealed interface ToolboxScreenEvent {
    data class Configuration(val codec: String, val width: Int, val height: Int, val generation: Long = 0) : ToolboxScreenEvent
    class Frame(
        val ptsMicros: Long, val keyFrame: Boolean, val codecConfig: Boolean, val bytes: ByteArray,
        /** Null when the transport doesn't observe it, as over the reliable stream. */
        val receipt: ToolboxScreenReceipt? = null,
    ) : ToolboxScreenEvent {
        override fun toString() = "Frame(ptsMicros=$ptsMicros, bytes=${bytes.size})"
    }
    /** Null [codec]: the device can't capture sound, so only the picture follows. */
    data class AudioConfiguration(val codec: String?) : ToolboxScreenEvent
    class AudioFrame(val ptsMicros: Long, val codecConfig: Boolean, val bytes: ByteArray) : ToolboxScreenEvent {
        override fun toString() = "AudioFrame(ptsMicros=$ptsMicros, bytes=${bytes.size})"
    }
    class Clipboard(val text: String) : ToolboxScreenEvent { override fun toString() = "Clipboard(contents=<redacted>)" }
    data class ClipboardAcknowledged(val sequence: Long) : ToolboxScreenEvent
    data class Closed(val reason: String?) : ToolboxScreenEvent
}
sealed interface ToolboxScreenInput {
    data class Key(val keyCode: Int, val down: Boolean, val repeat: Int = 0, val metaState: Int = 0) : ToolboxScreenInput
    class Text(val text: String) : ToolboxScreenInput { override fun toString() = "Text(contents=<redacted>)" }
    /** Coordinates refer to the current encoded frame, including after rotation. */
    data class Touch(val action: Int, val pointerId: Long, val x: Int, val y: Int, val width: Int, val height: Int, val pressure: Float = 1f, val actionButton: Int = 0, val buttons: Int = 1) : ToolboxScreenInput
    data class Scroll(val x: Int, val y: Int, val width: Int, val height: Int, val horizontal: Float, val vertical: Float, val buttons: Int = 0) : ToolboxScreenInput
    data class DisplayPower(val on: Boolean) : ToolboxScreenInput
}
/** Ordered reassembly delivery on this phone, on System.nanoTime, and accumulated receive work. */
data class ToolboxScreenReceipt(val completedNanos: Long, val workNanos: Long, val sourceRelayMicros: Long? = null)
/** Recent link and sender timing; metadata only, and not end-to-end latency. Null was not measured lately. */
data class ToolboxScreenTiming(
    /** UDP heartbeat round trip, including datagrams queued ahead of it on either side. */
    val roundTripMicros: Long?,
    /** Mean per frame on the target: capture timestamp to Agent relay (including codec/local-socket queues), then FEC, authentication and socket sends; pacing waits excluded. Not isolated hardware encode time. */
    val senderMicros: Long?,
)
/** Independent single-collector media lanes; [ToolboxScreenSession.events] then carries control only. */
data class ToolboxScreenMedia(val video: Flow<ToolboxScreenEvent>, val audio: Flow<ToolboxScreenEvent>)

/** Two actually presented frames, not independent window maxima. Signed changes are intentional.
 * PTS spacing includes any missing/discarded frames and cannot alone establish source inactivity.
 */
data class ToolboxScreenPresentationGap(
    val framePtsMicros: Long, val intervalMicros: Long, val ptsGapMicros: Long?,
    val arrivalGapMicros: Long, val decodeChangeMicros: Long, val displayChangeMicros: Long,
    /** Same pair at ordered UDP reassembly delivery; null when either receipt was unavailable. */
    val receiveGapMicros: Long? = null,
    /** Change in delivery-to-decoder-entry wait. Signed; receiveGap + queueChange = arrivalGap. */
    val queueChangeMicros: Long? = null,
    /** Source clock interval at the Agent relay, not a cross-device timestamp subtraction. */
    val sourceRelayGapMicros: Long? = null,
    val captureToRelayChangeMicros: Long? = null,
    /** Change from source relay to local ordered reassembly, including sending/network/receive work. */
    val deliveryChangeMicros: Long? = null,
)

/** Screen-session counters and rolling-window timing, not optical display measurements.
 * A null maximum means no comparable observation exists in the current window.
 */
data class ToolboxScreenOutputScheduler(
    val vsyncCallbacks: Long, val emptyVsyncCallbacks: Long, val lateVsyncCallbacks: Long,
    val queueOverflowDrops: Long, val watchdogDrops: Long, val watchdogPresentations: Long,
    val callbackDelayMaxMicros: Long?, val outputQueueWaitMaxMicros: Long?,
)

/** Local playback metadata only; no image, text, clipboard or touch-coordinate contents. */
data class ToolboxScreenPlayback(
    val receivedFps: Int,
    val renderedFps: Int,
    val frameGapMaxMillis: Int?,
    val arrivalVariationMaxMillis: Int?,
    val decodeMaxMillis: Int?,
    val displayMaxMillis: Int?,
    val inputWaitMaxMillis: Int?,
    val oversizedFrames: Long,
    val decoderName: String?,
    val hardwareDecoder: Boolean?,
    val receivedMbps: Double? = null,
    /** H.264 only: true while the decoder gets parameter sets declaring no reordering, false once B pictures arrived. */
    val h264LowDelay: Boolean? = null,
    val displayDroppedFrames: Long = 0,
    /** Actual presentation callback intervals over the current window; idle periods included. */
    val presentedIntervalSamples: Int = 0,
    val presentedIntervalP50Micros: Long? = null,
    val presentedIntervalP95Micros: Long? = null,
    val presentedIntervalP99Micros: Long? = null,
    val presentedIntervalMaxMicros: Long? = null,
    val presentedLow1Fps: Double? = null,
    val presentedLongIntervals: Int = 0,
    val invalidPresentationTimes: Long = 0,
    val displayRefreshHz: Double? = null,
    val longestPresentationGap: ToolboxScreenPresentationGap? = null,
    val outputScheduler: ToolboxScreenOutputScheduler? = null,
    /** Largest positive (presentation interval - PTS interval), independent of the longest raw gap.
     * No source/idle intervals are removed from the original presentation statistics. */
    val largestPipelineExpansion: ToolboxScreenPresentationGap? = null,
)

interface ToolboxScreenSession : AutoCloseable {
    /** One collector. Picture and sound stay encoded as their configurations name; playing them belongs to the frontend. */
    val events: Flow<ToolboxScreenEvent>
    /** Null retains the legacy combined event stream. Collect all lanes concurrently when present. */
    val media: ToolboxScreenMedia? get() = null
    /** When true, consume Configuration and acknowledge a started codec with available input before video delivery. */
    val requiresVideoDecoderReady: Boolean get() = false
    /** Ignores stale configurations. Call after the matching decoder starts and can accept input. */
    fun videoDecoderReady(configuration: ToolboxScreenEvent.Configuration, initializationMillis: Long) = Unit
    /** Nonblocking best-effort diagnostics. Implementations may ignore local playback telemetry. */
    fun reportPlayback(sample: ToolboxScreenPlayback) = Unit
    /** Nonblocking; null when this transport measures neither. */
    fun timing(): ToolboxScreenTiming? = null
    /** DOWN/UP queue residence only; coordinates and text never enter diagnostics. */
    fun reportControlQueued(action: Int, waitMillis: Long) = Unit
    suspend fun input(event: ToolboxScreenInput)
    suspend fun getClipboard()
    suspend fun setClipboard(text: String, paste: Boolean = false): Long
}

interface WatchToolboxBackend : AutoCloseable {
    val state: StateFlow<ToolboxBackendState>
    /** Pins a complete operation, including bundle sub-requests, to this connection. */
    fun bind(target: ToolboxTarget = state.value.target ?: throw ToolboxException("device_identity_required")): WatchToolboxBackend = BoundToolboxBackend(this, target)
    /** Acknowledges an identity change only for the connection the user actually reviewed. */
    fun confirmDevice(target: ToolboxTarget) {
        if (state.value.phase != ToolboxPhase.CONNECTED || state.value.target != target) throw ToolboxException("connection_replaced")
    }
    suspend fun connect()
    suspend fun disconnect()
    suspend fun install(apks: List<ToolboxApk>, options: ToolboxInstallOptions = ToolboxInstallOptions(), progress: (ToolboxProgress) -> Unit = {}): ToolboxInstallResult
    /** Null for a plain APK, which [install] takes as it is. Asks the watch which parts it needs. */
    suspend fun readBundle(file: ToolboxApk): ToolboxBundle?
    /** One session for the APKs, then the data if they installed; progress items count both in turn. */
    suspend fun installBundle(bundle: ToolboxBundle, options: ToolboxInstallOptions = ToolboxInstallOptions(), progress: (ToolboxProgress) -> Unit = {}): ToolboxBundleResult
    suspend fun apps(userId: Int = 0): List<ToolboxApp>
    suspend fun app(request: ToolboxAppRequest): ToolboxCommandResult
    suspend fun permission(packageName: String, permission: String, grant: Boolean, userId: Int = 0): ToolboxCommandResult
    suspend fun appOp(packageName: String, operation: String, mode: String, userId: Int = 0): ToolboxCommandResult
    /** Includes private credential/device-protected data when root permits it. */
    suspend fun backupAppData(packageName: String, output: OutputStream, userId: Int = 0, progress: (ToolboxProgress) -> Unit = {})
    suspend fun execute(command: String, options: ToolboxCommandOptions = ToolboxCommandOptions()): ToolboxCommandResult
    /** A persistent shell by default; pipe-backed sessions have no controlling terminal. */
    suspend fun openCommand(command: String = "/system/bin/sh", options: ToolboxCommandOptions = ToolboxCommandOptions(timeoutMillis = 86_400_000)): ToolboxCommandSession
    /** Returns only after the remote PTY shell starts; output is not used as a readiness signal. */
    suspend fun openTerminal(options: ToolboxTerminalOptions = ToolboxTerminalOptions()): ToolboxTerminalSession = throw ToolboxException("terminal_unavailable")
    suspend fun clipboard(): String
    suspend fun setClipboard(text: String)
    suspend fun openScreen(options: ToolboxScreenOptions = ToolboxScreenOptions()): ToolboxScreenSession
    /** Both codecs at the long side [maxSize]; needs [ToolboxCapability.SCREEN_ENCODER_LIMITS]. */
    suspend fun screenEncoders(maxSize: Int): List<ToolboxScreenEncoder> = throw ToolboxException("screen_encoders_unavailable")
    suspend fun screenshot(output: OutputStream)
    suspend fun files(path: String): List<ToolboxFile>
    suspend fun stat(path: String): ToolboxFile
    suspend fun file(request: ToolboxFileRequest)
    /** Caller owns output. Cancellation closes the remote transfer; no whole-file buffering. */
    suspend fun pull(path: String, output: OutputStream, offset: Long = 0, progress: (ToolboxProgress) -> Unit = {})
    suspend fun push(path: String, input: InputStream, size: Long, options: ToolboxWriteOptions = ToolboxWriteOptions(), progress: (ToolboxProgress) -> Unit = {})
    /** Reads and hashes one regular remote file; no file contents are retained by the backend. */
    suspend fun snapshotFile(path: String): ToolboxFileSnapshot = throw ToolboxException("file_resume_unavailable")
    /**
     * Appends only the suffix to caller-owned [output]. For nonzero [offset], [prefixSha256] must
     * hash the existing local prefix. The caller keeps its staging file on interruption and checks
     * its final full-file hash before publishing it to a document provider.
     */
    suspend fun pullResumable(snapshot: ToolboxFileSnapshot, output: OutputStream, offset: Long = 0,
        prefixSha256: String? = null, progress: (ToolboxProgress) -> Unit = {}): ToolboxTransferReceipt = throw ToolboxException("file_resume_unavailable")
    /**
     * [open] opens the immutable local source at the accepted remote offset. The backend closes
     * that stream. Interrupted uploads retain their private staging data until resumed or discarded.
     */
    suspend fun pushResumable(request: ToolboxUploadRequest, open: (Long) -> InputStream,
        progress: (ToolboxProgress) -> Unit = {}): ToolboxTransferReceipt = throw ToolboxException("file_resume_unavailable")
    /** Deletes only this request's matching staging data, never the committed destination. */
    suspend fun discardUpload(request: ToolboxUploadRequest): Unit = throw ToolboxException("file_resume_unavailable")
    /**
     * Replaces the Local Bridge this connection runs through with [apk], even one whose agent tools refuse for its version.
     * The watch stops the bridge to replace it, so this usually ends in `install_result_unknown`: what counts is the version after reconnecting.
     */
    suspend fun updateHost(apk: ToolboxApk, progress: (ToolboxProgress) -> Unit = {}): ToolboxInstallResult = throw ToolboxException("toolbox_unavailable")
}
interface AdbToolboxClient : WatchToolboxBackend {
    suspend fun pair(endpoint: AdbEndpoint, code: String)
    suspend fun connect(endpoint: AdbEndpoint)
    suspend fun discover(): List<AdbDiscoveredEndpoint>
    /**
     * Tries the endpoint connected to last. This app's key is checked there first, in at most about
     * three seconds and without changing [state], and only an accepted key goes on to [connect].
     * False when there was nothing to try, the person disconnected last time, the check failed or a
     * connection was started meanwhile; a failure after the check throws as [connect] does.
     */
    suspend fun reconnectLast(): Boolean = false
    /**
     * [disconnect] because the person asked to. Nothing connects by itself afterwards, neither
     * [reconnectLast] nor [AdbDiscoveredEndpoint.autoConnect], until they [connect] to an endpoint.
     */
    suspend fun disconnectByChoice() = disconnect()
}
interface WatchToolboxClient : AutoCloseable {
    val adb: AdbToolboxClient
    val localBridge: WatchToolboxBackend
    override fun close() { adb.close(); localBridge.close() }
    companion object {
        val UNAVAILABLE: WatchToolboxClient = object : WatchToolboxClient {
            override val adb: AdbToolboxClient = object : UnavailableToolbox(ToolboxBackendKind.ADB), AdbToolboxClient {
                override suspend fun pair(endpoint: AdbEndpoint, code: String): Nothing = unavailable()
                override suspend fun connect(endpoint: AdbEndpoint): Nothing = unavailable()
                override suspend fun discover(): List<AdbDiscoveredEndpoint> = emptyList()
            }
            override val localBridge: WatchToolboxBackend = UnavailableToolbox(ToolboxBackendKind.ROOT_BRIDGE)
        }
    }
}
class ToolboxException(val code: String, cause: Throwable? = null) : java.io.IOException(code, cause)

/** Non-Android/demo service graphs advertise absence rather than pretending an operation succeeded. */
private open class UnavailableToolbox(kind: ToolboxBackendKind) : WatchToolboxBackend {
    override val state = MutableStateFlow(ToolboxBackendState(kind))
    protected fun unavailable(): Nothing = throw ToolboxException("toolbox_unavailable")
    override suspend fun connect(): Nothing = unavailable()
    override suspend fun disconnect() = Unit
    override suspend fun install(apks: List<ToolboxApk>, options: ToolboxInstallOptions, progress: (ToolboxProgress) -> Unit): Nothing = unavailable()
    override suspend fun readBundle(file: ToolboxApk): Nothing = unavailable()
    override suspend fun installBundle(bundle: ToolboxBundle, options: ToolboxInstallOptions, progress: (ToolboxProgress) -> Unit): Nothing = unavailable()
    override suspend fun apps(userId: Int): Nothing = unavailable()
    override suspend fun app(request: ToolboxAppRequest): Nothing = unavailable()
    override suspend fun permission(packageName: String, permission: String, grant: Boolean, userId: Int): Nothing = unavailable()
    override suspend fun appOp(packageName: String, operation: String, mode: String, userId: Int): Nothing = unavailable()
    override suspend fun backupAppData(packageName: String, output: OutputStream, userId: Int, progress: (ToolboxProgress) -> Unit): Nothing = unavailable()
    override suspend fun execute(command: String, options: ToolboxCommandOptions): Nothing = unavailable()
    override suspend fun openCommand(command: String, options: ToolboxCommandOptions): Nothing = unavailable()
    override suspend fun openTerminal(options: ToolboxTerminalOptions): Nothing = unavailable()
    override suspend fun clipboard(): Nothing = unavailable()
    override suspend fun setClipboard(text: String): Nothing = unavailable()
    override suspend fun openScreen(options: ToolboxScreenOptions): Nothing = unavailable()
    override suspend fun screenshot(output: OutputStream): Nothing = unavailable()
    override suspend fun files(path: String): Nothing = unavailable()
    override suspend fun stat(path: String): Nothing = unavailable()
    override suspend fun file(request: ToolboxFileRequest): Nothing = unavailable()
    override suspend fun pull(path: String, output: OutputStream, offset: Long, progress: (ToolboxProgress) -> Unit): Nothing = unavailable()
    override suspend fun push(path: String, input: InputStream, size: Long, options: ToolboxWriteOptions, progress: (ToolboxProgress) -> Unit): Nothing = unavailable()
    override fun close() = Unit
}
