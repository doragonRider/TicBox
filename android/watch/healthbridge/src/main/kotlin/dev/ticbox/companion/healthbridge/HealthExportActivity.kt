package dev.ticbox.companion.healthbridge

import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ticbox.companion.healthbridge.ui.BridgeTheme
import dev.ticbox.companion.healthbridge.ui.ExportForm
import dev.ticbox.companion.healthbridge.ui.HealthExportScreen
import java.time.*
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/** Explicit manual export only. Intent extras cannot select a query or start an export. */
class HealthExportActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val cancellations = Executors.newSingleThreadScheduledExecutor()
    private val exportLimits = HealthExportLimits()
    private lateinit var form: ExportForm
    private var running by mutableStateOf(false)
    private var cancelling by mutableStateOf(false)
    private var status by mutableStateOf<String?>(null)
    private var signal: CancellationSignal? = null
    private var stop: AtomicReference<String?>? = null
    private val pageClosed = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        form = ExportForm(savedInstanceState)
        setContent {
            BridgeTheme { HealthExportScreen(form, running, cancelling, status, ::begin, ::requestCancel) }
        }
    }

    private fun begin() {
        val selection = form.selection()
        if (selection == null) { status = getString(R.string.export_invalid_selection); return }
        markRunning(true)
        val cancellation = CancellationSignal(); val reason = AtomicReference<String?>()
        signal = cancellation; stop = reason
        val deadline = cancellations.schedule({
            reason.compareAndSet(null, "deadline"); cancellation.cancel()
        }, exportLimits.durationMillis, TimeUnit.MILLISECONDS)
        status = getString(R.string.export_running)
        val app = applicationContext
        worker.execute {
            if (!fileGate.tryLock()) {
                deadline.cancel(false)
                runOnUiThread { if (!isDestroyed) {
                    signal = null; stop = null; markRunning(false); status = getString(R.string.export_busy)
                } }
                return@execute
            }
            try {
                val download = HealthExportDownloads(app.contentResolver).write { output ->
                    val written = WatchHealthReader(app).use { reader -> HealthJsonExporter(reader::page,
                        { healthProviderMetadata(app, healthQueryUri(it)) }, exportLimits).write(output, selection, cancellation,
                        cancelReason = { reason.get() }, progress = { table, examined, exported ->
                            runOnUiThread { if (!isDestroyed) status = getString(R.string.export_progress, table.wireName, examined, exported) }
                        }) }
                    // Nobody is left to be told where an unfinished file went, so it isn't published.
                    if (pageClosed.get() && written.status != "complete") throw CancellationException("Export page closed")
                    written
                }
                val summary = download.result
                runOnUiThread {
                    if (!isDestroyed) status = getString(R.string.export_done, summary.rows, summary.status, summary.runId, download.relativePath)
                }
            } catch (problem: Exception) {
                runOnUiThread { if (!isDestroyed) status = getString(R.string.export_failed, problem.javaClass.simpleName) }
            } finally {
                fileGate.unlock()
                deadline.cancel(false)
                runOnUiThread { if (!isDestroyed) { signal = null; stop = null; markRunning(false) } }
            }
        }
    }

    private fun requestCancel() {
        stop?.compareAndSet(null, "cancelled")
        // Cancellation may call a remote Binder; keep it off the UI thread.
        signal?.let { cancellation -> cancellations.execute { cancellation.cancel() } }
        cancelling = true
    }

    private fun markRunning(running: Boolean) {
        this.running = running
        cancelling = false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        form.save(outState)
    }

    override fun onDestroy() {
        pageClosed.set(true)
        requestCancel(); cancellations.shutdown(); worker.shutdown(); super.onDestroy()
    }

    companion object {
        // An older Activity can still be unwinding a cancelled Provider call after recreation.
        private val fileGate = ReentrantLock()
    }
}

internal fun parseExportTime(text: String, endOfDay: Boolean, zone: ZoneId = ZoneId.systemDefault()): Long? {
    val value = text.trim()
    if (value.isEmpty()) return null
    value.toLongOrNull()?.let { return it }
    try { return Instant.parse(value).toEpochMilli() } catch (_: java.time.format.DateTimeParseException) { }
    try { return OffsetDateTime.parse(value.replace(' ', 'T')).toInstant().toEpochMilli() }
    catch (_: java.time.format.DateTimeParseException) { }
    try { return LocalDateTime.parse(value.replace(' ', 'T')).atZone(zone).toInstant().toEpochMilli() }
    catch (_: java.time.format.DateTimeParseException) { }
    val date = LocalDate.parse(value)
    return if (endOfDay) date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    else date.atStartOfDay(zone).toInstant().toEpochMilli()
}
