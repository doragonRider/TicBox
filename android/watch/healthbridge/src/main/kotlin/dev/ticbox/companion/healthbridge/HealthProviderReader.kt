package dev.ticbox.companion.healthbridge

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import androidx.core.net.toUri
import dev.ticbox.companion.protocol.*
import kotlinx.serialization.json.*

/** Only query(), fixed local authorities/tables; never accepts caller-supplied SQL or URIs. */
class HealthProviderReader(private val resolver: ContentResolver, private val providerVersion: String? = null,
    private val sportProviderVersion: String? = null) {
    fun page(request: JsonObject, cancellation: CancellationSignal = CancellationSignal()): JsonObject { return try {
        val table = LocalHealthTable.entries.firstOrNull { it.wireName == request.text("table") }
            ?: return error("unsupported_table")
        if (table.privateFitness) return error("unsupported_table")
        if (table == LocalHealthTable.SPORT) return SportProviderReader(resolver, sportProviderVersion).page(request, cancellation)
        val session = if (table == LocalHealthTable.SESSION) SessionProjection(resolver, cancellation) else null
        val sessionSelect = session?.select
        val after = request.number("afterId")
        val upper = request.number("upperId") ?: (resolver.query(URI, null, sessionSelect, null,
            "_id DESC LIMIT 1", cancellation) ?: return error("provider_missing")).use { cursor -> if (cursor.moveToFirst()) cursor.healthRowId() else null }
        if (upper == null) response(emptyList(), after, null, complete = true)
        else {
            val limit = (request.number("limit") ?: LocalHealthProtocol.PAGE_SIZE.toLong()).coerceIn(1, LocalHealthProtocol.MAX_PAGE_ROWS.toLong()).toInt()
            val names = if (request.number("since") != null || request.number("before") != null) (resolver.query(URI, null, "0", null, null, cancellation)
                ?: return error("provider_missing")).use { it.columnNames.toList() } else null
            val (bounds, args) = sourceSelection(table, JsonObject(request.toMutableMap().apply { put("upperId", JsonPrimitive(upper)) }), names)
            val where = sessionSelect?.let { "$it WHERE $bounds" } ?: bounds
            val cursor = resolver.query(URI, null, where, args, "_id ASC LIMIT ${limit + 1}", cancellation)
                ?: return error("provider_missing")
            cursor.use {
                val rows = mutableListOf<JsonObject>(); var lastId = after; var complete = true
                var bytes = 0
                while (cursor.moveToNext()) {
                    cancellation.throwIfCanceled()
                    if (rows.size == limit) { complete = false; break }
                    val id = cursor.healthRowId()
                    val row = cursor.healthRawRow(id, table)
                    val size = row.toString().toByteArray(Charsets.UTF_8).size
                    // Leave room for framing metadata. Defer a large row to the next page, never silently skip it.
                    if (bytes + size > LocalHealthProtocol.MAX_FRAME_BYTES - 8192) {
                        if (rows.isEmpty()) return error("row_exceeds_budget")
                        complete = false; break
                    }
                    rows += row; bytes += size; lastId = id
                }
                response(session?.expand(rows, request, cancellation) ?: rows, lastId, upper, complete)
            }
        }
    } catch (_: SecurityException) { error("provider_denied") }
    catch (problem: HealthSchemaException) {
        android.util.Log.w("TicBoxHealthBridge", "Provider schema unavailable; reason=${problem.message}")
        error("unsupported_schema")
    }
    catch (_: android.os.OperationCanceledException) { error("query_cancelled") }
    catch (problem: Exception) {
        android.util.Log.w("TicBoxHealthBridge", "Provider query failed; table=${request.text("table")}; exception=${problem.javaClass.simpleName}")
        error("query_failed")
    } }

    private fun response(rows: List<JsonObject>, next: Long?, upper: Long?, complete: Boolean) =
        localHealthPage(rows, next, upper, complete, providerVersion)
    private fun error(code: String) = queryError(code)
    companion object {
        val URI: Uri = LocalHealthProtocol.SOURCE_NAME.toUri()

    }
}

internal fun queryError(code: String) = buildJsonObject { put("op", "page"); put("error", code) }

internal fun localHealthPage(rows: List<JsonObject>, next: Long?, upper: Long?, complete: Boolean,
    providerVersion: String?, nextKey: String? = null, upperKey: String? = null) = buildJsonObject {
    put("op", "page"); put("version", 1); put("rows", JsonArray(rows))
    next?.let { put("nextId", it.toString()) }; upper?.let { put("upperId", it.toString()) }
    nextKey?.let { put("nextKey", it) }; upperKey?.let { put("upperKey", it) }
    put("complete", complete); put("partial", rows.any { it.flag("partial") == true })
    providerVersion?.let { put("providerVersion", it) }
}
