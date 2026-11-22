@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

class DndSyncEngineTest {
    private class Store(var value: DndSettings = DndSettings()) : DndSettingsStore {
        override fun load() = value
        override fun save(settings: DndSettings) { value = settings }
    }
    private val a = DndLink(PeerAddress("AA:BB:CC:DD:EE:01"), 1)
    private val b = DndLink(PeerAddress("AA:BB:CC:DD:EE:02"), 2)
    private data class Request(val link: DndLink, val revision: Long, val active: Boolean)

    @Test fun offNeverTouchesWatchAndUnknownListenerNeverTurnsDndOff() = runTest {
        val phone = MutableStateFlow<Boolean?>(true); val calls = mutableListOf<Request>()
        val engine = DndSyncEngine(backgroundScope, phone, Store(), DndRemote { l, r, v -> calls += Request(l, r, v); v })
        engine.attach(a); runCurrent(); assertTrue(calls.isEmpty())
        phone.value = null; engine.setMode(DndSyncMode.FOLLOW_PHONE); runCurrent()
        assertTrue(calls.isEmpty()); assertEquals("notification_listener_unavailable", engine.state.value.error)
        phone.value = true; runCurrent(); assertTrue(calls.single().active)
        phone.value = false; runCurrent(); assertFalse(calls.last().active)
        assertFalse(engine.state.value.watchRuleActive!!); engine.close()
    }
    @Test fun lostActivationReceiptStillReleasesOurRuleWhenDisabledAndRestarted() = runTest {
        val store = Store(DndSettings(DndSyncMode.FOLLOW_PHONE)); val phone = MutableStateFlow<Boolean?>(true)
        val first = DndSyncEngine(backgroundScope, phone, store, DndRemote { _, _, _ -> awaitCancellation() })
        first.attach(a); runCurrent(); assertEquals(setOf(a.peer.value), store.value.ownedPeers)
        first.setMode(DndSyncMode.OFF); runCurrent(); first.close()
        val calls = mutableListOf<Request>()
        val second = DndSyncEngine(backgroundScope, phone, store, DndRemote { l, r, v -> calls += Request(l, r, v); v })
        second.attach(a.copy(sessionId = 3)); runCurrent()
        assertFalse(calls.single().active); assertTrue(store.value.ownedPeers.isEmpty())
        assertFalse(second.state.value.pending); second.close()
    }
    @Test fun rapidPhoneChangesCancelOldRequestAndOnlyLatestAcknowledgementWins() = runTest {
        val phone = MutableStateFlow<Boolean?>(true); val replies = mutableListOf<CompletableDeferred<Boolean>>()
        val requests = mutableListOf<Request>()
        val engine = DndSyncEngine(backgroundScope, phone, Store(DndSettings(DndSyncMode.FOLLOW_PHONE)), DndRemote { l, r, v ->
            requests += Request(l, r, v); CompletableDeferred<Boolean>().also { replies += it }.await()
        })
        engine.attach(a); runCurrent(); phone.value = false; runCurrent()
        assertEquals(listOf(true, false), requests.map { it.active })
        replies.first().complete(true); runCurrent(); assertNull(engine.state.value.watchRuleActive)
        replies.last().complete(false); runCurrent(); assertEquals(false, engine.state.value.watchRuleActive)
        assertTrue(requests[1].revision > requests[0].revision); engine.close()
    }
    @Test fun offlineReleaseDoesNotAffectDifferentWatchAndSurvivesUntilOriginalReturns() = runTest {
        val store = Store(DndSettings(DndSyncMode.OFF, 4, setOf(a.peer.value)))
        val calls = mutableListOf<Request>()
        val engine = DndSyncEngine(backgroundScope, MutableStateFlow<Boolean?>(false), store, DndRemote { l, r, v -> calls += Request(l, r, v); v })
        engine.attach(b); runCurrent(); assertTrue(calls.isEmpty()); assertTrue(engine.state.value.pending)
        engine.attach(a); runCurrent(); assertEquals(a, calls.single().link); assertFalse(calls.single().active)
        engine.attach(b); runCurrent(); assertNull(engine.state.value.watchRuleActive); assertFalse(engine.state.value.pending)
        engine.close()
    }
    @Test fun transientFailureRetriesButPermissionDenialWaitsForExplicitRefresh() = runTest {
        var attempts = 0; var permission = false
        val engine = DndSyncEngine(backgroundScope, MutableStateFlow<Boolean?>(true), Store(DndSettings(DndSyncMode.FOLLOW_PHONE)), DndRemote { _, _, v ->
            attempts++; if (attempts == 1) throw ToolboxException("dnd_ack_timeout")
            if (!permission) throw ToolboxException("notification_policy_access_required")
            v
        }, retryMillis = 100)
        engine.attach(a); runCurrent(); assertEquals(1, attempts)
        advanceTimeBy(101); runCurrent(); assertEquals(2, attempts)
        advanceTimeBy(100_000); runCurrent(); assertEquals(2, attempts)
        permission = true; engine.refresh(); runCurrent(); assertEquals(true, engine.state.value.watchRuleActive)
        engine.close()
    }
    @Test fun persistenceFailurePreventsRemoteMutationAndRemainsRecoverable() = runTest {
        var writable = false; var requests = 0
        val store = object : DndSettingsStore {
            override fun load() = DndSettings(DndSyncMode.FOLLOW_PHONE)
            override fun save(settings: DndSettings) { if (!writable) error("disk unavailable") }
        }
        val engine = DndSyncEngine(backgroundScope, MutableStateFlow<Boolean?>(true), store, DndRemote { _, _, value -> requests++; value })
        engine.attach(a); runCurrent(); assertEquals(0, requests); assertTrue(engine.state.value.pending)
        writable = true; engine.refresh(); runCurrent(); assertEquals(1, requests); engine.close()
    }
}
