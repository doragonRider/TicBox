@file:Suppress("DEPRECATION") // Explicit native SQLite fixtures on both supported Android test runtimes.

package dev.ticbox.companion.healthbridge

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import dev.ticbox.companion.protocol.*
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.test.*

/** Source SQLite commits -> durable bridge outbox/cursor -> exact batch ACK, without root or real health values. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class JournalHealthPipelineTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var database: SQLiteDatabase
    private lateinit var reader: IncrementalHealthReader
    private lateinit var ledger: HealthIncrementalStore
    private var clock = 1_800_000_000_000L
    private var generation = "file-generation-1"
    private var fullReads = 0
    private var fullAuditReads = 0
    private var journalReads = 0
    private var journalAvailable = true
    private var pageFailure = false
    private var readPartial = false
    private var onFullPage: (() -> Unit)? = null
    private val table = LocalHealthTable.POINT
    private val signal get() = CancellationSignal()

    @Before fun setup() {
        database = SQLiteDatabase.openOrCreateDatabase(File(context.cacheDir, "journal-source.db"), null)
        database.enableWriteAheadLogging()
        database.execSQL("CREATE TABLE data_point (_id INTEGER PRIMARY KEY AUTOINCREMENT, `values` TEXT, type INTEGER, time_to INTEGER)")
        database.execSQL("CREATE TABLE data_session (id TEXT PRIMARY KEY, time_to INTEGER)")
        database.execSQL("CREATE TABLE data_source (name TEXT PRIMARY KEY)")
        AtomicHealthJournal.install(database, "health")
        reopen()
    }

    private val source = object : HealthJournalReader {
        override fun window(table: LocalHealthTable, cancellation: CancellationSignal) =
            if (journalAvailable) HealthJournalWindow.parse(JsonObject(AtomicHealthJournal.status(database, "health") + ("generation" to JsonPrimitive(generation)))) else null
        override fun page(table: LocalHealthTable, after: HealthJournalPosition, upper: Long, cancellation: CancellationSignal): JsonObject {
            journalReads++
            if (pageFailure) throw IOException("fixture")
            val result = if (after.generation != generation) JsonObject(AtomicHealthJournal.status(database, "health") + ("reset" to JsonPrimitive(true)))
            else HealthJournalMain.capture(database, "health", table, buildJsonObject {
                put("epoch", after.epoch); put("after", after.sequence.toString()); put("upper", upper.toString())
            })
            return JsonObject(result + ("generation" to JsonPrimitive(generation)))
        }
    }

    private fun reopen() {
        ledger = HealthIncrementalStore(context, "journal-ledger.db", now = { clock })
        reader = IncrementalHealthReader(ledger, { request, _ ->
            fullReads++
            if (request.flag("audit") == true && request.number("remaining") == null) fullAuditReads++
            val result = fitnessLikePointPage(request)
            onFullPage?.invoke()
            if (readPartial) JsonObject(result + ("partial" to JsonPrimitive(true))) else result
        }, listOf(table), journal = source)
    }

    private fun fitnessLikePointPage(request: JsonObject): JsonObject {
        val upper = request.number("upperId") ?: database.rawQuery("SELECT MAX(_id) FROM data_point", null).use {
            it.moveToFirst(); if (it.isNull(0)) null else it.getLong(0)
        }
        if (upper == null) return localHealthPage(emptyList(), null, null, true, "fixture")
        val (selection, args) = sourceSelection(table, JsonObject(request + ("upperId" to JsonPrimitive(upper))))
        val rows = mutableListOf<JsonObject>(); var next = request.number("afterId"); var complete = true
        database.rawQuery("SELECT * FROM data_point WHERE $selection ORDER BY _id LIMIT 129", args).use {
            while (it.moveToNext()) {
                if (rows.size == 128) { complete = false; break }
                next = it.getLong(0); rows += it.healthDatabaseRow(next.toString(), table)
            }
        }
        return localHealthPage(rows, next, upper, complete, "fixture")
    }

    private fun insert(id: Long, value: String = "72") = database.execSQL(
        "INSERT INTO data_point(_id,`values`,type,time_to) VALUES (?,?,1,0)", arrayOf<Any>(id, value))

    @Test fun helperWriteOpenPreservesWalAndLeavesAnExistingWriterUsable() {
        val file = File(database.path)
        openJournalDatabase(file, write = true).use { helper ->
            assertEquals("wal", helper.rawQuery("PRAGMA journal_mode", null).use { it.moveToFirst(); it.getString(0) })
            AtomicHealthJournal.remove(helper, "health")
            AtomicHealthJournal.install(helper, "health")
        }
        assertEquals("wal", database.rawQuery("PRAGMA journal_mode", null).use { it.moveToFirst(); it.getString(0) })
        insert(1)
        assertEquals(1, cycle().size)
    }

    private fun changes(run: String, receiver: String = "receiver", full: Boolean = false): JsonObject =
        reader.handle(LocalHealthProtocol.changes(table, receiver, run, full), signal).also { assertNull(it.text("error"), it.toString()) }
    private fun ack(batch: JsonObject, receiver: String = "receiver") =
        reader.handle(LocalHealthProtocol.acknowledge(receiver, table, batch.text("batch")!!), signal)
    private fun cycle(receiver: String = "receiver", full: Boolean = false): List<JsonObject> {
        val run = UUID.randomUUID().toString(); val rows = mutableListOf<JsonObject>()
        repeat(100) {
            val result = changes(run, receiver, full)
            rows += (result["rows"] as JsonArray).map { it as JsonObject }
            ack(result, receiver)
            if (result.flag("complete") == true) return rows
        }
        fail("journal acquisition did not finish")
    }

    @After fun cleanup() { reader.close(); database.close() }

    @Test fun exportProductionTriggersForOwnedProcessCrashChecks() {
        val path = System.getProperty("ticbox.journal.fixture") ?: return
        val target = File(path); target.parentFile!!.mkdirs(); target.delete()
        // Only this synthetic empty fixture is exported, never an OEM/private database.
        database.execSQL("VACUUM INTO '${target.path.replace("'", "''")}'")
    }

    @Test fun explicitControlRequiresExistingRootConsentAndDisablePersistsDespiteRevocation() {
        val control = RootHealthJournalReader(context)
        val before = AtomicHealthJournal.status(database, "health")
        val enable = control.control(buildJsonObject { put("source", "health"); put("action", "enable") }, signal)
        assertEquals("journal_root_not_enabled", enable.text("error")); assertFalse(enable.flag("enabledForSync")!!)
        BridgeSettings.setJournalEnabled(context, "health", true)
        val disable = control.control(buildJsonObject { put("source", "health"); put("action", "disable") }, signal)
        assertEquals("journal_root_not_enabled", disable.text("error")); assertFalse(disable.flag("enabledForSync")!!)
        assertNull(disable.flag("installed")) // Revocation cannot prove removal.
        assertEquals(before, AtomicHealthJournal.status(database, "health"))
    }

    @Test fun unchangedSourceSkipsRowsAndOldIdsUseJournalKeysAcrossRestart() {
        repeat(200) { insert(it + 1L) }
        assertEquals(200, cycle().size)
        fullReads = 0; journalReads = 0
        assertTrue(cycle().isEmpty()); assertEquals(0, fullReads); assertEquals(0, journalReads)
        database.execSQL("UPDATE data_point SET `values`='81' WHERE _id=1")
        reader.close(); reopen()
        val delta = cycle()
        assertEquals(listOf("1"), delta.map { it.text("bridgeKey") }); assertEquals("81", (delta.single()["columns"] as JsonObject).text("values"))
        assertEquals(0, fullReads); assertEquals(1, journalReads)
    }

    @Test fun rollbackDoesNotCreateAChangeAndIgnoredCandidateDoesNotCreateAbsence() {
        insert(1); cycle(); fullReads = 0
        database.beginTransaction()
        try { database.execSQL("UPDATE data_point SET `values`='90' WHERE _id=1") } finally { database.endTransaction() }
        assertTrue(cycle().isEmpty()); assertEquals(0, fullReads)
        database.execSQL("INSERT OR IGNORE INTO data_point VALUES(1,'ignored',1,0)")
        val result = cycle()
        assertTrue(result.none { it.flag("sourceAbsent") == true })
        assertEquals("72", database.rawQuery("SELECT `values` FROM data_point WHERE _id=1", null).use { it.moveToFirst(); it.getString(0) })
    }

    @Test fun physicalAbsenceAndReusedIdAreVerifiedBySourceReadback() {
        insert(1); cycle()
        database.execSQL("DELETE FROM data_point WHERE _id=1")
        val absent = cycle().single()
        assertEquals("1", absent.text("bridgeKey")); assertTrue(absent.flag("sourceAbsent") == true)
        insert(1, "88")
        val present = cycle().single()
        assertEquals("1", present.text("bridgeKey")); assertNull(present.flag("sourceAbsent"))
        assertEquals("88", (present["columns"] as JsonObject).text("values"))
    }

    @Test fun directSourceRowsPreserveSessionKeysUnknownColumnsAndExactStorageTypes() {
        database.execSQL("ALTER TABLE data_point ADD COLUMN extra BLOB")
        database.execSQL("ALTER TABLE data_point ADD COLUMN exact INTEGER")
        insert(1)
        database.execSQL("UPDATE data_point SET extra=x'00ff',exact=9007199254740993 WHERE _id=1")
        val pointHead = AtomicHealthJournal.status(database, "health")
        fun capture(table: LocalHealthTable, head: JsonObject) = HealthJournalMain.capture(database, "health", table, buildJsonObject {
            put("epoch", head.text("epoch")); put("after", "0"); put("upper", head.text("head"))
        })
        val point = ((capture(table, pointHead)["rows"] as JsonArray).single() as JsonObject)
        assertEquals("9007199254740993", (point["columns"] as JsonObject).text("exact"))
        assertEquals("AP8=", (point["columns"] as JsonObject).text("extra"))
        assertEquals("blob-base64", (point["storageTypes"] as JsonObject).text("extra"))
        for ((name, type) in mapOf("device_id" to "TEXT", "wwid" to "TEXT", "type" to "INTEGER", "time_from" to "INTEGER", "synced" to "INTEGER", "deleted" to "INTEGER"))
            database.execSQL("ALTER TABLE data_session ADD COLUMN $name $type")
        database.execSQL("INSERT INTO data_session(id,time_to) VALUES('session-A',100)")
        database.execSQL("UPDATE data_session SET id='session-B' WHERE id='session-A'")
        val result = capture(LocalHealthTable.SESSION, AtomicHealthJournal.status(database, "health"))
        val session = (result["rows"] as JsonArray).single() as JsonObject
        assertEquals("session-B", (session["columns"] as JsonObject).text("id"))
        assertEquals("session-B", session.text("id")); assertNull(session.flag("partial"))
        assertTrue((result["missingKeys"] as JsonArray).any { (it as JsonPrimitive).content == "session-A" })
    }

    @Test fun sourceWriteAfterBaselinePageRemainsAfterCapturedHead() {
        repeat(140) { insert(it + 1L) }
        var changed = false
        onFullPage = {
            if (!changed) { changed = true; database.execSQL("UPDATE data_point SET `values`='89' WHERE _id=1") }
        }
        assertEquals(140, cycle().size); onFullPage = null
        fullReads = 0
        val next = cycle().single()
        assertEquals("1", next.text("bridgeKey")); assertEquals("89", (next["columns"] as JsonObject).text("values"))
        assertEquals(0, fullReads)
    }

    @Test fun retainedBatchSurvivesLostAckEvenAfterSourceCursorWasCommitted() {
        insert(1); cycle()
        database.execSQL("UPDATE data_point SET `values`='92' WHERE _id=1")
        val batch = changes("lost-ack")
        reader.close(); reopen()
        val replay = changes("reconnect")
        assertEquals(batch.text("batch"), replay.text("batch")); assertEquals(batch["rows"], replay["rows"])
        ack(replay)
        assertTrue(cycle().isEmpty())
    }

    @Test fun bridgeCommitFailureDoesNotAdvanceTheSourceCheckpoint() {
        insert(1); cycle()
        database.execSQL("UPDATE data_point SET `values`='93' WHERE _id=1")
        val bridgePath = File(context.noBackupFilesDir, "journal-ledger.db").path
        SQLiteDatabase.openDatabase(bridgePath, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("CREATE TRIGGER fixture_fail BEFORE INSERT ON pending BEGIN SELECT RAISE(ABORT,'fixture'); END")
        }
        val failed = reader.handle(LocalHealthProtocol.changes(table, "receiver", "failed", false), signal)
        assertEquals("query_failed", failed.text("error"))
        SQLiteDatabase.openDatabase(bridgePath, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("DROP TRIGGER fixture_fail") }
        reader.close(); reopen()
        assertEquals("93", (cycle().single()["columns"] as JsonObject).text("values"))
    }

    @Test fun generationGapLossOfRootAndDailyAuditUseIndependentBaseline() {
        insert(1); cycle(); fullReads = 0
        generation = "restored-file-2"
        assertTrue(cycle().isEmpty()); assertTrue(fullReads > 0)
        fullReads = 0; clock += 86_400_000
        assertTrue(cycle().isEmpty()); assertTrue(fullReads > 0)
        fullReads = 0; journalAvailable = false
        assertTrue(cycle().isEmpty()); assertTrue(fullReads > 0)
        journalAvailable = true
        database.execSQL("UPDATE data_point SET `values`='94' WHERE _id=1"); pageFailure = true
        fullReads = 0
        assertEquals(1, cycle().size); assertTrue(fullReads > 0)
        pageFailure = false
        assertTrue(cycle().isEmpty()) // The fallback did not consume the source log on its own.
    }

    @Test fun unavailableJournalCompletesOnlyOneFallbackAuditAcrossRunsAndRestarts() {
        repeat(300) { insert(it + 1L) }; cycle()
        journalAvailable = false; fullAuditReads = 0
        assertTrue(cycle().isEmpty())
        assertTrue(fullAuditReads > 0)
        reader.close(); reopen()
        repeat(3) {
            fullAuditReads = 0
            assertTrue(cycle().isEmpty())
            assertEquals(0, fullAuditReads)
        }
        journalAvailable = true
        assertTrue(cycle().isEmpty())
        journalAvailable = false; fullAuditReads = 0
        database.execSQL("UPDATE data_point SET type=2 WHERE _id=1")
        assertEquals(listOf("1"), cycle().map { it.text("bridgeKey") })
        assertTrue(fullAuditReads > 0) // A new loss after recovery needs a new independent audit.
    }

    @Test fun repeatedJournalPageFailuresDoNotRepeatFullAuditsOrAdvanceTheCursor() {
        repeat(200) { insert(it + 1L) }; cycle()
        database.execSQL("UPDATE data_point SET type=2 WHERE _id=1")
        pageFailure = true; fullAuditReads = 0
        assertEquals(listOf("1"), cycle().map { it.text("bridgeKey") })
        assertTrue(fullAuditReads > 0)
        reader.close(); reopen()
        repeat(3) {
            fullAuditReads = 0
            assertTrue(cycle().isEmpty())
            assertEquals(0, fullAuditReads)
        }
        pageFailure = false
        database.execSQL("UPDATE data_point SET type=3 WHERE _id=1")
        assertEquals(listOf("1"), cycle().map { it.text("bridgeKey") })
        fullReads = 0
        assertTrue(cycle().isEmpty()); assertEquals(0, fullReads)
    }

    @Test fun partialFallbackAuditDoesNotClaimCompletionAcrossRestart() {
        repeat(3) { insert(it + 1L) }; cycle()
        journalAvailable = false; readPartial = true
        val partial = changes("partial-fallback")
        assertTrue(partial.flag("complete") == true)
        assertTrue(partial.flag("partial") == true)
        ack(partial)
        reader.close(); reopen()
        readPartial = false; fullAuditReads = 0
        assertTrue(cycle().isEmpty())
        assertTrue(fullAuditReads > 0)
        fullAuditReads = 0
        assertTrue(cycle().isEmpty())
        assertEquals(0, fullAuditReads)
    }

    @Test fun dailyAuditComparesRowsAndMissingKeysWhileOnlyExplicitFullResendsUnchangedRows() {
        repeat(3) { insert(it + 1L) }; cycle()
        // Bypass a journal once to model a source change an independent audit must catch.
        val journalSchema = database.rawQuery("SELECT sql FROM sqlite_master WHERE type='trigger' AND tbl_name='data_point'", null).use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }
        val names = database.rawQuery("SELECT name FROM sqlite_master WHERE type='trigger' AND tbl_name='data_point'", null).use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }
        names.forEach { database.execSQL("DROP TRIGGER $it") }
        database.execSQL("UPDATE data_point SET type=2 WHERE _id=1")
        database.execSQL("DELETE FROM data_point WHERE _id=2")
        journalSchema.forEach(database::execSQL)
        clock += 86_400_000; fullAuditReads = 0
        val delta = cycle()
        assertEquals(setOf("1", "2"), delta.map { it.text("bridgeKey") }.toSet())
        assertTrue(delta.single { it.text("bridgeKey") == "2" }.flag("sourceAbsent") == true)
        assertTrue(fullAuditReads > 0)
        assertTrue(cycle().isEmpty())
        assertEquals(setOf("1", "3"), cycle(full = true).map { it.text("bridgeKey") }.toSet())
    }

    @Test fun freshReceiverAndExplicitFullDoNotInheritAnotherReceiversCursor() {
        insert(1); cycle()
        assertEquals(1, cycle("other-receiver").size)
        assertTrue(cycle().isEmpty())
        assertEquals(1, cycle(full = true).size)
    }

    @Test fun retentionOverflowAndRemovedTriggersCannotLookLikeAnEmptyLog() {
        insert(1); cycle()
        database.beginTransaction()
        try {
            repeat(8200) { database.execSQL("UPDATE data_point SET `values`=? WHERE _id=1", arrayOf(it.toString())) }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        fullReads = 0; assertEquals(1, cycle().size); assertTrue(fullReads > 0)
        AtomicHealthJournal.remove(database, "health")
        journalAvailable = false; fullReads = 0
        database.execSQL("UPDATE data_point SET type=2 WHERE _id=1")
        assertEquals(1, cycle().size); assertTrue(fullReads > 0)
    }
}
