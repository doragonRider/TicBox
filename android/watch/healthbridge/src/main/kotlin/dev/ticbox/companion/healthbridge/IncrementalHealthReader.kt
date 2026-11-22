package dev.ticbox.companion.healthbridge

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.util.Log
import dev.ticbox.companion.protocol.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.json.JsonObject

/** Durable source observation and delivery are separate: only a phone commit ACK clears a batch. */
internal class IncrementalHealthReader(
    private val store: HealthIncrementalStore,
    private val read: (JsonObject, CancellationSignal) -> JsonObject,
    val tables: List<LocalHealthTable>,
    /** Tells whether the private fitness files changed; null when that can't be determined. */
    private val fitnessStamp: (CancellationSignal) -> String? = { null },
    private val journal: HealthJournalReader? = null,
    /** Closed with the reader: the workout helper kept for this read window. */
    private val helper: AutoCloseable? = null,
) : AutoCloseable {
    constructor(context: Context) : this(context, FitnessHelperSession(context))
    private constructor(context: Context, fitness: FitnessHelperSession) : this(HealthIncrementalStore(context), sourceReader(context, fitness),
        enabledHealthTables(context), { cancellation -> if (BridgeSettings.rootFitnessEnabled(context)) RootFitnessPageReader(context).stamp(cancellation) else null },
        RootHealthJournalReader(context, fitness = fitness), fitness)

    /**
     * Held while a capture begins or reads the source: by the read-ahead thread for one source page at a time, and by the
     * answering thread only when a request finds nothing to send. Everything below that a read touches is kept under it.
     */
    private val scan = ReentrantLock()
    /** The run each receiver's table last began in this window; a request of that run needn't begin it again. */
    private val begun = ConcurrentHashMap<Pair<String, LocalHealthTable>, String>()
    private var stamped: Pair<String, String?>? = null
    private var journalRun: String? = null
    private val journalWindows = mutableMapOf<String, HealthJournalWindow?>()

    /** Where one reply or read-ahead step spent its time, for the log: reading the source, hashing rows, the ledger. */
    private class Timing {
        var source = 0L; var hash = 0L; var ledger = 0L
        fun describe(vararg extra: Pair<String, Long>) = (listOf("source" to source, "hash" to hash, "ledger" to ledger) + extra)
            .joinToString(" ") { (name, nanos) -> "$name=${nanos / 1_000_000}ms" }
    }
    /** The last reply's stages, for the log line that follows it. */
    @Volatile var lastStages = ""
        private set
    private inline fun <T> timed(into: (Long) -> Unit, block: () -> T): T {
        val started = System.nanoTime()
        try { return block() } finally { into(System.nanoTime() - started) }
    }
    /** An ACK that came with a request naming `after`, applied once the reply is on its way: that reply doesn't depend on it. */
    private var deferredAck: Triple<String, LocalHealthTable, String>? = null

    private fun window(table: LocalHealthTable, receiver: String, run: String, cancellation: CancellationSignal): HealthJournalWindow? {
        val source = journalSource(table) ?: return null
        if (journalRun != run) { journalWindows.clear(); journalRun = run }
        if (!journalWindows.containsKey(source)) journalWindows[source] = readWindow(table, source, receiver, run, cancellation)
        return journalWindows[source]
    }

    /**
     * Workout files that look as they did when the window was last read hold the same log, so the window is reused and
     * no helper starts. The look is taken before the read, so a write during it shows up as a change next time.
     * Otherwise one read brings the window and each table's page after this receiver's cursor.
     */
    private fun readWindow(table: LocalHealthTable, source: String, receiver: String, run: String, cancellation: CancellationSignal): HealthJournalWindow? {
        val journal = journal ?: return null
        if (!journal.enabled(source)) return null
        val stamp = if (source == "fitness") stamp(table, run, cancellation) else null
        if (stamp != null) store.journalGate(source, stamp)?.let { kept ->
            Log.i("TicBoxHealthBridge", "Journal window unchanged; source=$source")
            return kept
        }
        val positions = tables.filter { journalSource(it) == source }.mapNotNull { each -> store.journalCursor(receiver, each)?.let { each to it } }.toMap()
        return journal.window(table, positions, cancellation).also { if (stamp != null) store.setJournalGate(source, stamp, it) }
    }

    /** Both fitness tables live in one file, so one look at it serves the whole run. */
    private fun stamp(table: LocalHealthTable, run: String, cancellation: CancellationSignal): String? {
        if (!table.privateFitness) return null
        stamped?.takeIf { it.first == run }?.let { return it.second }
        return fitnessStamp(cancellation).also { stamped = run to it }
    }

    /**
     * [deferAck]: an ACK on a request that names `after` waits for [afterReply]. The bridge passes it; a caller that
     * doesn't gets every ACK applied first.
     */
    fun handle(request: JsonObject, cancellation: CancellationSignal, deferAck: Boolean = false): JsonObject = try {
        afterReply()
        lastStages = ""
        cancellation.throwIfCanceled()
        val timing = Timing()
        val table = tables.firstOrNull { it.wireName == request.text("table") }
            ?: return queryError("unsupported_table")
        val receiver = request.text("receiver") ?: return queryError("receiver_missing")
        if (request.text("op") == "ack") {
            val batch = request.text("batch") ?: return queryError("batch_missing")
            store.acknowledge(receiver, table, batch)
        } else {
            val run = request.text("run") ?: return queryError("run_missing")
            val after = request.text("after")
            // The previous page's ACK may ride on this request. It commits on its own, whatever this read then finds.
            request.text("ack")?.let { batch ->
                if (deferAck && after != null) deferredAck = Triple(receiver, table, batch) else store.acknowledge(receiver, table, batch)
            }
            // A batch made ahead, or rows read ahead, answer at once, without waiting for the read in progress.
            if (begun[receiver to table] != run || store.needsRows(receiver, table, after)) scan.withLock {
                val capture = current(request, table, receiver, run, cancellation)
                if (store.needsRows(receiver, table, after)) {
                    // Pages without changes have nothing to deliver, so they needn't cost a round trip.
                    val (_, error) = fill(receiver, table, run, capture, cancellation, timing) { store.pendingBeyond(receiver, table) > 0 }
                    error?.let { return it }
                }
            }
            var packNanos = 0L
            timed({ packNanos = it }) { store.batch(receiver, table, after) }.also { lastStages = timing.describe("pack" to packNanos) }
        }
    } catch (problem: FitnessSourceException) {
        Log.w("TicBoxHealthBridge", "Fitness source unavailable; reason=${problem.code}")
        queryError(problem.code)
    } catch (_: SecurityException) { queryError("provider_denied") }
    catch (_: android.os.OperationCanceledException) { queryError("query_cancelled") }
    catch (problem: Exception) {
        Log.w("TicBoxHealthBridge", "Incremental query failed; exception=${problem.javaClass.simpleName}")
        queryError("query_failed")
    }

    /** Applies an ACK [handle] left for after its reply. If the reply never went out, the batch is just sent again. */
    fun afterReply() {
        val (receiver, table, batch) = deferredAck ?: return
        deferredAck = null
        try { store.acknowledge(receiver, table, batch) }
        catch (problem: Exception) { Log.w("TicBoxHealthBridge", "Acknowledgement not saved; exception=${problem.javaClass.simpleName}") }
    }

    /** This request's capture: begun once per window and run, as last saved after that. Called with [scan] held. */
    private fun current(request: JsonObject, table: LocalHealthTable, receiver: String, run: String, cancellation: CancellationSignal): HealthCapture {
        if (begun[receiver to table] == run) store.capture(receiver, table)?.takeIf { it.run == run }?.let { return it }
        return store.begin(receiver, table, run, request.flag("full") == true, window(table, receiver, run, cancellation),
            request.number("window")?.takeIf { it > 0 }) { stamp(table, run, cancellation) }.also { begun[receiver to table] = run }
    }

    /** Reads the source until [enough], [steps] pages, or the time budget. A failed source page ends it with that error. */
    private fun fill(receiver: String, table: LocalHealthTable, run: String, start: HealthCapture, cancellation: CancellationSignal,
        timing: Timing, steps: Int = Int.MAX_VALUE, enough: () -> Boolean): Pair<HealthCapture, JsonObject?> {
        var capture = start
        val deadline = System.nanoTime() + READ_BUDGET_NANOS
        var taken = 0
        while (!capture.done && System.nanoTime() < deadline) {
            cancellation.throwIfCanceled()
            // A finished baseline keeps the log head read before it, with no second read of the window: the next run checks
            // its cursor against the window anyway and starts over if the log was cut, rebuilt or replaced meanwhile. It
            // reads nothing, so it goes with the page that ended the scan, and the last batch made after it is the end.
            if (capture.scopes.isEmpty() && checkNotNull(capture.journal).baseline) { capture = store.finishJournal(receiver, table, capture); continue }
            if (taken++ >= steps || enough()) break
            if (capture.scopes.isNotEmpty()) {
                val scope = capture.scopes.first()
                val page = timed({ timing.source += it }) { LocalHealthProtocol.page(read(scope.request(table, store.pageRows), cancellation)) }
                if (page.error != null) return capture to queryError(page.error!!)
                capture = if (scope.probe) timed({ timing.ledger += it }) { store.probed(receiver, table, capture, page.rows.isNotEmpty()) }
                else {
                    val prepared = timed({ timing.hash += it }) { store.prepare(table, page.rows) }
                    timed({ timing.ledger += it }) { store.observe(receiver, table, capture, page, prepared) }
                }
            } else {
                val selected = checkNotNull(capture.journal)
                val page = try {
                    timed({ timing.source += it }) { journal?.page(table, selected.position, selected.upper, cancellation) }
                } catch (cancelled: android.os.OperationCanceledException) { throw cancelled }
                catch (_: Exception) { null }
                val current = page?.let(HealthJournalWindow::parse)
                capture = when {
                    page == null || page.text("error") != null || current == null -> store.withoutJournal(receiver, table, capture, stamp(table, run, cancellation))
                    page.flag("reset") == true || page.flag("wide") == true -> store.resetJournal(receiver, table, capture, current)
                    (page["rows"] as? kotlinx.serialization.json.JsonArray).orEmpty().any { (it as JsonObject).flag("partial") == true } ->
                        store.withoutJournal(receiver, table, capture, stamp(table, run, cancellation))
                    else -> timed({ timing.ledger += it }) { store.observeJournal(receiver, table, capture, page) }
                }
            }
        }
        return capture to null
    }

    enum class ReadAhead { IDLE, MORE, ENOUGH }

    /**
     * Reads one source page ahead for the table [request] asked for, while replies go out from another thread, until
     * [AHEAD_PAGES] pages' worth of rows are queued past the batches made. [ReadAhead.IDLE] when there was nothing to
     * read. Errors are left for the next request to meet and report.
     */
    fun readAhead(request: JsonObject, cancellation: CancellationSignal): ReadAhead {
        val table = tables.firstOrNull { it.wireName == request.text("table") } ?: return ReadAhead.IDLE
        val receiver = request.text("receiver") ?: return ReadAhead.IDLE
        val run = request.text("run") ?: return ReadAhead.IDLE
        val timing = Timing()
        val started = android.os.SystemClock.elapsedRealtime()
        val ahead = AHEAD_PAGES * store.pageRows
        val (before, after, error) = try {
            scan.withLock {
                val capture = current(request, table, receiver, run, cancellation)
                if (capture.done || store.pendingBeyond(receiver, table, ahead) >= ahead) return ReadAhead.IDLE
                val (next, error) = fill(receiver, table, run, capture, cancellation, timing, steps = 1) { false }
                Triple(capture, next, error)
            }
        } catch (_: android.os.OperationCanceledException) { return ReadAhead.IDLE }
        catch (problem: Exception) {
            Log.w("TicBoxHealthBridge", "Read-ahead stopped; exception=${problem.javaClass.simpleName}")
            return ReadAhead.IDLE
        }
        val queued = store.pendingBeyond(receiver, table, ahead)
        Log.i("TicBoxHealthBridge", "Read-ahead; table=${table.wireName} scanned=${after.scanned - before.scanned} queued=$queued " +
            "done=${after.done} error=${error?.text("error") ?: "none"} millis=${android.os.SystemClock.elapsedRealtime() - started} ${timing.describe()}")
        return if (error != null || after.done || queued >= ahead) ReadAhead.ENOUGH else ReadAhead.MORE
    }

    /** Makes the table's next batch from rows read ahead while the one before it travels; true when one was made. */
    fun packAhead(request: JsonObject): Boolean {
        val table = tables.firstOrNull { it.wireName == request.text("table") } ?: return false
        val receiver = request.text("receiver") ?: return false
        val started = System.nanoTime()
        val made = try { store.packAhead(receiver, table) } catch (problem: Exception) {
            Log.w("TicBoxHealthBridge", "Packing ahead stopped; exception=${problem.javaClass.simpleName}")
            false
        }
        if (made) Log.i("TicBoxHealthBridge", "Packed ahead; table=${table.wireName} millis=${(System.nanoTime() - started) / 1_000_000}")
        return made
    }

    /**
     * What the bridge's two threads do once a changes reply is written, in a fixed order on this one: reads ahead and
     * packs until the next batch is ready.
     */
    fun prefetch(request: JsonObject, cancellation: CancellationSignal) {
        while (true) {
            val step = readAhead(request, cancellation)
            packAhead(request)
            if (step != ReadAhead.MORE) return
        }
    }

    override fun close() { try { helper?.close() } finally { store.close() } }
    fun invalidateJournal(source: String) = scan.withLock {
        journalWindows.remove(source)
        begun.keys.removeAll { (_, table) -> journalSource(table) == source }
        store.invalidateJournal(source)
    }

    private companion object {
        /** Well inside the phone's 30-second page timeout. */
        const val READ_BUDGET_NANOS = 3_000_000_000L
        /**
         * Pages read ahead past the batches made. With one (1.0.9), a request arrived while that page was still being read
         * and waited for it. With two (1.0.10), reading caught up with packing and then waited for each batch to be made;
         * the batch waited for the ACK before it, and the ACK for the transaction of the page being read, so the three still
         * took turns. A few more let reading go on while the answering thread waits.
         */
        const val AHEAD_PAGES = 4
    }
}

private fun sourceReader(context: Context, fitnessHelper: FitnessHelperSession): (JsonObject, CancellationSignal) -> JsonObject {
    val provider = HealthProviderReader(context.contentResolver, healthProviderVersion(context, HealthProviderReader.URI),
        healthProviderVersion(context, SportProviderReader.URI), framed = false)
    val fitness = RootFitnessPageReader(context, fitnessHelper)
    return { request, cancellation ->
        if (LocalHealthTable.entries.firstOrNull { it.wireName == request.text("table") }?.privateFitness == true) {
            if (BridgeSettings.rootFitnessEnabled(context)) fitness.page(request, cancellation) else queryError("root_not_enabled")
        } else provider.page(request, cancellation)
    }
}

internal fun enabledHealthTables(context: Context): List<LocalHealthTable> = if (BridgeSettings.rootFitnessEnabled(context))
    listOf(LocalHealthTable.SESSION, LocalHealthTable.POINT, LocalHealthTable.FITNESS_RECORD, LocalHealthTable.FITNESS_POINT)
else listOf(LocalHealthTable.SESSION, LocalHealthTable.POINT, LocalHealthTable.SPORT)

private fun healthProviderVersion(context: Context, uri: Uri): String? = runCatching {
    val info = context.packageManager.resolveContentProvider(uri.authority!!, 0) ?: return null
    context.packageManager.getPackageInfo(info.packageName, 0).versionName
}.getOrNull()
