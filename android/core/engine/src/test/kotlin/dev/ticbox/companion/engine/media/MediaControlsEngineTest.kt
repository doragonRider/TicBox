@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.ticbox.companion.engine.media

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.CompanionFeatureSession
import dev.ticbox.companion.protocol.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class MediaControlsEngineTest {
    private val peer = PeerAddress("AA:BB:CC:DD:EE:FF")
    private val route = ApplicationRoute("com.mobvoi.companion.at", "5cc9866db03e530a21f842a421c7eb42bac58961")
    private val scope = DataScope(peer, "phone", "watch", "network")
    private fun media(id: String = "native-one", app: String = "player.one", playing: Boolean = true, title: String = "private song", image: ByteArray? = null): PhoneMediaSession =
        PhoneMediaSession(MediaSessionSummary(id, app, "Player", title, "private artist", playing), MediaContent(
            mapOf("mediacontrols.media_session_token" to DataMapValue.Text(id), "mediacontrols.package_name" to DataMapValue.Text(app),
                "mediacontrols.artist" to DataMapValue.Text("private artist"), "mediacontrols.title" to DataMapValue.Text(title),
                "mediacontrols.playing" to DataMapValue.Bool(playing)),
            image?.let { mapOf("mediacontrols.artwork" to MediaAsset(it)) }.orEmpty()))
    private class Source : MediaSource {
        override val state = MutableStateFlow(MediaSourceState(true, true))
        override val events = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
        var values: List<PhoneMediaSession> = emptyList()
        var failure: Exception? = null
        val executions = mutableListOf<Pair<String, MediaCommand>>()
        var executeAction: suspend (String, MediaCommand) -> MediaCommandOutcome = { _, command -> MediaCommandOutcome(MediaProtocol.operationCount(command), MediaProtocol.operationCount(command)) }
        override suspend fun snapshot(): List<PhoneMediaSession> { failure?.let { throw it }; return values.toList() }
        override suspend fun execute(sessionId: String, command: MediaCommand): MediaCommandOutcome { executions += sessionId to command; return executeAction(sessionId, command) }
        fun changed() { events.tryEmit(Unit) }
    }
    private inner class Data : DataSyncClient {
        override val state = MutableStateFlow(DataSyncState(DataSyncPhase.ACTIVE, scope, 1))
        val rows = linkedMapOf<Triple<DataScope, ApplicationRoute, String>, DataItemRecord>()
        val assets = linkedMapOf<String, DataAssetRecord>()
        val writes = mutableListOf<DataItemRecord>()
        var failWrites = false
        var sequence = 0L
        var barrierCalls = 0
        var barrierStatus = DataSyncBarrierStatus.COMPLETE
        var barrierAction: suspend () -> Unit = {}
        override suspend fun scopes(peer: PeerAddress?) = rows.values.map { it.scope }.distinct().filter { peer == null || peer == it.peer }
        override suspend fun items(scope: DataScope, query: DataItemQuery): DataItemPage {
            val selected = rows.values.filter { it.scope == scope && (query.route == null || query.route == it.route) &&
                (query.uriPrefix == null || it.uri.startsWith(query.uriPrefix.orEmpty())) && (query.includeDeleted || !it.deleted) }
            return DataItemPage(selected.drop(query.offset).take(query.limit), (query.offset + query.limit).takeIf { it < selected.size })
        }
        override suspend fun publish(scope: DataScope, route: ApplicationRoute, draft: DataItemDraft): DataItemRecord {
            if (failWrites) error("private payload in fixture storage failure")
            return DataItemRecord(scope, route, draft.uri, scope.localNodeId, ++sequence, draft.deleted, draft.data, draft.assets,
                receivedAtEpochMillis = 0, local = true).also { rows[Triple(scope, route, draft.uri)] = it; writes += it }
        }
        override suspend fun delete(scope: DataScope, route: ApplicationRoute, uri: String) = publish(scope, route, DataItemDraft(uri, deleted = true))
        override suspend fun putAsset(scope: DataScope, route: ApplicationRoute, bytes: ByteArray): DataAssetRecord =
            DataAssetRecord(scope, DataProtocol.digest(bytes), setOf(route), bytes, 0).also { assets[it.digest] = it }
        override suspend fun asset(scope: DataScope, route: ApplicationRoute, digest: String) = assets[digest]?.takeIf { it.scope == scope && route in it.owners }
        override suspend fun requestAsset(scope: DataScope, route: ApplicationRoute, digest: String, permissionOnly: Boolean) {}
        override suspend fun requestSync() {}
        override suspend fun awaitProgressBarrier(timeoutMillis: Long): DataSyncBarrierResult {
            barrierCalls++; barrierAction(); return DataSyncBarrierResult(barrierStatus, state.value.sessionId)
        }
        override fun close() {}
    }
    private inner class Fixture(parent: CoroutineScope, initial: List<PhoneMediaSession>, settings: MediaControlsSettings = MediaControlsSettings(),
        config: MediaControlsConfig = MediaControlsConfig(), v2: Boolean = false) : AutoCloseable {
        val source = Source().apply { values = initial }
        val data = Data()
        val preferences = MemoryMediaSettingsStore(settings)
        val logs = mutableListOf<ProtocolDiagnostic>()
        val messages = mutableListOf<Triple<ApplicationRoute, String, ByteArray>>()
        val retired = mutableListOf<Set<String>>()
        var now = 100L
        val engine = MediaControlsEngine(parent, data, source, preferences, DiagnosticSink { logs += it }, config, { now },
            MediaAssetRetirement { _, _, values -> retired += values })
        init { attach(1, v2) }
        fun attach(id: Long, v2: Boolean = false) {
            data.state.value = data.state.value.copy(sessionId = id)
            engine.attach(CompanionFeatureSession(id, scope, route, { app, path, bytes -> messages += Triple(app, path, bytes) },
                { _, _, _, _ -> error("Unexpected request") }, { _, _ -> error("Unexpected channel") }), v2)
        }
        fun live() = data.rows.values.filter { it.scope == scope && it.route == route && !it.deleted }
        fun fields() = MediaCodec.decode(live().single().data!!).document.values
        suspend fun command(command: MediaCommand, id: Long = data.state.value.sessionId, app: ApplicationRoute? = route): Boolean =
            engine.handleMessage(peer, id, MediaProtocol.PATH, MediaCodec.encodeCommand(command), app)
        override fun close() { engine.close() }
    }

    @Test fun `legacy mirror uses companion AppKey external artwork and a quiet fresh session`(): Unit = runTest {
        Fixture(backgroundScope, listOf(media(image = byteArrayOf(1, 2))), MediaControlsSettings(autoLaunch = true)).use { f ->
            runCurrent(); val item = f.live().single()
            assertEquals(route, item.route); assertEquals("wear://phone${MediaProtocol.PATH}", item.uri)
            assertEquals(DataMapValue.Bool(false), f.fields()["mediacontrols.watch_app_auto_launch_enabled"])
            assertEquals(DataMapValue.LongNumber(100), f.fields()["elapsed_time"])
            assertContentEquals(byteArrayOf(1, 2), f.data.assets.getValue(item.assets.single().digest).data)
            assertEquals(MediaControlsPhase.ACTIVE, f.engine.state.value.phase)
            assertTrue(f.messages.isEmpty()); assertEquals("native-one", f.engine.state.value.selectedSessionId)
        }
    }
    @Test fun `unchanged metadata rescans do not rewrite Assets or turn a quiet replay into auto launch`(): Unit = runTest {
        Fixture(backgroundScope, listOf(media(image = byteArrayOf(1))), MediaControlsSettings(autoLaunch = true)).use { f ->
            runCurrent(); val writes = f.data.writes.size
            repeat(3) { f.now++; f.source.changed(); f.engine.resync(); runCurrent() }
            assertEquals(writes, f.data.writes.size); assertEquals(1, f.data.assets.size)
            assertEquals(DataMapValue.Bool(false), f.fields()["mediacontrols.watch_app_auto_launch_enabled"])
        }
    }
    @Test fun `semantic updates use a newer timestamp and permit configured auto launch`(): Unit = runTest {
        Fixture(backgroundScope, listOf(media()), MediaControlsSettings(autoLaunch = true)).use { f ->
            runCurrent(); f.now = 0
            f.source.values = listOf(media(title = "changed")); f.source.changed(); runCurrent()
            assertEquals(DataMapValue.LongNumber(101), f.fields()["elapsed_time"])
            assertEquals(DataMapValue.Bool(true), f.fields()["mediacontrols.watch_app_auto_launch_enabled"])
        }
    }
    @Test fun `selection favors playing priority then explicit preferred package and tracks session removal`(): Unit = runTest {
        val paused = media("paused", "paused.app", false); val playing = media("playing", "playing.app")
        Fixture(backgroundScope, listOf(paused, playing)).use { f ->
            runCurrent(); assertEquals("playing", f.engine.state.value.selectedSessionId)
            f.engine.setPreferredPackage("paused.app"); runCurrent()
            assertEquals("paused", f.engine.state.value.selectedSessionId)
            assertEquals("paused.app", f.preferences.load().preferredPackage)
            f.command(MediaCommand("play")); assertEquals("paused", f.source.executions.single().first)
            f.source.values = listOf(playing); f.source.changed(); runCurrent()
            assertEquals("playing", f.engine.state.value.selectedSessionId)
        }
    }
    @Test fun `unreadable snapshot preserves the last mirror and diagnostics exclude media contents`(): Unit = runTest {
        Fixture(backgroundScope, listOf(media())).use { f ->
            runCurrent(); val writes = f.data.writes.size
            f.source.failure = IllegalStateException("private song private native token")
            f.source.changed(); runCurrent()
            assertEquals(writes, f.data.writes.size); assertEquals(1, f.live().size)
            assertEquals(MediaControlsIssue.SOURCE, f.engine.state.value.lastIssue)
            assertTrue(f.logs.flatMap { it.details.values }.none { it.contains("private") })
        }
    }
    @Test fun `publication failure retains prior data and does not retire its artwork`(): Unit = runTest {
        Fixture(backgroundScope, listOf(media(image = byteArrayOf(1)))).use { f ->
            runCurrent(); f.data.failWrites = true
            f.source.values = listOf(media(title = "new", image = byteArrayOf(2))); f.source.changed(); runCurrent()
            assertEquals(MediaControlsPhase.FAILED, f.engine.state.value.phase)
            assertEquals(MediaControlsIssue.PUBLISH, f.engine.state.value.lastIssue)
            assertTrue(f.retired.isEmpty()); assertEquals(DataMapValue.Text("private song"), f.fields()["mediacontrols.title"])
            f.data.failWrites = false; f.engine.resync(); runCurrent()
            assertEquals(setOf(DataProtocol.digest(byteArrayOf(1))), f.retired.single())
            assertEquals(DataMapValue.Text("new"), f.fields()["mediacontrols.title"])
        }
    }
    @Test fun `empty authoritative sessions total disable and access withdrawal remove controls without native actions`(): Unit = runTest {
        Fixture(backgroundScope, listOf(media(image = byteArrayOf(1)))).use { f ->
            runCurrent(); f.engine.setEnabled(false); runCurrent()
            assertTrue(f.live().isEmpty()); assertEquals(MediaControlsPhase.DISABLED, f.engine.state.value.phase)
            assertFalse(f.preferences.load().enabled); assertEquals(1, f.retired.size)
            f.engine.setEnabled(true); runCurrent(); assertEquals(1, f.live().size)
            f.source.state.value = MediaSourceState(false, false); runCurrent()
            assertTrue(f.live().isEmpty()); assertEquals(MediaControlsPhase.ACCESS_REQUIRED, f.engine.state.value.phase)
            f.source.state.value = MediaSourceState(true, true); f.source.values = emptyList(); f.source.changed(); runCurrent()
            assertEquals(MediaControlsPhase.IDLE, f.engine.state.value.phase); assertTrue(f.source.executions.isEmpty())
        }
    }
    @Test fun `commands are session and AppKey scoped have no invented ACK and retain compound zero values`(): Unit = runTest {
        Fixture(backgroundScope, listOf(media())).use { f ->
            runCurrent()
            assertFalse(f.command(MediaCommand("play"), id = 9))
            assertFalse(f.command(MediaCommand("play"), app = ApplicationRoute("com.google.android.gms", "other")))
            assertTrue(f.command(MediaCommand("pause", 1, 0f, 0, 0, 0, "custom")))
            assertEquals(7L, f.engine.state.value.acceptedOperations)
            assertEquals(0L, f.source.executions.single().second.positionMillis)
            assertTrue(f.command(MediaCommand("future_action"))); assertEquals(1, f.source.executions.size)
            assertTrue(f.messages.isEmpty())
        }
    }
    @Test fun `native failure records actual failed operations while stale detach cannot withdraw a replacement session`(): Unit = runTest {
        Fixture(backgroundScope, listOf(media())).use { f ->
            runCurrent(); f.source.executeAction = { _, _ -> error("private native media details") }
            f.command(MediaCommand("play")); assertEquals(1L, f.engine.state.value.failedOperations)
            f.engine.detach(1); f.attach(2); runCurrent(); f.engine.detach(1); runCurrent()
            assertEquals(2L, f.engine.state.value.sessionId); assertEquals(1, f.live().size)
            assertEquals(DataMapValue.Bool(false), f.fields()["mediacontrols.watch_app_auto_launch_enabled"])
            assertTrue(f.logs.flatMap { it.details.values }.none { it.contains("private") })
        }
    }
    @Test fun `disconnect persists a tombstone and retires known artwork for later DataApi replay`(): Unit = runTest {
        Fixture(backgroundScope, listOf(media(image = byteArrayOf(1)))).use { f ->
            runCurrent(); f.engine.detach(1); runCurrent()
            assertTrue(f.live().isEmpty()); assertTrue(f.data.writes.last().deleted)
            assertEquals(setOf(DataProtocol.digest(byteArrayOf(1))), f.retired.single())
            assertEquals(MediaControlsPhase.DISCONNECTED, f.engine.state.value.phase)
        }
    }
    @Test fun `oversized artwork fails explicitly while preserving old controls and permits a later recovery`(): Unit = runTest {
        Fixture(backgroundScope, listOf(media(image = byteArrayOf(1))), config = MediaControlsConfig(maxImageBytes = 1)).use { f ->
            runCurrent(); f.source.values = listOf(media(image = byteArrayOf(1, 2))); f.source.changed(); runCurrent()
            assertEquals(MediaControlsPhase.FAILED, f.engine.state.value.phase); assertEquals(1, f.live().size)
            assertTrue(f.retired.isEmpty())
            f.source.values = listOf(media(image = byteArrayOf(3))); f.source.changed(); runCurrent()
            assertEquals(MediaControlsPhase.ACTIVE, f.engine.state.value.phase); assertEquals(1, f.retired.size)
        }
    }
    @Test fun `optional v2 waits for legacy data and coalesces without blocking native media commands`(): Unit = runTest {
        Fixture(backgroundScope, listOf(media(title = "old")), v2 = true).use { f ->
            val release = CompletableDeferred<Unit>(); f.data.barrierAction = { release.await() }
            runCurrent(); assertEquals(1, f.live().size); assertTrue(f.messages.isEmpty())
            f.command(MediaCommand("pause")); assertEquals(1, f.source.executions.size)
            f.source.values = listOf(media(title = "latest")); f.source.changed(); runCurrent()
            release.complete(Unit); runCurrent()
            val sent = f.messages.single(); assertEquals(route, sent.first); assertEquals(MediaProtocol.V2_PATH, sent.second)
            val fields = MediaV2Codec.decode(sent.third).fields
            assertEquals(DataMapValue.Text("latest"), fields["mediacontrols.title"])
            val legacyTime = (f.fields()["elapsed_time"] as DataMapValue.LongNumber).value
            assertEquals(DataMapValue.LongNumber(legacyTime + 1), fields["elapsed_time"])
            assertEquals(DataMapValue.LongNumber(legacyTime), fields["artwork_data_elapsed_time"])
        }
    }
    @Test fun `missing v2 progress proof keeps a fully usable legacy mirror without fabricated success`(): Unit = runTest {
        Fixture(backgroundScope, listOf(media()), v2 = true).use { f ->
            f.data.barrierStatus = DataSyncBarrierStatus.TIMED_OUT; runCurrent()
            assertTrue(f.messages.isEmpty()); assertEquals(1, f.live().size)
            assertEquals(MediaControlsPhase.ACTIVE, f.engine.state.value.phase)
            assertTrue(f.logs.any { it.event == "v2_waiting_for_dataapi" })
        }
    }
    @Test fun `late capability discovery enables optional v2 without replacing session or rewriting legacy data`(): Unit = runTest {
        Fixture(backgroundScope, listOf(media()), MediaControlsSettings(autoLaunch = true)).use { f ->
            runCurrent(); val writes = f.data.writes.size
            f.engine.setSupportsV2(99, true); runCurrent(); assertTrue(f.messages.isEmpty())
            f.engine.setSupportsV2(1, true); runCurrent()
            assertEquals(1, f.messages.size); assertEquals(writes, f.data.writes.size)
            assertEquals(1L, f.engine.state.value.sessionId)
            assertEquals(DataMapValue.Bool(false), MediaV2Codec.decode(f.messages.single().third).fields["mediacontrols.watch_app_auto_launch_enabled"])
            f.engine.setSupportsV2(1, false); f.source.values = listOf(media(title = "new")); f.source.changed(); runCurrent()
            assertEquals(1, f.messages.size)
        }
    }
}
