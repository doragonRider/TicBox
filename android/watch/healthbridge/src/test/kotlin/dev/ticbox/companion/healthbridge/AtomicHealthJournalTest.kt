package dev.ticbox.companion.healthbridge

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import dev.ticbox.companion.protocol.LocalHealthTable
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.test.*

/** Real SQLite trigger/conflict/WAL semantics; no provider, root, device or health data is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Suppress("DEPRECATION") // Explicit native SQLite coverage, including legacy Robolectric configurations.
class AtomicHealthJournalTest {
    private val meta = "ticwatch_journal_v1_meta"
    private val events = "ticwatch_journal_v1_events"
    private val context get() = RuntimeEnvironment.getApplication()

    private fun database(source: String = "health", beforeInstall: (SQLiteDatabase) -> Unit = {}): Pair<File, SQLiteDatabase> {
        val file = File.createTempFile("atomic-health-journal-", ".db", context.cacheDir)
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        when (source) {
            "health" -> {
                db.execSQL("CREATE TABLE data_point (_id INTEGER PRIMARY KEY AUTOINCREMENT, value TEXT)")
                db.execSQL("CREATE TABLE data_session (id TEXT PRIMARY KEY, value TEXT)")
                db.execSQL("CREATE TABLE data_source (name TEXT PRIMARY KEY, value TEXT)")
            }
            "fitness" -> {
                db.execSQL("CREATE TABLE RECORD (_id INTEGER PRIMARY KEY AUTOINCREMENT, hash TEXT, value TEXT)")
                db.execSQL("CREATE TABLE POINT (_id INTEGER PRIMARY KEY, record_id INTEGER, value TEXT)")
            }
        }
        beforeInstall(db)
        AtomicHealthJournal.install(db, source)
        return file to db
    }

    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.long
    private fun JsonObject.flag(key: String) = getValue(key).jsonPrimitive.boolean
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.entries() = getValue("events").jsonArray.map { it.jsonObject }
    private fun SQLiteDatabase.number(sql: String): Long = rawQuery(sql, null).use { assertTrue(it.moveToFirst()); it.getLong(0) }
    private fun SQLiteDatabase.text(sql: String): String = rawQuery(sql, null).use { assertTrue(it.moveToFirst()); it.getString(0) }
    private fun SQLiteDatabase.window(source: String = "health") = AtomicHealthJournal.status(this, source)
    private fun SQLiteDatabase.page(table: LocalHealthTable = LocalHealthTable.POINT, source: String = "health",
        after: Long = 0, upper: Long = window(source).number("head"), limit: Int = 32,
        epoch: String = window(source).text("epoch")) = AtomicHealthJournal.read(this, source, table, epoch, after, upper, limit)
    private fun SQLiteDatabase.transaction(body: () -> Unit) {
        beginTransactionNonExclusive()
        try { body(); setTransactionSuccessful() } finally { endTransaction() }
    }
    private fun failure(code: String, body: () -> Unit) = assertEquals(code, assertFailsWith<HealthJournalException>(block = body).code)

    @Test fun installsIdempotentlyWithoutChangingOemRowsVersionsRoomOrPragmas() {
        val (_, db) = database(beforeInstall = {
            it.execSQL("PRAGMA user_version=17"); it.execSQL("PRAGMA application_id=4141"); it.execSQL("PRAGMA recursive_triggers=OFF")
            it.execSQL("CREATE TABLE room_master_table(id INTEGER PRIMARY KEY,identity_hash TEXT)")
            it.execSQL("INSERT INTO room_master_table VALUES(42,'oem-identity')")
            it.execSQL("INSERT INTO data_point VALUES(1,'preserved')")
        })
        db.use {
            val original = db.window()
            assertEquals(original, AtomicHealthJournal.install(db, "health"))
            assertEquals(0L, original.number("head")); assertEquals(0L, original.number("floor"))
            assertEquals(17L, db.number("PRAGMA user_version")); assertEquals(4141L, db.number("PRAGMA application_id"))
            assertEquals(0L, db.number("PRAGMA recursive_triggers"))
            assertEquals("oem-identity", db.text("SELECT identity_hash FROM room_master_table"))
            assertEquals("preserved", db.text("SELECT value FROM data_point"))
            assertTrue(db.page().flag("complete")); assertFalse(db.page().flag("wide"))
            assertTrue(AtomicHealthJournal.remove(db, "health").flag("removed"))
            assertFalse(db.window().flag("installed"))
            assertFalse(AtomicHealthJournal.remove(db, "health").flag("removed"))
            assertEquals(0L, db.number("SELECT COUNT(*) FROM sqlite_master WHERE name LIKE 'ticwatch_journal_v1_%'"))
            assertEquals("preserved", db.text("SELECT value FROM data_point"))
            val reinstalled = AtomicHealthJournal.install(db, "health")
            assertNotEquals(original.text("epoch"), reinstalled.text("epoch"))
        }
    }

    @Test fun completesAutoIdsAndTracksOldAndNewKeysForBothSources() {
        val (_, db) = database()
        db.use {
            db.execSQL("INSERT INTO data_point(value) VALUES('new')")
            db.execSQL("UPDATE data_point SET _id=9007199254740993,value='changed' WHERE _id=1")
            db.execSQL("INSERT INTO data_session VALUES('sleep:old','old')")
            db.execSQL("UPDATE data_session SET id='sleep:new',value='changed' WHERE id='sleep:old'")
            val point = db.page().entries()
            assertEquals(listOf("1", "9007199254740993"), point.map { it.text("key") })
            assertEquals("1", point[1].text("previousKey"))
            assertTrue(point.none { it.flag("pending") || it.flag("wide") })
            val session = db.page(LocalHealthTable.SESSION).entries()
            assertEquals(listOf("sleep:old", "sleep:new"), session.map { it.text("key") })
            assertEquals("sleep:old", session.last().text("previousKey"))
        }
        val (_, fitness) = database("fitness")
        fitness.use {
            fitness.execSQL("INSERT INTO RECORD(hash) VALUES('auto')")
            fitness.execSQL("INSERT INTO POINT VALUES(9007199254740993,1,'large')")
            assertEquals("1", fitness.page(LocalHealthTable.FITNESS_RECORD, "fitness").entries().single().text("key"))
            val point = fitness.page(LocalHealthTable.FITNESS_POINT, "fitness").entries().single()
            assertEquals("9007199254740993", point.text("key")); assertFalse(point.flag("pending"))
        }
    }

    @Test fun pendingPairingQueriesUseTheBoundedPartialIndex() {
        val (_, db) = database()
        db.use {
            for (query in listOf("SELECT MAX(seq) FROM $events WHERE kind='data_point' AND op='I' AND pending=1",
                "SELECT COUNT(*) FROM $events WHERE kind='data_point' AND op='I' AND pending=1",
                "SELECT 1 FROM $events WHERE kind='data_point' AND op='I' AND pending=1 LIMIT 1 OFFSET 1")) {
                val plan = db.rawQuery("EXPLAIN QUERY PLAN $query", null).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }.joinToString("\n")
                }
                assertTrue(plan.contains("ticwatch_journal_v1_pending"), plan)
                assertFalse(plan.contains("SCAN $events"), plan)
            }
        }
    }

    @Test fun transactionRollbackAndSavepointUndoLogAndHeadAlongWithRows() {
        val (_, db) = database()
        db.use {
            db.beginTransactionNonExclusive()
            try { db.execSQL("INSERT INTO data_point VALUES(1,'rollback')") } finally { db.endTransaction() }
            assertEquals(0L, db.window().number("head")); assertEquals(0L, db.number("SELECT COUNT(*) FROM data_point"))
            db.transaction {
                db.execSQL("INSERT INTO data_point VALUES(1,'kept')")
                db.execSQL("SAVEPOINT nested")
                db.execSQL("INSERT INTO data_point VALUES(2,'rolled back')")
                db.execSQL("UPDATE data_point SET value='rolled back' WHERE _id=1")
                db.execSQL("ROLLBACK TO nested"); db.execSQL("RELEASE nested")
                db.execSQL("INSERT INTO data_point VALUES(3,'kept')")
            }
            assertEquals(2L, db.window().number("head"))
            assertEquals(listOf("1", "3"), db.page().entries().map { it.text("key") })
            assertEquals(listOf(1L, 2L), db.page().entries().map { it.number("sequence") })
        }
    }

    @Test fun afterRaiseFailKeepsChangedRowsWithPendingBaselineCandidatesEvenWithOuterFail() {
        val (_, db) = database()
        db.use {
            db.execSQL("CREATE TRIGGER oem_after AFTER INSERT ON data_point WHEN NEW._id=2 BEGIN SELECT RAISE(FAIL,'fixture failure'); END")
            assertFailsWith<SQLiteException> { db.execSQL("INSERT OR FAIL INTO data_point VALUES(1,'first'),(2,'changed'),(3,'never')") }
            assertEquals(2L, db.number("SELECT COUNT(*) FROM data_point"))
            val page = db.page()
            assertEquals(2L, page.number("head")); assertTrue(page.flag("wide"))
            assertTrue(page.entries().last().flag("pending"))
            assertEquals("2", page.entries().last().text("key"))
            db.execSQL("DROP TRIGGER oem_after")
            db.execSQL("INSERT INTO data_point VALUES(3,'next')")
            assertEquals(listOf(1L, 2L, 3L), db.page().entries().map { it.number("sequence") })
            assertTrue(db.page().flag("wide"))
        }
    }

    @Test fun afterRaiseIgnoreCanKeepMutationButNeverLooksLikeAnEmptyCleanJournal() {
        val (_, db) = database()
        db.use {
            db.execSQL("CREATE TRIGGER oem_after AFTER INSERT ON data_point BEGIN SELECT RAISE(IGNORE); END")
            db.execSQL("INSERT OR IGNORE INTO data_point VALUES(1,'retained')")
            assertEquals(1L, db.number("SELECT COUNT(*) FROM data_point"))
            val event = db.page().entries().single()
            assertEquals("1", event.text("key")); assertTrue(event.flag("pending")); assertTrue(event.flag("wide"))
        }
    }

    @Test fun ignoredConstraintCandidateRemainsWideButDoesNotBlockFutureWritesOrTrimming() {
        val (_, db) = database()
        db.use {
            db.execSQL("INSERT INTO data_session VALUES('same','original')")
            db.execSQL("INSERT OR IGNORE INTO data_session VALUES('same','ignored')")
            assertEquals("original", db.text("SELECT value FROM data_session"))
            assertTrue(db.page(LocalHealthTable.SESSION).flag("wide"))
            db.transaction { repeat(8193) { db.execSQL("INSERT INTO data_point(value) VALUES('new')") } }
            val latest = db.window()
            assertEquals(8195L, latest.number("head")); assertEquals(3L, latest.number("floor"))
            assertEquals(8192L, db.number("SELECT COUNT(*) FROM $events"))
            assertTrue(db.page(upper = latest.number("head")).flag("reset"))
            val bounded = db.page(after = latest.number("floor"), upper = latest.number("head"))
            assertFalse(bounded.flag("reset")); assertEquals(32, bounded.entries().size)
        }
    }

    @Test fun updateAndDeleteAfterFailRetainCandidatesForCommittedStateReread() {
        val (_, db) = database()
        db.use {
            db.execSQL("INSERT INTO data_session VALUES('old','existing')")
            db.execSQL("CREATE TRIGGER oem_update AFTER UPDATE ON data_session BEGIN SELECT RAISE(FAIL,'stop'); END")
            assertFailsWith<SQLiteException> { db.execSQL("UPDATE OR FAIL data_session SET id='new'") }
            assertEquals("new", db.text("SELECT id FROM data_session"))
            val update = db.page(LocalHealthTable.SESSION).entries().last()
            assertEquals("new", update.text("key")); assertEquals("old", update.text("previousKey")); assertTrue(update.flag("pending"))
            db.execSQL("CREATE TRIGGER oem_delete AFTER DELETE ON data_session BEGIN SELECT RAISE(FAIL,'stop'); END")
            assertFailsWith<SQLiteException> { db.execSQL("DELETE FROM data_session") }
            assertEquals(0L, db.number("SELECT COUNT(*) FROM data_session"))
            val delete = db.page(LocalHealthTable.SESSION).entries().last()
            assertEquals("new", delete.text("key")); assertFalse(delete.flag("pending"))
            assertFalse(delete.containsKey("deleted")); assertFalse(delete.containsKey("tombstone"))
        }
    }

    @Test fun ignoredDeleteCandidateDoesNotClaimThatTheSourceRowWasRemoved() {
        val (_, db) = database(beforeInstall = {
            it.execSQL("INSERT INTO data_point VALUES(1,'retained')")
            it.execSQL("CREATE TRIGGER oem_delete BEFORE DELETE ON data_point BEGIN SELECT RAISE(IGNORE); END")
        })
        db.use {
            db.execSQL("DELETE FROM data_point")
            val event = db.page().entries().single()
            assertEquals("1", event.text("key")); assertFalse(event.flag("pending"))
            assertEquals("retained", db.text("SELECT value FROM data_point WHERE _id=1"))
            assertFalse(event.containsKey("deleted")); assertFalse(event.containsKey("tombstone"))
        }
    }

    @Test fun secondaryUniqueReplaceRequestsBaselineWithoutRequiringRecursiveTriggers() {
        val (_, db) = database("fitness") { it.execSQL("CREATE UNIQUE INDEX record_hash ON RECORD(hash)") }
        db.use {
            assertEquals(0L, db.number("PRAGMA recursive_triggers"))
            db.execSQL("INSERT INTO RECORD VALUES(1,'same','old')")
            db.execSQL("INSERT OR REPLACE INTO RECORD VALUES(2,'same','replacement')")
            assertEquals(1L, db.number("SELECT COUNT(*) FROM RECORD"))
            assertEquals(2L, db.number("SELECT _id FROM RECORD"))
            val insert = db.page(LocalHealthTable.FITNESS_RECORD, "fitness").entries().last()
            assertEquals("2", insert.text("key")); assertTrue(insert.flag("wide")); assertFalse(insert.flag("pending"))
            db.execSQL("INSERT INTO RECORD VALUES(3,'other','next')")
            db.execSQL("UPDATE OR REPLACE RECORD SET hash='same' WHERE _id=3")
            val update = db.page(LocalHealthTable.FITNESS_RECORD, "fitness").entries().last()
            assertTrue(update.flag("wide")); assertEquals("3", update.text("key"))
            assertEquals(0L, db.number("PRAGMA recursive_triggers"))
        }
    }

    @Test fun nestedReplaceAndIgnoredSameKindWritesAreConservativelyWide() {
        val (_, db) = database(beforeInstall = {
            it.execSQL("PRAGMA recursive_triggers=ON")
            it.execSQL("INSERT INTO data_session VALUES('inner','original')")
            it.execSQL("CREATE TRIGGER nested_before BEFORE INSERT ON data_session WHEN NEW.id='outer' BEGIN " +
                "INSERT OR IGNORE INTO data_session VALUES('inner','ignored'); END")
            it.execSQL("CREATE TRIGGER nested_replace BEFORE INSERT ON data_point WHEN NEW._id=1 BEGIN " +
                "INSERT OR REPLACE INTO data_point VALUES(2,'nested'); END")
        })
        db.use {
            db.execSQL("INSERT INTO data_session VALUES('outer','outer')")
            assertEquals(2L, db.number("SELECT COUNT(*) FROM data_session"))
            val session = db.page(LocalHealthTable.SESSION)
            assertTrue(session.flag("wide")); assertTrue(session.entries().any { it.flag("pending") })
            assertTrue(session.entries().all { it.flag("wide") })
            db.execSQL("INSERT INTO data_point VALUES(1,'outer')")
            val point = db.page()
            assertEquals(setOf("1", "2"), point.entries().map { it.text("key") }.toSet())
            assertTrue(point.flag("wide")); assertTrue(point.entries().none { it.flag("pending") })
            assertEquals(1L, db.number("PRAGMA recursive_triggers"))
        }
    }

    @Test fun sourceChangesAffectBothHealthTablesAndUnrelatedEventsAdvanceTheWindow() {
        val (_, db) = database()
        db.use {
            db.execSQL("INSERT INTO data_session VALUES('sleep','one')")
            val unrelated = db.page()
            assertTrue(unrelated.flag("complete")); assertEquals(1L, unrelated.number("nextSequence")); assertTrue(unrelated.entries().isEmpty())
            db.execSQL("INSERT INTO data_source VALUES('watch','one')")
            db.execSQL("UPDATE data_source SET value='two'")
            db.execSQL("DELETE FROM data_source")
            for (table in listOf(LocalHealthTable.POINT, LocalHealthTable.SESSION)) {
                val page = db.page(table, after = 1)
                assertTrue(page.flag("wide")); assertEquals(3, page.entries().size)
                assertTrue(page.entries().all { it.flag("wide") && it["key"] == JsonNull })
            }
        }
    }

    @Test fun nullOversizeNulAndNonAsciiKeysRemainBoundedWideCandidates() {
        val (_, db) = database()
        db.use {
            val keys = listOf<String?>(null, "x".repeat(4096), "tiny\u0000" + "x".repeat(4096), "睡眠")
            keys.forEach { db.execSQL("INSERT INTO data_session VALUES(?,'value')", arrayOf<Any?>(it)) }
            val page = db.page(LocalHealthTable.SESSION)
            assertEquals(4, page.entries().size)
            assertTrue(page.flag("wide")); assertTrue(page.entries().all { it["key"] == JsonNull && !it.flag("pending") })
            assertTrue(page.toString().length < 2048)
        }
    }

    @Test fun paginationUsesFixedUpperAndReturnsResetForEveryStaleCursorCase() {
        val (_, db) = database()
        db.use {
            repeat(7) { db.execSQL("INSERT INTO data_point(value) VALUES('value')") }
            val window = db.window(); val epoch = window.text("epoch")
            val first = db.page(limit = 3)
            assertEquals(listOf(1L, 2L, 3L), first.entries().map { it.number("sequence") })
            assertEquals(3L, first.number("nextSequence")); assertFalse(first.flag("complete"))
            db.execSQL("INSERT INTO data_point(value) VALUES('later')")
            val second = db.page(after = 3, upper = 7, limit = 3, epoch = epoch)
            assertEquals(listOf(4L, 5L, 6L), second.entries().map { it.number("sequence") })
            val last = db.page(after = 6, upper = 7, limit = 3, epoch = epoch)
            assertTrue(last.flag("complete")); assertEquals(7L, last.number("nextSequence"))
            assertEquals(listOf(7L), last.entries().map { it.number("sequence") })
            for (page in listOf(db.page(epoch = "old"), db.page(after = 5, upper = 4), db.page(upper = 9), db.page(upper = -1))) {
                assertTrue(page.flag("reset")); assertTrue(page.flag("wide")); assertFalse(page.flag("complete")); assertTrue(page.entries().isEmpty())
            }
            failure("journal_invalid_cursor") { db.page(after = -1) }
            failure("journal_invalid_cursor") { db.page(limit = 0) }
            failure("journal_invalid_cursor") { db.page(limit = 129) }
            failure("journal_invalid_source") { db.page(LocalHealthTable.SPORT) }
        }
    }

    @Test fun bigNestedTransactionCanTrimAnActiveAncestorAndCompleteWithWideFallback() {
        val (_, db) = database(beforeInstall = {
            it.execSQL("CREATE TRIGGER big_nested BEFORE INSERT ON data_point WHEN NEW._id=1 BEGIN " +
                "INSERT INTO data_source(name,value) WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<8193) " +
                "SELECT 'source:'||x,'fixture' FROM n; END")
        })
        db.use {
            db.execSQL("INSERT INTO data_point VALUES(1,'ancestor')")
            val window = db.window()
            assertEquals(8195L, window.number("head")); assertEquals(3L, window.number("floor"))
            assertEquals(8192L, db.number("SELECT COUNT(*) FROM $events"))
            assertEquals(8193L, db.number("SELECT COUNT(*) FROM data_source"))
            assertEquals(1L, db.number("SELECT COUNT(*) FROM data_point"))
            assertTrue(db.page(upper = window.number("head")).flag("reset"))
            val tail = db.page(after = 8194, upper = 8195)
            assertTrue(tail.flag("wide")); assertEquals("1", tail.entries().single().text("key"))
            assertFalse(tail.entries().single().flag("pending"))
        }
    }

    @Test fun rollbackAlsoRestoresTrimmedEntriesAndRetentionFloor() {
        val (_, db) = database()
        db.use {
            db.transaction { repeat(8190) { db.execSQL("INSERT INTO data_point(value) VALUES('committed')") } }
            val original = db.window()
            db.beginTransactionNonExclusive()
            try {
                repeat(10) { db.execSQL("INSERT INTO data_point(value) VALUES('rollback')") }
                assertEquals(8L, db.window().number("floor"))
                assertEquals(8192L, db.number("SELECT COUNT(*) FROM $events"))
            } finally { db.endTransaction() }
            assertEquals(original, db.window())
            assertEquals(1L, db.number("SELECT MIN(seq) FROM $events"))
            assertEquals(8190L, db.number("SELECT COUNT(*) FROM data_point"))
        }
    }

    @Test fun journalInsertIgnoreOrMetadataIgnoreForcesRollbackUnderOuterConflictModes() {
        for (objectName in listOf(meta, events)) for (outer in listOf("IGNORE", "FAIL", "REPLACE")) {
            val (_, db) = database()
            db.use {
                val event = if (objectName == meta) "UPDATE" else "INSERT"
                db.execSQL("CREATE TRIGGER break_journal BEFORE $event ON $objectName BEGIN SELECT RAISE(IGNORE); END")
                assertFailsWith<SQLiteException>("$objectName / $outer") {
                    db.execSQL("INSERT OR $outer INTO data_point VALUES(1,'first'),(2,'second')")
                }
                db.execSQL("DROP TRIGGER break_journal")
                assertEquals(0L, db.number("SELECT COUNT(*) FROM data_point"))
                assertEquals(0L, db.window().number("head"))
            }
        }
    }

    @Test fun afterCompletionFailureCannotHideAlreadyAppliedRows() {
        val (_, db) = database()
        db.use {
            db.execSQL("CREATE TRIGGER break_completion BEFORE UPDATE ON $events BEGIN SELECT RAISE(FAIL,'fixture fail'); END")
            assertFailsWith<SQLiteException> { db.execSQL("INSERT OR FAIL INTO data_point VALUES(1,'changed')") }
            db.execSQL("DROP TRIGGER break_completion")
            assertEquals(1L, db.number("SELECT COUNT(*) FROM data_point"))
            assertTrue(db.page().flag("wide")); assertTrue(db.page().entries().single().flag("pending"))
        }
    }

    @Test fun unknownOrAlteredNamespaceObjectsAreNeverOverwrittenOrRemoved() {
        val file = File.createTempFile("foreign-journal-", ".db", context.cacheDir)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE $meta(foreign_owner TEXT)")
            db.execSQL("INSERT INTO $meta VALUES('retained')")
            failure("journal_namespace_unknown") { AtomicHealthJournal.install(db, "health") }
            failure("journal_namespace_unknown") { AtomicHealthJournal.status(db, "health") }
            failure("journal_namespace_unknown") { AtomicHealthJournal.remove(db, "health") }
            assertEquals("retained", db.text("SELECT foreign_owner FROM $meta"))
        }
        val (_, db) = database()
        db.use {
            db.execSQL("CREATE INDEX foreign_index ON $events(kind)")
            failure("journal_namespace_unknown") { db.window() }
            failure("journal_namespace_unknown") { AtomicHealthJournal.remove(db, "health") }
            db.execSQL("DROP INDEX foreign_index")
            db.execSQL("DROP TRIGGER ticwatch_journal_v1_data_point_i_after")
            db.execSQL("CREATE TRIGGER ticwatch_journal_v1_data_point_i_after AFTER INSERT ON data_point BEGIN SELECT 1; END")
            failure("journal_namespace_unknown") { db.window() }
            failure("journal_namespace_unknown") { AtomicHealthJournal.remove(db, "health") }
        }
    }

    @Test fun partialNamespaceCanBeRemovedButMissingTriggersAreNeverSilentlyRepaired() {
        val (_, db) = database()
        db.use {
            db.execSQL("DROP TRIGGER ticwatch_journal_v1_data_point_i_before")
            failure("journal_incomplete") { db.window() }
            failure("journal_incomplete") { AtomicHealthJournal.install(db, "health") }
            assertTrue(AtomicHealthJournal.remove(db, "health").flag("removed"))
            AtomicHealthJournal.install(db, "health")
            db.execSQL("DROP TABLE data_point") // SQLite removes every attached trigger itself.
            failure("journal_incomplete") { db.window() }
            assertTrue(AtomicHealthJournal.remove(db, "health").flag("removed"))
            assertFalse(db.window().flag("installed"))
        }
    }

    @Test fun missingJournalTableOrIndexCanBeRemovedWithoutTouchingTheOemTables() {
        for (dropped in listOf("TABLE $events", "TABLE $meta", "INDEX ticwatch_journal_v1_pending")) {
            val (_, db) = database()
            db.use {
                db.execSQL("INSERT INTO data_point VALUES(1,'oem retained')")
                db.execSQL("DROP $dropped")
                failure("journal_incomplete") { db.window() }
                assertTrue(AtomicHealthJournal.remove(db, "health").flag("removed"))
                assertEquals("oem retained", db.text("SELECT value FROM data_point"))
                assertFalse(db.window().flag("installed"))
            }
        }
    }

    @Test fun deletedEventsOrMetadataAreDetectedRatherThanReturningCleanEmptyPages() {
        val (_, db) = database()
        db.use {
            db.execSQL("INSERT INTO data_point VALUES(1,'value')")
            val epoch = db.window().text("epoch")
            db.execSQL("DELETE FROM $events")
            failure("journal_metadata_invalid") { db.window() }
            failure("journal_metadata_invalid") { db.page(epoch = epoch, upper = 1) }
            assertTrue(AtomicHealthJournal.remove(db, "health").flag("removed"))
            AtomicHealthJournal.install(db, "health")
            db.execSQL("DELETE FROM $meta")
            failure("journal_metadata_invalid") { db.window() }
            assertFailsWith<SQLiteException> { db.execSQL("INSERT OR IGNORE INTO data_point VALUES(2,'no metadata')") }
            assertEquals(1L, db.number("SELECT COUNT(*) FROM data_point"))
        }
    }

    @Test fun walReaderOnlySeesCommittedHeadAndRollbackDoesNotConsumeSequenceNumbers() {
        val (file, db) = database { assertTrue(it.enableWriteAheadLogging()) }
        db.use {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { reader ->
                val old = reader.window()
                db.beginTransactionNonExclusive()
                try {
                    db.execSQL("INSERT INTO data_point VALUES(1,'uncommitted')")
                    assertEquals(old, reader.window())
                    assertTrue(reader.page(upper = old.number("head")).entries().isEmpty())
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                assertEquals(1L, reader.window().number("head"))
                db.beginTransactionNonExclusive()
                try { db.execSQL("INSERT INTO data_point VALUES(2,'rollback')") } finally { db.endTransaction() }
                assertEquals(1L, reader.window().number("head"))
                db.execSQL("INSERT INTO data_point VALUES(3,'committed')")
                assertEquals(listOf("1", "3"), reader.page().entries().map { it.text("key") })
                assertEquals(listOf(1L, 2L), reader.page().entries().map { it.number("sequence") })
            }
        }
    }

    @Test fun installingAndRemovingOnAnOpenWalDatabaseKeepItsJournalModeAndVersions() {
        val (file, db) = database {
            assertTrue(it.enableWriteAheadLogging()); it.execSQL("PRAGMA user_version=17")
        }
        db.use {
            assertEquals("wal", db.text("PRAGMA journal_mode"))
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { reader ->
                db.execSQL("INSERT INTO data_point VALUES(1,'kept')")
                assertEquals(1L, reader.window().number("head")); assertEquals("wal", reader.text("PRAGMA journal_mode"))
            }
            AtomicHealthJournal.remove(db, "health")
            assertEquals("wal", db.text("PRAGMA journal_mode")); assertEquals(17L, db.number("PRAGMA user_version"))
            assertEquals("kept", db.text("SELECT value FROM data_point"))
        }
    }

    @Test fun walConcurrentRetentionAndPagingHaveOneMetadataAndEventsSnapshot() {
        val (file, db) = database { assertTrue(it.enableWriteAheadLogging()) }
        db.use {
            db.transaction { repeat(8192) { db.execSQL("INSERT INTO data_point(value) VALUES('initial')") } }
            val initial = db.window(); val ready = CountDownLatch(1); val start = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val writer = executor.submit {
                    ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS))
                    repeat(60) { db.execSQL("INSERT INTO data_point(value) VALUES('concurrent')") }
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS))
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { reader ->
                    start.countDown()
                    repeat(80) {
                        val page = reader.page(epoch = initial.text("epoch"), upper = 8192, limit = 128)
                        if (page.flag("reset")) {
                            assertTrue(page.number("floor") > 0); assertTrue(page.entries().isEmpty())
                        } else {
                            assertEquals(0L, page.number("floor")); assertEquals(8192L, page.number("head"))
                            assertEquals((1L..128L).toList(), page.entries().map { it.number("sequence") })
                        }
                    }
                }
                writer.get(20, TimeUnit.SECONDS)
                assertEquals(8252L, db.window().number("head")); assertEquals(60L, db.window().number("floor"))
            } finally { start.countDown(); executor.shutdownNow() }
        }
    }
}
