package dev.ticbox.companion.engine.calling

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.CompanionFeatureSession
import dev.ticbox.companion.protocol.*
import java.io.IOException
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionCallingEngineTest {
    private val peer = PeerAddress("01:23:45:67:89:AB")
    private val route = ApplicationRoute("com.mobvoi.companion.at", "5cc9866db03e530a21f842a421c7eb42bac58961")
    private data class Sent(val route: ApplicationRoute, val path: String, val bytes: ByteArray)
    private fun session(sent: MutableList<Sent>, id: Long = 1) = CompanionFeatureSession(id, DataScope(peer, "local", "watch"), route,
        send = { r, path, bytes -> sent += Sent(r, path, bytes) }, request = { _, _, _, _ -> error("CallApi uses ordinary Message") },
        open = { _, _ -> error("CallApi does not open Channels") })

    @Test fun attachResetsThenPublishesRealCallsAndMuteWithoutBusinessAck() = runTest {
        val platform = Platform().apply { state.value = state.value.copy(calls = listOf(CompanionCall("a", 2)), muted = false) }
        val sent = mutableListOf<Sent>()
        val engine = CompanionCallingEngine(backgroundScope, platform)
        engine.attach(session(sent)); runCurrent()
        assertEquals(listOf(CallProtocol.RESET, CallProtocol.CALL, CallProtocol.AUDIO_STATE), sent.map { it.path })
        assertTrue(sent.all { it.route == route })
        engine.handleMessage(peer, 1, CallProtocol.ACTION_REQUEST, CallCodec.action("a", 1), route)
        assertEquals(listOf<CallCommand>(CallCommand.Action("a", 1)), platform.commands)
        assertEquals(3, sent.size)
        assertEquals(2, engine.state.value.calls.single().stateValue) // Dispatch cannot invent ACTIVE.
        assertEquals(CallCommandStatus.DISPATCHED, engine.state.value.lastCommandResult?.status)
        engine.close()
    }

    @Test fun stalePeerSessionAndOtherRouteNeverInvokeNativeCommands() = runTest {
        val platform = Platform()
        val engine = CompanionCallingEngine(backgroundScope, platform)
        engine.attach(session(mutableListOf()))
        engine.handleMessage(peer, 99, CallProtocol.DIAL_REQUEST, CallCodec.dial("123"), route)
        engine.handleMessage(PeerAddress("01:23:45:67:89:CD"), 1, CallProtocol.DIAL_REQUEST, CallCodec.dial("123"), route)
        engine.handleMessage(peer, 1, CallProtocol.DIAL_REQUEST, CallCodec.dial("123"), ApplicationRoute("other", "digest"))
        assertTrue(platform.commands.isEmpty())
        engine.close()
    }

    @Test fun decoderUsesLastKnownValuesAndDoesNotRejectUnrecognizedAdditionalFields() = runTest {
        val platform = Platform()
        val records = mutableListOf<ProtocolDiagnostic>()
        val engine = CompanionCallingEngine(backgroundScope, platform, DiagnosticSink(records::add))
        engine.attach(session(mutableListOf()))
        engine.handleMessage(peer, 1, CallProtocol.ACTION_REQUEST, CallCodec.action("id", 1) + CallCodec.action("id", 8) + byteArrayOf(0x98.toByte(), 6, 1))
        assertEquals(CallCommand.Action("id", 8), platform.commands.single())
        assertTrue(records.any { it.event == "compat_decode" })
        assertFalse(records.any { it.details.values.any { value -> value.contains("123456") } })
        engine.close()
    }

    @Test fun invalidDtmfUnknownActionAndMalformedPayloadNeverReachPlatform() = runTest {
        val platform = Platform()
        val engine = CompanionCallingEngine(backgroundScope, platform)
        engine.attach(session(mutableListOf()))
        engine.handleMessage(peer, 1, CallProtocol.DTMF_REQUEST, CallCodec.dtmf("a", "12"))
        assertEquals(CallCommandStatus.MALFORMED, engine.state.value.lastCommandResult?.status)
        engine.handleMessage(peer, 1, CallProtocol.ACTION_REQUEST, CallCodec.action("a", 77))
        assertEquals(CallCommandStatus.UNSUPPORTED, engine.state.value.lastCommandResult?.status)
        engine.handleMessage(peer, 1, CallProtocol.DIAL_REQUEST, byteArrayOf(10, 8, 1))
        assertEquals(CallCommandStatus.MALFORMED, engine.state.value.lastCommandResult?.status)
        assertTrue(platform.commands.isEmpty())
        engine.close()
    }

    @Test fun nativeFailureIsTypedAndCannotBecomeCallOrAudioSuccess() = runTest {
        val platform = Platform().apply { problem = SecurityException("hidden handset content") }
        val records = mutableListOf<ProtocolDiagnostic>()
        val engine = CompanionCallingEngine(backgroundScope, platform, DiagnosticSink(records::add))
        engine.attach(session(mutableListOf()))
        val result = engine.execute(CallCommand.SelectAudioEndpoint("opaque"))
        assertEquals(CallCommandStatus.UNAVAILABLE, result.status)
        assertNull(engine.state.value.currentEndpointId)
        assertFalse(records.any { it.details.values.any { value -> value.contains("hidden handset") } })
        engine.close()
    }

    @Test fun disableWithdrawsWatchCacheAndPreventsFurtherCommandsUntilEnabled() = runTest {
        val platform = Platform()
        val sent = mutableListOf<Sent>()
        val engine = CompanionCallingEngine(backgroundScope, platform)
        engine.attach(session(sent))
        engine.setEnabled(false)
        assertEquals(2, sent.count { it.path == CallProtocol.RESET })
        engine.handleMessage(peer, 1, CallProtocol.DIAL_REQUEST, CallCodec.dial("123"))
        assertEquals(CallCommandStatus.DISABLED, engine.state.value.lastCommandResult?.status)
        assertTrue(platform.commands.isEmpty())
        engine.setEnabled(true)
        assertEquals(3, sent.count { it.path == CallProtocol.RESET })
        engine.close()
    }

    @Test fun outgoingCancellationBeforeTelecomAddsCallHasStockThreeSecondWindow() = runTest {
        val platform = Platform()
        val engine = CompanionCallingEngine(backgroundScope, platform)
        engine.attach(session(mutableListOf()))
        assertEquals(CallCommandStatus.DEFERRED, engine.execute(CallCommand.Action(actionValue = 2)).status)
        platform.state.value = platform.state.value.copy(calls = listOf(CompanionCall("later", 1)))
        runCurrent()
        assertEquals(CallCommand.Action("later", 2), platform.commands.single())
        engine.close()
    }

    @Test fun expiredOrPreviousSessionCancellationCannotEndNewCalls() = runTest {
        val platform = Platform()
        val engine = CompanionCallingEngine(backgroundScope, platform)
        engine.attach(session(mutableListOf()))
        engine.execute(CallCommand.Action(actionValue = 2))
        advanceTimeBy(3_001); runCurrent()
        platform.state.value = platform.state.value.copy(calls = listOf(CompanionCall("late", 1)))
        runCurrent()
        assertTrue(platform.commands.isEmpty())
        platform.state.value = platform.state.value.copy(calls = emptyList()); runCurrent()
        engine.execute(CallCommand.Action(actionValue = 2))
        engine.attach(session(mutableListOf(), 2))
        platform.state.value = platform.state.value.copy(calls = listOf(CompanionCall("new-session", 9))); runCurrent()
        assertTrue(platform.commands.isEmpty())
        engine.close()
    }

    @Test fun endedOnWatchSuppressesStaleActiveUpdateButObservedRemovalWithdrawsCall() = runTest {
        val platform = Platform().apply { state.value = state.value.copy(calls = listOf(CompanionCall("a", 4))) }
        val sent = mutableListOf<Sent>()
        val engine = CompanionCallingEngine(backgroundScope, platform)
        engine.attach(session(sent)); runCurrent()
        engine.execute(CallCommand.Action("a", 2))
        platform.state.value = platform.state.value.copy(calls = listOf(CompanionCall("a", 4, callerDisplayName = "changed")))
        runCurrent()
        assertEquals(1, sent.count { it.path == CallProtocol.CALL })
        platform.state.value = platform.state.value.copy(calls = emptyList()); runCurrent()
        val terminal = ProtoWire.decodeFields(sent.last { it.path == CallProtocol.CALL }.bytes)
        assertEquals(7uL, terminal.single { it.number == 1 }.value)
        engine.close()
    }

    @Test fun legacyDialIsDecodedAsDataMapAndSilenceCallbackSendsItsObservedPath() = runTest {
        val platform = Platform()
        val sent = mutableListOf<Sent>()
        val engine = CompanionCallingEngine(backgroundScope, platform)
        engine.attach(session(sent)); runCurrent()
        engine.handleMessage(peer, 1, "/phone", DataMapCodec.encode(mapOf("command" to DataMapValue.IntNumber(3), "phone_number" to DataMapValue.Text("123"))))
        assertEquals(CallCommand.Dial("123"), platform.commands.single())
        platform.state.value = platform.state.value.copy(silenceRingerSequence = 1); runCurrent()
        assertEquals(CallProtocol.SILENCE_RINGER, sent.last().path)
        engine.close()
    }

    @Test fun slowNativeCompletionAndQueuedSnapshotCannotPublishIntoReplacedSession() = runTest {
        val platform = Platform()
        val gate = CompletableDeferred<Unit>()
        platform.gate = gate
        val sentOld = mutableListOf<Sent>()
        val sentNew = mutableListOf<Sent>()
        val engine = CompanionCallingEngine(backgroundScope, platform)
        engine.attach(session(sentOld)); runCurrent()
        val command = async { engine.handleMessage(peer, 1, CallProtocol.ACTION_REQUEST, CallCodec.action("a", 1)) }
        runCurrent()
        platform.state.value = platform.state.value.copy(calls = listOf(CompanionCall("new", 2)))
        runCurrent()
        val attach = async { engine.attach(session(sentNew, 2)) }
        runCurrent()
        gate.complete(Unit)
        command.await(); attach.await(); runCurrent()
        assertTrue(sentOld.none { it.path == CallProtocol.CALL })
        assertEquals(listOf(CallProtocol.RESET, CallProtocol.CALL), sentNew.map { it.path })
        assertEquals(CallCommandStatus.STALE_SESSION, engine.state.value.lastCommandResult?.status)
        engine.close()
    }

    @Test fun emptyCallIdAnswerChoosesOnlyOneRingingCallAndUnknownIdNeverSelectsAnother() {
        val ringing = CompanionCall("ring", 2)
        val active = CompanionCall("active", 4)
        assertEquals(ringing, CallTargetSelector.select(listOf(active, ringing), "", answer = true))
        assertNull(CallTargetSelector.select(listOf(ringing, ringing.copy(id = "other")), "", answer = true))
        assertNull(CallTargetSelector.select(listOf(active), "missing"))
        assertEquals(active, CallTargetSelector.select(listOf(active), ""))
        assertTrue(CallTargetSelector.isSuppressedRinging(2, true))
        assertFalse(CallTargetSelector.isSuppressedRinging(2, false))
        assertFalse(CallTargetSelector.isSuppressedRinging(4, true))
    }

    @Test fun disabledPreferenceLoadsBeforeAttachAndRemainsDisabledAfterRecreation() = runTest {
        val store = Settings(false)
        val sent = mutableListOf<Sent>()
        val first = CompanionCallingEngine(backgroundScope, Platform(), settingsStore = store)
        first.attach(session(sent))
        assertTrue(first.state.value.settingsLoaded)
        assertFalse(first.state.value.enabled)
        assertTrue(sent.isEmpty())
        first.setEnabled(true)
        assertEquals(true, store.enabled)
        first.setEnabled(false)
        assertEquals(false, store.enabled)
        first.close()
        val second = CompanionCallingEngine(backgroundScope, Platform(), settingsStore = store)
        val next = mutableListOf<Sent>()
        second.attach(session(next, 2))
        assertFalse(second.state.value.enabled)
        assertTrue(next.isEmpty())
        second.close()
    }

    @Test fun corruptPreferenceDoesNotBrieflyEnableCallingAndWriteFailureIsExposed() = runTest {
        val store = Settings(true).apply { readFailure = IOException("corrupt") }
        val engine = CompanionCallingEngine(backgroundScope, Platform(), settingsStore = store)
        val sent = mutableListOf<Sent>()
        engine.attach(session(sent))
        assertFalse(engine.state.value.enabled)
        assertFalse(engine.state.value.settingsDurable)
        assertTrue(sent.isEmpty())
        engine.setEnabled(true)
        assertTrue(engine.state.value.enabled)
        assertTrue(engine.state.value.settingsDurable)
        store.writeFailure = IOException("disk full")
        engine.setEnabled(false)
        assertFalse(engine.state.value.enabled)
        assertFalse(engine.state.value.settingsDurable)
        assertTrue(engine.state.value.lastIssue!!.startsWith("call_settings_write_"))
        engine.close()
    }

    @Test fun oldDetachCannotClearAlreadyAttachedOrQueuedNewSession() = runTest {
        val platform = Platform()
        val engine = CompanionCallingEngine(backgroundScope, platform)
        engine.attach(session(mutableListOf(), 1))
        val gate = CompletableDeferred<Unit>()
        platform.gate = gate
        val command = async { engine.execute(CallCommand.Action("old", 1)) }
        runCurrent()
        // Both orderings matter: a teardown may be queued before the next attach takes its lock.
        val detach = async { engine.detach(1) }
        runCurrent()
        val attach = async { engine.attach(session(mutableListOf(), 2)) }
        runCurrent()
        gate.complete(Unit)
        command.await(); detach.await(); attach.await()
        assertEquals(2L, engine.state.value.sessionId)
        engine.detach(1)
        assertEquals(2L, engine.state.value.sessionId)
        engine.close()
    }

    private class Settings(var enabled: Boolean?) : CallingSettingsStore {
        var readFailure: Exception? = null
        var writeFailure: Exception? = null
        override suspend fun readEnabled(): Boolean? { readFailure?.let { throw it }; return enabled }
        override suspend fun writeEnabled(enabled: Boolean) {
            writeFailure?.let { throw it }
            this.enabled = enabled; readFailure = null
        }
    }

    private class Platform : CallingPlatform {
        override val state = MutableStateFlow(CallingPlatformState(access = CallingAccess(manageOngoingCalls = true), serviceConnected = true))
        val commands = mutableListOf<CallCommand>()
        var problem: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun refreshAccess() = Unit
        override suspend fun execute(command: CallCommand, watch: PeerAddress?): CallCommandResult {
            problem?.let { throw it }
            gate?.await()
            commands += command
            return CallCommandResult(CallCommandStatus.DISPATCHED, callId = (command as? CallCommand.Action)?.callId)
        }
        override fun close() = Unit
    }
}
