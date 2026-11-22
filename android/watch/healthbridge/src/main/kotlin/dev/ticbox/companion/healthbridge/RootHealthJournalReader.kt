package dev.ticbox.companion.healthbridge

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import android.system.Os
import dev.ticbox.companion.protocol.*
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlinx.serialization.json.*

/** A checkpoint identifies one OEM file/installation, not the phone delivery epoch. */
internal data class HealthJournalPosition(val epoch: String, val generation: String, val sequence: Long) {
    fun json() = buildJsonObject { put("epoch", epoch); put("generation", generation); put("sequence", sequence.toString()) }
    companion object {
        fun parse(value: JsonObject) = HealthJournalPosition(value.text("epoch")!!, value.text("generation")!!, value.number("sequence")!!)
    }
}

internal data class HealthJournalWindow(val epoch: String, val generation: String, val head: Long, val floor: Long) {
    fun position(sequence: Long = head) = HealthJournalPosition(epoch, generation, sequence)
    companion object {
        fun parse(value: JsonObject): HealthJournalWindow? {
            if (value.flag("installed") != true || value.number("version") != 1L || value.text("error") != null) return null
            val epoch = value.text("epoch") ?: return null
            val generation = value.text("generation") ?: return null
            val head = value.number("head") ?: return null; val floor = value.number("floor") ?: return null
            if (head < 0 || floor !in 0..head) return null
            return HealthJournalWindow(epoch, generation, head, floor)
        }
    }
}

internal interface HealthJournalReader {
    /** Null means ordinary independent scans remain necessary. No schema is installed here. */
    fun window(table: LocalHealthTable, cancellation: CancellationSignal): HealthJournalWindow?
    fun page(table: LocalHealthTable, after: HealthJournalPosition, upper: Long, cancellation: CancellationSignal): JsonObject
}

/** Starts a bounded helper only after the watch's existing root consent and explicit journal enablement. */
internal class RootHealthJournalReader(private val context: Context) : HealthJournalReader {
    override fun window(table: LocalHealthTable, cancellation: CancellationSignal): HealthJournalWindow? {
        val source = journalSource(table) ?: return null
        if (!BridgeSettings.rootFitnessEnabled(context) || !BridgeSettings.journalEnabled(context, source)) return null
        return try { HealthJournalWindow.parse(invoke(buildJsonObject { put("source", source); put("action", "status") }, cancellation)) }
        catch (cancelled: android.os.OperationCanceledException) { throw cancelled }
        catch (_: Exception) { null }
    }

    override fun page(table: LocalHealthTable, after: HealthJournalPosition, upper: Long, cancellation: CancellationSignal) = invoke(buildJsonObject {
        put("source", journalSource(table) ?: throw HealthJournalException("journal_invalid_source"))
        put("action", "page"); put("table", table.wireName); put("epoch", after.epoch)
        put("generation", after.generation); put("after", after.sequence.toString()); put("upper", upper.toString())
    }, cancellation)

    fun control(request: JsonObject, cancellation: CancellationSignal): JsonObject {
        val source = request.text("source")
        if (source !in setOf("health", "fitness")) throw HealthJournalException("journal_invalid_source")
        val action = request.text("action")
        if (action !in setOf("status", "enable", "disable")) throw HealthJournalException("journal_invalid_action")
        // Turning off optimization is durable even if root was subsequently revoked. Removal can be retried explicitly.
        if (action == "disable") BridgeSettings.setJournalEnabled(context, source!!, false)
        val result = try {
            if (!BridgeSettings.rootFitnessEnabled(context)) throw HealthJournalException("journal_root_not_enabled")
            invoke(buildJsonObject { put("source", source); put("action", action) }, cancellation).also {
                if (action == "enable" && it.flag("installed") == true && it.text("error") == null)
                    BridgeSettings.setJournalEnabled(context, source!!, true)
            }
        } catch (cancelled: android.os.OperationCanceledException) { throw cancelled }
        catch (problem: Exception) { buildJsonObject {
            put("error", (problem as? HealthJournalException)?.code ?: "journal_operation_unconfirmed")
        } }
        return JsonObject(result + mapOf("op" to JsonPrimitive("journal"), "source" to JsonPrimitive(source),
            "enabledForSync" to JsonPrimitive(BridgeSettings.journalEnabled(context, source!!))))
    }

    private fun invoke(request: JsonObject, cancellation: CancellationSignal): JsonObject {
        cancellation.throwIfCanceled()
        val directory = File(context.noBackupFilesDir, "health-journal-requests").apply { check(mkdirs() || isDirectory) }
        val input = File.createTempFile("request-", ".json", directory)
        val output = File.createTempFile("reply-", ".json", directory)
        try {
            input.writeText(request.toString())
            // Open both bridge-owned files before the helper drops to the OEM UID. Never chown OEM files/sidecars.
            val command = "CLASSPATH=${shellQuote(context.applicationInfo.sourceDir)} exec /system/bin/app_process /system/bin " +
                "dev.ticbox.companion.healthbridge.HealthJournalMain ${shellQuote(input.path)} ${shellQuote(output.path)}"
            val result = RootProcess.run(command, cancellation, 25_000)
            if (result.timedOut || result.exitCode == 124) throw HealthJournalException("journal_operation_unconfirmed")
            if (result.exitCode != 0 || result.output.lineSequence().none { it == "JOURNAL_OK" })
                throw HealthJournalException("journal_root_unavailable")
            cancellation.throwIfCanceled()
            if (output.length() !in 1..LocalHealthProtocol.MAX_FRAME_BYTES.toLong()) throw HealthJournalException("journal_invalid_reply")
            return LocalHealthProtocol.parse(output.readBytes())
        } finally { input.delete(); output.delete() }
    }
}

internal fun journalSource(table: LocalHealthTable): String? = when (table) {
    LocalHealthTable.POINT, LocalHealthTable.SESSION -> "health"
    LocalHealthTable.FITNESS_RECORD, LocalHealthTable.FITNESS_POINT -> "fitness"
    else -> null
}

/** Standalone one-shot process. UID is irreversibly dropped before SQLite can create WAL/SHM files. */
object HealthJournalMain {
    @JvmStatic fun main(args: Array<String>) {
        val deadline = Executors.newSingleThreadScheduledExecutor()
        deadline.schedule({ exitProcess(124) }, 20, TimeUnit.SECONDS)
        try {
            if (android.os.Process.myUid() != 0 || args.size != 2) exitProcess(77)
            val input = File(args[0]); require(input.length() in 1..4096)
            val request = LocalHealthProtocol.parse(input.readBytes())
            FileOutputStream(args[1]).use { output ->
                val result = try {
                    val source = request.text("source") ?: throw HealthJournalException("journal_invalid_source")
                    val action = request.text("action") ?: throw HealthJournalException("journal_invalid_action")
                    if (action !in setOf("status", "enable", "disable", "page")) throw HealthJournalException("journal_invalid_action")
                    val context = systemContext()
                    val verified = HealthJournalProfiles.verify(context, source, forRemoval = action == "disable")
                    val before = Os.stat(verified.file.path)
                    Os.setgid(verified.uid); Os.setuid(verified.uid)
                    check(android.os.Process.myUid() == verified.uid && Os.getgid() == verified.uid)
                    val write = action == "enable" || action == "disable"
                    openJournalDatabase(verified.file, write).use { database ->
                        if (action != "disable") HealthJournalProfiles.validateDatabase(database, source)
                        val generation = if (action == "disable") "" else generation(source, verified.providerVersion, before.st_dev, before.st_ino,
                            HealthJournalProfiles.schemaFingerprint(database, source))
                        val body = when (action) {
                            "enable" -> AtomicHealthJournal.install(database, source)
                            "disable" -> AtomicHealthJournal.remove(database, source)
                            "status" -> AtomicHealthJournal.status(database, source)
                            else -> {
                                val table = LocalHealthTable.entries.firstOrNull { it.wireName == request.text("table") && journalSource(it) == source }
                                    ?: throw HealthJournalException("journal_invalid_source")
                                if (generation != request.text("generation")) JsonObject(AtomicHealthJournal.status(database, source) + ("reset" to JsonPrimitive(true)))
                                else capture(database, source, table, request)
                            }
                        }
                        val after = Os.stat(verified.file.path)
                        if (before.st_dev != after.st_dev || before.st_ino != after.st_ino) throw HealthJournalException("journal_source_replaced")
                        JsonObject(body + mapOf("generation" to JsonPrimitive(generation), "providerVersion" to JsonPrimitive(verified.providerVersion)))
                    }
                } catch (problem: Exception) { buildJsonObject { put("error", (problem as? HealthJournalException)?.code ?: "journal_operation_unconfirmed") } }
                val bytes = result.toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= LocalHealthProtocol.MAX_FRAME_BYTES)
                output.write(bytes); output.flush()
            }
            println("JOURNAL_OK")
        } finally { deadline.shutdownNow() }
    }

    internal fun capture(database: SQLiteDatabase, source: String, table: LocalHealthTable, request: JsonObject): JsonObject {
        val read = AtomicHealthJournal.read(database, source, table, request.text("epoch") ?: "",
            request.number("after") ?: -1, request.number("upper") ?: -1)
        if (read.flag("reset") == true || read.flag("wide") == true) return read
        val events = (read["events"] as? JsonArray).orEmpty().map { it as JsonObject }
        val keys = events.flatMap { listOfNotNull(it.text("key"), it.text("previousKey")) }.distinct()
        if (keys.isEmpty()) return JsonObject(read + mapOf("rows" to JsonArray(emptyList()), "missingKeys" to JsonArray(emptyList())))
        val sqlTable = when (table) {
            LocalHealthTable.POINT -> "data_point"; LocalHealthTable.SESSION -> "data_session"
            LocalHealthTable.FITNESS_RECORD -> "RECORD"; LocalHealthTable.FITNESS_POINT -> "POINT"
            else -> throw HealthJournalException("journal_invalid_source")
        }
        val key = if (table == LocalHealthTable.SESSION) "id" else "_id"
        val missing = keys.toMutableSet(); val rows = mutableListOf<JsonObject>(); var bytes = 0
        database.rawQuery("SELECT * FROM $sqlTable WHERE $key IN (${keys.joinToString(",") { "?" }})", keys.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                val value = cursor.getString(cursor.getColumnIndexOrThrow(key))
                val row = cursor.healthDatabaseRow(value, table)
                bytes += row.toString().toByteArray(Charsets.UTF_8).size
                if (bytes > LocalHealthProtocol.MAX_FRAME_BYTES - 64 * 1024) throw HealthJournalException("journal_row_exceeds_budget")
                rows += row; missing -= value
            }
        }
        // A key-only log is a prompt to reread committed state, not a claim that every candidate was applied.
        return JsonObject(read + mapOf("rows" to JsonArray(rows), "missingKeys" to JsonArray(missing.map(::JsonPrimitive))))
    }

    @android.annotation.SuppressLint("PrivateApi") // Isolated su app_process has no Application; never called in an OEM process.
    private fun systemContext(): Context {
        if (android.os.Looper.getMainLooper() == null) android.os.Looper.prepareMainLooper()
        val type = Class.forName("android.app.ActivityThread")
        return type.getMethod("getSystemContext").invoke(type.getMethod("systemMain").invoke(null)) as Context
    }

    private fun generation(source: String, version: String, device: Long, inode: Long, schema: String): String =
        MessageDigest.getInstance("SHA-256").digest("$source\n$version\n$device\n$inode\n$schema".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** Android's bare OPEN_READWRITE defaults can switch an OEM WAL database to its global default. */
internal fun openJournalDatabase(file: File, write: Boolean): SQLiteDatabase {
    val readFlags = SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
    if (!write) return SQLiteDatabase.openDatabase(file.path, null, readFlags, DatabaseErrorHandler { })
    val mode = SQLiteDatabase.openDatabase(file.path, null, readFlags, DatabaseErrorHandler { }).use { database ->
        database.rawQuery("PRAGMA journal_mode", null).use { cursor ->
            check(cursor.moveToFirst()); cursor.getString(0).lowercase(java.util.Locale.ROOT)
        }
    }
    if (mode !in setOf("wal", "delete", "truncate", "persist")) throw HealthJournalException("journal_storage_mode_unsupported")
    // These are this helper connection's open parameters, not changes to any OEM connection's
    // synchronous/recursive-trigger/checkpoint settings. No CREATE_IF_NECESSARY or corruption repair.
    val flags = SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS or
        (if (mode == "wal") SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING else 0)
    val parameters = SQLiteDatabase.OpenParams.Builder().setOpenFlags(flags).setErrorHandler(DatabaseErrorHandler { })
        .setJournalMode(mode).setSynchronousMode("FULL").build()
    return SQLiteDatabase.openDatabase(file, parameters).also { database ->
        val observed = database.rawQuery("PRAGMA journal_mode", null).use { cursor -> cursor.moveToFirst(); cursor.getString(0).lowercase(java.util.Locale.ROOT) }
        if (observed != mode) { database.close(); throw HealthJournalException("journal_storage_mode_changed") }
    }
}
