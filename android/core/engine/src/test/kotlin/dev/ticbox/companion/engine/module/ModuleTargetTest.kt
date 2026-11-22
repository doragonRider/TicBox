package dev.ticbox.companion.engine.module

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class ModuleTargetTest {
    private val peer = PeerAddress("00:11:22:33:44:55")
    private val other = PeerAddress("00:11:22:33:44:66")
    private val catalog = MagiskModuleProtocol.Catalog("0.3.1", 4, "a".repeat(64), "b".repeat(64), 1, emptyMap())
    private fun payload() = HealthModulePayload(catalog) { byteArrayOf(1).inputStream() }
    private fun reply() = HealthModuleReply(ToolboxTarget("fixture", 1, ToolboxBackendKind.ROOT_BRIDGE), buildJsonObject {
        put("outcome", "READ")
        putJsonObject("snapshot") { put("sdk", 33); put("abi", "arm64-v8a") }
    })
    private fun commandResult(text: String = "") = ToolboxCommandResult(0, text.toByteArray(), byteArrayOf())

    @Test fun aConfirmedPeerCannotBeReplacedBeforeAnyModuleOperation() = runTest {
        var contacts = 0
        val module = HealthModuleEngine({ payload() }) { _, _, _, _, _ -> contacts++; reply() }
        val host = WatchModuleHostEngine { _, _, _ -> contacts++; commandResult("zygisk value=1\n") }
        val patch = NotificationAlertPatchEngine { _, _, _ -> contacts++; commandResult("optin none\n") }
        val changed = HealthModuleLink(other, 2)
        module.attach(changed); host.attach(changed); patch.attach(changed)
        withContext(WatchModuleTarget(peer)) {
            val operations: List<suspend () -> Unit> = listOf(
                { module.refresh() }, { module.installBundled() },
                { host.refresh() }, { host.stayAwake() }, { host.turnOnZygisk() }, { host.restart() },
                { patch.refresh() }, { patch.optIn() }, { patch.optOut() }, { patch.applyToAll() },
            )
            for (operation in operations) {
                assertEquals("device_changed", assertFailsWith<ToolboxException> { operation() }.code)
            }
        }
        assertEquals(0, contacts)
        assertFalse(module.state.value.current); assertFalse(host.state.value.current); assertFalse(patch.state.value.current)
        module.close(); host.close(); patch.close()
    }

    @Test fun aScopedPlanAcceptsTheSamePeerOnANewPhysicalConnection() = runTest {
        val seen = mutableListOf<HealthModuleLink>()
        val module = HealthModuleEngine({ payload() }) { link, _, _, _, _ -> seen += link; reply() }
        val host = WatchModuleHostEngine { link, _, _ -> seen += link; commandResult("zygisk value=1\n") }
        val patch = NotificationAlertPatchEngine { link, _, _ -> seen += link; commandResult("optin none\n") }
        withContext(WatchModuleTarget(peer)) {
            for (sessionId in listOf(1L, 2L)) {
                val link = HealthModuleLink(peer, sessionId)
                module.attach(link); host.attach(link); patch.attach(link)
                assertEquals(HealthModuleOutcome.READ, module.refresh().outcome)
                host.refresh(); patch.refresh()
                assertTrue(module.state.value.current); assertTrue(host.state.value.current); assertTrue(patch.state.value.current)
            }
        }
        assertEquals(listOf(1L, 1L, 1L, 2L, 2L, 2L), seen.map { it.sessionId })
        assertTrue(seen.all { it.peer == peer })
        module.close(); host.close(); patch.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun queuedRebootAndWakeRequestsCannotRunAfterTheLinkEndsOrTheClientCloses() = runTest {
        for (restart in listOf(false, true)) for (close in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val host = WatchModuleHostEngine { _, _, _ ->
                calls++; entered.complete(Unit); release.await(); commandResult("zygisk value=1\n")
            }
            host.attach(HealthModuleLink(peer, 1))
            val reading = async { host.refresh() }
            entered.await()
            val queued = async { if (restart) host.restart() else host.stayAwake() }
            runCurrent()
            if (close) host.close() else host.attach(HealthModuleLink(other, 2))
            release.complete(Unit)
            reading.await()
            assertFailsWith<CancellationException> { queued.await() }
            assertEquals(1, calls)
            host.close()
        }
    }
}
