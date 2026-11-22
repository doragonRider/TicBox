package dev.ticbox.companion.healthbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import dev.ticbox.companion.protocol.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

/**
 * Health change-log reads answered by the TicBox module inside the official health app, so a sync needs no root helper
 * process for them. The module hands over its Binder only for the random token it keeps in that app's private files,
 * which takes root to read, and checks the token on every query. All checks run here, on the module's answers, with the
 * same code the helper runs on the file; so the generation, and with it every cursor, is the same on both paths.
 */
internal class HealthModuleJournal(private val context: Context, private val connect: (CancellationSignal) -> ((JsonObject) -> JsonObject)? = {
    ModuleConnection.call(context, it)
}) {
    private class Identity(val versionName: String, val device: Long, val inode: Long)

    /** Null when the module isn't answering here; the caller then starts the root helper. */
    fun read(positions: JsonObject?, cancellation: CancellationSignal): JsonObject? = session(cancellation) { sql, generation, version ->
        HealthJournalRead.read(sql, SOURCE, positions, generation, version)
    }

    fun page(table: LocalHealthTable, request: JsonObject, cancellation: CancellationSignal): JsonObject? = session(cancellation) { sql, generation, _ ->
        HealthJournalRead.page(sql, SOURCE, table, request, generation)
    }

    private fun session(cancellation: CancellationSignal, block: (ModuleJournalSql, String, String) -> JsonObject): JsonObject? {
        val started = android.os.SystemClock.elapsedRealtime()
        val call = connect(cancellation) ?: return null
        val sql = ModuleJournalSql(call, cancellation)
        val before = describe(call)
        // The helper validates the schema and fingerprints it in one go; so does this.
        val generation = HealthJournalRead.generation(SOURCE, before.versionName, before.device, before.inode,
            HealthJournalProfiles.schemaFingerprint(sql, SOURCE))
        val body = block(sql, generation, before.versionName)
        val after = describe(call)
        if (before.device != after.device || before.inode != after.inode) throw HealthJournalException("journal_source_replaced")
        Log.i("TicBoxHealthBridge", "Journal read through the module; queries=${sql.queries} " +
            "millis=${android.os.SystemClock.elapsedRealtime() - started}")
        return JsonObject(body + HealthJournalRead.identity(generation, before.versionName))
    }

    /** The module runs only in an official app whose version and APK hash it matched; the change log must know them too. */
    private fun describe(call: (JsonObject) -> JsonObject): Identity {
        val reply = call(buildJsonObject { put("version", 1); put("op", "journal_describe") })
        reply.text("error")?.let { throw HealthJournalException(it) }
        val name = reply.text("versionName") ?: throw HealthJournalException("journal_invalid_reply")
        if (reply.number("journal") != 1L || !HealthJournalProfiles.profileMatches(SOURCE, reply.text("packageName") ?: "",
                reply.number("versionCode") ?: -1, name, reply.text("apkSha256") ?: "")) throw HealthJournalException("journal_provider_unsupported")
        return Identity(name, reply.number("device") ?: throw HealthJournalException("journal_invalid_reply"),
            reply.number("inode") ?: throw HealthJournalException("journal_invalid_reply"))
    }

    companion object { const val SOURCE = "health" }
}

/** The module's answers as cursors, typed cell by cell exactly as a cursor on the file would type them. */
internal class ModuleJournalSql(private val call: (JsonObject) -> JsonObject, private val cancellation: CancellationSignal) : JournalSql {
    var queries = 0
        private set
    override val version: Int get() = rawQuery("PRAGMA user_version", null).use { check(it.moveToFirst()); it.getInt(0) }

    override fun rawQuery(sql: String, selectionArgs: Array<String>?): Cursor {
        cancellation.throwIfCanceled()
        queries++
        val reply = call(buildJsonObject {
            put("version", 1); put("op", "journal_query"); put("sql", sql)
            put("args", JsonArray(selectionArgs.orEmpty().map(::JsonPrimitive)).toString())
        })
        reply.text("error")?.let { throw HealthJournalException(it) }
        val columns = (reply["columns"] as? JsonArray ?: throw HealthJournalException("journal_invalid_reply")).map { it.jsonPrimitive.content }
        val cursor = MatrixCursor(columns.toTypedArray())
        (reply["rows"] as? JsonArray ?: throw HealthJournalException("journal_invalid_reply")).forEach { row ->
            val cells = row.jsonArray
            if (cells.size != columns.size) throw HealthJournalException("journal_invalid_reply")
            cursor.addRow(cells.map(::cell))
        }
        return cursor
    }

    private fun cell(value: JsonElement): Any? {
        if (value is JsonNull) return null
        val text = value.jsonPrimitive.content
        val body = text.substring(1)
        return when (text.firstOrNull()) {
            'i' -> body.toLong(); 'f' -> body.toDouble(); 's' -> body
            'b' -> java.util.Base64.getDecoder().decode(body)
            else -> throw HealthJournalException("journal_invalid_reply")
        }
    }
}

/** The token and the Binder, kept for the life of this process and fetched again once the module's process is gone. */
internal object ModuleConnection {
    private const val PACKAGE = "com.mobvoi.wear.health.at"
    private const val ACTION = "dev.ticbox.companion.healthmodule.JOURNAL"
    private const val TOKEN_FILE = "no_backup/ticbox-health-token"
    private const val GRANTED = 1
    private const val TRANSACTION = IBinder.FIRST_CALL_TRANSACTION
    private const val HANDOVER_MILLIS = 3_000L
    private val handler by lazy { Handler(HandlerThread("TicBoxModuleJournal").apply { isDaemon = true; start() }.looper) }
    @Volatile private var connection: Pair<IBinder, String>? = null

    fun call(context: Context, cancellation: CancellationSignal): ((JsonObject) -> JsonObject)? {
        val (binder, token) = connection?.takeIf { it.first.isBinderAlive } ?: open(context, cancellation)?.also { connection = it } ?: return null
        return { request -> transact(binder, JsonObject(request + ("token" to JsonPrimitive(token)))) }
    }

    private fun open(context: Context, cancellation: CancellationSignal): Pair<IBinder, String>? {
        val dataDir = try { context.packageManager.getApplicationInfo(PACKAGE, 0).dataDir } catch (_: Exception) { null } ?: return null
        // Without the file the module has never run here: nothing to ask, and no waiting.
        val read = try { RootProcess.run("cat " + shellQuote(File(dataDir, TOKEN_FILE).path), cancellation, 5_000) }
            catch (_: FitnessSourceException) { return null }
        val token = read.output.trim().takeIf { read.exitCode == 0 && it.matches(Regex("[0-9a-f]{32}")) } ?: return null
        val done = CountDownLatch(1)
        var binder: IBinder? = null
        // Ordered, so the answer comes back at once even when no module is listening.
        context.sendOrderedBroadcast(Intent(ACTION).setPackage(PACKAGE).addFlags(Intent.FLAG_RECEIVER_FOREGROUND).putExtra("token", token),
            null, object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (resultCode == GRANTED) binder = getResultExtras(false)?.getBinder("journal")
                    done.countDown()
                }
            }, handler, 0, null, null)
        if (!done.await(HANDOVER_MILLIS, TimeUnit.MILLISECONDS)) return null
        val granted = binder ?: run { Log.i("TicBoxHealthBridge", "Health module journal not available"); return null }
        return granted to token
    }

    private fun transact(binder: IBinder, request: JsonObject): JsonObject {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeByteArray(request.toString().toByteArray(Charsets.UTF_8))
            try { if (!binder.transact(TRANSACTION, data, reply, 0)) throw HealthJournalException("journal_module_unavailable") }
            catch (_: RemoteException) { connection = null; throw HealthJournalException("journal_module_unavailable") }
            val bytes = reply.createByteArray() ?: throw HealthJournalException("journal_invalid_reply")
            return LocalHealthProtocol.parse(bytes)
        } finally { data.recycle(); reply.recycle() }
    }
}
