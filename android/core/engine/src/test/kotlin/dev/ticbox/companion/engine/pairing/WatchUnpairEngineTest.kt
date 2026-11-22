@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.ticbox.companion.engine.pairing

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.features.FeatureTestData
import dev.ticbox.companion.protocol.DataMapCodec
import dev.ticbox.companion.protocol.DataMapValue
import java.io.IOException
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class WatchUnpairEngineTest {
    private val identityTimeout = 100L
    private val ackTimeout = 200L

    private fun home(data: FeatureTestData,
        code: DataMapValue? = DataMapValue.IntNumber(790_427_547),
        name: String? = "1.15.81.626317553", scope: DataScope = data.scope,
        route: ApplicationRoute = data.route, sourceNodeId: String = scope.remoteNodeId,
        uri: String = "wear://${scope.remoteNodeId}/settings/home_info", local: Boolean = false,
        deleted: Boolean = false, body: ByteArray? = null, sdk: Int? = null): DataItemRecord {
        val fields = buildMap {
            code?.let { put("HOME_VERSION_CODE", it) }
            name?.let { put("HOME_VERSION_NAME", DataMapValue.Text(it)) }
            sdk?.let { put("WEAR_ANDROID_SDK_VERSION", DataMapValue.IntNumber(it)) }
        }
        return DataItemRecord(scope, route, uri, sourceNodeId, 1, deleted,
            body ?: DataMapCodec.encode(fields), receivedAtEpochMillis = 1, local = local)
    }

    @Test fun `only a reported API 30 rules out a phone transfer`() = runTest {
        val data = FeatureTestData(); var sdk: Int? = 30
        lateinit var engine: WatchUnpairEngine
        engine = WatchUnpairEngine(backgroundScope, requestSnapshot = { session ->
            assertTrue(engine.offerRemoteItem(session.sessionId, home(data, code = DataMapValue.IntNumber(1), sdk = sdk)))
        }, cleanup = {}, identityTimeoutMillis = identityTimeout, ackTimeoutMillis = ackTimeout, remoteCleanupGraceMillis = 0)
        try {
            engine.attach(data.session())
            assertTrue(engine.probe().phoneTransferUnsupported)
            assertTrue(engine.state.value.phoneTransferUnsupported)
            sdk = 33
            assertEquals(33, engine.probe().watchSdk)
            assertFalse(engine.state.value.phoneTransferUnsupported)
            sdk = null
            assertNull(engine.probe().watchSdk)
            assertFalse(engine.state.value.phoneTransferUnsupported)
        } finally { engine.close() }
    }

    @Test fun `exact receiver sends both explicit flags and remembers an ACK before write completes`() = runTest {
        val data = FeatureTestData(); val cleaned = mutableListOf<PeerAddress>()
        val writeReturn = CompletableDeferred<Unit>(); var snapshots = 0
        lateinit var engine: WatchUnpairEngine
        engine = WatchUnpairEngine(backgroundScope, requestSnapshot = { session ->
            snapshots++; assertTrue(engine.offerRemoteItem(session.sessionId, home(data)))
        }, cleanup = { cleaned += it }, identityTimeoutMillis = identityTimeout, ackTimeoutMillis = ackTimeout, remoteCleanupGraceMillis = 0)
        try {
            engine.attach(data.session())
            data.onSend = { route, path, bytes ->
                assertEquals(data.route, route)
                assertEquals(WatchUnpairEngine.REQUEST_PATH, path)
                assertContentEquals(byteArrayOf(0x08, 0x00, 0x10, 0x01), bytes)
                assertTrue(engine.offerMessage(data.scope.peer, 1, WatchUnpairEngine.ACK_PATH, byteArrayOf(), route))
                writeReturn.await()
            }
            val operation = async { engine.unpairWithoutReset() }
            runCurrent()
            assertEquals(WatchUnpairPhase.SENDING, engine.state.value.phase)
            assertTrue(engine.state.value.busy)
            assertTrue(cleaned.isEmpty())
            writeReturn.complete(Unit); runCurrent()
            val result = operation.await()
            assertEquals(1, snapshots)
            assertEquals(WatchUnpairOutcome.ACK_RECEIVED, result.outcome)
            assertEquals("ack_received_receipt_only", result.reason)
            assertEquals(listOf(data.scope.peer), cleaned)
            assertTrue(result.localCleanupAttempted)
            assertNull(result.localCleanupFailure)
            assertEquals(result, engine.state.value.lastResult)
            assertEquals(WatchUnpairSupport.UNKNOWN, engine.state.value.support)
            assertNull(engine.state.value.homeVersionCode)
            assertFalse(engine.state.value.busy)
        } finally { engine.close() }
    }

    @Test fun `unverified or missing receiver identity never writes or performs local cleanup`() = runTest {
        val identities = listOf<Pair<DataMapValue?, String?>>(
            DataMapValue.IntNumber(790_427_546) to "1.15.81.626317553",
            DataMapValue.IntNumber(790_427_547) to "1.15.81.other",
            DataMapValue.IntNumber(790_427_547) to null,
            null to "1.15.81.626317553",
            DataMapValue.Text("790427547") to "1.15.81.626317553",
        )
        for ((code, name) in identities) {
            val data = FeatureTestData(); var cleanups = 0
            lateinit var engine: WatchUnpairEngine
            engine = WatchUnpairEngine(backgroundScope,
                requestSnapshot = { session -> engine.offerRemoteItem(session.sessionId, home(data, code, name)) },
                cleanup = { cleanups++ }, identityTimeoutMillis = identityTimeout, ackTimeoutMillis = ackTimeout, remoteCleanupGraceMillis = 0)
            try {
                engine.attach(data.session())
                val result = engine.unpairWithoutReset()
                assertEquals(WatchUnpairOutcome.NOT_SENT, result.outcome)
                assertEquals(WatchUnpairSupport.UNSUPPORTED, engine.state.value.support)
                assertFalse(result.localCleanupAttempted)
                assertEquals(0, cleanups)
                assertTrue(data.sent.isEmpty())
            } finally { engine.close() }
        }
    }

    @Test fun `ACK leaves the transport alive for watch execution before local cleanup`() = runTest {
        val data = FeatureTestData(); var cleanedAt: Long? = null
        lateinit var engine: WatchUnpairEngine
        engine = WatchUnpairEngine(backgroundScope,
            requestSnapshot = { session -> engine.offerRemoteItem(session.sessionId, home(data)) },
            cleanup = { cleanedAt = testScheduler.currentTime }, identityTimeoutMillis = identityTimeout,
            ackTimeoutMillis = ackTimeout)
        try {
            engine.attach(data.session())
            data.onSend = { route, _, _ -> engine.offerMessage(data.scope.peer, 1,
                WatchUnpairEngine.ACK_PATH, byteArrayOf(), route) }
            val operation = async { engine.unpairWithoutReset() }; runCurrent()
            assertEquals(WatchUnpairPhase.WAITING_FOR_WATCH, engine.state.value.phase)
            assertTrue(engine.state.value.busy)
            assertNull(cleanedAt)
            advanceTimeBy(4_999); runCurrent()
            assertNull(cleanedAt)
            advanceTimeBy(1); runCurrent()
            assertEquals(5_000L, cleanedAt)
            assertEquals(WatchUnpairOutcome.ACK_RECEIVED, operation.await().outcome)
            // A locally elapsed grace period still cannot confirm remote cleanup or discovery.
            assertEquals("ack_received_receipt_only", engine.state.value.lastResult?.reason)
        } finally { engine.close() }
    }

    @Test fun `caller cancellation preserves ACK execution window and watch disconnect releases it`() = runTest {
        val data = FeatureTestData(); var cleanups = 0
        lateinit var engine: WatchUnpairEngine
        engine = WatchUnpairEngine(backgroundScope,
            requestSnapshot = { session -> engine.offerRemoteItem(session.sessionId, home(data)) },
            cleanup = { cleanups++ }, identityTimeoutMillis = identityTimeout, ackTimeoutMillis = ackTimeout)
        try {
            engine.attach(data.session())
            data.onSend = { route, _, _ -> engine.offerMessage(data.scope.peer, 1,
                WatchUnpairEngine.ACK_PATH, byteArrayOf(), route) }
            val operation = async { engine.unpairWithoutReset() }; runCurrent()
            operation.cancel(); runCurrent()
            assertFalse(operation.isCompleted)
            assertEquals(0, cleanups)
            advanceTimeBy(100); runCurrent()
            engine.detach(1); runCurrent(); operation.join()
            assertTrue(operation.isCancelled)
            assertEquals(1, cleanups)
            assertEquals(100L, testScheduler.currentTime)
            assertEquals(WatchUnpairOutcome.ACK_RECEIVED, engine.state.value.lastResult?.outcome)
            assertFalse(engine.state.value.busy)
        } finally { engine.close() }
    }

    @Test fun `wrong peer route session source URI and local items cannot authorize an unpair`() = runTest {
        val data = FeatureTestData(); var cleanups = 0
        lateinit var engine: WatchUnpairEngine
        engine = WatchUnpairEngine(backgroundScope, requestSnapshot = { session ->
            assertFalse(engine.offerRemoteItem(0, home(data)))
            assertFalse(engine.offerRemoteItem(session.sessionId, home(data,
                scope = data.scope.copy(peer = PeerAddress("AA:BB:CC:DD:EE:02")))))
            assertFalse(engine.offerRemoteItem(session.sessionId, home(data, route = ApplicationRoute("other", "digest"))))
            assertFalse(engine.offerRemoteItem(session.sessionId, home(data, scope = data.scope.copy(remoteNodeId = "other"))))
            assertFalse(engine.offerRemoteItem(session.sessionId, home(data, sourceNodeId = "other")))
            assertFalse(engine.offerRemoteItem(session.sessionId, home(data, uri = "wear://other/settings/home_info")))
            assertFalse(engine.offerRemoteItem(session.sessionId, home(data, local = true)))
        }, cleanup = { cleanups++ }, identityTimeoutMillis = identityTimeout, ackTimeoutMillis = ackTimeout, remoteCleanupGraceMillis = 0)
        try {
            engine.attach(data.session())
            val operation = async { engine.unpairWithoutReset() }; runCurrent()
            advanceTimeBy(identityTimeout); runCurrent()
            assertEquals(WatchUnpairOutcome.NOT_SENT, operation.await().outcome)
            assertEquals("fresh_home_info_timeout", engine.state.value.reason)
            assertEquals(0, cleanups)
            assertTrue(data.sent.isEmpty())
        } finally { engine.close() }
    }

    @Test fun `a cached identity or prior fresh observation does not authorize the next probe`() = runTest {
        val data = FeatureTestData(); var cleanups = 0
        val engine = WatchUnpairEngine(backgroundScope, requestSnapshot = {}, cleanup = { cleanups++ },
            identityTimeoutMillis = identityTimeout, ackTimeoutMillis = ackTimeout, remoteCleanupGraceMillis = 0)
        try {
            engine.attach(data.session())
            val cached = home(data)
            data.records[cached.route to cached.uri] = cached
            assertTrue(engine.offerRemoteItem(1, cached))
            assertEquals(WatchUnpairSupport.SUPPORTED, engine.state.value.support)
            val operation = async { engine.unpairWithoutReset() }; runCurrent()
            assertEquals(WatchUnpairSupport.UNKNOWN, engine.state.value.support)
            advanceTimeBy(identityTimeout); runCurrent()
            assertEquals(WatchUnpairOutcome.NOT_SENT, operation.await().outcome)
            assertEquals(0, cleanups)
            assertTrue(data.sent.isEmpty())
        } finally { engine.close() }
    }

    @Test fun `same sequence and body on fresh wire can satisfy successive identity probes`() = runTest {
        val data = FeatureTestData(); var snapshots = 0
        val repeated = home(data, DataMapValue.LongNumber(790_427_547L))
        lateinit var engine: WatchUnpairEngine
        engine = WatchUnpairEngine(backgroundScope, requestSnapshot = { session ->
            snapshots++; engine.offerRemoteItem(session.sessionId, repeated)
        }, cleanup = {}, identityTimeoutMillis = identityTimeout, ackTimeoutMillis = ackTimeout, remoteCleanupGraceMillis = 0)
        try {
            engine.attach(data.session())
            assertEquals(WatchUnpairSupport.SUPPORTED, engine.probe().support)
            data.onSend = { route, _, _ -> engine.offerMessage(data.scope.peer, 1, WatchUnpairEngine.ACK_PATH, byteArrayOf(), route) }
            assertEquals(WatchUnpairOutcome.ACK_RECEIVED, engine.unpairWithoutReset().outcome)
            assertEquals(2, snapshots)
        } finally { engine.close() }
    }

    @Test fun `a probe returns its interrupted session observation even when reconnection has already replaced state`() = runTest {
        val data = FeatureTestData()
        val engine = WatchUnpairEngine(backgroundScope, requestSnapshot = {}, cleanup = {},
            identityTimeoutMillis = identityTimeout, ackTimeoutMillis = ackTimeout)
        try {
            engine.attach(data.session())
            val pending = async { engine.probe() }; runCurrent()
            engine.detach(1); engine.attach(data.session(2)); runCurrent()
            val observed = pending.await()
            assertEquals(1L, observed.sessionId)
            assertEquals(WatchUnpairSupport.UNAVAILABLE, observed.support)
            assertEquals("watch_link_ended", observed.reason)
            assertFalse(observed.busy)
            assertEquals(2L, engine.state.value.sessionId)
            assertEquals(WatchUnpairSupport.UNKNOWN, engine.state.value.support)
            assertEquals("fresh_home_info_required", engine.state.value.reason)
            assertFalse(engine.state.value.busy)
        } finally { engine.close() }
    }

    @Test fun `revoked fresh identity before writing blocks the command`() = runTest {
        val data = FeatureTestData(); var cleanups = 0
        lateinit var engine: WatchUnpairEngine
        engine = WatchUnpairEngine(backgroundScope, requestSnapshot = { session ->
            engine.offerRemoteItem(session.sessionId, home(data))
            engine.offerRemoteItem(session.sessionId, home(data, deleted = true))
        }, cleanup = { cleanups++ }, identityTimeoutMillis = identityTimeout, ackTimeoutMillis = ackTimeout, remoteCleanupGraceMillis = 0)
        try {
            engine.attach(data.session())
            assertEquals(WatchUnpairOutcome.NOT_SENT, engine.unpairWithoutReset().outcome)
            assertEquals(WatchUnpairSupport.UNKNOWN, engine.state.value.support)
            assertEquals(0, cleanups)
            assertTrue(data.sent.isEmpty())
        } finally { engine.close() }
    }

    @Test fun `wrong ACKs time out and still perform local cleanup after writing`() = runTest {
        val data = FeatureTestData(); val cleaned = mutableListOf<PeerAddress>()
        lateinit var engine: WatchUnpairEngine
        engine = WatchUnpairEngine(backgroundScope,
            requestSnapshot = { session -> engine.offerRemoteItem(session.sessionId, home(data)) },
            cleanup = { cleaned += it }, identityTimeoutMillis = identityTimeout, ackTimeoutMillis = ackTimeout, remoteCleanupGraceMillis = 0)
        try {
            engine.attach(data.session())
            val operation = async { engine.unpairWithoutReset() }; runCurrent()
            assertEquals(WatchUnpairPhase.WAITING_FOR_ACK, engine.state.value.phase)
            assertFalse(engine.offerMessage(PeerAddress("AA:BB:CC:DD:EE:02"), 1, WatchUnpairEngine.ACK_PATH, byteArrayOf(), data.route))
            assertFalse(engine.offerMessage(data.scope.peer, 0, WatchUnpairEngine.ACK_PATH, byteArrayOf(), data.route))
            assertFalse(engine.offerMessage(data.scope.peer, 1, WatchUnpairEngine.ACK_PATH, byteArrayOf(), ApplicationRoute("other", "digest")))
            assertFalse(engine.offerMessage(data.scope.peer, 1, WatchUnpairEngine.ACK_PATH, byteArrayOf(), null))
            assertFalse(engine.offerMessage(data.scope.peer, 1, "other_path", byteArrayOf(), data.route))
            advanceTimeBy(ackTimeout); runCurrent()
            val result = operation.await()
            assertEquals(WatchUnpairOutcome.UNCONFIRMED, result.outcome)
            assertEquals("unpair_ack_timeout", result.reason)
            assertEquals(listOf(data.scope.peer), cleaned)
        } finally { engine.close() }
    }

    @Test fun `caller cancellation after writing waits for noncancellable local cleanup`() = runTest {
        val data = FeatureTestData(); val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val cleaned = mutableListOf<PeerAddress>()
        lateinit var engine: WatchUnpairEngine
        engine = WatchUnpairEngine(backgroundScope,
            requestSnapshot = { session -> engine.offerRemoteItem(session.sessionId, home(data)) },
            cleanup = { peer -> started.complete(Unit); finish.await(); cleaned += peer },
            identityTimeoutMillis = identityTimeout, ackTimeoutMillis = ackTimeout, remoteCleanupGraceMillis = 0)
        try {
            engine.attach(data.session())
            val operation = async { engine.unpairWithoutReset() }; runCurrent()
            assertEquals(1, data.sent.size)
            operation.cancel(); runCurrent()
            assertTrue(started.isCompleted)
            assertFalse(operation.isCompleted)
            assertEquals(WatchUnpairPhase.CLEANING_UP, engine.state.value.phase)
            finish.complete(Unit); runCurrent(); operation.join()
            assertTrue(operation.isCancelled)
            assertEquals(listOf(data.scope.peer), cleaned)
            assertEquals(WatchUnpairOutcome.UNCONFIRMED, engine.state.value.lastResult?.outcome)
            assertEquals("unpair_cancelled", engine.state.value.lastResult?.reason)
        } finally { finish.complete(Unit); engine.close() }
    }

    @Test fun `link loss after writing cleans only the old peer and cannot overwrite the new identity`() = runTest {
        val data = FeatureTestData(); val cleaned = mutableListOf<PeerAddress>()
        lateinit var engine: WatchUnpairEngine
        engine = WatchUnpairEngine(backgroundScope,
            requestSnapshot = { session -> engine.offerRemoteItem(session.sessionId, home(data)) },
            cleanup = { cleaned += it }, identityTimeoutMillis = identityTimeout, ackTimeoutMillis = ackTimeout, remoteCleanupGraceMillis = 0)
        try {
            engine.attach(data.session())
            val operation = async { engine.unpairWithoutReset() }; runCurrent()
            engine.detach(1)
            assertTrue(engine.state.value.busy)
            engine.attach(data.session(2))
            assertTrue(engine.state.value.busy)
            runCurrent()
            val result = operation.await()
            assertEquals(WatchUnpairOutcome.UNCONFIRMED, result.outcome)
            assertEquals(listOf(data.scope.peer), cleaned)
            assertEquals(1L, result.sessionId)
            assertEquals(2L, engine.state.value.sessionId)
            assertEquals(WatchUnpairSupport.UNKNOWN, engine.state.value.support)
            assertNull(engine.state.value.homeVersionCode)
            assertEquals(WatchUnpairPhase.IDLE, engine.state.value.phase)
            assertFalse(engine.state.value.busy)
            assertFalse(engine.offerRemoteItem(1, home(data)))
            assertFalse(engine.offerMessage(data.scope.peer, 1, WatchUnpairEngine.ACK_PATH, byteArrayOf(), data.route))
        } finally { engine.close() }
    }

    @Test fun `link loss or cancellation before any write cancels the probe without local cleanup`() = runTest {
        for (cancel in listOf(false, true)) {
            val data = FeatureTestData(); var cleanups = 0; var refreshCancelled = false
            val engine = WatchUnpairEngine(backgroundScope,
                requestSnapshot = { try { awaitCancellation() } finally { refreshCancelled = true } },
                cleanup = { cleanups++ }, identityTimeoutMillis = identityTimeout, ackTimeoutMillis = ackTimeout, remoteCleanupGraceMillis = 0)
            try {
                engine.attach(data.session())
                val operation = async { engine.unpairWithoutReset() }; runCurrent()
                if (cancel) operation.cancel() else engine.detach(1)
                runCurrent()
                if (cancel) { operation.join(); assertTrue(operation.isCancelled) }
                else assertEquals(WatchUnpairOutcome.NOT_SENT, operation.await().outcome)
                assertTrue(refreshCancelled)
                assertEquals(0, cleanups)
                assertTrue(data.sent.isEmpty())
            } finally { engine.close() }
        }
    }

    @Test fun `refresh failure is not sent while a write failure is unconfirmed and cleans locally`() = runTest {
        for (failRefresh in listOf(true, false)) {
            val data = FeatureTestData(); var cleanups = 0
            lateinit var engine: WatchUnpairEngine
            engine = WatchUnpairEngine(backgroundScope, requestSnapshot = { session ->
                if (failRefresh) throw IOException("synthetic refresh")
                engine.offerRemoteItem(session.sessionId, home(data))
            }, cleanup = { cleanups++ }, identityTimeoutMillis = identityTimeout, ackTimeoutMillis = ackTimeout, remoteCleanupGraceMillis = 0)
            try {
                engine.attach(data.session())
                data.onSend = { _, _, _ -> throw IOException("synthetic write") }
                val result = engine.unpairWithoutReset()
                assertEquals(if (failRefresh) WatchUnpairOutcome.NOT_SENT else WatchUnpairOutcome.UNCONFIRMED, result.outcome)
                assertEquals(if (failRefresh) 0 else 1, cleanups)
                assertEquals(if (failRefresh) 0 else 1, data.sent.size)
            } finally { engine.close() }
        }
    }

    @Test fun `cleanup failure is separate from receipt and never loses the target outcome`() = runTest {
        val data = FeatureTestData()
        lateinit var engine: WatchUnpairEngine
        engine = WatchUnpairEngine(backgroundScope,
            requestSnapshot = { session -> engine.offerRemoteItem(session.sessionId, home(data)) },
            cleanup = { throw IOException("synthetic cleanup") }, identityTimeoutMillis = identityTimeout, ackTimeoutMillis = ackTimeout, remoteCleanupGraceMillis = 0)
        try {
            engine.attach(data.session())
            data.onSend = { route, _, _ -> engine.offerMessage(data.scope.peer, 1, WatchUnpairEngine.ACK_PATH, byteArrayOf(), route) }
            val result = engine.unpairWithoutReset()
            assertEquals(WatchUnpairOutcome.ACK_RECEIVED, result.outcome)
            assertEquals("IOException", result.localCleanupFailure)
            assertEquals(result, engine.state.value.lastResult)
            assertFalse(engine.state.value.busy)
        } finally { engine.close() }
    }

    @Test fun `local disconnect during cleanup stays busy and queued commands cannot reuse the old receiver`() = runTest {
        val data = FeatureTestData(); var snapshots = 0
        val cleanupStarted = CompletableDeferred<Unit>(); val finishCleanup = CompletableDeferred<Unit>()
        lateinit var engine: WatchUnpairEngine
        engine = WatchUnpairEngine(backgroundScope, requestSnapshot = { session ->
            snapshots++; engine.offerRemoteItem(session.sessionId, home(data))
        }, cleanup = { engine.detach(1); cleanupStarted.complete(Unit); finishCleanup.await() },
            identityTimeoutMillis = identityTimeout, ackTimeoutMillis = ackTimeout, remoteCleanupGraceMillis = 0)
        try {
            engine.attach(data.session())
            val first = async { engine.unpairWithoutReset() }; runCurrent()
            val second = async { engine.unpairWithoutReset() }; runCurrent()
            assertEquals(1, snapshots)
            assertTrue(engine.offerMessage(data.scope.peer, 1, WatchUnpairEngine.ACK_PATH, byteArrayOf(), data.route))
            runCurrent()
            assertTrue(cleanupStarted.isCompleted)
            assertEquals(WatchUnpairPhase.DISCONNECTED, engine.state.value.phase)
            assertTrue(engine.state.value.busy)
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            finishCleanup.complete(Unit); runCurrent()
            assertEquals(WatchUnpairOutcome.ACK_RECEIVED, first.await().outcome)
            assertEquals(WatchUnpairOutcome.NOT_SENT, second.await().outcome)
            assertEquals(1, snapshots)
            assertEquals(1, data.sent.size)
            assertFalse(engine.state.value.busy)
        } finally { finishCleanup.complete(Unit); engine.close() }
    }

    @Test fun `clearing a watch this phone can't reach sends nothing and cleans up`() = runTest {
        val data = FeatureTestData(); val cleaned = mutableListOf<PeerAddress>()
        val engine = WatchUnpairEngine(backgroundScope, requestSnapshot = { error("Nothing is needed from the watch") },
            cleanup = { cleaned += it }, identityTimeoutMillis = identityTimeout, ackTimeoutMillis = ackTimeout)
        try {
            val result = engine.clearLocalPairing(data.scope.peer)
            assertEquals(WatchUnpairOutcome.LOCAL_ONLY, result.outcome)
            assertNull(result.localCleanupFailure)
            assertEquals(listOf(data.scope.peer), cleaned)
            assertTrue(data.sent.isEmpty())
            assertEquals(result, engine.state.value.lastResult)
            assertFalse(engine.state.value.busy)
        } finally { engine.close() }
    }

    @Test fun `clearing is refused while the watch is connected`() = runTest {
        val data = FeatureTestData(); var cleanups = 0
        val engine = WatchUnpairEngine(backgroundScope, requestSnapshot = { error("Nothing is needed from the watch") },
            cleanup = { cleanups++ }, identityTimeoutMillis = identityTimeout, ackTimeoutMillis = ackTimeout)
        try {
            engine.attach(data.session())
            val result = engine.clearLocalPairing(data.scope.peer)
            assertEquals(WatchUnpairOutcome.NOT_SENT, result.outcome)
            assertEquals("watch_still_connected", result.reason)
            assertEquals(0, cleanups)
            assertTrue(data.sent.isEmpty())
        } finally { engine.close() }
    }

    @Test fun `a failed clear is reported and a caller cancelling can't cut it short`() = runTest {
        val data = FeatureTestData(); val finish = CompletableDeferred<Unit>()
        val engine = WatchUnpairEngine(backgroundScope, requestSnapshot = { error("Nothing is needed from the watch") },
            cleanup = { finish.await(); throw IOException("fixture") },
            identityTimeoutMillis = identityTimeout, ackTimeoutMillis = ackTimeout)
        try {
            val operation = launch { engine.clearLocalPairing(data.scope.peer) }; runCurrent()
            assertEquals(WatchUnpairPhase.CLEANING_UP, engine.state.value.phase)
            assertTrue(engine.state.value.busy)
            operation.cancel(); runCurrent()
            assertTrue(engine.state.value.busy)
            finish.complete(Unit); runCurrent(); operation.join()
            val result = assertNotNull(engine.state.value.lastResult)
            assertEquals(WatchUnpairOutcome.LOCAL_ONLY, result.outcome)
            assertEquals("IOException", result.localCleanupFailure)
            assertFalse(engine.state.value.busy)
        } finally { engine.close() }
    }

    @Test fun `close after writing cancels the caller but retains CLOSED while cleanup finishes`() = runTest {
        val data = FeatureTestData(); val finishCleanup = CompletableDeferred<Unit>(); var cleanups = 0
        lateinit var engine: WatchUnpairEngine
        engine = WatchUnpairEngine(backgroundScope,
            requestSnapshot = { session -> engine.offerRemoteItem(session.sessionId, home(data)) },
            cleanup = { cleanups++; finishCleanup.await() },
            identityTimeoutMillis = identityTimeout, ackTimeoutMillis = ackTimeout, remoteCleanupGraceMillis = 0)
        try {
            engine.attach(data.session())
            val operation = async { engine.unpairWithoutReset() }; runCurrent()
            engine.close(); runCurrent()
            assertEquals(WatchUnpairPhase.CLOSED, engine.state.value.phase)
            assertTrue(engine.state.value.busy)
            assertEquals(1, cleanups)
            finishCleanup.complete(Unit); runCurrent(); operation.join()
            assertTrue(operation.isCancelled)
            assertEquals(WatchUnpairPhase.CLOSED, engine.state.value.phase)
            assertEquals("watch_unpair_closed", engine.state.value.reason)
            assertEquals(WatchUnpairOutcome.UNCONFIRMED, engine.state.value.lastResult?.outcome)
            assertFalse(engine.state.value.busy)
        } finally { finishCleanup.complete(Unit); engine.close() }
    }
}
