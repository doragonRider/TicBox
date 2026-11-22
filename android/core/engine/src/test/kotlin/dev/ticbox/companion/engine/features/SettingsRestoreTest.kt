@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.ticbox.companion.engine.features

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.settings.*
import dev.ticbox.companion.engine.finding.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

class SettingsRestoreTest {
    @Test fun `display restore is asynchronous and a queued edit preserves other saved fields`() = runTest {
        val restored = CompletableDeferred<CompanionDevicePreferences>(); var loads = 0
        var saved: CompanionDevicePreferences? = null
        val store = object : DevicePreferencesStore {
            override suspend fun load(): CompanionDevicePreferences { loads++; return restored.await() }
            override suspend fun save(preferences: CompanionDevicePreferences) { saved = preferences }
        }
        val engine = DeviceSettingsEngine(backgroundScope, FeatureTestData(), store)
        assertEquals(0, loads)
        val edit = async { engine.set24HourFormat(true) }; runCurrent()
        assertFalse(engine.state.value.preferencesReady); assertNull(saved); assertFalse(edit.isCompleted)
        restored.complete(CompanionDevicePreferences("zh-CN", false)); runCurrent(); edit.await()
        assertEquals(CompanionDevicePreferences("zh-CN", true), saved)
        assertTrue(engine.state.value.preferencesReady); engine.close()
    }

    @Test fun `finding does not publish a default capability or ring before stored preferences arrive`() = runTest {
        val restored = CompletableDeferred<FindDeviceSettings>(); var loads = 0
        val store = object : FindDeviceSettingsStore {
            override suspend fun load(): FindDeviceSettings { loads++; return restored.await() }
            override suspend fun save(settings: FindDeviceSettings) = Unit
        }
        val data = FeatureTestData(); val ringer = Ringer()
        val engine = FindDevicesEngine(backgroundScope, data, ringer, store)
        assertEquals(0, loads)
        val attach = async { engine.attach(data.session()) }; runCurrent()
        val ring = async { engine.ringPhone() }; runCurrent()
        assertTrue(data.published.isEmpty()); assertEquals(0, ringer.starts); assertFalse(attach.isCompleted)
        restored.complete(FindDeviceSettings(false)); runCurrent(); attach.await(); ring.await()
        assertTrue(data.published.single().deleted); assertEquals(0, ringer.starts)
        assertTrue(engine.state.value.settingsReady); engine.close()
    }

    @Test fun `closing during preference IO prevents late restoration and releases queued commands`() = runTest {
        val display = CompletableDeferred<CompanionDevicePreferences>(); val find = CompletableDeferred<FindDeviceSettings>()
        val devices = DeviceSettingsEngine(backgroundScope, FeatureTestData(), object : DevicePreferencesStore {
            override suspend fun load() = withContext(NonCancellable) { display.await() }
            override suspend fun save(preferences: CompanionDevicePreferences) { fail("closed store write") }
        })
        val finding = FindDevicesEngine(backgroundScope, FeatureTestData(), Ringer(), object : FindDeviceSettingsStore {
            override suspend fun load() = withContext(NonCancellable) { find.await() }
            override suspend fun save(settings: FindDeviceSettings) { fail("closed store write") }
        })
        val edit = async { runCatching { devices.setLanguageTags("en") } }
        val enable = async { runCatching { finding.setEnabled(true) } }; runCurrent()
        devices.close(); finding.close(); runCurrent()
        assertTrue(edit.await().isFailure); assertTrue(enable.await().isFailure)
        display.complete(CompanionDevicePreferences("old", true)); find.complete(FindDeviceSettings(false)); runCurrent()
        assertFalse(devices.state.value.preferencesReady); assertFalse(finding.state.value.settingsReady)
    }

    @Test fun `restore failure is reported and explicit preferences can still be saved`() = runTest {
        val logs = mutableListOf<ProtocolDiagnostic>(); var saved: CompanionDevicePreferences? = null
        val engine = DeviceSettingsEngine(backgroundScope, FeatureTestData(), object : DevicePreferencesStore {
            override suspend fun load(): CompanionDevicePreferences = throw java.io.IOException("private path")
            override suspend fun save(preferences: CompanionDevicePreferences) { saved = preferences }
        }, diagnostics = DiagnosticSink { logs += it })
        engine.setLanguageTags("zh"); assertEquals("zh", saved?.languageTags)
        assertTrue(engine.state.value.preferencesReady); assertTrue(logs.any { it.event == "preferences_restore_failed" })
        assertFalse(logs.any { "private path" in it.details.toString() }); engine.close()
    }

    private class Ringer : PhoneRinger {
        override val state = MutableStateFlow(PhoneRingingState())
        var starts = 0
        override suspend fun start() { starts++ }
        override suspend fun stop() = Unit
        override fun close() = Unit
    }
}
