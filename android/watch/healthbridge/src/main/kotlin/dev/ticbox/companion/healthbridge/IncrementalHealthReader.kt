package dev.ticbox.companion.healthbridge

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.util.Log
import dev.ticbox.companion.protocol.*
import kotlinx.serialization.json.JsonObject

/** Durable source observation and delivery are separate: only a phone commit ACK clears a batch. */
internal class IncrementalHealthReader(
    private val store: HealthIncrementalStore,
    private val read: (JsonObject, CancellationSignal) -> JsonObject,
    val tables: List<LocalHealthTable>,
    /** Tells whether the private fitness files changed; null when that can't be determined. */
    private val fitnessStamp: (CancellationSignal) -> String? = { null },
    private val journal: HealthJournalReader? = null,
) : AutoCloseable {
    constructor(context: Context) : this(HealthIncrementalStore(context), sourceReader(context), enabledHealthTables(context),
        { cancellation -> if (BridgeSettings.rootFitnessEnabled(context)) RootFitnessPageReader(context).stamp(cancellation) else null }, RootHealthJournalReader(context))

    private var stamped: Pair<String, String?>? = null
    /** Where the last reply or read-ahead spent its time, for the log: reading the source, hashing rows, the ledger. */
    private var sourceNanos = 0L
    private var hashNanos = 0L
    private var ledgerNanos = 0L
    var lastStages = ""
        private set
    private inline fun <T> timed(into: (Long) -> Unit, block: () -> T): T {
        val started = System.nanoTime()
        try { return block() } finally { into(System.nanoTime() - started) }
    }
    private fun stages(vararg extra: Pair<String, Long>) = (listOf("source" to sourceNanos, "hash" to hashNanos, "ledger" to ledgerNanos) + extra)
        .joinToString(" ") { (name, nanos) -> "$name=${nanos / 1_000_000}ms" }
    private var journalRun: String? = null
    private val journalWindows = mutableMapOf<String, HealthJournalWindow?>()

    private fun window(table: LocalHealthTable, run: String, cancellation: CancellationSignal): HealthJournalWindow? {
        val source = journalSource(table) ?: return null
        if (journalRun != run) { journalWindows.clear(); journalRun = run }
        if (!journalWindows.containsKey(source)) journalWindows[source] = journal?.window(table, cancellation)
        return journalWindows[source]
    }

    /** Both fitness tables live in one file, so one look at it serves the whole run. */
    private fun stamp(table: LocalHealthTable, run: String, cancellation: CancellationSignal): String? {
        if (!table.privateFitness) return null
        stamped?.takeIf { it.first == run }?.let { return it.second }
        return fitnessStamp(cancellation).also { stamped = run to it }
    }

    fun handle(request: JsonObject, cancellation: CancellationSignal): JsonObject = try {
        sourceNanos = 0; hashNanos = 0; ledgerNanos = 0; lastStages = ""
        cancellation.throwIfCanceled()
        val table = tables.firstOrNull { it.wireName == request.text("table") }
            ?: return queryError("unsupported_table")
        val receiver = request.text("receiver") ?: return queryError("receiver_missing")
        if (request.text("op") == "ack") {
            val batch = request.text("batch") ?: return queryError("batch_missing")
            store.acknowledge(receiver, table, batch)
        } else {
            val run = request.text("run") ?: return queryError("run_missing")
            // The previous page's ACK may ride on this request. It commits on its own, whatever this read then finds.
            request.text("ack")?.let { batch -> store.acknowledge(receiver, table, batch) }
            var capture = begin(request, table, receiver, run, cancellation)
            if (!store.hasBatch(receiver, table)) {
                // Pages without changes have nothing to deliver, so they needn't cost a round trip.
                val (read, error) = fill(receiver, table, run, capture, cancellation) { store.hasPending(receiver, table) }
                error?.let { return it }
                capture = read
            }
            var packNanos = 0L
            timed({ packNanos = it }) { store.batch(receiver, table, capture) }.also { lastStages = stages("pack" to packNanos) }
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

    private fun begin(request: JsonObject, table: LocalHealthTable, receiver: String, run: String, cancellation: CancellationSignal) =
        store.begin(receiver, table, run, request.flag("full") == true, window(table, run, cancellation),
            request.number("window")?.takeIf { it > 0 }) { stamp(table, run, cancellation) }

    /** Reads the source until [enough] or the time budget runs out. A failed source page ends it with that error. */
    private fun fill(receiver: String, table: LocalHealthTable, run: String, start: HealthCapture, cancellation: CancellationSignal,
        enough: () -> Boolean): Pair<HealthCapture, JsonObject?> {
        var capture = start
        val deadline = System.nanoTime() + READ_BUDGET_NANOS
        while (!capture.done && !enough() && System.nanoTime() < deadline) {
            cancellation.throwIfCanceled()
            if (capture.scopes.isNotEmpty()) {
                val scope = capture.scopes.first()
                val page = timed({ sourceNanos += it }) { LocalHealthProtocol.page(read(scope.request(table, store.pageRows), cancellation)) }
                if (page.error != null) return capture to queryError(page.error!!)
                capture = if (scope.probe) timed({ ledgerNanos += it }) { store.probed(receiver, table, capture, page.rows.isNotEmpty()) }
                else {
                    val prepared = timed({ hashNanos += it }) { store.prepare(table, page.rows) }
                    timed({ ledgerNanos += it }) { store.observe(receiver, table, capture, page, prepared) }
                }
            } else {
                val selected = checkNotNull(capture.journal)
                // A finished baseline keeps the log head read before it, with no second read of the window: the next run checks
                // its cursor against the window anyway and starts over if the log was cut, rebuilt or replaced meanwhile.
                if (selected.baseline) { capture = store.finishJournal(receiver, table, capture); continue }
                val page = try {
                    timed({ sourceNanos += it }) { journal?.page(table, selected.position, selected.upper, cancellation) }
                } catch (cancelled: android.os.OperationCanceledException) { throw cancelled }
                catch (_: Exception) { null }
                val current = page?.let(HealthJournalWindow::parse)
                capture = when {
                    page == null || page.text("error") != null || current == null -> store.withoutJournal(receiver, table, capture, stamp(table, run, cancellation))
                    page.flag("reset") == true || page.flag("wide") == true -> store.resetJournal(receiver, table, capture, current)
                    (page["rows"] as? kotlinx.serialization.json.JsonArray).orEmpty().any { (it as JsonObject).flag("partial") == true } ->
                        store.withoutJournal(receiver, table, capture, stamp(table, run, cancellation))
                    else -> timed({ ledgerNanos += it }) { store.observeJournal(receiver, table, capture, page) }
                }
            }
        }
        return capture to null
    }

    /**
     * Called once a changes reply is written: reads the next page's rows while that reply travels and the phone saves it,
     * so the next request finds them waiting. Errors are left for that request to meet and report.
     */
    fun prefetch(request: JsonObject, cancellation: CancellationSignal) {
        try {
            val table = tables.firstOrNull { it.wireName == request.text("table") } ?: return
            val receiver = request.text("receiver") ?: return
            val run = request.text("run") ?: return
            val capture = begin(request, table, receiver, run, cancellation)
            sourceNanos = 0; hashNanos = 0; ledgerNanos = 0
            val started = android.os.SystemClock.elapsedRealtime()
            // The batch on its way still counts as pending until its ACK, so this stops one page ahead.
            val (next, error) = fill(receiver, table, run, capture, cancellation) {
                store.pendingCount(receiver, table) >= 2 * store.pageRows
            }
            Log.i("TicBoxHealthBridge", "Read-ahead; table=${table.wireName} scanned=${next.scanned - capture.scanned} " +
                "pending=${store.pendingCount(receiver, table)} done=${next.done} error=${error?.text("error") ?: "none"} " +
                "millis=${android.os.SystemClock.elapsedRealtime() - started} ${stages()}")
        } catch (_: android.os.OperationCanceledException) {
        } catch (problem: Exception) {
            Log.w("TicBoxHealthBridge", "Read-ahead stopped; exception=${problem.javaClass.simpleName}")
        }
    }

    override fun close() = store.close()
    fun invalidateJournal(source: String) {
        journalWindows.remove(source)
        store.invalidateJournal(source)
    }

    private companion object {
        /** Well inside the phone's 30-second page timeout. */
        const val READ_BUDGET_NANOS = 3_000_000_000L
    }
}

private fun sourceReader(context: Context): (JsonObject, CancellationSignal) -> JsonObject {
    val provider = HealthProviderReader(context.contentResolver, healthProviderVersion(context, HealthProviderReader.URI),
        healthProviderVersion(context, SportProviderReader.URI), framed = false)
    val fitness = RootFitnessPageReader(context)
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
