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
    private val now: () -> Long = System::currentTimeMillis,
    /** Most rows per source read and per batch; tests lower it to cross page boundaries with few rows. */
    val pageRows: Int = LocalHealthProtocol.MAX_PAGE_ROWS) : AutoCloseable {
    // Version 1 is the baseline shipped in 0.2.0. Any format change must raise it and add a migration.
    // Version 2 (1.0.9) lets a table have several batches waiting for their ACKs, each with its bound and order.
    private val helper = object : SQLiteOpenHelper(context, File(context.noBackupFilesDir, name).path, null, 2) {
        // The answering thread reads batches while the reading thread saves a page; WAL lets those reads go on meanwhile.
        init { setWriteAheadLoggingEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE metadata (name TEXT PRIMARY KEY,value TEXT NOT NULL)")
            db.execSQL("INSERT INTO metadata VALUES ('epoch','${UUID.randomUUID()}')")
            db.execSQL("CREATE TABLE observations (receiver TEXT NOT NULL,kind TEXT NOT NULL,row_key TEXT NOT NULL,source_id INTEGER,parent_id INTEGER,fingerprint TEXT,present INTEGER NOT NULL,last_changed INTEGER NOT NULL,seen TEXT,payload TEXT,PRIMARY KEY(receiver,kind,row_key))")
            db.execSQL("CREATE INDEX observed_range ON observations(receiver,kind,source_id)")
            db.execSQL("CREATE INDEX observed_parent ON observations(receiver,kind,parent_id)")
            db.execSQL("CREATE TABLE pending (revision INTEGER PRIMARY KEY AUTOINCREMENT,receiver TEXT NOT NULL,kind TEXT NOT NULL,row_key TEXT NOT NULL,payload TEXT NOT NULL,UNIQUE(receiver,kind,row_key))")
            db.execSQL(BATCHES)
            db.execSQL("CREATE TABLE progress (receiver TEXT NOT NULL,kind TEXT NOT NULL,baseline INTEGER NOT NULL DEFAULT 0,audit_after INTEGER NOT NULL DEFAULT 0,step INTEGER NOT NULL DEFAULT 0,capture TEXT,schema_fields TEXT,PRIMARY KEY(receiver,kind))")
            db.execSQL("CREATE TABLE children (receiver TEXT NOT NULL,parent_id INTEGER NOT NULL,last_checked INTEGER NOT NULL,same_count INTEGER NOT NULL,PRIMARY KEY(receiver,parent_id))")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion != 1 || newVersion != 2) error("No migration from health bridge schema $oldVersion to $newVersion")
            db.execSQL("ALTER TABLE batches RENAME TO batches_v1")
            db.execSQL(BATCHES)
            val bounds = HashMap<String, String>()
            db.rawQuery("SELECT name,value FROM metadata WHERE name LIKE 'batch_bound:%'", null).use { c -> while (c.moveToNext()) bounds[c.getString(0)] = c.getString(1) }
            db.rawQuery("SELECT receiver,kind,batch_id,payload FROM batches_v1", null).use { c ->
                while (c.moveToNext()) {
                    val (receiver, kind, id, payload) = List(4) { c.getString(it) }
                    // A batch whose bound was kept deletes by it; an older one still names its rows in its payload.
                    val bound = bounds["batch_bound:$receiver:$kind"]?.split(' ')?.takeIf { it.size == 2 && it[0] == id }?.get(1)?.toLongOrNull()
                    val step = runCatching { LocalHealthProtocol.parse(payload.toByteArray()).number("nextId") }.getOrNull() ?: 0L
                    db.execSQL("INSERT INTO batches VALUES (?,?,?,?,?,?)", arrayOf<Any?>(receiver, kind, id, payload, bound, step))
                }
            }
            db.execSQL("DROP TABLE batches_v1")
            db.execSQL("DELETE FROM metadata WHERE name LIKE 'batch_bound:%'")
        }
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
    private fun gateKey(source: String) = "journal_gate:$source"

    fun journalCursor(receiver: String, table: LocalHealthTable): HealthJournalPosition? = metadata(journalKey(receiver, table))?.let { json ->
        runCatching { HealthJournalPosition.parse(LocalHealthProtocol.parse(json.toByteArray())) }.getOrNull()
    }

    /** The window last read for this source, while its files still carry the stamp taken before that read. */
    fun journalGate(source: String, stamp: String): HealthJournalWindow? = metadata(gateKey(source))?.let { json ->
        runCatching {
            val gate = LocalHealthProtocol.parse(json.toByteArray())
            if (gate.text("stamp") == stamp) HealthJournalWindow.parse(gate.getValue("window").jsonObject) else null
        }.getOrNull()
    }

    fun setJournalGate(source: String, stamp: String, window: HealthJournalWindow?) {
        transaction { database ->
            if (window == null) database.delete("metadata", "name=?", arrayOf(gateKey(source)))
            else setMetadata(gateKey(source), buildJsonObject { put("stamp", stamp); put("window", window.json()) }.toString())
        }
    }
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
        database.delete("metadata", "name=?", arrayOf(gateKey(source)))
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
        observeRows(receiver, table, capture, HealthScanScope(), prepare(table, rows))
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

    /** Keys and fingerprints of a page's rows, worked out before its transaction; hashing is most of the work per row. */
    fun prepare(table: LocalHealthTable, rows: List<LocalHealthRow>): List<PreparedRow> = rows.map { row ->
        PreparedRow(row, LocalHealthFingerprint.key(table, row.raw), if (row.partial) null else LocalHealthFingerprint.of(row.raw))
    }

    fun observe(receiver: String, table: LocalHealthTable, capture: HealthCapture, page: LocalHealthPage,
        prepared: List<PreparedRow> = prepare(table, page.rows)): HealthCapture {
        // Only the reading side writes the ledger, under the reader's scan lock, so it's read before the transaction:
        // the answering thread's ACKs and batches wait for this transaction to commit, so the less it holds, the better.
        val previous = previous(receiver, table, prepared)
        return transaction {
            val scope = capture.scopes.first()
            val changed = scope.changed + observeRows(receiver, table, capture, scope, prepared, previous)
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
                    val same = it.rawQuery("SELECT same_count FROM children WHERE receiver=? AND parent_id=?", arrayOf(receiver, parent.toString()))
                        .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                    it.execSQL("INSERT OR REPLACE INTO children VALUES (?,?,?,?)", arrayOf<Any?>(receiver, parent, now(), if (changed > 0) 0 else same + 1))
                }
            }
            var next = capture.copy(scopes = if (ended) capture.scopes.drop(1) else listOf(nextScope) + capture.scopes.drop(1),
                partial = partial, scanned = capture.scanned + page.rows.size,
                providerVersion = page.providerVersion ?: capture.providerVersion)
            // A row without its oversized cells doesn't show the table's columns either.
            page.rows.firstOrNull { row -> !row.partial && !row.oversized }?.let { row ->
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
    }

    /**
     * Compares a page with what was observed before and queues what the phone must receive; returns how many rows
     * changed. The page's earlier observations come from one query, and its writes use statements compiled once.
     */
    /** What the ledger holds for these rows' keys: fingerprint, and whether the row was there when last read. */
    private fun previous(receiver: String, table: LocalHealthTable, rows: List<PreparedRow>): HashMap<String, Pair<String?, Boolean>> {
        val previous = HashMap<String, Pair<String?, Boolean>>(rows.size * 2)
        rows.map { it.key }.distinct().chunked(KEYS_PER_STATEMENT).forEach { keys ->
            db.rawQuery("SELECT row_key,fingerprint,present FROM observations WHERE receiver=? AND kind=? AND row_key IN (${keys.joinToString(",") { "?" }})",
                args(receiver, table) + keys).use { c ->
                while (c.moveToNext()) previous[c.getString(0)] = (if (c.isNull(1)) null else c.getString(1)) to (c.getInt(2) != 0)
            }
        }
        return previous
    }

    private fun observeRows(receiver: String, table: LocalHealthTable, capture: HealthCapture, scope: HealthScanScope,
        rows: List<PreparedRow>, previous: HashMap<String, Pair<String?, Boolean>> = previous(receiver, table, rows)): Int {
        if (rows.isEmpty()) return 0
        val keepsPayload = table == LocalHealthTable.SESSION || table == LocalHealthTable.FITNESS_RECORD || table == LocalHealthTable.SPORT
        val seen = mutableListOf<String>(); var changed = 0
        db.compileStatement("INSERT OR REPLACE INTO pending(receiver,kind,row_key,payload) VALUES (?,?,?,?)").use { queue ->
            db.compileStatement("INSERT OR REPLACE INTO observations(receiver,kind,row_key,source_id,parent_id,fingerprint,present,last_changed,seen,payload) " +
                "VALUES (?,?,?,?,?,?,1,?,?,?)").use { observed ->
                rows.forEach { prepared ->
                    val (row, key, fingerprint) = prepared
                    val before = previous[key]
                    val different = before == null || !before.second || fingerprint == null || before.first != fingerprint
                    if (different || capture.resend) {
                        queue.bindString(1, receiver); queue.bindString(2, table.wireName); queue.bindString(3, key); queue.bindString(4, prepared.payload)
                        check(queue.executeInsert() != -1L)
                    }
                    if (!different) { if (scope.audit) seen += key; return@forEach }
                    if (table == LocalHealthTable.FITNESS_RECORD) row.id?.let { parent ->
                        db.execSQL("UPDATE children SET last_checked=0,same_count=0 WHERE receiver=? AND parent_id=?", arrayOf<Any?>(receiver, parent))
                    }
                    observed.clearBindings()
                    observed.bindString(1, receiver); observed.bindString(2, table.wireName); observed.bindString(3, key)
                    row.id?.let { observed.bindLong(4, it) }; row.number("RECORD_ID")?.let { observed.bindLong(5, it) }
                    fingerprint?.let { observed.bindString(6, it) }
                    // A session a full capture sees for the first time hasn't changed. Counting it would stretch the recent POINT
                    // range back to the oldest such session on every sync for a day, although this first sync already read those
                    // points or left them behind on purpose under its window.
                    observed.bindLong(7, if (before == null && capture.full && table == LocalHealthTable.SESSION) 0L else now())
                    observed.bindString(8, scope.token)
                    if (keepsPayload) observed.bindString(9, prepared.payload)
                    check(observed.executeInsert() != -1L)
                    // A key twice on one page compares its second row with the first, as row-by-row writes would.
                    previous[key] = fingerprint to true
                    changed++
                }
            }
        }
        seen.distinct().chunked(KEYS_PER_STATEMENT).forEach { keys ->
            db.execSQL("UPDATE observations SET seen=? WHERE receiver=? AND kind=? AND row_key IN (${keys.joinToString(",") { "?" }})",
                arrayOf<Any?>(scope.token, receiver, table.wireName, *keys.toTypedArray()))
        }
        return changed
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

    /*
     * Batches, and the queued rows they cover, change only on the thread that answers the phone. Reading ahead adds queued
     * rows and moves the capture from another thread, each source page in one transaction, so what is read here outside a
     * transaction is never older than the batches it is compared with.
     */

    /** A batch made and not yet acknowledged; [bound] is null for one made before 1.0.6 kept bounds. */
    private class Outstanding(val id: String, val bound: Long?, val step: Long)
    private fun outstanding(receiver: String, table: LocalHealthTable) =
        db.rawQuery("SELECT batch_id,bound,step FROM batches WHERE receiver=? AND kind=? ORDER BY step", args(receiver, table)).use { c ->
            buildList { while (c.moveToNext()) add(Outstanding(c.getString(0), if (c.isNull(1)) null else c.getLong(1), c.getLong(2))) }
        }

    /**
     * The made batch a request gets: with [after], the one made after that batch; without, or when this side no longer has
     * it (acknowledged since), the oldest. Null when a new one is to be made. Batches go out in the order they were made,
     * so a row is never sent older than it was sent before.
     */
    private fun pick(made: List<Outstanding>, after: String?): Outstanding? {
        val index = after?.let { id -> made.indexOfFirst { it.id == id } } ?: -1
        if (index < 0) return made.firstOrNull()
        // An old batch names its rows only in its payload, so nothing is made past it until its ACK.
        return made.getOrNull(index + 1) ?: made.firstOrNull { it.bound == null }
    }

    private fun beyond(made: List<Outstanding>) = made.mapNotNull { it.bound }.maxOrNull() ?: -1L

    /** Queued rows no made batch covers yet, counted up to [limit]. */
    private fun pendingBeyond(receiver: String, table: LocalHealthTable, made: List<Outstanding>, limit: Int): Int =
        db.rawQuery("SELECT COUNT(*) FROM (SELECT 1 FROM pending WHERE receiver=? AND kind=? AND revision>? LIMIT ?)",
            args(receiver, table) + beyond(made).toString() + limit.toString()).use { it.moveToFirst(); it.getInt(0) }

    /** Queued rows no made batch covers yet, counted up to [limit]; one page is enough to make the next batch from. */
    fun pendingBeyond(receiver: String, table: LocalHealthTable, limit: Int = pageRows): Int =
        pendingBeyond(receiver, table, outstanding(receiver, table), limit)

    /** Whether a request for the batch after [after] needs a new batch and no queued row is there to make it from. */
    fun needsRows(receiver: String, table: LocalHealthTable, after: String? = null): Boolean {
        val made = outstanding(receiver, table)
        return pick(made, after) == null && pendingBeyond(receiver, table, made, 1) == 0
    }

    /** The table's capture as last saved; null before its first. */
    fun capture(receiver: String, table: LocalHealthTable): HealthCapture? =
        db.rawQuery("SELECT capture FROM progress WHERE receiver=? AND kind=?", args(receiver, table)).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) HealthCapture.parse(LocalHealthProtocol.parse(c.getString(0).toByteArray())) else null
        }

    /** The batch for a request that has received [after]: one already made, or a new one from the rows queued past them. */
    fun batch(receiver: String, table: LocalHealthTable, after: String? = null): JsonObject {
        val made = outstanding(receiver, table)
        val chosen = pick(made, after) ?: return make(receiver, table, made)
        // Read first: once reading is done, no row is queued until the next run begins.
        val capture = capture(receiver, table)
        val existing = db.rawQuery("SELECT payload FROM batches WHERE receiver=? AND kind=? AND batch_id=?", args(receiver, table) + chosen.id).use { c ->
            c.moveToFirst(); LocalHealthProtocol.storedPage(c.getString(0))
        }
        // A last batch sent again in a later run that hasn't finished reading isn't the end any more.
        if (capture?.done != true) return if (existing.flag("complete") == false) existing else JsonObject(existing + ("complete" to JsonPrimitive(false)))
        // One made ahead while reading went on becomes the end once reading has finished and nothing is queued past it,
        // and then says how reading ended.
        val last = chosen === made.last() && chosen.bound != null && db.rawQuery("SELECT 1 FROM pending WHERE receiver=? AND kind=? AND revision>? LIMIT 1",
            args(receiver, table) + chosen.bound.toString()).use { !it.moveToFirst() }
        return if (!last || existing.flag("complete") == true) existing
            else JsonObject(existing - setOf("deferredBefore", "providerVersion", "sourceJournal") + ending(receiver, table, capture, complete = true))
    }

    /** What a batch says about the read it came from. */
    private fun ending(receiver: String, table: LocalHealthTable, capture: HealthCapture, complete: Boolean) = buildJsonObject {
        put("complete", complete); put("partial", capture.partial); put("scannedRows", capture.scanned)
        deferredBefore(receiver, table)?.let { before -> put("deferredBefore", before) }
        capture.providerVersion?.let { put("providerVersion", it) }
        capture.journal?.let { journal -> put("sourceJournal", buildJsonObject {
            put("version", 1); put("baseline", journal.baseline); put("finished", journal.finished)
            put("sequence", journal.position.sequence.toString()); put("upper", journal.upper.toString())
        }) }
    }

    /**
     * Makes the next batch ahead of its request, while the one before it travels, once a full page is queued or reading is
     * done; false when there is nothing to make or enough are waiting already.
     */
    fun packAhead(receiver: String, table: LocalHealthTable): Boolean {
        val made = outstanding(receiver, table)
        if (made.size >= MAX_OUTSTANDING || made.any { it.bound == null }) return false
        val done = capture(receiver, table)?.done == true
        val queued = pendingBeyond(receiver, table, made, pageRows)
        if (queued < pageRows && !(done && queued > 0)) return false
        return make(receiver, table, made).text("error") == null
    }

    /** Only the rows' bound and the batch itself are written, so a page being saved by the reading thread rarely waits. */
    private fun make(receiver: String, table: LocalHealthTable, made: List<Outstanding>): JsonObject {
        val database = db
        // Read before the rows: a capture that says reading is done queues no more.
        val capture = capture(receiver, table) ?: HealthCapture("", false, emptyList())
        val after = beyond(made)
        val epoch = JsonPrimitive(metadata("epoch")!!).toString()
        val rows = mutableListOf<JsonElement>(); var bytes = 0; var last = maxOf(after, 0L)
        database.rawQuery("SELECT revision,row_key,payload FROM pending WHERE receiver=? AND kind=? AND revision>? ORDER BY revision LIMIT ?",
            args(receiver, table) + after.toString() + (pageRows + 1).toString()).use { c ->
            while (c.moveToNext()) {
                if (rows.size >= pageRows) break
                // The stored object text with the delivery fields appended, as adding them to the parsed row would print it.
                val payload = c.getString(2)
                val fields = "\"bridgeRevision\":\"${c.getLong(0)}\",\"bridgeEpoch\":$epoch,\"bridgeKey\":${JsonPrimitive(c.getString(1))}}"
                fun delivered(text: String) = if (text == "{}") "{$fields" else text.substring(0, text.length - 1) + "," + fields
                var row = delivered(payload)
                var size = utf8Length(row)
                if (rows.isEmpty() && size > BATCH_BUDGET) {
                    // Queued before 1.0.7 bounded rows at the source: it goes without its largest cells rather than never.
                    row = delivered(boundHealthRow(LocalHealthProtocol.parse(payload.toByteArray()), BATCH_BUDGET - utf8Length(fields)).toString())
                    size = utf8Length(row)
                }
                if (bytes + size > BATCH_BUDGET) {
                    if (rows.isEmpty()) return queryError("row_exceeds_budget")
                    break
                }
                rows += LocalHealthProtocol.storedRow(row); bytes += size; last = c.getLong(0)
            }
        }
        val more = database.rawQuery("SELECT 1 FROM pending WHERE receiver=? AND kind=? AND revision>? LIMIT 1",
            args(receiver, table) + last.toString()).use { it.moveToFirst() }
        val step = database.rawQuery("SELECT step FROM progress WHERE receiver=? AND kind=?", args(receiver, table)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        } + 1
        val batchId = UUID.randomUUID().toString()
        val result = buildJsonObject {
            put("op", "changes"); put("version", LocalHealthProtocol.INCREMENTAL_VERSION); put("table", table.wireName)
            put("rows", JsonArray(rows)); put("batch", batchId); put("nextId", step.toString())
            ending(receiver, table, capture, complete = capture.done && !more).forEach { (name, value) -> put(name, value) }
            // This batch's ACK may come with the table's next changes request.
            put("inlineAck", true)
        }
        val payload = result.toString()
        // A batch is the table's queued rows after the batch before it, up to its last revision, and later rows get higher
        // ones; its ACK deletes by that.
        transaction {
            it.execSQL("UPDATE progress SET step=? WHERE receiver=? AND kind=?", arrayOf<Any?>(step, receiver, table.wireName))
            it.execSQL("INSERT INTO batches VALUES (?,?,?,?,?,?)", arrayOf<Any?>(receiver, table.wireName, batchId, payload, last, step))
        }
        return result
    }
    fun pendingCount(receiver: String, table: LocalHealthTable) =
        db.rawQuery("SELECT COUNT(*) FROM pending WHERE receiver=? AND kind=?", args(receiver, table)).use { it.moveToFirst(); it.getLong(0) }

    /** Also clears every batch made before it: the phone commits in order, and each batch's rows follow the one before. */
    fun acknowledge(receiver: String, table: LocalHealthTable, batch: String): JsonObject = transaction {
        val acknowledged = outstanding(receiver, table).firstOrNull { made -> made.id == batch }
        if (acknowledged?.bound != null) {
            it.execSQL("DELETE FROM pending WHERE receiver=? AND kind=? AND revision<=?", arrayOf<Any?>(receiver, table.wireName, acknowledged.bound))
            it.execSQL("DELETE FROM batches WHERE receiver=? AND kind=? AND step<=?", arrayOf<Any?>(receiver, table.wireName, acknowledged.step))
        } else if (acknowledged != null) {
            // A batch made before its bound was kept names its rows only in its payload.
            val stored = it.rawQuery("SELECT payload FROM batches WHERE receiver=? AND kind=? AND batch_id=?", args(receiver, table) + batch).use { c ->
                c.moveToFirst(); LocalHealthProtocol.parse(c.getString(0).toByteArray())
            }
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
        /** Keys per IN list, well under SQLite's 999 bound parameters. */
        private const val KEYS_PER_STATEMENT = 500
        /** Room for one batch's rows, leaving the rest of the frame to its metadata. */
        private const val BATCH_BUDGET = LocalHealthProtocol.MAX_FRAME_BYTES - 8192
        /** One being committed on the phone, one on its way, and one made ahead of its request. */
        const val MAX_OUTSTANDING = 3
        private const val BATCHES = "CREATE TABLE batches (receiver TEXT NOT NULL,kind TEXT NOT NULL,batch_id TEXT NOT NULL,payload TEXT NOT NULL," +
            "bound INTEGER,step INTEGER NOT NULL,PRIMARY KEY(receiver,kind,batch_id))"

    }
}

/** A source row with what the ledger compares it by; its delivery text is made only when it is queued or kept. */
internal data class PreparedRow(val row: LocalHealthRow, val key: String, val fingerprint: String?) {
    val payload: String by lazy(LazyThreadSafetyMode.NONE) { row.raw.toString() }
}
