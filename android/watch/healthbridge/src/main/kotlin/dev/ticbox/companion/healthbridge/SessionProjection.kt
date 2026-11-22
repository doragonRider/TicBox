package dev.ticbox.companion.healthbridge

import android.content.ContentResolver
import android.os.CancellationSignal
import dev.ticbox.companion.protocol.*
import java.util.Locale
import kotlinx.serialization.json.*

/** The OEM URI returns a point-shaped UNION cursor. Discover session columns via that same URI. */
internal class SessionProjection(private val resolver: ContentResolver, cancellation: CancellationSignal) {
    private val carrier = (resolver.query(HealthProviderReader.URI, null, "0", null, null, cancellation)
        ?: throw HealthSchemaException("Missing session carrier")).use { it.columnNames.toList() }
    private val names: List<String>?
    val select: String

    init {
        if (!listOf("_id", "values").all { required -> carrier.any { it.equals(required, true) } })
            throw HealthSchemaException("Missing session carrier column")
        names = try {
            val select = projection("cid", "name", emptyMap())
            resolver.query(HealthProviderReader.URI, null, "0 UNION ALL SELECT $select FROM pragma_table_info('data_session')",
                null, null, cancellation)?.use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("values"))) }
            }?.takeIf { it.isNotEmpty() }
        } catch (cancelled: android.os.OperationCanceledException) { throw cancelled }
        catch (problem: Exception) {
            android.util.Log.w("TicBoxHealthBridge", "Session column discovery unavailable; exception=${problem.javaClass.simpleName}")
            null
        }
        val fields = KNOWN.filter { name -> names == null || name in names }
        select = "0 UNION ALL SELECT ${projection("rowid", if ("id" in fields) quote("id") else "NULL",
            fields.associateWith(::quote))} FROM data_session"
    }

    private fun projection(id: String, value: String, fields: Map<String, String>) = carrier.joinToString(",") {
        when (val name = it.lowercase(Locale.ROOT)) { "_id" -> id; "values" -> value; else -> fields[name] ?: "NULL" }
    }

    /** Extra fields preserve original names/types without pretending a point placeholder is a session field. */
    fun expand(rows: List<JsonObject>, request: JsonObject, cancellation: CancellationSignal): List<JsonObject> {
        if (rows.isEmpty()) return rows
        val schema = names ?: return rows.map { JsonObject(it + mapOf(
            "schemaCoverage" to JsonPrimitive("known_fields_only"), "partial" to JsonPrimitive(true))) }
        val result = rows.associateByTo(linkedMapOf()) { it.number("id")!! }.mapValuesTo(linkedMapOf()) { (_, raw) ->
            raw.toMutableMap().apply {
                put("columns", JsonObject((raw["columns"] as JsonObject).filterKeys { it in schema }))
                put("storageTypes", JsonObject((raw["storageTypes"] as JsonObject).filterKeys { it in schema }))
                // Columns absent from the source schema are not unreadable cells.
                val unavailable = (raw["unavailableColumns"] as? JsonArray).orEmpty().filter { (it as? JsonPrimitive)?.content in schema }
                remove("unavailableColumns"); remove("partial")
                if (unavailable.isNotEmpty()) { put("unavailableColumns", JsonArray(unavailable)); put("partial", JsonPrimitive(true)) }
            }
        }
        val (bounds, args) = sourceSelection(LocalHealthTable.SESSION, JsonObject(request + ("upperId" to JsonPrimitive(result.keys.max()))))
        for (name in schema.filter { it !in KNOWN }) {
            val seen = mutableSetOf<Long>()
            try {
                val sql = "0 UNION ALL SELECT ${projection("rowid", quote(name), emptyMap())} FROM data_session WHERE $bounds"
                (resolver.query(HealthProviderReader.URI, null, sql, args, "_id ASC LIMIT ${rows.size}", cancellation)
                    ?: throw HealthSchemaException("Session extra column unavailable")).use { cursor ->
                    while (cursor.moveToNext()) {
                        cancellation.throwIfCanceled()
                        val id = cursor.healthRowId(); val raw = result[id] ?: continue
                        val (value, type) = cursor.healthCell(cursor.getColumnIndexOrThrow("values"))
                        raw["columns"] = JsonObject((raw["columns"] as JsonObject) + (name to value))
                        raw["storageTypes"] = JsonObject((raw["storageTypes"] as JsonObject) + (name to type)); seen += id
                    }
                }
            } catch (cancelled: android.os.OperationCanceledException) { throw cancelled }
            catch (problem: Exception) {
                android.util.Log.w("TicBoxHealthBridge", "Session extra column unavailable; exception=${problem.javaClass.simpleName}")
            }
            (result.keys - seen).forEach { id ->
                val raw = result.getValue(id)
                raw["partial"] = JsonPrimitive(true)
                raw["unavailableColumns"] = JsonArray((raw["unavailableColumns"] as? JsonArray).orEmpty() + JsonPrimitive(name))
            }
        }
        return result.values.map(::JsonObject)
    }
    companion object {
        private val KNOWN = setOf("id", "device_id", "wwid", "type", "time_to", "time_from", "synced", "deleted")
        private fun quote(name: String) = "\"" + name.replace("\"", "\"\"") + "\""
    }
}
