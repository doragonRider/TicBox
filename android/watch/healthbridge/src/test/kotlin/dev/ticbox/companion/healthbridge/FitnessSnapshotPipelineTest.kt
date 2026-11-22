package dev.ticbox.companion.healthbridge

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.health.*
import dev.ticbox.companion.platform.data.SQLiteDataSyncStore
import dev.ticbox.companion.platform.health.SQLiteLocalHealthStore
import dev.ticbox.companion.protocol.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

/** Synthetic live SQLite (including WAL) -> snapshot -> wire pages -> phone store -> typed queries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35], application = Application::class)
class FitnessSnapshotPipelineTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val source = HealthRecordSource.LocalDevice("fixture-profile", PeerAddress("AA:BB:CC:DD:EE:FF"))
    private val parent = 9007199254740993L

    private fun database(): Pair<File, SQLiteDatabase> {
        val file = File.createTempFile("fitness-source-", ".db", context.cacheDir)
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE RECORD (_id INTEGER PRIMARY KEY,HASH TEXT,TYPE INTEGER,DURATION INTEGER,START_TIME INTEGER,END_TIME INTEGER,DISTANCE INTEGER,STEP INTEGER,HEART INTEGER,CALORIE REAL,SYNCED INTEGER,DELETED INTEGER,DEVICE_ID TEXT,ACCOUNT_ID TEXT,FUTURE BLOB)")
        db.execSQL("CREATE TABLE POINT (_id INTEGER PRIMARY KEY,RECORD_ID INTEGER,TIME INTEGER,TIMESTAMP INTEGER,LAT REAL,LON REAL,ACC REAL,SPEED REAL,DISTANCE INTEGER,STEP INTEGER,HEART INTEGER,CALORIE REAL,FUTURE TEXT)")
        return file to db
    }

    private fun SQLiteDatabase.record(id: Long = parent, deleted: Int = 0) {
        execSQL("INSERT INTO RECORD VALUES(?, 'same-hash',0,600000,1000000,1600000,120000,900,80,25.5,0,?,'private-device','private-account',X'010203')", arrayOf<Any>(id, deleted))
    }
    private fun SQLiteDatabase.point(id: Long, recordId: Long = parent) {
        execSQL("INSERT INTO POINT VALUES(?,?,?, ?,1.25,2.5,3.0,2.0,100,5,80,0.5,'keep-unknown')",
            arrayOf(id, recordId, id * 1000, 1000000 + id * 1000))
    }
    private fun snapshot(source: File): FitnessSnapshot {
        val copy = File.createTempFile("snapshot-fixture-", ".db", context.cacheDir)
        FitnessSnapshotMain.create(source, copy)
        return FitnessSnapshot(copy, SQLiteDatabase.openDatabase(copy.path, null, RootFitnessSnapshot.READ_ONLY_FLAGS), "fixture-version")
    }
    private fun FitnessSnapshotReader.read(table: LocalHealthTable, after: Long? = null, upper: Long? = null) =
        LocalHealthProtocol.page(page(LocalHealthProtocol.request(table, after, upper), CancellationSignal()))

    @Test fun incrementalRootPagesRefreshOldGpsPointsWithoutRetransmittingStableTraining() = runBlocking {
        val (file, writer) = database()
        writer.use { db ->
            assertTrue(db.enableWriteAheadLogging())
            db.record(); repeat(600) { db.point(it + 1L) }
            var now = 1_800_000_000_000L
            val tables = listOf(LocalHealthTable.FITNESS_RECORD, LocalHealthTable.FITNESS_POINT)
            val ledger = HealthIncrementalStore(context, "fitness-incremental.sqlite", now = { now }, pageRows = 128)
            var sourceReads = 0
            IncrementalHealthReader(ledger, { request, signal ->
                sourceReads++
                FitnessPageMain.capture(file, request, signal)
            }, tables).use { reader ->
                val remote = LocalHealthRemote {
                    object : LocalHealthConnection {
                        override val servesAllTables = true
                        override val supportedTables = tables.toSet()
                        override suspend fun page(table: LocalHealthTable, checkpoint: LocalHealthCheckpoint) = LocalHealthProtocol.page(
                            reader.handle(LocalHealthProtocol.changes(table, source.localUserId, checkpoint.run!!, checkpoint.full), CancellationSignal()))
                        override suspend fun acknowledge(table: LocalHealthTable, page: LocalHealthPage) {
                            assertTrue(reader.handle(LocalHealthProtocol.acknowledge(source.localUserId, table, page.batchId!!), CancellationSignal()).flag("committed") == true)
                        }
                        override fun close() = Unit
                    }
                }
                SQLiteDataSyncStore(context, "fitness-delta-phone.sqlite").use { database ->
                    val phone = SQLiteLocalHealthStore(database)
                    val engine = LocalHealthSyncEngine(this, phone, remote, tables = tables, now = { now })
                    suspend fun finish() = withTimeout(15_000) { engine.state.first { it.phase in setOf(
                        LocalHealthSyncPhase.COMPLETED, LocalHealthSyncPhase.PARTIAL, LocalHealthSyncPhase.FAILED) } }
                    try {
                        val original = file.readBytes(); val wal = File(file.path + "-wal").readBytes()
                        engine.attach(LocalHealthLink(source, 1))
                        assertEquals(601, finish().recordsCommitted)
                        assertContentEquals(original, file.readBytes()); assertContentEquals(wal, File(file.path + "-wal").readBytes())
                        assertTrue(sourceReads >= 6) // bounded pages, no whole-database copy
                        now += 360_000
                        db.execSQL("UPDATE POINT SET LAT=1.5,HEART=95 WHERE _id=500")
                        engine.sync()
                        assertEquals(1, finish().recordsCommitted) // Parent header was unchanged.
                        val updated = phone.fitnessPoints(HealthFitnessPointQuery(source, parent, offset = 499, limit = 1)).items.single()
                        assertTrue("1.5" in updated.raw.json); assertTrue("95" in updated.raw.json)
                        db.execSQL("DELETE FROM POINT WHERE _id=500")
                        engine.sync(LocalHealthSyncMode.FULL)
                        assertEquals(LocalHealthSyncPhase.COMPLETED, finish().phase)
                        val retained = phone.records(HealthRecordKind.FITNESS_POINT, HealthRecordQuery(source, recordKey = "500")).items.single()
                        assertTrue("\"sourceAbsent\":true" in retained.json); assertTrue("1.5" in retained.json)
                    } finally { engine.close() }
                }
            }
        }
    }

    @Test fun committedWalRowsAreReadOnlyConsistentAcrossBothTablesAndPreserveUnknownFields() {
        val (file, writer) = database()
        writer.use { db ->
            assertTrue(db.enableWriteAheadLogging())
            db.record(); db.point(1)
            var acquisitions = 0; var temporary: File? = null
            FitnessSnapshotReader { snapshot(file).also { acquisitions++; temporary = it.file } }.use { reader ->
                val records = reader.read(LocalHealthTable.FITNESS_RECORD)
                assertNull(records.error); assertEquals(parent, records.rows.single().id)
                assertEquals("AQID", records.rows.single().text("FUTURE"))
                // Mutations after acquisition must not leak into either table of this read window.
                db.record(parent + 1); db.point(2)
                val points = reader.read(LocalHealthTable.FITNESS_POINT)
                assertEquals(listOf(1L), points.rows.map { it.id })
                assertEquals(parent, points.rows.single().number("RECORD_ID"))
                assertEquals("keep-unknown", points.rows.single().text("FUTURE"))
                assertEquals(1, acquisitions)
                assertEquals(1, reader.read(LocalHealthTable.FITNESS_RECORD).rows.size)
            }
            assertFalse(temporary!!.exists())
            db.rawQuery("SELECT SYNCED,DELETED FROM RECORD WHERE _id=?", arrayOf(parent.toString())).use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertEquals(0, it.getInt(1))
            }
            FitnessSnapshotReader { snapshot(file) }.use { reader ->
                assertEquals(2, reader.read(LocalHealthTable.FITNESS_RECORD).rows.size)
                assertEquals(2, reader.read(LocalHealthTable.FITNESS_POINT).rows.size)
            }
        }
    }

    @Test fun aCorruptSourceIsNeitherDeletedNorRepaired() {
        val source = File.createTempFile("corrupt-fitness-", ".db", context.cacheDir)
        val original = ByteArray(4096) { 73 }
        source.writeBytes(original)
        val destination = File.createTempFile("snapshot-failed-", ".db", context.cacheDir)
        try {
            assertFailsWith<android.database.sqlite.SQLiteException> { FitnessSnapshotMain.create(source, destination) }
            assertTrue(source.isFile); assertContentEquals(original, source.readBytes())
        } finally { source.delete(); destination.delete() }
    }

    @Test fun fullPagedPrivateTablesCrossFramingReachPhoneAndRefreshDeletedRecords() = runBlocking {
        val (file, writer) = database()
        writer.use { db ->
            db.record(); repeat(270) { db.point(it.toLong() + 1) }
            SQLiteDataSyncStore(context, "root-fitness-pipeline.sqlite").use { database ->
                val store = SQLiteLocalHealthStore(database)
                var acquisitions = 0
                val remote = LocalHealthRemote {
                    val reader = FitnessSnapshotReader { snapshot(file).also { acquisitions++ } }
                    object : LocalHealthConnection {
                        override val servesAllTables = true
                        override val supportedTables = LocalHealthProtocol.supportedTables(LocalHealthProtocol.ready(
                            listOf(LocalHealthTable.FITNESS_RECORD, LocalHealthTable.FITNESS_POINT)))
                        override suspend fun page(table: LocalHealthTable, checkpoint: LocalHealthCheckpoint): LocalHealthPage {
                            val request = LocalHealthProtocol.request(table, checkpoint.afterId, checkpoint.upperId)
                            val response = reader.page(request, CancellationSignal())
                            return LocalHealthProtocol.page(LocalHealthProtocol.read(ByteArrayInputStream(LocalHealthProtocol.frame(response)))!!)
                        }
                        override fun close() = reader.close()
                    }
                }
                val engine = LocalHealthSyncEngine(this, store, remote)
                try {
                    engine.attach(LocalHealthLink(source, 7))
                    val result = withTimeout(15_000) { engine.state.first { it.phase == LocalHealthSyncPhase.COMPLETED || it.phase == LocalHealthSyncPhase.FAILED } }
                    assertEquals(LocalHealthSyncPhase.COMPLETED, result.phase)
                    assertEquals(271, result.recordsCommitted); assertEquals(4, result.pagesCommitted)
                    assertEquals(1, acquisitions)
                    HealthRecordsEngine(deviceRecords = store).use { client ->
                        assertEquals(parent, client.fitnessRecords(HealthFitnessRecordQuery(source)).items.single().id)
                        val first = client.fitnessPoints(HealthFitnessPointQuery(source, parent, limit = 128))
                        assertEquals(128, first.items.size); assertEquals(128, first.nextOffset)
                        assertEquals(270, client.fitnessPoints(HealthFitnessPointQuery(source, parent, limit = 500)).items.size)
                        val raw = client.records(HealthRecordKind.FITNESS_POINT, HealthRecordQuery(source, limit = 1)).items.single()
                        assertTrue("keep-unknown" in raw.json)
                        assertEquals(LocalHealthTable.FITNESS_POINT.sourceName, raw.sourceName)
                    }
                    db.execSQL("UPDATE RECORD SET DELETED=1 WHERE _id=?", arrayOf(parent))
                    engine.sync()
                    withTimeout(15_000) { engine.state.first { it.phase == LocalHealthSyncPhase.COMPLETED && acquisitions == 2 } }
                    assertTrue(store.fitnessRecords(HealthFitnessRecordQuery(source)).items.isEmpty())
                    assertEquals(1, store.fitnessRecords(HealthFitnessRecordQuery(source, includeDeleted = true)).items.size)
                } finally { engine.close() }
            }
        }
    }

    @Test fun aWatchThatNeverRecordedAWorkoutReadsAsEmptyNotAsAFailure() {
        val request = buildJsonObject { put("table", LocalHealthTable.FITNESS_RECORD.wireName) }
        val blank = File.createTempFile("fitness-blank-", ".db", context.cacheDir)
        SQLiteDatabase.openOrCreateDatabase(blank, null).close()
        listOf(File(context.cacheDir, "fitness-absent.db"), blank).forEach { file ->
            val page = LocalHealthProtocol.page(FitnessPageMain.capture(file, request))
            assertNull(page.error); assertTrue(page.complete); assertTrue(page.rows.isEmpty())
        }
    }

    @Test fun anEmptyAcquiredTableAndRootDenialAreDistinctAndCancellationIsNotSuccess() {
        val (file, writer) = database()
        writer.use {
            FitnessSnapshotReader { snapshot(file) }.use { reader ->
                val page = reader.read(LocalHealthTable.FITNESS_RECORD)
                assertNull(page.error); assertTrue(page.complete); assertTrue(page.rows.isEmpty())
            }
            var attempts = 0
            FitnessSnapshotReader { attempts++; throw FitnessSourceException("root_denied") }.use { reader ->
                assertEquals("root_denied", reader.read(LocalHealthTable.FITNESS_RECORD).error)
                assertEquals("root_denied", reader.read(LocalHealthTable.FITNESS_POINT).error)
                assertEquals(1, attempts) // Do not repeatedly ask for su within one window.
            }
            FitnessSnapshotReader { it.throwIfCanceled(); snapshot(file) }.use { reader ->
                val cancelled = CancellationSignal().apply { cancel() }
                assertEquals("query_cancelled", reader.page(LocalHealthProtocol.request(LocalHealthTable.FITNESS_RECORD, null, null), cancelled).text("error"))
            }
            BridgeSettings.setRootFitnessEnabled(context, false)
            WatchHealthReader(context).use { reader ->
                assertFalse(reader.tables.any { it.privateFitness })
                assertEquals("root_not_enabled", reader.page(LocalHealthProtocol.request(LocalHealthTable.FITNESS_RECORD, null, null), CancellationSignal()).text("error"))
            }
        }
    }

    @Test fun manualPrivateExportFiltersWallTimeAndRedactsVendorIdentifiersWithoutAlteringGps() {
        val (file, writer) = database()
        writer.use { db ->
            db.record(); db.point(1)
            FitnessSnapshotReader { snapshot(file) }.use { reader ->
                val output = ByteArrayOutputStream()
                val report = HealthJsonExporter(reader::page, { buildJsonObject { } }).write(output,
                    HealthExportSelection(linkedMapOf(LocalHealthTable.FITNESS_RECORD to setOf(0), LocalHealthTable.FITNESS_POINT to emptySet()),
                        fromMillis = 1000000, toMillis = 1600000), CancellationSignal())
                assertEquals("complete", report.status); assertEquals(2, report.rows)
                val text = output.toString(Charsets.UTF_8.name())
                assertFalse("private-device" in text); assertFalse("private-account" in text); assertFalse("same-hash" in text)
                assertTrue("root_readonly_sqlite_snapshot" in text)
                val rows = LocalHealthProtocol.parse(output.toByteArray())["tables"]!!.jsonArray
                    .last().jsonObject["rows"]!!.jsonArray.single().jsonObject["columns"]!!.jsonObject
                assertEquals("1.25", rows.text("LAT")); assertEquals("2.5", rows.text("LON"))
            }
        }
    }
}
