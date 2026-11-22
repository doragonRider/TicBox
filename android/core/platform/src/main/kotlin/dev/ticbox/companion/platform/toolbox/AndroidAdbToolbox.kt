package dev.ticbox.companion.platform.toolbox

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.AtomicFile
import com.flyfishxu.kadb.DelayedAckMode
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.KadbOptions
import com.flyfishxu.kadb.cert.*
import com.flyfishxu.kadb.shell.AdbShellPacket
import com.flyfishxu.kadb.shell.AdbShellStream
import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.toolbox.*
import dev.ticbox.companion.protocol.ToolboxProtocol
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*

internal class AndroidAdbToolbox private constructor(
    private val context: Context, scope: CoroutineScope, private val adbConnector: AndroidAdbConnector,
    diagnostics: DiagnosticSink,
    private val implementation: AgentToolboxBackend = AgentToolboxBackend(ToolboxBackendKind.ADB, scope, adbConnector, diagnostics = diagnostics),
) : AdbToolboxClient, WatchToolboxBackend by implementation {
    constructor(context: Context, scope: CoroutineScope, diagnostics: DiagnosticSink = DiagnosticSink.NONE) :
        this(context.applicationContext, scope, AndroidAdbConnector(context.applicationContext, diagnostics), diagnostics)
    override suspend fun pair(endpoint: AdbEndpoint, code: String) {
        require(code.matches(Regex("[0-9]{6}")))
        requireLocalNetwork(context)
        AndroidAdbIdentity.configure(context)
        val relay = toolboxIo(onDiscard = AdbPairingRelay::close) { AdbPairingRelay(endpoint) }
        try {
            // Kadb's pairing handshake is blocking and doesn't expose its socket. Own a raw
            // TCP relay so timeout/cancellation really closes that TLS stream as well.
            withTimeout(90_000) { toolboxIo(onCancel = relay::close) {
                runBlocking { Kadb.pair("127.0.0.1", relay.port, code, "TicBox") }
            } }
        } catch (timeout: TimeoutCancellationException) { throw ToolboxException("adb_pairing_timeout", timeout) }
        finally { relay.close() }
    }
    /** The last search's results, to remember a chosen endpoint by its service name. */
    @Volatile private var discovered: List<AdbDiscoveredEndpoint> = emptyList()
    override suspend fun connect(endpoint: AdbEndpoint) = implementation.connectConfigured {
        adbConnector.select(endpoint, discovered.firstOrNull { !it.pairing && it.endpoint == endpoint }?.name)
    }
    override suspend fun connect() = implementation.connect()
    override suspend fun discover(): List<AdbDiscoveredEndpoint> {
        val endpoints = discoverAdb(context)
        toolboxIo { AndroidAdbIdentity.configure(context) }
        val checked = authenticateAdbDiscovery(endpoints, authenticate = ::probe)
        return adbConnector.markLast(checked).also { discovered = it }
    }
    override suspend fun reconnectLast(): Boolean {
        val last = adbConnector.reconnectable ?: return false
        if (!hasLocalNetwork(context)) return false
        toolboxIo { AndroidAdbIdentity.configure(context) }
        if (probe(last) != AdbPairingStatus.PAIRED) return false
        // The person may have picked a device while this was checking.
        val busy = state.value.phase.let { it == ToolboxPhase.CONNECTING || it == ToolboxPhase.CONNECTED }
        if (busy || adbConnector.reconnectable != last) return false
        connect()
        return true
    }
    override suspend fun disconnectByChoice() {
        adbConnector.stayDisconnected()
        disconnect()
    }
    private suspend fun probe(endpoint: AdbEndpoint): AdbPairingStatus {
        val client = Kadb.create(endpoint.host, endpoint.port, connectTimeout = 2_000, socketTimeout = 2_000)
        return probeAdbPairing(close = client::close) {
            // Negotiates CNXN/TLS only. Do not open a shell, deploy files or start an agent.
            client.supportsFeature("shell_v2")
        }
    }
}

/**
 * Waiting for an OKAY after every message caps a stream at one message per round trip, which video
 * outruns. The window is what can pile up here unread, so it also limits how far behind the picture
 * can get at this end: 1 MiB is about 0.2 s at 40 Mbps, and still 160 Mbps at a 50 ms round trip.
 * An adbd without `delayed_ack` doesn't negotiate it and keeps the old behaviour.
 */
private val STREAMING_OPTIONS = KadbOptions(DelayedAckMode.ENABLED, delayedAckWindowBytes = 1024 * 1024)

internal class AndroidAdbConnector(
    private val context: Context,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
) : ToolboxAgentConnector {
    @Volatile private var endpoint: AdbEndpoint? = readEndpoint()
    /** The endpoint's service name, which stays when the watch's address and port change; null if typed in. */
    @Volatile private var endpointName: String? = prefs().getString("name", null)
    /** Cleared when the person disconnects, and set again by their next connection. */
    @Volatile private var reconnects = prefs().getBoolean("reconnect", true)
    @Volatile private var client: Kadb? = null
    @Volatile private var agentPath: String? = null
    @Volatile private var screenPath: String? = null
    private val nextChannel = AtomicLong()
    override val device: String? get() = endpoint?.let { "${it.host}:${it.port}" }
    override val screenUdpHost: String? get() = endpoint?.host
    suspend fun select(value: AdbEndpoint, name: String?) {
        toolboxIo {
            val edit = prefs().edit().putString("host", value.host).putInt("port", value.port).putBoolean("reconnect", true)
            check((if (name != null) edit.putString("name", name) else edit.remove("name")).commit())
        }
        endpoint = value; endpointName = name; reconnects = true
    }
    suspend fun stayDisconnected() {
        toolboxIo { check(prefs().edit().putBoolean("reconnect", false).commit()) }
        reconnects = false
    }
    /** The endpoint connected to last, unless the person disconnected from it. */
    val reconnectable: AdbEndpoint? get() = endpoint?.takeIf { reconnects }
    fun markLast(found: List<AdbDiscoveredEndpoint>) = if (reconnects) markLastAdbDevice(found, endpointName, endpoint?.host) else found
    private fun prefs() = context.getSharedPreferences("toolbox-adb-endpoint", Context.MODE_PRIVATE)
    private fun readEndpoint(): AdbEndpoint? = prefs().let { prefs ->
        prefs.getString("host", null)?.let { host -> runCatching { AdbEndpoint(host, prefs.getInt("port", 0)) }.getOrNull() }
    }
    override suspend fun prepare() {
        requireLocalNetwork(context)
        val target = endpoint ?: throw ToolboxException("adb_endpoint_required")
        AndroidAdbIdentity.configure(context)
        val selected = Kadb.create(target.host, target.port, connectTimeout = 15_000, options = STREAMING_OPTIONS)
        client = selected
        try {
            toolboxIo(onCancel = ::close) {
                val id = selected.shell("id -u"); if (id.exitCode != 0) throw ToolboxException("adb_shell_unavailable")
                val delayedAck = selected.supportsFeature("delayed_ack").toString()
                runCatching { diagnostics.record(ProtocolDiagnostic("toolbox_adb", "connected", DiagnosticSeverity.INFO, details = mapOf("delayedAck" to delayedAck))) }
                agentPath = deploy(selected, "watch/local-bridge.apk", "agent")
                screenPath = deploy(selected, "watch/local-bridge.apk", "scrcpy", "assets/toolbox/scrcpy-server-v4.1.jar")
            }
        } catch (error: Throwable) {
            close()
            if (adbRequiresPairing(error)) throw ToolboxException("adb_pairing_required", error)
            throw error
        }
    }
    private fun deploy(client: Kadb, asset: String, prefix: String, entry: String? = null): String {
        val folder = File(context.cacheDir, "toolbox-deploy").apply { mkdirs() }
        val local = File.createTempFile(prefix, ".bin", folder)
        try {
            val input = context.assets.open(asset).let { if (entry == null) it else bundledApkEntry(it, entry) }
            input.use { source -> local.outputStream().use { source.copyTo(it) } }
            val digest = MessageDigest.getInstance("SHA-256").digest(local.readBytes()).joinToString("") { "%02x".format(it) }
            val path = "/data/local/tmp/ticbox-$prefix-$digest.apk"
            val existing = client.shell("sha256sum ${ToolboxProtocol.quote(path)}")
            if (existing.exitCode != 0 || existing.output.substringBefore(' ') != digest) {
                client.push(local, path, 420)
                val verified = client.shell("sha256sum ${ToolboxProtocol.quote(path)}")
                if (verified.exitCode != 0 || verified.output.substringBefore(' ') != digest) throw ToolboxException("agent_deploy_integrity")
            }
            return path
        } finally { local.delete() }
    }
    override suspend fun open(): ToolboxChannel {
        val selected = client ?: throw ToolboxException("adb_not_connected")
        val apk = agentPath ?: throw ToolboxException("agent_not_deployed")
        val screen = screenPath ?: throw ToolboxException("screen_not_deployed")
        val id = nextChannel.incrementAndGet()
        diagnostic("channel_open_started", selected, id)
        return try { openAdbAgentChannel(resetTransport = selected::resetConnection, onCancelled = {
            diagnostic("channel_open_cancelled", selected, id)
        }) {
            val shell = selected.openShell("CLASSPATH=${ToolboxProtocol.quote(apk)} exec app_process / ${ToolboxProtocol.AGENT_CLASS} ${ToolboxProtocol.quote(screen)}")
            val channel = shellChannel(shell)
            diagnostic("channel_opened", selected, id)
            observeAdbChannel(channel) { event, error -> diagnostic(event, selected, id, error) }
        } } catch (error: Exception) {
            diagnostic("channel_open_failed", selected, id, error)
            throw error
        }
    }
    override fun recoverBeforeRequest(error: IOException): Boolean {
        val selected = client ?: return false
        if (adbRequiresPairing(error) || selected.connectionCheck()) return false
        // Kadb reconnects lazily on its next open. Never reset an already live transport:
        // another module may have recovered it, or may have a screen/transfer still running.
        diagnostic("dead_transport_before_request", selected, error = error)
        return true
    }
    override fun close() {
        val previous = client; client = null; agentPath = null; screenPath = null
        previous?.let { diagnostic("connector_closed", it) }
        runCatching { previous?.close() }
    }
    private fun diagnostic(event: String, selected: Kadb, id: Long = 0, error: Throwable? = null) {
        runCatching { diagnostics.record(ProtocolDiagnostic("toolbox_adb", event,
            severity = if (error != null) DiagnosticSeverity.WARNING else DiagnosticSeverity.INFO,
            details = buildMap {
                put("channel", id.toString()); put("transportAlive", selected.connectionCheck().toString())
                put("tlsImplementation", "platform_ssl_socket")
                error?.let {
                    put("exception", it.javaClass.simpleName)
                    put("causes", generateSequence(it.cause) { cause -> cause.cause }.take(8).joinToString(",") { cause -> cause.javaClass.simpleName })
                    put("failureKind", adbFailureKind(it))
                    putAll(adbTlsFailureDetails(it))
                    put("pairingRequired", adbRequiresPairing(it).toString())
                    if (it is ToolboxException) put("code", it.code)
                }
            })) }
    }
}

/** Adapt shell-v2 packets without interpreting binary stdout as text or merging stderr into it. */
internal fun shellChannel(shell: AdbShellStream): ToolboxChannel = shellPacketChannel(shell::read, shell::write, shell::close, encrypted = shell.encrypted)
internal fun shellPacketChannel(readPacket: () -> AdbShellPacket, writePacket: (ByteArray) -> Unit, closeStream: () -> Unit, encrypted: Boolean = false): ToolboxChannel = object : ToolboxChannel {
    override val secureScreenUdp = encrypted
    override val input: InputStream = object : InputStream() {
        private var pending = byteArrayOf(); private var offset = 0; private var ended = false
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) == -1) -1 else b[0].toInt() and 255 }
        override fun read(target: ByteArray, start: Int, length: Int): Int {
            if (length == 0) return 0
            while (offset == pending.size && !ended) when (val packet = readPacket()) {
                is AdbShellPacket.StdOut -> { pending = packet.payload; offset = 0 }
                is AdbShellPacket.StdError -> Unit
                is AdbShellPacket.Exit -> { ended = true; if (packet.payload.firstOrNull() != 0.toByte()) throw ToolboxException("agent_process_failed") }
            }
            if (offset == pending.size) return -1
            val n = minOf(length, pending.size - offset); pending.copyInto(target, start, offset, offset + n); offset += n; return n
        }
    }
    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = writePacket(byteArrayOf(b.toByte()))
        override fun write(b: ByteArray, off: Int, len: Int) { writePacket(b.copyOfRange(off, off + len)) }
    }
    override fun close() { runCatching(closeStream) }
}

private object AndroidAdbIdentity {
    private var configured: String? = null
    @Synchronized fun configure(context: Context) {
        val file = File(context.noBackupFilesDir, "toolbox-adb/host-key.pem")
        if (configured == file.path) return
        file.parentFile!!.mkdirs()
        val atomic = AtomicFile(file)
        KadbCert.configure(object : KadbPrivateKeyStore {
            override fun readPrivateKeyPem(): ByteArray? = if (!file.exists() && !File(file.path + ".bak").exists()) null else atomic.openRead().use { it.readBytes().also { bytes -> check(bytes.isNotEmpty()) { "adb_identity_corrupt" } } }
            override fun writePrivateKeyPemAtomic(privateKeyPem: ByteArray) {
                val stream = atomic.startWrite()
                try { stream.write(privateKeyPem); atomic.finishWrite(stream) } catch (error: Throwable) { atomic.failWrite(stream); throw error }
                file.setReadable(false, false); file.setWritable(false, false); file.setReadable(true, true); file.setWritable(true, true)
            }
            override fun clear() { atomic.delete() }
        }, KadbCertPolicy(autoHealInvalidPrivateKey = false))
        configured = file.path
    }
}

private suspend fun discoverAdb(context: Context): List<AdbDiscoveredEndpoint> {
    requireLocalNetwork(context)
    val manager = context.getSystemService(NsdManager::class.java) ?: throw ToolboxException("discovery_unavailable")
    val results = ConcurrentHashMap<String, AdbDiscoveredEndpoint>()
    val callbacks = ConcurrentHashMap.newKeySet<NsdManager.ServiceInfoCallback>()
    val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    val direct = Executor { it.run() }
    val lock = Any()
    var active = true
    var discoveryFailure: Int? = null
    try {
        listOf("_adb-tls-connect._tcp.", "_adb-tls-pairing._tcp.").forEach { type ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { synchronized(lock) { discoveryFailure = errorCode } }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onServiceLost(serviceInfo: NsdServiceInfo) { results.remove(serviceInfo.serviceName + type) }
                override fun onServiceFound(info: NsdServiceInfo) {
                    synchronized(lock) {
                    if (!active) return
                    val callback = object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
                        override fun onServiceInfoCallbackUnregistered() = Unit
                        override fun onServiceLost() { results.remove(info.serviceName + type) }
                        override fun onServiceUpdated(value: NsdServiceInfo) {
                            val host = value.hostAddresses.firstOrNull()?.hostAddress ?: return
                            results[info.serviceName + type] = AdbDiscoveredEndpoint(info.serviceName, AdbEndpoint(host, value.port), type.contains("pairing"))
                        }
                    }
                    callbacks += callback
                    runCatching { manager.registerServiceInfoCallback(info, direct, callback) }
                    }
                }
            }
            listeners += listener; manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        }
        delay(5000)
        synchronized(lock) { if (discoveryFailure != null && results.isEmpty()) throw ToolboxException("adb_discovery_failed_$discoveryFailure") }
        return results.values.sortedWith(compareBy({ it.name }, { it.pairing }))
    } finally {
        synchronized(lock) { active = false }
        listeners.forEach { runCatching { manager.stopServiceDiscovery(it) } }
        callbacks.forEach { runCatching { manager.unregisterServiceInfoCallback(it) } }
    }
}

private fun requireLocalNetwork(context: Context) {
    if (!hasLocalNetwork(context)) throw ToolboxException("local_network_permission_required")
}

private fun hasLocalNetwork(context: Context) =
    Build.VERSION.SDK_INT < 37 || context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") == PackageManager.PERMISSION_GRANTED
