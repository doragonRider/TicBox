package dev.ticbox.companion.engine.module

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class HealthModuleEngineTest {
    private val catalog = MagiskModuleProtocol.Catalog("0.3.0", 3, "a".repeat(64), "b".repeat(64), 1, emptyMap())
    private val link = HealthModuleLink(PeerAddress("00:11:22:33:44:55"), 1)
    private val target = ToolboxTarget("watch1", 5, ToolboxBackendKind.ROOT_BRIDGE)
    private fun engine(remote: HealthModuleRemote) = HealthModuleEngine({ HealthModulePayload(catalog) { byteArrayOf(1).inputStream() } }, remote)
    private fun reply(outcome: String = "READ") = HealthModuleReply(target, buildJsonObject {
        put("outcome", outcome); putJsonObject("snapshot") {
            put("sdk", 33); put("abi", "arm64-v8a"); put("magiskVersionCode", 31001)
            put("healthRuntimeBuildId", "c".repeat(64))
            putJsonObject("staged") { put("buildId", catalog.buildId); put("matchesBundled", true) }
        }
    })
    @Test fun readingBundleOfflineNeverUsesRootAndStagingDoesNotClaimRuntimeActivation() = runTest {
        var calls = 0
        val engine = engine { _, _, _, _, _ -> calls++; reply() }
        assertEquals(catalog.buildId, engine.bundled().buildId); assertEquals(0, calls)
        assertEquals(HealthModuleOutcome.REJECTED, engine.refresh().outcome)
        engine.attach(link); assertEquals(0, calls)
        assertEquals(HealthModuleOutcome.READ, engine.refresh().outcome)
        val state = engine.state.value
        assertTrue(state.current); assertTrue(state.snapshot!!.rebootRequired)
        assertNotEquals(state.bundle!!.buildId, state.snapshot!!.healthRuntimeBuildId)
        engine.attach(null); assertFalse(engine.state.value.current); assertNull(engine.state.value.snapshot)
    }
    @Test fun preflightRefusalAndLossAfterSubmissionRemainDistinctAndAreNeverRetried() = runTest {
        var calls = 0
        val engine = engine { _, _, _, _, submitted -> calls++; if (calls == 1) throw ToolboxException("module_bridge_update_required")
            submitted(); throw java.io.EOFException() }
        engine.attach(link)
        assertEquals(HealthModuleOutcome.REJECTED, engine.installBundled().outcome)
        assertEquals(HealthModuleOutcome.UNCONFIRMED, engine.installBundled().outcome)
        assertEquals(2, calls); assertFalse(engine.state.value.current)
    }
    @Test fun callerCancellationRecordsUnknownButStillPropagatesCancellation() = runTest {
        val submitted = CompletableDeferred<Unit>()
        val engine = engine { _, _, _, _, accepted -> accepted(); submitted.complete(Unit); awaitCancellation() }
        engine.attach(link)
        val job = launch { engine.installBundled() }
        submitted.await(); job.cancelAndJoin()
        assertEquals(HealthModuleOutcome.UNCONFIRMED, engine.state.value.lastOperation!!.outcome)
        assertEquals(HealthModulePhase.READY, engine.state.value.phase)
    }
    @Test fun oldRepliesAndQueuedInstallsCannotMoveToReconnectedWatch() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
        val engine = engine { _, _, _, _, _ -> calls++; entered.complete(Unit); release.await(); reply() }
        engine.attach(link)
        val first = async { engine.refresh() }; entered.await()
        val second = async { engine.installBundled() }; yield()
        engine.attach(link.copy(sessionId = 2)); release.complete(Unit)
        assertFailsWith<CancellationException> { first.await() }
        assertFailsWith<CancellationException> { second.await() }
        assertEquals(1, calls); assertNull(engine.state.value.snapshot); assertFalse(engine.state.value.current)
    }
    @Test fun nestedTimeoutIsAnUnconfirmedResultRatherThanSilentUiCancellation() = runTest {
        val engine = engine { _, _, _, _, submitted -> submitted(); withTimeout(1) { delay(5); reply() } }
        engine.attach(link)
        assertEquals(HealthModuleOutcome.UNCONFIRMED, engine.installBundled().outcome)
    }
}
