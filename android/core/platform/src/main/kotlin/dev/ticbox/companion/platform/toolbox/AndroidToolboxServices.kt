package dev.ticbox.companion.platform.toolbox

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.util.AtomicFile
import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.toolbox.*
import dev.ticbox.companion.engine.settings.MonitoringRemote
import dev.ticbox.companion.protocol.ToolboxProtocol
import dev.ticbox.companion.protocol.flag
import java.io.InputStream
import java.io.OutputStream
import java.io.File
import java.security.SecureRandom
import dev.ticbox.companion.engine.module.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal data class ToolboxBridgeLink(val peer: PeerAddress, val sessionId: Long)
private const val ALERT_PATCH_CAPTURE_BYTES = 65_536

internal class AndroidToolboxServices(
    context: Context, scope: CoroutineScope,
    diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    send: suspend (ToolboxBridgeLink, ByteArray) -> Unit,
) : WatchToolboxClient {
    private val bridge = RootBridgeConnector(context.applicationContext, send)
    override val adb: AdbToolboxClient = AndroidAdbToolbox(context, scope, diagnostics)
    override val localBridge = AgentToolboxBackend(ToolboxBackendKind.ROOT_BRIDGE, scope, bridge, diagnostics = diagnostics)
    private val monitoringCommands = Mutex()
    val monitoring: MonitoringRemote = MonitoringRemote { link, request -> monitoringCommands.withLock {
        val selected = ToolboxBridgeLink(link.peer, link.sessionId)
        fun requireLink() { if (bridge.link != selected) throw ToolboxException("companion_link_changed") }
        requireLink()
        localBridge.ensureConnected()
        requireLink()
        val target = localBridge.state.value.target ?: throw ToolboxException("device_identity_required")
        // Includes READY's device identity; an Advanced reconnect cannot move this operation.
        withContext(ToolboxOperationTarget(localBridge, target)) {
            localBridge.monitoringSettings(request).also { requireLink() }
        }
    } }
    val healthModule: HealthModuleRemote = HealthModuleRemote { link, payload, install, progress, submitted ->
        val selected = ToolboxBridgeLink(link.peer, link.sessionId)
        fun requireLink() { if (bridge.link != selected) throw ToolboxException("companion_link_changed") }
        requireLink(); localBridge.ensureConnected(); requireLink()
        val target = localBridge.state.value.target ?: throw ToolboxException("device_identity_required")
        withContext(ToolboxOperationTarget(localBridge, target)) {
            HealthModuleReply(target, localBridge.healthModule(payload.catalog, payload.open.takeIf { install }, progress, submitted)).also { requireLink() }
        }
    }
    val moduleShell: ModuleShell = ModuleShell { link, command, timeoutMillis ->
        val selected = ToolboxBridgeLink(link.peer, link.sessionId)
        fun requireLink() { if (bridge.link != selected) throw ToolboxException("companion_link_changed") }
        requireLink(); localBridge.ensureConnected(); requireLink()
        val target = localBridge.state.value.target ?: throw ToolboxException("device_identity_required")
        if (localBridge.state.value.capabilities?.root != true) throw ToolboxException("module_root_required")
        withContext(ToolboxOperationTarget(localBridge, target)) {
            localBridge.execute(command, ToolboxCommandOptions(timeoutMillis, ALERT_PATCH_CAPTURE_BYTES)).also { requireLink() }
        }
    }
    suspend fun attach(value: ToolboxBridgeLink?) {
        if (bridge.link != value) { localBridge.disconnect(); bridge.link = value }
    }
}

internal class RootBridgeConnector(
    private val context: Context,
    private val send: suspend (ToolboxBridgeLink, ByteArray) -> Unit,
) : ToolboxAgentConnector {
    @Volatile var link: ToolboxBridgeLink? = null
    @Volatile private var token: String? = null
    private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<BluetoothSocket>()
    override val device: String? get() = link?.peer?.value
    override suspend fun prepare() {
        if (link == null) throw ToolboxException("companion_link_required")
        // Retain the installation's private key across reconnects and app updates. Otherwise a
        // still-bound WearableListenerService could be accepting the previous wake's key.
        token = toolboxIo { bridgeKey(context) }
    }
    private suspend fun wake(selected: ToolboxBridgeLink) {
        send(selected, buildJsonObject { put("token", token ?: throw ToolboxException("bridge_not_connected")) }.toString().toByteArray(Charsets.UTF_8))
    }
    @SuppressLint("MissingPermission")
    override suspend fun open(): ToolboxChannel {
        val selected = link ?: throw ToolboxException("companion_link_required")
        val key = token ?: throw ToolboxException("bridge_not_connected")
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) throw ToolboxException("bluetooth_permission_required")
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: throw ToolboxException("bluetooth_unavailable")
        if (!adapter.isEnabled) throw ToolboxException("bluetooth_disabled")
        if (adapter.getRemoteDevice(selected.peer.value).bondState != android.bluetooth.BluetoothDevice.BOND_BONDED) throw ToolboxException("bluetooth_bond_required")
        var awakened = false
        var failure: Exception? = null
        repeat(12) {
            if (link != selected || token != key) throw ToolboxException("connection_replaced")
            val pending = AtomicReference<BluetoothSocket?>()
            try {
                return withTimeout(3000) { toolboxIo(onCancel = { pending.get()?.close() }, onDiscard = ToolboxChannel::close) {
                    val socket = adapter.getRemoteDevice(selected.peer.value).createRfcommSocketToServiceRecord(UUID.fromString(ToolboxProtocol.UUID))
                    pending.set(socket); sockets += socket
                    try {
                        socket.connect()
                        ToolboxProtocol.write(socket.outputStream, ToolboxProtocol.REQUEST, ToolboxProtocol.request("hello", buildJsonObject { put("token", key) }))
                        val accepted = ToolboxProtocol.required(socket.inputStream)
                        if (accepted.type != ToolboxProtocol.RESULT || accepted.objectValue().flag("authenticated") != true) throw ToolboxException("bridge_auth_failed")
                        object : ToolboxChannel {
                            override val input: InputStream get() = socket.inputStream
                            override val output: OutputStream get() = socket.outputStream
                            override fun close() { sockets.remove(socket); runCatching(socket::close) }
                        }
                    } catch (error: Throwable) { sockets.remove(socket); socket.close(); throw error }
                } }
            } catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive(); failure = java.io.IOException("bridge_connect_timeout", cancelled) }
            catch (error: java.io.IOException) { failure = error }
            // Only wake a listener when the socket was unavailable. A wake callback owns the
            // current listener window, so queuing one per file/command would keep it alive idle.
            if (!awakened) { wake(selected); awakened = true }
            delay(200)
        }
        throw ToolboxException("bridge_unavailable", failure)
    }
    override fun close() { sockets.forEach { runCatching(it::close) }; sockets.clear() }
}

@Synchronized internal fun bridgeKey(context: Context): String {
    val file = AtomicFile(File(context.noBackupFilesDir, "companion/toolbox-bridge-key.bin").apply { parentFile!!.mkdirs() })
    val bytes = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) file.openRead().use { it.readBytes() }
        else ByteArray(32).also { SecureRandom().nextBytes(it); val stream = file.startWrite()
            try { stream.write(it); file.finishWrite(stream) } catch (error: Throwable) { file.failWrite(stream); throw error }
        }
    if (bytes.size != 32) throw ToolboxException("bridge_identity_corrupt")
    return bytes.joinToString("") { "%02x".format(it) }
}
