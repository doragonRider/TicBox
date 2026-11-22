package dev.ticbox.companion.healthbridge

import android.os.CancellationSignal
import android.util.Log
import dev.ticbox.companion.protocol.*
import kotlinx.serialization.json.*

/** One immutable database view supplies both private tables for the lifetime of a read window. */
internal class FitnessSnapshotReader(private val acquire: (CancellationSignal) -> FitnessSnapshot) : AutoCloseable {
    private var snapshot: FitnessSnapshot? = null
    private var failure: String? = null

    fun page(request: JsonObject, cancellation: CancellationSignal): JsonObject = try {
        failure?.let { return queryError(it) }
        val table = LocalHealthTable.entries.firstOrNull { it.wireName == request.text("table") && it.privateFitness }
            ?: return queryError("unsupported_table")
        val current = snapshot ?: acquire(cancellation).also { snapshot = it }
        fitnessDatabasePage(current.database, current.providerVersion, table, request, cancellation)
    } catch (problem: FitnessSourceException) {
        failure = problem.code
        Log.w("TicBoxHealthBridge", "Fitness source unavailable; reason=${problem.code}")
        queryError(problem.code)
    } catch (_: android.os.OperationCanceledException) { queryError("query_cancelled") }
    catch (problem: Exception) {
        Log.w("TicBoxHealthBridge", "Fitness snapshot query failed; table=${request.text("table")}; exception=${problem.javaClass.simpleName}")
        queryError(if (problem is HealthSchemaException) "unsupported_schema" else "query_failed")
    }

    override fun close() { snapshot?.close(); snapshot = null }
}

/** A bounded Cursor from a manual export snapshot or the production read-only source. */
internal fun fitnessDatabasePage(database: android.database.sqlite.SQLiteDatabase, providerVersion: String?,
    table: LocalHealthTable, request: JsonObject, cancellation: CancellationSignal): JsonObject {
        val sqlTable = if (table == LocalHealthTable.FITNESS_RECORD) "RECORD" else "POINT"
        val after = request.number("afterId")
        // The vendor app creates its tables when first needed; until then there are simply no rows.
        val created = database.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? COLLATE NOCASE", arrayOf(sqlTable),
            cancellation).use { it.moveToFirst() }
        if (!created) return localHealthPage(emptyList(), after, null, true, providerVersion)
        val upper = request.number("upperId") ?: database.rawQuery("SELECT _id FROM $sqlTable ORDER BY _id DESC LIMIT 1", null,
            cancellation).use { if (it.moveToFirst()) it.healthRowId() else null }
        return if (upper == null) localHealthPage(emptyList(), after, null, true, providerVersion)
        else {
            val limit = (request.number("limit") ?: LocalHealthProtocol.PAGE_SIZE.toLong()).coerceIn(1, LocalHealthProtocol.MAX_PAGE_ROWS.toLong()).toInt()
            val (bounds, args) = sourceSelection(table, JsonObject(request.toMutableMap().apply { put("upperId", JsonPrimitive(upper)) }))
            database.rawQuery("SELECT * FROM $sqlTable WHERE $bounds ORDER BY _id ASC LIMIT ${limit + 1}", args,
                cancellation).use { cursor ->
                val rows = mutableListOf<JsonObject>(); var last = after; var complete = true; var bytes = 0
                while (cursor.moveToNext()) {
                    cancellation.throwIfCanceled()
                    if (rows.size == limit) { complete = false; break }
                    val id = cursor.healthRowId()
                    val row = cursor.healthRawRow(id, table)
                    val size = row.toString().toByteArray(Charsets.UTF_8).size
                    if (bytes + size > LocalHealthProtocol.MAX_FRAME_BYTES - 8192) {
                        if (rows.isEmpty()) return queryError("row_exceeds_budget")
                        complete = false; break
                    }
                    rows += row; bytes += size; last = id
                }
                localHealthPage(rows, last, upper, complete, providerVersion)
            }
        }
}

/** Routes fixed table requests; a disabled root switch never invokes su. */
internal class WatchHealthReader(private val context: android.content.Context) : AutoCloseable {
    private fun version(uri: android.net.Uri): String? = runCatching {
        val info = context.packageManager.resolveContentProvider(uri.authority!!, 0) ?: return null
        context.packageManager.getPackageInfo(info.packageName, 0).versionName
    }.getOrNull()
    private val provider = HealthProviderReader(context.contentResolver, version(HealthProviderReader.URI), version(SportProviderReader.URI))
    private val fitness = FitnessSnapshotReader(RootFitnessSnapshot(context)::open)
    val tables: List<LocalHealthTable> get() = enabledHealthTables(context)

    fun page(request: JsonObject, cancellation: CancellationSignal): JsonObject {
        val table = LocalHealthTable.entries.firstOrNull { it.wireName == request.text("table") }
        return if (table?.privateFitness == true) {
            if (BridgeSettings.rootFitnessEnabled(context)) fitness.page(request, cancellation) else queryError("root_not_enabled")
        } else provider.page(request, cancellation)
    }
    override fun close() = fitness.close()
}
