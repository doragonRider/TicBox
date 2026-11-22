package dev.ticbox.companion.protocol

import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Compare source values and storage types, not delivery metadata or JSON member order. */
object LocalHealthFingerprint {
    fun of(row: JsonObject): String {
        val source = buildJsonObject {
            put("columns", row["columns"] ?: row)
            row["storageTypes"]?.let { put("storageTypes", it) }
            row["sourceOccurrence"]?.let { put("sourceOccurrence", it) }
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical(source).toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }

    fun key(table: LocalHealthTable, row: JsonObject): String {
        val columns = row["columns"] as? JsonObject ?: row
        return when (table) {
            LocalHealthTable.SESSION -> columns.text("id") ?: row.text("id")
            LocalHealthTable.SPORT -> (columns.text("id") ?: row.text("id"))?.let {
                "$it#${row.number("sourceOccurrence") ?: 0}"
            }
            else -> row.text("id") ?: columns.text("_id")
        } ?: "raw:${of(row)}"
    }
}
