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
    /** Whether ACKs ride on the next request, replies may be compressed and the watch reads ahead, as in production. */
    private var streamlined = false
    private var held: String? = null
    private var zlibFrames = 0
    /** Small pages let a few hundred rows cross page boundaries. */
    private var pageRows = 128
    /** Source rows the watch read for the last POINT capture. */
    private var pointScans = 0L
    /** Whether the phone side is the production connection over frames, which asks ahead when the watch says it may. */
    private var framed = false
    /** Whether this watch's `ready` says it takes requests ahead. */
    private var pipelineReady = true
    private var aheadRequests = 0

    @Before fun setup() {
        provider = LocalHealthPipelineTest.ReadOnlyFixtureProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = HealthProviderReader.URI.authority; exported = true })
        ShadowContentResolver.registerProviderInternal(HealthProviderReader.URI.authority!!, provider)
        reopenWatch()
    }
    private fun reopenWatch() {
        ledger = HealthIncrementalStore(context, now = { now }, pageRows = pageRows)
        val providerReader = HealthProviderReader(context.contentResolver, "synthetic")
        reader = IncrementalHealthReader(ledger, { request, signal -> providerReader.page(request, signal) }, tables)
    }
    @After fun cleanup() { reader.close(); provider.db.close() }

    private fun exchange(request: JsonObject): JsonObject {
        val decoded = LocalHealthProtocol.read(ByteArrayInputStream(LocalHealthProtocol.frame(request)))!!
        val reply = reader.handle(decoded, CancellationSignal(), deferAck = true)
        val wire = LocalHealthProtocol.frame(reply, zlib = decoded.text("compression") == LocalHealthProtocol.ZLIB)
        if (LocalHealthProtocol.header(java.nio.ByteBuffer.wrap(wire, 0, 4).int).second) zlibFrames++
        // As HealthWakeService does once the reply is written.
        reader.afterReply()
        if (streamlined && decoded.text("op") == "changes" && reply.text("error") == null && reply.flag("complete") != true)
            reader.prefetch(decoded, CancellationSignal())
        return LocalHealthProtocol.read(ByteArrayInputStream(wire))!!
    }
    /** The phone's own connection class, its frames carried to [exchange] and back in odd-sized pieces. */
    private val framedRemote = LocalHealthRemote { link ->
        val chunks = ArrayDeque<ByteArray>()
        val frames = LocalHealthFrames(object : DuplexTransport {
            override suspend fun read() = chunks.removeFirstOrNull()
            override suspend fun write(bytes: ByteArray) {
                val sent = LocalHealthProtocol.read(ByteArrayInputStream(bytes))!!
                // As the other phone side here, the first-sync window goes on the wire only when a test asks for it.
                val request = if (windowed) sent else JsonObject(sent - "window")
                if (request.text("after") != null) aheadRequests++
                if (request.text("op") == "ack") acknowledged++
                val reply = exchange(request)
                transmitted += (reply["rows"] as? JsonArray)?.size ?: 0
                LocalHealthProtocol.frame(reply, zlib = request.text("compression") == LocalHealthProtocol.ZLIB).toList().chunked(997)
                    .forEach { chunks.addLast(it.toByteArray()) }
            }
            override fun close() = chunks.clear()
        })
        val ready = JsonObject(LocalHealthProtocol.ready(tables) + if (pipelineReady)
            mapOf("pipeline" to JsonPrimitive(LocalHealthProtocol.PIPELINE_VERSION)) else emptyMap())
        LocalHealthFrameConnection(frames, ready, link.source.localUserId, onClose = { chunks.clear() })
    }
    private val remote = LocalHealthRemote { link ->
        object : LocalHealthConnection {
            override val servesAllTables = true
            override val supportedTables = tables.toSet()
            override suspend fun page(table: LocalHealthTable, checkpoint: LocalHealthCheckpoint): LocalHealthPage {
                val ack = held; held = null
                val page = LocalHealthProtocol.page(exchange(LocalHealthProtocol.changes(table, link.source.localUserId,
                    checkpoint.run!!, checkpoint.full, checkpoint.window.takeIf { windowed }, ack, zlib = streamlined)))
                transmitted += page.rows.size
                if (table == LocalHealthTable.POINT) pointScans = page.scannedRows
                return page
            }
            override suspend fun acknowledge(table: LocalHealthTable, page: LocalHealthPage) {
                if (streamlined && page.inlineAck && !page.complete) { held = page.batchId; return }
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
        val engine = LocalHealthSyncEngine(this, store, if (framed) framedRemote else remote, retryDelayMillis = 0, tables = tables, now = { now })
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

    @Test fun acksOnTheNextRequestCompressedRepliesAndReadingAheadLoseAndRepeatNothing() = runBlocking {
        streamlined = true
        repeat(300) { provider.insertFixture(it + 1L, (60 + it % 40).toString(), 1) }
        SQLiteDataSyncStore(context, "streamlined-phone.sqlite").use { database ->
            val phone = SQLiteLocalHealthStore(database, now = { now })
            assertEquals(300, sync(phone).recordsCommitted)
            assertEquals(300, transmitted) // No batch came twice.
            assertEquals(2, acknowledged) // Only the last page of each table needed an ACK of its own.
            assertTrue(zlibFrames > 0)
            assertEquals(0L, ledger.pendingCount(source.localUserId, LocalHealthTable.POINT))
            assertEquals(300, phone.records(HealthRecordKind.POINT, HealthRecordQuery(source, limit = 500)).items.size)
            now += HOUR
            assertEquals(0, sync(phone).recordsCommitted)
        }
    }

    @Test fun aPhoneThatAsksAheadGetsEachPageOnceWhileSavingTheOneBefore() = runBlocking {
        framed = true; streamlined = true
        repeat(300) { provider.insertFixture(it + 1L, (60 + it % 40).toString(), 1) }
        SQLiteDataSyncStore(context, "pipelined-phone.sqlite").use { database ->
            val phone = SQLiteLocalHealthStore(database, now = { now })
            assertEquals(300, sync(phone).recordsCommitted)
            assertEquals(300, transmitted)
            // The second and third POINT pages were asked for as the one before each arrived.
            assertEquals(2, aheadRequests)
            assertEquals(2, acknowledged) // Only the last page of each table needed an ACK of its own.
            assertEquals(0L, ledger.pendingCount(source.localUserId, LocalHealthTable.POINT))
            now += HOUR
            assertEquals(0, sync(phone).recordsCommitted)
        }
    }

    @Test fun aPageThatFailsToSaveAfterTheNextWasAskedForIsSentAgainInOrder() = runBlocking {
        framed = true; streamlined = true
        repeat(300) { provider.insertFixture(it + 1L, "70", 1) }
        SQLiteDataSyncStore(context, "pipelined-failed-phone.sqlite").use { database ->
            val phone = SQLiteLocalHealthStore(database, now = { now })
            phone.begin(source, LocalHealthTable.POINT)
            val path = File(context.noBackupFilesDir, "pipelined-failed-phone.sqlite").path
            SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("CREATE TRIGGER failed_insert BEFORE INSERT ON local_health_records WHEN NEW.record_key='200' BEGIN SELECT RAISE(ABORT,'fixture'); END")
            }
            assertEquals(LocalHealthSyncPhase.FAILED, sync(phone).phase)
            assertEquals(128, phone.records(HealthRecordKind.POINT, HealthRecordQuery(source, limit = 500)).items.size)
            SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("DROP TRIGGER failed_insert") }
            // The failed page and the one asked for after it come again, in their order; the first isn't sent twice.
            val retried = sync(phone)
            assertEquals(LocalHealthSyncPhase.COMPLETED, retried.phase); assertEquals(172, retried.recordsCommitted)
            assertEquals(300 + 172, transmitted)
            assertEquals(300, phone.records(HealthRecordKind.POINT, HealthRecordQuery(source, limit = 500)).items.size)
            assertEquals(0L, ledger.pendingCount(source.localUserId, LocalHealthTable.POINT))
        }
    }

    @Test fun aPhoneThatAsksAheadReadsAnOlderBridgeOnePageAtATime() = runBlocking {
        framed = true; pipelineReady = false
        repeat(300) { provider.insertFixture(it + 1L, "70", 1) }
        SQLiteDataSyncStore(context, "older-bridge-phone.sqlite").use { database ->
            assertEquals(300, sync(SQLiteLocalHealthStore(database, now = { now })).recordsCommitted)
            assertEquals(0, aheadRequests); assertEquals(300, transmitted); assertEquals(2, acknowledged)
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
        val first = ledger.batch(receiver, table)
        capture = ledger.begin(receiver, table, "later-run", true)
        capture = ledger.observe(receiver, table, capture, observation("second"))
        ledger.acknowledge(receiver, table, first.text("batch")!!)
        val next = ledger.batch(receiver, table)
        assertEquals("second", LocalHealthProtocol.page(next).rows.single().text("values"))
        ledger.acknowledge(receiver, table, first.text("batch")!!)
        assertEquals(next.toString(), ledger.batch(receiver, table).toString())
    }

    @Test fun batchesMadeAheadGoInOrderAndAnAckClearsEveryOneBeforeIt() {
        val table = LocalHealthTable.POINT; val receiver = "receiver"
        fun rows(ids: IntRange, complete: Boolean) = LocalHealthPage(ids.map { id -> LocalHealthRow(buildJsonObject {
            put("id", id.toString()); put("columns", buildJsonObject { put("_id", id); put("values", "v$id") })
        }) }, ids.lastOrNull()?.toLong() ?: 300, null, complete)
        fun ids(batch: JsonObject) = LocalHealthProtocol.page(batch).rows.map { it.id!!.toInt() }
        var capture = ledger.begin(receiver, table, "run", true)
        capture = ledger.observe(receiver, table, capture, rows(1..300, complete = false))
        assertTrue(ledger.packAhead(receiver, table)); assertTrue(ledger.packAhead(receiver, table))
        assertFalse(ledger.packAhead(receiver, table)) // Less than a page is queued, and reading goes on.
        val first = ledger.batch(receiver, table)
        assertEquals((1..128).toList(), ids(first))
        val second = ledger.batch(receiver, table, after = first.text("batch"))
        assertEquals((129..256).toList(), ids(second))
        val third = ledger.batch(receiver, table, after = second.text("batch"))
        assertEquals((257..300).toList(), ids(third)); assertFalse(third.flag("complete")!!)
        assertFalse(ledger.packAhead(receiver, table)) // Three wait for their ACKs.
        // Reading ends with nothing new: the last batch made is now the end.
        ledger.observe(receiver, table, capture, rows(IntRange.EMPTY, complete = true))
        val ending = ledger.batch(receiver, table, after = second.text("batch"))
        assertEquals(third.text("batch"), ending.text("batch")); assertTrue(ending.flag("complete")!!)
        ledger.acknowledge(receiver, table, second.text("batch")!!)
        assertEquals(44L, ledger.pendingCount(receiver, table))
        // Without `after`, or after one already acknowledged, the oldest waiting goes first.
        assertEquals(third.text("batch"), ledger.batch(receiver, table).text("batch"))
        assertEquals(third.text("batch"), ledger.batch(receiver, table, after = first.text("batch")).text("batch"))
        ledger.acknowledge(receiver, table, third.text("batch")!!)
        assertEquals(0L, ledger.pendingCount(receiver, table))
        assertEquals(emptyList(), ids(ledger.batch(receiver, table)))
    }

    @Test fun anOutboxFromTheFirstLedgerSchemaIsDeliveredAndClearedByItsAck() {
        val point = LocalHealthTable.POINT.wireName; val session = LocalHealthTable.SESSION.wireName
        fun row(key: String, revision: Int) = """{"id":"$key","columns":{"_id":"$key"},"bridgeRevision":"$revision","bridgeEpoch":"e","bridgeKey":"$key"}"""
        fun batch(table: String, id: String, vararg rows: String) =
            """{"op":"changes","version":3,"table":"$table","rows":[${rows.joinToString(",")}],"batch":"$id","nextId":"4","complete":false,"inlineAck":true}"""
        SQLiteDatabase.openOrCreateDatabase(File(context.noBackupFilesDir, "v1-ledger.sqlite"), null).use { db ->
            // The schema 0.2.0 through 1.0.8 created.
            db.execSQL("CREATE TABLE metadata (name TEXT PRIMARY KEY,value TEXT NOT NULL)")
            db.execSQL("CREATE TABLE observations (receiver TEXT NOT NULL,kind TEXT NOT NULL,row_key TEXT NOT NULL,source_id INTEGER,parent_id INTEGER,fingerprint TEXT," +
                "present INTEGER NOT NULL,last_changed INTEGER NOT NULL,seen TEXT,payload TEXT,PRIMARY KEY(receiver,kind,row_key))")
            db.execSQL("CREATE INDEX observed_range ON observations(receiver,kind,source_id)")
            db.execSQL("CREATE INDEX observed_parent ON observations(receiver,kind,parent_id)")
            db.execSQL("CREATE TABLE pending (revision INTEGER PRIMARY KEY AUTOINCREMENT,receiver TEXT NOT NULL,kind TEXT NOT NULL," +
                "row_key TEXT NOT NULL,payload TEXT NOT NULL,UNIQUE(receiver,kind,row_key))")
            db.execSQL("CREATE TABLE batches (receiver TEXT NOT NULL,kind TEXT NOT NULL,batch_id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(receiver,kind))")
            db.execSQL("CREATE TABLE progress (receiver TEXT NOT NULL,kind TEXT NOT NULL,baseline INTEGER NOT NULL DEFAULT 0,audit_after INTEGER NOT NULL DEFAULT 0," +
                "step INTEGER NOT NULL DEFAULT 0,capture TEXT,schema_fields TEXT,PRIMARY KEY(receiver,kind))")
            db.execSQL("CREATE TABLE children (receiver TEXT NOT NULL,parent_id INTEGER NOT NULL,last_checked INTEGER NOT NULL,same_count INTEGER NOT NULL,PRIMARY KEY(receiver,parent_id))")
            db.execSQL("INSERT INTO metadata VALUES ('epoch','e')")
            listOf(point to "1", point to "2", point to "3", session to "s").forEach { (kind, key) ->
                db.execSQL("INSERT INTO pending(receiver,kind,row_key,payload) VALUES ('r',?,?,?)", arrayOf(kind, key, """{"id":"$key"}"""))
            }
            // A POINT batch made by 1.0.6 or later, with its bound, and a SESSION one made before bounds were kept.
            db.execSQL("INSERT INTO batches VALUES ('r',?,'old-point',?)", arrayOf(point, batch(point, "old-point", row("1", 1), row("2", 2))))
            db.execSQL("INSERT INTO metadata VALUES (?,'old-point 2')", arrayOf("batch_bound:r:$point"))
            db.execSQL("INSERT INTO batches VALUES ('r',?,'old-session',?)", arrayOf(session, batch(session, "old-session", row("s", 4))))
            db.version = 1
        }
        HealthIncrementalStore(context, "v1-ledger.sqlite", pageRows = pageRows).use { store ->
            assertEquals("old-point", store.batch("r", LocalHealthTable.POINT).text("batch"))
            store.acknowledge("r", LocalHealthTable.POINT, "old-point")
            assertEquals(1L, store.pendingCount("r", LocalHealthTable.POINT))
            assertEquals(listOf("3"), LocalHealthProtocol.page(store.batch("r", LocalHealthTable.POINT)).rows.map { it.text("bridgeKey") ?: it.raw.text("bridgeKey") })
            // Nothing is made past a batch that names its rows only in its payload, until its ACK.
            assertEquals("old-session", store.batch("r", LocalHealthTable.SESSION, after = "old-session").text("batch"))
            assertFalse(store.packAhead("r", LocalHealthTable.SESSION))
            store.acknowledge("r", LocalHealthTable.SESSION, "old-session")
            assertEquals(0L, store.pendingCount("r", LocalHealthTable.SESSION))
        }
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

    @Test fun aRowTooLargeToDeliverGoesOnceWithoutItsLargestCellAndHoldsNothingBack() = runBlocking {
        // As in production, where incremental reads don't measure rows against a frame.
        val unframed = HealthProviderReader(context.contentResolver, "synthetic", framed = false)
        reader.close(); reader = IncrementalHealthReader(ledger, { request, signal -> unframed.page(request, signal) }, tables)
        val huge = "x".repeat(MAX_ROW_BYTES)
        provider.insertFixture(1, "70", 1); provider.insertFixture(2, huge, 1); provider.insertFixture(3, "72", 1)
        SQLiteDataSyncStore(context, "oversized-phone.sqlite").use { database ->
            val phone = SQLiteLocalHealthStore(database, now = { now })
            val first = sync(phone)
            assertEquals(LocalHealthSyncPhase.COMPLETED, first.phase); assertEquals(3, first.recordsCommitted)
            val point = phone.points(HealthPointQuery(source)).items.single { it.providerId == 2L }
            assertNull(point.rawValues); assertTrue(HealthParseIssue.PARTIAL_SOURCE_ROW in point.issues)
            assertTrue("oversizedColumns" in point.raw.json)
            now += HOUR
            assertEquals(0, sync(phone).recordsCommitted) // Settled, so it isn't sent again.
            // A row the phone already holds keeps the cell it can no longer receive.
            provider.db.execSQL("UPDATE data_point SET `values`=? WHERE _id=1", arrayOf(huge))
            provider.insertFixture(4, "73", 1)
            now += HOUR
            assertEquals(2, sync(phone).recordsCommitted)
            assertEquals("70", phone.points(HealthPointQuery(source)).items.single { it.providerId == 1L }.rawValues)
        }
    }

    @Test fun aPointNoCursorCanHoldGoesWithoutItsLargestCellInsteadOfStoppingTheTable() = runBlocking {
        val unframed = HealthProviderReader(context.contentResolver, "synthetic", framed = false)
        reader.close(); reader = IncrementalHealthReader(ledger, { request, signal -> unframed.page(request, signal) }, tables)
        provider.insertFixture(1, "70", 1); provider.insertFixture(2, "x".repeat(3 * 1024 * 1024), 1); provider.insertFixture(3, "72", 1)
        SQLiteDataSyncStore(context, "cursor-oversized-phone.sqlite").use { database ->
            val phone = SQLiteLocalHealthStore(database, now = { now })
            val first = sync(phone)
            assertEquals(LocalHealthSyncPhase.COMPLETED, first.phase); assertEquals(3, first.recordsCommitted)
            val point = phone.points(HealthPointQuery(source)).items.single { it.providerId == 2L }
            assertNull(point.rawValues); assertTrue("oversizedColumns" in point.raw.json)
            now += HOUR
            assertEquals(0, sync(phone).recordsCommitted)
        }
    }

    @Test fun aTooLargeRowQueuedBeforeRowsWereBoundedIsDeliveredWithoutItsLargestCell() = runBlocking {
        fun row(id: Int, value: String) = buildJsonObject {
            put("id", id.toString()); put("columns", buildJsonObject { put("_id", id); put("type", 1); put("values", value) })
            put("storageTypes", buildJsonObject { put("_id", "integer"); put("type", "integer"); put("values", "string") })
        }
        // Rows straight from a source that doesn't bound them, as 1.0.6 queued them.
        val rows = listOf(row(1, "x".repeat(LocalHealthProtocol.MAX_FRAME_BYTES)), row(2, "72"))
        reader.close(); reader = IncrementalHealthReader(ledger, { request, _ ->
            if (request.text("table") != LocalHealthTable.POINT.wireName) localHealthPage(emptyList(), null, null, true, null)
            else rows.filter { it.number("id")!! > (request.number("afterId") ?: 0) }.let { left ->
                localHealthPage(left, left.lastOrNull()?.number("id") ?: request.number("afterId"), 2, true, "synthetic")
            }
        }, tables)
        SQLiteDataSyncStore(context, "queued-oversized-phone.sqlite").use { database ->
            val phone = SQLiteLocalHealthStore(database, now = { now })
            val state = sync(phone)
            assertEquals(LocalHealthSyncPhase.COMPLETED, state.phase); assertEquals(2, state.recordsCommitted)
            assertTrue("oversizedColumns" in phone.raw(1).json); assertFalse("xxxx" in phone.raw(1).json)
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
    @Test fun oldSessionsFromTheFirstSyncDoNotMakeLaterSyncsRereadTheirPoints() = runBlocking {
        provider.insertSession(1, "sleep-old", now - 10 * DAY, now - 9 * DAY)
        for (id in 1L..200L) provider.db.execSQL("INSERT INTO data_point VALUES(?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(id, "60", "watch-id", "no-login-required", 1, now - 10 * DAY + id * 60_000, now - 10 * DAY + id * 60_000, 0, 0, null))
        SQLiteDataSyncStore(context, "old-session-phone.sqlite").use { database ->
            val phone = SQLiteLocalHealthStore(database)
            assertEquals(LocalHealthSyncPhase.COMPLETED, sync(phone).phase)
            now += 10 * 60_000L // Before the hourly check, so only new and recent rows are due.
            assertEquals(LocalHealthSyncPhase.COMPLETED, sync(phone).phase)
            assertEquals(0L, pointScans) // The session's points stay outside the recent range.
        }
    }
    companion object {
        private const val DAY = 86_400_000L
        private const val HOUR = 3_600_000L
    }
}
