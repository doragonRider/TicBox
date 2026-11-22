package dev.ticbox.companion.healthbridge

import android.app.Application
import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.CancellationSignal
import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.health.*
import dev.ticbox.companion.platform.data.SQLiteDataSyncStore
import dev.ticbox.companion.platform.health.SQLiteLocalHealthStore
import dev.ticbox.companion.protocol.*
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.*

/** Stock Provider's query/MatrixCursor contract with synthetic rows; radio/GMS and OEM access stay unobserved. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35], application = Application::class)
class LocalSportPipelineTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val source = HealthRecordSource.LocalDevice("sport-profile", PeerAddress("AA:BB:CC:DD:EE:FF"))
    private lateinit var provider: SportFixtureProvider
    @Before fun setup() {
        provider = SportFixtureProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = SportProviderReader.URI.authority; exported = true })
        ShadowContentResolver.registerProviderInternal(SportProviderReader.URI.authority!!, provider)
    }
    @After fun cleanup() = provider.db.close()

    private fun remote(failAfter: Int? = null): LocalHealthRemote {
        var requests = 0
        return LocalHealthRemote { object : LocalHealthConnection {
            override suspend fun page(table: LocalHealthTable, checkpoint: LocalHealthCheckpoint): LocalHealthPage {
                if (failAfter != null && requests++ >= failAfter) throw IOException("fixture radio unavailable")
                val request = LocalHealthProtocol.request(table, checkpoint.afterId, checkpoint.upperId, checkpoint.afterKey, checkpoint.upperKey)
                val response = HealthProviderReader(context.contentResolver, sportProviderVersion = "sport-fixture").page(request)
                return LocalHealthProtocol.page(LocalHealthProtocol.read(ByteArrayInputStream(LocalHealthProtocol.frame(response)))!!)
            }
            override fun close() = Unit
        } }
    }
    private suspend fun CoroutineScope.sync(store: SQLiteLocalHealthStore, remote: LocalHealthRemote = remote()): LocalHealthSyncState {
        val engine = LocalHealthSyncEngine(this, store, remote, retryDelayMillis = 0, tables = listOf(LocalHealthTable.SPORT))
        return try {
            engine.attach(LocalHealthLink(source, 9))
            engine.sync() // Each call is an explicit sync, even right after the previous one.
            withTimeout(15_000) { engine.state.first { it.phase in setOf(LocalHealthSyncPhase.COMPLETED,
                LocalHealthSyncPhase.PARTIAL, LocalHealthSyncPhase.UNAVAILABLE, LocalHealthSyncPhase.FAILED) } }
        } finally { engine.close() }
    }

    @Test fun opaqueSportIdsCrossPagesReachPhoneAndRefreshWithoutACloudAccount() = runBlocking {
        repeat(140) { provider.insert("sport-${it.toString().padStart(3, '0')}", type = 9876) }
        provider.insert("a' OR 1=1 --")
        provider.insert("非UUID-🙂")
        val name = "sports.sqlite"
        SQLiteDataSyncStore(context, name).use { database ->
            val store = SQLiteLocalHealthStore(database)
            val point = LocalHealthRow(buildJsonObject {
                put("id", "1"); put("columns", buildJsonObject {
                    put("_id", 1); put("type", 1); put("values", "75"); put("device_id", "watch-local")
                    put("time_from", 1000); put("time_to", 1000); put("synced", 0); put("deleted", 0)
                })
            })
            store.commit(source, LocalHealthTable.POINT, LocalHealthPage(listOf(point), 1, 1, true),
                LocalHealthCheckpoint(1, 1, false))
            assertEquals(HealthRecordReadStatus.SOURCE_UNAVAILABLE, store.records(HealthRecordKind.SPORT, HealthRecordQuery(source)).status)
            val result = sync(store)
            assertEquals(LocalHealthSyncPhase.COMPLETED, result.phase); assertEquals(142, result.recordsCommitted)
            assertEquals(2, result.pagesCommitted)
            val first = store.records(HealthRecordKind.SPORT, HealthRecordQuery(source, dataType = "9876", limit = 100))
            assertEquals(100, first.items.size); assertEquals(100, first.nextOffset)
            val rest = store.records(HealthRecordKind.SPORT, HealthRecordQuery(source, dataType = "9876", offset = 100))
            assertEquals(40, rest.items.size)
            val raw = first.items.first()
            assertEquals(LocalHealthTable.SPORT.sourceName, raw.sourceName)
            val data = HealthSportParser.parse(raw)
            assertEquals(60000L, data.durationMillis); assertEquals(85L, data.averageHeartRateBpm)
            assertEquals("12.5", data.calorieDecimal); assertEquals(9876L, data.typeCode)
            val json = Json.parseToJsonElement(raw.json).jsonObject
            assertEquals("AQID", json["columns"]!!.jsonObject.text("future_blob"))
            assertEquals("blob-base64", json["storageTypes"]!!.jsonObject.text("future_blob"))
            assertTrue(store.records(HealthRecordKind.SPORT, HealthRecordQuery(source, fromMillis = 90000)).items.isEmpty())
            assertEquals(HealthRecordReadStatus.SOURCE_UNAVAILABLE,
                store.records(HealthRecordKind.SPORT, HealthRecordQuery(source.copy(localUserId = "other"))).status)
        }
        SQLiteDataSyncStore(context, name).use { database ->
            val store = SQLiteLocalHealthStore(database)
            assertEquals("75", store.points(HealthPointQuery(source)).items.single().values.single().decimal)
            assertEquals(142, store.records(HealthRecordKind.SPORT, HealthRecordQuery(source, limit = 500)).items.size)
            // A new random HASH can precede last time's upper bound. Completed scans must start over.
            provider.insert("000-new-random-hash")
            provider.db.execSQL("UPDATE RECORD SET HEART=99 WHERE HASH='sport-000'")
            assertEquals(LocalHealthSyncPhase.COMPLETED, sync(store).phase)
            val all = store.records(HealthRecordKind.SPORT, HealthRecordQuery(source, limit = 500)).items
            assertEquals(143, all.size)
            val revised = all.first { HealthSportParser.parse(it).id == "sport-000" }
            assertEquals(99L, HealthSportParser.parse(revised).averageHeartRateBpm)
            assertTrue(provider.querySql.none { "wwid" in it.lowercase() })
        }
        assertEquals(0, provider.mutations)
    }

    @Test fun duplicateHashesAcrossPageBoundariesAreRetainedAndCheckpointResumesAfterRestart() = runBlocking {
        repeat(260) { provider.insert("same-logical-id", heart = 60 + it) }
        provider.insert("z-next-training")
        val name = "sport-resume.sqlite"
        SQLiteDataSyncStore(context, name).use { database ->
            val store = SQLiteLocalHealthStore(database)
            assertEquals(LocalHealthSyncPhase.UNAVAILABLE, sync(store, remote(failAfter = 1)).phase)
            val checkpoint = store.begin(source, LocalHealthTable.SPORT)
            assertEquals("same-logical-id", checkpoint.afterKey); assertEquals(128L, checkpoint.afterId)
            assertEquals("z-next-training", checkpoint.upperKey)
            assertEquals(128, store.records(HealthRecordKind.SPORT, HealthRecordQuery(source)).items.size)
        }
        SQLiteDataSyncStore(context, name).use { database ->
            val store = SQLiteLocalHealthStore(database)
            val result = sync(store)
            assertEquals(LocalHealthSyncPhase.COMPLETED, result.phase); assertEquals(133, result.recordsCommitted)
            val rows = store.records(HealthRecordKind.SPORT, HealthRecordQuery(source, limit = 500)).items
            assertEquals(261, rows.size); assertEquals(261, rows.map { it.key }.toSet().size)
            val duplicateRows = rows.map(HealthSportParser::parse).filter { it.id == "same-logical-id" }
            assertEquals((0L..259L).toSet(), duplicateRows.map { it.occurrence }.toSet())
            assertEquals(319L, duplicateRows.maxOf { it.averageHeartRateBpm!! })
            assertNull(store.begin(source, LocalHealthTable.SPORT).afterKey)
        }
        assertEquals(0, provider.mutations)
    }

    @Test fun sourceDenialAndEmptySuccessStayDistinct() = runBlocking {
        val reader = HealthProviderReader(context.contentResolver)
        val request = LocalHealthProtocol.request(LocalHealthTable.SPORT, null, null)
        val empty = LocalHealthProtocol.page(reader.page(request))
        assertNull(empty.error); assertTrue(empty.complete); assertTrue(empty.rows.isEmpty())
        SQLiteDataSyncStore(context, "sport-empty.sqlite").use { database ->
            val store = SQLiteLocalHealthStore(database)
            assertEquals(LocalHealthSyncPhase.COMPLETED, sync(store).phase)
            assertEquals(HealthRecordReadStatus.AVAILABLE, store.records(HealthRecordKind.SPORT, HealthRecordQuery(source)).status)
            provider.deny = true
            val unavailable = LocalHealthProtocol.page(reader.page(request))
            assertEquals("provider_denied", unavailable.error)
            assertEquals(LocalHealthSyncPhase.UNAVAILABLE, sync(store).phase)
            assertEquals(HealthRecordReadStatus.AVAILABLE, store.records(HealthRecordKind.SPORT, HealthRecordQuery(source)).status)
        }
        assertEquals(0, provider.mutations)
    }

    @Test fun stockPostLimitFiltersDoNotMakeAnInvalidLeadingRowHideValidTrainings() {
        provider.insert("z-invalid")
        provider.db.execSQL("UPDATE RECORD SET TYPE=-1 WHERE HASH='z-invalid'")
        provider.insert("valid-training")
        val page = LocalHealthProtocol.page(HealthProviderReader(context.contentResolver)
            .page(LocalHealthProtocol.request(LocalHealthTable.SPORT, null, null)))
        assertNull(page.error); assertTrue(page.complete)
        assertEquals("valid-training", page.rows.single().text("id"))
        assertEquals(0, provider.mutations)
    }

    @Test fun removingThePreviousBoundaryGroupDoesNotSkipTheFollowingTrainings() {
        repeat(2) { provider.insert("a-boundary") }
        provider.insert("b-next"); provider.insert("c-next")
        val reader = HealthProviderReader(context.contentResolver)
        val firstRequest = JsonObject(LocalHealthProtocol.request(LocalHealthTable.SPORT, null, null).toMutableMap()
            .apply { put("limit", JsonPrimitive(2)) })
        val first = LocalHealthProtocol.page(reader.page(firstRequest))
        assertFalse(first.complete); assertEquals("a-boundary", first.nextKey); assertEquals(2L, first.nextId)
        provider.db.execSQL("DELETE FROM RECORD WHERE HASH='a-boundary'")
        val next = LocalHealthProtocol.page(reader.page(LocalHealthProtocol.request(LocalHealthTable.SPORT,
            first.nextId, null, first.nextKey, first.upperKey)))
        assertTrue(next.complete); assertEquals(listOf("b-next", "c-next"), next.rows.map { it.text("id") })
    }

    @Test fun providerKeywordRewritingDoesNotAlterOpaqueHashText() {
        val keys = listOf("time_from", "time_to", "`wwid`", "q'quoted", "z-last")
        keys.forEach { provider.insert(it) }
        val reader = HealthProviderReader(context.contentResolver)
        val ids = mutableListOf<String>()
        var after: String? = null; var upper: String? = null; var consumed: Long? = null
        do {
            val request = JsonObject(LocalHealthProtocol.request(LocalHealthTable.SPORT, consumed, null, after, upper)
                .toMutableMap().apply { put("limit", JsonPrimitive(1)) })
            val page = LocalHealthProtocol.page(reader.page(request))
            assertNull(page.error); ids += page.rows.map { it.text("id")!! }
            after = page.nextKey; upper = page.upperKey; consumed = page.nextId
        } while (!page.complete)
        assertEquals(keys.sorted(), ids)
    }
}

/** Reproduces fixed stock summary projection, no selectionArgs, hidden deletions and HASH identity. */
internal class SportFixtureProvider : ContentProvider() {
    lateinit var db: SQLiteDatabase
    var deny = false; var mutations = 0; val querySql = mutableListOf<String>()
    override fun onCreate(): Boolean {
        db = SQLiteDatabase.create(null)
        db.execSQL("CREATE TABLE RECORD (_id INTEGER PRIMARY KEY, HASH TEXT, DEVICE_ID TEXT, START_TIME INTEGER, END_TIME INTEGER, TYPE INTEGER, SYNCED INTEGER, DURATION INTEGER, CALORIE REAL, HEART INTEGER, DELETED INTEGER)")
        return true
    }
    fun insert(hash: String, type: Int = 0, heart: Int = 85) {
        db.execSQL("INSERT INTO RECORD(HASH,DEVICE_ID,START_TIME,END_TIME,TYPE,SYNCED,DURATION,CALORIE,HEART,DELETED) VALUES(?,?,?,?,?,0,60000,12.5,?,0)",
            arrayOf<Any>(hash, "watch-local", 1000, 61000, type, heart))
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? {
        if (deny) throw SecurityException("signature permission required")
        check(args.isNullOrEmpty()); check(uri.path == "/fitness_summary")
        if (selection!!.contains("`wwid`")) return null
        val rewritten = selection.replace("time_from", "START_TIME").replace("time_to", "END_TIME")
        val sql = "SELECT * FROM RECORD WHERE DELETED=0 AND $rewritten ORDER BY $order"
        querySql += sql
        return MatrixCursor(arrayOf("id", "wwid", "device_id", "time_from", "time_to", "type", "synced", "during", "calorie", "heart", "future_blob")).apply {
            db.rawQuery(sql, null).use { cursor -> while (cursor.moveToNext()) {
                fun index(name: String) = cursor.getColumnIndexOrThrow(name)
                val hash = cursor.getString(index("HASH")); val start = cursor.getLong(index("START_TIME")); val end = cursor.getLong(index("END_TIME"))
                val type = cursor.getInt(index("TYPE"))
                if (hash.isNullOrEmpty() || start <= 0 || end < start || type == -1) continue
                addRow(arrayOf(hash, "", cursor.getString(index("DEVICE_ID")), start, end, type,
                    cursor.getInt(index("SYNCED")), cursor.getLong(index("DURATION")), cursor.getDouble(index("CALORIE")),
                    cursor.getInt(index("HEART")), byteArrayOf(1, 2, 3)))
            } }
        }
    }
    override fun getType(uri: Uri) = "vnd.android.cursor.dir/fitness_summary"
    override fun insert(uri: Uri, values: ContentValues?): Uri? { mutations++; error("not a reader") }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int { mutations++; error("not a reader") }
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int { mutations++; error("not a reader") }
}
