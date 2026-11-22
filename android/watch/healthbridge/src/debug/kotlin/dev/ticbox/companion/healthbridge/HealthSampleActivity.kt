package dev.ticbox.companion.healthbridge

import android.content.ContentResolver
import android.content.Intent
import android.os.*
import android.util.AtomicFile
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ticbox.companion.healthbridge.ui.BridgeTheme
import dev.ticbox.companion.healthbridge.ui.HealthSampleScreen
import dev.ticbox.companion.protocol.LocalHealthProtocol
import dev.ticbox.companion.protocol.LocalHealthTable
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*

/** Debug-only, user-triggered and bounded. Background synchronization never calls this sampler. */
class HealthSampleActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val deadlines = Executors.newSingleThreadScheduledExecutor()
    private var cancellation: CancellationSignal? = null
    private var offlineConfirmed by mutableStateOf(false)
    private var running by mutableStateOf(false)
    private var status by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BridgeTheme {
                HealthSampleScreen(
                    offlineConfirmed = offlineConfirmed,
                    running = running,
                    status = status,
                    onOfflineChange = { offlineConfirmed = it },
                    onSessionProbe = { startRead("session-probe.json", HealthSessionProbe::read) },
                    onSportProbe = { startRead("sport-probe.json", HealthSportProbe::read) },
                    onRawSample = { startRead("health-sample.json", HealthSamples::read) },
                    onExport = { startActivity(Intent(this, HealthExportActivity::class.java)) },
                )
            }
        }
    }

    private fun startRead(filename: String, sample: (ContentResolver, CancellationSignal, Boolean) -> JsonObject) {
        running = true
        status = getString(R.string.sample_running)
        val offlineAcknowledged = offlineConfirmed
        val signal = CancellationSignal(); cancellation = signal
        val expired = AtomicBoolean()
        val deadline = deadlines.schedule({ expired.set(true); signal.cancel() }, 20, TimeUnit.SECONDS)
        worker.execute {
            try {
                val data = sample(contentResolver, signal, offlineAcknowledged)
                val report = buildJsonObject {
                    data.forEach { (key, value) -> put(key, value) }
                    put("deadlineElapsed", expired.get())
                    val uri = (data["uri"] as? JsonPrimitive)?.content?.let(android.net.Uri::parse) ?: HealthProviderReader.URI
                    healthProviderMetadata(this@HealthSampleActivity, uri).forEach { (key, value) -> put(key, value) }
                }
                val file = AtomicFile(File(filesDir, filename))
                val out = file.startWrite()
                try { out.write(report.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(out) }
                catch (problem: Throwable) { file.failWrite(out); throw problem }
                runOnUiThread {
                    val count = report["returnedRows"]?.jsonPrimitive?.intOrNull
                        ?: (report["rows"] as? JsonArray)?.size ?: 0
                    status = getString(R.string.sample_done, count, (report["status"] as JsonPrimitive).content, filename)
                }
            } catch (problem: Exception) {
                runOnUiThread { status = getString(R.string.sample_failed, problem.javaClass.simpleName) }
            } finally {
                deadline.cancel(false)
                runOnUiThread { running = false }
            }
        }
    }

    override fun onDestroy() {
        // Avoid a synchronous remote-provider cancellation Binder call on the UI thread.
        cancellation?.let { signal -> deadlines.execute { signal.cancel() } }
        deadlines.shutdown(); worker.shutdown(); super.onDestroy()
    }
}

/** Exercise the production SESSION reader, retaining only metadata in the exported report. */
internal object HealthSessionProbe {
    fun read(resolver: ContentResolver, cancellation: CancellationSignal, offlineAcknowledged: Boolean): JsonObject =
        HealthTableProbe.read(LocalHealthTable.SESSION, resolver, cancellation, offlineAcknowledged)
}
internal object HealthSportProbe {
    fun read(resolver: ContentResolver, cancellation: CancellationSignal, offlineAcknowledged: Boolean): JsonObject =
        HealthTableProbe.read(LocalHealthTable.SPORT, resolver, cancellation, offlineAcknowledged)
}
private object HealthTableProbe {
    fun read(table: LocalHealthTable, resolver: ContentResolver, cancellation: CancellationSignal, offlineAcknowledged: Boolean): JsonObject {
        val request = JsonObject(LocalHealthProtocol.request(table, null, null)
            .toMutableMap().apply { put("limit", JsonPrimitive(1)) })
        val page = HealthProviderReader(resolver).page(request, cancellation)
        val rows = page["rows"] as? JsonArray ?: JsonArray(emptyList())
        val first = rows.firstOrNull() as? JsonObject
        return buildJsonObject {
            put("schema", if (table == LocalHealthTable.SPORT) "watch-health-sport-probe-v1" else "watch-health-session-probe-v1")
            put("runId", UUID.randomUUID().toString())
            put("uri", if (table == LocalHealthTable.SPORT) table.sourceName else LocalHealthProtocol.SOURCE_NAME)
            put("table", table.wireName)
            put("pageSize", 1); put("tableTotalRows", "not_queried"); put("productionReaderUsed", true)
            put("offlineUserAcknowledged", offlineAcknowledged); put("offlineVerifiedBySampler", false)
            put("rowValuesMayBeRead", true); put("healthValuesIncluded", false); put("identifiersIncluded", false)
            put("providerMutationMethodsCalled", false); put("providerDatabaseWritesExcluded", false)
            put("providerStartupSideEffectsPossible", true)
            put("status", if (page["error"] == null) "query_returned" else "query_failed")
            page["error"]?.let { put("error", it) }
            put("returnedRows", rows.size)
            page["complete"]?.let { put("pageComplete", it) }; page["partial"]?.let { put("partial", it) }
            put("columnCoverage", if (first == null) "no_row_observed" else "first_row_only")
            put("columns", JsonArray((first?.get("columns") as? JsonObject)?.keys.orEmpty().map(::JsonPrimitive)))
            put("storageTypes", first?.get("storageTypes") as? JsonObject ?: JsonObject(emptyMap()))
            first?.get("unavailableColumns")?.let { put("unavailableColumns", it) }
        }
    }
}

internal object HealthSamples {
    fun read(resolver: ContentResolver, cancellation: CancellationSignal, offlineAcknowledged: Boolean): JsonObject {
        val rows = mutableListOf<JsonObject>(); var error: String? = null; var valueGettersUsed = false
        try {
            resolver.query(HealthProviderReader.URI, null, null, null, "_id DESC LIMIT 5", cancellation)?.use { cursor ->
                while (rows.size < 5 && cursor.moveToNext()) {
                    cancellation.throwIfCanceled()
                    valueGettersUsed = true
                    val raw = cursor.healthRawRow(cursor.healthRowId())
                    val columns = (raw["columns"] as JsonObject).toMutableMap()
                    listOf("wwid", "device_id").forEach { if (columns.containsKey(it)) columns[it] = JsonPrimitive("<redacted>") }
                    val redacted = JsonObject(raw.toMutableMap().apply { put("columns", JsonObject(columns)) })
                    if ((rows + redacted).sumOf { it.toString().toByteArray(Charsets.UTF_8).size } > LocalHealthProtocol.MAX_FRAME_BYTES) {
                        error = "sample_budget_exceeded"; break
                    }
                    rows += redacted
                }
            } ?: run { error = "provider_missing" }
        } catch (problem: Exception) { error = problem.javaClass.simpleName }
        return buildJsonObject {
            put("schema", "watch-health-value-sample-v1"); put("runId", UUID.randomUUID().toString())
            put("uri", LocalHealthProtocol.SOURCE_NAME); put("sqlRowLimit", 5); put("order", "_id DESC")
            put("offlineUserAcknowledged", offlineAcknowledged); put("offlineVerifiedBySampler", false)
            put("healthCellValueGettersUsed", valueGettersUsed); put("providerMutationMethodsCalled", false)
            put("providerDatabaseWritesExcluded", false)
            put("providerStartupSideEffectsPossible", true)
            put("redactedColumns", JsonArray(listOf("wwid", "device_id").map(::JsonPrimitive)))
            put("status", if (error == null) "query_returned" else "partial_or_failed")
            error?.let { put("error", it) }; put("rows", JsonArray(rows))
        }
    }
}
