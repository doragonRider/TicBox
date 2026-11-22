package dev.ticbox.companion.healthbridge

import android.database.Cursor
import android.util.Base64
import dev.ticbox.companion.protocol.LocalHealthTable
import java.util.Locale
import kotlinx.serialization.json.*

internal class HealthSchemaException(message: String = "Missing numeric pagination ID") : Exception(message)

internal fun Cursor.healthRowId(): Long {
    val index = getColumnIndex("_id")
    if (index < 0 || isNull(index)) throw HealthSchemaException()
    return getString(index)?.toLongOrNull() ?: throw HealthSchemaException()
}
internal fun Cursor.healthRawRow(id: Long, table: LocalHealthTable = LocalHealthTable.POINT): JsonObject {
    return healthRawRow(JsonPrimitive(id.toString()), table)
}
internal fun Cursor.healthRawRow(key: String, table: LocalHealthTable): JsonObject {
    return healthRawRow(JsonPrimitive(key), table)
}
/** Direct SQLite rows have the real SESSION id column, without the Provider's point-shaped carrier. */
internal fun Cursor.healthDatabaseRow(key: String, table: LocalHealthTable): JsonObject =
    healthRawRow(JsonPrimitive(key), table, directSession = true)
internal fun Cursor.healthCell(index: Int): Pair<JsonElement, JsonPrimitive> {
    val type = getType(index)
    val name = when (type) {
        Cursor.FIELD_TYPE_NULL -> "null"; Cursor.FIELD_TYPE_INTEGER -> "integer"
        Cursor.FIELD_TYPE_FLOAT -> "float"; Cursor.FIELD_TYPE_STRING -> "string"
        Cursor.FIELD_TYPE_BLOB -> "blob-base64"; else -> "unknown:$type"
    }
    val value = when (type) {
        Cursor.FIELD_TYPE_NULL -> JsonNull
        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(getLong(index))
        Cursor.FIELD_TYPE_FLOAT -> getDouble(index).let { if (it.isFinite()) JsonPrimitive(it) else JsonPrimitive(it.toString()) }
        Cursor.FIELD_TYPE_BLOB -> JsonPrimitive(Base64.encodeToString(getBlob(index), Base64.NO_WRAP))
        else -> getString(index)?.let(::JsonPrimitive) ?: JsonNull
    }
    return value to JsonPrimitive(name)
}
private fun Cursor.healthRawRow(id: JsonPrimitive, table: LocalHealthTable, directSession: Boolean = false): JsonObject {
    val columns = linkedMapOf<String, JsonElement>()
    val types = linkedMapOf<String, JsonElement>()
    val unavailable = linkedSetOf<String>()
    columnNames.forEachIndexed { index, name ->
        val originalName = if (table != LocalHealthTable.SESSION || directSession) name else when (val normalized = name.lowercase(Locale.ROOT)) {
            "values" -> "id"
            "device_id", "wwid", "type", "time_to", "time_from", "synced", "deleted" -> normalized
            else -> return@forEachIndexed // Synthetic rowid and NULL placeholders are not original session columns.
        }
        try {
            val (value, type) = healthCell(index)
            types[originalName] = type
            columns[originalName] = value
        } catch (problem: Exception) {
            unavailable += originalName
            android.util.Log.w("TicBoxHealthBridge", "Provider cell unavailable; table=${table.wireName}; column=$originalName; exception=${problem.javaClass.simpleName}")
        }
    }
    if (table == LocalHealthTable.SESSION) {
        val missing = setOf("id", "device_id", "wwid", "type", "time_to", "time_from", "synced", "deleted") - columns.keys
        unavailable += missing
        if (missing.isNotEmpty()) android.util.Log.w("TicBoxHealthBridge", "Session fields unavailable; columns=${missing.joinToString(",")}")
    }
    val row = buildJsonObject {
        put("id", id); put("columns", JsonObject(columns)); put("storageTypes", JsonObject(types))
        if (unavailable.isNotEmpty()) { put("partial", true); put("unavailableColumns", JsonArray(unavailable.map(::JsonPrimitive))) }
    }
    return boundHealthRow(row).also { bounded ->
        if (bounded !== row) android.util.Log.i("TicBoxHealthBridge", "Source row too large to deliver whole; table=${table.wireName} " +
            "cellsLeftOut=${(bounded["oversizedColumns"] as? JsonArray)?.size ?: 0}")
    }
}
