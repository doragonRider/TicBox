package dev.ticbox.companion.healthbridge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

internal class FitnessSourceException(val code: String) : Exception(code)

internal class FitnessSnapshot(val file: File, val database: SQLiteDatabase, val providerVersion: String?) : AutoCloseable {
    override fun close() {
        try { database.close() } finally { file.delete() }
    }
}

/** Root is used only to produce a consistent SQLite copy. Paging runs under the ordinary app UID. */
internal class RootFitnessSnapshot(private val context: Context) {
    fun open(cancellation: CancellationSignal): FitnessSnapshot {
        cancellation.throwIfCanceled()
        val info = try { context.packageManager.getApplicationInfo(FITNESS_PACKAGE, 0) }
        catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            throw FitnessSourceException("fitness_database_missing")
        }
        val directory = File(context.noBackupFilesDir, "fitness-snapshots").apply { mkdirs() }
        // A killed process may have left its own private temporary copy. Never touch the vendor files.
        directory.listFiles()?.filter { it.name.startsWith("snapshot-") && System.currentTimeMillis() - it.lastModified() > 600_000 }
            ?.forEach { it.delete() }
        // Pre-create under the app UID: owner, mode and SELinux label remain suitable for normal paging.
        val copy = File.createTempFile("snapshot-", ".db", directory)
        try {
            val source = File(info.dataDir, "databases/fitness.db").path
            val command = "printf 'FITNESS_ROOT_READY\\n'; CLASSPATH=${shellQuote(context.applicationInfo.sourceDir)} exec /system/bin/app_process /system/bin " +
                "dev.ticbox.companion.healthbridge.FitnessSnapshotMain ${shellQuote(source)} ${shellQuote(copy.path)}"
            val result = RootProcess.run(command, cancellation, 25_000)
            when {
                result.timedOut || result.exitCode == 124 -> throw FitnessSourceException("snapshot_timeout")
                result.output.lineSequence().any { it == "SNAPSHOT_ERROR:fitness_database_missing" } -> throw FitnessSourceException("fitness_database_missing")
                result.exitCode != 0 && result.output.contains("SNAPSHOT_ERROR:") -> throw FitnessSourceException("snapshot_failed")
                result.exitCode != 0 && result.output.lineSequence().any { it == "FITNESS_ROOT_READY" } -> throw FitnessSourceException("snapshot_failed")
                result.exitCode != 0 -> throw FitnessSourceException("root_denied")
                !result.output.lineSequence().any { it == "SNAPSHOT_OK" } -> throw FitnessSourceException("snapshot_failed")
            }
            cancellation.throwIfCanceled()
            val version = runCatching { context.packageManager.getPackageInfo(FITNESS_PACKAGE, 0).versionName }.getOrNull()
            val database = SQLiteDatabase.openDatabase(copy.path, null, READ_ONLY_FLAGS)
            Log.i("TicBoxHealthBridge", "Fitness snapshot ready; version=$version")
            return FitnessSnapshot(copy, database, version)
        } catch (problem: Exception) {
            copy.delete()
            if (problem is FitnessSourceException || problem is android.os.OperationCanceledException) throw problem
            Log.w("TicBoxHealthBridge", "Fitness snapshot unavailable; exception=${problem.javaClass.simpleName}")
            throw FitnessSourceException("snapshot_failed")
        }
    }

    companion object {
        const val FITNESS_PACKAGE = "com.mobvoi.wear.fitness.at"
        const val READ_ONLY_FLAGS = SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
    }
}

/**
 * Entry point in this APK, loaded by app_process through Magisk su. No vendor code is loaded.
 * proguard-rules.pro keeps it by name.
 */
object FitnessSnapshotMain {
    @JvmStatic fun main(args: Array<String>) {
        // This independent deadline also bounds the worker if its su client is cancelled/disconnected.
        val deadline = Executors.newSingleThreadScheduledExecutor()
        deadline.schedule({ exitProcess(124) }, 20, TimeUnit.SECONDS)
        try {
            if (android.os.Process.myUid() != 0 || args.size != 2) exitProcess(1)
            create(File(args[0]), File(args[1]))
            println("SNAPSHOT_OK")
        } catch (problem: Exception) {
            // SQLite messages may include source data. Only emit an exception type.
            println("SNAPSHOT_ERROR:${(problem as? FitnessSourceException)?.code ?: problem.javaClass.simpleName}")
            exitProcess(1)
        } finally { deadline.shutdownNow() }
    }

    internal fun create(source: File, destination: File) {
        if (!source.isFile) throw FitnessSourceException("fitness_database_missing")
        // Android's default corruption handler can delete the input even on an OPEN_READONLY handle.
        // Keep the vendor source intact on every error; failure must not initiate repair/recreation.
        SQLiteDatabase.openDatabase(source.path, null, RootFitnessSnapshot.READ_ONLY_FLAGS,
            android.database.DatabaseErrorHandler { }).use { database ->
            // INTO writes only destination. SQLite includes committed WAL data in a consistent view.
            // RECORD and POINT have explicit INTEGER PRIMARY KEYs, preserved by this operation.
            database.execSQL("VACUUM INTO ?", arrayOf(destination.path))
        }
    }
}

internal fun shellQuote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"

internal data class RootProcessResult(val exitCode: Int, val output: String, val timedOut: Boolean)

/** Bounded status-only output. Raw stdout/stderr is never written to application logs. */
internal object RootProcess {
    fun run(command: String, cancellation: CancellationSignal, timeoutMillis: Long): RootProcessResult {
        cancellation.throwIfCanceled()
        val process = try { ProcessBuilder("su", "-c", command).redirectErrorStream(true).start() }
        catch (_: java.io.IOException) { throw FitnessSourceException("root_unavailable") }
        val output = ByteArrayOutputStream()
        val drain = Thread({
            try { process.inputStream.use { input ->
                val buffer = ByteArray(1024)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    if (output.size() < 8192) output.write(buffer, 0, minOf(count, 8192 - output.size()))
                }
            } } catch (_: java.io.IOException) { }
        }, "fitness-root-status").apply { isDaemon = true; start() }
        cancellation.setOnCancelListener { process.destroy() }
        try {
            val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            drain.join(500)
            cancellation.throwIfCanceled()
            return RootProcessResult(if (finished) process.exitValue() else -1,
                output.toString(Charsets.UTF_8.name()), !finished)
        } finally {
            cancellation.setOnCancelListener(null)
            process.destroy()
            runCatching { process.inputStream.close() }; runCatching { process.outputStream.close() }
        }
    }

    fun authorize(cancellation: CancellationSignal): Boolean {
        val result = run("id -u", cancellation, 60_000)
        return !result.timedOut && result.exitCode == 0 && result.output.lineSequence().any { it.trim() == "0" }
    }
}
