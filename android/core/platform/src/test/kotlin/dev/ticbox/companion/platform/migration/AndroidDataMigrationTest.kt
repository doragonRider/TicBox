package dev.ticbox.companion.platform.migration

import android.app.Application
import android.content.Context
import android.database.Cursor
import android.util.AtomicFile
import dev.ticbox.companion.api.MigrationException
import dev.ticbox.companion.api.MigrationSection
import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.engine.recovery.ConnectionIntent
import dev.ticbox.companion.platform.data.SQLiteDataSyncStore
import dev.ticbox.companion.platform.health.LocalHealthProfile
import dev.ticbox.companion.platform.identity.AtomicFileIdentityStore
import dev.ticbox.companion.platform.recovery.AtomicFileConnectionIntentStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** One app exports to a file, another app's data takes it in; both share this test's files. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidDataMigrationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val peer = PeerAddress("AA:BB:CC:DD:EE:01").value
    private var stops = 0

    private fun migration(from: SQLiteDataSyncStore, into: String, stopped: SQLiteDataSyncStore = from) =
        AndroidDataMigration(context, from, stopBackend = { stops++; stopped.awaitClosed() }, iterations = 10_000,
            clock = { 1_700_000_000_000 }, databaseName = into)
    private suspend fun SQLiteDataSyncStore.sql(vararg statements: String) = withDatabase { db -> statements.forEach(db::execSQL) }
    private suspend fun <T> SQLiteDataSyncStore.rows(sql: String, read: (Cursor) -> T): List<T> =
        readDatabase { db -> db.rawQuery(sql, null).use { cursor -> buildList { while (cursor.moveToNext()) add(read(cursor)) } } }
    private fun record(profile: String, key: String, payload: String, updated: Long) =
        "INSERT INTO local_health_records VALUES ('$profile', '$peer', 'POINT', '$key', 'src', NULL, NULL, NULL, 1, 2, 0, '$payload', '{}', $updated)"
    private fun sample(at: Long, battery: Int) =
        "INSERT INTO observation_samples (peer, kind, received_at, battery) VALUES ('$peer', 'BATTERY', $at, $battery)"
    private fun counterFile() = AtomicFile(File(context.noBackupFilesDir, "notification-global-revision.counter"))
    private fun writeCounter(value: Long) = counterFile().let { file ->
        val out = file.startWrite(); DataOutputStream(out).writeLong(value); file.finishWrite(out)
    }
    private fun code(block: suspend () -> Unit) = assertFailsWith<MigrationException> { runBlocking { block() } }.code

    @Test fun healthMergesKeepingTheNewerCopyWhilePairingReplacesTheIdentity() = runBlocking {
        val old = SQLiteDataSyncStore(context, "old.sqlite")
        old.sql(
            "INSERT INTO local_health_sources VALUES ('old-profile', '$peer', 'POINT', 10, NULL, 0, 0, 1, NULL, 100)",
            "INSERT INTO local_health_sources VALUES ('old-profile', '$peer', 'SPORT', NULL, X'7B7D', 1, 0, 1, 'v2', NULL)",
            record("old-profile", "r1", "file-older", 50), record("old-profile", "r2", "file-newer", 90),
            record("old-profile", "r3", "only-in-file", 70), sample(1000, 50), sample(3000, 40),
            "INSERT INTO cloud_cache VALUES ('owner', 'SLEEP', 'c1', NULL, NULL, 1, 2, NULL, 0, '{}', 5)",
            "INSERT INTO scopes VALUES ('scope-old', '$peer', 'node-old', 'watch', NULL)",
            "INSERT INTO sequences VALUES ('scope-old', 42)",
            "INSERT INTO items VALUES ('item-1', 'scope-old', 'pkg', 'digest', 'wear://x', 'node-old', 1, 1, 0, X'0102', NULL, 5, 1, X'0A0B')",
        )
        val identity = AtomicFileIdentityStore(context).loadOrCreate()
        AtomicFileConnectionIntentStore(context).write(ConnectionIntent.selected(PeerAddress(peer), associationId = 7))
        context.getSharedPreferences("frontend-appearance", Context.MODE_PRIVATE).edit()
            .putBoolean("dynamic-color", false).putInt("remote-screen-max-fps", 90).commit()
        File(context.noBackupFilesDir, "toolbox-adb").mkdirs()
        File(context.noBackupFilesDir, "toolbox-adb/host-key.pem").writeText("-----BEGIN PRIVATE KEY-----\nold\n")
        writeCounter(5)
        val file = ByteArrayOutputStream().also { migration(old, "new.sqlite").export(it, MigrationSection.entries.toSet(), "pw".toCharArray()) }
            .toByteArray()
        old.close()

        // The other app: its own profile, an older scan of the same watch, its own identity and sync state.
        val profile = LocalHealthProfile.id(context)
        val new = SQLiteDataSyncStore(context, "new.sqlite")
        new.sql(
            "INSERT INTO local_health_sources VALUES ('$profile', '$peer', 'POINT', 5, NULL, 0, 0, 1, NULL, 50)",
            record(profile, "r1", "local-newer", 80), record(profile, "r2", "local-older", 60),
            record(profile, "r4", "only-local", 10), sample(1000, 50), sample(2000, 45),
            "INSERT INTO scopes VALUES ('scope-new', '$peer', 'node-new', 'watch', NULL)",
        )
        File(context.noBackupFilesDir, "companion/identity.bin").delete()
        assertNotEquals(identity, AtomicFileIdentityStore(context).loadOrCreate())
        context.getSharedPreferences("frontend-appearance", Context.MODE_PRIVATE).edit().putBoolean("dynamic-color", true).commit()
        writeCounter(9)
        File(context.noBackupFilesDir, "toolbox-adb/host-key.pem").writeText("-----BEGIN PRIVATE KEY-----\nnew\n")

        val migration = migration(new, "new.sqlite")
        assertEquals("migration_password_required", code { migration.inspect({ ByteArrayInputStream(file) }, null) })
        assertEquals("migration_wrong_password", code { migration.inspect({ ByteArrayInputStream(file) }, "nope".toCharArray()) })
        val manifest = migration.inspect({ ByteArrayInputStream(file) }, "pw".toCharArray())
        assertEquals(MigrationSection.entries.toSet(), manifest.sections)
        assertTrue(manifest.encrypted); assertEquals(1, manifest.dataSchema); assertEquals(1_700_000_000_000, manifest.createdAtMillis)
        migration.import({ ByteArrayInputStream(file) }, "pw".toCharArray(), MigrationSection.entries.toSet())
        assertEquals(1, stops)

        SQLiteDataSyncStore(context, "new.sqlite").use { merged ->
            assertEquals(listOf("r1" to "local-newer", "r2" to "file-newer", "r3" to "only-in-file", "r4" to "only-local"),
                merged.rows("SELECT record_key, payload, local_user FROM local_health_records ORDER BY record_key") {
                    assertEquals(profile, it.getString(2)); it.getString(0) to it.getString(1)
                })
            // The newer scan wins in place; replacing the row would have cascaded to the records above.
            assertEquals(listOf(10L to 100L), merged.rows("SELECT after_id, last_success FROM local_health_sources WHERE kind='POINT'") {
                it.getLong(0) to it.getLong(1)
            })
            assertContentEquals("{}".toByteArray(), merged.rows("SELECT upper_id FROM local_health_sources WHERE kind='SPORT'") { it.getBlob(0) }.single())
            assertEquals(listOf(1000L, 2000L, 3000L), merged.rows("SELECT received_at FROM observation_samples ORDER BY sample_id") { it.getLong(0) })
            assertEquals(1, merged.rows("SELECT 1 FROM cloud_cache") { it.getInt(0) }.size)
            assertEquals(listOf("scope-old"), merged.rows("SELECT scope_id FROM scopes") { it.getString(0) })
            assertEquals(listOf(42L), merged.rows("SELECT value FROM sequences") { it.getLong(0) })
            assertContentEquals(byteArrayOf(1, 2), merged.rows("SELECT body FROM items") { it.getBlob(0) }.single())
        }
        assertEquals(identity, AtomicFileIdentityStore(context).loadOrCreate())
        val intent = AtomicFileConnectionIntentStore(context).read()!!
        assertEquals(PeerAddress(peer), intent.peer); assertNull(intent.associationId); assertTrue(intent.reconnectEnabled)
        val preferences = context.getSharedPreferences("frontend-appearance", Context.MODE_PRIVATE)
        assertFalse(preferences.getBoolean("dynamic-color", true)); assertEquals(90, preferences.getInt("remote-screen-max-fps", 0))
        assertEquals("-----BEGIN PRIVATE KEY-----\nold\n", File(context.noBackupFilesDir, "toolbox-adb/host-key.pem").readText())
        assertEquals(9L, DataInputStream(counterFile().openRead()).use { it.readLong() })
    }

    @Test fun aFileThatCanNotBeImportedLeavesTheServiceRunning() = runBlocking {
        val store = SQLiteDataSyncStore(context, "running.sqlite")
        store.sql("INSERT INTO local_health_sources VALUES ('p', '$peer', 'POINT', 1, NULL, 0, 0, 1, NULL, 1)", record("p", "r1", "x", 1))
        val migration = migration(store, "running.sqlite")
        val healthOnly = ByteArrayOutputStream().also { migration.export(it, setOf(MigrationSection.HEALTH), null) }.toByteArray()
        assertEquals("migration_section_missing", code {
            migration.import({ ByteArrayInputStream(healthOnly) }, null, setOf(MigrationSection.PAIRING))
        })
        fun zip(manifest: String, vararg entries: Pair<String, String>) = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                (listOf("manifest.json" to manifest) + entries).forEach { (name, text) ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
                }
            }
        }.toByteArray()
        val manifest = """{"format":1,"schema":1,"app":"x","versionCode":1,"createdAt":1,"sections":["HEALTH"]}"""
        val broken = zip(manifest, "tables/local_health_records.jsonl" to "{\"local_user\":")
        assertEquals("migration_damaged", code { migration.import({ ByteArrayInputStream(broken) }, null, setOf(MigrationSection.HEALTH)) })
        val newer = zip(manifest.replace("\"schema\":1", "\"schema\":2"))
        assertEquals("migration_newer", code { migration.inspect({ ByteArrayInputStream(newer) }, null) })
        assertEquals("migration_not_a_backup", code { migration.inspect({ ByteArrayInputStream("hello".toByteArray()) }, null) })
        assertEquals(0, stops)
        assertEquals(1, store.rows("SELECT 1 FROM local_health_records") { it.getInt(0) }.size)
        store.close()
    }
}
