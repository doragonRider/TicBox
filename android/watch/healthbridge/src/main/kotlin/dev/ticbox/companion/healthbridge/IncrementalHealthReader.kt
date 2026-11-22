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
        cancellation.throwIfCanceled()
        val table = tables.firstOrNull { it.wireName == request.text("table") }
            ?: return queryError("unsupported_table")
        val receiver = request.text("receiver") ?: return queryError("receiver_missing")
        if (request.text("op") == "ack") {
            val batch = request.text("batch") ?: return queryError("batch_missing")
            store.acknowledge(receiver, table, batch)
        } else {
            val run = request.text("run") ?: return queryError("run_missing")
            var capture = store.begin(receiver, table, run, request.flag("full") == true, window(table, run, cancellation),
                request.number("window")?.takeIf { it > 0 }) { stamp(table, run, cancellation) }
            if (!store.hasBatch(receiver, table)) {
                // Pages without changes have nothing to deliver, so they needn't cost a round trip.
                val deadline = System.nanoTime() + READ_BUDGET_NANOS
                while (!capture.done && !store.hasPending(receiver, table) && System.nanoTime() < deadline) {
                    cancellation.throwIfCanceled()
                    if (capture.scopes.isNotEmpty()) {
                        val scope = capture.scopes.first()
                        val page = LocalHealthProtocol.page(read(scope.request(table), cancellation))
                        if (page.error != null) return queryError(page.error!!)
                        capture = if (scope.probe) store.probed(receiver, table, capture, page.rows.isNotEmpty())
                            else store.observe(receiver, table, capture, page)
                    } else {
                        val selected = checkNotNull(capture.journal)
                        val page = try {
                            if (selected.baseline) {
                                val current = journal?.window(table, cancellation)
                                if (current == null) null
                                else if (current.epoch != selected.position.epoch || current.generation != selected.position.generation ||
                                    selected.upper !in current.floor..current.head) {
                                    capture = store.resetJournal(receiver, table, capture, current); continue
                                } else {
                                    capture = store.finishJournal(receiver, table, capture); continue
                                }
                            } else journal?.page(table, selected.position, selected.upper, cancellation)
                        } catch (cancelled: android.os.OperationCanceledException) { throw cancelled }
                        catch (_: Exception) { null }
                        val current = page?.let(HealthJournalWindow::parse)
                        capture = when {
                            page == null || page.text("error") != null || current == null -> store.withoutJournal(receiver, table, capture, stamp(table, run, cancellation))
                            page.flag("reset") == true || page.flag("wide") == true -> store.resetJournal(receiver, table, capture, current)
                            (page["rows"] as? kotlinx.serialization.json.JsonArray).orEmpty().any { (it as JsonObject).flag("partial") == true } ->
                                store.withoutJournal(receiver, table, capture, stamp(table, run, cancellation))
                            else -> store.observeJournal(receiver, table, capture, page)
                        }
                    }
                }
            }
            store.batch(receiver, table, capture)
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
        healthProviderVersion(context, SportProviderReader.URI))
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
