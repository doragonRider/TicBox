package dev.ticbox.companion.engine.health

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.LocalHealthPage
import dev.ticbox.companion.protocol.LocalHealthTable
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LocalHealthLink(val source: HealthRecordSource.LocalDevice, val sessionId: Long)

/** A checkpoint belongs to a bounded scan, not a permanent high-water mark: old rows may change. */
data class LocalHealthCheckpoint(val afterId: Long? = null, val upperId: Long? = null,
    val inProgress: Boolean = true, val partial: Boolean = false,
    /** SPORT uses opaque HASH keys; afterId is the consumed occurrence count at afterKey. */
    val afterKey: String? = null, val upperKey: String? = null,
    val hasBaseline: Boolean = false,
    val receiver: String? = null, val run: String? = null, val full: Boolean = false,
    /** How far back a first sync reaches; null asks for everything. */
    val window: Long? = null)

interface LocalHealthStore : HealthRecordReader {
    suspend fun begin(source: HealthRecordSource.LocalDevice, table: LocalHealthTable): LocalHealthCheckpoint
    /** Row upserts and the next checkpoint must commit in the same transaction. */
    suspend fun commit(source: HealthRecordSource.LocalDevice, table: LocalHealthTable, page: LocalHealthPage, checkpoint: LocalHealthCheckpoint)

    /** When a scan of [source] last completed, so a restarted app need not sync again at once. */
    suspend fun lastSuccess(source: HealthRecordSource.LocalDevice): Long? = null
}

interface LocalHealthConnection : AutoCloseable {
    suspend fun page(table: LocalHealthTable, checkpoint: LocalHealthCheckpoint): LocalHealthPage
    /** Called only after the phone's row/checkpoint transaction has committed. */
    suspend fun acknowledge(table: LocalHealthTable, page: LocalHealthPage) = Unit

    suspend fun journal(source: LocalHealthJournalSource, enabled: Boolean?): LocalHealthJournalStatus =
        throw LocalHealthJournalException("journal_bridge_update_required")

    /** How opening this connection was spent (table is null); empty when not measured. */
    val setupStages: List<LocalHealthSyncStage>
        get() = emptyList()

    /** Whether later tables can be read on this connection; otherwise each table opens its own. */
    val servesAllTables: Boolean
        get() = false

    /** Which sources this watch has enabled. Null means no inventory was supplied. */
    val supportedTables: Set<LocalHealthTable>?
        get() = null
}
fun interface LocalHealthRemote { suspend fun open(link: LocalHealthLink): LocalHealthConnection }

/** Only local acquisition. The service owns jobs; a UI refresh starts/coalesces work and returns. */
class LocalHealthSyncEngine(scope: CoroutineScope, private val store: LocalHealthStore,
    private val remote: LocalHealthRemote, private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val retryDelayMillis: Long = 1_000,
    private val now: () -> Long = System::currentTimeMillis,
    private val tables: List<LocalHealthTable> = listOf(LocalHealthTable.SESSION, LocalHealthTable.POINT,
        LocalHealthTable.SPORT, LocalHealthTable.FITNESS_RECORD, LocalHealthTable.FITNESS_POINT),
    private val autoSyncIntervalMillis: Long = LocalHealthSyncClient.AUTO_SYNC_INTERVAL_MILLIS,
    /** Monotonic clock for stage timings. */
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) : LocalHealthSyncClient {
    private val lifetime = SupervisorJob(scope.coroutineContext[Job])
    private val worker = CoroutineScope(scope.coroutineContext + lifetime)
    private val mutableState = MutableStateFlow(LocalHealthSyncState())
    override val state = mutableState.asStateFlow()
    private val runs = Mutex()
    private var link: LocalHealthLink? = null
    private var generation = 0L
    private var work: Job? = null
    private var closed = false
    /** Per table, from the last reply that table gave; a table not read this run keeps its earlier answer. */
    private val deferred = mutableMapOf<LocalHealthTable, Long>()

    /** A new link syncs at once, unless the last successful sync is recent. */
    suspend fun attach(next: LocalHealthLink?) {
        val stored = next?.let { lastSuccess(it.source) }
        synchronized(this) {
            if (closed || link == next) return
            generation++; work?.cancel(); work = null; link = next
            val previous = state.value
            if (next != null && previous.source != next.source) deferred.clear()
            val remembered = previous.lastSuccessAtMillis.takeIf { next == null || previous.source == next.source }
            mutableState.value = LocalHealthSyncState(
                phase = if (next == null) LocalHealthSyncPhase.DISCONNECTED else LocalHealthSyncPhase.IDLE,
                source = next?.source, sessionId = next?.sessionId ?: 0,
                lastSuccessAtMillis = listOfNotNull(remembered, stored).maxOrNull(),
                lastRun = previous.lastRun, deferredBeforeMillis = deferred.values.minOrNull())
            if (next != null && isStale()) start()
        }
    }

    private suspend fun lastSuccess(source: HealthRecordSource.LocalDevice): Long? = try {
        store.lastSuccess(source)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null } // Unknown age: sync as before.

    private fun isStale(): Boolean {
        val last = state.value.lastSuccessAtMillis ?: return true
        return now() - last !in 0 until autoSyncIntervalMillis
    }

    @Synchronized private fun start(mode: LocalHealthSyncMode = LocalHealthSyncMode.INCREMENTAL) {
        if (closed) throw BackendClosedException()
        val current = link ?: return
        if (work?.isActive == true) {
            if (mode != LocalHealthSyncMode.FULL || state.value.mode == LocalHealthSyncMode.FULL) return
            generation++; work?.cancel()
        }
        val selectedGeneration = generation
        mutableState.value = state.value.copy(mode = mode, phase = LocalHealthSyncPhase.CONNECTING,
            pagesCommitted = 0, recordsCommitted = 0, reason = null)
        work = worker.launch { runs.withLock { acquire(current, selectedGeneration, mode) } }
    }
    override suspend fun sync(mode: LocalHealthSyncMode) = start(mode)
    override suspend fun syncIfStale() = synchronized(this) { if (isStale()) start() }
    override suspend fun cancel() = synchronized(this) {
        if (closed) return@synchronized
        generation++; work?.cancel(); work = null
        mutableState.value = state.value.copy(phase = LocalHealthSyncPhase.IDLE, reason = "cancelled")
    }
    override suspend fun journalStatus(source: LocalHealthJournalSource) = journal(source, null)
    override suspend fun setJournalEnabled(source: LocalHealthJournalSource, enabled: Boolean) = journal(source, enabled)
    // Each read on a connection of its own costs a wake, a connection and a read window on the watch.
    override suspend fun journalStatuses(sources: List<LocalHealthJournalSource>,
        each: suspend (LocalHealthJournalSource, Result<LocalHealthJournalStatus>) -> Unit) = journal(sources.map { it to null }, each)

    private suspend fun journal(source: LocalHealthJournalSource, enabled: Boolean?): LocalHealthJournalStatus {
        var result: Result<LocalHealthJournalStatus>? = null
        journal(listOf(source to enabled)) { _, it -> result = it }
        return checkNotNull(result).getOrThrow()
    }

    /**
     * Journal operations one after another over one connection, each reported as it ends. One that fails with a
     * LocalHealthJournalException is reported and the rest go on; after a timeout the next opens a connection of its
     * own. Any other failure ends them all.
     */
    private suspend fun journal(operations: List<Pair<LocalHealthJournalSource, Boolean?>>,
        each: suspend (LocalHealthJournalSource, Result<LocalHealthJournalStatus>) -> Unit) {
        // A second RFCOMM client cannot share the watch's current acquisition window.
        if (!runs.tryLock()) throw LocalHealthJournalException("journal_sync_in_progress")
        var connection: LocalHealthConnection? = null
        try {
            val (current, selectedGeneration) = synchronized(this) {
                if (closed) throw BackendClosedException()
                (link ?: throw LocalHealthJournalException("journal_not_connected")) to generation
            }
            fun checkLink() = synchronized(this) {
                if (closed || generation != selectedGeneration || link != current)
                    throw LocalHealthJournalException("journal_connection_replaced")
            }
            for ((source, enabled) in operations) {
                val started = elapsedMillis()
                // A stable code, or an exception type; tells a phone-side timeout from an error the watch reported.
                var outcome = "ok"
                val result = try {
                    val status = withTimeoutOrNull(90_000) {
                        val open = connection ?: remote.open(current).also { connection = it }
                        checkLink()
                        // A lost result can mean a completed schema transaction. Never retry this write.
                        open.journal(source, enabled).also { checkLink() }
                    } ?: run { outcome = "phone_timeout"; throw LocalHealthJournalException("journal_operation_unconfirmed") }
                    status.reason?.let { outcome = it }
                    Result.success(status)
                } catch (problem: LocalHealthJournalException) {
                    if (outcome == "ok") outcome = problem.code
                    Result.failure(problem)
                } catch (cancelled: CancellationException) { outcome = "cancelled"; throw cancelled }
                catch (problem: Exception) { outcome = problem.javaClass.simpleName; throw problem }
                finally {
                    runCatching { diagnostics.record(ProtocolDiagnostic("local_health", "journal_result",
                        if (outcome == "ok") DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING, details = mapOf(
                            "source" to source.wireName, "action" to when (enabled) { null -> "status"; true -> "enable"; false -> "disable" },
                            "outcome" to outcome, "millis" to (elapsedMillis() - started).toString()))) }
                }
                if (outcome == "phone_timeout") { connection?.close(); connection = null }
                each(source, result)
            }
        } finally {
            try { connection?.close() } finally { runs.unlock() }
        }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true; generation++; work?.cancel(); lifetime.cancel()
        mutableState.value = state.value.copy(phase = LocalHealthSyncPhase.CLOSED)
    }

    private suspend fun acquire(current: LocalHealthLink, selectedGeneration: Long, mode: LocalHealthSyncMode) {
        var pages = 0; var records = 0
        val committedBatches = mutableSetOf<String>()
        var currentTable: LocalHealthTable? = null
        val stopwatch = SyncStopwatch(now(), elapsedMillis)
        val runId = UUID.randomUUID().toString()
        fun publish(phase: LocalHealthSyncPhase, reason: String? = null, finished: LocalHealthSyncTiming? = null) = synchronized(this) {
            if (!closed && generation == selectedGeneration) mutableState.value = state.value.copy(
                phase = phase, source = current.source, sessionId = current.sessionId,
                pagesCommitted = pages, recordsCommitted = records, reason = reason,
                lastSuccessAtMillis = if (phase == LocalHealthSyncPhase.COMPLETED || phase == LocalHealthSyncPhase.PARTIAL)
                    now() else state.value.lastSuccessAtMillis,
                lastRun = finished ?: state.value.lastRun, deferredBeforeMillis = deferred.values.minOrNull())
        }
        /** One line per table, each stage its own field, so `adb logcat` shows a whole run at once. */
        fun logTiming(timing: LocalHealthSyncTiming, phase: LocalHealthSyncPhase) {
            fun record(details: Map<String, String>) = runCatching { diagnostics.record(ProtocolDiagnostic("local_health", "run_timing",
                DiagnosticSeverity.INFO, peer = current.source.peer, sessionId = current.sessionId, details = details)) }
            record(mapOf("table" to "*", "mode" to mode.name, "phase" to phase.name, "total" to "${timing.totalMillis}ms"))
            timing.stages.groupBy { it.table }.forEach { (table, stages) ->
                record(buildMap {
                    put("table", table ?: "*"); put("mode", mode.name)
                    stages.forEach { stage ->
                        put(stage.name, "${stage.millis}ms x${stage.count}" + if (stage.bytes > 0) " ${stage.bytes}B" else "")
                    }
                })
            }
        }
        fun finish(phase: LocalHealthSyncPhase, reason: String?) {
            val timing = stopwatch.finish()
            publish(phase, reason, finished = timing)
            logTiming(timing, phase)
        }
        fun log(event: String, problem: Exception? = null, page: LocalHealthPage? = null) {
            runCatching { diagnostics.record(ProtocolDiagnostic("local_health", event,
                peer = current.source.peer, sessionId = current.sessionId,
                details = buildMap {
                    put("pages", pages.toString()); put("records", records.toString())
                    put("mode", mode.name)
                    currentTable?.let { put("table", it.wireName) }
                    page?.let { put("scannedRows", it.scannedRows.toString()); put("batchRows", it.rows.size.toString()) }
                    problem?.let { put("exceptionType", it.javaClass.simpleName) }
                })) }
        }
        var connection: LocalHealthConnection? = null
        fun disconnect() {
            connection?.let { runCatching { it.close() } }
            connection = null
        }
        suspend fun scan(table: LocalHealthTable): String? {
            val stage = table.wireName
            var checkpoint = stopwatch.measure(stage, "checkpoint") { store.begin(current.source, table) }.let {
                it.copy(receiver = current.source.localUserId, run = runId,
                    full = mode == LocalHealthSyncMode.FULL || !it.hasBaseline,
                    window = if (mode == LocalHealthSyncMode.FULL) null else LocalHealthSyncClient.FIRST_SYNC_WINDOW_DAYS * 86_400_000L)
            }
            var failures = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                publish(LocalHealthSyncPhase.CONNECTING)
                try {
                    var ended = false
                    var reason: String? = null
                    run {
                        val open = connection ?: stopwatch.measure(stage, "open") { withTimeout(40_000) { remote.open(current) } }.also { opened ->
                            connection = opened
                            opened.setupStages.forEach { stopwatch.add(stage, it.name, it.millis, it.count) }
                            synchronized(this) {
                                if (!closed && generation == selectedGeneration) mutableState.value =
                                    state.value.copy(advertisedTables = opened.supportedTables?.map { it.wireName }?.toSet())
                            }
                        }
                        if (open.supportedTables?.contains(table) == false) {
                            log("table_not_advertised"); reason = "table_not_advertised"; ended = true; return@run
                        }
                        while (currentCoroutineContext().isActive) {
                            publish(LocalHealthSyncPhase.READING)
                            val readStarted = elapsedMillis()
                            val page = stopwatch.measure(stage, "read") { withTimeout(30_000) { open.page(table, checkpoint) } }
                            stopwatch.add(stage, "read", 0, count = 0, bytes = page.wireBytes)
                            // The watch says how long it worked on the page; the rest went to Bluetooth and parsing here.
                            page.watchMillis?.let { watch ->
                                stopwatch.add(stage, "watch", watch)
                                // The request waited on the watch while it read the next rows ahead; not Bluetooth time.
                                val queued = page.queuedMillis?.also { stopwatch.add(stage, "queued", it) } ?: 0
                                stopwatch.add(stage, "transfer", (elapsedMillis() - readStarted - watch - queued).coerceAtLeast(0))
                            }
                            if (page.error != null) {
                                // Remote errors are identifiers, never copy remote exception text into diagnostics/UI.
                                reason = when (page.error) {
                                    "provider_missing", "provider_denied", "unsupported_schema", "unsupported_table", "row_exceeds_budget", "bridge_disabled",
                                    "root_not_enabled", "root_denied", "root_unavailable", "fitness_database_missing", "snapshot_failed", "snapshot_timeout" -> page.error
                                    else -> "watch_query_failed"
                                }
                                log("watch_read_unavailable"); ended = true; return@run
                            }
                            val textCursor = table == LocalHealthTable.SPORT
                            val next = if (textCursor || page.batchId != null) page.nextId else
                                listOfNotNull(page.nextId, page.rows.mapNotNull { it.id }.maxOrNull()).maxOrNull()
                            val stalled = page.batchId == null && !page.complete && if (textCursor) {
                                page.nextKey == null || (page.nextKey == checkpoint.afterKey &&
                                    (next == null || next <= (checkpoint.afterId ?: 0)))
                            } else next == null || (checkpoint.afterId != null && next <= checkpoint.afterId)
                            val nextCheckpoint = LocalHealthCheckpoint(next ?: checkpoint.afterId,
                                page.upperId ?: checkpoint.upperId, !page.complete && !stalled, checkpoint.partial || page.partial || stalled,
                                page.nextKey ?: checkpoint.afterKey, page.upperKey ?: checkpoint.upperKey,
                                hasBaseline = true, receiver = checkpoint.receiver, run = checkpoint.run, full = checkpoint.full,
                                window = checkpoint.window)
                            stopwatch.measure(stage, "save") { store.commit(current.source, table, page, nextCheckpoint) }
                            if (page.batchId == null || committedBatches.add("${table.wireName}:${page.batchId}")) {
                                pages++; records += page.rows.size
                            }
                            checkpoint = nextCheckpoint
                            synchronized(this) { page.deferredBefore?.let { deferred[table] = it } ?: deferred.remove(table) }
                            log("page_committed", page = page)
                            stopwatch.measure(stage, "ack") { withTimeout(30_000) { open.acknowledge(table, page) } }
                            failures = 0
                            if (page.complete || stalled) {
                                reason = if (stalled) "page_did_not_advance" else if (checkpoint.partial) "partial_cells" else null
                                if (stalled) log("page_did_not_advance_preserved")
                                log("scan_completed"); ended = true; return@run
                            }
                        }
                    }
                    if (ended) {
                        if (connection?.servesAllTables != true) disconnect()
                        return reason
                    }
                } catch (timeout: TimeoutCancellationException) {
                    disconnect()
                    currentCoroutineContext().ensureActive()
                    if (++failures >= 3) { log("bridge_timeout", timeout); return "bridge_timeout" }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (problem: IOException) {
                    disconnect()
                    if (++failures >= 3) { log("bridge_connection_failed", problem); return "bridge_connection_failed" }
                    log("connection_interrupted", problem)
                }
                publish(LocalHealthSyncPhase.RETRYING)
                stopwatch.measure(stage, "retry_wait") { delay(retryDelayMillis * failures) }
            }
        }
        try {
            val problems = mutableListOf<String>()
            var availableTables = 0
            for (table in tables) {
                currentTable = table
                val reason = scan(table)
                if (reason == "table_not_advertised") continue
                if (reason == null || reason == "partial_cells" || reason == "page_did_not_advance") availableTables++
                reason?.let { problems += if (tables.size == 1) it else "${table.wireName}:$it" }
            }
            disconnect()
            if (availableTables == 0 && problems.isEmpty()) problems += "no_advertised_sources"
            finish(when {
                problems.isEmpty() -> LocalHealthSyncPhase.COMPLETED
                availableTables > 0 -> LocalHealthSyncPhase.PARTIAL
                else -> LocalHealthSyncPhase.UNAVAILABLE
            }, problems.takeIf { it.isNotEmpty() }?.joinToString(","))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) {
            finish(LocalHealthSyncPhase.FAILED, "local_sync_${problem.javaClass.simpleName}")
            log("sync_failed", problem)
        } finally { disconnect() }
    }
}

/** Adds up where one sync run spends its time. Stages keep the order in which they first ran. */
private class SyncStopwatch(private val startedAtMillis: Long, private val elapsedMillis: () -> Long) {
    private val started = elapsedMillis()
    private val stages = linkedMapOf<Pair<String?, String>, LocalHealthSyncStage>()

    @Synchronized fun add(table: String?, name: String, millis: Long, count: Int = 1, bytes: Long = 0) {
        val previous = stages[table to name]
        stages[table to name] = LocalHealthSyncStage(table, name,
            millis = (previous?.millis ?: 0) + millis, count = (previous?.count ?: 0) + count, bytes = (previous?.bytes ?: 0) + bytes)
    }

    /** Failed and cancelled attempts count too: they are part of why a sync was slow. */
    inline fun <T> measure(table: String?, name: String, block: () -> T): T {
        val start = elapsedMillis()
        try { return block() } finally { add(table, name, elapsedMillis() - start) }
    }

    @Synchronized fun finish() = LocalHealthSyncTiming(startedAtMillis, elapsedMillis() - started, stages.values.toList())
}
