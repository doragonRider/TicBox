package dev.ticbox.companion.healthbridge

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.security.MessageDigest
import java.util.Locale

internal class HealthJournalException(val code: String) : Exception(code)

internal data class VerifiedHealthJournalSource(
    val source: String,
    val file: File,
    val uid: Int,
    val providerVersion: String,
)

/**
 * Admission for writing our independent journal into an existing OEM database. This neither opens
 * nor repairs a database and never changes a pragma, Room identity, or OEM schema object.
 */
internal object HealthJournalProfiles {
    private const val JOURNAL_PREFIX = "ticwatch_journal_v1_"
    private const val MAX_APK_BYTES = 256L * 1024 * 1024
    private const val MAX_SCHEMA_OBJECTS = 512
    private const val MAX_SCHEMA_BYTES = 512 * 1024

    private data class Apk(val code: Long, val name: String, val sha256: String)
    private data class Source(
        val packageName: String,
        val database: String,
        val version: Int,
        val hashes: Set<String>,
        val apks: List<Apk>,
    )

    private val health = Source(
        "com.mobvoi.wear.health.at", "health_common.db", 5,
        setOf("993724866f858147fc2b385340b6c6a2", "d54ea02cfe7bc87bfe1f4aad8d32d23a"),
        listOf(
            Apk(50503292, "5.5.0-3292.2931", "39969a876dcd716bd9e868a446626f762f8c23c7c90d567a061df9e405666e25"),
            Apk(50603234, "5.6.0-3234.2686", "276264832680b5da9fc7bae260ff521e7df6d49c7e04fa40c00f64e3ef96c4db"),
        ),
    )
    private val fitness = Source(
        "com.mobvoi.wear.fitness.at", "fitness.db", 1021,
        setOf("516350121b90186f5b7326fe366cb4f0", "f7fe416659310e31e28e1c8b6837c16a"),
        listOf(Apk(50502536, "5.5.0-2536.2932", "4682ad6b0bcbb33394e445d4d4066512446062508924903822ef25071d03faae")),
    )

    private fun source(name: String): Source = when (name) {
        "health" -> health
        "fitness" -> fitness
        else -> throw HealthJournalException("journal_source_unsupported")
    }

    /** Removal deliberately permits an updated APK: persistent triggers must remain removable. */
    fun verify(context: Context, source: String, forRemoval: Boolean = false): VerifiedHealthJournalSource =
        guarded("journal_source_unavailable") {
            val expected = source(source)
            val pm = context.packageManager
            val info = try { pm.getApplicationInfo(expected.packageName, 0) }
            catch (_: PackageManager.NameNotFoundException) { fail("journal_source_not_installed") }
            val pkg = try { pm.getPackageInfo(expected.packageName, 0) }
            catch (_: PackageManager.NameNotFoundException) { fail("journal_source_not_installed") }
            checkInstalled(expected, info, pkg)
            if (!forRemoval) {
                if (!info.splitSourceDirs.isNullOrEmpty()) fail("journal_provider_unsupported")
                val profile = expected.apks.singleOrNull { it.code == pkg.longVersionCode && it.name == pkg.versionName }
                    ?: fail("journal_provider_unsupported")
                val apk = File(info.sourceDir ?: fail("journal_provider_unavailable"))
                val before = regularFile(apk, null, "journal_provider_unavailable")
                if (before.st_size !in 1..MAX_APK_BYTES) fail("journal_provider_integrity_mismatch")
                val digest = apk.inputStream().buffered().use { input ->
                    val hash = MessageDigest.getInstance("SHA-256")
                    val bytes = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(bytes)
                        if (count < 0) break
                        total += count
                        if (total > MAX_APK_BYTES) fail("journal_provider_integrity_mismatch")
                        hash.update(bytes, 0, count)
                    }
                    if (total != before.st_size) fail("journal_provider_integrity_mismatch")
                    hex(hash.digest())
                }
                if (!sameFile(before, regularFile(apk, null, "journal_provider_unavailable")) || digest != profile.sha256) {
                    fail("journal_provider_integrity_mismatch")
                }
            }
            // Resolve Android's system data-directory aliases once, then forbid links below the
            // PackageManager-owned app directory. Neither a caller path nor a database name is used.
            val directory = File(info.dataDir ?: fail("journal_path_invalid")).canonicalFile
            if (!directory.isAbsolute || directory.parentFile == null) fail("journal_path_invalid")
            ownedDirectory(directory, info.uid)
            val databases = File(directory, "databases")
            ownedDirectory(databases, info.uid)
            val database = File(databases, expected.database)
            regularFile(database, info.uid, "journal_database_missing")
            if (database.canonicalFile != database) fail("journal_path_invalid")
            for (suffix in listOf("-wal", "-shm", "-journal")) {
                val sidecar = File(database.path + suffix)
                val stat = lstatOrNull(sidecar)
                if (stat != null) checkRegular(sidecar, stat, info.uid)
            }
            // Refuse an update/reinstall racing the relatively expensive APK hash.
            val current = pm.getApplicationInfo(expected.packageName, 0)
            val currentPkg = pm.getPackageInfo(expected.packageName, 0)
            checkInstalled(expected, current, currentPkg)
            if (current.uid != info.uid || current.dataDir != info.dataDir || current.sourceDir != info.sourceDir ||
                currentPkg.longVersionCode != pkg.longVersionCode || currentPkg.versionName != pkg.versionName ||
                !current.splitSourceDirs.contentEquals(info.splitSourceDirs)) fail("journal_provider_changed")
            VerifiedHealthJournalSource(source, database, info.uid, pkg.versionName ?: "code:${pkg.longVersionCode}")
        }

    /** Exact manifest/code/hash pins are independently testable without executing an original APK. */
    internal fun profileMatches(source: String, packageName: String, code: Long, name: String?, sha256: String): Boolean {
        val expected = source(source)
        return packageName == expected.packageName && expected.apks.any { it.code == code && it.name == name && it.sha256 == sha256 }
    }

    private fun checkInstalled(expected: Source, info: ApplicationInfo, pkg: PackageInfo) {
        if (info.packageName != expected.packageName || pkg.packageName != expected.packageName || info.uid <= 0 ||
            pkg.applicationInfo?.let { it.uid != info.uid || it.packageName != info.packageName || it.dataDir != info.dataDir || it.sourceDir != info.sourceDir } != false) {
            fail("journal_provider_identity_mismatch")
        }
    }

    private fun ownedDirectory(file: File, uid: Int) {
        val stat = lstatOrNull(file) ?: fail("journal_database_missing")
        if (!OsConstants.S_ISDIR(stat.st_mode) || file.canonicalFile != file) fail("journal_path_invalid")
        if (stat.st_uid != uid) fail("journal_source_uid_mismatch")
    }

    private fun regularFile(file: File, uid: Int?, missing: String): StructStat {
        val stat = lstatOrNull(file) ?: fail(missing)
        checkRegular(file, stat, uid)
        return stat
    }

    private fun checkRegular(file: File, stat: StructStat, uid: Int?) {
        if (!file.isAbsolute || !OsConstants.S_ISREG(stat.st_mode) || file.canonicalFile != file.absoluteFile) fail("journal_path_invalid")
        if (uid != null && stat.st_uid != uid) fail("journal_source_uid_mismatch")
    }

    private fun lstatOrNull(file: File): StructStat? = try { Os.lstat(file.path) }
    catch (problem: ErrnoException) {
        if (problem.errno == OsConstants.ENOENT) null else throw problem
    }

    private fun sameFile(a: StructStat, b: StructStat): Boolean = a.st_dev == b.st_dev && a.st_ino == b.st_ino &&
        a.st_size == b.st_size && a.st_uid == b.st_uid && a.st_mtime == b.st_mtime && a.st_ctime == b.st_ctime

    private data class Column(val type: String, val notNull: Boolean = false, val default: String? = null, val pk: Int = 0)
    private data class Index(val name: String?, val origin: String, val unique: Boolean, val columns: List<String>)
    private data class Table(val name: String, val columns: Map<String, Column>, val autoIncrement: String? = null, val unique: Set<String> = emptySet(), val indexes: List<Index> = emptyList())

    private fun cols(vararg columns: Pair<String, Column>) = linkedMapOf(*columns)
    private fun int(notNull: Boolean = false, default: String? = null, pk: Int = 0) = Column("INTEGER", notNull, default, pk)
    private fun text(notNull: Boolean = false, default: String? = null, pk: Int = 0) = Column("TEXT", notNull, default, pk)
    private fun real() = Column("REAL")
    private fun index(name: String, vararg columns: String) = Index(name, "c", false, columns.toList())
    private fun pkIndex(column: String) = Index(null, "pk", true, listOf(column))
    private val master = Table("room_master_table", cols("id" to int(pk = 1), "identity_hash" to text()))
    private val healthTables = listOf(
        Table("data_point", cols(
            "_id" to int(true, pk = 1), "values" to text(true, "text:"), "device_id" to text(true, "text:"), "wwid" to text(),
            "type" to int(true, "int:-1"), "time_to" to int(true), "time_from" to int(true), "synced" to int(true, "int:0"), "deleted" to int(true, "int:0"),
        ), "_id", indexes = listOf(index("point_query", "wwid", "type", "time_to", "time_from"), index("point_sync", "wwid", "type", "synced"))),
        Table("data_session", cols(
            "id" to text(true, pk = 1), "device_id" to text(true, "text:"), "wwid" to text(), "type" to int(true, "int:-1"),
            "time_to" to int(true), "time_from" to int(true), "synced" to int(true, "int:0"), "deleted" to int(true, "int:0"),
        ), indexes = listOf(pkIndex("id"), index("session_sync", "wwid", "type", "synced"), index("session_query", "wwid", "type", "time_to", "time_from"))),
        Table("data_source", cols(
            "name" to text(true, pk = 1), "wwid" to text(true, "text:"), "device_id" to text(true, "text:"), "device_model" to text(true, "text:"),
            "device_type" to text(), "device_version" to text(),
        ), indexes = listOf(pkIndex("name"))),
    )
    private val fitnessPoint = Table("POINT", cols(
        "_id" to int(pk = 1), "TIME" to int(true), "HEART" to int(), "STEP" to int(), "LAT" to Column("REAL", true),
        "LON" to Column("REAL", true), "ACC" to Column("REAL", true), "DISTANCE" to int(), "SPEED" to real(), "CALORIE" to real(),
        "RECORD_ID" to int(), "RESUME" to int(), "SWIM_TRIPS" to real(), "SWIM_DISTANCE" to int(), "SWIM_STROKE" to int(),
        "SWIM_TYPE" to int(), "TIMESTAMP" to int(true), "ELEVATION" to real(), "STRIDE_FREQ" to int(),
    ))
    private val fitnessRecord = Table("RECORD", cols(
        "_id" to int(pk = 1), "HASH" to text(), "TYPE" to int(true), "TARGET_TYPE" to int(), "TARGET_VALUE" to real(), "ACCOUNT_ID" to text(),
        "SYNCED" to int(true), "DELETED" to int(true), "START_TIME" to int(), "END_TIME" to int(), "TIMESTAMP" to int(), "DURATION" to int(true),
        "DISTANCE" to int(), "CALORIE" to real(), "STEP" to int(), "HEART" to int(), "GROUPS" to text(), "SWIM_TRIPS" to real(), "SWIM_DISTANCE" to int(),
        "SWIM_POOL_LENGTH" to int(), "SWIM_STROKE" to int(), "DEVICE_ID" to text(), "SESSION_MODE" to int(), "MAX_ELEVATION" to real(),
        "MIN_ELEVATION" to real(), "CUMULATIVE_UP" to real(), "CUMULATIVE_DOWN" to real(), "SCORE" to real(), "EXTRA" to text(),
    ), "_id", indexes = listOf(index("IDX_RECORD_HASH", "HASH")))

    fun validateDatabase(db: SQLiteDatabase, source: String) = validateDatabase(FileJournalSql(db), source)
    fun schemaFingerprint(db: SQLiteDatabase, source: String) = schemaFingerprint(FileJournalSql(db), source)

    fun validateDatabase(db: JournalSql, source: String) = guarded("journal_schema_unavailable") {
        val profile = source(source)
        if (db.version != profile.version) fail("journal_schema_unsupported")
        validateTable(db, master)
        val hash = db.rawQuery("SELECT identity_hash FROM room_master_table WHERE id=42", null).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) fail("journal_schema_unsupported")
            cursor.getString(0).also { if (cursor.moveToNext()) fail("journal_schema_unsupported") }
        }
        if (hash !in profile.hashes) fail("journal_schema_unsupported")
        if (source == "health") healthTables.forEach { validateTable(db, it) }
        else {
            val legacy = declaredTable(db, "POINT").autoIncrement == "_id"
            validateTable(db, if (legacy) fitnessPoint.copy(autoIncrement = "_id") else fitnessPoint)
            validateTable(db, if (legacy) fitnessRecord.copy(
                unique = setOf("HASH"), indexes = fitnessRecord.indexes + Index(null, "u", true, listOf("HASH")),
            ) else fitnessRecord)
        }
    }

    /** Full OEM schema identity, excluding only the installer's independently checked namespace. */
    fun schemaFingerprint(db: JournalSql, source: String): String = guarded("journal_schema_unavailable") {
        validateDatabase(db, source)
        val hash = MessageDigest.getInstance("SHA-256")
        fun add(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            hash.update(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(), (bytes.size ushr 8).toByte(), bytes.size.toByte()))
            hash.update(bytes)
        }
        add(source); add(db.version.toString())
        db.rawQuery("SELECT identity_hash FROM room_master_table WHERE id=42", null).use { it.moveToFirst(); add(it.getString(0)) }
        var objects = 0; var bytes = 0
        db.rawQuery("SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name,tbl_name", null).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                if (name.startsWith(JOURNAL_PREFIX)) continue
                if (++objects > MAX_SCHEMA_OBJECTS) fail("journal_schema_unsupported")
                for (column in 0..3) {
                    val value = if (cursor.isNull(column)) "<null>" else cursor.getString(column)
                    bytes += value.toByteArray(Charsets.UTF_8).size
                    if (bytes > MAX_SCHEMA_BYTES) fail("journal_schema_unsupported")
                    add(value)
                }
            }
        }
        hex(hash.digest())
    }

    private fun validateTable(db: JournalSql, expected: Table) {
        val declared = declaredTable(db, expected.name)
        if (declared.columns != expected.columns || declared.autoIncrement != expected.autoIncrement || declared.unique != expected.unique) fail("journal_schema_unsupported")
        val actual = linkedMapOf<String, Column>()
        db.rawQuery("PRAGMA main.table_xinfo(${quote(expected.name)})", null).use { cursor ->
            val hidden = cursor.getColumnIndex("hidden")
            if (hidden < 0) fail("journal_schema_unsupported")
            while (cursor.moveToNext()) {
                if (cursor.getInt(hidden) != 0) fail("journal_schema_unsupported")
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
                val value = Column(cursor.getString(cursor.getColumnIndexOrThrow("type")).uppercase(Locale.ROOT),
                    cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) != 0,
                    if (cursor.isNull(defaultIndex)) null else defaultValue(tokenize(cursor.getString(defaultIndex))),
                    cursor.getInt(cursor.getColumnIndexOrThrow("pk")))
                if (actual.put(name, value) != null) fail("journal_schema_unsupported")
            }
        }
        if (actual != expected.columns) fail("journal_schema_unsupported")
        db.rawQuery("PRAGMA main.foreign_key_list(${quote(expected.name)})", null).use { if (it.moveToFirst()) fail("journal_schema_unsupported") }
        val indexes = mutableListOf<Index>()
        db.rawQuery("PRAGMA main.index_list(${quote(expected.name)})", null).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val origin = cursor.getString(cursor.getColumnIndexOrThrow("origin"))
                if (cursor.getInt(cursor.getColumnIndexOrThrow("partial")) != 0) fail("journal_schema_unsupported")
                val columns = mutableListOf<String>()
                db.rawQuery("PRAGMA main.index_xinfo(${quote(name)})", null).use { info ->
                    while (info.moveToNext()) {
                        if (info.getInt(info.getColumnIndexOrThrow("key")) == 0) continue
                        if (info.getInt(info.getColumnIndexOrThrow("cid")) < 0 || info.getInt(info.getColumnIndexOrThrow("desc")) != 0 ||
                            info.getString(info.getColumnIndexOrThrow("coll")) != "BINARY") fail("journal_schema_unsupported")
                        columns += info.getString(info.getColumnIndexOrThrow("name"))
                    }
                }
                indexes += Index(if (origin == "c") name else null, origin, cursor.getInt(cursor.getColumnIndexOrThrow("unique")) != 0, columns)
            }
        }
        if (indexes.size != expected.indexes.size || indexes.toSet() != expected.indexes.toSet()) fail("journal_schema_unsupported")
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='trigger' AND tbl_name=?", arrayOf(expected.name)).use { cursor ->
            while (cursor.moveToNext()) if (!cursor.getString(0).startsWith(JOURNAL_PREFIX)) fail("journal_schema_unsupported")
        }
    }

    private data class Token(val text: String, val quoted: Boolean = false)
    private fun Token.word(value: String) = !quoted && text.equals(value, ignoreCase = true)
    private fun quote(value: String) = "\"${value.replace("\"", "\"\"")}\""

    /** Small allowlist parser: rejects hidden CHECK/collation/conflict/generated/rowid clauses. */
    private fun declaredTable(db: JournalSql, name: String): Table {
        val sql = db.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) fail("journal_schema_unsupported")
            cursor.getString(0).also { if (cursor.moveToNext()) fail("journal_schema_unsupported") }
        }
        val tokens = tokenize(sql)
        var position = 0
        fun word(value: String) { if (position >= tokens.size || !tokens[position++].word(value)) fail("journal_schema_unsupported") }
        word("CREATE"); word("TABLE")
        if (tokens.getOrNull(position)?.word("IF") == true) { word("IF"); word("NOT"); word("EXISTS") }
        if (tokens.getOrNull(position++)?.text != name || tokens.getOrNull(position++)?.text != "(") fail("journal_schema_unsupported")
        val definitions = mutableListOf<List<Token>>()
        var start = position; var depth = 1
        while (position < tokens.size && depth != 0) {
            when (tokens[position].text) {
                "(" -> depth++
                ")" -> { depth--; if (depth == 0) definitions += tokens.subList(start, position) }
                "," -> if (depth == 1) { definitions += tokens.subList(start, position); start = position + 1 }
            }
            position++
        }
        if (depth != 0 || tokens.drop(position).any { it.text != ";" } || definitions.isEmpty()) fail("journal_schema_unsupported")
        val columns = linkedMapOf<String, Column>(); var primary = emptyList<String>(); var auto: String? = null; val unique = mutableSetOf<String>()
        for (definition in definitions) {
            if (definition.isEmpty()) fail("journal_schema_unsupported")
            if (definition.first().word("PRIMARY")) {
                if (primary.isNotEmpty() || definition.size < 5 || !definition[1].word("KEY") || definition[2].text != "(" || definition.last().text != ")") fail("journal_schema_unsupported")
                val body = definition.subList(3, definition.size - 1)
                if (body.indices.any { if (it % 2 == 0) body[it].text in setOf("(", ")", ",") else body[it].text != "," }) fail("journal_schema_unsupported")
                primary = body.filterIndexed { index, _ -> index % 2 == 0 }.map { it.text }
                continue
            }
            if (definition.size < 2) fail("journal_schema_unsupported")
            val column = definition[0].text; val type = definition[1].text.uppercase(Locale.ROOT)
            if (type !in setOf("INTEGER", "TEXT", "REAL")) fail("journal_schema_unsupported")
            var notNull = false; var pk = false; var default: String? = null; var p = 2
            while (p < definition.size) {
                when {
                    definition[p].word("NOT") -> { if (notNull || definition.getOrNull(p + 1)?.word("NULL") != true) fail("journal_schema_unsupported"); notNull = true; p += 2 }
                    definition[p].word("PRIMARY") -> { if (pk || definition.getOrNull(p + 1)?.word("KEY") != true) fail("journal_schema_unsupported"); pk = true; p += 2 }
                    definition[p].word("AUTOINCREMENT") -> { if (!pk || type != "INTEGER" || auto != null) fail("journal_schema_unsupported"); auto = column; p++ }
                    definition[p].word("UNIQUE") -> { if (!unique.add(column)) fail("journal_schema_unsupported"); p++ }
                    definition[p].word("DEFAULT") -> {
                        if (default != null) fail("journal_schema_unsupported")
                        val first = ++p
                        if (definition.getOrNull(p)?.text == "(") {
                            var nested = 0
                            do { val token = definition.getOrNull(p++) ?: fail("journal_schema_unsupported"); if (token.text == "(") nested++; if (token.text == ")") nested-- } while (nested > 0)
                        } else { if (definition.getOrNull(p)?.text in setOf("-", "+")) p++; p++ }
                        if (p > definition.size) fail("journal_schema_unsupported")
                        default = defaultValue(definition.subList(first, p))
                    }
                    else -> fail("journal_schema_unsupported")
                }
            }
            if (pk) { if (primary.isNotEmpty()) fail("journal_schema_unsupported"); primary = listOf(column) }
            if (columns.put(column, Column(type, notNull, default)) != null) fail("journal_schema_unsupported")
        }
        if (primary.distinct().size != primary.size || primary.any { it !in columns }) fail("journal_schema_unsupported")
        primary.forEachIndexed { index, column -> columns[column] = columns.getValue(column).copy(pk = index + 1) }
        return Table(name, columns, auto, unique)
    }

    private fun defaultValue(input: List<Token>): String {
        var tokens = input
        while (tokens.size >= 3 && tokens.first().text == "(" && tokens.last().text == ")") tokens = tokens.subList(1, tokens.size - 1)
        if (tokens.size == 1 && tokens[0].quoted) return "text:${tokens[0].text}"
        if (tokens.size == 1 && tokens[0].text.matches(Regex("[0-9]+"))) return "int:${tokens[0].text.toLongOrNull() ?: fail("journal_schema_unsupported")}"
        if (tokens.size == 2 && tokens[0].text in setOf("-", "+") && tokens[1].text.matches(Regex("[0-9]+"))) {
            val value = (tokens[0].text + tokens[1].text).toLongOrNull() ?: fail("journal_schema_unsupported")
            return "int:$value"
        }
        fail("journal_schema_unsupported")
    }

    private fun tokenize(sql: String): List<Token> {
        if (sql.length > MAX_SCHEMA_BYTES) fail("journal_schema_unsupported")
        val tokens = mutableListOf<Token>(); var p = 0
        while (p < sql.length) {
            val c = sql[p]
            when {
                c.isWhitespace() -> p++
                c in "'\"`[" -> {
                    val end = if (c == '[') ']' else c; p++; val text = StringBuilder(); var closed = false
                    while (p < sql.length) {
                        val next = sql[p++]
                        if (next == end) { if (p < sql.length && sql[p] == end && c != '[') { text.append(end); p++ } else { closed = true; break } }
                        else text.append(next)
                    }
                    if (!closed) fail("journal_schema_unsupported")
                    tokens += Token(text.toString(), true)
                }
                c.isLetterOrDigit() || c == '_' -> { val start = p++; while (p < sql.length && (sql[p].isLetterOrDigit() || sql[p] == '_')) p++; tokens += Token(sql.substring(start, p)) }
                c in "(),;+-" -> { tokens += Token(c.toString()); p++ }
                else -> fail("journal_schema_unsupported")
            }
            if (tokens.size > 4096) fail("journal_schema_unsupported")
        }
        return tokens
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    private fun fail(code: String): Nothing = throw HealthJournalException(code)
    private inline fun <T> guarded(fallback: String, block: () -> T): T = try { block() }
    catch (problem: HealthJournalException) { throw problem }
    catch (_: SecurityException) { fail("journal_permission_denied") }
    catch (problem: ErrnoException) { fail(if (problem.errno in setOf(OsConstants.EACCES, OsConstants.EPERM)) "journal_permission_denied" else fallback) }
    catch (_: Exception) { fail(fallback) }
}
