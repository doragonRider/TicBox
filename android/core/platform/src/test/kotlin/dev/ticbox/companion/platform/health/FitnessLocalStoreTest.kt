package dev.ticbox.companion.platform.health

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.health.*
import dev.ticbox.companion.platform.data.SQLiteDataSyncStore
import dev.ticbox.companion.protocol.*
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

/** Synthetic private-table acquisition pages -> production parser/SQLite -> public read client. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FitnessLocalStoreTest {
    private val source = HealthRecordSource.LocalDevice("fixture-profile", PeerAddress("AA:BB:CC:DD:EE:FF"))
    private val context get() = RuntimeEnvironment.getApplication()

    private fun row(id: Long, columns: JsonObject) = LocalHealthRow(buildJsonObject {
        put("id", id.toString()); put("columns", buildJsonObject { put("_id", id); columns.forEach(::put) })
    })
    private fun record(id: Long, type: Long, start: Long = 100, end: Long = 200, deleted: Long = 0) = row(id, buildJsonObject {
        put("HASH", "same-opaque-hash"); put("TYPE", type); put("START_TIME", start); put("END_TIME", end)
        put("DURATION", 100); put("SYNCED", 0); put("DELETED", deleted); put("DEVICE_ID", "fixture-device")
        put("SWIM_TRIPS", "2.5"); put("SWIM_STROKE", 60000); put("SWIM_POOL_LENGTH", 1000)
        put("SWIM_DISTANCE", 2); put("EXTRA", "{\"future\":true}"); put("future_column", "preserved")
    })
    private fun point(id: Long, record: Long, at: Long) = row(id, buildJsonObject {
        put("RECORD_ID", record); put("TIME", 50000); put("TIMESTAMP", at); put("HEART", 80)
        put("LAT", "0"); put("LON", "0"); put("ACC", "271.5"); put("DISTANCE", -1); put("STEP", -1)
        put("SWIM_TRIPS", 2); put("SWIM_STROKE", 30000); put("SWIM_TYPE", 1000)
    })
    private suspend fun SQLiteLocalHealthStore.save(kind: HealthRecordKind, vararg rows: LocalHealthRow) =
        commitFitnessPage(source, kind, LocalHealthPage(rows.toList(), rows.lastOrNull()?.id, rows.lastOrNull()?.id, true),
            LocalHealthCheckpoint(rows.lastOrNull()?.id, rows.lastOrNull()?.id, false))

    @Test fun exactParentIdsLateModeAssociationAndPaginationSurviveRestart() = runBlocking {
        val firstId = 9007199254740993L
        val secondId = firstId + 1
        val name = "fitness-query.sqlite"
        SQLiteDataSyncStore(context, name).use { db ->
            val store = SQLiteLocalHealthStore(db)
            HealthRecordsEngine(deviceRecords = store).use { client ->
                assertEquals(HealthRecordReadStatus.SOURCE_UNAVAILABLE,
                    client.fitnessRecords(HealthFitnessRecordQuery(source)).status)
                store.save(HealthRecordKind.FITNESS_POINT, point(1, firstId, 140), point(2, firstId, 150), point(3, secondId, 150))
                val orphan = client.fitnessPoints(HealthFitnessPointQuery(source, firstId)).items.first()
                assertEquals(firstId, orphan.recordId); assertEquals(HealthFitnessMode.UNCONFIRMED, orphan.mode)
                store.save(HealthRecordKind.FITNESS_RECORD, record(firstId, 0), record(secondId, 9))
                val records = client.fitnessRecords(HealthFitnessRecordQuery(source, limit = 1))
                assertEquals(firstId, records.items.single().id); assertEquals(1, records.nextOffset)
                val last = client.fitnessRecords(HealthFitnessRecordQuery(source, offset = 1))
                assertEquals(secondId, last.items.single().id); assertNull(last.nextOffset)
                assertEquals("same-opaque-hash", records.items.single().hash)
                assertEquals(records.items.single().hash, last.items.single().hash)
                val page = client.fitnessPoints(HealthFitnessPointQuery(source, firstId, fromMillis = 150, limit = 1))
                assertEquals(listOf(2L), page.items.map { it.id }); assertEquals(1000L, page.items.single().lap?.distanceMeters)
                assertEquals("271.5", page.items.single().locationAuxiliaryDecimal)
                assertEquals(HealthFitnessMode.LAND, page.items.single().mode)
                val swimming = client.fitnessPoints(HealthFitnessPointQuery(source, secondId)).items.single()
                assertEquals(HealthFitnessMode.SWIMMING, swimming.mode); assertNull(swimming.lap)
                assertEquals(1000L, swimming.swimming?.strokeTypeCode) // Future stroke code survives.
                assertEquals(HealthRecordReadStatus.SOURCE_UNAVAILABLE, client.fitnessRecords(
                    HealthFitnessRecordQuery(source.copy(localUserId = "another"))).status)
                assertEquals(HealthRecordReadStatus.SOURCE_UNAVAILABLE, client.fitnessPoints(
                    HealthFitnessPointQuery(source.copy(peer = PeerAddress("11:22:33:44:55:66")), firstId)).status)
                assertTrue(client.fitnessRecords(HealthFitnessRecordQuery(source, typeCode = 777)).items.isEmpty())
                assertTrue(client.fitnessRecords(HealthFitnessRecordQuery(source, fromMillis = 201)).items.isEmpty())
            }
        }
        SQLiteDataSyncStore(context, name).use { db ->
            val store = SQLiteLocalHealthStore(db)
            val raw = store.records(HealthRecordKind.FITNESS_RECORD, HealthRecordQuery(source, recordKey = firstId.toString())).items.single()
            assertTrue("future_column" in raw.json)
            assertEquals("sqlite://com.mobvoi.wear.fitness.at/fitness.db/RECORD", raw.sourceName)
            store.save(HealthRecordKind.FITNESS_RECORD, record(firstId, 9, deleted = 1))
            assertEquals(listOf(secondId), store.fitnessRecords(HealthFitnessRecordQuery(source)).items.map { it.id })
            assertEquals(firstId, store.fitnessRecords(HealthFitnessRecordQuery(source, includeDeleted = true,
                recordKey = firstId.toString())).items.single().id)
            // A caller selecting this parent explicitly can still inspect its raw POINT history.
            assertEquals(HealthFitnessMode.SWIMMING, store.fitnessPoints(HealthFitnessPointQuery(source, firstId)).items.first().mode)
            val parsed = SQLiteDatabase.openDatabase(File(context.noBackupFilesDir, name).path, null, SQLiteDatabase.OPEN_READONLY).use { sql ->
                sql.rawQuery("SELECT parsed_payload FROM local_health_records WHERE kind='FITNESS_RECORD' LIMIT 1", null).use {
                    assertTrue(it.moveToFirst()); it.getString(0)
                }
            }
            assertTrue(Json.parseToJsonElement(parsed).jsonObject.isNotEmpty())
        }
    }

    @Test fun anAcquiredEmptyTableIsAvailableAndFailedPageDoesNotPartiallyReplaceRows() = runBlocking {
        val name = "fitness-transaction.sqlite"
        SQLiteDataSyncStore(context, name).use { db ->
            val store = SQLiteLocalHealthStore(db)
            store.save(HealthRecordKind.FITNESS_RECORD)
            assertNull(store.lastSuccess(source))
            assertEquals(HealthRecordReadStatus.AVAILABLE, store.fitnessRecords(HealthFitnessRecordQuery(source)).status)
            assertEquals(HealthRecordReadStatus.SOURCE_UNAVAILABLE, store.fitnessPoints(HealthFitnessPointQuery(source, 1)).status)
            store.save(HealthRecordKind.FITNESS_RECORD, record(1, 0))
            SQLiteDatabase.openDatabase(File(context.noBackupFilesDir, name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("CREATE TRIGGER fitness_disk_failure BEFORE INSERT ON local_health_records WHEN NEW.record_key='3' BEGIN SELECT RAISE(ABORT,'fixture'); END")
            }
            assertFailsWith<android.database.sqlite.SQLiteException> {
                store.save(HealthRecordKind.FITNESS_RECORD, record(2, 9), record(3, 24))
            }
            assertEquals(listOf(1L), store.fitnessRecords(HealthFitnessRecordQuery(source)).items.map { it.id })
        }
    }
}
