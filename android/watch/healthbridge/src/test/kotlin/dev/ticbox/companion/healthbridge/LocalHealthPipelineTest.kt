package dev.ticbox.companion.healthbridge

import android.app.Application
import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.health.*
import dev.ticbox.companion.platform.data.SQLiteDataSyncStore
import dev.ticbox.companion.platform.health.SQLiteLocalHealthStore
import dev.ticbox.companion.protocol.*
import java.io.ByteArrayInputStream
import java.io.File
import java.util.ArrayDeque
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.*
import org.junit.Test

/** Real ContentResolver/Cursor -> framed fixture stream -> production phone engine -> real SQLite -> read API.
 * All row values, identities and intervals are synthetic. The fixture replaces only the radio/GMS wake;
 * this does not claim physical device interoperability. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalHealthPipelineTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val source = HealthRecordSource.LocalDevice("offline-profile", PeerAddress("AA:BB:CC:DD:EE:FF"))
    private lateinit var provider: ReadOnlyFixtureProvider
    @Before fun setup() {
        provider = ReadOnlyFixtureProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = HealthProviderReader.URI.authority; exported = true })
        ShadowContentResolver.registerProviderInternal(HealthProviderReader.URI.authority!!, provider)
    }
    @After fun cleanup() { provider.db.close() }

    private class FixtureRemote(private val reader: HealthProviderReader) : LocalHealthRemote {
        var pages = 0
        override suspend fun open(link: LocalHealthLink): LocalHealthConnection {
            val chunks = ArrayDeque<ByteArray>()
            val frames = LocalHealthFrames(object : DuplexTransport {
                override suspend fun read() = chunks.pollFirst()
                override suspend fun write(bytes: ByteArray) {
                    val request = LocalHealthProtocol.read(ByteArrayInputStream(bytes))!!
                    val response = if (request.text("op") == "hello") buildJsonObject { put("op", "ready") }
                    else { pages++; reader.page(request) }
                    // Split both headers and row payloads, as a real stream is free to do.
                    LocalHealthProtocol.frame(response).toList().chunked(83).forEach { chunks.addLast(it.toByteArray()) }
                }
                override fun close() { chunks.clear() }
            })
            frames.write(LocalHealthProtocol.hello("fixture-only")); assertEquals("ready", frames.read().text("op"))
            return object : LocalHealthConnection {
                override suspend fun page(table: LocalHealthTable, checkpoint: LocalHealthCheckpoint): LocalHealthPage {
                    frames.write(LocalHealthProtocol.request(table, checkpoint.afterId, checkpoint.upperId,
                        checkpoint.afterKey, checkpoint.upperKey))
                    return LocalHealthProtocol.page(frames.read())
                }
                override fun close() { chunks.clear() }
            }
        }
    }
    private suspend fun CoroutineScope.sync(store: SQLiteLocalHealthStore, remote: LocalHealthRemote): LocalHealthSyncState {
        val engine = LocalHealthSyncEngine(this, store, remote, retryDelayMillis = 0,
            tables = listOf(LocalHealthTable.POINT, LocalHealthTable.SESSION))
        return try {
            engine.attach(LocalHealthLink(source, 5))
            engine.sync() // Each call is an explicit sync, even right after the previous one.
            withTimeout(15_000) { engine.state.first { it.phase in setOf(LocalHealthSyncPhase.COMPLETED,
                LocalHealthSyncPhase.PARTIAL, LocalHealthSyncPhase.FAILED, LocalHealthSyncPhase.UNAVAILABLE) } }
        } finally { engine.close() }
    }

    @Test fun rawRowsReachPhoneQueriesPersistAcrossRestartAndRefreshOldIds() = runBlocking {
        repeat(270) { provider.insertFixture(it + 1L, "75,,raw-$it", it % 3) }
        provider.insertFixture(Long.MAX_VALUE, "unknown,unchanged", 999)
        val name = "pipeline.sqlite"
        var database = SQLiteDataSyncStore(context, name)
        val remote = FixtureRemote(HealthProviderReader(context.contentResolver, "fixture-version"))
        try {
            var store = SQLiteLocalHealthStore(database)
            val unavailable = store.records(HealthRecordKind.POINT, HealthRecordQuery(source))
            assertEquals(HealthRecordReadStatus.SOURCE_UNAVAILABLE, unavailable.status)
            val result = sync(store, remote)
            assertEquals(LocalHealthSyncPhase.COMPLETED, result.phase)
            assertEquals(271, result.recordsCommitted); assertEquals(4, remote.pages)
            val client = HealthRecordsEngine(deviceRecords = store)
            assertEquals(listOf(source), client.sources())
            val first = client.records(HealthRecordKind.POINT, HealthRecordQuery(source, limit = 200))
            assertEquals(200, first.items.size); assertEquals(200, first.nextOffset)
            assertEquals(71, client.records(HealthRecordKind.POINT, HealthRecordQuery(source, offset = 200)).items.size)
            val maximum = client.records(HealthRecordKind.POINT, HealthRecordQuery(source, recordKey = Long.MAX_VALUE.toString())).items.single()
            val raw = LocalHealthProtocol.parse(maximum.json.toByteArray())
            assertEquals(Long.MAX_VALUE, (raw["columns"] as JsonObject).number("_id"))
            assertEquals("unknown,unchanged", (raw["columns"] as JsonObject).text("values"))
            assertEquals("AQID", (raw["columns"] as JsonObject).text("future_blob"))
            assertEquals("blob-base64", (raw["storageTypes"] as JsonObject).text("future_blob"))
            assertEquals(HealthRecordReadStatus.AVAILABLE,
                client.records(HealthRecordKind.SESSION, HealthRecordQuery(source)).status)
            assertEquals(HealthRecordReadStatus.SOURCE_UNAVAILABLE,
                client.records(HealthRecordKind.POINT, HealthRecordQuery(source.copy(localUserId = "other-profile"))).status)
            client.close(); database.close()
            database = SQLiteDataSyncStore(context, name); store = SQLiteLocalHealthStore(database)
            assertEquals(271, store.records(HealthRecordKind.POINT, HealthRecordQuery(source, limit = 500)).items.size)
            provider.db.execSQL("UPDATE data_point SET `values`='revised,opaque',deleted=1 WHERE _id=1")
            assertEquals(LocalHealthSyncPhase.COMPLETED, sync(store, remote).phase)
            assertTrue(store.records(HealthRecordKind.POINT, HealthRecordQuery(source, recordKey = "1")).items.isEmpty())
            val revised = store.records(HealthRecordKind.POINT, HealthRecordQuery(source, recordKey = "1", includeDeleted = true)).items.single()
            assertTrue(revised.deleted); assertTrue("revised,opaque" in revised.json)
            assertEquals(90, store.records(HealthRecordKind.POINT, HealthRecordQuery(source, dataType = "2", limit = 500)).items.size)
            assertTrue(store.records(HealthRecordKind.POINT, HealthRecordQuery(source, fromMillis = 10_000)).items.isEmpty())
            assertEquals(0, provider.mutations)
        } finally { database.close() }
    }
    @Test fun rowAndCheckpointRollBackTogetherOnStorageFailure() = runBlocking {
        val name = "rollback.sqlite"
        SQLiteDataSyncStore(context, name).use { database ->
            val store = SQLiteLocalHealthStore(database)
            val one = LocalHealthPage(listOf(LocalHealthRow(buildJsonObject { put("id", "1") })), 1, 3, false)
            store.commit(source, LocalHealthTable.POINT, one, LocalHealthCheckpoint(1, 3))
            SQLiteDatabase.openDatabase(File(context.noBackupFilesDir, name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("CREATE TRIGGER disk_failure BEFORE INSERT ON local_health_records WHEN NEW.record_key='3' BEGIN SELECT RAISE(ABORT,'fixture'); END")
            }
            val failing = LocalHealthPage(listOf(2, 3).map { LocalHealthRow(buildJsonObject { put("id", it.toString()) }) }, 3, 3, true)
            assertFailsWith<android.database.sqlite.SQLiteException> { store.commit(source, LocalHealthTable.POINT, failing, LocalHealthCheckpoint(3, 3, false)) }
            assertEquals(1L, store.begin(source, LocalHealthTable.POINT).afterId)
            assertEquals(listOf("1"), store.records(HealthRecordKind.POINT, HealthRecordQuery(source)).items.map { it.key })
            assertEquals(listOf(1L), store.points(HealthPointQuery(source)).items.map { it.providerId })
        }
    }
    @Test fun syntheticRowsAreParsedInTransactionAndRemainQueryableAfterPhoneRestart() = runBlocking {
        provider.insertFixture(1, "6,0", 2); provider.insertFixture(2, "72", 1); provider.insertFixture(3, "0,6", 2)
        provider.db.execSQL("UPDATE data_point SET time_from=946684800000,time_to=946684860000 WHERE _id=1")
        provider.db.execSQL("UPDATE data_point SET time_from=946684890000,time_to=946684890000 WHERE _id=2")
        provider.db.execSQL("UPDATE data_point SET time_from=946684860000,time_to=946684920000 WHERE _id=3")
        val name = "synthetic-health.sqlite"
        SQLiteDataSyncStore(context, name).use { database ->
            val store = SQLiteLocalHealthStore(database)
            assertEquals(LocalHealthSyncPhase.COMPLETED,
                sync(store, FixtureRemote(HealthProviderReader(context.contentResolver))).phase)
            SQLiteDatabase.openDatabase(File(context.noBackupFilesDir, name).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT count(*) FROM local_health_records WHERE kind='POINT' AND parsed_payload IS NOT NULL", null).use {
                    assertTrue(it.moveToFirst()); assertEquals(3, it.getInt(0))
                }
            }
        }
        SQLiteDataSyncStore(context, name).use { database ->
            val store = SQLiteLocalHealthStore(database)
            HealthRecordsEngine(deviceRecords = store).use { client ->
                val query = HealthPointQuery(source, typeCode = 1, fromMillis = 946684890000, toMillis = 946684890000)
                val heart = client.points(query).items.single()
                assertEquals("72", heart.values.single().decimal); assertEquals("bpm", heart.values.single().unit)
                assertEquals(946684890000, heart.endMillis)
                assertTrue("72" in heart.raw.json)
                val motion = client.points(HealthPointQuery(source, typeCode = 2, limit = 1))
                assertEquals(HealthMotionState.OFF_WRIST, motion.items.single().motion!!.state)
                assertEquals(1, motion.nextOffset)
                val next = client.points(HealthPointQuery(source, typeCode = 2, offset = motion.nextOffset!!, limit = 1))
                assertEquals(HealthMotionState.OFF_WRIST, next.items.single().motion!!.nextState); assertNull(next.nextOffset)
                assertEquals(HealthRecordReadStatus.SOURCE_UNAVAILABLE, client.points(query.copy(source = source.copy(localUserId = "another"))).status)
                assertEquals(HealthRecordReadStatus.SOURCE_UNAVAILABLE, client.points(query.copy(source = source.copy(peer = PeerAddress("11:22:33:44:55:66")))).status)
                provider.db.execSQL("UPDATE data_point SET `values`='73',deleted=1 WHERE _id=2")
                sync(store, FixtureRemote(HealthProviderReader(context.contentResolver)))
                assertTrue(client.points(query).items.isEmpty())
                val deleted = client.points(query.copy(includeDeleted = true)).items.single()
                assertEquals(true, deleted.deleted); assertEquals("73", deleted.values.single().decimal)
            }
        }
        assertEquals(0, provider.mutations)
    }
    @Test fun sleepSessionsKeepTheirOwnIdentityAndRefreshInPlaceWithAssociatedMeasurements() = runBlocking {
        val uuid = "62e0ece2-6423-407e-913d-f01da2957380"
        provider.insertFixture(1, "10,8", 2)
        provider.insertFixture(2, "75", 1)
        provider.insertFixture(3, "98", 28)
        provider.insertFixture(4, "36.5", 32)
        provider.insertFixture(5, "14.5", 38)
        provider.insertFixture(6, "33.4", 42)
        provider.db.execSQL("UPDATE data_point SET time_from=150,time_to=150 WHERE _id>1")
        provider.insertSession(1, "1", 100, 200)
        provider.insertSession(2, uuid, 1000, 0, account = null)
        val name = "sleep-pipeline.sqlite"
        SQLiteDataSyncStore(context, name).use { database ->
            val store = SQLiteLocalHealthStore(database)
            assertEquals(LocalHealthSyncPhase.COMPLETED,
                sync(store, FixtureRemote(HealthProviderReader(context.contentResolver))).phase)
            HealthRecordsEngine(deviceRecords = store).use { client ->
                val point = client.records(HealthRecordKind.POINT, HealthRecordQuery(source, recordKey = "1")).items.single()
                val session = client.records(HealthRecordKind.SESSION, HealthRecordQuery(source, recordKey = "1")).items.single()
                assertTrue("10,8" in point.json); assertFalse("10,8" in session.json)
                val sleep = client.sleepRecords(HealthSleepQuery(source, recordKey = "1")).items.single()
                assertEquals("1", sleep.sessionId); assertEquals(100L, sleep.activityTypeCode)
                assertEquals(HealthRecordReadStatus.AVAILABLE, sleep.relatedPointsStatus)
                assertEquals(setOf(1L, 2L, 28L, 38L, 42L), sleep.relatedPoints.map { it.typeCode }.toSet())
                assertEquals(HealthMotionState.DEEP_SLEEP, sleep.motionIntervals.single().motion!!.state)
                assertEquals(HealthMotionState.REM, sleep.motionIntervals.single().motion!!.nextState)
                val opened = client.sleepRecords(HealthSleepQuery(source, recordKey = uuid)).items.single()
                assertEquals(0L, opened.endMillis); assertNull(opened.vendorUserId)
                assertTrue(HealthParseIssue.OPEN_SESSION in opened.issues)
            }
        }
        // The rowid is unchanged: a new scan must replace the old open session by its original UUID.
        provider.db.execSQL("UPDATE data_session SET time_to=2500,synced=3 WHERE id=?", arrayOf(uuid))
        provider.insertFixture(7, "60", 1)
        provider.db.execSQL("UPDATE data_point SET time_from=2000,time_to=2000 WHERE _id=7")
        SQLiteDataSyncStore(context, name).use { database ->
            val store = SQLiteLocalHealthStore(database)
            assertEquals(LocalHealthSyncPhase.COMPLETED,
                sync(store, FixtureRemote(HealthProviderReader(context.contentResolver))).phase)
            HealthRecordsEngine(deviceRecords = store).use { client ->
                val sleep = client.sleepRecords(HealthSleepQuery(source, recordKey = uuid)).items.single()
                assertEquals(uuid, sleep.sessionId); assertEquals(2500L, sleep.endMillis)
                assertEquals(3L, sleep.vendorSyncCode); assertEquals(true, sleep.vendorSynced)
                assertEquals("60", sleep.relatedPoints.single().values.single().decimal)
                assertEquals(2, client.records(HealthRecordKind.SESSION, HealthRecordQuery(source)).items.size)
                provider.db.execSQL("UPDATE data_session SET deleted=1 WHERE id=?", arrayOf(uuid))
                sync(store, FixtureRemote(HealthProviderReader(context.contentResolver)))
                assertTrue(client.sleepRecords(HealthSleepQuery(source, recordKey = uuid)).items.isEmpty())
                val deleted = client.sleepRecords(HealthSleepQuery(source, recordKey = uuid, includeDeleted = true)).items.single()
                assertEquals(true, deleted.deleted)
            }
        }
        assertEquals(0, provider.mutations)
    }
    @Test @Config(sdk = [30]) fun sessionProjectionPagesByNumericCursorAndRestoresOnlyOriginalColumnsWithFuturePointFields() {
        provider.insertFixture(1, "opaque-point-value", 1)
        provider.db.execSQL("ALTER TABLE data_point ADD COLUMN `future;literal` TEXT")
        provider.insertSession(2, "2d34f67a-87af-47b1-891f-3bf38113a8c1", 100, 200)
        provider.insertSession(5, "future-activity", 1000, 2000, type = 987, account = null)
        provider.insertSession(Long.MAX_VALUE, "open-session", 3000, 0)
        val reader = HealthProviderReader(context.contentResolver)
        fun page(after: Long?, upper: Long?) = LocalHealthProtocol.page(reader.page(buildJsonObject {
            put("op", "page"); put("table", LocalHealthTable.SESSION.wireName); put("limit", 2)
            after?.let { put("afterId", it.toString()) }; upper?.let { put("upperId", it.toString()) }
        }))
        val first = page(null, null)
        assertNull(first.error); assertFalse(first.complete); assertFalse(first.partial)
        assertEquals(listOf(2L, 5L), first.rows.map { it.id }); assertEquals(Long.MAX_VALUE, first.upperId)
        assertEquals("future-activity", first.rows[1].text("id")); assertEquals(987L, first.rows[1].number("type"))
        assertEquals(JsonNull, first.rows[1].columns["wwid"])
        val last = page(first.nextId, first.upperId)
        assertNull(last.error); assertTrue(last.complete); assertEquals(Long.MAX_VALUE, last.rows.single().id)
        (first.rows + last.rows).forEach { row ->
            assertEquals(setOf("id", "device_id", "wwid", "type", "time_to", "time_from", "synced", "deleted"), row.columns.keys)
            val types = row.raw["storageTypes"]!!.jsonObject
            assertEquals(row.columns.keys, types.keys); assertEquals("string", types.text("id"))
            assertFalse(row.raw.toString().contains("opaque-point-value"))
        }
        assertEquals(0, provider.mutations)
    }
    @Test fun sessionCapabilityErrorsDoNotBecomeEmptyHistoryOrAcceptAnUnknownTable() {
        val reader = HealthProviderReader(context.contentResolver)
        val unknown = reader.page(buildJsonObject { put("table", "other_table") })
        assertEquals("unsupported_table", unknown.text("error")); assertTrue(provider.querySql.isEmpty())
        provider.db.execSQL("DROP TABLE data_point")
        provider.db.execSQL("CREATE TABLE data_point (_id INTEGER PRIMARY KEY)")
        val missing = reader.page(LocalHealthProtocol.request(LocalHealthTable.SESSION, null, null))
        assertEquals("unsupported_schema", missing.text("error"))
        assertFalse(missing.flag("complete") == true); assertEquals(0, provider.mutations)
    }
    @Test fun missingSessionMetadataPreservesTheReadableFieldsAndReportsAPartialRow() {
        provider.insertSession(1, "partial-sleep", 100, 200)
        provider.db.execSQL("DROP TABLE data_point")
        provider.db.execSQL("CREATE TABLE data_point (_id INTEGER PRIMARY KEY, `values` TEXT, type INTEGER, time_from INTEGER)")
        val page = LocalHealthProtocol.page(HealthProviderReader(context.contentResolver)
            .page(LocalHealthProtocol.request(LocalHealthTable.SESSION, null, null)))
        assertNull(page.error); assertTrue(page.complete); assertTrue(page.partial)
        val row = page.rows.single()
        assertEquals("partial-sleep", row.text("id")); assertEquals(100L, row.number("type")); assertEquals(100L, row.number("time_from"))
        assertEquals(setOf("id", "type", "time_from"), row.columns.keys)
        assertEquals(setOf("device_id", "wwid", "time_to", "synced", "deleted"),
            row.raw["unavailableColumns"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals(0, provider.mutations)
    }
    @Test fun sleepQueriesDistinguishMissingPointsAndRetainAllAssociatedPointsWithoutSourceMixing() = runBlocking {
        val sessionId = "69d38ea7-7f2b-4f06-b6ea-69027854eaa0"
        fun row(cursor: Long, fields: JsonObject) = LocalHealthRow(buildJsonObject {
            put("id", cursor.toString()); put("columns", fields)
        })
        val session = row(1, buildJsonObject {
            put("id", sessionId); put("device_id", "watch-id"); put("wwid", JsonNull)
            put("type", 100); put("time_from", 1000); put("time_to", 2000); put("synced", 0); put("deleted", 0)
        })
        fun point(id: Long, value: String) = row(id, buildJsonObject {
            put("_id", id); put("values", value); put("device_id", "watch-id"); put("wwid", JsonNull)
            put("type", 1); put("time_from", 1000 + id); put("time_to", 1000 + id); put("synced", 0); put("deleted", 0)
        })
        SQLiteDataSyncStore(context, "sleep-query-volume.sqlite").use { database ->
            val store = SQLiteLocalHealthStore(database)
            suspend fun commit(selected: HealthRecordSource.LocalDevice, table: LocalHealthTable, rows: List<LocalHealthRow>) {
                val cursor = rows.maxOf { it.id!! }
                store.commit(selected, table, LocalHealthPage(rows, cursor, cursor, true), LocalHealthCheckpoint(cursor, cursor, false))
            }
            commit(source, LocalHealthTable.SESSION, listOf(session))
            HealthRecordsEngine(deviceRecords = store).use { client ->
                val initial = client.sleepRecords(HealthSleepQuery(source, recordKey = sessionId))
                assertEquals(HealthRecordReadStatus.AVAILABLE, initial.status)
                assertEquals(sessionId, initial.items.single().sessionId)
                assertEquals(HealthRecordReadStatus.SOURCE_UNAVAILABLE, initial.items.single().relatedPointsStatus)
                assertTrue(initial.items.single().relatedPoints.isEmpty())

                commit(source, LocalHealthTable.POINT, (1L..600L).map { point(it, "75") })
                listOf(source.copy(localUserId = "other-profile"), source.copy(peer = PeerAddress("11:22:33:44:55:66"))).forEach { other ->
                    commit(other, LocalHealthTable.SESSION, listOf(session))
                    commit(other, LocalHealthTable.POINT, listOf(point(1, "9999")))
                }
                val sleep = client.sleepRecords(HealthSleepQuery(source, recordKey = sessionId)).items.single()
                assertEquals(HealthRecordReadStatus.AVAILABLE, sleep.relatedPointsStatus)
                assertEquals(600, sleep.relatedPoints.size)
                assertEquals((1L..600L).toList(), sleep.relatedPoints.map { it.providerId })
                assertTrue(sleep.relatedPoints.all { it.raw.source == source && it.deviceId == "watch-id" && it.rawValues == "75" })
            }
        }
        assertEquals(0, provider.mutations)
    }
    @Test @Config(sdk = [30]) fun providerReadAlsoWorksOnWatchApi30AndEmptyIsNotUnavailable() = runBlocking {
        val reader = HealthProviderReader(context.contentResolver)
        val page = LocalHealthProtocol.page(reader.page(LocalHealthProtocol.request(LocalHealthTable.POINT, null, null)))
        assertTrue(page.complete); assertFalse(page.partial); assertTrue(page.rows.isEmpty()); assertNull(page.error)
        provider.insertFixture(1, "opaque,,75", 77)
        val row = LocalHealthProtocol.page(reader.page(LocalHealthProtocol.request(LocalHealthTable.POINT, null, null))).rows.single()
        assertEquals("opaque,,75", row.text("values")); assertEquals(77L, row.number("type"))
        assertEquals(0, provider.mutations)
    }
    @Test fun deniedProviderIsAnExplicitErrorAndRawValuesNeverAppearInDiagnosticMessages() {
        provider.deny = true
        val page = LocalHealthProtocol.page(HealthProviderReader(context.contentResolver).page(LocalHealthProtocol.request(LocalHealthTable.POINT, null, null)))
        assertEquals("provider_denied", page.error)
        assertFalse("sensitive fixture" in page.toString())
        assertEquals(0, provider.mutations)
    }

    class ReadOnlyFixtureProvider : ContentProvider() {
        lateinit var db: SQLiteDatabase; var mutations = 0; var deny = false
        val querySql = mutableListOf<String>()
        override fun onCreate(): Boolean {
            db = SQLiteDatabase.create(null)
            db.execSQL("CREATE TABLE data_point (_id INTEGER PRIMARY KEY, `values` TEXT, device_id TEXT, wwid TEXT, type INTEGER, time_to INTEGER, time_from INTEGER, synced INTEGER, deleted INTEGER, future_blob BLOB)")
            // Exact current APK DDL, including the TEXT primary key and implicit numeric rowid.
            db.execSQL("CREATE TABLE data_session (`id` TEXT NOT NULL, `device_id` TEXT NOT NULL DEFAULT '', `wwid` TEXT, `type` INTEGER NOT NULL DEFAULT -1, `time_to` INTEGER NOT NULL, `time_from` INTEGER NOT NULL, `synced` INTEGER NOT NULL DEFAULT 0, `deleted` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))")
            return true
        }
        fun insertFixture(id: Long, value: String, type: Int) {
            db.execSQL("INSERT INTO data_point VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf(id, value, "watch-id", "no-login-required", type, 200L, 100L, 0, 0, byteArrayOf(1,2,3)))
        }
        fun insertSession(cursorId: Long, id: String, start: Long, end: Long, type: Int = 100, account: String? = "no-login-required") {
            db.execSQL("INSERT INTO data_session(rowid,id,device_id,wwid,type,time_to,time_from,synced,deleted) VALUES(?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(cursorId, id, "watch-id", account, type, end, start, 0, 0))
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            if (deny) throw SecurityException("sensitive fixture")
            val sql = "SELECT * FROM data_point" + (selection?.let { " WHERE $it" } ?: "") + (sortOrder?.let { " ORDER BY $it" } ?: "")
            querySql += sql
            return db.rawQuery(sql, selectionArgs)
        }
        override fun getType(uri: Uri) = "vnd.android.cursor.dir/raw"
        override fun insert(uri: Uri, values: ContentValues?): Uri? { mutations++; error("Must not mutate") }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int { mutations++; error("Must not mutate") }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { mutations++; error("Must not mutate") }
    }
}
