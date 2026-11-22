package dev.ticbox.companion.healthbridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dev.ticbox.companion.protocol.*
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import kotlinx.serialization.json.*

/** The existing signed application route wakes an independent, token-authenticated tool listener. */
class ToolboxWakeService : WearableListenerService() {
    private val serving = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val clients = ConcurrentHashMap.newKeySet<BluetoothSocket>()
    private val processes = ConcurrentHashMap.newKeySet<Process>()
    private val timer = Executors.newScheduledThreadPool(1)
    private val threads = Executors.newFixedThreadPool(4)
    private val slots = Semaphore(4)
    @Volatile private var server: BluetoothServerSocket? = null

    @SuppressLint("MissingPermission")
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != ToolboxProtocol.WAKE_PATH || closed.get()) return
        val token = runCatching { ToolboxProtocol.json.parseToJsonElement(event.data.toString(Charsets.UTF_8)).jsonObject.text("token") }.getOrNull() ?: return
        if (!token.matches(Regex("[0-9a-f]{64}")) || !serving.compareAndSet(false, true)) return
        try {
            if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
            if (!adapter.isEnabled) return
            val jar = File(cacheDir, "toolbox/scrcpy-server-v4.1.jar").apply { parentFile!!.mkdirs() }
            assets.open("toolbox/scrcpy-server-v4.1.jar").use { input -> jar.outputStream().use { input.copyTo(it) } }
            val listener = adapter.listenUsingRfcommWithServiceRecord("TicBox Toolbox", UUID.fromString(ToolboxProtocol.UUID)); server = listener
            val lastActivity = AtomicLong(android.os.SystemClock.elapsedRealtime())
            while (!closed.get()) {
                if (clients.isEmpty() && android.os.SystemClock.elapsedRealtime() - lastActivity.get() > 25_000) break
                val socket = try { listener.accept(1000) } catch (_: IOException) {
                    if (!adapter.isEnabled || closed.get()) break
                    Thread.sleep(50); continue
                }
                if (socket.remoteDevice.bondState != BluetoothDevice.BOND_BONDED || !slots.tryAcquire()) { socket.close(); continue }
                clients += socket
                threads.execute {
                    val deadline = timer.schedule({ runCatching(socket::close) }, 10, TimeUnit.SECONDS)
                    var authenticated = false
                    try {
                        socket.use {
                            val packet = ToolboxProtocol.required(socket.inputStream)
                            val hello = packet.objectValue()
                            val supplied = (hello["args"] as? JsonObject)?.text("token") ?: return@use
                            if (packet.type != ToolboxProtocol.REQUEST || hello.text("op") != "hello" || !MessageDigest.isEqual(token.toByteArray(Charsets.UTF_8), supplied.toByteArray(Charsets.UTF_8))) return@use
                            authenticated = true
                            deadline.cancel(false)
                            ToolboxProtocol.write(socket.outputStream, ToolboxProtocol.RESULT, buildJsonObject { put("authenticated", true) })
                            serve(socket, jar)
                        }
                    } catch (_: Exception) { /* No command, path, clipboard or private file content is logged. */ }
                    finally { deadline.cancel(false); clients.remove(socket); slots.release(); if (authenticated) lastActivity.set(android.os.SystemClock.elapsedRealtime()) }
                }
            }
        } finally { runCatching { server?.close() }; server = null; serving.set(false) }
    }
    private fun serve(socket: BluetoothSocket, jar: File) {
        val agent = "CLASSPATH=${shellQuote(applicationInfo.sourceDir)} exec app_process / ${ToolboxProtocol.AGENT_CLASS}"
        val command = "$agent ${shellQuote(jar.path)} --bridge-host"
        val process = ProcessBuilder("su", "-c", command).start(); processes += process
        val disconnected = AtomicBoolean()
        fun disconnect() {
            if (disconnected.compareAndSet(false, true)) {
                runCatching(socket::close)
                Thread({ closeToolboxInput(process) }, "toolbox-root-eof").apply { isDaemon = true; start() }
            }
        }
        val sending = Thread({
            try { relayToolboxStream(socket.inputStream, process.outputStream) } catch (_: IOException) { }
            finally { disconnect() }
        }, "toolbox-root-input").apply { isDaemon = true; start() }
        Thread({ runCatching { process.errorStream.use { input -> val buffer = ByteArray(8192); while (input.read(buffer) >= 0) { /* discard diagnostics */ } } } }, "toolbox-root-status").apply { isDaemon = true; start() }
        try {
            val ready = ToolboxProtocol.required(process.inputStream)
            try { ToolboxProtocol.write(socket.outputStream, ready.type, ready.bytes) }
            catch (_: IOException) { disconnect() }
            relayToolboxOutput(process.inputStream, socket.outputStream, ::disconnect)
        }
        finally {
            disconnect()
            try { process.waitFor() }
            finally {
                runCatching { process.inputStream.close() }; runCatching { process.errorStream.close() }
                processes.remove(process); sending.interrupt()
            }
        }
    }
    override fun onDestroy() {
        closed.set(true); runCatching { server?.close() }; clients.forEach { runCatching(it::close) }
        processes.forEach { process -> Thread({ closeToolboxInput(process) }, "toolbox-root-eof").apply { isDaemon = true; start() } }
        threads.shutdown(); timer.shutdownNow()
        super.onDestroy()
    }
}
