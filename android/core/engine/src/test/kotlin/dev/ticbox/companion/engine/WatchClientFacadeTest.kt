package dev.ticbox.companion.engine

import dev.ticbox.companion.api.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WatchClientFacadeTest {
    @Test fun cloudSideEffectCommandsWaitForFrontendSettingsWhileLocalBatteryStillWorks() = runTest {
        val session = FakeSession()
        val client = WatchClientFacade(backgroundScope, session, clock)
        client.connect(peer)
        assertFailsWith<CloudFeatureDisabledException> { client.refreshHealth() }
        client.refreshBattery()
        assertEquals(listOf("/companion/sync_wear_battery"), session.writes.map { it.first })
        session.message("/health/cmd_new_wear_push_to_phone", """{"goal":10000,"current_step":123}""")
        runCurrent()
        assertEquals(StepSnapshot(123, 10000), client.state.value.steps?.value)
        client.close()
    }
    @Test fun aStepGoalIsSentWithTheCloudSwitchOff() = runTest {
        val session = FakeSession()
        val client = WatchClientFacade(backgroundScope, session, clock)
        client.connect(peer)
        client.setStepGoal(12000)
        assertEquals(listOf("/health/cmd_wear_pull_from_phone"), session.writes.map { it.first })
        client.close()
    }
    private val peer = PeerAddress("AA:BB:CC:DD:EE:FF")
    private val clock = Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC)

    private class FakeSession : CompanionBackend {
        override val state = MutableStateFlow(ConnectionState())
        override val events = MutableSharedFlow<BackendEvent>(extraBufferCapacity = 16)
        val writes = mutableListOf<Pair<String, ByteArray>>()
        var closed = false
        private var generation = 0L
        override suspend fun connect(peer: PeerAddress) {
            state.value = ConnectionState(ConnectionPhase.LINK_READY, peer, ++generation)
        }
        override suspend fun disconnect() { state.value = state.value.copy(phase = ConnectionPhase.IDLE) }
        override suspend fun sendMessage(path: String, payload: ByteArray): TransmissionReceipt {
            if (state.value.phase != ConnectionPhase.LINK_READY) throw NotConnectedException()
            writes += path to payload.copyOf()
            return TransmissionReceipt(writes.size, payload.size)
        }
        override fun close() { closed = true; state.value = state.value.copy(phase = ConnectionPhase.IDLE) }
        suspend fun message(path: String, text: String, sessionId: Long = state.value.sessionId, expectsResponse: Boolean = false) {
            events.emit(BackendEvent.MessageReceived(state.value.peer!!, sessionId,
                InboundMessage(1, path, text.toByteArray(Charsets.UTF_8), "watch", expectsResponse)))
        }
    }

    private class History : WatchObservationStore {
        val samples = mutableListOf<WatchHistorySample>()
        override suspend fun append(peer: PeerAddress, kind: WatchSampleKind, atMillis: Long, batteryPercent: Int?, steps: StepSnapshot?) {
            samples += WatchHistorySample((samples.size + 1).toLong(), peer, kind, atMillis, batteryPercent, steps)
        }
        override suspend fun recent(peer: PeerAddress, kind: WatchSampleKind?, limit: Int, beforeId: Long?) = samples.asReversed()
            .filter { it.peer == peer && (kind == null || it.kind == kind) && (beforeId == null || it.id < beforeId) }.take(limit)
    }

    @Test fun observationsAreRestoredStaleAfterRestartAndNeverMixedAcrossPeers() = runTest {
        val history = History()
        val first = FakeSession(); val client = WatchClientFacade(backgroundScope, first, clock, history = history)
        client.connect(peer); runCurrent()
        first.message("/companion/sync_wear_battery", "74"); runCurrent()
        first.message("/health/cmd_new_wear_push_to_phone", """{"goal":10000,"current_step":123}"""); runCurrent()
        client.close()
        val second = FakeSession(); val restored = WatchClientFacade(backgroundScope, second, clock, history = history)
        restored.connect(peer); runCurrent()
        assertEquals(74, restored.state.value.battery?.value)
        assertEquals(true, restored.state.value.battery?.stale)
        assertEquals(StepSnapshot(123, 10000), restored.state.value.steps?.value)
        assertEquals(true, restored.state.value.steps?.stale)
        second.message("/companion/sync_wear_battery", "73"); runCurrent()
        assertEquals(false, restored.state.value.battery?.stale)
        restored.connect(PeerAddress("10:20:30:40:50:60")); runCurrent()
        assertNull(restored.state.value.battery); assertNull(restored.state.value.steps)
        assertEquals(3, history.samples.size)
        restored.close()
    }

    @Test fun batteryChangesOnlyAfterAValidWatchObservation() = runTest {
        val session = FakeSession()
        val client = WatchClientFacade(backgroundScope, session, clock)
        client.connect(peer)
        client.refreshBattery()
        runCurrent()
        assertNull(client.state.value.battery)
        session.message("/companion/sync_wear_battery", "74")
        runCurrent()
        assertEquals(WatchObservation(74, 1234, false), client.state.value.battery)
        for (bad in listOf("101", "-1", "1.5", "not-an-integer")) {
            session.message("/companion/sync_wear_battery", bad)
            runCurrent()
            assertEquals(74, client.state.value.battery?.value)
            assertEquals(WatchDataIssue.INVALID_BATTERY, client.state.value.lastDataIssue)
        }
    }

    @Test fun stepNumbersRemainTypedAndInvalidDataDoesNotBecomeZero() = runTest {
        val session = FakeSession()
        val client = WatchClientFacade(backgroundScope, session, clock)
        client.connect(peer)
        session.message("/health/cmd_new_wear_push_to_phone", """{"goal":10000,"current_step":123,"unknown":true}""")
        runCurrent()
        assertEquals(StepSnapshot(123, 10000), client.state.value.steps?.value)
        for (bad in listOf(
            "{}",
            """{"goal":10000,"current_step":2147483648}""",
            """{"goal":10000,"current_step":-1}""",
            """{"goal":10000,"current_step":1.5}""",
            """{"goal":10000,"current_step":1e-999}""",
            """{"goal":10000,"current_step":1e}""",
        )) {
            session.message("/health/cmd_new_wear_push_to_phone", bad)
            runCurrent()
            assertEquals(StepSnapshot(123, 10000), client.state.value.steps?.value)
            assertEquals(WatchDataIssue.INVALID_STEPS, client.state.value.lastDataIssue)
        }
    }

    @Test fun equivalentNumericSpellingsAreInterpretedAndDiagnosedWithoutRounding() = runTest {
        val session = FakeSession()
        val notes = mutableListOf<ProtocolDiagnostic>()
        val client = WatchClientFacade(backgroundScope, session, clock, DiagnosticSink {
            notes.add(it)
            throw IllegalStateException("Diagnostic consumer unavailable")
        })
        client.connect(peer)
        for (payload in listOf(" 80 ", "\uFEFF80", "\"80\"", "8e1", "80.0", "+080")) {
            session.message("/companion/sync_wear_battery", payload)
            runCurrent()
            assertEquals(80, client.state.value.battery?.value)
            assertNull(client.state.value.lastDataIssue)
        }
        for (payload in listOf(
            """{"goal":"10000","current_step":" 12 "}""",
            """{"goal":1e4,"current_step":12.0}""",
            """{"goal":"010000","current_step":"1.2e1"}""",
        )) {
            session.message("/health/cmd_new_wear_push_to_phone", payload)
            runCurrent()
            assertEquals(StepSnapshot(12, 10000), client.state.value.steps?.value)
            assertNull(client.state.value.lastDataIssue)
        }
        session.message("/health/cmd_new_wear_push_to_phone", """{"goal":10000,"current_step":1e-999}""")
        runCurrent()
        assertEquals(StepSnapshot(12, 10000), client.state.value.steps?.value)
        assertEquals(WatchDataIssue.INVALID_STEPS, client.state.value.lastDataIssue)
        assertTrue(notes.any { it.event == "status_text_normalized" })
        assertTrue(notes.any { it.event == "status_number_interpreted" && it.details["representation"] == "string" })
        assertTrue(notes.any { it.event == "status_number_interpreted" && it.details["exponentNotation"] == "true" })
        assertTrue(notes.any { it.event == "status_observation_unreadable" && it.details["issue"] == "INVALID_STEPS" })
        assertTrue(notes.any { it.event == "status_observation_unreadable" && it.details["field"] == "current_step" && it.details["reason"] == "fractional_or_outside_java_int" })
        assertTrue(notes.all { it.peer == peer && it.sessionId == session.state.value.sessionId })
        assertFalse(notes.any { it.details.containsKey("payload") || it.details.containsKey("value") })
    }

    @Test fun typedCommandsUseRecoveredPayloadsWithoutFabricatingConfirmation() = runTest {
        val session = FakeSession()
        val client = WatchClientFacade(backgroundScope, session, clock, cloudSettings = { CloudPrivacySettings(setOf(CloudCapability.MOBVOI_CLOUD)) })
        client.connect(peer)
        session.message("/health/cmd_new_wear_push_to_phone", """{"goal":10000,"current_step":123}""")
        runCurrent()
        client.refreshBattery()
        client.refreshHealth()
        client.setStepGoal(12000)
        assertEquals(listOf("/companion/sync_wear_battery", "/health/cmd_wear_push_to_phone", "/health/cmd_wear_pull_from_phone"), session.writes.map { it.first })
        assertContentEquals(byteArrayOf(), session.writes[0].second)
        assertContentEquals(byteArrayOf(), session.writes[1].second)
        assertEquals("""{"goal":12000,"current_step":0}""", session.writes[2].second.toString(Charsets.UTF_8))
        assertEquals(10000, client.state.value.steps?.value?.goal)
        assertFailsWith<IllegalArgumentException> { client.setStepGoal(0) }
        assertFailsWith<IllegalArgumentException> { client.setStepGoal(-1) }
        assertEquals(3, session.writes.size)
    }

    @Test fun peerRequestsDoNotFabricateStatusObservationsOrResponses() = runTest {
        val session = FakeSession()
        val client = WatchClientFacade(backgroundScope, session, clock)
        client.connect(peer)
        session.message("/companion/sync_wear_battery", "74", expectsResponse = true)
        session.message("/health/cmd_new_wear_push_to_phone", """{"goal":8000,"current_step":10}""", expectsResponse = true)
        runCurrent()
        assertNull(client.state.value.battery)
        assertNull(client.state.value.steps)
        assertTrue(session.writes.isEmpty())
        session.message("/companion/sync_wear_battery", "74")
        runCurrent()
        assertEquals(74, client.state.value.battery?.value)
    }

    @Test fun observationsBecomeStaleAndOldSessionEventsCannotRefreshThem() = runTest {
        val session = FakeSession()
        val client = WatchClientFacade(backgroundScope, session, clock)
        client.connect(peer)
        session.message("/companion/sync_wear_battery", "74")
        runCurrent()
        val old = session.state.value.sessionId
        client.disconnect()
        runCurrent()
        assertEquals(true, client.state.value.battery?.stale)
        client.connect(peer)
        session.message("/companion/sync_wear_battery", "99", old)
        runCurrent()
        assertEquals(74, client.state.value.battery?.value)
        assertEquals(true, client.state.value.battery?.stale)
        session.message("/companion/sync_wear_battery", "invalid")
        runCurrent()
        assertEquals(WatchDataIssue.INVALID_BATTERY, client.state.value.lastDataIssue)
        client.connect(PeerAddress("00:11:22:33:44:55"))
        runCurrent()
        assertNull(client.state.value.battery)
        assertNull(client.state.value.steps)
        assertNull(client.state.value.lastDataIssue)
    }

    @Test fun malformedUtf8AndDeepJsonCannotKillTheObservationCollector() = runTest {
        val session = FakeSession()
        val client = WatchClientFacade(backgroundScope, session, clock)
        client.connect(peer)
        session.events.emit(BackendEvent.MessageReceived(peer, session.state.value.sessionId,
            InboundMessage(1, "/health/cmd_new_wear_push_to_phone", byteArrayOf(0xc3.toByte(), 0x28), null)))
        runCurrent()
        assertEquals(WatchDataIssue.INVALID_STEPS, client.state.value.lastDataIssue)
        session.message("/health/cmd_new_wear_push_to_phone", "[".repeat(30) + "0" + "]".repeat(30))
        runCurrent()
        assertEquals(WatchDataIssue.INVALID_STEPS, client.state.value.lastDataIssue)
        session.message("/health/cmd_new_wear_push_to_phone", """{"goal":8000,"current_step":10}""")
        runCurrent()
        assertEquals(StepSnapshot(10, 8000), client.state.value.steps?.value)
        assertNull(client.state.value.lastDataIssue)
    }

    @Test fun closeReleasesTheOwnedSession() = runTest {
        val session = FakeSession()
        val client = WatchClientFacade(backgroundScope, session, clock)
        client.close()
        assertTrue(session.closed)
        assertEquals(ConnectionPhase.IDLE, client.state.value.connection.phase)
    }
}
