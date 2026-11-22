@file:Suppress("DEPRECATION") // Explicit native SQLite fixtures on both supported Android test runtimes.

package dev.ticbox.companion.healthbridge

import android.app.Application
import android.database.Cursor
import android.database.sqlite.SQLiteBlobTooBigException
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import android.os.Parcel
import android.os.SharedMemory
import dev.ticbox.companion.protocol.*
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.test.*

/** The module path must reach exactly what the helper reaches on the file, so cursors carry over between them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ModuleJournalReadTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: SQLiteDatabase
    private val signal get() = CancellationSignal()
    private val version = "5.6.0-3234.2686"

    @Before fun setup() {
        db = SQLiteDatabase.openOrCreateDatabase(File(context.cacheDir, "module-source.db").apply { delete() }, null)
        db.execSQL("CREATE TABLE room_master_table(id INTEGER PRIMARY KEY,identity_hash TEXT)")
        db.execSQL("INSERT INTO room_master_table VALUES(42,?)", arrayOf(HealthJournalProfilesTest.HEALTH_HASHES.first()))
        db.version = 5
        db.execSQL(HealthJournalProfilesTest.HEALTH_POINT); db.execSQL(HealthJournalProfilesTest.HEALTH_SESSION)
        db.execSQL(HealthJournalProfilesTest.HEALTH_SOURCE)
        db.execSQL("CREATE INDEX point_query ON data_point(wwid,type,time_to,time_from)")
        db.execSQL("CREATE INDEX point_sync ON data_point(wwid,type,synced)")
        db.execSQL("CREATE INDEX session_sync ON data_session(wwid,type,synced)")
        db.execSQL("CREATE INDEX session_query ON data_session(wwid,type,time_to,time_from)")
        AtomicHealthJournal.install(db, "health")
    }

    @After fun cleanup() { db.close() }

    /** What the module answers, cell types and all, from the same database; [maxRows] limits the row reads by key. */
    private fun module(maxRows: Int = Int.MAX_VALUE, inodes: Iterator<Long> = generateSequence { 9L }.iterator()): (JsonObject) -> JsonObject = { request ->
        when (request.text("op")) {
            "journal_describe" -> buildJsonObject {
                put("version", 1); put("journal", 1); put("packageName", "com.mobvoi.wear.health.at"); put("versionCode", 50603234)
                put("versionName", version); put("apkSha256", "276264832680b5da9fc7bae260ff521e7df6d49c7e04fa40c00f64e3ef96c4db")
                put("device", 7); put("inode", inodes.next())
            }
            else -> try {
                db.rawQuery(request.text("sql")!!, Json.parseToJsonElement(request.text("args")!!).jsonArray.map { it.jsonPrimitive.content }.toTypedArray()).use { c ->
                    if (request.text("sql")!!.startsWith("SELECT * FROM") && c.count > maxRows) return@use buildJsonObject { put("error", "journal_reply_too_large") }
                    buildJsonObject {
                        put("columns", JsonArray(c.columnNames.map(::JsonPrimitive)))
                        put("rows", buildJsonArray { while (c.moveToNext()) add(JsonArray((0 until c.columnCount).map { cell(c, it) })) })
                    }
                }
            } catch (_: SQLiteBlobTooBigException) { buildJsonObject { put("error", "journal_row_too_big") } }
        }
    }

    private fun cell(c: Cursor, i: Int): JsonElement = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> JsonNull
        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive("i" + c.getLong(i))
        Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive("f" + c.getDouble(i))
        Cursor.FIELD_TYPE_BLOB -> JsonPrimitive("b" + java.util.Base64.getEncoder().encodeToString(c.getBlob(i)))
        else -> JsonPrimitive("s" + c.getString(i))
    }

    private fun onFile(positions: JsonObject): JsonObject {
        val generation = HealthJournalRead.generation("health", version, 7, 9, HealthJournalProfiles.schemaFingerprint(db, "health"))
        return JsonObject(HealthJournalRead.read(FileJournalSql(db), "health", positions, generation, version) + HealthJournalRead.identity(generation, version))
    }

    @Test fun theModuleReachesTheSameWindowGenerationAndPagesAsTheFileIncludingEveryCellType() {
        val start = HealthJournalWindow.parse(onFile(JsonObject(emptyMap())))!!.position()
        db.execSQL("INSERT INTO data_point(_id,\"values\",device_id,type,time_to,time_from) VALUES(1,'{\"bpm\":\"7\\u00e9\"}',x'00ff',1,1.5,2)")
        db.execSQL("INSERT INTO data_point(_id,\"values\",type,time_to,time_from) VALUES(2,'b',1,9223372036854775807,-0.0)")
        db.execSQL("INSERT INTO data_point(_id,type,time_to,time_from) VALUES(3,1,3,3)")
        db.execSQL("DELETE FROM data_point WHERE _id=3")
        db.execSQL("INSERT INTO data_session(id,type,time_to,time_from) VALUES('s1',2,5,4)")
        val positions = buildJsonObject { put("data_point", start.json()); put("data_session", start.json()) }

        val expected = onFile(positions)
        val pages = expected.getValue("pages").jsonObject
        assertEquals(setOf("data_point", "data_session"), pages.keys)
        assertEquals(2, pages.getValue("data_point").jsonObject.getValue("rows").jsonArray.size)
        assertEquals(listOf("3"), pages.getValue("data_point").jsonObject.getValue("missingKeys").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(expected, HealthModuleJournal(context) { module() }.read(positions, signal))
        // Row answers too large for the module are asked for again in smaller parts, with the same result.
        assertEquals(expected, HealthModuleJournal(context) { module(maxRows = 1) }.read(positions, signal))
    }

    @Test fun aRowNoCursorCanHoldIsReadWithoutItsLargestCellOnBothPaths() {
        val start = HealthJournalWindow.parse(onFile(JsonObject(emptyMap())))!!.position()
        db.execSQL("INSERT INTO data_point(_id,\"values\",type,time_to,time_from) VALUES(1,'a',1,1,1)")
        db.execSQL("INSERT INTO data_point(_id,\"values\",type,time_to,time_from) VALUES(2,?,1,2,2)", arrayOf("x".repeat(3 * 1024 * 1024)))
        db.execSQL("INSERT INTO data_point(_id,\"values\",type,time_to,time_from) VALUES(3,'c',1,3,3)")
        val positions = buildJsonObject { put("data_point", start.json()) }
        val expected = onFile(positions)
        val page = expected.getValue("pages").jsonObject.getValue("data_point").jsonObject
        val rows = page.getValue("rows").jsonArray.map { it.jsonObject }
        assertEquals(listOf("1", "2", "3"), rows.map { it.text("id") }); assertEquals(0, page.getValue("missingKeys").jsonArray.size)
        assertEquals("""["values"]""", rows[1]["oversizedColumns"].toString())
        assertEquals(expected, HealthModuleJournal(context) { module() }.read(positions, signal))
    }

    @Test fun aLargeModuleAnswerArrivesThroughSharedMemory() {
        val body = buildJsonObject { put("text", "x".repeat(300_000)) }.toString().toByteArray()
        val reply = Parcel.obtain()
        try {
            SharedMemory.create("fixture", body.size).use { memory ->
                memory.mapReadWrite().let { buffer -> buffer.put(body); SharedMemory.unmap(buffer) }
                reply.writeInt(1); memory.writeToParcel(reply, 0)
            }
            reply.setDataPosition(0)
            assertContentEquals(body, ModuleConnection.answer(reply))
        } finally { reply.recycle() }
    }

    @Test fun aFileReplacedDuringTheReadIsReportedAndAMissingModuleLeavesTheReadToTheHelper() {
        val replaced = assertFailsWith<HealthJournalException> {
            HealthModuleJournal(context) { module(inodes = listOf(9L, 10L).iterator()) }.read(JsonObject(emptyMap()), signal)
        }
        assertEquals("journal_source_replaced", replaced.code)
        assertNull(HealthModuleJournal(context) { null }.read(JsonObject(emptyMap()), signal))
    }

    @Test fun unchangedWorkoutFilesReuseTheWindowAndAChangeReadsItWithTheCursor() {
        val table = LocalHealthTable.FITNESS_RECORD
        var stamp = "a"; var windows = 0
        var seen: Map<LocalHealthTable, HealthJournalPosition> = emptyMap()
        val epoch = UUID.randomUUID().toString()
        val journal = object : HealthJournalReader {
            override fun window(table: LocalHealthTable, cancellation: CancellationSignal) = fail("one read with the cursors expected")
            override fun window(table: LocalHealthTable, positions: Map<LocalHealthTable, HealthJournalPosition>, cancellation: CancellationSignal) =
                HealthJournalWindow(epoch, "g", 5, 0).also { windows++; seen = positions }
            override fun page(table: LocalHealthTable, after: HealthJournalPosition, upper: Long, cancellation: CancellationSignal) = fail("no events to read")
        }
        val store = HealthIncrementalStore(context, "module-ledger.db")
        val reader = IncrementalHealthReader(store, { _, _ -> localHealthPage(emptyList(), null, null, true, "fixture") }, listOf(table), { stamp }, journal)
        fun sync() {
            val run = UUID.randomUUID().toString()
            repeat(20) {
                val reply = LocalHealthProtocol.parse(reader.handle(LocalHealthProtocol.changes(table, "receiver", run, false), signal).toString().toByteArray())
                assertNull(reply.text("error"), reply.toString())
                reader.handle(LocalHealthProtocol.acknowledge("receiver", table, reply.text("batch")!!), signal)
                if (reply.flag("complete") == true) return
            }
            fail("sync did not finish")
        }
        sync(); assertEquals(1, windows); assertTrue(seen.isEmpty())
        sync(); assertEquals(1, windows)
        stamp = "b"
        sync(); assertEquals(2, windows); assertEquals(mapOf(table to HealthJournalPosition(epoch, "g", 5)), seen)
        reader.close()
    }
}
