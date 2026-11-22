package dev.ticbox.companion.engine.health

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LocalHealthSyncEngineTest {
    private val source = HealthRecordSource.LocalDevice("profile", PeerAddress("AA:BB:CC:DD:EE:FF"))
    private val link = LocalHealthLink(source, 42)
    private fun row(id: Long) = LocalHealthRow(buildJsonObject { put("id", id.toString()); put("values", "raw-secret,$id,,future") })
    private class Store : LocalHealthStore {
        var checkpoint = LocalHealthCheckpoint(); val rows = mutableMapOf<Long?, LocalHealthRow>()
        override suspend fun begin(source: HealthRecordSource.LocalDevice, table: LocalHealthTable) = if (checkpoint.inProgress) checkpoint else LocalHealthCheckpoint()
        override suspend fun commit(source: HealthRecordSource.LocalDevice, table: LocalHealthTable, page: LocalHealthPage, checkpoint: LocalHealthCheckpoint) {
            page.rows.forEach { rows[it.id] = it }; this.checkpoint = checkpoint
        }
        var lastSuccess: Long? = null
        override suspend fun lastSuccess(source: HealthRecordSource.LocalDevice) = lastSuccess
        override suspend fun sources() = emptyList<HealthRecordSource>()
        override suspend fun records(kind: HealthRecordKind, query: HealthRecordQuery) = HealthRecordPage(emptyList(), null)
    }
    @Test fun `journal installation is explicit and a lost result is never retried`() = runTest {
        val now = 10_000L; val requests = mutableListOf<Boolean?>(); var installed = false
        val store = Store().apply { lastSuccess = now }
        val engine = LocalHealthSyncEngine(backgroundScope, store, LocalHealthRemote {
            object : LocalHealthConnection {
                override suspend fun page(table: LocalHealthTable, checkpoint: LocalHealthCheckpoint): LocalHealthPage = error("unexpected scan")
                override suspend fun journal(source: LocalHealthJournalSource, enabled: Boolean?): LocalHealthJournalStatus {
                    requests += enabled
                    if (enabled == true) { installed = true; throw IOException("lost reply after commit") }
                    if (enabled == false) installed = false
                    return LocalHealthJournalStatus(source, installed, installed)
                }
                override fun close() = Unit
            }
        }, now = { now })
        engine.attach(link); runCurrent(); assertTrue(requests.isEmpty())
        assertFalse(engine.journalStatus(LocalHealthJournalSource.HEALTH).installed!!)
        assertFailsWith<IOException> { engine.setJournalEnabled(LocalHealthJournalSource.HEALTH, true) }
        runCurrent(); assertEquals(listOf(null, true), requests)
        assertTrue(engine.journalStatus(LocalHealthJournalSource.HEALTH).installed!!)
        assertFalse(engine.setJournalEnabled(LocalHealthJournalSource.HEALTH, false).installed!!)
        engine.close()
    }
    @Test fun journalTimeoutIsAnUnconfirmedBusinessFailureButCallerCancellationStillPropagates() = runTest {
        var writes = 0; var closes = 0
        val now = 10_000L
        val engine = LocalHealthSyncEngine(backgroundScope, Store().apply { lastSuccess = now }, LocalHealthRemote {
            object : LocalHealthConnection {
                override suspend fun page(table: LocalHealthTable, checkpoint: LocalHealthCheckpoint): LocalHealthPage = error("unexpected scan")
                override suspend fun journal(source: LocalHealthJournalSource, enabled: Boolean?): LocalHealthJournalStatus {
                    writes++; awaitCancellation()
                }
                override fun close() { closes++ }
            }
        }, now = { now })
        engine.attach(link)
        val expired = async { runCatching { engine.setJournalEnabled(LocalHealthJournalSource.HEALTH, true) } }
        runCurrent(); advanceTimeBy(90_001); runCurrent()
        assertEquals("journal_operation_unconfirmed", (expired.await().exceptionOrNull() as LocalHealthJournalException).code)
        assertEquals(1, writes); assertEquals(1, closes)
        val cancelled = async { engine.setJournalEnabled(LocalHealthJournalSource.HEALTH, false) }
        runCurrent(); cancelled.cancel()
        assertFailsWith<CancellationException> { cancelled.await() }
        assertEquals(2, writes); assertEquals(2, closes)
        engine.close()
    }

    @Test fun `journal control refuses a concurrent sync instead of opening another read window`() = runTest {
        val started = CompletableDeferred<Unit>()
        var journalRequests = 0
        val engine = LocalHealthSyncEngine(backgroundScope, Store(), LocalHealthRemote {
            object : LocalHealthConnection {
                override suspend fun page(table: LocalHealthTable, checkpoint: LocalHealthCheckpoint): LocalHealthPage {
                    started.complete(Unit); awaitCancellation()
                }
                override suspend fun journal(source: LocalHealthJournalSource, enabled: Boolean?): LocalHealthJournalStatus {
                    journalRequests++; return LocalHealthJournalStatus(source, false, false)
                }
                override fun close() = Unit
            }
        }, tables = listOf(LocalHealthTable.POINT))
        engine.attach(link); started.await()
        assertEquals("journal_sync_in_progress", assertFailsWith<LocalHealthJournalException> {
            engine.setJournalEnabled(LocalHealthJournalSource.HEALTH, true)
        }.code)
        assertEquals(0, journalRequests); engine.close()
    }

    @Test fun `journal result from an old link is not attributed to its replacement`() = runTest {
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>(); var closes = 0
        val store = Store().apply { lastSuccess = 10_000 }
        val engine = LocalHealthSyncEngine(backgroundScope, store, LocalHealthRemote {
            object : LocalHealthConnection {
                override suspend fun page(table: LocalHealthTable, checkpoint: LocalHealthCheckpoint): LocalHealthPage = error("unexpected scan")
                override suspend fun journal(source: LocalHealthJournalSource, enabled: Boolean?): LocalHealthJournalStatus {
                    entered.complete(Unit); finish.await(); return LocalHealthJournalStatus(source, true, true)
                }
                override fun close() { closes++ }
            }
        }, now = { 10_000 })
        engine.attach(link)
        val result = async { runCatching { engine.setJournalEnabled(LocalHealthJournalSource.HEALTH, true) } }
        entered.await(); engine.attach(link.copy(sessionId = 43)); finish.complete(Unit)
        assertEquals("journal_connection_replaced", (result.await().exceptionOrNull() as LocalHealthJournalException).code)
        assertEquals(1, closes); engine.close()
    }
    @Test fun `full sync replaces a blocked incremental scan and repeated full requests coalesce`() = runTest {
        val modes = mutableListOf<Boolean>(); val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        var baseline = true
        val store = object : LocalHealthStore by Store() {
            override suspend fun begin(source: HealthRecordSource.LocalDevice, table: LocalHealthTable) = LocalHealthCheckpoint(hasBaseline = baseline)
        }
        val engine = LocalHealthSyncEngine(backgroundScope, store, LocalHealthRemote {
            object : LocalHealthConnection {
                override suspend fun page(table: LocalHealthTable, checkpoint: LocalHealthCheckpoint): LocalHealthPage {
                    modes += checkpoint.full
                    if (!checkpoint.full) { started.complete(Unit); awaitCancellation() }
                    finish.await()
                    return LocalHealthPage(emptyList(), 1, null, true, batchId = "batch")
                }
                override fun close() = Unit
            }
        }, tables = listOf(LocalHealthTable.POINT))
        engine.attach(link); started.await()
        engine.sync(LocalHealthSyncMode.FULL); runCurrent(); engine.sync(LocalHealthSyncMode.FULL)
        finish.complete(Unit)
        engine.state.first { it.phase == LocalHealthSyncPhase.COMPLETED }
        assertEquals(listOf(false, true), modes); assertEquals(LocalHealthSyncMode.FULL, engine.state.value.mode)
        baseline = false
        engine.sync(); engine.state.first { it.phase == LocalHealthSyncPhase.COMPLETED }
        assertEquals(true, modes.last()) // Empty/rebuilt phone storage must request a baseline.
        engine.close()
    }
    @Test fun `ack timeout is bounded and already committed batches are counted once`() = runTest {
        val store = Store(); var attempts = 0
        val engine = LocalHealthSyncEngine(backgroundScope, store, LocalHealthRemote {
            object : LocalHealthConnection {
                override suspend fun page(table: LocalHealthTable, checkpoint: LocalHealthCheckpoint) =
                    LocalHealthPage(listOf(row(1)), 1, null, true, batchId = "persisted-batch")
                override suspend fun acknowledge(table: LocalHealthTable, page: LocalHealthPage) { attempts++; awaitCancellation() }
                override fun close() = Unit
            }
        }, retryDelayMillis = 1, tables = listOf(LocalHealthTable.POINT))
        engine.attach(link)
        val result = engine.state.first { it.phase == LocalHealthSyncPhase.UNAVAILABLE }
        assertEquals(3, attempts); assertEquals(1, result.recordsCommitted); assertEquals(1, result.pagesCommitted)
        assertEquals(setOf<Long?>(1L), store.rows.keys); assertEquals("bridge_timeout", result.reason)
        engine.close()
    }
    @Test fun `interrupted scan resumes durably then a completed scan refreshes old ids`() = runTest {
        val store = Store(); val seen = mutableListOf<Long?>(); var connections = 0; val logs = mutableListOf<ProtocolDiagnostic>()
        val first = LocalHealthSyncEngine(backgroundScope, store, LocalHealthRemote {
            connections++
            object : LocalHealthConnection {
                override suspend fun page(table: LocalHealthTable, checkpoint: LocalHealthCheckpoint): LocalHealthPage {
                    seen += checkpoint.afterId
                    if (checkpoint.afterId == null) return LocalHealthPage(listOf(row(1)), 1, 2, false)
                    throw IOException("fixture-secret")
                }
                override fun close() = Unit
            }
        }, DiagnosticSink(logs::add), retryDelayMillis = 1, tables = listOf(LocalHealthTable.POINT))
        first.attach(link)
        first.state.first { it.phase == LocalHealthSyncPhase.UNAVAILABLE }
        assertEquals(1L, store.checkpoint.afterId); assertEquals(2L, store.checkpoint.upperId)
        assertEquals(3, connections); first.close()
        val second = LocalHealthSyncEngine(backgroundScope, store, LocalHealthRemote {
            object : LocalHealthConnection {
                override suspend fun page(table: LocalHealthTable, checkpoint: LocalHealthCheckpoint): LocalHealthPage {
                    seen += checkpoint.afterId
                    return LocalHealthPage(listOf(row(2)), 2, 2, true)
                }
                override fun close() = Unit
            }
        }, tables = listOf(LocalHealthTable.POINT))
        second.attach(link.copy(sessionId = 43)); second.state.first { it.phase == LocalHealthSyncPhase.COMPLETED }
        assertEquals(1L, seen.last()); assertEquals(setOf<Long?>(1L, 2L), store.rows.keys)
        second.sync(); runCurrent()
        assertEquals(LocalHealthSyncPhase.COMPLETED, second.state.value.phase)
        assertNull(seen.last()) // A permanent high-water cursor would miss updates to row 1.
        assertFalse(logs.flatMap { it.details.values }.any { "secret" in it })
        second.close()
    }
    @Test fun `automatic syncs skip a recent success but an explicit sync and a stale one run`() = runTest {
        val now = 10 * 60_000L
        val store = Store().apply { lastSuccess = now - 60_000 }
        var connections = 0
        val engine = LocalHealthSyncEngine(backgroundScope, store, LocalHealthRemote {
            connections++
            object : LocalHealthConnection {
                override suspend fun page(table: LocalHealthTable, checkpoint: LocalHealthCheckpoint): LocalHealthPage {
                    delay(50)
                    return LocalHealthPage(listOf(row(1)), 1, 1, complete = true, watchMillis = 20, queuedMillis = 12, wireBytes = 1_000)
                }
                override val setupStages = listOf(LocalHealthSyncStage(null, "connect", 7, count = 2))
                override fun close() = Unit
            }
        }, now = { now }, tables = listOf(LocalHealthTable.POINT), elapsedMillis = { testScheduler.currentTime })

        engine.attach(link); engine.syncIfStale(); runCurrent()
        assertEquals(0, connections)
        assertEquals(LocalHealthSyncPhase.IDLE, engine.state.value.phase)
        assertEquals(now - 60_000, engine.state.value.lastSuccessAtMillis)

        engine.sync(); val synced = engine.state.first { it.phase == LocalHealthSyncPhase.COMPLETED }
        assertEquals(1, connections)
        val stages = synced.lastRun!!.stages.associateBy { it.table to it.name }
        assertEquals(LocalHealthSyncStage("data_point", "connect", 7, count = 2), stages["data_point" to "connect"])
        assertEquals(LocalHealthSyncStage("data_point", "read", 50, bytes = 1_000), stages["data_point" to "read"])
        // The watch's own 20 ms leave 30 for Bluetooth and parsing.
        assertEquals(LocalHealthSyncStage("data_point", "watch", 20), stages["data_point" to "watch"])
        assertEquals(LocalHealthSyncStage("data_point", "queued", 12), stages["data_point" to "queued"])
        assertEquals(LocalHealthSyncStage("data_point", "transfer", 18), stages["data_point" to "transfer"])
        assertEquals(50, synced.lastRun!!.totalMillis)

        store.lastSuccess = now - 6 * 60_000
        engine.attach(null); engine.attach(link.copy(sessionId = 43))
        engine.state.first { it.phase == LocalHealthSyncPhase.COMPLETED }
        assertEquals(2, connections)
        engine.close()
    }
    @Test fun `a connection that serves every table is opened once and replaced after a failure`() = runTest {
        var connections = 0; var closed = 0; var dropSession = true
        val engine = LocalHealthSyncEngine(backgroundScope, Store(), LocalHealthRemote {
            connections++
            object : LocalHealthConnection {
                override val servesAllTables = true
                override suspend fun page(table: LocalHealthTable, checkpoint: LocalHealthCheckpoint): LocalHealthPage {
                    if (table == LocalHealthTable.SESSION && dropSession) { dropSession = false; throw IOException("dropped") }
                    return LocalHealthPage(emptyList(), null, null, complete = true)
                }
                override fun close() { closed++ }
            }
        }, retryDelayMillis = 1)
        engine.attach(link); engine.state.first { it.phase == LocalHealthSyncPhase.COMPLETED }
        assertEquals(2, connections); assertEquals(2, closed)
        engine.close()
    }
    @Test fun `connection replacement cancels blocked reads closes socket and keeps current state`() = runTest {
        var closed = false; val reading = CompletableDeferred<Unit>()
        val engine = LocalHealthSyncEngine(backgroundScope, Store(), LocalHealthRemote {
            object : LocalHealthConnection {
                override suspend fun page(table: LocalHealthTable, checkpoint: LocalHealthCheckpoint): LocalHealthPage { reading.complete(Unit); awaitCancellation() }
                override fun close() { closed = true }
            }
        }, tables = listOf(LocalHealthTable.POINT))
        engine.attach(link); reading.await(); engine.sync(); engine.attach(null); runCurrent()
        assertTrue(closed); assertEquals(LocalHealthSyncPhase.DISCONNECTED, engine.state.value.phase)
        engine.close()
        engine.cancel(); assertEquals(LocalHealthSyncPhase.CLOSED, engine.state.value.phase)
    }
    @Test fun `partial cells remain readable and do not become a full export claim`() = runTest {
        val store = Store()
        val engine = LocalHealthSyncEngine(backgroundScope, store, LocalHealthRemote {
            object : LocalHealthConnection {
                override suspend fun page(table: LocalHealthTable, checkpoint: LocalHealthCheckpoint) = LocalHealthPage(listOf(row(5)), 5, 5, true, partial = true)
                override fun close() = Unit
            }
        }, tables = listOf(LocalHealthTable.POINT))
        engine.attach(link); engine.state.first { it.phase == LocalHealthSyncPhase.PARTIAL }
        assertEquals(1, store.rows.size); assertTrue(store.checkpoint.partial)
        engine.close()
    }
    @Test fun `framed connection handles arbitrary chunk boundaries and preserves raw unknown int64 fields`() = runTest {
        val value = LocalHealthProtocol.parse("""{"rows":[{"id":"9223372036854775807","columns":{"_id":9223372036854775807,"values":"75,,future","extra":{"v":null}}}],"complete":1,"future":"opaque"}""".toByteArray())
        val combined = LocalHealthProtocol.frame(value) + LocalHealthProtocol.frame(value)
        val parts = combined.toList().chunked(3).map { it.toByteArray() }.iterator()
        val frames = LocalHealthFrames(object : DuplexTransport {
            override suspend fun read() = if (parts.hasNext()) parts.next() else null
            override suspend fun write(bytes: ByteArray) = Unit
            override fun close() = Unit
        })
        repeat(2) {
            val page = LocalHealthProtocol.page(frames.read())
            assertEquals(Long.MAX_VALUE, page.rows.single().id); assertTrue(page.complete)
            assertEquals("75,,future", page.rows.single().text("values"))
            assertEquals(JsonNull, (page.rows.single().columns["extra"] as JsonObject)["v"])
        }
        assertFailsWith<java.io.EOFException> { frames.read() }
    }
    @Test fun `an unfamiliar row without a usable cursor is preserved and stops as partial instead of looping`() = runTest {
        val store = Store(); var requests = 0
        val raw = LocalHealthRow(buildJsonObject { put("future", "opaque-not-zero") })
        val engine = LocalHealthSyncEngine(backgroundScope, store, LocalHealthRemote {
            object : LocalHealthConnection {
                override suspend fun page(table: LocalHealthTable, checkpoint: LocalHealthCheckpoint): LocalHealthPage {
                    requests++; return LocalHealthPage(listOf(raw), null, null, complete = false)
                }
                override fun close() = Unit
            }
        }, tables = listOf(LocalHealthTable.POINT))
        engine.attach(link); val result = engine.state.first { it.phase == LocalHealthSyncPhase.PARTIAL }
        assertEquals(1, requests); assertSame(raw, store.rows[null]); assertTrue(store.checkpoint.partial)
        assertFalse(store.checkpoint.inProgress); assertEquals("page_did_not_advance", result.reason)
        engine.close()
    }
    @Test fun `unavailable sessions do not discard points or report both tables as completed`() = runTest {
        val store = Store(); val requested = mutableListOf<LocalHealthTable>()
        val logs = mutableListOf<ProtocolDiagnostic>()
        val engine = LocalHealthSyncEngine(backgroundScope, store, LocalHealthRemote {
            object : LocalHealthConnection {
                override val servesAllTables = true
                override val supportedTables = setOf(LocalHealthTable.POINT, LocalHealthTable.SESSION, LocalHealthTable.SPORT)
                override suspend fun page(table: LocalHealthTable, checkpoint: LocalHealthCheckpoint): LocalHealthPage {
                    requested += table
                    return if (table == LocalHealthTable.POINT) LocalHealthPage(listOf(row(1)), 1, 1, true)
                        else LocalHealthPage(emptyList(), null, null, true, error = "provider_denied")
                }
                override fun close() = Unit
            }
        }, DiagnosticSink(logs::add))
        engine.attach(link)
        val result = engine.state.first { it.phase == LocalHealthSyncPhase.PARTIAL }
        assertEquals(listOf(LocalHealthTable.SESSION, LocalHealthTable.POINT, LocalHealthTable.SPORT), requested)
        assertEquals(1, result.recordsCommitted); assertEquals(1, result.pagesCommitted)
        assertEquals("data_session:provider_denied,fitness_summary:provider_denied", result.reason)
        assertEquals(setOf<Long?>(1L), store.rows.keys)
        assertTrue(logs.any { it.details["table"] == "data_session" })
        engine.close()
    }

    @Test fun `a long active private scan keeps one connection beyond forty seconds`() = runTest {
        val requested = mutableListOf<LocalHealthTable>(); var opened = 0
        val engine = LocalHealthSyncEngine(backgroundScope, Store(), LocalHealthRemote {
            opened++
            object : LocalHealthConnection {
                override val servesAllTables = true
                override val supportedTables = setOf(LocalHealthTable.FITNESS_RECORD, LocalHealthTable.FITNESS_POINT)
                override suspend fun page(table: LocalHealthTable, checkpoint: LocalHealthCheckpoint): LocalHealthPage {
                    requested += table
                    delay(20_000)
                    val id = (checkpoint.afterId ?: 0) + 1
                    return LocalHealthPage(listOf(row(id)), id, 3, id == 3L)
                }
                override fun close() = Unit
            }
        })
        engine.attach(link)
        val result = engine.state.first { it.phase == LocalHealthSyncPhase.COMPLETED }
        assertEquals(1, opened)
        assertEquals(6, result.recordsCommitted)
        assertEquals(120_000, testScheduler.currentTime)
        assertEquals(setOf(LocalHealthTable.FITNESS_RECORD, LocalHealthTable.FITNESS_POINT), requested.toSet())
        assertEquals(setOf("fitness_record", "fitness_point"), result.advertisedTables)
        engine.close()
    }
}
