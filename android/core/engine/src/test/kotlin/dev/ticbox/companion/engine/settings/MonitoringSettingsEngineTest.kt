@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.ticbox.companion.engine.settings

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

class MonitoringSettingsEngineTest {
    private val link = MonitoringLink(PeerAddress("AA:BB:CC:DD:EE:01"), 7)
    private fun reply(values: Map<String, String?> = emptyMap(), outcome: String = "READ", version: Long = 50_603_234L) = buildJsonObject {
        put("outcome", outcome); put("writable", true)
        putJsonObject("source") { put("packageName", HealthMonitoringProtocol.PACKAGE); put("versionName", "5.6.0-3234.2686"); put("versionCode", version) }
        putJsonArray("settings") { HealthMonitoringProtocol.SETTINGS.forEach { spec -> add(buildJsonObject { put("id", spec.id); put("value", values[spec.id]) }) } }
    }
    @Test fun attachAndReconnectNeverStartRootOrReplaySettings() = runTest {
        var calls = 0
        val engine = MonitoringSettingsEngine(backgroundScope, MonitoringRemote { _, _ -> calls++; reply() })
        engine.attach(link); runCurrent(); assertEquals(0, calls)
        assertEquals(MonitoringCommandStatus.READ, engine.refresh().status)
        assertTrue(engine.state.value.current)
        engine.attach(null); engine.attach(link.copy(sessionId = 8)); runCurrent()
        assertEquals(1, calls); assertFalse(engine.state.value.current); assertTrue(engine.state.value.settings.isEmpty())
        engine.close()
    }
    @Test fun typedValidationHappensBeforeConnectingAndProviderUsesFixedIdentifiers() = runTest {
        val requests = mutableListOf<JsonObject>()
        val engine = MonitoringSettingsEngine(backgroundScope, MonitoringRemote { selected, request ->
            assertEquals(link, selected); requests += request; reply(outcome = "STORED_AND_NOTIFIED")
        })
        engine.attach(link)
        assertEquals(MonitoringCommandStatus.INVALID_ARGUMENT, engine.set(MonitoringSettingId.SLEEP, MonitoringValue.Text("true")).status)
        assertEquals(MonitoringCommandStatus.INVALID_ARGUMENT, engine.set(MonitoringSettingId.SLEEP_BLOOD_OXYGEN_INTERVAL, MonitoringValue.Number(1)).status)
        assertTrue(requests.isEmpty())
        assertEquals(MonitoringCommandStatus.STORED_AND_NOTIFIED, engine.set(MonitoringSettingId.SLEEP_BLOOD_OXYGEN_INTERVAL, MonitoringValue.Number(60)).status)
        assertEquals(buildJsonObject { put("id", "SLEEP_BLOOD_OXYGEN_INTERVAL"); put("value", "60") }, requests.single())
        engine.close()
    }
    @Test fun lateOldSessionResultCannotBecomeTheNewWatchSettings() = runTest {
        val pending = CompletableDeferred<JsonObject>()
        val engine = MonitoringSettingsEngine(backgroundScope, MonitoringRemote { _, _ -> withContext(NonCancellable) { pending.await() } })
        engine.attach(link)
        val first = async { engine.refresh() }; runCurrent()
        engine.attach(link.copy(peer = PeerAddress("AA:BB:CC:DD:EE:02"), sessionId = 8))
        pending.complete(reply(mapOf("BACKGROUND_HEART_RATE" to "true"))); runCurrent()
        assertEquals(MonitoringCommandStatus.INTERRUPTED, first.await().status)
        assertEquals(8L, engine.state.value.sessionId); assertFalse(engine.state.value.current); assertNull(engine.state.value.source)
        engine.close()
    }
    @Test fun callerCancellationClosesTheOperationAndDoesNotLeaveWritingState() = runTest {
        var cancelled = false
        val engine = MonitoringSettingsEngine(backgroundScope, MonitoringRemote { _, _ -> try { awaitCancellation() } finally { cancelled = true } })
        engine.attach(link)
        val write = launch { engine.set(MonitoringSettingId.PRESSURE, MonitoringValue.Toggle(true)) }
        runCurrent(); assertEquals(MonitoringPhase.WRITING, engine.state.value.phase)
        write.cancelAndJoin(); runCurrent()
        assertTrue(cancelled); assertEquals(MonitoringPhase.READY, engine.state.value.phase); assertFalse(engine.state.value.current)
        engine.close()
    }
    @Test fun unknownFirmwareRemainsReadableButDoesNotBecomeAnApprovedWritableVersion() {
        val (_, settings) = MonitoringSettingsCodec.decode(reply(mapOf("BACKGROUND_HEART_RATE_MODE" to "2"), version = 999))
        assertTrue(settings.all { !it.descriptor.writable })
        val mode = settings.single { it.descriptor.id == MonitoringSettingId.BACKGROUND_HEART_RATE_MODE }
        assertEquals(MonitoringValue.Number(2), mode.storedValue); assertTrue(mode.unrecognizedStoredValue)
        assertNull(settings.single { it.descriptor.id == MonitoringSettingId.BACKGROUND_HEART_RATE }.effectiveValue)
        assertNull(settings.single { it.descriptor.id == MonitoringSettingId.PRESSURE }.effectiveValue)
        assertEquals(HealthMonitoringProtocol.SETTINGS.map { it.id }.toSet(), MonitoringSettingId.entries.map { it.name }.toSet())
    }
    @Test fun partialFailureCannotBePresentedAsConfirmedSettings() = runTest {
        val engine = MonitoringSettingsEngine(backgroundScope, MonitoringRemote { _, _ -> reply(outcome = "STORED_UNVERIFIED") })
        engine.attach(link)
        assertEquals(MonitoringCommandStatus.STORED_UNVERIFIED, engine.set(MonitoringSettingId.HEART_HEALTH, MonitoringValue.Toggle(true)).status)
        assertFalse(engine.state.value.current); engine.close()
    }
    @Test fun lostReplyAfterAWriteRemainsUnconfirmedAndIsNeverRetried() = runTest {
        var writes = 0
        val engine = MonitoringSettingsEngine(backgroundScope, MonitoringRemote { _, _ ->
            writes++; throw IOException("transport ended after commit")
        })
        engine.attach(link)
        assertEquals(MonitoringCommandStatus.STORED_UNVERIFIED, engine.set(MonitoringSettingId.PRESSURE, MonitoringValue.Toggle(true)).status)
        runCurrent(); assertEquals(1, writes); assertFalse(engine.state.value.current)
        engine.close()
    }
    @Test fun operationTimeoutDoesNotPretendThatThePhysicalWatchChanged() = runTest {
        val engine = MonitoringSettingsEngine(backgroundScope, MonitoringRemote { _, _ -> withTimeout(10) { delay(20); reply() } })
        engine.attach(link)
        val result = engine.set(MonitoringSettingId.PRESSURE, MonitoringValue.Toggle(true))
        assertEquals(MonitoringCommandStatus.STORED_UNVERIFIED, result.status); assertEquals("monitoring_timeout", result.reason)
        assertEquals(link.sessionId, engine.state.value.sessionId); assertEquals(MonitoringPhase.READY, engine.state.value.phase)
        assertFalse(engine.state.value.current); engine.close()
    }
    @Test fun closeIsTerminalEvenWhenAnOldRemoteCallFinishesAndTheLinkAttachesAgain() = runTest {
        val pending = CompletableDeferred<JsonObject>()
        val engine = MonitoringSettingsEngine(backgroundScope, MonitoringRemote { _, _ -> withContext(NonCancellable) { pending.await() } })
        engine.attach(link)
        val read = async { engine.refresh() }; runCurrent()
        engine.close()
        engine.attach(link.copy(sessionId = 8))
        pending.complete(reply(mapOf("PRESSURE" to "true"))); runCurrent()
        assertEquals(MonitoringCommandStatus.INTERRUPTED, read.await().status)
        assertEquals(MonitoringSettingsState(phase = MonitoringPhase.CLOSED), engine.state.value)
        assertEquals(MonitoringCommandStatus.UNAVAILABLE, engine.refresh().status)
    }
}
