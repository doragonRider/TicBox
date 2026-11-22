@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.ticbox.companion.engine.features

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.finding.*
import dev.ticbox.companion.protocol.*
import kotlin.test.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*

class FindDevicesEngineTest {
    private class Ringer : PhoneRinger {
        override val state = MutableStateFlow(PhoneRingingState())
        var starts = 0; var stops = 0; var available = true
        override suspend fun start() { starts++; state.value = PhoneRingingState(active = available, audioPlaybackStarted = available) }
        override suspend fun stop() { stops++; state.value = PhoneRingingState() }
        override fun close() { state.value = PhoneRingingState() }
    }
    @Test fun `find phone publishes the observed static GMS capability with double URI encoding`() = runTest {
        val data = FeatureTestData(); val ringer = Ringer()
        val engine = FindDevicesEngine(backgroundScope, data, ringer)
        engine.attach(data.session()); runCurrent()
        val item = data.published.single()
        assertEquals(FindDevicesEngine.GMS_ROUTE, item.route)
        assertEquals("wear://phone${FindDevicesEngine.CAPABILITY_PATH}", item.uri)
        assertContentEquals(byteArrayOf(0x73), item.data)
        assertTrue(engine.state.value.capabilityPublished)
        engine.close()
    }
    @Test fun `watch request ignores unexpected payload and timed stop returns actual native state`() = runTest {
        val data = FeatureTestData(); val ringer = Ringer(); val diagnostics = mutableListOf<ProtocolDiagnostic>()
        val engine = FindDevicesEngine(backgroundScope, data, ringer, diagnostics = DiagnosticSink { diagnostics += it }, ringMillis = 50)
        engine.attach(data.session()); runCurrent()
        assertTrue(engine.handleMessage(data.scope.peer, 1, FindDevicesEngine.ACTION_RING, "private ignored text".toByteArray(), FindDevicesEngine.GMS_ROUTE))
        runCurrent()
        assertTrue(engine.state.value.phone.audioPlaybackStarted)
        assertEquals(FindDevicesEngine.RING_STARTED, data.sent.last().second)
        assertEquals(FindDevicesEngine.GMS_ROUTE, data.sent.last().first)
        assertTrue(data.sent.last().third.isEmpty())
        advanceTimeBy(51); runCurrent()
        assertFalse(ringer.state.value.active)
        assertFalse(engine.state.value.phone.active)
        assertEquals(FindDevicesEngine.RING_CANCELED, data.sent.last().second)
        assertFalse(diagnostics.any { "private ignored text" in it.details.toString() })
        engine.close()
    }
    @Test fun `stale session and other AppKey cannot ring the current phone`() = runTest {
        val data = FeatureTestData(); val ringer = Ringer(); val engine = FindDevicesEngine(backgroundScope, data, ringer)
        engine.attach(data.session(2)); runCurrent()
        assertFalse(engine.handleMessage(data.scope.peer, 1, FindDevicesEngine.ACTION_RING, byteArrayOf()))
        assertFalse(engine.handleMessage(data.scope.peer, 2, FindDevicesEngine.ACTION_RING, byteArrayOf(), data.route))
        assertEquals(0, ringer.starts); engine.close()
    }
    @Test fun `disabling withdraws capability stops local sound and rejects new ring requests`() = runTest {
        val data = FeatureTestData(); val ringer = Ringer(); val engine = FindDevicesEngine(backgroundScope, data, ringer)
        engine.attach(data.session()); runCurrent()
        engine.handleMessage(data.scope.peer, 1, FindDevicesEngine.ACTION_RING, byteArrayOf()); runCurrent()
        engine.setEnabled(false); runCurrent()
        assertFalse(ringer.state.value.active)
        assertTrue(data.published.last().deleted)
        engine.handleMessage(data.scope.peer, 1, FindDevicesEngine.ACTION_RING, byteArrayOf()); runCurrent()
        assertEquals(1, ringer.starts)
        assertEquals(FindDevicesEngine.RING_CANCELED, data.sent.last().second)
        engine.close()
    }
    @Test fun `disconnect stops audio and its timeout cannot acknowledge a replacement peer session`() = runTest {
        val data = FeatureTestData(); val ringer = Ringer(); val engine = FindDevicesEngine(backgroundScope, data, ringer, ringMillis = 50)
        engine.attach(data.session()); runCurrent()
        engine.handleMessage(data.scope.peer, 1, FindDevicesEngine.ACTION_RING, byteArrayOf())
        engine.detach(1); engine.attach(data.session(2)); runCurrent()
        val replies = data.sent.size
        advanceTimeBy(100); runCurrent()
        assertEquals(replies, data.sent.size); assertFalse(ringer.state.value.active)
        engine.detach(1); assertEquals(2, engine.state.value.sessionId)
        engine.close()
    }
    @Test fun `no available native ringer never sends a started acknowledgement`() = runTest {
        val data = FeatureTestData(); val ringer = Ringer().apply { available = false }
        val engine = FindDevicesEngine(backgroundScope, data, ringer)
        engine.attach(data.session()); runCurrent()
        engine.handleMessage(data.scope.peer, 1, FindDevicesEngine.ACTION_RING, byteArrayOf()); runCurrent()
        assertEquals(listOf(FindDevicesEngine.RING_CANCELED), data.sent.map { it.second })
        assertFalse(engine.state.value.phone.active); engine.close()
    }
    @Test fun `native user dismissal reports canceled and retires the timeout`() = runTest {
        val data = FeatureTestData(); val ringer = Ringer(); val engine = FindDevicesEngine(backgroundScope, data, ringer, ringMillis = 50)
        engine.attach(data.session()); runCurrent()
        engine.handleMessage(data.scope.peer, 1, FindDevicesEngine.ACTION_RING, byteArrayOf()); runCurrent()
        ringer.state.value = PhoneRingingState(); runCurrent()
        assertEquals(FindDevicesEngine.RING_CANCELED, data.sent.last().second)
        val replies = data.sent.size; advanceTimeBy(100); runCurrent()
        assertEquals(replies, data.sent.size); engine.close()
    }
    private val ownRoute = ApplicationRoute("dev.ticbox.companion", "own-signing-identity")
    private fun requestId(data: FeatureTestData) = RemoteIntentCodec.decode(data.sent.last {
        it.second == DeviceSettingsProtocol.REMOTE_INTENT
    }.third).invocationId
    private suspend fun report(engine: FindDevicesEngine, data: FeatureTestData, id: String, state: String,
        audio: Boolean = false, vibration: Boolean = false, session: Long = 1) = engine.handleMessage(data.scope.peer,
        session, WatchFindingProtocol.STATE_PATH, WatchFindingProtocol.state(id, state, audio, vibration), ownRoute)

    @Test fun `watch launch uses package scoped visible activity and actual feedback before confirming playback`() = runTest {
        val data = FeatureTestData(); val engine = FindDevicesEngine(backgroundScope, data, Ringer(),
            ringMillis = 50, watchRoute = ownRoute)
        engine.attach(data.session()); runCurrent(); engine.ringWatch()
        assertEquals(FindWatchStatus.STARTING, engine.state.value.watchStatus)
        assertFalse(engine.state.value.watchRingingConfirmed)
        assertEquals(data.route, data.sent.last().first)
        val request = RemoteIntentCodec.decode(data.sent.single().third)
        assertEquals(WatchFindingProtocol.ACTION, request.action)
        assertEquals(ownRoute.packageName, request.packageName)
        assertEquals(0, request.effectiveStartMode)
        assertEquals(DataMapValue.Text(data.scope.localNodeId), request.extras[WatchFindingProtocol.SOURCE_NODE])
        assertEquals(DataMapValue.LongNumber(50), request.extras[WatchFindingProtocol.DURATION_MILLIS])
        engine.handleMessage(data.scope.peer, 1, DeviceSettingsProtocol.REMOTE_INTENT_RESULT,
            RemoteIntentCodec.result(request.invocationId, true), data.route)
        assertFalse(engine.state.value.watchRingingConfirmed)
        report(engine, data, request.invocationId, "ringing", vibration = true)
        assertEquals(FindWatchStatus.RINGING, engine.state.value.watchStatus)
        assertTrue(engine.state.value.watchRingingConfirmed)
        assertFalse(engine.state.value.watchAudioPlaybackStarted)
        report(engine, data, request.invocationId, "ringing", audio = true, vibration = true)
        assertTrue(engine.state.value.watchAudioPlaybackStarted)
        advanceTimeBy(51); runCurrent()
        assertEquals(listOf(DeviceSettingsProtocol.REMOTE_INTENT, WatchFindingProtocol.STOP_PATH), data.sent.map { it.second })
        assertEquals(ownRoute, data.sent.last().first)
        assertEquals(request.invocationId, WatchFindingProtocol.text(WatchFindingProtocol.parse(data.sent.last().third), "requestId"))
        assertEquals(FindWatchStatus.STOPPING, engine.state.value.watchStatus)
        assertTrue(engine.state.value.watchRingingConfirmed)
        report(engine, data, request.invocationId, "stopped")
        assertEquals(FindWatchStatus.STOPPED, engine.state.value.watchStatus)
        assertFalse(engine.state.value.watchRingingConfirmed); engine.close()
    }
    @Test fun `missing watch component reports unavailable instead of a successful ring`() = runTest {
        val data = FeatureTestData(); val engine = FindDevicesEngine(backgroundScope, data, Ringer(), watchRoute = ownRoute)
        engine.attach(data.session()); engine.ringWatch()
        engine.handleMessage(data.scope.peer, 1, DeviceSettingsProtocol.REMOTE_INTENT_RESULT,
            RemoteIntentCodec.result(requestId(data), false), data.route)
        assertEquals(FindWatchStatus.UNAVAILABLE, engine.state.value.watchStatus)
        assertFalse(engine.state.value.watchRingingConfirmed)
        assertEquals(1, data.sent.size); engine.close()
    }
    @Test fun `lost watch start response times out and cancels the possibly delayed activity`() = runTest {
        var failStop = true
        val data = FeatureTestData().apply { onSend = { _, path, _ ->
            if (path == WatchFindingProtocol.STOP_PATH && failStop) {
                failStop = false; throw java.io.IOException("fixture disconnected during stop write")
            }
        } }
        val engine = FindDevicesEngine(backgroundScope, data, Ringer(),
            watchRoute = ownRoute, watchResponseMillis = 20)
        engine.attach(data.session()); engine.ringWatch(); val id = requestId(data)
        advanceTimeBy(21); runCurrent()
        assertEquals(FindWatchStatus.TIMED_OUT, engine.state.value.watchStatus)
        assertEquals(WatchFindingProtocol.STOP_PATH, data.sent.last().second)
        assertEquals(ownRoute, data.sent.last().first)
        assertTrue(engine.state.value.watchStopAvailable)
        assertTrue(report(engine, data, id, "ringing", audio = true))
        assertFalse(engine.state.value.watchRingingConfirmed)
        assertEquals(FindWatchStatus.TIMED_OUT, engine.state.value.watchStatus)
        engine.stopWatch()
        assertEquals(3, data.sent.count { it.second == WatchFindingProtocol.STOP_PATH })
        report(engine, data, id, "stopped")
        assertEquals(FindWatchStatus.STOPPED, engine.state.value.watchStatus)
        assertFalse(engine.state.value.watchStopAvailable)
        assertTrue(engine.state.value.capabilityPublished); engine.close()
    }
    @Test fun `stop while activity is starting cannot be overwritten by late ringing feedback`() = runTest {
        val data = FeatureTestData(); val engine = FindDevicesEngine(backgroundScope, data, Ringer(), watchRoute = ownRoute)
        engine.attach(data.session()); engine.ringWatch(); val id = requestId(data)
        engine.stopWatch()
        report(engine, data, id, "ringing", audio = true)
        assertEquals(FindWatchStatus.STOPPING, engine.state.value.watchStatus)
        assertFalse(engine.state.value.watchRingingConfirmed)
        assertEquals(2, data.sent.count { it.second == WatchFindingProtocol.STOP_PATH })
        report(engine, data, id, "stopped")
        assertEquals(FindWatchStatus.STOPPED, engine.state.value.watchStatus)
        assertFalse(report(engine, data, id, "ringing", audio = true)); engine.close()
    }
    @Test fun `wrong identity retired session and replaced request cannot confirm watch playback`() = runTest {
        val data = FeatureTestData(); val engine = FindDevicesEngine(backgroundScope, data, Ringer(), watchRoute = ownRoute)
        engine.attach(data.session()); engine.ringWatch(); val old = requestId(data)
        assertFalse(engine.handleMessage(data.scope.peer, 1, WatchFindingProtocol.STATE_PATH,
            WatchFindingProtocol.state(old, "ringing", true), data.route))
        engine.ringWatch(); val id = requestId(data)
        assertNotEquals(old, id)
        assertFalse(report(engine, data, old, "ringing", audio = true))
        engine.detach(1); engine.attach(data.session(2))
        assertFalse(report(engine, data, id, "ringing", audio = true))
        assertEquals(FindWatchStatus.IDLE, engine.state.value.watchStatus)
        engine.close()
    }
    @Test fun `unknown fields and version retain usable watch feedback while malformed messages keep waiting`() = runTest {
        val data = FeatureTestData(); val logs = mutableListOf<ProtocolDiagnostic>()
        val engine = FindDevicesEngine(backgroundScope, data, Ringer(), diagnostics = DiagnosticSink { logs += it }, watchRoute = ownRoute)
        engine.attach(data.session()); engine.ringWatch(); val id = requestId(data)
        engine.handleMessage(data.scope.peer, 1, WatchFindingProtocol.STATE_PATH, "not JSON".toByteArray(), ownRoute)
        assertEquals(FindWatchStatus.STARTING, engine.state.value.watchStatus)
        val bytes = """{"version":9,"requestId":"$id","state":"ringing","audioPlaybackStarted":"1","extra":{"future":true}}""".toByteArray()
        engine.handleMessage(data.scope.peer, 1, WatchFindingProtocol.STATE_PATH, bytes, ownRoute)
        assertTrue(engine.state.value.watchAudioPlaybackStarted)
        engine.handleMessage(data.scope.peer, 1, WatchFindingProtocol.STATE_PATH,
            """{"requestId":"$id","state":"ringing","extra":1}""".toByteArray(), ownRoute)
        assertTrue(engine.state.value.watchAudioPlaybackStarted)
        assertTrue(logs.any { it.event == "watch_state_unreadable" })
        assertTrue(logs.any { it.event == "watch_state_received" && it.details["version"] == "9" })
        engine.close()
    }
    @Test fun `watch playback failure and stop timeout remain visible without resetting the link`() = runTest {
        val data = FeatureTestData(); val engine = FindDevicesEngine(backgroundScope, data, Ringer(), watchRoute = ownRoute, watchResponseMillis = 20)
        engine.attach(data.session()); engine.ringWatch()
        report(engine, data, requestId(data), "failed")
        assertEquals(FindWatchStatus.FAILED, engine.state.value.watchStatus)
        engine.ringWatch(); val id = requestId(data)
        report(engine, data, id, "ringing", audio = true)
        engine.stopWatch(); advanceTimeBy(21); runCurrent()
        assertEquals(FindWatchStatus.TIMED_OUT, engine.state.value.watchStatus)
        assertTrue(engine.state.value.watchAudioPlaybackStarted)
        report(engine, data, id, "stopped")
        assertEquals(FindWatchStatus.STOPPED, engine.state.value.watchStatus)
        assertEquals(1, engine.state.value.sessionId); engine.close()
    }
    @Test fun `disabling finding cancels a pending watch launch and still consumes its stop feedback`() = runTest {
        val data = FeatureTestData(); val engine = FindDevicesEngine(backgroundScope, data, Ringer(), watchRoute = ownRoute)
        engine.attach(data.session()); engine.ringWatch(); val id = requestId(data)
        engine.setEnabled(false)
        assertEquals(WatchFindingProtocol.STOP_PATH, data.sent.last().second)
        report(engine, data, id, "stopped")
        assertEquals(FindWatchStatus.STOPPED, engine.state.value.watchStatus)
        assertFalse(engine.state.value.settings.enabled); engine.close()
    }
    @Test fun `cancelling the caller during submission still cancels a possibly launched watch activity`() = runTest {
        val data = FeatureTestData().apply { onSend = { _, path, _ ->
            if (path == DeviceSettingsProtocol.REMOTE_INTENT) awaitCancellation()
        } }
        val engine = FindDevicesEngine(backgroundScope, data, Ringer(), watchRoute = ownRoute)
        engine.attach(data.session())
        val command = launch { engine.ringWatch() }; runCurrent()
        val id = requestId(data)
        command.cancel(); runCurrent()
        assertEquals(FindWatchStatus.STOPPING, engine.state.value.watchStatus)
        assertEquals(WatchFindingProtocol.STOP_PATH, data.sent.last().second)
        report(engine, data, id, "stopped")
        assertEquals(FindWatchStatus.STOPPED, engine.state.value.watchStatus); engine.close()
    }
    @Test fun `persisted disabled setting withdraws an old capability when the data link resumes`() = runTest {
        val data = FeatureTestData(); val store = MemoryFindDeviceSettingsStore().apply { save(FindDeviceSettings(false)) }
        val engine = FindDevicesEngine(backgroundScope, data, Ringer(), store)
        engine.attach(data.session()); runCurrent()
        assertTrue(data.published.single().deleted)
        assertFalse(engine.state.value.capabilityPublished)
        engine.close()
    }
    @Test fun `late attachment of a retired session cannot replace the new ringer owner`() = runTest {
        val data = FeatureTestData(); val ringer = Ringer(); val engine = FindDevicesEngine(backgroundScope, data, ringer)
        engine.attach(data.session()); engine.detach(1); engine.attach(data.session(2))
        engine.attach(data.session(1)); runCurrent()
        assertEquals(2, engine.state.value.sessionId)
        assertTrue(engine.handleMessage(data.scope.peer, 2, FindDevicesEngine.ACTION_RING, byteArrayOf()))
        assertTrue(engine.state.value.phone.active)
        engine.close()
    }
}
