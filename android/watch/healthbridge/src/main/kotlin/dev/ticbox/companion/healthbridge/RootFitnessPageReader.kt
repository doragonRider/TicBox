package dev.ticbox.companion.healthbridge

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import dev.ticbox.companion.protocol.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Normal synchronization reads one bounded source page; it never copies the whole fitness DB. */
internal class RootFitnessPageReader(private val context: Context, private val session: FitnessHelperSession? = null) {
    private fun fitnessInfo() = try { context.packageManager.getApplicationInfo(RootFitnessSnapshot.FITNESS_PACKAGE, 0) }
    catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }

    /**
     * How the database files look right now, or null if that can't be told. Far cheaper than
     * starting the page helper, so unchanged files can skip it. Only times and sizes are read.
     */
    fun stamp(cancellation: CancellationSignal): String? {
        val info = fitnessInfo() ?: return NO_DATABASE
        val source = File(info.dataDir, "databases/fitness.db").path
        val quoted = shellQuote(source)
        val result = RootProcess.run("[ -f $quoted ] || { printf '$NO_DATABASE\\n'; exit 0; }; " +
            "stat -c '%y %s' $quoted ${shellQuote("$source-wal")} 2>/dev/null; exit 0", cancellation, 25_000)
        return result.output.trim().takeIf { !result.timedOut && result.exitCode == 0 && it.isNotEmpty() }
    }

    fun page(request: JsonObject, cancellation: CancellationSignal): JsonObject {
        cancellation.throwIfCanceled()
        // A watch that has never recorded a workout has nothing to read, which is not a failure.
        val info = fitnessInfo() ?: return noWorkouts()
        session?.page(request, cancellation)?.let { page -> return withVersion(page) }
        val directory = File(context.noBackupFilesDir, "fitness-pages").apply { mkdirs() }
        directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 600_000 }?.forEach { it.delete() }
        // Pre-created by the application so the root worker preserves its owner and private label.
        val input = File.createTempFile("request-", ".json", directory)
        val output = File.createTempFile("page-", ".json", directory)
        try {
            input.writeText(request.toString())
            val source = File(info.dataDir, "databases/fitness.db")
            val command = "printf 'FITNESS_ROOT_READY\\n'; CLASSPATH=${shellQuote(context.applicationInfo.sourceDir)} exec /system/bin/app_process /system/bin " +
                "dev.ticbox.companion.healthbridge.FitnessPageMain ${shellQuote(source.path)} ${shellQuote(input.path)} ${shellQuote(output.path)}"
            val result = RootProcess.run(command, cancellation, 25_000)
            when {
                result.timedOut || result.exitCode == 124 -> throw FitnessSourceException("snapshot_timeout")
                result.exitCode != 0 -> throw FitnessSourceException(if (result.output.contains("FITNESS_ROOT_READY")) "snapshot_failed" else "root_denied")
                result.output.lineSequence().none { it == "PAGE_OK" } -> throw FitnessSourceException("snapshot_failed")
            }
            cancellation.throwIfCanceled()
            if (output.length() !in 1..LocalHealthProtocol.MAX_FRAME_BYTES.toLong()) throw FitnessSourceException("snapshot_failed")
            return withVersion(LocalHealthProtocol.parse(output.readBytes()))
        } finally { input.delete(); output.delete() }
    }

    private fun withVersion(page: JsonObject): JsonObject {
        val version = runCatching { context.packageManager.getPackageInfo(RootFitnessSnapshot.FITNESS_PACKAGE, 0).versionName }.getOrNull()
        return if (version == null) page else JsonObject(page + ("providerVersion" to JsonPrimitive(version)))
    }

    private companion object { const val NO_DATABASE = "no_database" }
}

private fun noWorkouts() = localHealthPage(emptyList(), null, null, true, null)

/**
 * A read-only connection includes committed WAL rows without changing OEM sync flags.
 * proguard-rules.pro keeps this entry point by name.
 */
object FitnessPageMain {
    @JvmStatic fun main(args: Array<String>) {
        val deadline = Executors.newSingleThreadScheduledExecutor()
        deadline.schedule({ exitProcess(124) }, 20, TimeUnit.SECONDS)
        try {
            if (android.os.Process.myUid() != 0 || args.size != 3) exitProcess(1)
            val page = capture(File(args[0]), LocalHealthProtocol.parse(File(args[1]).readBytes()))
            File(args[2]).writeBytes(page.toString().toByteArray(Charsets.UTF_8))
            println("PAGE_OK")
        } catch (problem: Exception) {
            println("PAGE_ERROR:${(problem as? FitnessSourceException)?.code ?: problem.javaClass.simpleName}")
            exitProcess(1)
        } finally { deadline.shutdownNow() }
    }

    internal fun capture(source: File, request: JsonObject, cancellation: CancellationSignal = CancellationSignal()): JsonObject {
        val table = LocalHealthTable.entries.firstOrNull { it.privateFitness && it.wireName == request.text("table") }
            ?: return queryError("unsupported_table")
        if (!source.isFile) return noWorkouts()
        return SQLiteDatabase.openDatabase(source.path, null, RootFitnessSnapshot.READ_ONLY_FLAGS,
            DatabaseErrorHandler { }).use { database -> fitnessDatabasePage(database, null, table, request, cancellation) }
    }
}
