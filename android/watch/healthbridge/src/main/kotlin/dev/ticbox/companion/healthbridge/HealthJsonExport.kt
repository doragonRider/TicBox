package dev.ticbox.companion.healthbridge

import android.os.CancellationSignal
import android.os.SystemClock
import dev.ticbox.companion.protocol.*
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.*

internal data class HealthExportSelection(val tables: Map<LocalHealthTable, Set<Long>>,
    val fromMillis: Long? = null, val toMillis: Long? = null, val maxRowsPerTable: Int = 200,
    val redactIdentities: Boolean = true)

/** Allocation/observation budgets for this manual export, not limits imposed on vendor records. */
internal data class HealthExportLimits(val rowBytes: Long = 8L * 1024 * 1024,
    val scannedRowsPerTable: Int = 50_000, val durationMillis: Long = 60_000)

internal data class HealthExportSummary(val runId: String, val status: String, val rows: Int)

/** Streams original production-reader rows into one JSON document; never invokes Provider writes. */
internal class HealthJsonExporter(
    private val readPage: (JsonObject, CancellationSignal) -> JsonObject,
    private val providerMetadata: (LocalHealthTable) -> JsonObject,
    private val limits: HealthExportLimits = HealthExportLimits(),
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
) {
    fun write(output: OutputStream, selection: HealthExportSelection, cancellation: CancellationSignal,
        cancelReason: () -> String? = { if (cancellation.isCanceled) "cancelled" else null },
        progress: (LocalHealthTable, Int, Int) -> Unit = { _, _, _ -> }): HealthExportSummary {
        val runId = UUID.randomUUID().toString()
        val started = elapsed(); var bytes = 0L; var totalRows = 0; var incomplete = false; var stopped: String? = null
        var failedTables = 0
        val salt = UUID.randomUUID().toString()
        fun stop(): String? = cancelReason() ?: if (elapsed() - started >= limits.durationMillis) "deadline" else null
        fun token(value: String) { output.write(value.toByteArray(Charsets.UTF_8)) }
        fun json(value: JsonElement) = token(value.toString())
        val header = buildJsonObject {
            put("schema", "watch-health-json-export-v1"); put("runId", runId)
            put("watchExportStartedAtMillis", System.currentTimeMillis())
            put("watchTimeZone", java.time.ZoneId.systemDefault().id)
            put("selection", buildJsonObject {
                put("tables", buildJsonObject { selection.tables.forEach { (table, types) ->
                    put(table.wireName, JsonArray(types.sorted().map(::JsonPrimitive)))
                } })
                selection.fromMillis?.let { put("fromMillisInclusive", it) }
                selection.toMillis?.let { put("toMillisInclusive", it) }
                put("maxRowsPerTable", selection.maxRowsPerTable)
                put("order", "production_reader_cursor_order")
                put("timeFilter", "interval_overlap; unresolved metadata retained and counted")
            })
            put("limits", buildJsonObject {
                put("exportedRowBytes", limits.rowBytes); put("scannedRowsPerTable", limits.scannedRowsPerTable)
                put("durationMillis", limits.durationMillis)
            })
            put("redactedColumns", JsonArray(if (selection.redactIdentities) listOf("wwid", "device_id", "ACCOUNT_ID", "DEVICE_ID", "HASH").map(::JsonPrimitive) else emptyList()))
            put("identityReplacement", if (selection.redactIdentities) "per_export_consistent_pseudonyms" else "none")
            put("rowIdentifiersIncluded", true); put("healthValuesIncluded", true)
            put("providerMutationMethodsCalled", false); put("providerDatabaseWritesExcluded", false)
            put("providerStartupSideEffectsPossible", true)
            put("tableTotalRows", "not_queried")
        }
        token(header.toString().dropLast(1)); token(",\"tables\":[")
        selection.tables.entries.forEachIndexed { index, (table, types) ->
            if (index > 0) token(",")
            val meta = buildJsonObject {
                put("table", table.wireName); put("sourceName", table.sourceName)
                if (table.privateFitness) put("access", "root_readonly_sqlite_snapshot")
                else put("queryUri", healthQueryUri(table).toString())
                put("provider", try { providerMetadata(table) }
                    catch (problem: Exception) { buildJsonObject { put("providerMetadataError", problem.javaClass.simpleName) } })
            }
            token(meta.toString().dropLast(1)); token(",\"rows\":[")
            var after: Long? = null; var upper: Long? = null; var afterKey: String? = null; var upperKey: String? = null
            var examined = 0; var returned = 0; var exported = 0; var filtered = 0; var unresolved = 0; var pages = 0
            var complete = false; var partialRows = false; var reason: String? = stopped ?: stop(); var queryFailure = false
            var exceptionType: String? = null
            while (reason == null && !complete) {
                reason = stop()
                if (reason != null) break
                if (examined >= limits.scannedRowsPerTable) { reason = "scan_limit"; break }
                if (bytes >= limits.rowBytes) { reason = "byte_limit"; break }
                val request = LocalHealthProtocol.request(table, after, upper, afterKey, upperKey)
                val page = try { LocalHealthProtocol.page(readPage(request, cancellation)) }
                catch (problem: Exception) {
                    exceptionType = problem.javaClass.simpleName
                    reason = stop() ?: "reader_failed"; queryFailure = stop() == null
                    break
                }
                pages++; returned += page.rows.size; partialRows = partialRows || page.partial
                var consumed = 0
                for (row in page.rows) {
                    reason = stop()
                    if (reason != null) break
                    if (examined >= limits.scannedRowsPerTable) { reason = "scan_limit"; break }
                    if (exported >= selection.maxRowsPerTable) { reason = "row_limit"; break }
                    examined++; consumed++
                    val match = matches(row, table, types, selection)
                    if (match == Match.NO) { filtered++; continue }
                    if (match == Match.UNRESOLVED) unresolved++
                    val raw = if (selection.redactIdentities) redact(row.raw, salt) else row.raw
                    val encoded = raw.toString().toByteArray(Charsets.UTF_8)
                    if (bytes + encoded.size > limits.rowBytes) { reason = "byte_limit"; break }
                    if (exported > 0) token(",")
                    output.write(encoded); bytes += encoded.size; exported++; totalRows++
                }
                progress(table, examined, exported)
                if (reason != null) break
                if (page.error != null) { reason = stop() ?: page.error; queryFailure = stop() == null; break }
                if (page.complete && consumed == page.rows.size) { complete = true; break }
                if (exported >= selection.maxRowsPerTable) { reason = "row_limit"; break }
                val next = page.nextId; val nextKey = page.nextKey
                if (next == after && nextKey == afterKey) { reason = "cursor_not_advanced"; queryFailure = true; break }
                after = next; afterKey = nextKey; upper = page.upperId ?: upper; upperKey = page.upperKey ?: upperKey
            }
            if (reason in setOf("deadline", "cancelled", "byte_limit")) stopped = reason
            val status = when {
                queryFailure -> "failed"
                reason == "cancelled" -> "cancelled"
                !complete || partialRows -> "partial"
                else -> "complete"
            }
            if (status != "complete") incomplete = true
            if (status == "failed") failedTables++
            token("],\"result\":")
            json(buildJsonObject {
                put("status", status); put("complete", complete); put("partialRows", partialRows)
                reason?.let { put("reason", it) }; exceptionType?.let { put("exceptionType", it) }
                put("pagesRead", pages); put("rowsReturnedByReader", returned); put("rowsExamined", examined)
                put("rowsExported", exported); put("rowsFiltered", filtered); put("unresolvedFilterRows", unresolved)
            }); token("}")
        }
        val status = when {
            stopped == "cancelled" -> "cancelled"
            failedTables > 0 && failedTables == selection.tables.size -> "failed"
            incomplete -> "partial"
            else -> "complete"
        }
        token("],\"summary\":")
        json(buildJsonObject {
            put("status", status); put("rowsExported", totalRows); put("exportedRowBytes", bytes)
            put("elapsedMillis", elapsed() - started); stopped?.let { put("stopReason", it) }
        }); token("}"); output.flush()
        return HealthExportSummary(runId, status, totalRows)
    }

    private enum class Match { YES, NO, UNRESOLVED }
    private fun matches(row: LocalHealthRow, table: LocalHealthTable, types: Set<Long>, selection: HealthExportSelection): Match {
        var unknown = false
        if (types.isNotEmpty()) {
            val type = row.number(if (table.privateFitness) "TYPE" else "type")
            if (type == null) unknown = true else if (type !in types) return Match.NO
        }
        if (selection.fromMillis != null || selection.toMillis != null) {
            val from = row.number(when (table) { LocalHealthTable.FITNESS_RECORD -> "START_TIME"; LocalHealthTable.FITNESS_POINT -> "TIMESTAMP"; else -> "time_from" })
            val to = row.number(when (table) { LocalHealthTable.FITNESS_RECORD -> "END_TIME"; LocalHealthTable.FITNESS_POINT -> "TIMESTAMP"; else -> "time_to" })
            if (from == null || to == null || to < from) unknown = true
            else if (selection.fromMillis?.let { to < it } == true || selection.toMillis?.let { from > it } == true) return Match.NO
        }
        return if (unknown) Match.UNRESOLVED else Match.YES
    }

    private fun redact(raw: JsonObject, salt: String): JsonObject {
        val columns = raw["columns"] as? JsonObject ?: return raw
        val changed = columns.toMutableMap()
        for (name in columns.keys.filter { it.lowercase(java.util.Locale.ROOT) in setOf("wwid", "device_id", "account_id", "hash") }) {
            val value = columns[name]?.takeUnless { it is JsonNull } ?: continue
            if (value is JsonPrimitive && value.isString && value.content.isEmpty()) continue
            val digest = MessageDigest.getInstance("SHA-256").digest("$salt\u0000$name\u0000$value".toByteArray(Charsets.UTF_8))
            changed[name] = JsonPrimitive("redacted:" + digest.take(16).joinToString("") { "%02x".format(it) })
        }
        return JsonObject(raw.toMutableMap().apply { put("columns", JsonObject(changed)) })
    }
}
