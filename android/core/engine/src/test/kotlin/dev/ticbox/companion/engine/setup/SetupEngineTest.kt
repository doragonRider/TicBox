package dev.ticbox.companion.engine.setup

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SetupEngineTest {
    private val peer = PeerAddress("01:23:45:67:89:AB")
    private val config = SetupConfig(queryTimeoutMillis = 100, readyTimeoutMillis = 1_000,
        checkinTimeoutMillis = 1_000, checkinPollMillis = 100, completionWaitMillis = 100,
        completionAttempts = 3, oobeTimeoutMillis = 1_000, dependencyTimeoutMillis = 100)
    private val options = SetupOptions(termsAccepted = true, accountChoice = SetupAccountChoice.SKIP)
    private val checkinOptions = options.copy(checkinChoice = SetupCheckinChoice.ATTEMPT)
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000), ZoneOffset.UTC)

    @Test fun fragmentedProbeQueriesWithoutChangingWatchSetup() = runTest {
        val backend = FakeBackend()
        backend.fragment = true
        backend.watchRequestsTime = true
        val rpc = FakeRpc()
        val engine = SetupEngine(backgroundScope, backend, rpc, clock = clock, config = config)
        try {
            val result = async { engine.probe() }
            runCurrent()
            val probe = result.await()
            assertEquals(1, probe.watchStatusValue)
            assertEquals(listOf(2, 5), probe.systemInfo?.effectiveSetupCapabilities)
            assertFalse(probe.connectionInfoReceived)
            assertNull(probe.cellularInfo)
            assertEquals(listOf(1, 2), backend.channels.single().sent.map { (it["command"] as DecodedStruct)["type"] })
            assertTrue(rpc.requests.isEmpty())
            assertEquals(SetupPhase.PROBED, engine.state.value.phase)
            assertTrue(backend.channels.single().closed)
        } finally { engine.close() }
    }

    @Test fun explicitSetupUsesRpcAndRequiresWatchCompletionAfterBlockAcks() = runTest {
        val backend = FakeBackend()
        val rpc = FakeRpc().apply { checkin = 3 }
        val engine = SetupEngine(backgroundScope, backend, rpc, clock = clock, timeZone = { "Etc/UTC" }, config = config)
        try {
            engine.start(checkinOptions.copy(consents = listOf(SetupConsent(SetupOptinType.LOCATION, SetupOptinChoice.DECLINED, 42))))
            runCurrent()
            assertEquals(SetupPhase.COMPLETED, engine.state.value.phase)
            assertTrue(engine.state.value.watchCompleteObserved)
            assertFalse(engine.state.value.watchOobeDoneObserved)
            assertFalse(engine.state.value.dataPreparationCompleted)
            assertFalse(engine.state.value.dataSyncCompletionObserved)
            val time = backend.channels.single().sent.single { it["type"] == 5 }["companionTime"] as DecodedStruct
            assertEquals(clock.millis(), time["millis"])
            assertEquals("Etc/UTC", time["timeZoneId"])
            val optin = (backend.channels.single().sent.single { it["type"] == 2 }["event"] as DecodedStruct)["optin"] as DecodedStruct
            assertEquals(2, optin["state"])
            assertEquals(42L, optin["time"])
            assertEquals(listOf(SetupProtocol.TETHER_CONFIG_PATH, SetupProtocol.CHECKIN_REQUEST_PATH), rpc.requests.map { it.first })
            assertContentEquals(byteArrayOf(8, 2), rpc.requests.first().second)
        } finally { engine.close() }
    }

    @Test fun defaultLocalSetupSkipsAdvertisedCheckinWithoutQueryingOrWaiting() = runTest {
        val backend = FakeBackend().apply { setupCapabilities = listOf(1, 2, 6) }
        val rpc = FakeRpc().apply {
            checkinResponseGate = CompletableDeferred()
            onRestoreSkipped = { backend.oobeAfterComplete = true }
        }
        val logs = mutableListOf<ProtocolDiagnostic>()
        val engine = SetupEngine(backgroundScope, backend, rpc, diagnostics = DiagnosticSink(logs::add), config = config)
        try {
            engine.start(options)
            runCurrent()
            assertEquals(SetupPhase.COMPLETED, engine.state.value.phase)
            assertTrue(engine.state.value.watchCompleteObserved)
            assertTrue(engine.state.value.watchOobeDoneObserved)
            assertEquals(0L, testScheduler.currentTime)
            assertEquals(SetupStepState.SKIPPED, engine.state.value.progress.single { it.step == SetupStep.CHECKIN }.state)
            assertNull(engine.state.value.checkin)
            assertTrue(rpc.requests.none { it.first == SetupProtocol.CHECKIN_REQUEST_PATH })
            assertTrue(logs.any { it.event == "checkin_skipped" && it.details["reason"] == "not_requested" })
        } finally { engine.close() }
    }

    @Test fun skippingInFlightCheckinCancelsOnlyItsQueryAndStillRequiresWatchComplete() = runTest {
        val backend = FakeBackend().apply { setupCapabilities = listOf(2); acknowledgeComplete = false }
        val rpc = FakeRpc().apply { checkinResponseGate = CompletableDeferred() }
        val engine = SetupEngine(backgroundScope, backend, rpc, config = config)
        try {
            engine.start(checkinOptions)
            runCurrent()
            val action = assertNotNull(engine.state.value.action)
            assertEquals(SetupActionKind.CHECKIN, action.kind)
            assertEquals("watch_checkin_in_progress", action.reason)
            assertEquals(setOf(SetupDecision.SKIP_OPTIONAL, SetupDecision.CANCEL), action.allowedDecisions)
            assertEquals(SetupPhase.RUNNING, engine.state.value.phase)
            assertContentEquals(byteArrayOf(), rpc.update(peer, 7, 77))
            assertTrue(engine.resume(action.id, SetupUserResponse(SetupDecision.SKIP_OPTIONAL)))
            runCurrent()
            assertEquals(1, rpc.cancelledCheckinRequests)
            assertEquals(1, rpc.requests.count { it.first == SetupProtocol.CHECKIN_REQUEST_PATH })
            assertEquals(77, engine.state.value.checkin?.statusValue)
            assertEquals(SetupStepState.SKIPPED, engine.state.value.progress.single { it.step == SetupStep.CHECKIN }.state)
            assertEquals(SetupStep.COMPLETE, engine.state.value.currentStep)
            assertEquals(SetupPhase.RUNNING, engine.state.value.phase)
            assertFalse(engine.state.value.watchCompleteObserved)
            assertFalse(backend.channels.single().closed)
            assertNull(engine.state.value.action)
            assertFalse(engine.resume(action.id, SetupUserResponse(SetupDecision.SKIP_OPTIONAL)))
            backend.channels.single().emit(SetupProtocol.encodeStatus(3))
            runCurrent()
            assertEquals(SetupPhase.COMPLETED, engine.state.value.phase)
            advanceTimeBy(config.checkinTimeoutMillis * 2)
            runCurrent()
            assertEquals(1, rpc.requests.count { it.first == SetupProtocol.CHECKIN_REQUEST_PATH })
            assertEquals(77, engine.state.value.checkin?.statusValue)
        } finally { engine.close() }
    }

    @Test fun timedOutSelectedCheckinCanBeSkippedWithoutInventingSuccess() = runTest {
        val backend = FakeBackend().apply { setupCapabilities = listOf(2) }
        val rpc = FakeRpc().apply { checkin = 1 }
        val logs = mutableListOf<ProtocolDiagnostic>()
        val engine = SetupEngine(backgroundScope, backend, rpc, diagnostics = DiagnosticSink(logs::add), config = config)
        try {
            engine.start(checkinOptions)
            runCurrent()
            advanceTimeBy(config.checkinTimeoutMillis)
            runCurrent()
            val action = assertNotNull(engine.state.value.action)
            assertEquals(SetupPhase.WAITING_FOR_USER, engine.state.value.phase)
            assertEquals(SetupActionKind.CHECKIN, action.kind)
            assertEquals(setOf(SetupDecision.RETRY, SetupDecision.SKIP_OPTIONAL, SetupDecision.CANCEL), action.allowedDecisions)
            assertFalse(engine.state.value.watchCompleteObserved)
            assertTrue(logs.any { it.event == "checkin_timeout" })
            assertTrue(engine.resume(action.id, SetupUserResponse(SetupDecision.SKIP_OPTIONAL)))
            runCurrent()
            assertEquals(SetupPhase.COMPLETED, engine.state.value.phase)
            assertEquals(1, engine.state.value.checkin?.statusValue)
            assertEquals(SetupStepState.SKIPPED, engine.state.value.progress.single { it.step == SetupStep.CHECKIN }.state)
            assertTrue(logs.any { it.event == "checkin_skipped" && it.details["reason"] == "user_skipped_after_failure" })
        } finally { engine.close() }
    }

    @Test fun cancellingRunningCheckinCancelsSetupAndClosesItsChannel() = runTest {
        val backend = FakeBackend().apply { setupCapabilities = listOf(2) }
        val rpc = FakeRpc().apply { checkinResponseGate = CompletableDeferred() }
        val engine = SetupEngine(backgroundScope, backend, rpc, config = config)
        try {
            engine.start(checkinOptions)
            runCurrent()
            val action = assertNotNull(engine.state.value.action)
            assertTrue(engine.resume(action.id, SetupUserResponse(SetupDecision.CANCEL)))
            runCurrent()
            assertEquals(SetupPhase.CANCELLED, engine.state.value.phase)
            assertEquals(1, rpc.cancelledCheckinRequests)
            assertTrue(backend.channels.single().closed)
            assertTrue(backend.channels.single().sent.none { it["type"] == 3 })
        } finally { engine.close() }
    }

    @Test fun perBlockAckDoesNotFabricateWatchComplete() = runTest {
        val backend = FakeBackend().apply { acknowledgeComplete = false; setupCapabilities = emptyList() }
        val engine = SetupEngine(backgroundScope, backend, FakeRpc(), config = config)
        try {
            engine.start(options)
            runCurrent()
            assertEquals(SetupStep.COMPLETE, engine.state.value.currentStep)
            assertFalse(engine.state.value.watchCompleteObserved)
            advanceTimeBy(301)
            runCurrent()
            assertEquals(SetupPhase.FAILED, engine.state.value.phase)
            assertEquals("watch_complete_not_observed", engine.state.value.failure?.reason)
            assertEquals(3, backend.channels.single().sent.count { it["type"] == 3 })
        } finally { engine.close() }
    }

    @Test fun checkinUpdateIsAcknowledgedAndUnknownValuesNeverSucceed() = runTest {
        val backend = FakeBackend().apply { setupCapabilities = listOf(2) }
        val rpc = FakeRpc().apply { checkin = 1 }
        val records = mutableListOf<ProtocolDiagnostic>()
        val engine = SetupEngine(backgroundScope, backend, rpc, diagnostics = DiagnosticSink(records::add), config = config)
        try {
            engine.start(checkinOptions)
            runCurrent()
            assertEquals(SetupStep.CHECKIN, engine.state.value.currentStep)
            assertContentEquals(byteArrayOf(), rpc.update(peer, 7, 77))
            assertEquals(77, engine.state.value.checkin?.statusValue)
            runCurrent()
            assertEquals(SetupPhase.RUNNING, engine.state.value.phase)
            // The newer fallback query can legitimately report STARTED after an unknown push.
            assertEquals(1, engine.state.value.checkin?.statusValue)
            assertTrue(records.any { it.event == "unknown_checkin_status" })
            assertContentEquals(byteArrayOf(), rpc.update(peer, 6, 3)) // Previous physical session.
            runCurrent()
            assertEquals(1, engine.state.value.checkin?.statusValue)
            assertContentEquals(byteArrayOf(), rpc.update(peer, 7, 3))
            runCurrent()
            assertEquals(SetupPhase.COMPLETED, engine.state.value.phase)
        } finally { engine.close() }
    }

    @Test fun restoreSkipStartsOemInitializationBeforeWatchOobe() = runTest {
        val backend = FakeBackend().apply { setupCapabilities = listOf(1, 2, 6) }
        val rpc = FakeRpc().apply { onRestoreSkipped = { backend.oobeAfterComplete = true } }
        val engine = SetupEngine(backgroundScope, backend, rpc, config = config)
        try {
            engine.start(options)
            runCurrent()
            val request = rpc.requests.single { it.first == "/clockworkSetupWizard/watch_restore_status_request" }
            assertContentEquals(byteArrayOf(0x08, 0x01), request.second)
            assertEquals(SetupPhase.COMPLETED, engine.state.value.phase)
            assertTrue(engine.state.value.watchCompleteObserved)
            assertTrue(engine.state.value.watchOobeDoneObserved)
            assertEquals(SetupStepState.SKIPPED, engine.state.value.progress.single { it.step == SetupStep.RESTORE }.state)
        } finally { engine.close() }
    }

    @Test fun resumeAfterCompleteStillNotifiesUnselectedRestoreWhileWatchWaitsForOem() = runTest {
        val backend = FakeBackend().apply { setupCapabilities = listOf(1, 2, 6); initialStatus = 3 }
        val rpc = FakeRpc().apply { onRestoreSkipped = { backend.channels.single().emit(SetupProtocol.encodeStatus(5)) } }
        val engine = SetupEngine(backgroundScope, backend, rpc, config = config)
        try {
            engine.start(options)
            runCurrent()
            assertEquals(SetupPhase.COMPLETED, engine.state.value.phase)
            assertTrue(engine.state.value.watchOobeDoneObserved)
            assertEquals(listOf(SetupProtocol.RESTORE_STATUS_PATH), rpc.requests.map { it.first })
            assertTrue(backend.channels.single().sent.none { it["type"] == 3 })
        } finally { engine.close() }
    }

    @Test fun watchAlreadyPastOobeDoesNotReceiveRestoreChoice() = runTest {
        val backend = FakeBackend().apply { setupCapabilities = listOf(1, 2, 6); initialStatus = 5 }
        val rpc = FakeRpc()
        val engine = SetupEngine(backgroundScope, backend, rpc, config = config)
        try {
            engine.start(options)
            runCurrent()
            assertEquals(SetupPhase.COMPLETED, engine.state.value.phase)
            assertTrue(rpc.requests.isEmpty())
        } finally { engine.close() }
    }

    @Test fun explicitlySkippingUnavailableRestoreAlsoNotifiesWatch() = runTest {
        val backend = FakeBackend().apply { setupCapabilities = listOf(1, 2, 6) }
        val rpc = FakeRpc().apply { onRestoreSkipped = { backend.oobeAfterComplete = true } }
        val engine = SetupEngine(backgroundScope, backend, rpc, config = config)
        try {
            engine.start(options.copy(optionalSteps = listOf(SetupOptionalStep.RESTORE)))
            runCurrent()
            val action = assertNotNull(engine.state.value.action)
            assertEquals(SetupOptionalStep.RESTORE, action.optionalStep)
            assertTrue(rpc.requests.none { it.first == SetupProtocol.RESTORE_STATUS_PATH })
            assertTrue(engine.resume(action.id, SetupUserResponse(SetupDecision.SKIP_OPTIONAL)))
            runCurrent()
            assertEquals(SetupPhase.COMPLETED, engine.state.value.phase)
            assertTrue(engine.state.value.watchOobeDoneObserved)
            assertEquals(SetupStepState.SKIPPED, engine.state.value.progress.single { it.step == SetupStep.OPTIONAL }.state)
        } finally { engine.close() }
    }

    @Test fun failedCheckinPushIsAcknowledgedThenRequeriedForWatchFallback() = runTest {
        val backend = FakeBackend().apply { setupCapabilities = listOf(2) }
        val rpc = FakeRpc().apply { checkin = 1 }
        val records = mutableListOf<ProtocolDiagnostic>()
        val engine = SetupEngine(backgroundScope, backend, rpc, diagnostics = DiagnosticSink(records::add), config = config)
        try {
            engine.start(checkinOptions)
            runCurrent()
            assertContentEquals(byteArrayOf(), rpc.update(peer, 7, 4))
            rpc.checkin = 3 // The stock service can apply its fallback after the RPC reply.
            runCurrent()
            assertEquals("watch_checkin_in_progress", engine.state.value.action?.reason)
            assertEquals(SetupPhase.RUNNING, engine.state.value.phase)
            assertFalse(engine.state.value.watchCompleteObserved)
            advanceTimeBy(config.checkinPollMillis)
            runCurrent()
            assertEquals(SetupPhase.COMPLETED, engine.state.value.phase)
            assertTrue(records.any { it.event == "checkin_status_received" && it.details["source"] == "update" && it.details["status"] == "4" })
            assertTrue(records.any { it.event == "checkin_status_received" && it.details["source"] == "response" && it.details["status"] == "3" })
        } finally { engine.close() }
    }

    @Test fun persistentCheckinFailureOffersRetryOrSkipAfterOneRequery() = runTest {
        val backend = FakeBackend().apply { setupCapabilities = listOf(2) }
        val rpc = FakeRpc().apply { checkin = 4; factoryReset = true }
        val engine = SetupEngine(backgroundScope, backend, rpc, config = config)
        try {
            engine.start(checkinOptions)
            runCurrent()
            advanceTimeBy(config.checkinPollMillis)
            runCurrent()
            val action = assertNotNull(engine.state.value.action)
            assertEquals(SetupActionKind.CHECKIN, action.kind)
            assertEquals("watch_reports_factory_reset", action.reason)
            assertEquals(setOf(SetupDecision.RETRY, SetupDecision.SKIP_OPTIONAL, SetupDecision.CANCEL), action.allowedDecisions)
            assertEquals(2, rpc.requests.count { it.first == SetupProtocol.CHECKIN_REQUEST_PATH })
            assertFalse(engine.state.value.watchCompleteObserved)
            rpc.checkin = 3
            assertTrue(engine.resume(action.id, SetupUserResponse(SetupDecision.RETRY)))
            runCurrent()
            assertEquals(SetupPhase.COMPLETED, engine.state.value.phase)
            assertEquals(SetupStepState.SUCCEEDED, engine.state.value.progress.single { it.step == SetupStep.CHECKIN }.state)
            assertEquals(3, engine.state.value.checkin?.statusValue)
        } finally { engine.close() }
    }

    @Test fun missingSelectedDependencyPausesAndStaleUserDecisionsCannotResumeIt() = runTest {
        val backend = FakeBackend().apply { setupCapabilities = emptyList() }
        var available = false
        val environment = object : SetupEnvironment {
            override suspend fun prepareInitialData(context: SetupContext, consents: List<SetupConsent>) =
                if (available) SetupDependencyResult.complete() else SetupDependencyResult.unavailable("locale_store_unavailable")
        }
        val engine = SetupEngine(backgroundScope, backend, FakeRpc(), environment, config = config)
        try {
            engine.start(options.copy(requireInitialDataPreparation = true))
            runCurrent()
            val action = assertNotNull(engine.state.value.action)
            assertEquals(SetupActionKind.INITIAL_DATA_PREPARATION, action.kind)
            assertEquals(SetupPhase.WAITING_FOR_USER, engine.state.value.phase)
            assertFalse(engine.resume(action.id, SetupUserResponse(SetupDecision.CONTINUE)))
            assertFalse(engine.resume(action.id - 1, SetupUserResponse(SetupDecision.RETRY)))
            available = true
            assertTrue(engine.resume(action.id, SetupUserResponse(SetupDecision.RETRY)))
            assertFalse(engine.resume(action.id, SetupUserResponse(SetupDecision.RETRY)))
            runCurrent()
            assertEquals(SetupPhase.COMPLETED, engine.state.value.phase)
            assertTrue(engine.state.value.dataPreparationCompleted)
        } finally { engine.close() }
    }

    @Test fun cancellationAndDisconnectReleaseRunAndNeverMigrateAnOldChannel() = runTest {
        val backend = FakeBackend().apply { setupCapabilities = emptyList() }
        val engine = SetupEngine(backgroundScope, backend, FakeRpc(), config = config)
        try {
            engine.start(options.copy(termsAccepted = null))
            runCurrent()
            val first = backend.channels.single()
            val action = assertNotNull(engine.state.value.action)
            val firstRun = engine.state.value.runId
            engine.start(options.copy(termsAccepted = null))
            assertEquals(firstRun, engine.state.value.runId)
            assertEquals(1, backend.channels.size)
            engine.cancel()
            assertEquals(SetupPhase.CANCELLED, engine.state.value.phase)
            assertTrue(first.closed)
            assertFalse(engine.resume(action.id, SetupUserResponse(SetupDecision.CONTINUE, termsAccepted = true)))
            // Restart before the old coroutine's finally block has had a scheduler turn.
            engine.start(options.copy(termsAccepted = null))
            runCurrent()
            backend.link.value = ConnectionState(ConnectionPhase.RETRYING, peer, sessionId = 8)
            runCurrent()
            assertEquals(SetupPhase.FAILED, engine.state.value.phase)
            assertEquals(SetupFailureKind.LINK_LOST, engine.state.value.failure?.kind)
            assertTrue(backend.channels.last().closed)
            backend.link.value = ConnectionState(ConnectionPhase.LINK_READY, peer, sessionId = 8)
            engine.start(options)
            runCurrent()
            assertEquals(SetupPhase.COMPLETED, engine.state.value.phase)
            assertEquals(8L, engine.state.value.sessionId)
            assertEquals(listOf(7L, 7L, 8L), backend.channels.map { it.info.sessionId })
        } finally { engine.close() }
    }

    @Test fun malformedFrameBodyAndMissingTypeRecoverWhileUnknownStatusStaysRaw() = runTest {
        val backend = FakeBackend().apply { setupCapabilities = emptyList(); corruptStatusPrelude = true; omitStatusType = true; extraStatusBody = true }
        val logs = mutableListOf<ProtocolDiagnostic>()
        val engine = SetupEngine(backgroundScope, backend, FakeRpc(), diagnostics = DiagnosticSink(logs::add), config = config)
        try {
            engine.start(options)
            runCurrent()
            assertEquals(SetupPhase.COMPLETED, engine.state.value.phase)
            assertTrue(logs.any { it.event == "malformed_setup_message" })
            assertTrue(logs.any { it.event == "setup_type_inferred" })
            assertTrue(logs.any { it.event == "setup_type_inferred_from_query" })
        } finally { engine.close() }
    }

    @Test fun advertisedOobeIsDistinctFromCompleteAndAcceptsFinalEof() = runTest {
        val backend = FakeBackend().apply { setupCapabilities = listOf(2, 6) }
        val engine = SetupEngine(backgroundScope, backend, FakeRpc(), config = config)
        try {
            engine.start(options)
            runCurrent()
            assertEquals(SetupStep.OOBE, engine.state.value.currentStep)
            assertEquals(SetupStepState.SKIPPED, engine.state.value.progress.single { it.step == SetupStep.CHECKIN }.state)
            assertTrue(engine.state.value.watchCompleteObserved)
            assertEquals(SetupPhase.RUNNING, engine.state.value.phase)
            backend.channels.single().emit(SetupProtocol.encodeStatus(5))
            backend.channels.single().incoming.close()
            runCurrent()
            assertEquals(SetupPhase.COMPLETED, engine.state.value.phase)
            assertTrue(engine.state.value.watchOobeDoneObserved)
        } finally { engine.close() }
    }

    @Test fun setupInputEofFailsOnlyTheChannelAndAllowsRetryOnTheReadyLink() = runTest {
        val backend = FakeBackend().apply { setupCapabilities = emptyList() }
        val logs = mutableListOf<ProtocolDiagnostic>()
        val engine = SetupEngine(backgroundScope, backend, FakeRpc(), diagnostics = DiagnosticSink(logs::add), config = config)
        try {
            engine.start(options.copy(termsAccepted = null))
            runCurrent()
            assertEquals(SetupPhase.WAITING_FOR_USER, engine.state.value.phase)
            backend.channels.single().incoming.close()
            runCurrent()
            assertEquals(SetupPhase.FAILED, engine.state.value.phase)
            assertEquals(SetupFailure(SetupFailureKind.CHANNEL_CLOSED, "setup_input_ended"), engine.state.value.failure)
            assertEquals(ConnectionPhase.LINK_READY, backend.state.value.phase)
            assertEquals("LINK_READY", logs.single { it.event == "input_ended" }.details["linkPhase"])
            assertTrue(logs.any { it.event == "watch_status_received" && it.details["status"] == "1" })
            val retry = async { engine.probe() }
            runCurrent()
            assertEquals(1, retry.await().watchStatusValue)
            assertEquals(SetupPhase.PROBED, engine.state.value.phase)
            assertEquals(listOf(7L, 7L), backend.channels.map { it.info.sessionId })
        } finally { engine.close() }
    }

    @Test fun systemInfoWithoutCapabilityVersionCannotEnableOptionalRpcFeatures() = runTest {
        val backend = FakeBackend().apply { setupCapabilityVersion = null }
        val rpc = FakeRpc()
        val engine = SetupEngine(backgroundScope, backend, rpc, config = config)
        try {
            engine.start(checkinOptions)
            runCurrent()
            assertEquals(SetupPhase.COMPLETED, engine.state.value.phase)
            assertTrue(rpc.requests.isEmpty())
            assertEquals(emptyList(), engine.state.value.probe?.systemInfo?.effectiveSetupCapabilities)
        } finally { engine.close() }
    }

    @Test fun factoryResetFlagAndOpaqueEmptyTetherReplyDoNotOverrideKnownSuccessStatus() = runTest {
        val backend = FakeBackend()
        val rpc = FakeRpc().apply { checkin = 2; factoryReset = true; tetherReply = byteArrayOf(0) }
        val logs = mutableListOf<ProtocolDiagnostic>()
        val engine = SetupEngine(backgroundScope, backend, rpc, diagnostics = DiagnosticSink(logs::add), config = config)
        try {
            engine.start(checkinOptions)
            runCurrent()
            assertEquals(SetupPhase.COMPLETED, engine.state.value.phase)
            assertEquals(SetupCheckinStatus(2, true), engine.state.value.checkin)
            assertTrue(logs.any { it.event == "watch_checkin_factory_reset_flag" })
            assertTrue(logs.any { it.event == "tether_response_opaque" })
        } finally { engine.close() }
    }

    @Test fun oobeAloneCanFinishWithoutClaimingAnUnseenCompletePacket() = runTest {
        val backend = FakeBackend().apply { setupCapabilities = listOf(6); completionReplyStatus = 5 }
        val engine = SetupEngine(backgroundScope, backend, FakeRpc(), config = config)
        try {
            engine.start(options)
            runCurrent()
            assertEquals(SetupPhase.COMPLETED, engine.state.value.phase)
            assertFalse(engine.state.value.watchCompleteObserved)
            assertTrue(engine.state.value.watchOobeDoneObserved)
        } finally { engine.close() }
    }

    private inner class FakeBackend : ChannelBackend {
        val link = MutableStateFlow(ConnectionState(ConnectionPhase.LINK_READY, peer, sessionId = 7))
        override val state: StateFlow<ConnectionState> = link
        override val events = MutableSharedFlow<BackendEvent>()
        val channels = mutableListOf<FakeChannel>()
        var fragment = false
        var setupCapabilities = listOf(2, 5)
        var setupCapabilityVersion: Int? = 1
        var acknowledgeComplete = true
        var completionReplyStatus = 3
        var initialStatus = 1
        var oobeAfterComplete = false
        var corruptStatusPrelude = false
        var omitStatusType = false
        var extraStatusBody = false
        var watchRequestsTime = false
        override suspend fun openChannel(path: String, allowOverMetered: Boolean): WearChannel {
            assertEquals(SetupProtocol.CHANNEL_PATH, path)
            val channel = FakeChannel(ChannelInfo(peer, link.value.sessionId, (channels.size + 1).toULong(), true, path))
            channel.onWrite = { message ->
                when (message["type"]) {
                    1 -> when ((message["command"] as DecodedStruct)["type"]) {
                        1 -> {
                            if (watchRequestsTime) channel.emit(GmsStructs.encode("SetupMessage", mapOf("type" to 1, "command" to mapOf("type" to 4))))
                            if (corruptStatusPrelude) channel.emit(byteArrayOf(0x22, 5, 1))
                            channel.emit(if (omitStatusType) GmsStructs.encode("SetupMessage", mapOf("status" to mapOf("state" to initialStatus), "systemInfo" to if (extraStatusBody) mapOf("version" to 4) else null)) else SetupProtocol.encodeStatus(initialStatus))
                        }
                        2 -> channel.emit(GmsStructs.encode("SetupMessage", mapOf("type" to if (omitStatusType) null else 4, "systemInfo" to mapOf("version" to 4, "capabilities" to listOf(1, 4), "setupCapabilities" to setupCapabilities, "setupCapabilityVersion" to setupCapabilityVersion))))
                        // Reference non-cellular provider returns null; the watch's builder then
                        // fails and its setup stream can end. No normal initialization needs it.
                        5 -> channel.incoming.close()
                    }
                    3 -> if (acknowledgeComplete) {
                        channel.emit(SetupProtocol.encodeStatus(completionReplyStatus))
                        if (oobeAfterComplete) channel.emit(SetupProtocol.encodeStatus(5))
                    }
                }
            }
            channels += channel
            return channel
        }
        override suspend fun acceptChannel(): WearChannel = error("not used")
        override suspend fun connect(peer: PeerAddress) = Unit
        override suspend fun disconnect() = Unit
        override suspend fun sendMessage(path: String, payload: ByteArray): TransmissionReceipt = error("RPC seam must be used")
        override fun close() = channels.forEach { it.close() }

        inner class FakeChannel(override val info: ChannelInfo) : WearChannel {
            override val state = MutableStateFlow(ChannelState(ChannelPhase.OPEN))
            val incoming = Channel<ByteArray>(Channel.UNLIMITED)
            val sent = mutableListOf<DecodedStruct>()
            var onWrite: suspend (DecodedStruct) -> Unit = {}
            var closed = false
            override suspend fun read(maxBytes: Int): ByteArray? = incoming.receiveCatching().getOrNull()
            override suspend fun write(bytes: ByteArray) {
                check(!closed)
                val decoder = SetupFrameDecoder()
                val messages = decoder.push(bytes)
                decoder.finish()
                for (payload in messages) {
                    val decoded = SetupProtocol.decode(payload)
                    sent += decoded
                    onWrite(decoded)
                }
            }
            fun emit(payload: ByteArray) {
                val bytes = SetupProtocol.frame(payload)
                if (fragment) bytes.forEach { incoming.trySend(byteArrayOf(it)).getOrThrow() }
                else incoming.trySend(bytes).getOrThrow()
            }
            override suspend fun finishOutput() = Unit
            override fun closeInput() { incoming.close() }
            override suspend fun closeGracefully(errorCode: Int) = close()
            override fun close() { closed = true; incoming.close(); state.value = ChannelState(ChannelPhase.CLOSED, endReason = ChannelEndReason.LOCAL_CLOSE) }
        }
    }

    private class FakeRpc : SetupRpcTransport {
        val requests = mutableListOf<Pair<String, ByteArray>>()
        var checkin = 3
        var factoryReset: Boolean? = null
        var checkinResponseGate: CompletableDeferred<Unit>? = null
        var cancelledCheckinRequests = 0
        var tetherReply = byteArrayOf()
        var onRestoreSkipped: () -> Unit = {}
        private val handlers = mutableMapOf<String, suspend (SetupRpcRequest) -> ByteArray>()
        override suspend fun request(peer: PeerAddress, sessionId: Long, path: String, payload: ByteArray, timeoutMillis: Long): ByteArray {
            requests += path to payload.copyOf()
            if (path == SetupProtocol.RESTORE_STATUS_PATH) {
                assertContentEquals(byteArrayOf(0x08, 0x01), payload)
                onRestoreSkipped()
                return byteArrayOf()
            }
            if (path == SetupProtocol.CHECKIN_REQUEST_PATH) {
                try { checkinResponseGate?.await() }
                catch (cancelled: CancellationException) { cancelledCheckinRequests++; throw cancelled }
                return GmsStructs.encode("WatchGmsCheckinStatus", mapOf("status" to checkin, "factoryReset" to factoryReset))
            }
            return tetherReply.copyOf()
        }
        override fun registerRequestHandler(path: String, handler: suspend (SetupRpcRequest) -> ByteArray): AutoCloseable {
            handlers[path] = handler
            return AutoCloseable { handlers.remove(path) }
        }
        suspend fun update(peer: PeerAddress, session: Long, value: Int): ByteArray = handlers.getValue(SetupProtocol.CHECKIN_UPDATE_PATH)(
            SetupRpcRequest(peer, session, GmsStructs.encode("WatchGmsCheckinStatus", mapOf("status" to value))))
    }
}
