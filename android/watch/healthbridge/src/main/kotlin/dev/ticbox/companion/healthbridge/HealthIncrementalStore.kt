package dev.ticbox.companion.healthbridge

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import dev.ticbox.companion.protocol.*
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.*

internal data class HealthCapture(val run: String, val full: Boolean, val scopes: List<HealthScanScope>,
    val partial: Boolean = false, val scanned: Long = 0, val providerVersion: String? = null,
    /** How the source files looked before this capture read them. */
    val stamp: String? = null,
    val journal: JournalHealthCapture? = null,
    /** Full source coverage need not resend rows already durably owned by the bridge/phone. */
    val resend: Boolean = full,
    val journalFallback: Boolean = false,
    /** A first sync the phone limited to rows from this time on; older ones stay on the watch. */
    val window: Long? = null) {
    val done get() = scopes.isEmpty() && (journal == null || journal.finished)
    fun json() = buildJsonObject {
        put("run", run); put("full", full); put("partial", partial); put("scanned", scanned)
        providerVersion?.let { put("providerVersion", it) }
        stamp?.let { put("stamp", it) }
        journal?.let { put("journal", it.json()) }
        put("resend", resend); put("journalFallback", journalFallback)
        window?.let { put("window", it) }
        put("scopes", JsonArray(scopes.map { it.json() }))
    }
    companion object {
        fun parse(json: JsonObject) = HealthCapture(json.text("run")!!, json.flag("full") == true,
            (json["scopes"] as JsonArray).map { HealthScanScope.parse(it as JsonObject) },
            json.flag("partial") == true, json.number("scanned") ?: 0, json.text("providerVersion"), json.text("stamp"),
            (json["journal"] as? JsonObject)?.let(JournalHealthCapture::parse),
            json.flag("resend") ?: (json.flag("full") == true), json.flag("journalFallback") == true, json.number("window"))
    }
}

internal data class JournalHealthCapture(val position: HealthJournalPosition, val upper: Long,
    val baseline: Boolean, val finished: Boolean = false) {
    fun json() = buildJsonObject {
        put("position", position.json()); put("upper", upper.toString()); put("baseline", baseline); put("finished", finished)
    }
    companion object {
        fun parse(json: JsonObject) = JournalHealthCapture(HealthJournalPosition.parse(json["position"] as JsonObject),
            json.number("upper")!!, json.flag("baseline") == true, json.flag("finished") == true)
    }
}

/** Delivery state lives here. The optional OEM journal never stores phone ACKs or health payloads. */
internal class HealthIncrementalStore(context: Context, name: String = "health-incremental.sqlite",
    private val now: () -> Long = System::currentTimeMillis) : AutoCloseable {
    // Version 1 is the baseline shipped in 0.2.0. Any format change must raise it and add a migration.
    private val helper = object : SQLiteOpenHelper(context, File(context.noBackupFilesDir, name).path, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE metadata (name TEXT PRIMARY KEY,value TEXT NOT NULL)")
            db.execSQL("INSERT INTO metadata VALUES ('epoch','${UUID.randomUUID()}')")
            db.execSQL("CREATE TABLE observations (receiver TEXT NOT NULL,kind TEXT NOT NULL,row_key TEXT NOT NULL,source_id INTEGER,parent_id INTEGER,fingerprint TEXT,present INTEGER NOT NULL,last_changed INTEGER NOT NULL,seen TEXT,payload TEXT,PRIMARY KEY(receiver,kind,row_key))")
            db.execSQL("CREATE INDEX observed_range ON observations(receiver,kind,source_id)")
            db.execSQL("CREATE INDEX observed_parent ON observations(receiver,kind,parent_id)")
            db.execSQL("CREATE TABLE pending (revision INTEGER PRIMARY KEY AUTOINCREMENT,receiver TEXT NOT NULL,kind TEXT NOT NULL,row_key TEXT NOT NULL,payload TEXT NOT NULL,UNIQUE(receiver,kind,row_key))")
            db.execSQL("CREATE TABLE batches (receiver TEXT NOT NULL,kind TEXT NOT NULL,batch_id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(receiver,kind))")
            db.execSQL("CREATE TABLE progress (receiver TEXT NOT NULL,kind TEXT NOT NULL,baseline INTEGER NOT NULL DEFAULT 0,audit_after INTEGER NOT NULL DEFAULT 0,step INTEGER NOT NULL DEFAULT 0,capture TEXT,schema_fields TEXT,PRIMARY KEY(receiver,kind))")
            db.execSQL("CREATE TABLE children (receiver TEXT NOT NULL,parent_id INTEGER NOT NULL,last_checked INTEGER NOT NULL,same_count INTEGER NOT NULL,PRIMARY KEY(receiver,parent_id))")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
            error("No migration from health bridge schema $oldVersion to $newVersion")
    }
    private val db get() = helper.writableDatabase
    private fun args(receiver: String, table: LocalHealthTable) = arrayOf(receiver, table.wireName)
    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val database = db; database.beginTransaction()
        try { val result = block(database); database.setTransactionSuccessful(); return result }
        finally { database.endTransaction() }
    }
    private fun ensure(receiver: String, table: LocalHealthTable) {
        db.execSQL("INSERT OR IGNORE INTO progress(receiver,kind) VALUES (?,?)", args(receiver, table))
    }
    private fun save(receiver: String, table: LocalHealthTable, capture: HealthCapture) {
        db.execSQL("UPDATE progress SET capture=? WHERE receiver=? AND kind=?",
            arrayOf<Any?>(capture.json().toString(), receiver, table.wireName))
    }
    private fun metadata(name: String) = db.rawQuery("SELECT value FROM metadata WHERE name=?", arrayOf(name)).use {
        if (it.moveToFirst()) it.getString(0) else null
    }
    private fun setMetadata(name: String, value: String) {
        db.execSQL("INSERT OR REPLACE INTO metadata VALUES (?,?)", arrayOf(name, value))
    }
    private fun stampKey(receiver: String, table: LocalHealthTable) = "source_stamp:$receiver:${table.wireName}"
    private fun journalKey(receiver: String, table: LocalHealthTable) = "journal_cursor:$receiver:${table.wireName}"
    private fun auditKey(receiver: String, table: LocalHealthTable) = "journal_audit:$receiver:${table.wireName}"
    private fun fallbackKey(receiver: String, table: LocalHealthTable) = "journal_fallback:$receiver:${table.wireName}"
    private fun clearFallback(receiver: String, table: LocalHealthTable) {
        db.delete("metadata", "name=?", arrayOf(fallbackKey(receiver, table)))
    }
    private fun deferredKey(receiver: String, table: LocalHealthTable) = "deferred_before:$receiver:${table.wireName}"
    /** Rows older than this were never sent to the receiver; set by a first-sync window, cleared by a complete resend. */
    fun deferredBefore(receiver: String, table: LocalHealthTable) = metadata(deferredKey(receiver, table))?.toLongOrNull()
    private fun lowestObserved(receiver: String, table: LocalHealthTable) =
        db.rawQuery("SELECT MIN(source_id) FROM observations WHERE receiver=? AND kind=?", args(receiver, table)).use { c ->
            c.moveToFirst(); if (c.isNull(0)) null else c.getLong(0)
        }
    private fun observed(receiver: String, table: LocalHealthTable) =
        db.rawQuery("SELECT 1 FROM observations WHERE receiver=? AND kind=? LIMIT 1", args(receiver, table)).use { it.moveToFirst() }

    /**
     * [window] is how far back the phone wants a first sync to reach. It applies only to a receiver this table has never
     * sent rows to, or one an earlier window already left rows behind for.
     */
    fun begin(receiver: String, table: LocalHealthTable, run: String, forceFull: Boolean,
        journalWindow: HealthJournalWindow? = null, window: Long? = null,
        sourceStamp: () -> String? = { null }): HealthCapture = transaction {
        ensure(receiver, table)
        val previous = it.rawQuery("SELECT capture,baseline,audit_after FROM progress WHERE receiver=? AND kind=?", args(receiver, table)).use { cursor ->
            cursor.moveToFirst()
            Triple(if (cursor.isNull(0)) null else HealthCapture.parse(LocalHealthProtocol.parse(cursor.getString(0).toByteArray())),
                cursor.getInt(1) != 0, cursor.getLong(2))
        }
        val old = previous.first
        val capture = when {
            old?.run == run -> old
            old != null && !old.done && !forceFull -> if (old.journal != null && journalWindow == null)
                fallback(receiver, table, old.copy(run = run), sourceStamp()) else old.copy(run = run)
            else -> {
                val resend = forceFull || !previous.second
                val deferred = deferredBefore(receiver, table)
                val since = window?.takeIf { !previous.second && table in WINDOWED && (deferred != null || !observed(receiver, table)) }
                    ?.let { deferred ?: (now() - it) }
                val journalLost = journalWindow == null && metadata(journalKey(receiver, table)) != null
                val full = resend || (journalLost && metadata(fallbackKey(receiver, table)) == null)
                val planned = if (journalWindow != null) {
                    val cursor = metadata(journalKey(receiver, table))?.let { json ->
                        runCatching { HealthJournalPosition.parse(LocalHealthProtocol.parse(json.toByteArray())) }.getOrNull()
                    }
                    val lastAudit = metadata(auditKey(receiver, table))?.toLongOrNull()
                    val baseline = full || cursor == null || cursor.epoch != journalWindow.epoch || cursor.generation != journalWindow.generation ||
                        cursor.sequence !in journalWindow.floor..journalWindow.head || lastAudit == null || now() - lastAudit !in 0 until JOURNAL_AUDIT_MILLIS
                    val start = if (baseline) journalWindow.position() else cursor!!
                    if (!baseline && start.sequence == journalWindow.head) clearFallback(receiver, table)
                    HealthCapture(run, baseline, if (baseline) coverage(receiver, table, since, resend) else emptyList(),
                        journal = JournalHealthCapture(start, journalWindow.head, baseline, !baseline && start.sequence == journalWindow.head),
                        resend = resend, window = since)
                } else {
                    val stamp = sourceStamp()
                    // Unjournaled fitness retains its prior best-effort polling path; FULL never skips it.
                    val unchanged = !full && stamp != null && stamp == metadata(stampKey(receiver, table))
                    HealthCapture(run, full, if (unchanged) emptyList() else plan(receiver, table, full, previous.third, since, resend),
                        stamp = stamp, resend = resend, journalFallback = journalLost, window = since)
                }
                Log.i("TicBoxHealthBridge", "Capture planned; table=${table.wireName} full=${planned.full} " +
                    "resend=${planned.resend} journal=${planned.journal != null} scopes=${describe(planned.scopes)}")
                planned
            }
        }
        save(receiver, table, capture); capture
    }

    /** Explicit configuration changes invalidate only acquisition plans, never retained phone deliveries. */
    fun invalidateJournal(source: String) = transaction { database ->
        LocalHealthTable.entries.filter { journalSource(it) == source }.forEach { table ->
            val suffix = ":${table.wireName}"
            database.rawQuery("SELECT name FROM metadata", null).use { cursor ->
                val names = mutableListOf<String>()
                while (cursor.moveToNext()) cursor.getString(0).takeIf {
                    (it.startsWith("journal_cursor:") || it.startsWith("journal_audit:") || it.startsWith("journal_fallback:")) && it.endsWith(suffix)
                }?.let(names::add)
                names.forEach { database.delete("metadata", "name=?", arrayOf(it)) }
            }
            database.execSQL("UPDATE progress SET baseline=0,capture=NULL WHERE kind=?", arrayOf(table.wireName))
        }
    }

    /** An unavailable journal never makes an empty log look like a completed source observation. */
    fun withoutJournal(receiver: String, table: LocalHealthTable, capture: HealthCapture, stamp: String? = null): HealthCapture = transaction {
        fallback(receiver, table, capture, stamp).also { save(receiver, table, it) }
    }

    private fun fallback(receiver: String, table: LocalHealthTable, capture: HealthCapture, stamp: String?): HealthCapture {
        val previous = db.rawQuery("SELECT baseline,audit_after FROM progress WHERE receiver=? AND kind=?", args(receiver, table)).use {
            check(it.moveToFirst()); (it.getInt(0) != 0) to it.getLong(1)
        }
        val full = capture.resend || !previous.first || metadata(fallbackKey(receiver, table)) == null
        val unchanged = !full && stamp != null && stamp == metadata(stampKey(receiver, table))
        return capture.copy(full = full, partial = false,
            scopes = if (unchanged) emptyList() else plan(receiver, table, full, previous.second, capture.window, capture.resend),
            journal = null, stamp = stamp, journalFallback = true)
    }

    fun resetJournal(receiver: String, table: LocalHealthTable, capture: HealthCapture, window: HealthJournalWindow): HealthCapture = transaction {
        capture.copy(full = true, scopes = coverage(receiver, table, capture.window, capture.resend), partial = false, stamp = null,
            journal = JournalHealthCapture(window.position(), window.head, baseline = true), journalFallback = false).also { save(receiver, table, it) }
    }

    /** Baselines acknowledge only the head captured BEFORE reading. Concurrent writes are replayed next run. */
    fun finishJournal(receiver: String, table: LocalHealthTable, capture: HealthCapture): HealthCapture = transaction {
        check(capture.scopes.isEmpty())
        val journal = checkNotNull(capture.journal)
        val next = capture.copy(journal = journal.copy(position = journal.position.copy(sequence = journal.upper), finished = true))
        if (!capture.partial) {
            setMetadata(journalKey(receiver, table), next.journal!!.position.json().toString())
            if (journal.baseline) setMetadata(auditKey(receiver, table), now().toString())
            clearFallback(receiver, table)
            it.execSQL("UPDATE progress SET baseline=1 WHERE receiver=? AND kind=?", args(receiver, table))
        }
        save(receiver, table, next); next
    }

    /** Row observations, confirmed current absence, outbox and consumed source sequence commit together. */
    fun observeJournal(receiver: String, table: LocalHealthTable, capture: HealthCapture, page: JsonObject): HealthCapture = transaction {
        val journal = checkNotNull(capture.journal)
        val rows = (page["rows"] as? JsonArray).orEmpty().map { LocalHealthRow(it as JsonObject) }
        check(rows.none { it.partial })
        rows.forEach { row -> observeRow(receiver, table, capture, HealthScanScope(), row) }
        val missing = (page["missingKeys"] as? JsonArray).orEmpty().map { (it as JsonPrimitive).content }
        missing.forEach { key ->
            it.rawQuery("SELECT row_key,source_id,payload FROM observations WHERE receiver=? AND kind=? AND row_key=? AND present=1", args(receiver, table) + key).use { c ->
                if (c.moveToFirst()) absent(receiver, table, c.getString(0), if (c.isNull(1)) null else c.getLong(1), if (c.isNull(2)) null else c.getString(2))
            }
        }
        val sequence = checkNotNull(page.number("nextSequence"))
        check(sequence in journal.position.sequence..journal.upper)
        check(page.flag("complete") == true || sequence > journal.position.sequence)
        check(page.flag("complete") != true || sequence == journal.upper)
        val next = capture.copy(scanned = capture.scanned + rows.size,
            providerVersion = page.text("providerVersion") ?: capture.providerVersion,
            journal = journal.copy(position = journal.position.copy(sequence = sequence), finished = page.flag("complete") == true))
        setMetadata(journalKey(receiver, table), next.journal!!.position.json().toString())
        clearFallback(receiver, table)
        save(receiver, table, next); next
    }

    private fun plan(receiver: String, table: LocalHealthTable, full: Boolean, auditAfter: Long,
        window: Long?, resend: Boolean): List<HealthScanScope> {
        if (table == LocalHealthTable.SESSION || table == LocalHealthTable.FITNESS_RECORD || table == LocalHealthTable.SPORT)
            return listOf(HealthScanScope(audit = true))
        val deep = deepCheckDue(receiver, table, full)
        if (full) return coverage(receiver, table, window, resend)
        // Rows a first-sync window left behind must not trickle in through the regular checks either.
        val deferred = deferredBefore(receiver, table)
        return buildList {
            val maximum = db.rawQuery("SELECT MAX(source_id) FROM observations WHERE receiver=? AND kind=?", args(receiver, table)).use { c ->
                c.moveToFirst(); if (c.isNull(0)) null else c.getLong(0)
            }
            add(if (maximum == null && deferred != null) HealthScanScope(since = deferred) else HealthScanScope(startId = maximum))
            if (table == LocalHealthTable.POINT) {
                val since = mutableHealthSince(receiver, if (deep) 3 * DAY else DAY)
                add(HealthScanScope(since = deferred?.let { maxOf(since, it) } ?: since))
            }
            if (table == LocalHealthTable.FITNESS_POINT) dueParents(receiver, deferred).forEach { id -> add(HealthScanScope(parent = id, audit = true)) }
            if (deep) {
                // Under a window, rows below the lowest one read are the ones left behind; with none read there is nothing to check.
                val floor = if (deferred == null) 0L else lowestObserved(receiver, table)?.minus(1)
                if (floor != null) add(HealthScanScope(startId = maxOf(auditAfter, floor).takeIf { value -> value > 0 }, audit = true, remaining = 256))
            }
        }
    }

    /** Reads a whole table, except rows a first-sync window left on the watch, until the phone asks for everything. */
    private fun coverage(receiver: String, table: LocalHealthTable, window: Long?, resend: Boolean): List<HealthScanScope> {
        if (window != null) return listOf(HealthScanScope(before = window, remaining = 1, probe = true), HealthScanScope(since = window))
        val deferred = if (resend) null else deferredBefore(receiver, table)
        if (deferred == null || table !in WINDOWED) return listOf(HealthScanScope(audit = true))
        val floor = lowestObserved(receiver, table) ?: return listOf(HealthScanScope(since = deferred))
        return listOf(HealthScanScope(startId = floor - 1, audit = true))
    }

    /** Older rows are only left behind, and the phone told so, when there are any. */
    fun probed(receiver: String, table: LocalHealthTable, capture: HealthCapture, older: Boolean): HealthCapture = transaction {
        val window = checkNotNull(capture.window)
        if (older) setMetadata(deferredKey(receiver, table), window.toString())
        else it.delete("metadata", "name=?", arrayOf(deferredKey(receiver, table)))
        capture.copy(scopes = capture.scopes.drop(1)).also { next -> save(receiver, table, next) }
    }

    /** Most of a sync goes to rereading what rarely changes, so the wider checks run at most hourly. */
    private fun deepCheckDue(receiver: String, table: LocalHealthTable, full: Boolean): Boolean {
        val key = "deep_check:$receiver:${table.wireName}"
        val last = metadata(key)?.toLongOrNull()
        val due = full || last == null || now() - last !in 0 until DEEP_CHECK_MILLIS
        if (due) setMetadata(key, now().toString())
        return due
    }

    /** Scope kinds only, for logs: no IDs or times. */
    private fun describe(scopes: List<HealthScanScope>) = scopes.joinToString(",") { scope -> when {
        scope.probe -> "probe"; scope.parent != null -> "workout"; scope.remaining != null -> "deep"
        scope.since != null -> "recent"; scope.audit -> "all"; else -> "new"
    } }.ifEmpty { "none" }

    /** An older sleep session that reopens/merges brings its original point range back into focus. */
    private fun mutableHealthSince(receiver: String, window: Long): Long {
        var since = now() - window
        var open = 0; var changed = 0
        db.rawQuery("SELECT payload,last_changed FROM observations WHERE receiver=? AND kind=? AND present=1",
            arrayOf(receiver, LocalHealthTable.SESSION.wireName)).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.isNull(0)) continue
                val columns = LocalHealthRow(LocalHealthProtocol.parse(cursor.getString(0).toByteArray())).columns
                val start = columns.number("time_from") ?: continue
                val end = columns.number("time_to") ?: 0
                if (start > 0 && (end <= start || cursor.getLong(1) >= now() - DAY) && start < now() - window) {
                    since = minOf(since, start); if (end <= start) open++ else changed++
                }
            }
        }
        // Counts only: how many unfinished or recently changed sessions reach back past the usual range.
        if (open + changed > 0) Log.i("TicBoxHealthBridge", "Recent range widened; openSessions=$open changedSessions=$changed")
        return since
    }

    /** Unfinished records always stay eligible. Stable children back off but remain in the audit cycle. */
    private fun dueParents(receiver: String, deferred: Long?): List<Long> = db.rawQuery(
        "SELECT o.source_id,o.payload,o.last_changed,c.last_checked,c.same_count FROM observations o LEFT JOIN children c ON c.receiver=o.receiver AND c.parent_id=o.source_id WHERE o.receiver=? AND o.kind=? AND o.present=1",
        arrayOf(receiver, LocalHealthTable.FITNESS_RECORD.wireName)).use { cursor ->
        val result = mutableListOf<Pair<Long, Long>>()
        while (cursor.moveToNext()) {
            if (cursor.isNull(0) || cursor.isNull(1)) continue
            val columns = LocalHealthRow(LocalHealthProtocol.parse(cursor.getString(1).toByteArray())).columns
            val end = columns.number("END_TIME")
            if (deferred != null && end != null && end in 1 until deferred) continue
            val checked = if (cursor.isNull(3)) 0 else cursor.getLong(3)
            val same = if (cursor.isNull(4)) 0 else cursor.getInt(4)
            val interval = (5 * 60_000L * (1L shl same.coerceIn(0, 8))).coerceAtMost(DAY)
            if (end == null || end <= 0 || cursor.getLong(2) > checked || now() - checked >= interval) result += cursor.getLong(0) to checked
        }
        result.sortedWith(compareBy<Pair<Long, Long>> { it.second }.thenBy { it.first }).take(8).map { it.first }
    }

    fun observe(receiver: String, table: LocalHealthTable, capture: HealthCapture, page: LocalHealthPage): HealthCapture = transaction {
        val scope = capture.scopes.first(); var changed = scope.changed
        page.rows.forEach { row ->
            if (observeRow(receiver, table, capture, scope, row)) changed++
        }
        val remaining = scope.remaining?.let { budget -> (budget - page.rows.size).coerceAtLeast(0) }
        val stalled = !page.complete && (if (table == LocalHealthTable.SPORT)
            page.nextKey == scope.afterKey && page.nextId == scope.afterId
            else page.nextId == null || page.nextId == scope.afterId)
        val partial = capture.partial || page.partial || stalled
        val nextScope = scope.copy(afterId = page.nextId ?: scope.afterId, upperId = page.upperId ?: scope.upperId,
            afterKey = page.nextKey ?: scope.afterKey, upperKey = page.upperKey ?: scope.upperKey,
            remaining = remaining, partial = scope.partial || page.partial || stalled, changed = changed)
        val ended = page.complete || remaining == 0 || stalled
        if (ended) {
            if (scope.audit && !nextScope.partial) missing(receiver, table, nextScope, page.complete)
            if (scope.remaining != null && !nextScope.partial) it.execSQL("UPDATE progress SET audit_after=? WHERE receiver=? AND kind=?",
                arrayOf<Any?>(if (page.complete) 0L else nextScope.afterId ?: 0L, receiver, table.wireName))
            scope.parent?.takeUnless { nextScope.partial }?.let { parent ->
                val same = it.rawQuery("SELECT same_count FROM children WHERE receiver=? AND parent_id=?", arrayOf(receiver, parent.toString())).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                it.execSQL("INSERT OR REPLACE INTO children VALUES (?,?,?,?)", arrayOf<Any?>(receiver, parent, now(), if (changed > 0) 0 else same + 1))
            }
        }
        var next = capture.copy(scopes = if (ended) capture.scopes.drop(1) else listOf(nextScope) + capture.scopes.drop(1),
            partial = partial, scanned = capture.scanned + page.rows.size,
            providerVersion = page.providerVersion ?: capture.providerVersion)
        page.rows.firstOrNull { row -> !row.partial }?.let { row ->
            val fields = JsonArray(row.columns.keys.sorted().map(::JsonPrimitive)).toString()
            val previousFields = it.rawQuery("SELECT schema_fields FROM progress WHERE receiver=? AND kind=?", args(receiver, table)).use { c ->
                c.moveToFirst(); if (c.isNull(0)) null else c.getString(0)
            }
            if (previousFields != fields) it.execSQL("UPDATE progress SET schema_fields=? WHERE receiver=? AND kind=?", arrayOf(fields, receiver, table.wireName))
            if (previousFields != null && previousFields != fields && !capture.full) {
                // An observed schema change needs a fresh comparison of old rows too.
                next = next.copy(full = true, scopes = coverage(receiver, table, capture.window, capture.resend))
            }
        }
        if (next.scopes.isEmpty() && capture.full && !partial) {
            it.execSQL("UPDATE progress SET baseline=1 WHERE receiver=? AND kind=?", args(receiver, table))
            // Everything was read and resent, so nothing is left behind any more.
            if (capture.resend && capture.window == null) it.delete("metadata", "name=?", arrayOf(deferredKey(receiver, table)))
            if (table == LocalHealthTable.FITNESS_POINT) it.execSQL(
                "INSERT OR IGNORE INTO children SELECT receiver,source_id,?,0 FROM observations WHERE receiver=? AND kind=? AND source_id IS NOT NULL",
                arrayOf<Any?>(now(), receiver, LocalHealthTable.FITNESS_RECORD.wireName))
        }
        if (next.done && !next.partial) {
            next.stamp?.let { stamp -> setMetadata(stampKey(receiver, table), stamp) }
            if (next.journalFallback) setMetadata(fallbackKey(receiver, table), "complete")
        }
        save(receiver, table, next); next
    }

    private fun observeRow(receiver: String, table: LocalHealthTable, capture: HealthCapture, scope: HealthScanScope, row: LocalHealthRow): Boolean {
        val key = LocalHealthFingerprint.key(table, row.raw)
        val fingerprint = if (row.partial) null else LocalHealthFingerprint.of(row.raw)
        val previous = db.rawQuery("SELECT fingerprint,present FROM observations WHERE receiver=? AND kind=? AND row_key=?", args(receiver, table) + key).use { c ->
            if (c.moveToFirst()) (if (c.isNull(0)) null else c.getString(0)) to (c.getInt(1) != 0) else null
        }
        val different = previous == null || !previous.second || fingerprint == null || previous.first != fingerprint
        if (different || capture.resend) enqueue(receiver, table, key, row.raw)
        if (different && table == LocalHealthTable.FITNESS_RECORD) row.id?.let { parent ->
            db.execSQL("UPDATE children SET last_checked=0,same_count=0 WHERE receiver=? AND parent_id=?", arrayOf<Any?>(receiver, parent))
        }
        if (!different) {
            if (scope.audit) db.execSQL("UPDATE observations SET seen=? WHERE receiver=? AND kind=? AND row_key=?", arrayOf(scope.token, receiver, table.wireName, key))
        } else db.insertWithOnConflict("observations", null, ContentValues().apply {
            put("receiver", receiver); put("kind", table.wireName); put("row_key", key); put("source_id", row.id)
            put("parent_id", row.number("RECORD_ID")); put("fingerprint", fingerprint); put("present", 1)
            put("last_changed", now()); put("seen", scope.token)
            if (table == LocalHealthTable.SESSION || table == LocalHealthTable.FITNESS_RECORD || table == LocalHealthTable.SPORT)
                put("payload", row.raw.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        return different
    }

    private fun enqueue(receiver: String, table: LocalHealthTable, key: String, row: JsonObject) {
        db.insertWithOnConflict("pending", null, ContentValues().apply {
            put("receiver", receiver); put("kind", table.wireName); put("row_key", key); put("payload", row.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
    }

    private fun missing(receiver: String, table: LocalHealthTable, scope: HealthScanScope, reachedEnd: Boolean) {
        val parts = mutableListOf("receiver=?", "kind=?", "present=1", "(seen IS NULL OR seen<>?)")
        val values = mutableListOf(receiver, table.wireName, scope.token)
        if (scope.parent != null) { parts += "parent_id=?"; values += scope.parent.toString() }
        else if (table != LocalHealthTable.SESSION && table != LocalHealthTable.SPORT) {
            scope.startId?.let { parts += "source_id>?"; values += it.toString() }
            val high = if (reachedEnd) null else scope.afterId
            high?.let { parts += "source_id<=?"; values += it.toString() }
        }
        val absent = db.query("observations", arrayOf("row_key", "source_id", "payload"), parts.joinToString(" AND "), values.toTypedArray(), null, null, null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(Triple(cursor.getString(0), if (cursor.isNull(1)) null else cursor.getLong(1), if (cursor.isNull(2)) null else cursor.getString(2))) }
        }
        absent.forEach { (key, id, previous) -> absent(receiver, table, key, id, previous) }
    }

    private fun absent(receiver: String, table: LocalHealthTable, key: String, id: Long?, previous: String?) {
        val row = previous?.let { LocalHealthProtocol.parse(it.toByteArray()) } ?: buildJsonObject {
            id?.let { put("id", it.toString()) }; put("columns", buildJsonObject { id?.let { put("_id", it) } })
        }
        enqueue(receiver, table, key, JsonObject(row.toMutableMap().apply { put("sourceAbsent", JsonPrimitive(true)) }))
        db.execSQL("UPDATE observations SET present=0 WHERE receiver=? AND kind=? AND row_key=?", args(receiver, table) + key)
    }

    fun batch(receiver: String, table: LocalHealthTable, capture: HealthCapture): JsonObject = transaction {
        val existing = it.rawQuery("SELECT payload FROM batches WHERE receiver=? AND kind=?", args(receiver, table)).use { c ->
            if (c.moveToFirst()) LocalHealthProtocol.parse(c.getString(0).toByteArray()) else null
        }
        if (existing != null) return@transaction JsonObject(existing.toMutableMap().apply { if (!capture.done) put("complete", JsonPrimitive(false)) })
        val epoch = it.rawQuery("SELECT value FROM metadata WHERE name='epoch'", null).use { c -> c.moveToFirst(); c.getString(0) }
        val rows = mutableListOf<JsonObject>(); var bytes = 0
        it.rawQuery("SELECT revision,row_key,payload FROM pending WHERE receiver=? AND kind=? ORDER BY revision LIMIT ?", args(receiver, table) + (LocalHealthProtocol.PAGE_SIZE + 1).toString()).use { c ->
            while (c.moveToNext()) {
                if (rows.size >= LocalHealthProtocol.PAGE_SIZE) break
                val raw = LocalHealthProtocol.parse(c.getString(2).toByteArray())
                val row = JsonObject(raw.toMutableMap().apply {
                    put("bridgeRevision", JsonPrimitive(c.getLong(0).toString())); put("bridgeEpoch", JsonPrimitive(epoch)); put("bridgeKey", JsonPrimitive(c.getString(1)))
                })
                val size = row.toString().toByteArray(Charsets.UTF_8).size
                if (bytes + size > LocalHealthProtocol.MAX_FRAME_BYTES - 8192) {
                    if (rows.isEmpty()) return@transaction queryError("row_exceeds_budget")
                    break
                }
                rows += row; bytes += size
            }
        }
        val total = it.rawQuery("SELECT COUNT(*) FROM pending WHERE receiver=? AND kind=?", args(receiver, table)).use { c -> c.moveToFirst(); c.getLong(0) }
        it.execSQL("UPDATE progress SET step=step+1 WHERE receiver=? AND kind=?", args(receiver, table))
        val step = it.rawQuery("SELECT step FROM progress WHERE receiver=? AND kind=?", args(receiver, table)).use { c -> c.moveToFirst(); c.getLong(0) }
        val batchId = UUID.randomUUID().toString()
        val result = buildJsonObject {
            put("op", "changes"); put("version", LocalHealthProtocol.INCREMENTAL_VERSION); put("table", table.wireName)
            put("rows", JsonArray(rows)); put("batch", batchId); put("nextId", step.toString())
            put("complete", capture.done && total <= rows.size); put("partial", capture.partial); put("scannedRows", capture.scanned)
            deferredBefore(receiver, table)?.let { before -> put("deferredBefore", before) }
            // This batch's ACK may come with the table's next changes request.
            put("inlineAck", true)
            capture.providerVersion?.let { put("providerVersion", it) }
            capture.journal?.let { journal -> put("sourceJournal", buildJsonObject {
                put("version", 1); put("baseline", journal.baseline); put("finished", journal.finished)
                put("sequence", journal.position.sequence.toString()); put("upper", journal.upper.toString())
            }) }
        }
        it.execSQL("INSERT INTO batches VALUES (?,?,?,?)", arrayOf<Any?>(receiver, table.wireName, batchId, result.toString()))
        result
    }
    fun hasBatch(receiver: String, table: LocalHealthTable) = db.rawQuery("SELECT 1 FROM batches WHERE receiver=? AND kind=?", args(receiver, table)).use { it.moveToFirst() }
    fun hasPending(receiver: String, table: LocalHealthTable) = db.rawQuery("SELECT 1 FROM pending WHERE receiver=? AND kind=? LIMIT 1", args(receiver, table)).use { it.moveToFirst() }
    fun pendingCount(receiver: String, table: LocalHealthTable) =
        db.rawQuery("SELECT COUNT(*) FROM pending WHERE receiver=? AND kind=?", args(receiver, table)).use { it.moveToFirst(); it.getLong(0) }

    fun acknowledge(receiver: String, table: LocalHealthTable, batch: String): JsonObject = transaction {
        val stored = it.rawQuery("SELECT payload FROM batches WHERE receiver=? AND kind=? AND batch_id=?", args(receiver, table) + batch).use { c ->
            if (c.moveToFirst()) LocalHealthProtocol.parse(c.getString(0).toByteArray()) else null
        }
        if (stored != null) {
            (stored["rows"] as JsonArray).forEach { item ->
                val row = item as JsonObject
                it.delete("pending", "receiver=? AND kind=? AND row_key=? AND revision=?", args(receiver, table) + arrayOf(row.text("bridgeKey")!!, row.text("bridgeRevision")!!))
            }
            it.delete("batches", "receiver=? AND kind=? AND batch_id=?", args(receiver, table) + batch)
        }
        // Repeating an ACK after its reply was lost is safe; it cannot clear a different batch.
        buildJsonObject { put("op", "ack"); put("batch", batch); put("committed", true) }
    }
    override fun close() = helper.close()
    companion object {
        private const val DAY = 86_400_000L
        private const val DEEP_CHECK_MILLIS = 3_600_000L
        private const val JOURNAL_AUDIT_MILLIS = DAY
        /** The tables big enough for a first sync to leave older rows behind. */
        private val WINDOWED = setOf(LocalHealthTable.POINT, LocalHealthTable.FITNESS_POINT)
    }
}
