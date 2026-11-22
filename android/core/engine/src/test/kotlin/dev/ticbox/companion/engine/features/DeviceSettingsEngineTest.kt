@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.ticbox.companion.engine.features

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.settings.*
import dev.ticbox.companion.protocol.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class DeviceSettingsEngineTest {
    @Test fun `refresh uses ordinary Mobvoi replies and preserves new info fields and equivalent battery readings`() = runTest {
        val data = FeatureTestData(); val engine = DeviceSettingsEngine(backgroundScope, data)
        engine.attach(data.session()); runCurrent()
        data.onSend = { key, path, _ -> engine.handleMessage(data.scope.peer, 1, path,
            if (path == DeviceSettingsProtocol.WEAR_INFO) """{"wearType":"Enduro","wearVersionNumber":33,"new_field":{"value":1}}""".toByteArray()
            else " \uFEFF\"9e1\" ".toByteArray(), key) }
        assertEquals(DeviceCommandStatus.REMOTE_CONFIRMED, engine.refresh().status)
        assertEquals("Enduro", engine.state.value.info?.model)
        assertEquals(33, engine.state.value.info?.sdkVersion)
        assertEquals("{\"value\":1}", engine.state.value.info?.fields?.get("new_field"))
        assertEquals(90, engine.state.value.batteryPercent)
        assertEquals(listOf(DeviceSettingsProtocol.WEAR_INFO, DeviceSettingsProtocol.BATTERY), data.sent.map { it.second })
        engine.close()
    }
    @Test fun `invalid battery keeps the previous reading and cannot complete a refresh`() = runTest {
        val data = FeatureTestData(); val logs = mutableListOf<ProtocolDiagnostic>()
        val engine = DeviceSettingsEngine(backgroundScope, data, diagnostics = DiagnosticSink { logs += it })
        engine.attach(data.session()); runCurrent()
        engine.handleMessage(data.scope.peer, 1, DeviceSettingsProtocol.BATTERY, "80.0".toByteArray())
        data.onSend = { route, path, _ -> if (path == DeviceSettingsProtocol.WEAR_INFO)
            engine.handleMessage(data.scope.peer, 1, path, "{}".toByteArray(), route) }
        val refresh = async { engine.refresh() }; runCurrent()
        for (invalid in listOf("103", "-1", "40.5", "private invalid text")) {
            engine.handleMessage(data.scope.peer, 1, DeviceSettingsProtocol.BATTERY, invalid.toByteArray())
            runCurrent(); assertFalse(refresh.isCompleted); assertEquals(80, engine.state.value.batteryPercent)
        }
        engine.handleMessage(data.scope.peer, 1, DeviceSettingsProtocol.BATTERY, "\"90.00\"".toByteArray())
        assertEquals(DeviceCommandStatus.REMOTE_CONFIRMED, refresh.await().status)
        assertEquals(90, engine.state.value.batteryPercent)
        assertTrue(logs.any { it.event == "message_decode_failed" && it.details["reason"] == "outside_0_to_100" })
        assertFalse(logs.any { "private invalid text" in it.details.toString() }); engine.close()
    }
    @Test fun `malformed info can be followed by a usable response without dropping the refresh`() = runTest {
        val data = FeatureTestData(); val logs = mutableListOf<ProtocolDiagnostic>()
        val engine = DeviceSettingsEngine(backgroundScope, data, diagnostics = DiagnosticSink { logs += it })
        engine.attach(data.session()); runCurrent()
        data.onSend = { key, path, _ ->
            if (path == DeviceSettingsProtocol.WEAR_INFO) {
                engine.handleMessage(data.scope.peer, 1, path, "invalid private identity".toByteArray(), key)
                engine.handleMessage(data.scope.peer, 1, path, "{}".toByteArray(), key)
            } else engine.handleMessage(data.scope.peer, 1, path, "50".toByteArray(), key)
        }
        assertEquals(DeviceCommandStatus.REMOTE_CONFIRMED, engine.refresh().status)
        assertTrue(logs.any { it.event == "message_decode_failed" })
        assertFalse(logs.any { "private identity" in it.details.toString() }); engine.close()
    }
    @Test fun `display preference writes use one monotonic writer and persist while disconnected`() = runTest {
        val data = FeatureTestData(); val store = MemoryDevicePreferencesStore()
        val engine = DeviceSettingsEngine(backgroundScope, data, store,
            clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC))
        assertEquals("saved_locally_pending_connection", engine.setLanguageTags("zh-CN,en-US").reason)
        engine.attach(data.session()); runCurrent()
        assertEquals(DeviceCommandStatus.PERSISTED_AND_SUBMITTED, engine.set24HourFormat(true).status)
        assertEquals("zh-CN,en-US", store.load().languageTags)
        val first = data.published.last { it.uri.endsWith(CompanionSettingsCodec.TIME_FORMAT_PATH) }
        assertEquals(100L, CompanionSettingsCodec.changedAt(first.data!!))
        engine.set24HourFormat(false)
        assertEquals(101L, CompanionSettingsCodec.changedAt(data.published.last().data!!))
        engine.close()
    }
    @Test fun `lock queries decode packed and unpacked unknown enum types without rejecting the session`() = runTest {
        val data = FeatureTestData(); val engine = DeviceSettingsEngine(backgroundScope, data)
        engine.attach(data.session()); runCurrent()
        data.onRequest = { _, path, _ -> if (path == DeviceSettingsProtocol.LOCK_SUPPORT) byteArrayOf(8, 1, 10, 3, 2, 3, 77) else byteArrayOf(8, 3) }
        assertEquals(DeviceCommandStatus.REMOTE_CONFIRMED, engine.queryLockSupport().status)
        assertEquals(listOf(1, 2, 3, 77), engine.state.value.lock.supportedTypes)
        assertEquals(DeviceCommandStatus.REMOTE_UI_OPENED, engine.configureLock(WatchLockType.PIN).status)
        assertEquals(WatchLockPhase.VERIFICATION_SCREEN, engine.state.value.lock.phase)
        assertContentEquals(byteArrayOf(), engine.handleLockRequest(data.scope.peer, 1, DeviceSettingsProtocol.LOCK_FINISHED, byteArrayOf(8, 1)))
        assertEquals(WatchLockPhase.CONFIGURED, engine.state.value.lock.phase)
        engine.close()
    }
    @Test fun `remote settings and uninstall confirm actual callback and never remove local app inventory`() = runTest {
        val data = FeatureTestData(); val engine = DeviceSettingsEngine(backgroundScope, data)
        engine.attach(data.session()); runCurrent()
        data.onSend = { route, path, bytes ->
            assertEquals(DeviceSettingsProtocol.REMOTE_INTENT, path)
            val values = DataMapCodec.decode(bytes).values
            val id = (values[NotificationProtocol.INVOCATION_ID] as DataMapValue.Text).value
            engine.handleMessage(data.scope.peer, 1, DeviceSettingsProtocol.REMOTE_INTENT_RESULT,
                NotificationRpcCodec.encodeResult(id, true, 2), route)
        }
        assertEquals(DeviceCommandStatus.REMOTE_UI_OPENED, engine.openSettings(WatchSettingsScreen.WIFI).status)
        assertEquals(DeviceCommandStatus.REMOTE_UI_OPENED, engine.openSettings(WatchSettingsScreen.TRANSFER).status)
        assertEquals(DataMapValue.Text("android.settings.WATCH_TRANSFER"), DataMapCodec.decode(data.sent.last().third).values["action"])
        assertTrue(data.sent.all { it.second == DeviceSettingsProtocol.REMOTE_INTENT })
        assertEquals(DeviceCommandStatus.REMOTE_UI_OPENED, engine.requestUninstall("app.face").status)
        val request = DataMapCodec.decode(data.sent.last().third).values
        assertEquals(DataMapValue.Text("android.intent.action.DELETE"), request["action"])
        assertEquals(DataMapValue.Text("package:app.face"), request["uri_data"])
        engine.close()
    }
    @Test fun `unmatched result times out and detach releases a pending request without migrating it`() = runTest {
        val data = FeatureTestData(); val engine = DeviceSettingsEngine(backgroundScope, data, responseTimeoutMillis = 50)
        engine.attach(data.session()); runCurrent()
        val opening = async { engine.openSettings(WatchSettingsScreen.ABOUT) }; runCurrent()
        engine.handleMessage(data.scope.peer, 1, DeviceSettingsProtocol.REMOTE_INTENT_RESULT, NotificationRpcCodec.encodeResult("unmatched", true))
        advanceTimeBy(51); runCurrent()
        assertEquals(DeviceCommandStatus.TIMED_OUT, opening.await().status)
        val refresh = async { engine.refresh() }; runCurrent()
        engine.detach(1); engine.attach(data.session(2)); runCurrent()
        assertEquals(DeviceCommandStatus.UNAVAILABLE, refresh.await().status)
        assertEquals(2, engine.state.value.sessionId); engine.close()
    }
    @Test fun `app catalog keeps good records and fetches only the referenced asset in its namespace`() = runTest {
        val data = FeatureTestData()
        data.remote("${DeviceSettingsProtocol.APPS}face.pkg", byteArrayOf(10, 8) + "face.pkg".toByteArray() + byteArrayOf(18, 4) + "Face".toByteArray() + byteArrayOf(32, 1, 40, 77), assets = listOf(DataAssetReference("app_icon", "digest")))
        data.remote("${DeviceSettingsProtocol.APPS}malformed", byteArrayOf(10, 99))
        val engine = DeviceSettingsEngine(backgroundScope, data); engine.attach(data.session()); runCurrent()
        val app = engine.state.value.apps.single()
        assertEquals("face.pkg", app.packageName); assertEquals(77, app.notificationPermissionState)
        assertNull(engine.appIcon(app.packageName)); assertEquals(listOf("digest"), data.assetRequests)
        engine.close()
    }
    @Test fun `heart health visibility sends exact ASCII toggle without claiming monitoring enabled`() = runTest {
        val data = FeatureTestData(); val engine = DeviceSettingsEngine(backgroundScope, data)
        engine.attach(data.session()); runCurrent()
        assertEquals(DeviceCommandStatus.SUBMITTED_UNVERIFIED, engine.setHeartHealthFeatureVisible(true).status)
        assertEquals(DeviceSettingsProtocol.HEART_HEALTH, data.sent.single().second)
        assertContentEquals(byteArrayOf(49), data.sent.single().third)
        engine.close()
    }
    @Suppress("DEPRECATION")
    @Test fun `legacy monitoring method uses the actual preference backend and never sends the visibility message`() = runTest {
        val data = FeatureTestData()
        val calls = mutableListOf<Pair<MonitoringSettingId, MonitoringValue>>()
        val monitoring = object : MonitoringSettingsClient by MonitoringSettingsClient.UNAVAILABLE {
            override suspend fun set(id: MonitoringSettingId, value: MonitoringValue): MonitoringCommandResult {
                calls += id to value
                return MonitoringCommandResult(MonitoringCommandStatus.STORED_AND_NOTIFIED, id)
            }
        }
        val engine = DeviceSettingsEngine(backgroundScope, data, monitoring = { monitoring })
        assertEquals(DeviceCommandStatus.REMOTE_CONFIRMED, engine.setHeartHealthMonitoring(true).status)
        assertEquals(MonitoringSettingId.HEART_HEALTH, calls.single().first)
        assertEquals<MonitoringValue>(MonitoringValue.Toggle(true), calls.single().second)
        assertTrue(data.sent.isEmpty())
        engine.close()
        val missing = DeviceSettingsEngine(backgroundScope, data)
        assertEquals(DeviceCommandStatus.UNAVAILABLE, missing.setHeartHealthMonitoring(true).status)
        assertTrue(data.sent.isEmpty()); missing.close()
    }
    @Test fun `queued old-session uninstall cannot become an operation on the replacement watch session`() = runTest {
        val data = FeatureTestData(); val engine = DeviceSettingsEngine(backgroundScope, data)
        val original = data.session(); engine.attach(original); runCurrent()
        val blocker = async { engine.refresh() }; runCurrent()
        val uninstall = async { engine.requestUninstallForSession(original, "face.pkg") }; runCurrent()
        engine.detach(1); engine.attach(data.session(2)); runCurrent()
        assertEquals(DeviceCommandStatus.UNAVAILABLE, blocker.await().status)
        assertEquals(DeviceCommandStatus.UNAVAILABLE, uninstall.await().status)
        assertFalse(data.sent.any { it.second == DeviceSettingsProtocol.REMOTE_INTENT })
        engine.close()
    }
}
