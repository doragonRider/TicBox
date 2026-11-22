package dev.ticbox.companion.healthbridge

import dev.ticbox.companion.protocol.*
import kotlinx.serialization.json.*

/** A local source selection. Only these fixed predicates reach the OEM reader. */
internal data class HealthScanScope(
    val startId: Long? = null, val afterId: Long? = startId, val upperId: Long? = null,
    val since: Long? = null, val parent: Long? = null,
    val audit: Boolean = false, val remaining: Int? = null,
    val afterKey: String? = null, val upperKey: String? = null,
    val token: String = java.util.UUID.randomUUID().toString(), val partial: Boolean = false,
    val changed: Int = 0,
) {
    fun json(): JsonObject = buildJsonObject {
        startId?.let { put("start", it.toString()) }; afterId?.let { put("afterId", it.toString()) }
        upperId?.let { put("upperId", it.toString()) }; since?.let { put("since", it) }
        parent?.let { put("parent", it.toString()) }; put("audit", audit)
        remaining?.let { put("remaining", it) }; afterKey?.let { put("afterKey", it) }
        upperKey?.let { put("upperKey", it) }; put("token", token); put("partial", partial); put("changed", changed)
    }
    fun request(table: LocalHealthTable): JsonObject = JsonObject(json().toMutableMap().apply {
        put("table", JsonPrimitive(table.wireName)); put("op", JsonPrimitive("page"))
        put("limit", JsonPrimitive(minOf(remaining ?: LocalHealthProtocol.PAGE_SIZE, LocalHealthProtocol.PAGE_SIZE)))
    })
    companion object {
        fun parse(json: JsonObject) = HealthScanScope(json.number("start"), json.number("afterId"), json.number("upperId"),
            json.number("since"), json.number("parent"), json.flag("audit") == true, json.number("remaining")?.toInt(),
            json.text("afterKey"), json.text("upperKey"), json.text("token") ?: java.util.UUID.randomUUID().toString(),
            json.flag("partial") == true, json.number("changed")?.toInt() ?: 0)
    }
}

/** Predicates are composed locally; remote callers cannot supply identifiers or SQL. */
internal fun sourceSelection(table: LocalHealthTable, request: JsonObject, columns: Collection<String>? = null): Pair<String, Array<String>> {
    val key = if (table == LocalHealthTable.SESSION) "rowid" else "_id"
    val parts = mutableListOf<String>(); val args = mutableListOf<String>()
    request.number("afterId")?.let { parts += "$key>?"; args += it.toString() }
    request.number("upperId")?.let { parts += "$key<=?"; args += it.toString() }
    request.number("since")?.let {
        val time = if (table.privateFitness) "TIMESTAMP" else "time_to"
        // Time is only an optimization. A future schema without it can still be compared by ID.
        if (columns == null || columns.any { name -> name.equals(time, true) }) {
            parts += "($time>=? OR $time<=0)"; args += it.toString()
        }
    }
    if (table == LocalHealthTable.FITNESS_POINT) request.number("parent")?.let {
        parts += "RECORD_ID=?"; args += it.toString()
    }
    return parts.joinToString(" AND ").ifEmpty { "1" } to args.toTypedArray()
}
