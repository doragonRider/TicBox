package dev.ticbox.companion.healthbridge

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.database.sqlite.SQLiteDatabase
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Original DDL-shaped synthetic databases; an explicit local copy can also be checked read-only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35], application = Application::class, shadows = [HealthJournalProfilesTest.RealFileStatShadow::class])
class HealthJournalProfilesTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun allThreeApksRequireExactPackageVersionCodeVersionNameAndFullDigest() {
        val pins = listOf(
            listOf("health", "com.mobvoi.wear.health.at", "50503292", "5.5.0-3292.2931", "39969a876dcd716bd9e868a446626f762f8c23c7c90d567a061df9e405666e25"),
            listOf("health", "com.mobvoi.wear.health.at", "50603234", "5.6.0-3234.2686", "276264832680b5da9fc7bae260ff521e7df6d49c7e04fa40c00f64e3ef96c4db"),
            listOf("fitness", "com.mobvoi.wear.fitness.at", "50502536", "5.5.0-2536.2932", "4682ad6b0bcbb33394e445d4d4066512446062508924903822ef25071d03faae"),
        )
        for ((source, pkg, code, name, digest) in pins) {
            assertTrue(HealthJournalProfiles.profileMatches(source, pkg, code.toLong(), name, digest))
            assertFalse(HealthJournalProfiles.profileMatches(source, "$pkg.other", code.toLong(), name, digest))
            assertFalse(HealthJournalProfiles.profileMatches(source, pkg, code.toLong() + 1, name, digest))
            assertFalse(HealthJournalProfiles.profileMatches(source, pkg, code.toLong(), "$name.1", digest))
            assertFalse(HealthJournalProfiles.profileMatches(source, pkg, code.toLong(), null, digest))
            assertFalse(HealthJournalProfiles.profileMatches(source, pkg, code.toLong(), name, "0".repeat(64)))
        }
        error("journal_source_unsupported") { HealthJournalProfiles.profileMatches("other", "pkg", 1, "v", "0".repeat(64)) }
    }

    @Test fun bothHealthRoomIdentitiesAndMigrationColumnOrderAreSupported() {
        for (hash in HEALTH_HASHES) for (reversed in listOf(false, true)) database("health", hash, reversed = reversed).use {
            HealthJournalProfiles.validateDatabase(it, "health")
            assertEquals(64, HealthJournalProfiles.schemaFingerprint(it, "health").length)
        }
    }

    @Test fun newFitnessAndTheFullyTracedRebuildMigrationAreSeparatelySupported() {
        for (hash in FITNESS_HASHES) for (legacy in listOf(false, true)) database("fitness", hash, legacy).use { db ->
            HealthJournalProfiles.validateDatabase(db, "fitness")
            val before = HealthJournalProfiles.schemaFingerprint(db, "fitness")
            assertEquals(before, HealthJournalProfiles.schemaFingerprint(db, "fitness"))
            db.rawQuery("PRAGMA index_list(RECORD)", null).use { assertEquals(if (legacy) 2 else 1, it.count) }
        }
    }

    @Test fun roomHashAndVersionNeverSubstituteForActualSchemaValidation() {
        database("health").use { db ->
            db.version = 6
            error("journal_schema_unsupported") { HealthJournalProfiles.validateDatabase(db, "health") }
            db.version = 5; db.execSQL("UPDATE room_master_table SET identity_hash='unrecognized'")
            error("journal_schema_unsupported") { HealthJournalProfiles.validateDatabase(db, "health") }
            db.execSQL("UPDATE room_master_table SET identity_hash=?", arrayOf(HEALTH_HASHES.first()))
            db.execSQL("ALTER TABLE data_point ADD COLUMN unknown TEXT")
            error("journal_schema_unsupported") { HealthJournalProfiles.validateDatabase(db, "health") }
        }
        database("health", master = false).use { error("journal_schema_unsupported") { HealthJournalProfiles.validateDatabase(it, "health") } }
        database("health").use { db ->
            db.execSQL("DELETE FROM room_master_table")
            error("journal_schema_unsupported") { HealthJournalProfiles.validateDatabase(db, "health") }
        }
        database("health").use { error("journal_source_unsupported") { HealthJournalProfiles.validateDatabase(it, "arbitrary") } }
    }

    @Test fun unknownBusinessConstraintsDefaultsIndexesOrTriggersFailClosed() {
        val mutations = listOf<(SQLiteDatabase) -> Unit>(
            { it.execSQL("CREATE INDEX extra_business ON data_point(time_to)") },
            { it.execSQL("DROP INDEX point_sync") },
            { it.execSQL("DROP INDEX point_sync"); it.execSQL("CREATE UNIQUE INDEX point_sync ON data_point(wwid,type,synced)") },
            { it.execSQL("DROP INDEX point_sync"); it.execSQL("CREATE INDEX point_sync ON data_point(wwid,type,synced) WHERE synced=0") },
            { it.execSQL("DROP INDEX point_sync"); it.execSQL("CREATE INDEX point_sync ON data_point(wwid,type,synced DESC)") },
            { it.execSQL("DROP INDEX point_sync"); it.execSQL("CREATE INDEX point_sync ON data_point(wwid COLLATE NOCASE,type,synced)") },
            { it.execSQL("DROP INDEX point_sync"); it.execSQL("CREATE INDEX point_sync ON data_point(wwid,type,synced+0)") },
            { it.execSQL("CREATE TRIGGER unknown_writer AFTER INSERT ON data_point BEGIN SELECT 1; END") },
        )
        for (mutation in mutations) database("health").use { db ->
            mutation(db)
            error("journal_schema_unsupported") { HealthJournalProfiles.validateDatabase(db, "health") }
        }
        val definitions = listOf(
            HEALTH_POINT.replace("type INTEGER NOT NULL DEFAULT -1", "type INTEGER NOT NULL DEFAULT 0"),
            HEALTH_POINT.replace("time_to INTEGER NOT NULL", "time_to INTEGER NOT NULL CHECK(time_to >= 0)"),
            HEALTH_POINT.replace("wwid TEXT", "wwid TEXT COLLATE NOCASE"),
            HEALTH_POINT.replace("wwid TEXT", "wwid TEXT UNIQUE"),
            HEALTH_POINT.replace("wwid TEXT", "wwid TEXT REFERENCES data_source(name)"),
            HEALTH_POINT.replace("synced INTEGER NOT NULL DEFAULT 0", "synced INTEGER NOT NULL ON CONFLICT REPLACE DEFAULT 0"),
            HEALTH_POINT.replace("PRIMARY KEY AUTOINCREMENT", "PRIMARY KEY"),
            HEALTH_POINT.replace("wwid TEXT", "wwid TEXT DEFAULT NULL"),
        )
        for (definition in definitions) database("health", pointSql = definition).use {
            error("journal_schema_unsupported") { HealthJournalProfiles.validateDatabase(it, "health") }
        }
    }

    @Test fun partiallyKnownFitnessMigrationVariantsAreNotLooselyAdmitted() {
        val variants = listOf(
            FITNESS_POINT.replace("_id INTEGER", "_id INTEGER PRIMARY KEY AUTOINCREMENT").replace(", PRIMARY KEY (_id)", "") to FITNESS_RECORD,
            FITNESS_POINT to FITNESS_RECORD.replace("HASH TEXT", "HASH TEXT UNIQUE"),
            FITNESS_POINT.replace("TIMESTAMP INTEGER NOT NULL", "TIMESTAMP INTEGER NOT NULL DEFAULT -1") to FITNESS_RECORD,
            FITNESS_POINT to FITNESS_RECORD.replace("DEVICE_ID TEXT", "DEVICE_ID TEXT UNIQUE"),
            FITNESS_POINT to FITNESS_RECORD.replace("SWIM_DISTANCE INTEGER", "SWIM_DISTANCE REAL"),
        )
        for ((point, record) in variants) database("fitness", pointSql = point, recordSql = record).use {
            error("journal_schema_unsupported") { HealthJournalProfiles.validateDatabase(it, "fitness") }
        }
    }

    @Test fun fingerprintExcludesOnlyOurNamespaceAndDoesNotChangeOemState() {
        database("health").use { db ->
            db.execSQL("INSERT INTO data_point(time_to,time_from) VALUES(1,0)")
            val schema = scalar(db, "PRAGMA schema_version")
            val hash = scalar(db, "SELECT identity_hash FROM room_master_table WHERE id=42")
            val before = HealthJournalProfiles.schemaFingerprint(db, "health")
            assertEquals(schema, scalar(db, "PRAGMA schema_version"))
            assertEquals(5, db.version); assertEquals(hash, scalar(db, "SELECT identity_hash FROM room_master_table WHERE id=42"))
            assertEquals("1", scalar(db, "SELECT count(*) FROM data_point"))
            db.execSQL("CREATE TABLE ticwatch_journal_v1_meta(value TEXT)")
            db.execSQL("CREATE TABLE ticwatch_journal_v1_events(value TEXT)")
            db.execSQL("CREATE TRIGGER ticwatch_journal_v1_point AFTER INSERT ON data_point BEGIN INSERT INTO ticwatch_journal_v1_events VALUES('dirty'); END")
            assertEquals(before, HealthJournalProfiles.schemaFingerprint(db, "health"))
            db.execSQL("CREATE TABLE future_oem_metadata(value TEXT)")
            assertNotEquals(before, HealthJournalProfiles.schemaFingerprint(db, "health"))
        }
    }

    @Test fun futureApkCanOnlyUseExplicitRemovalWithTheRealOwnedFixedDatabase() {
        for (source in listOf("health", "fitness")) {
            val installed = installed(source)
            error("journal_provider_unsupported") { HealthJournalProfiles.verify(context, source) }
            val original = installed.database.readBytes()
            val result = HealthJournalProfiles.verify(context, source, forRemoval = true)
            assertEquals(source, result.source); assertEquals(installed.database.canonicalFile, result.file)
            assertEquals(installed.uid, result.uid); assertEquals("future-version", result.providerVersion)
            assertTrue(original.contentEquals(installed.database.readBytes()))
        }
    }

    @Test fun knownManifestVersionWithoutItsExactOriginalBytesIsRejected() {
        installed("health", 50603234, "5.6.0-3234.2686")
        error("journal_provider_integrity_mismatch") { HealthJournalProfiles.verify(context, "health") }
        assertEquals("health", HealthJournalProfiles.verify(context, "health", forRemoval = true).source)
    }

    @Test fun missingProviderDatabaseAndUnexpectedUidAreDistinctFailures() {
        error("journal_source_not_installed") { HealthJournalProfiles.verify(context, "health", true) }
        val installed = installed("health")
        installed.database.delete()
        error("journal_database_missing") { HealthJournalProfiles.verify(context, "health", true) }
        installed.database.writeText("fixture")
        val pkg = context.packageManager.getPackageInfo("com.mobvoi.wear.health.at", 0)
        pkg.applicationInfo!!.uid = installed.uid + 1
        shadowOf(context.packageManager).installPackage(pkg)
        error("journal_source_uid_mismatch") { HealthJournalProfiles.verify(context, "health", true) }
        error("journal_source_unsupported") { HealthJournalProfiles.verify(context, "../health", true) }
    }

    @Test fun databaseDirectoryFileAndSidecarSymlinksAreNeverFollowed() {
        val installed = installed("health")
        val other = File(installed.directory, "other.db").apply { writeText("outside") }
        installed.database.delete(); Files.createSymbolicLink(installed.database.toPath(), other.toPath())
        error("journal_path_invalid") { HealthJournalProfiles.verify(context, "health", true) }
        installed.database.delete(); installed.database.writeText("fixture")
        val wal = File(installed.database.path + "-wal")
        Files.createSymbolicLink(wal.toPath(), other.toPath())
        error("journal_path_invalid") { HealthJournalProfiles.verify(context, "health", true) }
        wal.delete()
        val actualDirectory = File(installed.directory, "real-databases")
        assertTrue(installed.database.parentFile!!.renameTo(actualDirectory))
        Files.createSymbolicLink(installed.database.parentFile!!.toPath(), actualDirectory.toPath())
        error("journal_path_invalid") { HealthJournalProfiles.verify(context, "health", true) }
        assertEquals("outside", other.readText())
    }

    @Test fun explicitlySuppliedExistingDatabaseCopyPassesOnlyReadOnlySchemaAdmission() {
        val selected = System.getenv("TICBOX_JOURNAL_PROFILE_FIXTURE")
        assumeTrue("Optional offline database copy was not selected", !selected.isNullOrBlank())
        val file = File(selected!!)
        val before = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            android.database.DatabaseErrorHandler { }).use { db ->
            HealthJournalProfiles.validateDatabase(db, "fitness")
            assertEquals(64, HealthJournalProfiles.schemaFingerprint(db, "fitness").length)
        }
        assertTrue(before.contentEquals(MessageDigest.getInstance("SHA-256").digest(file.readBytes())))
        // This deliberately does not claim APK admission: the copy is not an installed package.
    }

    private data class Installed(val directory: File, val database: File, val uid: Int)
    private fun installed(source: String, versionCode: Long = 90000000, versionName: String? = "future-version"): Installed {
        val pkg = if (source == "health") "com.mobvoi.wear.health.at" else "com.mobvoi.wear.fitness.at"
        val root = File(context.cacheDir, "profile-${source}-${System.nanoTime()}").apply { mkdirs() }.canonicalFile
        val directory = File(root, "databases").apply { mkdirs() }
        val database = File(directory, if (source == "health") "health_common.db" else "fitness.db").apply { writeText("fixture") }
        val apk = File(root, "base.apk").apply { writeText("synthetic-original-bytes-are-not-a-shipped-apk") }
        val uid = Os.stat(root.path).st_uid
        check(uid > 0) { "Synthetic file UID must match a non-root installed package: $uid" }
        val application = ApplicationInfo().apply { packageName = pkg; dataDir = root.path; sourceDir = apk.path; this.uid = uid }
        val info = PackageInfo().apply { packageName = pkg; setLongVersionCode(versionCode); this.versionName = versionName; applicationInfo = application }
        shadowOf(context.packageManager).installPackage(info)
        val actual = context.packageManager.getApplicationInfo(pkg, 0)
        assertEquals(pkg, actual.packageName); assertEquals(uid, actual.uid)
        assertEquals(root.path, actual.dataDir); assertEquals(apk.path, actual.sourceDir)
        val installed = context.packageManager.getPackageInfo(pkg, 0).applicationInfo
        assertEquals(pkg, installed?.packageName); assertEquals(uid, installed?.uid)
        assertEquals(root.path, installed?.dataDir); assertEquals(apk.path, installed?.sourceDir)
        return Installed(root, database, uid)
    }

    private fun database(source: String, hash: String = if (source == "health") HEALTH_HASHES.first() else FITNESS_HASHES.first(),
        legacy: Boolean = false, reversed: Boolean = false, master: Boolean = true, pointSql: String? = null, recordSql: String? = null): SQLiteDatabase {
        val db = SQLiteDatabase.create(null)
        if (master) {
            db.execSQL("CREATE TABLE room_master_table(id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL("INSERT INTO room_master_table VALUES(42,?)", arrayOf(hash))
        }
        db.version = if (source == "health") 5 else 1021
        if (source == "health") {
            val point = pointSql ?: if (reversed) HEALTH_POINT_REORDERED else HEALTH_POINT
            db.execSQL(point); db.execSQL(HEALTH_SESSION); db.execSQL(HEALTH_SOURCE)
            db.execSQL("CREATE INDEX point_query ON data_point(wwid,type,time_to,time_from)")
            db.execSQL("CREATE INDEX point_sync ON data_point(wwid,type,synced)")
            db.execSQL("CREATE INDEX session_sync ON data_session(wwid,type,synced)")
            db.execSQL("CREATE INDEX session_query ON data_session(wwid,type,time_to,time_from)")
        } else {
            db.execSQL(pointSql ?: if (legacy) FITNESS_POINT.replace("_id INTEGER", "_id INTEGER PRIMARY KEY AUTOINCREMENT").replace(", PRIMARY KEY (_id)", "") else FITNESS_POINT)
            db.execSQL(recordSql ?: if (legacy) FITNESS_RECORD.replace("HASH TEXT", "HASH TEXT UNIQUE") else FITNESS_RECORD)
            db.execSQL("CREATE INDEX IDX_RECORD_HASH ON RECORD(HASH)")
        }
        return db
    }

    private fun scalar(db: SQLiteDatabase, sql: String) = db.rawQuery(sql, null).use { assertTrue(it.moveToFirst()); it.getString(0) }
    private fun error(code: String, operation: () -> Unit) { assertEquals(code, assertFailsWith<HealthJournalException> { operation() }.code) }

    /** Robolectric's default lstat reports UID 0; use actual host metadata for ownership tests. */
    @Implements(Os::class)
    class RealFileStatShadow {
        companion object {
            @JvmStatic @Implementation fun lstat(path: String): StructStat = metadata(path, LinkOption.NOFOLLOW_LINKS)
            @JvmStatic @Implementation fun stat(path: String): StructStat = metadata(path)
            private fun metadata(path: String, vararg options: LinkOption): StructStat {
                val values = try { Files.readAttributes(Path.of(path), "unix:dev,ino,mode,nlink,uid,gid,rdev,size,lastAccessTime,lastModifiedTime,ctime", *options) }
                catch (problem: NoSuchFileException) { throw ErrnoException("stat", OsConstants.ENOENT, problem) }
                fun number(name: String) = (values.getValue(name) as Number).toLong()
                fun time(name: String) = (values.getValue(name) as FileTime).toMillis() / 1000
                return StructStat(number("dev"), number("ino"), number("mode").toInt(), number("nlink"), number("uid").toInt(),
                    number("gid").toInt(), number("rdev"), number("size"), time("lastAccessTime"), time("lastModifiedTime"), time("ctime"),
                    4096, (number("size") + 511) / 512)
            }
        }
    }

    internal companion object {
        val HEALTH_HASHES = listOf("993724866f858147fc2b385340b6c6a2", "d54ea02cfe7bc87bfe1f4aad8d32d23a")
        val FITNESS_HASHES = listOf("516350121b90186f5b7326fe366cb4f0", "f7fe416659310e31e28e1c8b6837c16a")
        const val HEALTH_POINT = "CREATE TABLE data_point (_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,\"values\" TEXT NOT NULL DEFAULT '',device_id TEXT NOT NULL DEFAULT '',wwid TEXT,type INTEGER NOT NULL DEFAULT -1,time_to INTEGER NOT NULL,time_from INTEGER NOT NULL,synced INTEGER NOT NULL DEFAULT 0,deleted INTEGER NOT NULL DEFAULT 0)"
        const val HEALTH_POINT_REORDERED = "CREATE TABLE data_point (type INTEGER NOT NULL DEFAULT (-1),wwid TEXT,device_id TEXT NOT NULL DEFAULT '',\"values\" TEXT NOT NULL DEFAULT '',time_from INTEGER NOT NULL,time_to INTEGER NOT NULL,deleted INTEGER NOT NULL DEFAULT (0),synced INTEGER NOT NULL DEFAULT 0,_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)"
        const val HEALTH_SESSION = "CREATE TABLE data_session(id TEXT NOT NULL,device_id TEXT NOT NULL DEFAULT '',wwid TEXT,type INTEGER NOT NULL DEFAULT -1,time_to INTEGER NOT NULL,time_from INTEGER NOT NULL,synced INTEGER NOT NULL DEFAULT 0,deleted INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(id))"
        const val HEALTH_SOURCE = "CREATE TABLE data_source(name TEXT NOT NULL,wwid TEXT NOT NULL DEFAULT '',device_id TEXT NOT NULL DEFAULT '',device_model TEXT NOT NULL DEFAULT '',device_type TEXT,device_version TEXT,PRIMARY KEY(name))"
        const val FITNESS_POINT = "CREATE TABLE POINT(_id INTEGER,TIME INTEGER NOT NULL,HEART INTEGER,STEP INTEGER,LAT REAL NOT NULL,LON REAL NOT NULL,ACC REAL NOT NULL,DISTANCE INTEGER,SPEED REAL,CALORIE REAL,RECORD_ID INTEGER,RESUME INTEGER,SWIM_TRIPS REAL,SWIM_DISTANCE INTEGER,SWIM_STROKE INTEGER,SWIM_TYPE INTEGER,TIMESTAMP INTEGER NOT NULL,ELEVATION REAL,STRIDE_FREQ INTEGER, PRIMARY KEY (_id))"
        const val FITNESS_RECORD = "CREATE TABLE RECORD(_id INTEGER PRIMARY KEY AUTOINCREMENT,HASH TEXT,TYPE INTEGER NOT NULL,TARGET_TYPE INTEGER,TARGET_VALUE REAL,ACCOUNT_ID TEXT,SYNCED INTEGER NOT NULL,DELETED INTEGER NOT NULL,START_TIME INTEGER,END_TIME INTEGER,TIMESTAMP INTEGER,DURATION INTEGER NOT NULL,DISTANCE INTEGER,CALORIE REAL,STEP INTEGER,HEART INTEGER,GROUPS TEXT,SWIM_TRIPS REAL,SWIM_DISTANCE INTEGER,SWIM_POOL_LENGTH INTEGER,SWIM_STROKE INTEGER,DEVICE_ID TEXT,SESSION_MODE INTEGER,MAX_ELEVATION REAL,MIN_ELEVATION REAL,CUMULATIVE_UP REAL,CUMULATIVE_DOWN REAL,SCORE REAL,EXTRA TEXT)"
    }
}
