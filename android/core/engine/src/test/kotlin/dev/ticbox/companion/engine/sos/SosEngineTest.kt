@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.ticbox.companion.engine.sos

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.CompanionFeatureSession
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*

class SosEngineTest {
    @Test fun `contacts and mode writes match independently recovered phone and watch bytes`() = runTest {
        assertContentEquals("[{\"name\":\"家庭\",\"num\":\"+86 123456\"}]".toByteArray(),
            SosCodec.contacts(listOf(SosEmergencyContact("家庭", "+86 123456"))))
        assertEquals(listOf(-1, 0, 1), SosFallDetectionMode.entries.map { it.wireValue })
        val f = fixture()
        f.connect(); runCurrent(); f.sent.clear()
        assertEquals(SosCommandStatus.CONFIRMED, f.engine.setEmergencyNumber("112").status)
        assertEquals(SosCommandStatus.CONFIRMED, f.engine.setContacts(listOf(SosEmergencyContact("Home", "123456"))).status)
        assertEquals(SosCommandStatus.CONFIRMED, f.engine.setButtonTrigger(false).status)
        assertEquals(SosCommandStatus.CONFIRMED, f.engine.setShareLocation(true).status)
        val fall = f.engine.setFallDetection(SosFallDetectionMode.OFF)
        assertEquals(SosCommandStatus.CONFIRMED, fall.status)
        assertEquals("watch_preference_confirmed_sensor_execution_unverified", fall.reason)
        assertEquals(listOf(SosProtocol.NUMBER, SosProtocol.SETTINGS, SosProtocol.CONTACTS, SosProtocol.SETTINGS,
            SosProtocol.BUTTON, SosProtocol.SETTINGS, SosProtocol.SHARE_LOCATION, SosProtocol.SETTINGS,
            SosProtocol.FALL, SosProtocol.SETTINGS), f.sent.map { it.path })
        assertEquals(listOf("112", "[{\"name\":\"Home\",\"num\":\"123456\"}]", "false", "true", "-1"),
            f.sent.filter { it.path != SosProtocol.SETTINGS }.map { it.bytes.toString(Charsets.UTF_8) })
        assertTrue(f.sent.all { it.route == f.route }); f.engine.close()
    }

    @Test fun `unknown fall modes and contact schemas stay unknown instead of fabricated defaults`() {
        val bytes = """{"u_time":9007199254740993,"button_press":false,"e_num":"112","tic_fall_type":77,"share_loc":true,"cts":"{\"future\":1}","unknown":{"nested":[1,2]}}""".toByteArray()
        val snapshot = SosCodec.settings(bytes)
        assertEquals(9_007_199_254_740_993L, snapshot.updatedAtMillis)
        assertNull(snapshot.fallDetectionMode); assertEquals(77, snapshot.fallDetectionValue)
        assertNull(snapshot.contacts); assertEquals("{\"future\":1}", snapshot.contactsPayload)
        assertEquals(snapshot, SosCodec.settings(SosCodec.encodeSettings(snapshot)))
        assertEquals(emptyList(), SosCodec.contactsOrNull(""))
        assertEquals(emptyList(), SosCodec.contactsOrNull("[]"))
    }

    @Test fun `malformed UTF8 incomplete settings deep JSON and invalid recipients are rejected without wire writes`() = runTest {
        assertFails { SosCodec.settings(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertFails { SosCodec.settings("{}".toByteArray()) }
        assertFails { SosCodec.settings(ByteArray(SosCodec.MAX_SETTINGS_BYTES + 1)) }
        assertFails { SosCodec.settings(("[".repeat(100) + "]".repeat(100)).toByteArray()) }
        val f = fixture(); f.connect(); runCurrent(); f.sent.clear()
        assertEquals(SosCommandStatus.INVALID_ARGUMENT, f.engine.setEmergencyNumber("tel:112").status)
        assertEquals(SosCommandStatus.INVALID_ARGUMENT, f.engine.setContacts(List(5) { SosEmergencyContact("Home", "123456") }).status)
        assertEquals(SosCommandStatus.INVALID_ARGUMENT, f.engine.setContacts(listOf(SosEmergencyContact("Home", "123;456"))).status)
        assertTrue(f.sent.isEmpty()); f.engine.close()
    }

    @Test fun `shared ack cannot complete a command or hide failed global button writes`() = runTest {
        val f = fixture(); f.connect(); runCurrent()
        f.autoReply = false; f.applyWrites = false
        val command = async { f.engine.setButtonTrigger(false) }; runCurrent()
        f.receive(SosProtocol.SYNC_OK, byteArrayOf()); runCurrent()
        assertFalse(command.isCompleted)
        f.receive(SosProtocol.SETTINGS, SosCodec.encodeSettings(f.watch)); runCurrent()
        assertEquals(SosCommandStatus.READ_BACK_MISMATCH, command.await().status)
        assertTrue(f.engine.state.value.settings!!.buttonTriggerEnabled)
        f.engine.close()
    }

    @Test fun `timed out mutation quarantines writes until physical reconnect and ignores late ack`() = runTest {
        val f = fixture(timeout = 50); f.connect(); runCurrent()
        f.autoReply = false
        val command = async { f.engine.setShareLocation(true) }; runCurrent()
        advanceTimeBy(51); runCurrent()
        assertEquals(SosCommandStatus.TIMED_OUT, command.await().status)
        f.receive(SosProtocol.SYNC_OK, byteArrayOf())
        val before = f.sent.size
        assertEquals("previous_write_outcome_unknown_reconnect_required", f.engine.setFallDetection(SosFallDetectionMode.ALWAYS).reason)
        assertEquals(before, f.sent.size)
        f.engine.detach(1); f.autoReply = true; f.connect(id = 2); runCurrent()
        assertEquals(SosCommandStatus.CONFIRMED, f.engine.setFallDetection(SosFallDetectionMode.ALWAYS).status)
        f.engine.close()
    }

    @Test fun `caller cancellation after mutation also quarantines possible late remote execution`() = runTest {
        val f = fixture(); f.connect(); runCurrent(); f.autoReply = false
        val command = async { f.engine.setEmergencyNumber("112") }; runCurrent()
        command.cancelAndJoin()
        assertEquals(SosPhase.CONNECTED, f.engine.state.value.phase)
        assertEquals("previous_write_outcome_unknown_reconnect_required", f.engine.setButtonTrigger(false).reason)
        f.engine.close()
    }

    @Test fun `late ID-less query reply after timeout cannot verify the next mutation`() = runTest {
        val f = fixture(timeout = 50); f.connect(); runCurrent(); f.autoReply = false
        val refresh = async { f.engine.refresh() }; runCurrent(); advanceTimeBy(51); runCurrent()
        assertEquals(SosCommandStatus.TIMED_OUT, refresh.await().status)
        f.receive(SosProtocol.SETTINGS, SosCodec.encodeSettings(f.watch))
        val before = f.sent.size
        assertEquals("readback_correlation_lost_reconnect_required", f.engine.setShareLocation(true).reason)
        assertEquals(before, f.sent.size)
        f.engine.close()
    }

    @Test fun `same-session older snapshots do not replace contacts or complete a settings readback`() = runTest {
        val f = fixture(); f.watch = f.watch.copy(updatedAtMillis = 100); f.connect(); runCurrent()
        f.autoReply = false; f.applyWrites = false
        val pending = async { f.engine.setButtonTrigger(false) }; runCurrent()
        f.receive(SosProtocol.SETTINGS, SosCodec.encodeSettings(f.watch.copy(updatedAtMillis = 99, buttonTriggerEnabled = false)))
        runCurrent(); assertFalse(pending.isCompleted)
        assertEquals(100, f.engine.state.value.settings?.updatedAtMillis)
        f.receive(SosProtocol.SETTINGS, SosCodec.encodeSettings(f.watch.copy(updatedAtMillis = 101, buttonTriggerEnabled = false)))
        assertEquals(SosCommandStatus.CONFIRMED, pending.await().status)
        f.engine.detach(1); f.watch = f.watch.copy(updatedAtMillis = 1); f.autoReply = true; f.connect(id = 2); runCurrent()
        assertEquals(1, f.engine.state.value.settings?.updatedAtMillis)
        f.engine.close()
    }

    @Test fun `duplicate recipient keys including escaped aliases are never interpreted as a contact`() {
        assertNull(SosCodec.contactsOrNull("""[{"name":"Home","num":"123","num":"456"}]"""))
        assertNull(SosCodec.contactsOrNull("""[{"name":"Home","num":"123","n\u0075m":"456"}]"""))
        assertFails { SosCodec.settings("""{"u_time":1,"button_press":true,"e_num":"911","tic_fall_type":1,"share_loc":false,"share_loc":true,"cts":"[]"}""".toByteArray()) }
        assertEquals(listOf(SosEmergencyContact("A \"quoted\": value", "123")),
            SosCodec.contactsOrNull("""[{"name":"A \"quoted\": value","num":"123"}]"""))
    }

    @Test fun `session replacement cancels current and queued old watch commands`() = runTest {
        val f = fixture(); f.connect(); runCurrent(); f.autoReply = false
        val pending = async { f.engine.refresh() }; runCurrent()
        val queued = async { f.engine.setButtonTrigger(false) }; runCurrent()
        f.engine.detach(1); f.autoReply = true; f.connect(id = 2); runCurrent()
        assertEquals(SosCommandStatus.UNAVAILABLE, pending.await().status)
        assertEquals(SosCommandStatus.UNAVAILABLE, queued.await().status)
        assertFalse(f.sent.any { it.path == SosProtocol.BUTTON })
        assertFalse(f.engine.handleMessage(f.peer, 1, SosProtocol.SETTINGS, SosCodec.encodeSettings(f.watch), f.route))
        assertEquals(2, f.engine.state.value.sessionId); f.engine.close()
    }

    @Test fun `wrong AppKey peer and unsupported messages cannot change SOS state`() = runTest {
        val f = fixture(); f.connect(); runCurrent()
        val before = f.engine.state.value
        val body = SosCodec.encodeSettings(f.watch.copy(emergencyNumber = "999"))
        assertFalse(f.engine.handleMessage(f.peer, 1, SosProtocol.SETTINGS, body, ApplicationRoute("other", "digest")))
        assertFalse(f.engine.handleMessage(PeerAddress("AA:BB:CC:DD:EE:02"), 1, SosProtocol.START, byteArrayOf(), f.route))
        assertFalse(f.engine.handleMessage(f.peer, 1, "/companion/ticsos/unknown", byteArrayOf(), f.route))
        assertEquals(before, f.engine.state.value); f.engine.close()
    }

    @Test fun `watch editor requests expose an event without trusting reverse direction payload as settings`() = runTest {
        val f = fixture(); f.connect(); runCurrent()
        val confirmed = f.engine.state.value.settings
        f.receive(SosProtocol.NUMBER, "999".toByteArray())
        assertEquals(SosWatchEditKind.EMERGENCY_NUMBER, f.engine.state.value.lastWatchEditRequest?.kind)
        val sequence = f.engine.state.value.lastWatchEditRequest!!.sequence
        f.receive(SosProtocol.CONTACTS, "private raw contact".toByteArray())
        assertEquals(SosWatchEditKind.CONTACTS, f.engine.state.value.lastWatchEditRequest?.kind)
        assertTrue(f.engine.state.value.lastWatchEditRequest!!.sequence > sequence)
        assertEquals(confirmed, f.engine.state.value.settings); f.engine.close()
    }

    @Test fun `SOS dispatch uses saved contacts and share preference not payload number or phone call`() = runTest {
        val handler = RecordingHandler()
        val f = fixture(handler = handler); f.connect(); runCurrent()
        f.receive(SosProtocol.START, "UNTRUSTED_NUMBER".toByteArray()); runCurrent()
        val event = handler.requests.single()
        assertEquals(listOf(SosEmergencyContact("Private Home", "123456")), event.contacts)
        assertEquals(f.peer, event.peer); assertEquals(1, event.sessionId)
        assertFalse(event.shareLocation)
        assertEquals(1000, event.receivedAtMillis)
        assertEquals(1, f.engine.state.value.emergencyEventsReceived)
        f.engine.detach(1)
        assertEquals(0, handler.stops)
        f.engine.close(); assertEquals(0, handler.closed)
    }

    @Test fun `event receipt time is captured before cache load and not refreshed after queueing`() = runTest {
        val loaded = CompletableDeferred<Unit>()
        val clock = object : Clock() {
            var now = 1000L
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): Clock = this
            override fun instant(): Instant = Instant.ofEpochMilli(now)
        }
        val store = object : SosSettingsStore {
            override suspend fun load(peer: PeerAddress): SosWatchSettings? { loaded.await(); return null }
            override suspend fun save(peer: PeerAddress, settings: SosWatchSettings) = Unit
        }
        val handler = RecordingHandler(); val f = fixture(handler = handler, store = store, clock = clock)
        f.connect(); runCurrent()
        f.receive(SosProtocol.START, byteArrayOf()); runCurrent()
        clock.now = 100_000; loaded.complete(Unit); runCurrent()
        assertTrue(handler.requests.isEmpty())
        assertEquals("sos_event_expired", f.engine.state.value.lastIssue?.reason)
        f.engine.close()
    }

    @Test fun staleContactsAndLocationGrantAreNotUsedWhileTheCurrentQueryIsPending() = runTest {
        for (emptyCache in listOf(false, true)) {
            val store = MemorySosSettingsStore(); val handler = RecordingHandler()
            val f = fixture(handler = handler, store = store)
            store.save(f.peer, f.watch.copy(shareLocation = true, contacts = if (emptyCache) emptyList() else f.watch.contacts))
            f.autoReply = false; f.connect(); runCurrent()
            assertFalse(f.engine.state.value.settingsCurrent)
            f.receive(SosProtocol.START, byteArrayOf()); runCurrent()
            assertTrue(handler.requests.isEmpty())
            val fresh = f.watch.copy(shareLocation = false,
                contacts = listOf(SosEmergencyContact("Current", "987654")), contactsPayload = "[{\"name\":\"Current\",\"num\":\"987654\"}]")
            f.receive(SosProtocol.SETTINGS, SosCodec.encodeSettings(fresh)); runCurrent()
            assertEquals(fresh.contacts, handler.requests.single().contacts)
            assertFalse(handler.requests.single().shareLocation)
            assertEquals(1, f.sent.count { it.path == SosProtocol.SETTINGS })
            f.engine.close()
        }
    }

    @Test fun staleCachedRecipientsAreNotFallbackAuthorityAfterRefreshFailureOrDeletion() = runTest {
        for (timeout in listOf(false, true)) {
            val store = MemorySosSettingsStore(); val handler = RecordingHandler()
            val f = fixture(timeout = 50, handler = handler, store = store)
            store.save(f.peer, f.watch.copy(shareLocation = true))
            f.autoReply = false; f.connect(); runCurrent()
            if (timeout) { advanceTimeBy(51); runCurrent() }
            f.receive(SosProtocol.START, byteArrayOf()); runCurrent()
            if (!timeout) {
                f.receive(SosProtocol.SETTINGS, SosCodec.encodeSettings(f.watch.copy(contacts = emptyList(), contactsPayload = "[]")))
                runCurrent()
            }
            assertTrue(handler.requests.isEmpty())
            assertEquals(if (timeout) "readback_correlation_lost_reconnect_required" else "no_emergency_contacts",
                f.engine.state.value.lastIssue?.reason)
            f.engine.close()
        }
    }

    @Test fun anIgnoredOversizeStartBodyDoesNotDiscardAnAuthenticatedEmergency() = runTest {
        val logs = mutableListOf<ProtocolDiagnostic>(); val handler = RecordingHandler()
        val f = fixture(handler = handler, logs = logs)
        f.connect(); runCurrent()
        f.receive(SosProtocol.START, ByteArray(4096) { 65 }); runCurrent()
        assertEquals(1, handler.requests.size)
        assertTrue(logs.any { it.event == "emergency_payload_ignored" })
        assertFalse(logs.joinToString().contains("AAAA"))
        f.engine.close()
    }

    @Test fun `empty or unreadable contacts prevent automatic assistance and report a specific reason`() = runTest {
        val handler = RecordingHandler(); val f = fixture(handler = handler)
        f.watch = f.watch.copy(contacts = emptyList(), contactsPayload = "[]")
        f.connect(); runCurrent(); f.receive(SosProtocol.START, byteArrayOf()); runCurrent()
        assertTrue(handler.requests.isEmpty())
        assertEquals("no_emergency_contacts", f.engine.state.value.lastIssue?.reason)
        f.watch = f.watch.copy(contacts = null, contactsPayload = "{\"unknown\":true}")
        f.receive(SosProtocol.SETTINGS, SosCodec.encodeSettings(f.watch))
        f.receive(SosProtocol.START, byteArrayOf()); runCurrent()
        assertTrue(handler.requests.isEmpty())
        assertEquals("watch_contacts_unreadable", f.engine.state.value.lastIssue?.reason)
        f.engine.close()
    }

    @Test fun `SOS cannot start another uncorrelatable contact query after the initial query timed out`() = runTest {
        val handler = RecordingHandler(); val f = fixture(timeout = 50, handler = handler)
        f.autoReply = false; f.connect(); runCurrent(); advanceTimeBy(51); runCurrent()
        val before = f.sent.size
        f.receive(SosProtocol.START, byteArrayOf()); runCurrent()
        assertEquals(before, f.sent.size)
        assertTrue(handler.requests.isEmpty())
        assertEquals("readback_correlation_lost_reconnect_required", f.engine.state.value.lastIssue?.reason)
        f.engine.close()
    }

    @Test fun `emergency executor rejection is visible while listener remains alive`() = runTest {
        val handler = RecordingHandler().apply { handling = SosEmergencyHandling(false, "sms_permission_missing") }
        val f = fixture(handler = handler); f.connect(); runCurrent()
        f.receive(SosProtocol.START, byteArrayOf()); runCurrent()
        assertEquals("sms_permission_missing", f.engine.state.value.lastIssue?.reason)
        assertEquals(SosCommandStatus.CONFIRMED, f.engine.refresh().status)
        f.engine.close()
    }

    @Test fun `per-peer cache does not migrate emergency contacts to another watch`() = runTest {
        val store = MemorySosSettingsStore(); val f = fixture(store = store)
        f.connect(); runCurrent(); assertNotNull(store.load(f.peer))
        f.engine.detach(1); f.autoReply = false
        f.peer = PeerAddress("AA:BB:CC:DD:EE:02"); f.connect(id = 2); runCurrent()
        assertNull(f.engine.state.value.settings)
        assertFalse(f.engine.state.value.settingsCurrent)
        f.engine.close()
    }

    @Test fun `malformed messages and exception text never put contacts or numbers into diagnostics`() = runTest {
        val logs = mutableListOf<ProtocolDiagnostic>(); val f = fixture(logs = logs)
        f.connect(); runCurrent()
        f.receive(SosProtocol.SETTINGS, "private secret 123456".toByteArray())
        f.receive(SosProtocol.SYNC_OK, "Private Home".toByteArray())
        assertTrue(logs.any { it.event == "message_rejected" })
        assertFalse(logs.joinToString().contains("123456")); assertFalse(logs.joinToString().contains("Private Home"))
        assertFalse(f.watch.toString().contains("123456"))
        assertFalse(f.watch.contacts.toString().contains("Private Home"))
        assertEquals(SosCommandStatus.CONFIRMED, f.engine.refresh().status); f.engine.close()
    }

    private fun TestScope.fixture(timeout: Long = 1000, handler: SosEmergencyHandler = RecordingHandler(),
        store: SosSettingsStore = MemorySosSettingsStore(), logs: MutableList<ProtocolDiagnostic> = mutableListOf(),
        clock: Clock = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC)): Fixture =
        Fixture(backgroundScope, timeout, handler, store, logs, clock)

    private class RecordingHandler : SosEmergencyHandler {
        override val state = MutableStateFlow(SosAssistanceState())
        val requests = mutableListOf<SosEmergencyRequest>()
        var handling = SosEmergencyHandling(true)
        var stops = 0; var closed = 0
        override suspend fun handle(request: SosEmergencyRequest): SosEmergencyHandling { requests += request; return handling }
        override suspend fun refreshCapabilities() = Unit
        override suspend fun configure(config: SosPhoneAssistanceConfig) { state.value = state.value.copy(config = config) }
        override suspend fun stop() { stops++ }
        override fun close() { closed++ }
    }

    private data class Sent(val route: ApplicationRoute, val path: String, val bytes: ByteArray)
    private class Fixture(scope: CoroutineScope, timeout: Long, handler: SosEmergencyHandler,
        store: SosSettingsStore, logs: MutableList<ProtocolDiagnostic>, clock: Clock) {
        var peer = PeerAddress("AA:BB:CC:DD:EE:01")
        val route = ApplicationRoute("companion.test", "digest")
        var id = 1L
        var autoReply = true; var applyWrites = true
        val sent = mutableListOf<Sent>()
        var watch = SosWatchSettings(1, true, "911", 1, false,
            listOf(SosEmergencyContact("Private Home", "123456")), "[{\"name\":\"Private Home\",\"num\":\"123456\"}]")
        val engine = SosEngine(scope, store, handler, DiagnosticSink { logs += it }, clock, timeout)
        fun connect(id: Long = 1) {
            this.id = id
            val dataScope = DataScope(peer, "phone", "watch", "network")
            engine.attach(CompanionFeatureSession(id, dataScope, route, send = { key, path, bytes ->
                sent += Sent(key, path, bytes.copyOf())
                if (applyWrites) watch = when (path) {
                    SosProtocol.NUMBER -> watch.copy(emergencyNumber = bytes.toString(Charsets.UTF_8))
                    SosProtocol.CONTACTS -> watch.copy(contactsPayload = bytes.toString(Charsets.UTF_8), contacts = SosCodec.contactsOrNull(bytes.toString(Charsets.UTF_8)))
                    SosProtocol.BUTTON -> watch.copy(buttonTriggerEnabled = bytes.toString(Charsets.UTF_8).toBoolean())
                    SosProtocol.SHARE_LOCATION -> watch.copy(shareLocation = bytes.toString(Charsets.UTF_8).toBoolean())
                    SosProtocol.FALL -> watch.copy(fallDetectionValue = bytes.toString(Charsets.UTF_8).toInt())
                    else -> watch
                }
                if (path == SosProtocol.SETTINGS && autoReply) receive(path, SosCodec.encodeSettings(watch))
            }, request = { _, _, _, _ -> error("SOS does not use generic RPC ACKs") }, open = { _, _ -> error("SOS does not use Channels") }))
        }
        suspend fun receive(path: String, bytes: ByteArray) = engine.handleMessage(peer, id, path, bytes, route)
    }
}
