package dev.ticbox.companion.healthbridge

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import dev.ticbox.companion.protocol.LocalHealthTable
import java.util.UUID
import kotlinx.serialization.json.*

/**
 * An explicitly installed, bounded, same-database change journal. Entries are candidates for a
 * committed-state reread, never authoritative tombstones. No OEM row, pragma or schema version is
 * changed here. In particular, the caller must validate the OEM schema before installation/use.
 *
 * BEFORE candidates matter: SQLite FAIL can keep earlier changes, and another AFTER trigger may
 * IGNORE the rest of a trigger program. An unfinished candidate therefore requests a whole-table
 * baseline. Nested/ignored writes cannot be paired unambiguously by SQLite's row triggers; an
 * ambiguous completion is similarly wide. AFTER fills auto-generated row IDs when pairing is safe.
 */
internal object AtomicHealthJournal {
    private const val VERSION = 1
    private const val MAX_EVENTS = 8192
    private const val MAX_KEY = 1024
    private const val PREFIX = "ticwatch_journal_v1_"
    private const val META = "${PREFIX}meta"
    private const val EVENTS = "${PREFIX}events"
    private const val PENDING = "${PREFIX}pending"
    private const val FAILURE = "ticbox journal invariant"

    private data class Target(val sql: String, val kind: String, val key: String?)
    private data class ObjectDdl(val type: String, val name: String, val table: String, val sql: String)
    private data class Metadata(val epoch: String, val head: Long, val floor: Long) {
        fun json() = buildJsonObject {
            put("version", VERSION); put("installed", true); put("epoch", epoch)
            put("head", head.toString()); put("floor", floor.toString())
        }
    }

    fun install(db: SQLiteDatabase, source: String): JsonObject = transaction(db) {
        val targets = targets(source)
        val existing = catalog(db)
        if (existing.isNotEmpty()) {
            validateNamespace(db, source, existing)
            return@transaction metadata(db, source).json()
        }
        val expected = schema(db, source, targets)
        expected.values.forEach { db.execSQL(it.sql) }
        db.execSQL("INSERT INTO $META(singleton,version,source,epoch,head,floor) VALUES(1,?,?,?,0,0)",
            arrayOf<Any>(VERSION, source, UUID.randomUUID().toString()))
        validateNamespace(db, source)
        metadata(db, source).json()
    }

    fun status(db: SQLiteDatabase, source: String): JsonObject {
        targets(source)
        val existing = catalog(db)
        if (existing.isEmpty()) return absent()
        validateNamespace(db, source, existing)
        return metadata(db, source).json()
    }

    /** Missing attached triggers (for example after an OEM table was dropped) do not prevent removal. */
    fun remove(db: SQLiteDatabase, source: String): JsonObject = transaction(db) {
        val targets = targets(source)
        val existing = catalog(db)
        if (existing.isEmpty()) return@transaction JsonObject(absent() + ("removed" to JsonPrimitive(false)))
        val variants = listOf(schema(source, targets, emptySet()), schema(source, targets, targets.map { it.sql }.toSet()))
        existing.forEach { actual ->
            if (variants.none { it[actual.name] == actual }) fail("journal_namespace_unknown")
        }
        existing.filter { it.type == "trigger" }.forEach { db.execSQL("DROP TRIGGER ${it.name}") }
        existing.filter { it.type == "index" }.forEach { db.execSQL("DROP INDEX ${it.name}") }
        existing.filter { it.type == "table" }.forEach { db.execSQL("DROP TABLE ${it.name}") }
        if (catalog(db).isNotEmpty()) fail("journal_namespace_unknown")
        JsonObject(absent() + ("removed" to JsonPrimitive(true)))
    }

    /**
     * Metadata, retention bounds and the event page come from one SELECT/read snapshot. The supplied
     * upper is fixed for the entire run; unrelated table events may be skipped by advancing to it.
     */
    fun read(db: SQLiteDatabase, source: String, table: LocalHealthTable, epoch: String,
        after: Long, upper: Long, limit: Int = 32): JsonObject {
        val target = targets(source).firstOrNull { it.kind == table.wireName }
            ?: fail("journal_invalid_source")
        if (after < 0 || limit !in 1..128) fail("journal_invalid_cursor")
        val existing = catalog(db)
        if (existing.isEmpty()) fail("journal_not_installed")
        validateNamespace(db, source, existing)
        var meta: Metadata? = null
        val events = ArrayList<JsonObject>(limit + 1)
        // The uncorrelated aggregate is evaluated once. It checks continuity without retaining keys.
        val sql = "WITH m AS (${metadataSelect()}), p AS (SELECT seq,kind,op,row_key,previous_key,wide,pending " +
            "FROM $EVENTS WHERE seq>? AND seq<=? AND kind IN (?, '*') ORDER BY seq LIMIT ?) " +
            "SELECT m.*,p.seq,p.kind,p.op,p.row_key,p.previous_key,p.wide,p.pending " +
            "FROM m LEFT JOIN p ON 1=1 ORDER BY p.seq"
        db.rawQuery(sql, arrayOf(after.toString(), upper.toString(), target.kind, (limit + 1).toString())).use { cursor ->
            while (cursor.moveToNext()) {
                if (meta == null) meta = checkedMetadata(cursor, source)
                if (cursor.isNull(10)) continue
                val kind = cursor.getString(11)
                val pending = cursor.getInt(16) != 0
                val wide = cursor.getInt(15) != 0 || pending || kind == "*"
                events += buildJsonObject {
                    put("sequence", cursor.getLong(10).toString())
                    put("key", cursor.textOrNull(13)?.let(::JsonPrimitive) ?: JsonNull)
                    put("previousKey", cursor.textOrNull(14)?.let(::JsonPrimitive) ?: JsonNull)
                    put("wide", wide); put("pending", pending)
                }
            }
        }
        val observed = meta ?: fail("journal_metadata_invalid")
        val reset = epoch != observed.epoch || after < observed.floor || after > upper || upper > observed.head || upper < 0
        if (reset) return JsonObject(observed.json() + buildJsonObject {
            put("events", JsonArray(emptyList())); put("nextSequence", observed.head.toString())
            put("complete", false); put("reset", true); put("wide", true)
        })
        val complete = events.size <= limit
        val page = events.take(limit)
        return JsonObject(observed.json() + buildJsonObject {
            put("events", JsonArray(page))
            put("nextSequence", if (complete) upper.toString() else page.last().getValue("sequence").jsonPrimitive.content)
            put("complete", complete); put("reset", false)
            put("wide", page.any { it.getValue("wide").jsonPrimitive.boolean })
        })
    }

    private fun targets(source: String): List<Target> = when (source) {
        "health" -> listOf(Target("data_point", "data_point", "_id"), Target("data_session", "data_session", "id"), Target("data_source", "*", null))
        "fitness" -> listOf(Target("RECORD", "fitness_record", "_id"), Target("POINT", "fitness_point", "_id"))
        else -> fail("journal_invalid_source")
    }

    private fun schema(db: SQLiteDatabase, source: String, targets: List<Target> = targets(source)) =
        schema(source, targets, targets.filter { secondaryUnique(db, it.sql) }.map { it.sql }.toSet())

    private fun schema(source: String, targets: List<Target>, wideTables: Set<String>): Map<String, ObjectDdl> {
        val result = linkedMapOf<String, ObjectDdl>()
        fun add(type: String, name: String, table: String, sql: String) { result[name] = ObjectDdl(type, name, table, sql) }
        add("table", META, META, "CREATE TABLE $META (singleton INTEGER NOT NULL PRIMARY KEY CHECK(singleton=1)," +
            "version INTEGER NOT NULL CHECK(version=$VERSION),source TEXT NOT NULL CHECK(source='$source')," +
            "epoch TEXT NOT NULL CHECK(length(epoch)=36),head INTEGER NOT NULL CHECK(typeof(head)='integer' AND head>=0)," +
            "floor INTEGER NOT NULL CHECK(typeof(floor)='integer' AND floor>=0 AND floor<=head AND head-floor<=$MAX_EVENTS))")
        val kinds = targets.joinToString(",") { "'${it.kind}'" }
        add("table", EVENTS, EVENTS, "CREATE TABLE $EVENTS (seq INTEGER PRIMARY KEY CHECK(seq>0)," +
            "kind TEXT NOT NULL CHECK(kind IN ($kinds)),op TEXT NOT NULL CHECK(op IN ('I','U','D'))," +
            "row_key TEXT CHECK(row_key IS NULL OR (typeof(row_key)='text' AND length(CAST(row_key AS BLOB)) BETWEEN 1 AND $MAX_KEY AND instr(row_key,char(0))=0 AND row_key NOT GLOB '*[^ -~]*'))," +
            "previous_key TEXT CHECK(previous_key IS NULL OR (typeof(previous_key)='text' AND length(CAST(previous_key AS BLOB)) BETWEEN 1 AND $MAX_KEY AND instr(previous_key,char(0))=0 AND previous_key NOT GLOB '*[^ -~]*'))," +
            "wide INTEGER NOT NULL CHECK(wide IN (0,1)),pending INTEGER NOT NULL CHECK(pending IN (0,1)))")
        add("index", PENDING, EVENTS, "CREATE INDEX $PENDING ON $EVENTS(kind,op,seq) WHERE pending=1")
        targets.forEach { target ->
            listOf("I" to "INSERT", "U" to "UPDATE", "D" to "DELETE").forEach { (op, sqlOp) ->
                val beforeName = "${PREFIX}${target.sql.lowercase()}_${op.lowercase()}_before"
                val key = target.key?.let { boundedKey(if (op == "D") "OLD.$it" else "NEW.$it") } ?: "NULL"
                val previous = if (op == "U") target.key?.let { boundedKey("OLD.$it") } ?: "NULL" else "NULL"
                val wide = if (target.key == null || (op != "D" && target.sql in wideTables)) "1"
                    else "CASE WHEN $key IS NULL${if (op == "U") " OR $previous IS NULL" else ""} THEN 1 ELSE 0 END"
                val pending = if (target.key != null && op != "D") 1 else 0
                val body = guard(source) + grow() +
                    "INSERT INTO $EVENTS(seq,kind,op,row_key,previous_key,wide,pending) " +
                    "SELECT head,'${target.kind}','$op',$key,$previous,$wide,$pending FROM $META WHERE singleton=1;" +
                    "SELECT CASE WHEN changes()!=1 THEN RAISE(ROLLBACK,'$FAILURE') END;" + trim()
                add("trigger", beforeName, target.sql, "CREATE TRIGGER $beforeName BEFORE $sqlOp ON ${target.sql} BEGIN $body END")
                if (pending != 0) {
                    val afterName = "${PREFIX}${target.sql.lowercase()}_${op.lowercase()}_after"
                    val match = "kind='${target.kind}' AND op='$op' AND pending=1"
                    // An ancestor may be trimmed by a >8192-event transaction. Its pre-transaction
                    // cursor is now below floor, so every external consumer must baseline. Keep an
                    // explicit wide fallback as well; capacity alone must never block an OEM write.
                    val completion = guard(source) + grow("NOT EXISTS(SELECT 1 FROM $EVENTS WHERE $match)") +
                        "INSERT INTO $EVENTS(seq,kind,op,row_key,previous_key,wide,pending) " +
                        "SELECT head,'${target.kind}','$op',$key,$previous,1,1 FROM $META WHERE singleton=1 AND NOT EXISTS(SELECT 1 FROM $EVENTS WHERE $match);" +
                        // Read only two pending index entries and do not rewrite already-wide
                        // historical IGNORE candidates on every subsequent successful insert.
                        "UPDATE $EVENTS SET wide=1 WHERE $match AND wide=0 AND EXISTS(SELECT 1 FROM $EVENTS WHERE $match LIMIT 1 OFFSET 1);" +
                        "UPDATE $EVENTS SET row_key=$key,previous_key=$previous,pending=0," +
                        "wide=CASE WHEN $key IS NULL${if (op == "U") " OR $previous IS NULL" else ""} THEN 1 ELSE wide END " +
                        "WHERE seq=(SELECT MAX(seq) FROM $EVENTS WHERE $match);" +
                        "SELECT CASE WHEN changes()!=1 THEN RAISE(ROLLBACK,'$FAILURE') END;" + trim()
                    add("trigger", afterName, target.sql, "CREATE TRIGGER $afterName AFTER $sqlOp ON ${target.sql} BEGIN $completion END")
                }
            }
        }
        return result
    }

    // OEM IDs are printable ASCII. Other SQLite TEXT/BLOB encodings remain wide rather than being
    // decoded into a lossy Java String (or letting embedded NUL bypass SQLite's TEXT length limit).
    private fun boundedKey(expression: String) = "CASE WHEN typeof($expression) IN ('integer','text') AND " +
        "length(CAST($expression AS BLOB)) BETWEEN 1 AND $MAX_KEY AND instr(CAST($expression AS TEXT),char(0))=0 " +
        "AND CAST($expression AS TEXT) NOT GLOB '*[^ -~]*' THEN CAST($expression AS TEXT) ELSE NULL END"

    private fun guard(source: String) = "SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM $META WHERE singleton=1 " +
        "AND version=$VERSION AND source='$source' AND typeof(head)='integer' AND typeof(floor)='integer' " +
        "AND head>=floor AND floor>=0 AND head-floor<=$MAX_EVENTS) THEN RAISE(ROLLBACK,'$FAILURE') END;"

    private fun grow(condition: String = "1") = "SELECT CASE WHEN $condition AND " +
        "((SELECT head FROM $META WHERE singleton=1)>=9223372036854775807 OR " +
        "EXISTS(SELECT 1 FROM $EVENTS WHERE seq=(SELECT head+1 FROM $META WHERE singleton=1))) " +
        "THEN RAISE(ROLLBACK,'$FAILURE') END;" +
        "UPDATE $META SET head=head+1,floor=MAX(floor,head+1-$MAX_EVENTS) WHERE singleton=1 AND $condition;" +
        "SELECT CASE WHEN changes()!=CASE WHEN $condition THEN 1 ELSE 0 END THEN RAISE(ROLLBACK,'$FAILURE') END;"

    private fun trim() = "DELETE FROM $EVENTS WHERE seq<=(SELECT floor FROM $META WHERE singleton=1);" +
        "SELECT CASE WHEN EXISTS(SELECT 1 FROM $EVENTS WHERE seq<=(SELECT floor FROM $META WHERE singleton=1)) " +
        "OR NOT EXISTS(SELECT 1 FROM $EVENTS WHERE seq=(SELECT head FROM $META WHERE singleton=1)) " +
        "THEN RAISE(ROLLBACK,'$FAILURE') END;"

    /** A non-primary UNIQUE index can make REPLACE delete a different key without a DELETE trigger. */
    private fun secondaryUnique(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery("PRAGMA index_list('$table')", null).use { cursor ->
            val unique = cursor.getColumnIndexOrThrow("unique"); val origin = cursor.getColumnIndexOrThrow("origin")
            var found = false
            while (cursor.moveToNext()) if (cursor.getInt(unique) != 0 && cursor.getString(origin) != "pk") found = true
            found
        }

    private fun catalog(db: SQLiteDatabase): List<ObjectDdl> = db.rawQuery(
        "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE substr(lower(name),1,?)=? OR lower(tbl_name) IN (?,?) ORDER BY name",
        arrayOf(PREFIX.length.toString(), PREFIX, META, EVENTS)).use { cursor ->
        buildList { while (cursor.moveToNext()) add(ObjectDdl(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3) ?: "")) }
    }

    private fun validateNamespace(db: SQLiteDatabase, source: String, existing: List<ObjectDdl> = catalog(db)) {
        val expected = schema(db, source)
        // A recognizable but incomplete namespace needs explicit removal/reinstallation, never repair
        // in place: a missing trigger may already have allowed unjournaled committed writes.
        existing.forEach { actual -> if (expected[actual.name] != actual) fail("journal_namespace_unknown") }
        if (existing.size != expected.size || existing.map { it.name }.toSet() != expected.keys) fail("journal_incomplete")
    }

    private fun metadataSelect() = "SELECT version,source,epoch,head,floor," +
        "(SELECT COUNT(*) FROM $META),(SELECT COUNT(*) FROM $EVENTS),(SELECT MIN(seq) FROM $EVENTS)," +
        "(SELECT MAX(seq) FROM $EVENTS),typeof(head)||':'||typeof(floor) FROM $META WHERE singleton=1"

    private fun metadata(db: SQLiteDatabase, source: String): Metadata = db.rawQuery(metadataSelect(), null).use { cursor ->
        if (!cursor.moveToFirst()) fail("journal_metadata_invalid")
        checkedMetadata(cursor, source)
    }

    private fun checkedMetadata(cursor: Cursor, source: String): Metadata {
        val epoch = cursor.getString(2) ?: fail("journal_metadata_invalid")
        val head = cursor.getLong(3); val floor = cursor.getLong(4)
        val validEpoch = try { UUID.fromString(epoch).toString() == epoch } catch (_: IllegalArgumentException) { false }
        if (cursor.getInt(0) != VERSION || cursor.getString(1) != source || !validEpoch || cursor.getLong(5) != 1L ||
            head < 0 || floor < 0 || floor > head || head - floor > MAX_EVENTS || cursor.getString(9) != "integer:integer" ||
            cursor.getLong(6) != head - floor || (head > floor && (cursor.isNull(7) || cursor.getLong(7) != floor + 1 || cursor.getLong(8) != head)) ||
            (head == floor && (!cursor.isNull(7) || !cursor.isNull(8)))) fail("journal_metadata_invalid")
        return Metadata(epoch, head, floor)
    }

    private fun Cursor.textOrNull(index: Int) = if (isNull(index)) null else getString(index)
    private fun absent() = buildJsonObject { put("version", VERSION); put("installed", false) }
    private fun fail(code: String): Nothing = throw HealthJournalException(code)

    private fun <T> transaction(db: SQLiteDatabase, block: () -> T): T {
        db.beginTransactionNonExclusive()
        try { return block().also { db.setTransactionSuccessful() } }
        finally { db.endTransaction() }
    }
}
