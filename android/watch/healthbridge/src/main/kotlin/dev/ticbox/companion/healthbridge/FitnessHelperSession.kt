package dev.ticbox.companion.healthbridge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import android.os.SystemClock
import android.system.Os
import android.util.Log
import dev.ticbox.companion.protocol.*
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import kotlinx.serialization.json.*

/**
 * One root helper for the workout database, kept for a read window. Starting a VM as root takes about two seconds, and a
 * sync used to start one for the log and another for each page of each workout table. Requests and answers go as
 * length-prefixed frames over the helper's stdin and stdout. When the helper can't be started this way the callers use
 * their one-shot helpers, and a version that failed once doesn't try again.
 */
internal class FitnessHelperSession(private val context: Context,
    private val launch: (String) -> Process = { command -> ProcessBuilder("su", "--mount-master", "-c", command).start() }) : AutoCloseable {
    private var process: Process? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var broken = false
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "TicBoxFitnessHelper").apply { isDaemon = true } }
    /** Cancelled by [close], so a start that [warm] began ends with the window. */
    private val warming = CancellationSignal()

    /**
     * Starts the helper on a thread of its own, so it's ready by the first workout read, which waits for this start rather
     * than making another. For a full sync, which reads every workout page through it while the other tables go first.
     */
    fun warm() {
        thread(isDaemon = true, name = "TicBoxFitnessHelperStart") {
            synchronized(this) {
                if (broken || process != null || warming.isCanceled) return@synchronized
                try { start(warming) } catch (_: android.os.OperationCanceledException) { stop() }
            }
        }
    }

    /** A workout table page, or null when the one-shot helper has to read it. */
    fun page(request: JsonObject, cancellation: CancellationSignal): JsonObject? =
        call(buildJsonObject { put("kind", "page"); put("page", request) }, cancellation)?.also { reply ->
            reply.text("helperError")?.let { throw FitnessSourceException(it) }
        }

    /** A log read or page, or null when the one-shot helper has to answer it. */
    fun journal(request: JsonObject, cancellation: CancellationSignal): JsonObject? {
        val started = SystemClock.elapsedRealtime()
        val reply = try { call(buildJsonObject { put("kind", "journal"); put("journal", request) }, cancellation) }
            catch (_: FitnessSourceException) { throw HealthJournalException("journal_operation_unconfirmed") } ?: return null
        Log.i(TAG, "Journal read through the fitness helper; action=${request.text("action")} millis=${SystemClock.elapsedRealtime() - started}")
        reply.text("error")?.let { error -> Log.w(TAG, "Journal helper failed; action=${request.text("action")} error=$error " +
            "step=${reply.text("step") ?: "none"} exception=${reply.text("exception") ?: "none"} at=${reply.text("at") ?: "none"}") }
        return reply
    }

    @Synchronized private fun call(request: JsonObject, cancellation: CancellationSignal): JsonObject? {
        cancellation.throwIfCanceled()
        if (broken || (process == null && !start(cancellation))) return null
        val running = checkNotNull(process)
        val watchdog = timer.schedule({ running.destroy() }, CALL_MILLIS, TimeUnit.MILLISECONDS)
        cancellation.setOnCancelListener { running.destroy() }
        try {
            LocalHealthProtocol.write(checkNotNull(output), request)
            return LocalHealthProtocol.read(checkNotNull(input)) ?: throw IOException("helper_closed")
        } catch (problem: Exception) {
            stop()
            cancellation.throwIfCanceled()
            if (watchdog.isDone) throw FitnessSourceException("snapshot_timeout")
            // The helper ended between answers, which leaves the rest of this window to the one-shot helpers.
            Log.w(TAG, "Fitness helper ended; exception=${problem.javaClass.simpleName}")
            broken = true
            return null
        } finally {
            watchdog.cancel(false)
            cancellation.setOnCancelListener(null)
        }
    }

    private fun start(cancellation: CancellationSignal): Boolean {
        val failed = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        if (failed.getLong(FAILED_VERSION, -1) == version) { broken = true; return false }
        val started = SystemClock.elapsedRealtime()
        val command = "CLASSPATH=${shellQuote(context.applicationInfo.sourceDir)} exec /system/bin/app_process /system/bin " +
            "dev.ticbox.companion.healthbridge.FitnessHelperMain"
        val launched = try { launch(command) } catch (_: IOException) { broken = true; return false }
        // Nothing reads the helper's errors; the stream is drained so it can never block on it.
        Thread({ try { launched.errorStream.use { errors -> val buffer = ByteArray(1024); do { } while (errors.read(buffer) >= 0) } }
            catch (_: IOException) { } }, "TicBoxFitnessHelperErrors").apply { isDaemon = true; start() }
        process = launched; input = launched.inputStream; output = launched.outputStream
        val watchdog = timer.schedule({ launched.destroy() }, START_MILLIS, TimeUnit.MILLISECONDS)
        cancellation.setOnCancelListener { launched.destroy() }
        var garbled = false
        val ready = try { LocalHealthProtocol.read(launched.inputStream) } catch (_: Exception) { garbled = true; null }
            finally { cancellation.setOnCancelListener(null) }
        val slow = watchdog.isDone.also { watchdog.cancel(false) }
        cancellation.throwIfCanceled()
        if (ready?.flag("ready") == true) {
            Log.i(TAG, "Fitness helper started; millis=${SystemClock.elapsedRealtime() - started}")
            return true
        }
        stop(); broken = true
        // Root refused, or no workout app, leaves the one-shot helpers to meet and report the same, and root may be granted
        // later. No answer in time, one that isn't a frame, or a helper that failed to set itself up is this way of reading
        // failing, which later syncs on this version needn't pay for again.
        val unusable = slow || garbled || (ready != null && ready.text("helperError") in setOf(null, "snapshot_failed"))
        val reason = ready?.text("helperError") ?: when { slow -> "timeout"; garbled -> "garbled"; ready == null -> "closed"; else -> "invalid" }
        Log.w(TAG, "Fitness helper unavailable; reason=$reason step=${ready?.text("step") ?: "none"} " +
            "exception=${ready?.text("exception") ?: "none"} millis=${SystemClock.elapsedRealtime() - started}")
        if (unusable) failed.edit().putLong(FAILED_VERSION, version).apply()
        return false
    }

    private fun stop() {
        process?.destroy(); process = null
        runCatching { input?.close() }; runCatching { output?.close() }; input = null; output = null
    }

    override fun close() {
        // Ends a start still under way, which holds the lock taken below.
        warming.cancel()
        synchronized(this) { stop(); timer.shutdownNow() }
    }

    private companion object {
        const val TAG = "TicBoxHealthBridge"
        const val PREFERENCES = "fitness-helper"
        const val FAILED_VERSION = "failed-version"
        /** Starting a VM as root and checking the OEM app take a few seconds; more means it isn't coming. */
        const val START_MILLIS = 15_000L
        /** As the one-shot helpers allow for one answer. */
        const val CALL_MILLIS = 25_000L
    }
}

/**
 * The helper side: as root, it checks the workout app once, then runs as that app's user and answers reads until stdin
 * closes. Nothing else may write to stdout, which carries the frames. proguard-rules.pro keeps this entry point by name.
 */
object FitnessHelperMain {
    /** The longest a read window lasts on the watch. */
    private const val SESSION_MILLIS = 5 * 60_000L

    @JvmStatic fun main(args: Array<String>) {
        val deadline = Executors.newSingleThreadScheduledExecutor()
        deadline.schedule({ exitProcess(124) }, SESSION_MILLIS, TimeUnit.MILLISECONDS)
        val output = FileOutputStream(FileDescriptor.out)
        try {
            if (android.os.Process.myUid() != 0 || args.isNotEmpty()) exitProcess(77)
            val answer = try { setUp() } catch (problem: SetUpProblem) {
                LocalHealthProtocol.write(output, problem.reply); exitProcess(1)
            }
            LocalHealthProtocol.write(output, buildJsonObject { put("ready", true) })
            serve(FileInputStream(FileDescriptor.`in`), output, answer)
        } finally { deadline.shutdownNow() }
        exitProcess(0)
    }

    private class SetUpProblem(val reply: JsonObject) : Exception()

    /** Answers each request frame with one reply frame until the input ends. */
    internal fun serve(input: InputStream, output: OutputStream, answer: (JsonObject) -> JsonObject) {
        while (true) {
            val request = LocalHealthProtocol.read(input) ?: return
            LocalHealthProtocol.write(output, answer(request))
        }
    }

    private fun setUp(): (JsonObject) -> JsonObject {
        var step = "sqlite"
        try {
            // As HealthJournalMain explains: the first database is opened before the system context exists.
            SQLiteDatabase.create(null).close()
            step = "context"; val context = HealthJournalMain.systemContext()
            step = "package"
            val info = try { context.packageManager.getApplicationInfo(RootFitnessSnapshot.FITNESS_PACKAGE, 0) }
                catch (_: android.content.pm.PackageManager.NameNotFoundException) { throw FitnessSourceException("fitness_database_missing") }
            // The log needs an app version it was made for; workout pages don't, so a refusal is kept for log requests only.
            step = "verify"
            var refusal: String? = null
            val verified = try { HealthJournalProfiles.verify(context, "fitness") } catch (problem: Exception) {
                refusal = (problem as? HealthJournalException)?.code ?: "journal_operation_unconfirmed"; null
            }
            val file = verified?.file ?: File(info.dataDir, "databases/fitness.db")
            step = "stat"; val before = verified?.let { Os.stat(it.file.path) }
            // Reads run as the app's own user, so SQLite never leaves root-owned files beside its database.
            step = "uid"; val uid = verified?.uid ?: info.uid
            Os.setgid(uid); Os.setuid(uid)
            check(android.os.Process.myUid() == uid && Os.getgid() == uid)
            return { request -> answer(request, file, verified, before, refusal) }
        } catch (problem: Exception) {
            throw SetUpProblem(buildJsonObject {
                put("helperError", (problem as? FitnessSourceException)?.code ?: "snapshot_failed")
                put("step", step)
                if (problem !is FitnessSourceException) { put("exception", problem.javaClass.simpleName); put("at", HealthJournalMain.origin(problem)) }
            })
        }
    }

    private fun answer(request: JsonObject, file: File, verified: VerifiedHealthJournalSource?, before: android.system.StructStat?,
        refusal: String?): JsonObject = when (request.text("kind")) {
        "page" -> try { FitnessPageMain.capture(file, request.getValue("page").jsonObject) }
            catch (problem: Exception) { buildJsonObject { put("helperError", (problem as? FitnessSourceException)?.code ?: "snapshot_failed") } }
        "journal" -> {
            val journal = request.getValue("journal").jsonObject
            var step = "request"
            try {
                val action = journal.text("action")
                if (action !in setOf("status", "read", "page") || journal.text("source") != "fitness") throw HealthJournalException("journal_invalid_action")
                if (verified == null || before == null) throw HealthJournalException(refusal ?: "journal_provider_unsupported")
                step = "open"
                openJournalDatabase(verified.file, write = false).use { database ->
                    HealthJournalMain.journalAnswer(database, verified, before, journal, action!!) { step = it }
                }
            } catch (problem: Exception) { buildJsonObject {
                put("error", (problem as? HealthJournalException)?.code ?: "journal_operation_unconfirmed")
                if (problem !is HealthJournalException) { put("step", step); put("exception", problem.javaClass.simpleName); put("at", HealthJournalMain.origin(problem)) }
            } }
        }
        else -> buildJsonObject { put("helperError", "snapshot_failed") }
    }
}
