package dev.ticbox.companion.platform.migration

import android.app.LocaleManager
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteFullException
import android.os.LocaleList
import android.util.AtomicFile
import dev.ticbox.companion.api.DataMigration
import dev.ticbox.companion.api.DndSyncMode
import dev.ticbox.companion.api.MigrationException
import dev.ticbox.companion.api.MigrationManifest
import dev.ticbox.companion.api.MigrationSection
import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.engine.recovery.ConnectionIntent
import dev.ticbox.companion.engine.recovery.ConnectionIntentCodec
import dev.ticbox.companion.engine.sos.SosCodec
import dev.ticbox.companion.platform.data.SQLiteDataSyncStore
import dev.ticbox.companion.platform.health.LocalHealthProfile
import dev.ticbox.companion.platform.identity.IdentityDocumentCodec
import dev.ticbox.companion.platform.media.MediaPreferencesCodec
import dev.ticbox.companion.platform.notification.NotificationPreferencesCodec
import dev.ticbox.companion.platform.recovery.AtomicFileConnectionIntentStore
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.Reader
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Moves this app's local data in and out of one file: a ZIP, sealed by [MigrationCrypto] when a
 * password is given. The format is described in docs/dev/data-migration.md.
 *
 * Export reads [database], the running service's store, under its read lock, so every table comes
 * from one moment. Import reads the whole file once to check it, then calls [stopBackend], merges
 * the database in one transaction, and writes preference files only after that commits.
 */
class AndroidDataMigration internal constructor(
    context: Context,
    private val database: SQLiteDataSyncStore,
    private val stopBackend: suspend () -> Unit,
    private val iterations: Int = MigrationCrypto.ITERATIONS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val databaseName: String = "data-sync.sqlite",
) : DataMigration {
    private val context = context.applicationContext
    private val busy = Mutex()

    override suspend fun export(output: OutputStream, sections: Set<MigrationSection>, password: CharArray?) = busy.withLock {
        withContext(Dispatchers.IO) {
            if (sections.isEmpty()) throw MigrationException("migration_nothing_selected")
            translated {
                val sealed = password?.takeIf { it.isNotEmpty() }?.let { MigrationCrypto.seal(output, it, iterations) } ?: output
                ZipOutputStream(BufferedOutputStream(sealed, MigrationCrypto.CHUNK_BYTES)).use { zip ->
                    zip.entry(MANIFEST, manifest(sections).toString().toByteArray())
                    FILES.filter { it.section in sections }.forEach { item -> read(item.path)?.let { zip.entry(item.entry, it) } }
                    if (MigrationSection.SETTINGS in sections) zip.entry(SETTINGS_PREFERENCES, settingsJson().toString().toByteArray())
                    if (MigrationSection.SOS in sections) sosWatchFiles().forEach { (name, bytes) -> zip.entry(SOS_WATCH + name, bytes) }
                    if (MigrationSection.ADB in sections)
                        zip.entry(ADB_PREFERENCES, preferencesJson(context.getSharedPreferences(ADB_FILE, Context.MODE_PRIVATE)).toString().toByteArray())
                    if (MigrationSection.PAIRING in sections) connectionJson()?.let { zip.entry(PAIRING_CONNECTION, it.toString().toByteArray()) }
                    val tables = MigrationTables.selected(sections)
                    if (tables.isNotEmpty()) database.readDatabase { db -> tables.forEach { (name, columns) -> writeTable(zip, db, name, columns) } }
                }
            }
        }
    }

    override suspend fun inspect(open: () -> InputStream, password: CharArray?): MigrationManifest = withContext(Dispatchers.IO) {
        translated { unpack(open, password) { zip, encrypted -> readManifest(zip, encrypted) } }
    }

    override suspend fun import(open: () -> InputStream, password: CharArray?, sections: Set<MigrationSection>) = busy.withLock {
        withContext(Dispatchers.IO) {
            if (sections.isEmpty()) throw MigrationException("migration_nothing_selected")
            translated { pass(open, password, sections, null) }
            try { stopBackend() } catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                throw MigrationException("migration_failed", problem, restartRequired = true)
            }
            try {
                translated {
                    val files = SQLiteDataSyncStore(context, databaseName).use { store -> pass(open, password, sections, store) }
                    writeFiles(files)
                }
            } catch (problem: MigrationException) { throw MigrationException(problem.code, problem, restartRequired = true) }
        }
    }

    /** One read of the file. Without [store] it only checks; with it, rows go into one transaction. */
    private suspend fun pass(open: () -> InputStream, password: CharArray?, sections: Set<MigrationSection>,
        store: SQLiteDataSyncStore?): Map<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        val walk: (SQLiteDatabase?) -> Unit = { db ->
            unpack(open, password) { zip, encrypted ->
                val manifest = readManifest(zip, encrypted)
                if (!manifest.sections.containsAll(sections)) throw MigrationException("migration_section_missing")
                val merge = db?.let { TableMerge(it, LocalHealthProfile.id(context), sections) }
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (name.startsWith(TABLES) && name.endsWith(".jsonl")) {
                        val table = name.removePrefix(TABLES).removeSuffix(".jsonl")
                        val columns = MigrationTables.columns(table) ?: throw MigrationException("migration_damaged")
                        if (MigrationTables.section(table) !in sections) continue
                        lines(zip) { line -> MigrationTables.decodeRow(line, columns, manifest.dataSchema).let { merge?.row(table, it) } }
                    } else {
                        val section = sectionOf(name) ?: continue // A file this version doesn't know is left alone.
                        if (section !in sections) continue
                        val bytes = readLimited(zip, limitOf(name))
                        try { validate(name, bytes) } catch (problem: Exception) {
                            if (problem is MigrationException) throw problem
                            throw MigrationException("migration_damaged", problem)
                        }
                        files[name] = bytes
                    }
                }
                if (MigrationSection.PAIRING in sections && PAIRING_IDENTITY !in files) throw MigrationException("migration_damaged")
                merge?.finish()
            }
        }
        if (store == null) walk(null) else store.withDatabase { db ->
            db.beginTransaction()
            try { walk(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
        }
        return files
    }

    // ---- File entries

    private class FileItem(val section: MigrationSection, val entry: String, val path: String, val maxBytes: Int)

    private fun sectionOf(name: String): MigrationSection? = FILES.firstOrNull { it.entry == name }?.section ?: when {
        name == SETTINGS_PREFERENCES -> MigrationSection.SETTINGS
        name == ADB_PREFERENCES -> MigrationSection.ADB
        name == PAIRING_CONNECTION -> MigrationSection.PAIRING
        name.startsWith(SOS_WATCH) && SOS_FILE_NAME.matches(name.removePrefix(SOS_WATCH)) -> MigrationSection.SOS
        else -> null
    }
    private fun limitOf(name: String) = FILES.firstOrNull { it.entry == name }?.maxBytes ?: SMALL_BYTES

    private fun validate(name: String, bytes: ByteArray) {
        when (name) {
            "settings/notification-sync.settings" -> NotificationPreferencesCodec.decode(bytes)
            "settings/media-controls.settings" -> MediaPreferencesCodec.decode(bytes)
            "settings/device-display.settings", "settings/find-devices.settings" -> Properties().load(ByteArrayInputStream(bytes))
            "settings/calling-settings-v1.bin" -> require(bytes.size == 5 && bytes.copyOf(4).contentEquals(CALLING_MAGIC) && bytes[4].toInt() in 0..1)
            "settings/dnd-v1.json" -> dnd(bytes)
            "sos/sos-assistance-v1.json" -> json(bytes).let { require(it["version"]?.jsonPrimitive?.int == 1 && it["enabled"]?.jsonPrimitive?.booleanOrNull != null) }
            "adb/host-key.pem" -> require(bytes.isNotEmpty() && String(bytes, Charsets.US_ASCII).trimStart().startsWith("-----BEGIN"))
            "pairing/identity.bin" -> IdentityDocumentCodec.decode(bytes)
            "pairing/toolbox-bridge-key.bin" -> require(bytes.size == 32)
            "pairing/notification-revision.counter" -> require(bytes.size == 8 && counter(bytes) >= 0)
            SETTINGS_PREFERENCES -> json(bytes).let { value ->
                preferenceValues(value.getValue("app").jsonObject); value.getValue("locales").jsonPrimitive.content
            }
            ADB_PREFERENCES -> preferenceValues(json(bytes))
            PAIRING_CONNECTION -> json(bytes).let { value ->
                PeerAddress(value.getValue("peer").jsonPrimitive.content); value.getValue("reconnect").jsonPrimitive.boolean
            }
            else -> if (name.startsWith(SOS_WATCH)) SosCodec.settings(bytes)
        }
    }

    /** Preference files take the file's values. Counters only move forward, as the watch expects. */
    private suspend fun writeFiles(files: Map<String, ByteArray>) {
        for ((name, bytes) in files) {
            val item = FILES.firstOrNull { it.entry == name }
            when {
                name == "pairing/notification-revision.counter" ->
                    write(item!!.path, counterBytes(maxOf(counter(bytes), read(item.path)?.let { counter(it) } ?: 0L)))
                name == "settings/dnd-v1.json" -> write(item!!.path, mergedDnd(bytes, read(item.path)))
                item != null -> write(item.path, bytes)
                name == SETTINGS_PREFERENCES -> json(bytes).let { value ->
                    writePreferences(context.getSharedPreferences(APP_FILE, Context.MODE_PRIVATE), value.getValue("app").jsonObject)
                    context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                        LocaleList.forLanguageTags(value.getValue("locales").jsonPrimitive.content)
                }
                name == ADB_PREFERENCES -> writePreferences(context.getSharedPreferences(ADB_FILE, Context.MODE_PRIVATE), json(bytes))
                name == PAIRING_CONNECTION -> json(bytes).let { value ->
                    // The old app's association ID belongs to that app; the service adopts this app's own once there is one.
                    val intent = ConnectionIntent(UUID.randomUUID().toString(), PeerAddress(value.getValue("peer").jsonPrimitive.content),
                        null, value.getValue("reconnect").jsonPrimitive.boolean)
                    AtomicFileConnectionIntentStore(context).write(intent)
                }
                name.startsWith(SOS_WATCH) -> write(SOS_DIRECTORY + "/" + name.removePrefix(SOS_WATCH), bytes)
            }
        }
    }

    private fun read(path: String): ByteArray? = try { AtomicFile(File(context.noBackupFilesDir, path)).readFully() }
        catch (_: FileNotFoundException) { null }

    private fun write(path: String, bytes: ByteArray) {
        val file = File(context.noBackupFilesDir, path)
        file.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("Cannot create a data directory") }
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) } catch (problem: Throwable) { atomic.failWrite(stream); throw problem }
    }

    private fun sosWatchFiles(): List<Pair<String, ByteArray>> =
        File(context.noBackupFilesDir, SOS_DIRECTORY).listFiles().orEmpty()
            .map { it.name.removeSuffix(".bak") }.distinct().filter(SOS_FILE_NAME::matches).sorted()
            .mapNotNull { name -> read("$SOS_DIRECTORY/$name")?.let { name to it } }

    private fun connectionJson(): JsonObject? {
        val intent = read(CONNECTION_PATH)?.let(ConnectionIntentCodec::decode) ?: return null
        val peer = intent.peer ?: return null
        return buildJsonObject { put("peer", peer.value); put("reconnect", intent.reconnectEnabled) }
    }

    private fun settingsJson() = buildJsonObject {
        put("app", preferencesJson(context.getSharedPreferences(APP_FILE, Context.MODE_PRIVATE)))
        put("locales", context.getSystemService(LocaleManager::class.java)?.applicationLocales?.toLanguageTags().orEmpty())
    }

    private fun dnd(bytes: ByteArray): JsonObject = json(bytes).also { value ->
        DndSyncMode.valueOf(value.getValue("mode").jsonPrimitive.content)
        require(value.getValue("revision").jsonPrimitive.long >= 0)
    }
    private fun mergedDnd(incoming: ByteArray, local: ByteArray?): ByteArray {
        val revision = local?.let { runCatching { dnd(it).getValue("revision").jsonPrimitive.long }.getOrNull() } ?: 0L
        val value = dnd(incoming)
        val merged = JsonObject(value + ("revision" to JsonPrimitive(maxOf(revision, value.getValue("revision").jsonPrimitive.long))))
        return merged.toString().toByteArray()
    }

    // ---- Database tables

    private fun writeTable(zip: ZipOutputStream, db: SQLiteDatabase, table: String, columns: List<String>) {
        zip.putNextEntry(ZipEntry("$TABLES$table.jsonl"))
        val writer = zip.writer(Charsets.UTF_8)
        val order = if (table == "observation_samples") "sample_id" else "rowid"
        db.query(table, columns.toTypedArray(), null, null, null, null, order).use { cursor ->
            while (cursor.moveToNext()) { writer.write(MigrationTables.encodeRow(cursor, columns).toString()); writer.write('\n'.code) }
        }
        writer.flush()
        zip.closeEntry()
    }

    /** Health rows merge by key, keeping the newer copy; [MigrationSection.PAIRING] replaces sync state. */
    private class TableMerge(private val db: SQLiteDatabase, private val profile: String, sections: Set<MigrationSection>) {
        private var samples = false

        init {
            // Rows may refer to ones later in the file; keys are checked when the transaction commits.
            db.execSQL("PRAGMA defer_foreign_keys = ON")
            // Scopes cascade to sequences, cursors, items, assets and their links.
            if (MigrationSection.PAIRING in sections) db.delete("scopes", null, null)
            if (MigrationSection.HEALTH in sections) {
                db.execSQL("""CREATE TEMP TABLE IF NOT EXISTS migration_samples (peer TEXT NOT NULL, kind TEXT NOT NULL,
                    received_at INTEGER NOT NULL, battery INTEGER, steps INTEGER, goal INTEGER)""")
                db.execSQL("DELETE FROM temp.migration_samples")
            }
        }

        fun row(table: String, values: ContentValues) {
            when (table) {
                "local_health_sources" -> newer(table, values.also { it.put("local_user", profile) }, SOURCE_KEY, "last_success")
                "local_health_records" -> newer(table, values.also { it.put("local_user", profile) }, RECORD_KEY, "updated_at")
                "cloud_cache" -> newer(table, values, CLOUD_KEY, "updated_at")
                "cloud_sync_windows" -> db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_IGNORE)
                "observation_samples" -> { db.insertOrThrow("temp.migration_samples", null, values); samples = true }
                else -> db.insertOrThrow(table, null, values)
            }
        }

        /** Updates in place: replacing a source row would cascade and delete its records. */
        private fun newer(table: String, values: ContentValues, keys: List<String>, stamp: String) {
            val where = keys.joinToString(" AND ") { "$it=?" }
            val args = keys.map { values.getAsString(it) ?: throw MigrationException("migration_damaged") }.toTypedArray()
            val current = db.query(table, arrayOf(stamp), where, args, null, null, null).use { cursor ->
                if (!cursor.moveToFirst()) null else if (cursor.isNull(0)) Long.MIN_VALUE else cursor.getLong(0)
            }
            when {
                current == null -> db.insertOrThrow(table, null, values)
                (values.getAsLong(stamp) ?: Long.MIN_VALUE) > current -> db.update(table, values, where, args)
            }
        }

        fun finish() {
            if (samples) {
                val columns = "peer, kind, received_at, battery, steps, goal"
                db.execSQL("INSERT INTO temp.migration_samples ($columns) SELECT $columns FROM main.observation_samples")
                db.execSQL("CREATE TEMP TABLE migration_merged AS SELECT DISTINCT $columns FROM temp.migration_samples")
                db.execSQL("DELETE FROM main.observation_samples")
                db.execSQL("INSERT INTO main.observation_samples ($columns) SELECT $columns FROM temp.migration_merged ORDER BY received_at, rowid")
                db.execSQL("""DELETE FROM main.observation_samples WHERE sample_id <= (SELECT sample_id FROM main.observation_samples
                    ORDER BY sample_id DESC LIMIT 1 OFFSET $MAX_SAMPLES)""")
                db.execSQL("DROP TABLE temp.migration_merged")
            }
            db.execSQL("DROP TABLE IF EXISTS temp.migration_samples")
        }

        private companion object {
            val SOURCE_KEY = listOf("local_user", "peer", "kind")
            val RECORD_KEY = listOf("local_user", "peer", "kind", "record_key")
            val CLOUD_KEY = listOf("owner_key", "kind", "record_key")
            /** The same bound as [dev.ticbox.companion.platform.data.SQLiteWatchHistoryStore]. */
            const val MAX_SAMPLES = 100_000
        }
    }

    // ---- Container

    private fun manifest(sections: Set<MigrationSection>) = buildJsonObject {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        put("format", FORMAT); put("schema", MigrationTables.SCHEMA)
        put("app", info.versionName.orEmpty()); put("versionCode", info.longVersionCode)
        put("createdAt", clock())
        putJsonArray("sections") { MigrationSection.entries.filter { it in sections }.forEach { add(it.name) } }
    }

    private fun readManifest(zip: ZipInputStream, encrypted: Boolean): MigrationManifest {
        val entry = zip.nextEntry
        if (entry?.name != MANIFEST) throw MigrationException("migration_not_a_backup")
        val value = try { json(readLimited(zip, SMALL_BYTES)) } catch (problem: Exception) {
            if (problem is MigrationException) throw problem
            throw MigrationException("migration_damaged", problem)
        }
        try {
            val format = value.getValue("format").jsonPrimitive.int
            val schema = value.getValue("schema").jsonPrimitive.int
            if (format > FORMAT || schema > MigrationTables.SCHEMA) throw MigrationException("migration_newer")
            if (format < 1 || schema < MigrationTables.MIN_SCHEMA) throw MigrationException("migration_older")
            return MigrationManifest(format, schema, value.getValue("app").jsonPrimitive.content,
                value.getValue("createdAt").jsonPrimitive.long,
                value.getValue("sections").jsonArray.map { MigrationSection.valueOf(it.jsonPrimitive.content) }.toSet(), encrypted)
        } catch (problem: MigrationException) { throw problem }
        catch (problem: Exception) { throw MigrationException("migration_damaged", problem) }
    }

    private fun <T> unpack(open: () -> InputStream, password: CharArray?, block: (ZipInputStream, Boolean) -> T): T =
        BufferedInputStream(open(), MigrationCrypto.CHUNK_BYTES).use { input ->
            input.mark(MigrationCrypto.magicSize)
            val head = input.readNBytes(MigrationCrypto.magicSize)
            input.reset()
            val encrypted = MigrationCrypto.isEncrypted(head)
            val plain = when {
                encrypted && (password == null || password.isEmpty()) -> throw MigrationException("migration_password_required")
                encrypted -> MigrationCrypto.open(input, password!!)
                head.size >= 4 && head.copyOf(4).contentEquals(ZIP_MAGIC) -> input
                else -> throw MigrationException("migration_not_a_backup")
            }
            ZipInputStream(BufferedInputStream(plain, MigrationCrypto.CHUNK_BYTES)).use { block(it, encrypted) }
        }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val bytes = input.readNBytes(limit + 1)
        if (bytes.size > limit) throw MigrationException("migration_damaged")
        return bytes
    }

    /** Lines of one entry, each at most [MAX_LINE] characters, so a hostile file can't exhaust memory. */
    private fun lines(input: InputStream, each: (String) -> Unit) {
        val reader: Reader = InputStreamReader(input, Charsets.UTF_8).buffered(MigrationCrypto.CHUNK_BYTES)
        val line = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            var start = 0
            for (index in 0 until count) {
                if (buffer[index] != '\n') continue
                line.appendRange(buffer, start, index); start = index + 1
                if (line.isNotEmpty()) each(line.toString())
                line.setLength(0)
            }
            line.appendRange(buffer, start, count)
            if (line.length > MAX_LINE) throw MigrationException("migration_damaged")
        }
        if (line.isNotEmpty()) each(line.toString())
    }

    private inline fun <T> translated(block: () -> T): T = try { block() } catch (problem: Exception) {
        throw when (problem) {
            is CancellationException, is MigrationException -> problem
            is SQLiteFullException -> MigrationException("migration_storage_full", problem)
            is SQLiteConstraintException -> MigrationException("migration_damaged", problem)
            is ZipException -> MigrationException("migration_damaged", problem)
            is IOException -> MigrationException(if (problem.cause is MigrationException) (problem.cause as MigrationException).code
                else "migration_io", problem)
            else -> MigrationException("migration_failed", problem)
        }
    }

    private companion object {
        /** Raise when the container layout changes; table rows are versioned by the manifest's schema. */
        const val FORMAT = 1
        const val MANIFEST = "manifest.json"
        const val TABLES = "tables/"
        const val SETTINGS_PREFERENCES = "settings/preferences.json"
        const val ADB_PREFERENCES = "adb/preferences.json"
        const val PAIRING_CONNECTION = "pairing/connection.json"
        const val PAIRING_IDENTITY = "pairing/identity.bin"
        const val SOS_WATCH = "sos/watch/"
        const val SOS_DIRECTORY = "companion/sos-watch"
        const val CONNECTION_PATH = "companion/connection-intent-v1.bin"
        const val APP_FILE = "frontend-appearance"
        const val ADB_FILE = "toolbox-adb-endpoint"
        const val SMALL_BYTES = 1024 * 1024
        const val MAX_LINE = 96 * 1024 * 1024
        val SOS_FILE_NAME = Regex("[0-9a-f]{64}\\.json")
        val ZIP_MAGIC = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
        val CALLING_MAGIC = byteArrayOf(0x54, 0x43, 0x43, 0x31)
        val FILES = listOf(
            FileItem(MigrationSection.SETTINGS, "settings/notification-sync.settings", "notification-sync.settings", NotificationPreferencesCodec.MAX_BYTES),
            FileItem(MigrationSection.SETTINGS, "settings/media-controls.settings", "media-controls.settings", MediaPreferencesCodec.MAX_BYTES),
            FileItem(MigrationSection.SETTINGS, "settings/device-display.settings", "device-display.settings", 64 * 1024),
            FileItem(MigrationSection.SETTINGS, "settings/find-devices.settings", "find-devices.settings", 64 * 1024),
            FileItem(MigrationSection.SETTINGS, "settings/calling-settings-v1.bin", "companion/calling-settings-v1.bin", 5),
            FileItem(MigrationSection.SETTINGS, "settings/dnd-v1.json", "companion/dnd-v1.json", 64 * 1024),
            FileItem(MigrationSection.SOS, "sos/sos-assistance-v1.json", "companion/sos-assistance-v1.json", 64 * 1024),
            FileItem(MigrationSection.ADB, "adb/host-key.pem", "toolbox-adb/host-key.pem", 64 * 1024),
            FileItem(MigrationSection.PAIRING, PAIRING_IDENTITY, "companion/identity.bin", IdentityDocumentCodec.MAX_DOCUMENT_BYTES),
            FileItem(MigrationSection.PAIRING, "pairing/toolbox-bridge-key.bin", "companion/toolbox-bridge-key.bin", 32),
            FileItem(MigrationSection.PAIRING, "pairing/notification-revision.counter", "notification-global-revision.counter", 8),
        )

        fun ZipOutputStream.entry(name: String, bytes: ByteArray) { putNextEntry(ZipEntry(name)); write(bytes); closeEntry() }
        fun json(bytes: ByteArray): JsonObject = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        fun counter(bytes: ByteArray) = DataInputStream(ByteArrayInputStream(bytes)).readLong()
        fun counterBytes(value: Long): ByteArray = ByteArrayOutputStream().also { DataOutputStream(it).writeLong(value) }.toByteArray()

        /** Typed, so an Int read back as an Int: SharedPreferences refuses a value of the wrong type. */
        fun preferencesJson(preferences: SharedPreferences) = buildJsonObject {
            preferences.all.toSortedMap().forEach { (key, value) ->
                when (value) {
                    is Boolean -> put(key, buildJsonObject { put("boolean", value) })
                    is Int -> put(key, buildJsonObject { put("int", value) })
                    is Long -> put(key, buildJsonObject { put("long", value) })
                    is Float -> put(key, buildJsonObject { put("float", value) })
                    is String -> put(key, buildJsonObject { put("string", value) })
                    is Set<*> -> put(key, buildJsonObject { putJsonArray("strings") { value.forEach { add(it as String) } } })
                }
            }
        }
        fun preferenceValues(value: JsonObject): Map<String, Any> = value.mapValues { (_, typed) ->
            val (type, content) = typed.jsonObject.entries.single()
            when (type) {
                "boolean" -> content.jsonPrimitive.boolean
                "int" -> content.jsonPrimitive.int
                "long" -> content.jsonPrimitive.long
                "float" -> content.jsonPrimitive.float
                "string" -> content.jsonPrimitive.also { require(it.isString) }.content
                "strings" -> content.jsonArray.map { it.jsonPrimitive.content }.toSet()
                else -> throw IllegalArgumentException("Unknown preference type")
            }
        }
        fun writePreferences(preferences: SharedPreferences, value: JsonObject) {
            val editor = preferences.edit()
            preferenceValues(value).forEach { (key, content) ->
                @Suppress("UNCHECKED_CAST")
                when (content) {
                    is Boolean -> editor.putBoolean(key, content)
                    is Int -> editor.putInt(key, content)
                    is Long -> editor.putLong(key, content)
                    is Float -> editor.putFloat(key, content)
                    is String -> editor.putString(key, content)
                    is Set<*> -> editor.putStringSet(key, content as Set<String>)
                }
            }
            if (!editor.commit()) throw IOException("Preferences could not be saved")
        }
    }
}
