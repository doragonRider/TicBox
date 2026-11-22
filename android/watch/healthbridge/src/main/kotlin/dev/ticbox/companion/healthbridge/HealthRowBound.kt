package dev.ticbox.companion.healthbridge

import android.database.Cursor
import dev.ticbox.companion.protocol.LocalHealthProtocol
import kotlinx.serialization.json.*

/** The most a source row's JSON text may take, so one row alone always fits a delivery or a change-log page. */
internal const val MAX_ROW_BYTES = LocalHealthProtocol.MAX_FRAME_BYTES - 64 * 1024

/** The cells that identify a row or link it to its parent; they are never left out. */
internal val IDENTITY_COLUMNS = setOf("id", "_id", "RECORD_ID")

/**
 * The row as it is, or without its largest cells when its JSON text would take more than [limit] bytes; those are named in
 * oversizedColumns. Such a row can never be delivered whole, and failing on it would hold back every row after it. It reads
 * the same every time, so unlike a row with unreadable cells it isn't partial: it is compared and settled, not retried.
 */
internal fun boundHealthRow(row: JsonObject, limit: Int = MAX_ROW_BYTES): JsonObject {
    // Escaped UTF-8 takes at most six bytes a character, so almost every row is let through without writing it out.
    if (row.textChars() * 6 <= limit || utf8Length(row.toString()) <= limit) return row
    val columns = row["columns"] as? JsonObject ?: return row
    // Largest first, so as few cells as possible are left out.
    val largest = columns.filterKeys { it !in IDENTITY_COLUMNS }.map { (name, value) -> name to utf8Length(value.toString()) }.sortedByDescending { it.second }
    val left = mutableListOf<String>()
    var bounded = row
    for ((name, _) in largest) {
        left += name; bounded = row.withoutCells(left)
        if (utf8Length(bounded.toString()) <= limit) break
    }
    return bounded
}

/** The row without the named cells, which join its oversizedColumns. */
internal fun JsonObject.withoutCells(names: Collection<String>): JsonObject {
    if (names.isEmpty()) return this
    val gone = names.toSet()
    val left = (this["oversizedColumns"] as? JsonArray).orEmpty().mapTo(linkedSetOf()) { it.jsonPrimitive.content } + names
    return JsonObject(this + buildMap {
        (this@withoutCells["columns"] as? JsonObject)?.let { put("columns", JsonObject(it - gone)) }
        (this@withoutCells["storageTypes"] as? JsonObject)?.let { put("storageTypes", JsonObject(it - gone)) }
        put("oversizedColumns", JsonArray(left.map(::JsonPrimitive)))
    })
}

/**
 * Rows whose cells together don't fit a CursorWindow, so no cursor can hold them as they are. Each cell's size is read
 * first, then the row again without its largest cells, which are named in oversizedColumns; the large cells are never read.
 * [select] runs `SELECT * FROM <table> WHERE <selection> [ORDER BY <order>]`, through the official provider or on the file,
 * and the selection appends a compound SELECT of the same [columns], as the session carrier does.
 */
internal class OversizedRows(private val table: String, private val columns: List<String>,
    private val select: (selection: String, args: Array<String>, order: String?) -> Cursor?) {
    /** The first row's [keys] in [order], read without any of its other cells. */
    fun first(keys: Set<String>, where: String, args: Array<String>, order: String): Map<String, String?>? =
        select(carrier { if (it in keys) quote(it) else "NULL" } + " WHERE $where", args, "$order LIMIT 1")?.use { cursor ->
            if (!cursor.moveToFirst()) null else keys.associateWith { cursor.getString(cursor.getColumnIndexOrThrow(it)) }
        }

    /** The row whose [key] column holds [value], without its largest cells; null when it would fit a cursor after all. */
    fun read(key: String, value: String, build: (Cursor) -> JsonObject): JsonObject? {
        val match = " WHERE ${quote(key)} IN (?)"
        val sizes = select(carrier { "length(CAST(${quote(it)} AS BLOB))" } + match, arrayOf(value), null)?.use { cursor ->
            if (!cursor.moveToFirst()) null else columns.indices.associate { columns[it] to if (cursor.isNull(it)) 0L else cursor.getLong(it) }
        } ?: return null
        var total = sizes.values.sum()
        // Such a row fits a cursor, so the read failed for another reason.
        if (total <= MAX_ROW_BYTES) return null
        val left = mutableListOf<String>()
        for ((name, size) in sizes.filterKeys { it !in IDENTITY_COLUMNS }.entries.sortedByDescending { it.value }) {
            if (total <= MAX_ROW_BYTES) break
            left += name; total -= size
        }
        val row = select(carrier { if (it in left) "NULL" else quote(it) } + match, arrayOf(value), null)?.use { cursor ->
            if (!cursor.moveToFirst()) null else build(cursor)
        } ?: return null
        android.util.Log.i("TicBoxHealthBridge", "Source row too large for a cursor; table=$table cellsLeftOut=${left.size}")
        return boundHealthRow(row.withoutCells(left))
    }

    private fun carrier(cell: (String) -> String) = "0 UNION ALL SELECT ${columns.joinToString(",", transform = cell)} FROM $table"

    companion object {
        fun quote(name: String) = "\"" + name.replace("\"", "\"\"") + "\""
    }
}

/** At least a sixth of the UTF-8 size of the element's JSON text, from the lengths it already holds. */
internal fun JsonElement.textChars(): Long = when (this) {
    is JsonObject -> entries.sumOf { (name, value) -> name.length + value.textChars() + 4L } + 2
    is JsonArray -> sumOf { it.textChars() + 1L } + 2
    is JsonPrimitive -> content.length + 2L
}

/** The UTF-8 size of [text] without encoding it; a lone surrogate encodes as one replacement byte. */
internal fun utf8Length(text: String): Int {
    var bytes = 0; var index = 0
    while (index < text.length) {
        val char = text[index]
        bytes += when {
            char.code < 0x80 -> 1
            char.code < 0x800 -> 2
            char.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate() -> { index++; 4 }
            char.isSurrogate() -> 1
            else -> 3
        }
        index++
    }
    return bytes
}
