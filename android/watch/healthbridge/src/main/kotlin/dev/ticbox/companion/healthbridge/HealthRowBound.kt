package dev.ticbox.companion.healthbridge

import dev.ticbox.companion.protocol.LocalHealthProtocol
import kotlinx.serialization.json.*

/** The most a source row's JSON text may take, so one row alone always fits a delivery or a change-log page. */
internal const val MAX_ROW_BYTES = LocalHealthProtocol.MAX_FRAME_BYTES - 64 * 1024

/** The cells that identify a row or link it to its parent; they are never left out. */
private val IDENTITY_COLUMNS = setOf("id", "_id", "RECORD_ID")

/**
 * The row as it is, or without its largest cells when its JSON text would take more than [limit] bytes; those are named in
 * oversizedColumns. Such a row can never be delivered whole, and failing on it would hold back every row after it. It reads
 * the same every time, so unlike a row with unreadable cells it isn't partial: it is compared and settled, not retried.
 */
internal fun boundHealthRow(row: JsonObject, limit: Int = MAX_ROW_BYTES): JsonObject {
    // Escaped UTF-8 takes at most six bytes a character, so almost every row is let through without writing it out.
    if (row.textChars() * 6 <= limit || utf8Length(row.toString()) <= limit) return row
    val columns = (row["columns"] as? JsonObject)?.toMutableMap() ?: return row
    val types = (row["storageTypes"] as? JsonObject)?.toMutableMap()
    val left = (row["oversizedColumns"] as? JsonArray).orEmpty().mapTo(linkedSetOf()) { it.jsonPrimitive.content }
    // Largest first, so as few cells as possible are left out.
    val largest = columns.filterKeys { it !in IDENTITY_COLUMNS }.map { (name, value) -> name to utf8Length(value.toString()) }.sortedByDescending { it.second }
    var bounded = row
    for ((name, _) in largest) {
        columns.remove(name); types?.remove(name); left += name
        bounded = JsonObject(row + buildMap {
            put("columns", JsonObject(columns)); types?.let { put("storageTypes", JsonObject(it)) }
            put("oversizedColumns", JsonArray(left.map(::JsonPrimitive)))
        })
        if (utf8Length(bounded.toString()) <= limit) break
    }
    return bounded
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
