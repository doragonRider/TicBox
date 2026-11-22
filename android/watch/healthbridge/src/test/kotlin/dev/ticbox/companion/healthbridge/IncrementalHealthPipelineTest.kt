package dev.ticbox.companion.healthbridge

import android.app.Application
import android.content.pm.ProviderInfo
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.health.*
import dev.ticbox.companion.platform.data.SQLiteDataSyncStore
import dev.ticbox.companion.platform.health.SQLiteLocalHealthStore
import dev.ticbox.companion.protocol.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.*

/** Synthetic OEM SQLite -> production incremental ledger -> wire frames -> phone transaction/query. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35], application = Application::class)
class IncrementalHealthPipelineTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val source = HealthRecordSource.LocalDevice("local-profile", PeerAddress("AA:BB:CC:DD:EE:FF"))
    private var now = 1_800_000_000_000L
    private lateinit var provider: LocalHealthPipelineTest.ReadOnlyFixtureProvider
    private val tables = listOf(LocalHealthTable.SESSION, LocalHealthTable.POINT)
    private lateinit var ledger: HealthIncrementalStore
    private lateinit var reader: IncrementalHealthReader
    private var transmitted = 0
    private var acknowledged = 0
    private var dropAck = false
    /** Whether the phone's first-sync window goes on the wire, as in production. */
    private var windowed = false

    @Before fun setup() {
        provider = LocalHealthPipelineTest.ReadOnlyFixtureProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = HealthProviderReader.URI.authority; exported = true })
        ShadowContentResolver.registerProviderInternal(HealthProviderReader.URI.authority!!, provider)
        reopenWatch()
    }
    private fun reopenWatch() {
        ledger = HealthIncrementalStore(context, now = { now })
        val providerReader = HealthProviderReader(context.contentResolver, "synthetic")
        reader = IncrementalHealthReader(ledger, { request, signal -> providerReader.page(request, signal) }, tables)
    }
    @After fun cleanup() { reader.close(); provider.db.close() }

    private fun exchange(request: JsonObject): JsonObject {
        val decoded = LocalHealthProtocol.read(ByteArrayInputStream(LocalHealthProtocol.frame(request)))!!
        val reply = reader.handle(decoded, CancellationSignal())
        return LocalHealthProtocol.read(ByteArrayInputStream(LocalHealthProtocol.frame(reply)))!!
    }
    private val remote = LocalHealthRemote { link ->
        object : LocalHealthConnection {
            override val servesAllTables = true
            override val supportedTables = tables.toSet()
            override suspend fun page(table: LocalHealthTable, checkpoint: LocalHealthCheckpoint): LocalHealthPage {
                val page = LocalHealthProtocol.page(exchange(LocalHealthProtocol.changes(table, link.source.localUserId,
                    checkpoint.run!!, checkpoint.full, checkpoint.window.takeIf { windowed })))
                transmitted += page.rows.size
                return page
            }
            override suspend fun acknowledge(table: LocalHealthTable, page: LocalHealthPage) {
                if (dropAck && page.rows.isNotEmpty()) {
                    dropAck = false
                    // The pending batch survives a killed watch process before its ACK was handled.
                    reader.close(); reopenWatch(); throw IOException("synthetic lost ack")
                }
                assertTrue(exchange(LocalHealthProtocol.acknowledge(link.source.localUserId, table, page.batchId!!)).flag("committed") == true)
                acknowledged++
            }
            override fun close() = Unit
        }
    }
    private suspend fun CoroutineScope.sync(store: LocalHealthStore, mode: LocalHealthSyncMode = LocalHealthSyncMode.INCREMENTAL): LocalHealthSyncState {
        val engine = LocalHealthSyncEngine(this, store, remote, retryDelayMillis = 0, tables = tables, now = { now })
        return try {
            engine.attach(LocalHealthLink(source, 1)); engine.sync(mode)
            withTimeout(15_000) { engine.state.first { it.phase in setOf(LocalHealthSyncPhase.COMPLETED,
                LocalHealthSyncPhase.PARTIAL, LocalHealthSyncPhase.FAILED, LocalHealthSyncPhase.UNAVAILABLE) } }
        } finally { engine.close() }
    }
    private suspend fun SQLiteLocalHealthStore.raw(id: Long) = records(HealthRecordKind.POINT,
        HealthRecordQuery(source, recordKey = id.toString(), includeDeleted = true)).items.single()

    @Test fun initialBaselineThenNoChangeDeltaOldRowMutationAndFullRescan() = runBlocking {
        repeat(270) { provider.insertFixture(it + 1L, "75", 1) }
        SQLiteDataSyncStore(context, "incremental-phone.sqlite").use { database ->
            val phone = SQLiteLocalHealthStore(database, now = { now })
            assertEquals(270, sync(phone).recordsCommitted)
            val savedAt = phone.raw(1).updatedAtMillis
            now += HOUR
            assertEquals(0, sync(phone).recordsCommitted)
            assertEquals(savedAt, phone.raw(1).updatedAtMillis) // Unchanged rows are not written again.
            provider.db.execSQL("UPDATE data_point SET `values`='78',deleted=1 WHERE _id=260")
            provider.insertFixture(271, "98", 28)
            assertEquals(1, sync(phone).recordsCommitted) // New rows don't wait for the hourly check.
            now += HOUR
            assertEquals(1, sync(phone).recordsCommitted)
            assertTrue(phone.raw(260).deleted); assertTrue("78" in phone.raw(260).json)
            assertEquals(1, phone.points(HealthPointQuery(source, typeCode = 28)).items.size)
            val full = sync(phone, LocalHealthSyncMode.FULL)
            assertEquals(LocalHealthSyncPhase.COMPLETED, full.phase); assertEquals(LocalHealthSyncMode.FULL, full.mode)
            assertEquals(271, full.recordsCommitted)
            assertEquals(0, sync(phone).recordsCommitted)
            assertEquals(0, provider.mutations)
        }
    }

    @Test fun aFirstSyncLeavesOlderPointsOnTheWatchUntilEverythingIsAskedFor() = runBlocking {
        windowed = true
        repeat(300) { provider.insertFixture(it + 1L, "75", 1) }
        provider.db.execSQL("UPDATE data_point SET time_from=?,time_to=? WHERE _id>250", arrayOf(now - HOUR, now))
        SQLiteDataSyncStore(context, "window-phone.sqlite").use { database ->
            val phone = SQLiteLocalHealthStore(database, now = { now })
            val first = sync(phone)
            assertEquals(50, first.recordsCommitted)
            assertEquals(now - 3 * DAY, first.deferredBeforeMillis)
            now += HOUR // The hourly checks must not bring the older rows in a few at a time.
            provider.db.execSQL("UPDATE data_point SET `values`='80' WHERE _id=5")
            provider.insertFixture(301, "90", 1)
            provider.db.execSQL("UPDATE data_point SET time_from=?,time_to=? WHERE _id=301", arrayOf(now - HOUR, now))
            val next = sync(phone)
            assertEquals(1, next.recordsCommitted); assertNotNull(next.deferredBeforeMillis)
            val full = sync(phone, LocalHealthSyncMode.FULL)
            assertEquals(301, full.recordsCommitted); assertNull(full.deferredBeforeMillis)
            assertEquals("80", phone.points(HealthPointQuery(source, recordKey = "5")).items.single().rawValues)
            now += HOUR
            val after = sync(phone)
            assertEquals(0, after.recordsCommitted); assertNull(after.deferredBeforeMillis)
        }
    }

    @Test fun aWatchWithOnlyRecentPointsSendsThemAllWithoutLeavingAnythingBehind() = runBlocking {
        windowed = true
        repeat(3) { provider.insertFixture(it + 1L, "75", 1) }
        provider.db.execSQL("UPDATE data_point SET time_from=?,time_to=?", arrayOf(now - HOUR, now))
        SQLiteDataSyncStore(context, "recent-phone.sqlite").use { database ->
            val first = sync(SQLiteLocalHealthStore(database, now = { now }))
            assertEquals(3, first.recordsCommitted); assertNull(first.deferredBeforeMillis)
        }
    }

    @Test fun lostAckWatchRestartAndPhoneRestartPreserveIdempotentProgress() = runBlocking {
        repeat(140) { provider.insertFixture(it + 1L, "73", 1) }
        dropAck = true
        var database = SQLiteDataSyncStore(context, "restart-phone.sqlite")
        try {
            assertEquals(LocalHealthSyncPhase.COMPLETED, sync(SQLiteLocalHealthStore(database)).phase)
            assertEquals(268, transmitted) // First 128-row batch was retransmitted once.
            assertEquals(140, SQLiteLocalHealthStore(database).records(HealthRecordKind.POINT, HealthRecordQuery(source, limit = 500)).items.size)
            database.close(); database = SQLiteDataSyncStore(context, "restart-phone.sqlite")
            assertEquals(0, sync(SQLiteLocalHealthStore(database)).recordsCommitted)
            // A fresh phone DB with the same profile must rebuild a baseline despite the watch ACKs.
            SQLiteDataSyncStore(context, "rebuilt-phone.sqlite").use {
                assertEquals(140, sync(SQLiteLocalHealthStore(it)).recordsCommitted)
            }
        } finally { database.close() }
    }

    @Test fun failedPhoneTransactionNeverConfirmsAndPendingRowsCanBeRetried() = runBlocking {
        provider.insertFixture(1, "72", 1)
        SQLiteDataSyncStore(context, "failed-phone.sqlite").use { database ->
            val phone = SQLiteLocalHealthStore(database)
            phone.begin(source, LocalHealthTable.POINT)
            val path = File(context.noBackupFilesDir, "failed-phone.sqlite").path
            SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("CREATE TRIGGER failed_insert BEFORE INSERT ON local_health_records BEGIN SELECT RAISE(ABORT,'fixture'); END")
            }
            assertEquals(LocalHealthSyncPhase.FAILED, sync(phone).phase)
            // Empty SESSION was acknowledged; the failing POINT batch was not.
            assertEquals(1, acknowledged)
            assertTrue(phone.records(HealthRecordKind.POINT, HealthRecordQuery(source)).items.isEmpty())
            SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("DROP TRIGGER failed_insert") }
            assertEquals(LocalHealthSyncPhase.COMPLETED, sync(phone).phase)
            assertEquals("72", phone.points(HealthPointQuery(source)).items.single().rawValues)
        }
    }

    @Test fun sleepReopenMergeUnknownColumnsAndSourceRetentionPreservePhoneArchive() = runBlocking {
        provider.insertSession(1, "sleep-a", now - 10 * DAY, now - 9 * DAY)
        provider.insertFixture(1, "6,8", 2)
        provider.db.execSQL("UPDATE data_point SET time_from=?,time_to=?", arrayOf(now - 10 * DAY, now - 9 * DAY))
        SQLiteDataSyncStore(context, "sleep-delta.sqlite").use { database ->
            val phone = SQLiteLocalHealthStore(database)
            sync(phone)
            // A very old session reopens; its old point range becomes a priority despite its age.
            provider.db.execSQL("UPDATE data_session SET time_to=0 WHERE id='sleep-a'")
            provider.db.execSQL("UPDATE data_point SET `values`='6,10' WHERE _id=1")
            val reopened = sync(phone)
            assertEquals(2, reopened.recordsCommitted)
            assertEquals(0L, phone.sleepRecords(HealthSleepQuery(source)).items.single().endMillis)
            provider.db.execSQL("ALTER TABLE data_session ADD COLUMN `future\"field` BLOB")
            provider.db.execSQL("UPDATE data_session SET `future\"field`=X'050607',time_to=?", arrayOf(now))
            sync(phone)
            val session = phone.records(HealthRecordKind.SESSION, HealthRecordQuery(source)).items.single()
            val raw = LocalHealthProtocol.parse(session.json.toByteArray())
            assertEquals("BQYH", raw["columns"]!!.jsonObject.text("future\"field"))
            assertEquals("blob-base64", raw["storageTypes"]!!.jsonObject.text("future\"field"))
            provider.db.execSQL("DELETE FROM data_session")
            provider.db.execSQL("DELETE FROM data_point")
            sync(phone, LocalHealthSyncMode.FULL)
            assertTrue(phone.raw(1).json.contains("\"sourceAbsent\":true"))
            assertTrue(phone.raw(1).json.contains("6,10")); assertFalse(phone.raw(1).deleted)
            assertTrue(phone.records(HealthRecordKind.SESSION, HealthRecordQuery(source)).items.single().json.contains("sourceAbsent"))
        }
    }

    @Test fun acknowledgementOfOlderRevisionCannotClearAQueuedNewerRevision() {
        val table = LocalHealthTable.POINT; val receiver = "receiver"; val run = UUID.randomUUID().toString()
        fun observation(value: String) = LocalHealthPage(listOf(LocalHealthRow(buildJsonObject {
            put("id", "1"); put("columns", buildJsonObject { put("_id", 1); put("values", value) })
        })), 1, 1, true)
        var capture = ledger.begin(receiver, table, run, true)
        capture = ledger.observe(receiver, table, capture, observation("first"))
        val first = ledger.batch(receiver, table, capture)
        capture = ledger.begin(receiver, table, "later-run", true)
        capture = ledger.observe(receiver, table, capture, observation("second"))
        ledger.acknowledge(receiver, table, first.text("batch")!!)
        val next = ledger.batch(receiver, table, capture)
        assertEquals("second", LocalHealthProtocol.page(next).rows.single().text("values"))
        ledger.acknowledge(receiver, table, first.text("batch")!!)
        assertEquals(next, ledger.batch(receiver, table, capture))
    }

    @Test fun persistedOutboxIsDeliveredEvenIfTheNextSourceReadIsDenied() {
        val table = LocalHealthTable.POINT
        var capture = ledger.begin("receiver", table, "run", true)
        capture = ledger.observe("receiver", table, capture, LocalHealthPage(listOf(LocalHealthRow(buildJsonObject {
            put("id", "1"); put("columns", buildJsonObject { put("_id", 1); put("values", "73") })
        })), 1, 2, false))
        assertFalse(capture.done) // Crash before batch creation: source cursor and pending row survived.
        var queried = false
        val denied = IncrementalHealthReader(ledger, { _, _ -> queried = true; throw SecurityException("fixture") }, listOf(table))
        val page = LocalHealthProtocol.page(denied.handle(LocalHealthProtocol.changes(table, "receiver", "run", true), CancellationSignal()))
        assertNull(page.error); assertEquals("73", page.rows.single().text("values")); assertFalse(queried)
    }

    @Test fun incompleteObservationPreservesLastReadableCellsButRealNullIsAnUpdate() = runBlocking {
        SQLiteDataSyncStore(context, "partial-phone.sqlite").use { database ->
            val phone = SQLiteLocalHealthStore(database)
            suspend fun save(revision: Int, columns: JsonObject, partial: Boolean = false) {
                val raw = buildJsonObject {
                    put("id", "1"); put("columns", columns); put("bridgeEpoch", "epoch"); put("bridgeRevision", revision)
                    if (partial) { put("partial", true); put("unavailableColumns", JsonArray(listOf(JsonPrimitive("values")))) }
                }
                phone.commit(source, LocalHealthTable.POINT, LocalHealthPage(listOf(LocalHealthRow(raw)), revision.toLong(), null, true), LocalHealthCheckpoint(inProgress = false))
            }
            save(1, buildJsonObject { put("_id", 1); put("type", 1); put("values", "72"); put("future", "retained") })
            save(2, buildJsonObject { put("_id", 1); put("type", 1) }, partial = true)
            assertEquals("72", phone.points(HealthPointQuery(source)).items.single().rawValues)
            assertTrue("retained" in phone.raw(1).json)
            save(3, buildJsonObject { put("_id", 1); put("type", 1); put("values", JsonNull) })
            assertNull(phone.points(HealthPointQuery(source)).items.single().rawValues)
            assertFalse("retained" in phone.raw(1).json)
        }
    }

    @Test fun pagesWithNothingToDeliverShareOneRequest() {
        val table = LocalHealthTable.POINT
        var reads = 0
        val source = IncrementalHealthReader(ledger, { _, _ ->
            reads++
            localHealthPage(emptyList(), reads.toLong(), 3, reads == 3, null)
        }, listOf(table))
        val page = LocalHealthProtocol.page(source.handle(LocalHealthProtocol.changes(table, "receiver", "run", true), CancellationSignal()))
        assertEquals(3, reads); assertTrue(page.complete)
    }

    @Test fun unchangedFitnessFilesAreNotReadAgainUntilTheyChange() {
        val table = LocalHealthTable.FITNESS_RECORD
        var stamp: String? = "first"; var reads = 0; var stamps = 0
        val source = IncrementalHealthReader(ledger, { _, _ -> reads++; localHealthPage(emptyList(), null, null, true, null) },
            listOf(table, LocalHealthTable.FITNESS_POINT), { stamps++; stamp })
        fun round(run: String, tableInRound: LocalHealthTable = table, full: Boolean = false) {
            val page = source.handle(LocalHealthProtocol.changes(tableInRound, "receiver", run, full), CancellationSignal())
            source.handle(LocalHealthProtocol.acknowledge("receiver", tableInRound, page.text("batch")!!), CancellationSignal())
        }
        round("baseline", full = true); assertEquals(1, reads)
        round("same files"); assertEquals(1, reads)
        stamp = "second"; round("new files"); assertEquals(2, reads)
        round("same again"); assertEquals(2, reads)
        round("asked for everything", full = true); assertEquals(3, reads)
        stamp = null; round("cannot tell"); assertEquals(4, reads)
        val before = stamps
        round("two tables"); round("two tables", LocalHealthTable.FITNESS_POINT, full = true)
        assertEquals(before + 1, stamps) // One file, so one look at it per run.
    }

    @Test fun observingANewColumnReconcilesRowsBeyondTheCurrentAuditWindow() = runBlocking {
        repeat(400) { provider.insertFixture(it + 1L, "74", 1) }
        SQLiteDataSyncStore(context, "schema-phone.sqlite").use { database ->
            val phone = SQLiteLocalHealthStore(database)
            sync(phone)
            provider.db.execSQL("ALTER TABLE data_point ADD COLUMN future_sensor INTEGER")
            provider.db.execSQL("UPDATE data_point SET future_sensor=9007199254740993")
            now += HOUR // Rows this old are next read by the hourly check, which reveals the column.
            sync(phone)
            val last = LocalHealthProtocol.parse(phone.raw(400).json.toByteArray())
            assertEquals(9007199254740993L, last["columns"]!!.jsonObject.number("future_sensor"))
            assertEquals(0, sync(phone).recordsCommitted)
        }
    }
    companion object {
        private const val DAY = 86_400_000L
        private const val HOUR = 3_600_000L
    }
}
