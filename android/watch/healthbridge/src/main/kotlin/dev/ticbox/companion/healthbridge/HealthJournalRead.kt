package dev.ticbox.companion.healthbridge

import android.database.sqlite.SQLiteBlobTooBigException
import dev.ticbox.companion.protocol.*
import java.security.MessageDigest
import kotlinx.serialization.json.*

/**
 * The answers to change-log reads, worked out the same way on the database file (root helper) and on the module's
 * answers from inside the official app, so a cursor taken on one path stays valid on the other.
 */
internal object HealthJournalRead {
    /** Events per page. A page whose rows don't fit is read again with half as many events. */
    const val PAGE_EVENTS = 128
    /** Rows per query: inside SQLite's 999 parameters and, at ordinary row sizes, the module's answer size. */
    private const val KEYS_PER_QUERY = 128
    /** Room left for one reply's rows; the rest of the frame holds events and metadata. */
    private const val ROW_BUDGET = LocalHealthProtocol.MAX_FRAME_BYTES - 64 * 1024
    /** A combined read stops adding pages when less than this is left; those tables read their page on their own. */
    private const val MIN_PAGE_BUDGET = 64 * 1024

    /** Identifies one OEM file and installation: a different value means every cursor on it starts over. */
    fun generation(source: String, version: String, device: Long, inode: Long, schema: String): String =
        MessageDigest.getInstance("SHA-256").digest("$source\n$version\n$device\n$inode\n$schema".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun identity(generation: String, providerVersion: String) =
        mapOf("generation" to JsonPrimitive(generation), "providerVersion" to JsonPrimitive(providerVersion))

    /** One table's events after a cursor, or a reset when the cursor belongs to another file or installation. */
    fun page(db: JournalSql, source: String, table: LocalHealthTable, request: JsonObject, generation: String): JsonObject =
        if (generation != request.text("generation")) JsonObject(AtomicHealthJournal.status(db, source) + ("reset" to JsonPrimitive(true)))
        else capture(db, source, table, request.text("epoch") ?: "", request.number("after") ?: -1, request.number("upper") ?: -1)

    /**
     * The window, plus each table's page after its cursor while the reply has room, so a sync usually needs one read per
     * source. A cursor the window no longer admits gets no page: the caller starts that table over anyway.
     */
    fun read(db: JournalSql, source: String, positions: JsonObject?, generation: String, providerVersion: String): JsonObject {
        val status = AtomicHealthJournal.status(db, source)
        val window = HealthJournalWindow.parse(JsonObject(status + identity(generation, providerVersion))) ?: return status
        var budget = LocalHealthProtocol.MAX_FRAME_BYTES - 16 * 1024
        val pages = buildJsonObject {
            positions?.forEach { (name, value) ->
                val table = LocalHealthTable.entries.firstOrNull { it.wireName == name && journalSource(it) == source } ?: return@forEach
                val after = runCatching { HealthJournalPosition.parse(value.jsonObject) }.getOrNull() ?: return@forEach
                if (after.epoch != window.epoch || after.generation != generation || after.sequence !in window.floor until window.head) return@forEach
                if (budget < MIN_PAGE_BUDGET) return@forEach
                // A page that can't be read here is read on its own later, which reports the problem the usual way.
                val page = try { capture(db, source, table, after.epoch, after.sequence, window.head, budget = budget - 16 * 1024) }
                    catch (_: HealthJournalException) { return@forEach }
                val complete = JsonObject(page + identity(generation, providerVersion))
                budget -= complete.toString().toByteArray(Charsets.UTF_8).size
                put(name, complete)
            }
        }
        return JsonObject(status + ("pages" to pages))
    }

    fun capture(db: JournalSql, source: String, table: LocalHealthTable, epoch: String, after: Long, upper: Long,
        limit: Int = PAGE_EVENTS, budget: Int = ROW_BUDGET): JsonObject {
        val read = AtomicHealthJournal.read(db, source, table, epoch, after, upper, limit)
        if (read.flag("reset") == true || read.flag("wide") == true) return read
        val events = (read["events"] as? JsonArray).orEmpty().map { it as JsonObject }
        val keys = events.flatMap { listOfNotNull(it.text("key"), it.text("previousKey")) }.distinct()
        if (keys.isEmpty()) return JsonObject(read + mapOf("rows" to JsonArray(emptyList()), "missingKeys" to JsonArray(emptyList())))
        val missing = keys.toMutableSet(); val rows = mutableListOf<JsonObject>(); var bytes = 0
        for (chunk in keys.chunked(KEYS_PER_QUERY)) rows(db, table, chunk) { row, key ->
            bytes += row.toString().toByteArray(Charsets.UTF_8).size
            if (bytes > budget) {
                if (limit == 1) throw HealthJournalException("journal_row_exceeds_budget")
                return capture(db, source, table, epoch, after, upper, limit / 2, budget)
            }
            rows += row; missing -= key
        }
        // A key-only log is a prompt to reread committed state, not a claim that every candidate was applied.
        return JsonObject(read + mapOf("rows" to JsonArray(rows), "missingKeys" to JsonArray(missing.map(::JsonPrimitive))))
    }

    /**
     * An answer too large for the module is asked for again in halves, and a row too large for any cursor is read on its
     * own without its largest cells. Rows already handed over aren't asked for again.
     */
    private inline fun rows(db: JournalSql, table: LocalHealthTable, keys: List<String>, each: (JsonObject, String) -> Unit) {
        val sqlTable = when (table) {
            LocalHealthTable.POINT -> "data_point"; LocalHealthTable.SESSION -> "data_session"
            LocalHealthTable.FITNESS_RECORD -> "RECORD"; LocalHealthTable.FITNESS_POINT -> "POINT"
            else -> throw HealthJournalException("journal_invalid_source")
        }
        val key = if (table == LocalHealthTable.SESSION) "id" else "_id"
        val pending = ArrayDeque(listOf(keys))
        while (pending.isNotEmpty()) {
            val chunk = pending.removeFirst()
            val seen = HashSet<String>()
            val failure = try {
                db.rawQuery("SELECT * FROM $sqlTable WHERE $key IN (${chunk.joinToString(",") { "?" }})", chunk.toTypedArray()).use {
                    while (it.moveToNext()) {
                        val value = it.getString(it.getColumnIndexOrThrow(key))
                        seen += value; each(it.healthDatabaseRow(value, table), value)
                    }
                }
                continue
            } catch (problem: Exception) {
                if (problem !is SQLiteBlobTooBigException && (problem as? HealthJournalException)?.code !in TOO_LARGE) throw problem
                problem
            }
            val rest = chunk.filter { it !in seen }
            if (rest.size > 1) { pending.addFirst(rest.subList(rest.size / 2, rest.size)); pending.addFirst(rest.subList(0, rest.size / 2)) }
            else if (rest.size == 1) each(oversized(db, sqlTable, key, rest[0], table) ?: throw failure, rest[0])
        }
    }

    private val TOO_LARGE = setOf("journal_reply_too_large", "journal_row_too_big")

    /** One row no cursor can hold, read on its own without its largest cells; null when that isn't why the read failed. */
    private fun oversized(db: JournalSql, sqlTable: String, key: String, value: String, table: LocalHealthTable): JsonObject? {
        val columns = db.rawQuery("SELECT * FROM $sqlTable WHERE 0", null).use { it.columnNames.toList() }
        return OversizedRows(sqlTable, columns) { selection, args, order ->
            db.rawQuery("SELECT * FROM $sqlTable WHERE $selection" + order?.let { " ORDER BY $it" }.orEmpty(), args)
        }.read(key, value) { it.healthDatabaseRow(value, table) }
    }
}
