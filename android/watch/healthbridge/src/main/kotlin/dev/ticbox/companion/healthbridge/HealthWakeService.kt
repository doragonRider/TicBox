package dev.ticbox.companion.healthbridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dev.ticbox.companion.protocol.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*

/** GMS starts this background listener on a matching direct MessageApi event, even without an Activity. */
class HealthWakeService : WearableListenerService() {
    private val serving = AtomicBoolean()
    @Volatile private var activeWindow: ReadWindow? = null
    private val timer = Executors.newSingleThreadScheduledExecutor()

    @SuppressLint("MissingPermission")
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != LocalHealthProtocol.WAKE_PATH || !BridgeSettings.enabled(this)) return
        val token = runCatching { LocalHealthProtocol.parse(event.data).text("token") }.getOrNull() ?: return
        if (token.length !in 32..256 || !serving.compareAndSet(false, true)) return
        val window = ReadWindow(timer); activeWindow = window
        val cancellation = window.cancellation
        val deadline = timer.schedule(window::close, 5 * 60_000L, TimeUnit.MILLISECONDS)
        window.touch(LocalHealthProtocol.SESSION_MILLIS)
        val reader = IncrementalHealthReader(applicationContext)
        var pages = 0; var rows = 0
        try {
            if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Bluetooth permission unavailable"); return
            }
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
            if (!adapter.isEnabled) return
            val server = adapter.listenUsingRfcommWithServiceRecord("TicBox Local Health", UUID.fromString(LocalHealthProtocol.RFCOMM_UUID))
            window.attach(server)
            Log.i(TAG, "Local Bluetooth read window opened")
            while (!cancellation.isCanceled) {
                val socket = server.accept(); window.attach(socket)
                socket.use {
                    if (socket.remoteDevice.bondState != BluetoothDevice.BOND_BONDED) return@use
                    val hello = LocalHealthProtocol.read(socket.inputStream) ?: return@use
                    val supplied = hello.text("token") ?: return@use
                    if (!MessageDigest.isEqual(token.toByteArray(Charsets.UTF_8), supplied.toByteArray(Charsets.UTF_8))) return@use
                    window.touch(60_000)
                    LocalHealthProtocol.write(socket.outputStream, JsonObject(LocalHealthProtocol.ready(reader.tables) + ("journalVersion" to JsonPrimitive(1))))
                    // One connection serves every table; the window ends when the phone closes it.
                    while (!cancellation.isCanceled) {
                        val request = LocalHealthProtocol.read(socket.inputStream) ?: return
                        if (request.text("op") !in setOf("changes", "ack", "journal")) continue
                        val started = SystemClock.elapsedRealtime()
                        val handled = if (request.text("op") == "journal") RootHealthJournalReader(applicationContext).control(request, cancellation)
                            else reader.handle(request, cancellation)
                        // Lets the phone tell this side's work from the Bluetooth transfer in its sync timing.
                        val page = JsonObject(handled + ("watchMillis" to JsonPrimitive(SystemClock.elapsedRealtime() - started)))
                        if (request.text("op") == "journal" && request.text("action") in setOf("enable", "disable") &&
                            (page.text("error") == null || request.text("action") == "disable")) reader.invalidateJournal(request.text("source")!!)
                        LocalHealthProtocol.write(socket.outputStream, page)
                        window.touch(60_000)
                        if (request.text("op") == "changes") {
                            pages++; rows += (page["rows"] as? JsonArray)?.size ?: 0
                            Log.i(TAG, "Local changes sent; pages=$pages rows=$rows scanned=${page.number("scannedRows") ?: 0} " +
                                "partial=${page.flag("partial") == true} error=${page.text("error") ?: "none"} millis=${page.number("watchMillis")}")
                        }
                    }
                }
            }
        } catch (problem: Exception) {
            // Exception messages from SQL/transport can contain health values or remote input.
            Log.w(TAG, "Local window ended; exception=${problem.javaClass.simpleName} pages=$pages rows=$rows")
        } finally {
            deadline.cancel(false); window.close()
            try { reader.close() } finally { activeWindow = null; serving.set(false) }
        }
    }
    /** Each timer owns only its own handles, so an expired window cannot close its replacement. */
    private class ReadWindow(private val timer: java.util.concurrent.ScheduledExecutorService) {
        val cancellation = CancellationSignal()
        private var listening: BluetoothServerSocket? = null
        private var connected: BluetoothSocket? = null
        private var idle: java.util.concurrent.ScheduledFuture<*>? = null
        private var idleGeneration = 0L
        @Synchronized fun touch(millis: Long) {
            idle?.cancel(false)
            val generation = ++idleGeneration
            if (!cancellation.isCanceled) idle = timer.schedule({ synchronized(this) {
                if (generation == idleGeneration) close()
            } }, millis, TimeUnit.MILLISECONDS)
        }
        @Synchronized fun attach(server: BluetoothServerSocket) {
            if (cancellation.isCanceled) server.close() else listening = server
        }
        @Synchronized fun attach(socket: BluetoothSocket) {
            if (cancellation.isCanceled) socket.close() else connected = socket
        }
        @Synchronized fun close() {
            idleGeneration++
            idle?.cancel(false); idle = null
            runCatching { connected?.close() }; connected = null
            runCatching { listening?.close() }; listening = null
            // A provider's cancellation Binder call may block; release the radio handles first.
            cancellation.cancel()
        }
    }
    override fun onDestroy() { activeWindow?.close(); timer.shutdownNow(); super.onDestroy() }
    companion object { private const val TAG = "TicBoxHealthBridge" }
}
