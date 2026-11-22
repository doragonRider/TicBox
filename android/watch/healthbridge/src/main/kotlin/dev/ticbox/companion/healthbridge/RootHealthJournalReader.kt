package dev.ticbox.companion.healthbridge

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import android.system.Os
import android.util.Log
import dev.ticbox.companion.protocol.*
import java.io.File
import java.io.FileOutputStream
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
    fun json() = buildJsonObject {
        put("version", 1); put("installed", true); put("epoch", epoch); put("generation", generation)
        put("head", head.toString()); put("floor", floor.toString())
    }
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
    /** Whether the user turned this source's log on; nothing needs reading when not. */
    fun enabled(source: String): Boolean = true
    /** Null means ordinary independent scans remain necessary. No schema is installed here. */
    fun window(table: LocalHealthTable, cancellation: CancellationSignal): HealthJournalWindow?
    /** The same read, which may also bring each table's page after these cursors, so no separate read is needed for it. */
    fun window(table: LocalHealthTable, positions: Map<LocalHealthTable, HealthJournalPosition>, cancellation: CancellationSignal) =
        window(table, cancellation)
    fun page(table: LocalHealthTable, after: HealthJournalPosition, upper: Long, cancellation: CancellationSignal): JsonObject
}

/**
 * Reads the health log through the module in the official app when it answers, otherwise through a bounded root helper,
 * and only after the watch's existing root consent and explicit journal enablement.
 */
internal class RootHealthJournalReader(private val context: Context,
    private val module: HealthModuleJournal? = HealthModuleJournal(context),
    /** Answers the workout log's reads for the rest of a read window once it has started. */
    private val fitness: FitnessHelperSession? = null) : HealthJournalReader {
    /** Pages the last read of each source brought along, by table, with the cursor and head they were read for. */
    private val pages = mutableMapOf<LocalHealthTable, Pair<Pair<HealthJournalPosition, Long>, JsonObject>>()

    override fun enabled(source: String) = BridgeSettings.rootFitnessEnabled(context) && BridgeSettings.journalEnabled(context, source)

    override fun window(table: LocalHealthTable, cancellation: CancellationSignal) = window(table, emptyMap(), cancellation)

    override fun window(table: LocalHealthTable, positions: Map<LocalHealthTable, HealthJournalPosition>, cancellation: CancellationSignal): HealthJournalWindow? {
        val source = journalSource(table) ?: return null
        if (!enabled(source)) return null
        synchronized(pages) { pages.keys.removeAll { journalSource(it) == source } }
        val wanted = buildJsonObject { positions.forEach { (each, position) -> if (journalSource(each) == source) put(each.wireName, position.json()) } }
        val reply = try {
            (if (source == HealthModuleJournal.SOURCE) viaModule { module?.read(wanted, cancellation) } else null)
                ?: invoke(buildJsonObject { put("source", source); put("action", "read"); put("positions", wanted) }, cancellation)
        } catch (cancelled: android.os.OperationCanceledException) { throw cancelled }
        catch (_: Exception) { return null }
        val window = HealthJournalWindow.parse(reply) ?: return null
        (reply["pages"] as? JsonObject)?.forEach { (name, page) ->
            val each = LocalHealthTable.entries.firstOrNull { it.wireName == name } ?: return@forEach
            val position = positions[each] ?: return@forEach
            synchronized(pages) { pages[each] = (position to window.head) to page.jsonObject }
        }
        return window
    }

    override fun page(table: LocalHealthTable, after: HealthJournalPosition, upper: Long, cancellation: CancellationSignal): JsonObject {
        synchronized(pages) { pages.remove(table) }?.takeIf { it.first == (after to upper) }?.let { return it.second }
        val source = journalSource(table) ?: throw HealthJournalException("journal_invalid_source")
        val request = buildJsonObject {
            put("source", source); put("action", "page"); put("table", table.wireName); put("epoch", after.epoch)
            put("generation", after.generation); put("after", after.sequence.toString()); put("upper", upper.toString())
        }
        return (if (source == HealthModuleJournal.SOURCE) viaModule { module?.page(table, request, cancellation) } else null)
            ?: invoke(request, cancellation)
    }

    /** Any failure on the module's side leaves the read to the helper, which reports problems the usual way. */
    private inline fun viaModule(read: () -> JsonObject?): JsonObject? = try { read() }
        catch (cancelled: android.os.OperationCanceledException) { throw cancelled }
        catch (problem: Exception) {
            Log.w("TicBoxHealthBridge", "Journal read through the module failed; reason=" +
                ((problem as? HealthJournalException)?.code ?: problem.javaClass.simpleName))
            null
        }

    fun control(request: JsonObject, cancellation: CancellationSignal): JsonObject {
        val source = request.text("source")
        if (source !in setOf("health", "fitness")) throw HealthJournalException("journal_invalid_source")
        val action = request.text("action")
        if (action !in setOf("status", "enable", "disable")) throw HealthJournalException("journal_invalid_action")
        // Turning off optimization is durable even if root was subsequently revoked. Removal can be retried explicitly.
        if (action == "disable") BridgeSettings.setJournalEnabled(context, source!!, false)
        val result = try {
            if (!BridgeSettings.rootFitnessEnabled(context)) throw HealthJournalException("journal_root_not_enabled")
            // The module answers a look at the health log without starting a helper; changing the log always takes one.
            (if (action == "status" && source == HealthModuleJournal.SOURCE) viaModule { module?.status(cancellation) } else null)
                ?: invoke(buildJsonObject { put("source", source); put("action", action) }, cancellation).also {
                    if (action == "enable" && it.flag("installed") == true && it.text("error") == null)
                        BridgeSettings.setJournalEnabled(context, source!!, true)
                }
        } catch (cancelled: android.os.OperationCanceledException) { throw cancelled }
        catch (problem: Exception) { buildJsonObject {
            if (problem !is HealthJournalException) Log.w("TicBoxHealthBridge", "Journal $action failed; exception=${problem.javaClass.simpleName}")
            put("error", (problem as? HealthJournalException)?.code ?: "journal_operation_unconfirmed")
        } }
        return JsonObject(result + mapOf("op" to JsonPrimitive("journal"), "source" to JsonPrimitive(source),
            "enabledForSync" to JsonPrimitive(BridgeSettings.journalEnabled(context, source!!))))
    }

    private fun invoke(request: JsonObject, cancellation: CancellationSignal): JsonObject {
        cancellation.throwIfCanceled()
        if (request.text("source") == "fitness" && request.text("action") in setOf("read", "page"))
            fitness?.journal(request, cancellation)?.let { return it }
        val directory = File(context.noBackupFilesDir, "health-journal-requests").apply { check(mkdirs() || isDirectory) }
        val input = File.createTempFile("request-", ".json", directory)
        val output = File.createTempFile("reply-", ".json", directory)
        try {
            input.writeText(request.toString())
            // Open both bridge-owned files before the helper drops to the OEM UID. Never chown OEM files/sidecars.
            val command = "CLASSPATH=${shellQuote(context.applicationInfo.sourceDir)} exec /system/bin/app_process /system/bin " +
                "dev.ticbox.companion.healthbridge.HealthJournalMain ${shellQuote(input.path)} ${shellQuote(output.path)}"
            val action = request.text("action")
            val started = android.os.SystemClock.elapsedRealtime()
            val result = RootProcess.run(command, cancellation, 25_000)
            val millis = android.os.SystemClock.elapsedRealtime() - started
            if (result.timedOut || result.exitCode == 124) {
                Log.w("TicBoxHealthBridge", "Journal helper timed out; action=$action exit=${result.exitCode} millis=$millis")
                throw HealthJournalException("journal_operation_unconfirmed")
            }
            if (result.exitCode != 0 || result.output.lineSequence().none { it == "JOURNAL_OK" }) {
                Log.w("TicBoxHealthBridge", "Journal helper unavailable; action=$action exit=${result.exitCode} millis=$millis")
                throw HealthJournalException("journal_root_unavailable")
            }
            cancellation.throwIfCanceled()
            if (output.length() !in 1..LocalHealthProtocol.MAX_FRAME_BYTES.toLong()) throw HealthJournalException("journal_invalid_reply")
            Log.i("TicBoxHealthBridge", "Journal helper finished; action=$action millis=$millis")
            return LocalHealthProtocol.parse(output.readBytes()).also { reply ->
                // The step and exception type say where an unexpected failure happened; neither carries data.
                reply.text("error")?.let { error -> Log.w("TicBoxHealthBridge", "Journal helper failed; action=$action error=$error " +
                    "step=${reply.text("step") ?: "none"} exception=${reply.text("exception") ?: "none"} at=${reply.text("at") ?: "none"} millis=$millis") }
            }
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
                var step = "request"
                val result = try {
                    val source = request.text("source") ?: throw HealthJournalException("journal_invalid_source")
                    val action = request.text("action") ?: throw HealthJournalException("journal_invalid_action")
                    if (action !in setOf("status", "enable", "disable", "page", "read")) throw HealthJournalException("journal_invalid_action")
                    // The first database a process opens makes SQLite read a global setting through the process's
                    // application, if it has one. The system context below would make that call as package "android",
                    // which the system refuses from this UID, root included, and every open would then throw
                    // SecurityException. Opened before that context exists, SQLite skips the setting and keeps its defaults.
                    step = "sqlite"; SQLiteDatabase.create(null).close()
                    step = "context"; val context = systemContext()
                    step = "verify"; val verified = HealthJournalProfiles.verify(context, source, forRemoval = action == "disable")
                    step = "stat"; val before = Os.stat(verified.file.path)
                    step = "uid"; Os.setgid(verified.uid); Os.setuid(verified.uid)
                    check(android.os.Process.myUid() == verified.uid && Os.getgid() == verified.uid)
                    val write = action == "enable" || action == "disable"
                    step = "open"
                    openJournalDatabase(verified.file, write).use { database ->
                        journalAnswer(database, verified, before, request, action) { step = it }
                    }
                } catch (problem: Exception) { buildJsonObject {
                    put("error", (problem as? HealthJournalException)?.code ?: "journal_operation_unconfirmed")
                    // Only for unexpected failures: where it happened and the exception type. Messages may carry SQL or values.
                    if (problem !is HealthJournalException) {
                        put("step", step); put("exception", problem.javaClass.simpleName); put("at", origin(problem))
                    }
                } }
                val bytes = result.toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= LocalHealthProtocol.MAX_FRAME_BYTES)
                output.write(bytes); output.flush()
            }
            println("JOURNAL_OK")
        } finally { deadline.shutdownNow() }
    }

    /**
     * One action on the journal's database, opened as the verified UID. The fingerprint validates the schema first, so
     * this is the one validation, and a file replaced meanwhile fails the answer.
     */
    internal fun journalAnswer(database: SQLiteDatabase, verified: VerifiedHealthJournalSource, before: android.system.StructStat,
        request: JsonObject, action: String, step: (String) -> Unit): JsonObject {
        val source = verified.source
        step("validate")
        val generation = if (action == "disable") "" else HealthJournalRead.generation(source, verified.providerVersion,
            before.st_dev, before.st_ino, HealthJournalProfiles.schemaFingerprint(database, source))
        step(action)
        val sql = FileJournalSql(database)
        val body = when (action) {
            "enable" -> AtomicHealthJournal.install(database, source)
            "disable" -> AtomicHealthJournal.remove(database, source)
            "status" -> AtomicHealthJournal.status(sql, source)
            "read" -> HealthJournalRead.read(sql, source, request["positions"] as? JsonObject, generation, verified.providerVersion)
            else -> {
                val table = LocalHealthTable.entries.firstOrNull { it.wireName == request.text("table") && journalSource(it) == source }
                    ?: throw HealthJournalException("journal_invalid_source")
                HealthJournalRead.page(sql, source, table, request, generation)
            }
        }
        val after = Os.stat(verified.file.path)
        if (before.st_dev != after.st_dev || before.st_ino != after.st_ino) throw HealthJournalException("journal_source_replaced")
        return JsonObject(body + mapOf("generation" to JsonPrimitive(generation), "providerVersion" to JsonPrimitive(verified.providerVersion)))
    }

    /** Where an exception came from as code locations only, past the binder plumbing that rethrows remote ones. */
    internal fun origin(problem: Throwable) = problem.stackTrace.asSequence()
        .filterNot { it.className.startsWith("android.os.Parcel") || it.className.startsWith("android.os.BinderProxy") }
        .take(4).joinToString(">") { "${it.className.substringAfterLast('.')}.${it.methodName}" }

    /** One page on a database file, as the helper reads it. */
    internal fun capture(database: SQLiteDatabase, source: String, table: LocalHealthTable, request: JsonObject): JsonObject =
        HealthJournalRead.capture(FileJournalSql(database), source, table, request.text("epoch") ?: "",
            request.number("after") ?: -1, request.number("upper") ?: -1)

    @android.annotation.SuppressLint("PrivateApi") // Isolated su app_process has no Application; never called in an OEM process.
    internal fun systemContext(): Context {
        if (android.os.Looper.getMainLooper() == null) android.os.Looper.prepareMainLooper()
        val type = Class.forName("android.app.ActivityThread")
        return type.getMethod("getSystemContext").invoke(type.getMethod("systemMain").invoke(null)) as Context
    }
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
