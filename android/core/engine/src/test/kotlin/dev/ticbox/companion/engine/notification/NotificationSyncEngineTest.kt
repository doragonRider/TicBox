@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.ticbox.companion.engine.notification

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.data.*
import dev.ticbox.companion.protocol.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class NotificationSyncEngineTest {
    @Test fun `unreadable allowlist storage never publishes an allow all default`(): Unit = runTest {
        val store = object : NotificationSettingsStore {
            override suspend fun load(): NotificationSyncSettings = error("unreadable preferences")
            override suspend fun save(settings: NotificationSyncSettings) = Unit
        }
        Fixture(backgroundScope, listOf(notification()), store).use { f ->
            runCurrent()
            assertTrue(f.live().isEmpty())
            assertFalse(f.engine.state.value.settings.enabled)
            assertEquals(NotificationSyncIssue.STORAGE, f.engine.state.value.lastIssue)
            f.engine.setEnabled(true); runCurrent()
            assertTrue(f.live().isEmpty(), "Explicitly enabling alone must retain the empty safe allowlist")
            f.engine.setPackageFilter(NotificationFilterMode.ALLOWLIST, setOf("example.app")); runCurrent()
            assertEquals(1, f.live().size)
        }
    }
    @Test fun `allowlist applies to unseen notifications and retains the other modes list`(): Unit = runTest {
        val settings = MemoryNotificationSettingsStore()
        Fixture(backgroundScope, listOf(notification(), notification("second", "another.app")), settings).use { f ->
            runCurrent()
            f.engine.setPackageFilter(NotificationFilterMode.BLOCKLIST, setOf("another.app")); runCurrent()
            f.engine.setPackageFilter(NotificationFilterMode.ALLOWLIST, setOf("another.app")); runCurrent()
            assertEquals(setOf(uri(notification("second", "another.app"))), f.live().map { it.uri }.toSet())
            f.source.active = f.source.active + notification("new", "new.app"); f.source.invalidate(); runCurrent()
            assertEquals(setOf(uri(notification("second", "another.app"))), f.live().map { it.uri }.toSet())
            f.engine.setPackageEnabled("new.app", true); runCurrent()
            assertEquals(2, f.live().size)
            assertEquals(setOf("another.app"), settings.load().blockedPackages)
            f.engine.setPackageFilter(NotificationFilterMode.ALLOWLIST, emptySet()); runCurrent()
            assertTrue(f.live().isEmpty())
            assertTrue(f.source.dismissals.isEmpty())
        }
    }
    private val peer = PeerAddress("AA:BB:CC:DD:EE:FF")
    private val route = ApplicationRoute("com.mobvoi.companion.at", "5cc9866db03e530a21f842a421c7eb42bac58961")
    private val scope = DataScope(peer, "phone", "watch", "network")
    private fun notification(key: String = "private-key", packageName: String = "example.app", text: String = "private-text", post: Long = 1000,
        localOnly: Boolean = false, image: ByteArray? = null, intentId: String = "opaque-action") = PhoneNotification(
        key, packageName, 7, null, post, localOnly,
        NotificationContent(NotificationCodec.textFields("App", "Title", text, 900, post, 999) + mapOf(
            "defaults" to DataMapValue.IntNumber(3), "vibrate" to DataMapValue.Longs(listOf(0, 200)), "has_sound" to DataMapValue.Bool(true),
            "content_intent_id" to DataMapValue.Text(intentId), "has_content_intent" to DataMapValue.Bool(true)),
            assets = image?.let { mapOf("icon" to NotificationAsset(it, mimeType = "image/webp")) } ?: emptyMap(),
            lists = mapOf("actions" to listOf(NotificationContent(NotificationCodec.actionFields("Reply", intentId),
                lists = mapOf("remote_inputs" to listOf(NotificationContent(NotificationCodec.remoteInputFields("reply-key", "Reply")))))))),
    )
    private fun uri(value: PhoneNotification, owner: String = "phone") = "wear://$owner" +
        NotificationProtocol.streamItemPath(owner, value.packageName, value.id, value.tag, value.key)

    private class Source : NotificationSource {
        override val state = MutableStateFlow(NotificationSourceState(true, true))
        override val events = MutableSharedFlow<NotificationSourceEvent>(extraBufferCapacity = 32)
        var active: List<PhoneNotification> = emptyList()
        var failure: Exception? = null
        val executions = mutableListOf<NotificationActionInvocation>()
        val dismissals = mutableListOf<String>()
        var dismissAccepted = true
        var removeAfterDismiss = true
        var executeAction: suspend (NotificationActionInvocation) -> NotificationActionOutcome = { NotificationActionOutcome(true) }
        override suspend fun snapshot(): List<PhoneNotification> { failure?.let { throw it }; return active.toList() }
        override suspend fun execute(action: NotificationActionInvocation): NotificationActionOutcome { executions += action; return executeAction(action) }
        override suspend fun dismiss(key: String): Boolean {
            dismissals += key
            if (dismissAccepted && removeAfterDismiss) active = active.filterNot { it.key == key }
            return dismissAccepted
        }
        fun invalidate() { events.tryEmit(NotificationSourceEvent.Invalidated) }
    }

    private inner class Data : DataSyncClient {
        override val state = MutableStateFlow(DataSyncState(DataSyncPhase.ACTIVE, scope, 1))
        val rows = linkedMapOf<Triple<DataScope, ApplicationRoute, String>, DataItemRecord>()
        val assets = linkedMapOf<String, DataAssetRecord>()
        val writes = mutableListOf<DataItemRecord>()
        var sequence = 0L
        var assetWrites = 0
        var failPublishing = false
        override suspend fun scopes(peer: PeerAddress?) = rows.values.map { it.scope }.distinct().filter { peer == null || it.peer == peer }
        override suspend fun items(scope: DataScope, query: DataItemQuery): DataItemPage {
            val selected = rows.values.filter { it.scope == scope && (query.route == null || query.route == it.route) &&
                (query.uriPrefix == null || it.uri.startsWith(query.uriPrefix!!)) && (query.includeDeleted || !it.deleted) }.sortedBy { it.uri }
            return DataItemPage(selected.drop(query.offset).take(query.limit), (query.offset + query.limit).takeIf { it < selected.size })
        }
        override suspend fun publish(scope: DataScope, route: ApplicationRoute, draft: DataItemDraft): DataItemRecord {
            if (failPublishing) error("fixture write failure")
            val record = DataItemRecord(scope, route, draft.uri, scope.localNodeId, ++sequence, draft.deleted, draft.data, draft.assets,
                draft.lastModifiedMillis, 1, local = true)
            rows[Triple(scope, route, draft.uri)] = record; writes += record; return record
        }
        override suspend fun delete(scope: DataScope, route: ApplicationRoute, uri: String) = publish(scope, route, DataItemDraft(uri, deleted = true))
        override suspend fun putAsset(scope: DataScope, route: ApplicationRoute, bytes: ByteArray): DataAssetRecord {
            assetWrites++; val digest = DataProtocol.digest(bytes)
            return DataAssetRecord(scope, digest, setOf(route), bytes, 1).also { assets[digest] = it }
        }
        override suspend fun asset(scope: DataScope, route: ApplicationRoute, digest: String) = assets[digest]?.takeIf { it.scope == scope && route in it.owners }
        override suspend fun requestAsset(scope: DataScope, route: ApplicationRoute, digest: String, permissionOnly: Boolean) {}
        override suspend fun awaitProgressBarrier(timeoutMillis: Long) = DataSyncBarrierResult(DataSyncBarrierStatus.COMPLETE, state.value.sessionId)
        override suspend fun requestSync() {}
        override fun close() {}
        fun seed(value: PhoneNotification, app: ApplicationRoute = route, owner: String = "phone", local: Boolean = true): DataItemRecord {
            val record = DataItemRecord(scope, app, uri(value, owner), if (local) "phone" else "watch", ++sequence, false,
                NotificationCodec.encode(value.content).data, receivedAtEpochMillis = 0, local = local)
            rows[Triple(scope, app, record.uri)] = record; return record
        }
        fun removed(value: PhoneNotification, app: ApplicationRoute = route, owner: String = "phone", source: String = "watch", local: Boolean = false): DataItemRecord {
            val record = DataItemRecord(scope, app, uri(value, owner), source, ++sequence, true, null, receivedAtEpochMillis = 1, local = local)
            rows[Triple(scope, app, record.uri)] = record; return record
        }
    }
    private inner class Fixture(parent: CoroutineScope, initial: List<PhoneNotification>, val settings: NotificationSettingsStore = MemoryNotificationSettingsStore(),
        config: NotificationSyncConfig = NotificationSyncConfig()) : AutoCloseable {
        val source = Source().apply { active = initial }
        val data = Data()
        val results = mutableListOf<Pair<Long, ByteArray>>()
        val logs = mutableListOf<ProtocolDiagnostic>()
        val retired = mutableListOf<Triple<DataScope, ApplicationRoute, Set<String>>>()
        var retirementAction: suspend (Set<String>) -> Unit = {}
        var now = 10_000L
        var revisions = 0L
        val engine = NotificationSyncEngine(parent, data, source, settings, DiagnosticSink { logs += it }, config, { now }, NotificationRevisionStore { revisions++ },
            NotificationAssetRetirement { assetScope, assetRoute, digests ->
                retired += Triple(assetScope, assetRoute, digests.toSet()); retirementAction(digests)
            })
        init { attach(1) }
        fun attach(id: Long) {
            data.state.value = data.state.value.copy(sessionId = id)
            engine.attach(NotificationSession(id, scope, route) { path, bytes -> assertEquals(NotificationProtocol.RESULT_PATH, path); results += id to bytes })
        }
        suspend fun action(invocation: String? = "call", intent: String? = "opaque-action", text: String? = null, command: String? = NotificationProtocol.BRIDGED_ACTION,
            stream: String? = null, session: Long = data.state.value.sessionId): Boolean = engine.handleMessage(peer, session, NotificationProtocol.RPC_PATH,
            NotificationRpcCodec.encodeRequest(NotificationActionRequest(intent, invocation, stream, 999, true,
                text?.let { mapOf("reply-key" to NotificationReply(stringResult = it)) } ?: emptyMap(), command)))
        fun live() = data.rows.values.filter { it.route == route && !it.deleted && it.uri.startsWith("wear://phone/") }
        override fun close() { engine.close() }
    }

    @Test fun `global gates silence new alerts without dropping selected cards`(): Unit = runTest {
        val store = MemoryNotificationSettingsStore(NotificationSyncSettings(soundEnabled = false, vibrationEnabled = false,
            appAlerts = mapOf("example.app" to NotificationAppAlertSettings(NotificationAlertMode.ALWAYS, NotificationAlertMode.ALWAYS))))
        Fixture(backgroundScope, emptyList(), store).use { f ->
            runCurrent()
            f.source.active = listOf(notification()); f.source.invalidate(); runCurrent()
            assertEquals(1, f.live().size)
            val fields = NotificationCodec.decode(f.live().single().data!!).fields
            assertEquals(DataMapValue.Bool(false), fields["has_sound"])
            assertEquals(DataMapValue.IntNumber(0), fields["defaults"]); assertNull(fields["vibrate"])
            assertEquals(1, f.engine.state.value.mirroredNotificationCount)
            assertEquals(0, f.engine.state.value.filteredNotificationCount)
        }
    }
    @Test fun `always alerts can request watch defaults for a silent phone notification`(): Unit = runTest {
        val store = MemoryNotificationSettingsStore(NotificationSyncSettings(appAlerts = mapOf("example.app" to
            NotificationAppAlertSettings(NotificationAlertMode.ALWAYS, NotificationAlertMode.ALWAYS))))
        Fixture(backgroundScope, emptyList(), store).use { f ->
            runCurrent()
            val silent = notification().copy(content = NotificationContent(mapOf("title_raw" to DataMapValue.Text("silent phone channel"),
                "has_sound" to DataMapValue.Bool(false), "vibrate" to DataMapValue.Longs(listOf(100, 0)))))
            f.source.active = listOf(silent); f.source.invalidate(); runCurrent()
            val fields = NotificationCodec.decode(f.live().single().data!!).fields
            assertEquals(DataMapValue.Bool(true), fields["has_sound"])
            assertEquals(DataMapValue.IntNumber(3), fields["defaults"])
            assertNull(fields["vibrate"])
            assertEquals(DataMapValue.Text("silent phone channel"), fields["title_raw"])
        }
    }
    @Test fun `package sound and vibration are independent and do not select an app`(): Unit = runTest {
        val store = MemoryNotificationSettingsStore(NotificationSyncSettings(mode = NotificationFilterMode.ALLOWLIST, allowedPackages = setOf("example.app")))
        Fixture(backgroundScope, emptyList(), store).use { f ->
            runCurrent()
            coroutineScope {
                launch { f.engine.setPackageSoundMode("example.app", NotificationAlertMode.NEVER) }
                launch { f.engine.setPackageVibrationMode("example.app", NotificationAlertMode.ALWAYS) }
                launch { f.engine.setPackageAlertSettings("blocked.app", NotificationAppAlertSettings(NotificationAlertMode.ALWAYS, NotificationAlertMode.ALWAYS)) }
            }
            runCurrent()
            assertEquals(NotificationAppAlertSettings(NotificationAlertMode.NEVER, NotificationAlertMode.ALWAYS), store.load().alertsFor("example.app"))
            assertEquals(setOf("example.app"), store.load().allowedPackages)
            f.source.active = listOf(notification(), notification("second", "blocked.app")); f.source.invalidate(); runCurrent()
            val fields = NotificationCodec.decode(f.live().single().data!!).fields
            assertEquals(DataMapValue.Bool(false), fields["has_sound"]); assertEquals(DataMapValue.IntNumber(2), fields["defaults"])
            f.engine.setPackageEnabled("example.app", false); runCurrent()
            assertEquals(NotificationAlertMode.NEVER, store.load().alertsFor("example.app").sound)
            f.engine.setPackageAlertSettings("example.app", NotificationAppAlertSettings()); runCurrent()
            assertFalse("example.app" in store.load().appAlerts)
        }
    }
    @Test fun `changing alert settings rewrites an old card quietly without revision or replay loops`(): Unit = runTest {
        Fixture(backgroundScope, emptyList()).use { f ->
            runCurrent(); f.source.active = listOf(notification()); f.source.invalidate(); runCurrent()
            val revision = NotificationCodec.decode(f.live().single().data!!).fields["revision"]
            assertEquals(DataMapValue.Bool(true), NotificationCodec.decode(f.live().single().data!!).fields["has_sound"])
            val changes: List<suspend () -> Unit> = listOf(
                { f.engine.setPackageSoundMode("example.app", NotificationAlertMode.NEVER) },
                { f.engine.setPackageSoundMode("example.app", NotificationAlertMode.ALWAYS) },
                { f.engine.setVibrationEnabled(false) }, { f.engine.setVibrationEnabled(true) },
                { f.engine.setSoundEnabled(false) }, { f.engine.setSoundEnabled(true) })
            for (change in changes) {
                change(); runCurrent()
                val fields = NotificationCodec.decode(f.live().single().data!!).fields
                assertEquals(DataMapValue.Bool(false), fields["has_sound"])
                assertEquals(DataMapValue.Bool(true), fields["only_alert_once"])
                assertNull(fields["defaults"]); assertNull(fields["vibrate"]); assertEquals(revision, fields["revision"])
                val writes = f.data.writes.size
                f.source.invalidate(); f.engine.resync(); runCurrent(); assertEquals(writes, f.data.writes.size)
            }
            f.source.active = listOf(notification(text = "a genuinely new update", post = 2000)); f.source.invalidate(); runCurrent()
            val fields = NotificationCodec.decode(f.live().single().data!!).fields
            assertEquals(DataMapValue.Bool(true), fields["has_sound"])
            assertEquals(DataMapValue.IntNumber(3), fields["defaults"]); assertNotEquals(revision, fields["revision"])
            f.engine.detach(1); f.attach(2); runCurrent()
            assertEquals(DataMapValue.Bool(false), NotificationCodec.decode(f.live().single().data!!).fields["has_sound"])
        }
    }
    @Test fun `new source versions alongside a settings update still receive their selected alerts`(): Unit = runTest {
        Fixture(backgroundScope, emptyList()).use { f ->
            runCurrent(); f.source.active = listOf(notification(), notification("newer")); f.source.invalidate(); runCurrent()
            val revision = NotificationCodec.decode(f.live().single { it.uri == uri(notification()) }.data!!).fields["revision"]
            f.source.active = listOf(notification(), notification("newer", text = "new content", post = 2000))
            f.engine.setPackageSoundMode("example.app", NotificationAlertMode.NEVER); runCurrent()
            val cards = f.live().map { NotificationCodec.decode(it.data!!).fields }
            val old = cards.single { it["post_time_millis"] == DataMapValue.LongNumber(1000) }
            val fresh = cards.single { it["post_time_millis"] == DataMapValue.LongNumber(2000) }
            assertEquals(revision, old["revision"]); assertNull(old["vibrate"])
            assertEquals(DataMapValue.Bool(false), fresh["has_sound"])
            assertEquals(DataMapValue.IntNumber(2), fresh["defaults"])
            assertEquals(DataMapValue.Longs(listOf(0, 200)), fresh["vibrate"])
            assertNotEquals(DataMapValue.Bool(true), fresh["only_alert_once"])
        }
    }
    @Test fun `a failed policy rewrite retries quietly and a storage failure keeps the previous policy`(): Unit = runTest {
        val base = MemoryNotificationSettingsStore(); var failSave = false
        val store = object : NotificationSettingsStore {
            override suspend fun load() = base.load()
            override suspend fun save(settings: NotificationSyncSettings) { if (failSave) error("private package names must not be logged"); base.save(settings) }
        }
        Fixture(backgroundScope, emptyList(), store).use { f ->
            runCurrent(); f.source.active = listOf(notification()); f.source.invalidate(); runCurrent()
            f.data.failPublishing = true
            f.engine.setSoundEnabled(false); runCurrent()
            assertEquals(NotificationSyncIssue.PUBLISH_FAILED, f.engine.state.value.lastIssue)
            f.data.failPublishing = false; f.engine.resync(); runCurrent()
            val fields = NotificationCodec.decode(f.live().single().data!!).fields
            assertEquals(DataMapValue.Bool(false), fields["has_sound"]); assertNull(fields["vibrate"])
            failSave = true
            assertFailsWith<IllegalStateException> { f.engine.setVibrationEnabled(false) }
            assertTrue(f.engine.state.value.settings.vibrationEnabled); assertTrue(base.load().vibrationEnabled)
            assertEquals(NotificationSyncIssue.STORAGE, f.engine.state.value.lastIssue)
            assertFalse(f.logs.flatMap { it.details.values }.any { "private package" in it })
        }
    }
    @Test fun `invalid package rules cannot mutate settings`(): Unit = runTest {
        Fixture(backgroundScope, emptyList()).use { f ->
            runCurrent()
            for (name in listOf("", "bad package", "../other", "x".repeat(256)))
                assertFailsWith<IllegalArgumentException> { f.engine.setPackageSoundMode(name, NotificationAlertMode.ALWAYS) }
            assertTrue(f.engine.state.value.settings.appAlerts.isEmpty())
        }
    }

    @Test fun `active snapshot publishes companion DataItems with quiet content and separate Assets`(): Unit = runTest {
        val image = byteArrayOf(1, 2, 3)
        Fixture(backgroundScope, listOf(notification(image = image), notification("local", localOnly = true))).use { f ->
            runCurrent()
            val item = f.live().single()
            assertEquals(route, item.route); assertEquals("phone", item.sourceNodeId)
            val fields = NotificationCodec.decode(item.data!!).fields
            assertNull(fields["defaults"]); assertNull(fields["vibrate"])
            assertEquals(DataMapValue.Bool(false), fields["has_sound"])
            assertEquals(DataMapValue.Bool(true), fields["only_alert_once"])
            assertEquals(DataMapValue.LongNumber(0), fields["revision"])
            assertEquals(DataMapValue.AssetIndex(0), fields["icon"])
            assertEquals("0", item.assets.single().name)
            assertContentEquals(image, f.data.assets.getValue(item.assets.single().digest).data)
            assertEquals(1, f.engine.state.value.filteredNotificationCount)
        }
    }
    @Test fun `local write diagnostics identify actual publishes without leaking notification content`(): Unit = runTest {
        Fixture(backgroundScope, listOf(notification(image = byteArrayOf(1)))).use { f ->
            runCurrent()
            val first = f.logs.single { it.event == "notification_local_write" }
            assertEquals(mapOf("operation" to "publish", "sequenceId" to "1", "assetCount" to "1", "quietReplay" to "true"), first.details)
            assertEquals(1L, first.sessionId)
            f.engine.resync(); runCurrent()
            assertEquals(1, f.logs.count { it.event == "notification_local_write" })
            f.source.active = listOf(notification(text = "new private text", post = 1001, image = byteArrayOf(1)))
            f.source.invalidate(); runCurrent()
            val events = f.logs.filter { it.event == "notification_local_write" }
            assertEquals(listOf("true", "false"), events.map { it.details["quietReplay"] })
            assertTrue(events.flatMap { it.details.values }.none { it.contains("private") || it.contains("opaque-action") })
        }
    }

    @Test fun `unchanged rescan does not rewrite reallocate revision or upload images`(): Unit = runTest {
        Fixture(backgroundScope, listOf(notification(image = byteArrayOf(1)))).use { f ->
            runCurrent(); val writes = f.data.writes.size
            repeat(3) { f.source.invalidate(); f.engine.resync(); runCurrent() }
            assertEquals(writes, f.data.writes.size); assertEquals(1, f.data.assetWrites); assertEquals(1, f.revisions)
        }
    }
    @Test fun `material changes create a new revision while fresh reconnect reuses it quietly`(): Unit = runTest {
        Fixture(backgroundScope, listOf(notification())).use { f ->
            runCurrent()
            f.source.active = listOf(notification(text = "changed")); f.source.invalidate(); runCurrent()
            val changed = f.live().single()
            assertEquals(DataMapValue.LongNumber(1), NotificationCodec.decode(changed.data!!).fields["revision"])
            assertEquals(DataMapValue.Bool(true), NotificationCodec.decode(changed.data!!).fields["has_sound"])
            f.engine.detach(1); f.attach(2); runCurrent()
            assertEquals(DataMapValue.LongNumber(1), NotificationCodec.decode(f.live().single().data!!).fields["revision"])
            assertEquals(DataMapValue.Bool(false), NotificationCodec.decode(f.live().single().data!!).fields["has_sound"])
            assertEquals(2, f.revisions)
        }
    }
    @Test fun `authoritative snapshot removes stale own items while retaining other routes and owners`(): Unit = runTest {
        Fixture(backgroundScope, listOf(notification())).use { f ->
            val stale = f.data.seed(notification("stale"))
            val otherRoute = ApplicationRoute("other.app", "cert")
            val foreign = f.data.seed(notification("foreign"), otherRoute)
            val watchOwned = f.data.seed(notification("watch-own"), owner = "watch")
            runCurrent()
            assertTrue(f.data.rows.getValue(Triple(scope, route, stale.uri)).deleted)
            assertFalse(f.data.rows.getValue(Triple(scope, otherRoute, foreign.uri)).deleted)
            assertFalse(f.data.rows.getValue(Triple(scope, route, watchOwned.uri)).deleted)
        }
    }
    @Test fun `unreadable or disconnected source does not turn its snapshot into an empty deletion batch`(): Unit = runTest {
        Fixture(backgroundScope, listOf(notification())).use { f ->
            runCurrent(); val before = f.data.writes.size
            f.source.failure = IllegalStateException("private body must not be logged")
            f.source.invalidate(); runCurrent()
            assertEquals(before, f.data.writes.size); assertEquals(NotificationSyncIssue.SNAPSHOT_FAILED, f.engine.state.value.lastIssue)
            f.source.failure = null; f.source.state.value = NotificationSourceState(true, false); runCurrent()
            assertEquals(before, f.data.writes.size); assertEquals(NotificationSyncPhase.LISTENER_DISCONNECTED, f.engine.state.value.phase)
            assertTrue(f.logs.flatMap { it.details.values }.none { it.contains("private body") })
        }
    }
    @Test fun `application filters total switch and local only changes withdraw mirrors without cancelling phone`(): Unit = runTest {
        val settings = MemoryNotificationSettingsStore()
        Fixture(backgroundScope, listOf(notification(), notification("second", "another.app")), settings).use { f ->
            runCurrent(); assertEquals(2, f.live().size)
            f.engine.setPackageEnabled("example.app", false); runCurrent()
            assertEquals(1, f.live().size); assertTrue("example.app" in settings.load().blockedPackages)
            f.engine.setPackageEnabled("example.app", true); runCurrent(); assertEquals(2, f.live().size)
            f.source.active = listOf(notification(localOnly = true)); f.source.invalidate(); runCurrent()
            assertTrue(f.live().isEmpty()); assertTrue(f.source.dismissals.isEmpty())
            f.source.active = listOf(notification()); f.source.invalidate(); runCurrent(); assertEquals(1, f.live().size)
            f.engine.setEnabled(false); runCurrent(); assertTrue(f.live().isEmpty()); assertFalse(settings.load().enabled)
            assertEquals(NotificationSyncPhase.DISABLED, f.engine.state.value.phase)
        }
    }
    @Test fun `new remote deletion cancels current phone notification and prevents immediate reappearance`(): Unit = runTest {
        val phone = notification()
        Fixture(backgroundScope, listOf(phone)).use { f ->
            runCurrent(); f.source.removeAfterDismiss = false
            val removal = f.data.removed(phone)
            assertTrue(f.engine.offerRemoteItem(1, removal)); runCurrent()
            assertEquals(listOf(phone.key), f.source.dismissals); assertTrue(f.live().isEmpty())
            f.source.invalidate(); runCurrent(); assertTrue(f.live().isEmpty())
            f.source.active = listOf(phone.copy(postTimeMillis = 2000)); f.source.invalidate(); runCurrent()
            assertEquals(1, f.live().size)
        }
    }
    @Test fun `failed native dismissal does not suppress a phone notification that remains active`(): Unit = runTest {
        val phone = notification()
        Fixture(backgroundScope, listOf(phone)).use { f ->
            runCurrent(); f.source.dismissAccepted = false
            assertTrue(f.engine.offerRemoteItem(1, f.data.removed(phone))); runCurrent()
            assertEquals(listOf(phone.key), f.source.dismissals); assertEquals(1, f.live().size)
            assertEquals(NotificationSyncIssue.ACTION_FAILED, f.engine.state.value.lastIssue)
        }
    }
    @Test fun `watch deletion retires known published Assets after native acceptance or confirmed source absence`(): Unit = runTest {
        val first = notification(image = byteArrayOf(1))
        Fixture(backgroundScope, listOf(first)).use { f ->
            runCurrent(); f.source.removeAfterDismiss = false
            f.retirementAction = { assertTrue(f.live().isEmpty()) }
            assertTrue(f.engine.offerRemoteItem(1, f.data.removed(first))); runCurrent()
            assertEquals(listOf(Triple(scope, route, setOf(DataProtocol.digest(byteArrayOf(1))))), f.retired)
            assertEquals(listOf(first.key), f.source.dismissals)
            val next = notification(image = byteArrayOf(2), post = 2000)
            f.source.active = listOf(next); f.source.invalidate(); runCurrent()
            assertEquals(1, f.live().size)
            f.source.active = emptyList() // Source absence is confirmed by the deletion handler's snapshot.
            assertTrue(f.engine.offerRemoteItem(1, f.data.removed(next))); runCurrent()
            assertEquals(Triple(scope, route, setOf(DataProtocol.digest(byteArrayOf(2)))), f.retired.last())
            assertEquals(2, f.retired.size); assertEquals(1, f.source.dismissals.size)
        }
    }
    @Test fun `rejected watch dismissal keeps its old Assets and republishes the still active notification`(): Unit = runTest {
        val phone = notification(image = byteArrayOf(1))
        Fixture(backgroundScope, listOf(phone)).use { f ->
            runCurrent(); f.source.dismissAccepted = false
            assertTrue(f.engine.offerRemoteItem(1, f.data.removed(phone))); runCurrent()
            assertTrue(f.retired.isEmpty())
            assertEquals(setOf(DataProtocol.digest(byteArrayOf(1))), f.live().single().assets.map { it.digest }.toSet())
            assertEquals(listOf(phone.key), f.source.dismissals)
        }
    }
    @Test fun `stale session foreign AppKey owner and local echo deletions cannot cancel phone notifications`(): Unit = runTest {
        val phone = notification()
        Fixture(backgroundScope, listOf(phone)).use { f ->
            runCurrent()
            assertFalse(f.engine.offerRemoteItem(99, f.data.removed(phone)))
            assertFalse(f.engine.offerRemoteItem(1, f.data.removed(phone, app = ApplicationRoute("other.app", "cert"))))
            assertFalse(f.engine.offerRemoteItem(1, f.data.removed(phone, owner = "watch")))
            assertFalse(f.engine.offerRemoteItem(1, f.data.removed(phone, source = "phone")))
            assertFalse(f.engine.offerRemoteItem(1, f.data.removed(phone, local = true)))
            runCurrent(); assertTrue(f.source.dismissals.isEmpty())
        }
    }
    @Test fun `actual opaque action result and Unicode replies reach the callback without strict index hint checks`(): Unit = runTest {
        Fixture(backgroundScope, listOf(notification())).use { f ->
            runCurrent()
            assertTrue(f.action(text = "你好 ✓", stream = uri(notification("wrong-hint"))))
            val execution = f.source.executions.single()
            assertEquals("opaque-action", execution.intentId); assertEquals("你好 ✓", execution.replies["reply-key"]?.stringResult)
            val result = NotificationRpcCodec.decodeResult(f.results.single().second)
            assertEquals(true, result.successful); assertEquals("call", result.invocationId); assertEquals(1, result.messageId)
            assertTrue(f.logs.flatMap { it.details.values }.none { it.contains("你好") || it.contains("opaque-action") || it.contains("private-key") })
        }
    }
    @Test fun `failed native action returns false and unknown RPC command produces no callback ACK`(): Unit = runTest {
        Fixture(backgroundScope, listOf(notification())).use { f ->
            runCurrent(); f.source.executeAction = { NotificationActionOutcome(false, NotificationActionFailure.CANCELLED) }
            f.action(); assertEquals(false, NotificationRpcCodec.decodeResult(f.results.single().second).successful)
            f.action(invocation = "unknown", command = "future-command")
            assertEquals(1, f.source.executions.size); assertEquals(1, f.results.size)
            assertTrue(f.logs.any { it.event == "unsupported_rpc_command" })
        }
    }
    @Test fun `same invocation retries deduplicate across reconnect while changed action payload executes`(): Unit = runTest {
        Fixture(backgroundScope, listOf(notification())).use { f ->
            runCurrent(); f.action(text = "one"); f.action(text = "one")
            assertEquals(1, f.source.executions.size); assertEquals(2, f.results.size)
            f.engine.detach(1); f.attach(2); runCurrent(); f.action(text = "one")
            assertEquals(1, f.source.executions.size); assertEquals(2L, f.results.last().first)
            f.action(text = "different")
            assertEquals(2, f.source.executions.size)
            f.now += 60_001; f.action(text = "one"); assertEquals(3, f.source.executions.size)
        }
    }
    @Test fun `concurrent callback retry executes once and native completion survives a reconnect without stale result delivery`(): Unit = runTest {
        Fixture(backgroundScope, listOf(notification())).use { f ->
            runCurrent(); val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.source.executeAction = { started.complete(Unit); release.await(); NotificationActionOutcome(true) }
            val original = async { f.action(text = "once") }; runCurrent(); started.await()
            f.engine.detach(1); f.attach(2); runCurrent()
            val retry = async { f.action(text = "once") }; runCurrent(); release.complete(Unit)
            original.await(); retry.await(); runCurrent()
            assertEquals(1, f.source.executions.size); assertEquals(listOf(2L), f.results.map { it.first })
        }
    }
    @Test fun `bounded action cache evicts old callbacks and missing invocation does not deduplicate real repeated taps`(): Unit = runTest {
        Fixture(backgroundScope, listOf(notification()), config = NotificationSyncConfig(actionCacheEntries = 1)).use { f ->
            runCurrent(); f.action("one"); f.action("two"); f.action("one")
            assertEquals(3, f.source.executions.size)
            f.action(null); f.action(null)
            assertEquals(5, f.source.executions.size); assertEquals(3, f.results.size)
        }
    }
    @Test fun `changed Asset bytes cause a content update and write failures retain truthful current and stale counts`(): Unit = runTest {
        Fixture(backgroundScope, listOf(notification(image = byteArrayOf(1)))).use { f ->
            runCurrent(); f.source.active = listOf(notification(image = byteArrayOf(2))); f.source.invalidate(); runCurrent()
            assertEquals(2, f.data.assetWrites); assertEquals(2, f.revisions)
            f.data.failPublishing = true; f.source.active = listOf(notification("new-key")); f.source.invalidate(); runCurrent()
            assertEquals(NotificationSyncIssue.PUBLISH_FAILED, f.engine.state.value.lastIssue)
            assertEquals(0, f.engine.state.value.mirroredNotificationCount)
            assertEquals(1, f.engine.state.value.pendingRemovalCount)
            assertEquals(NotificationSyncPhase.FAILED, f.engine.state.value.phase)
            assertEquals(1, f.live().size)
            assertTrue(f.live().none { NotificationProtocol.parseStreamItemPath(it.uri)?.notificationKey == "new-key" })
            f.data.failPublishing = false; f.engine.resync(); runCurrent()
            assertEquals(1, f.engine.state.value.mirroredNotificationCount)
            assertEquals(0, f.engine.state.value.pendingRemovalCount)
            assertEquals(NotificationSyncPhase.ACTIVE, f.engine.state.value.phase)
        }
    }
    @Test fun `only replaced Asset digests retire after publication and remaining digests retire after withdrawal`(): Unit = runTest {
        val first = byteArrayOf(1); val second = byteArrayOf(2); val shared = byteArrayOf(3)
        fun withShared(value: PhoneNotification) = value.copy(content = value.content.copy(
            assets = value.content.assets + ("background" to NotificationAsset(shared))))
        Fixture(backgroundScope, listOf(withShared(notification(image = first)))).use { f ->
            runCurrent(); assertTrue(f.retired.isEmpty())
            val newDigests = setOf(DataProtocol.digest(second), DataProtocol.digest(shared))
            f.retirementAction = { assertEquals(newDigests, f.live().single().assets.map { it.digest }.toSet()) }
            f.source.active = listOf(withShared(notification(image = second))); f.source.invalidate(); runCurrent()
            assertEquals(listOf(Triple(scope, route, setOf(DataProtocol.digest(first)))), f.retired)
            f.engine.resync(); runCurrent(); assertEquals(1, f.retired.size)
            f.retirementAction = { assertTrue(f.live().isEmpty()) }
            f.source.active = emptyList(); f.source.invalidate(); runCurrent()
            assertEquals(Triple(scope, route, newDigests), f.retired.last())
            assertEquals(2, f.retired.size)
        }
    }
    @Test fun `failed publication and failed deletion never retire the old live notification Assets`(): Unit = runTest {
        val first = byteArrayOf(1); val second = byteArrayOf(2)
        Fixture(backgroundScope, listOf(notification(image = first))).use { f ->
            runCurrent(); f.data.failPublishing = true
            f.source.active = listOf(notification(image = second)); f.source.invalidate(); runCurrent()
            assertTrue(f.retired.isEmpty())
            assertEquals(setOf(DataProtocol.digest(first)), f.live().single().assets.map { it.digest }.toSet())
            f.source.active = emptyList(); f.source.invalidate(); runCurrent()
            assertTrue(f.retired.isEmpty()); assertEquals(1, f.engine.state.value.pendingRemovalCount)
            f.data.failPublishing = false; f.engine.resync(); runCurrent()
            assertEquals(listOf(Triple(scope, route, setOf(DataProtocol.digest(first)))), f.retired)
            assertTrue(f.live().isEmpty()); assertEquals(0, f.engine.state.value.pendingRemovalCount)
        }
    }
    @Test fun `turning off notification sync retires Assets only after successful mirror withdrawal`(): Unit = runTest {
        val image = byteArrayOf(4)
        Fixture(backgroundScope, listOf(notification(image = image))).use { f ->
            runCurrent(); f.data.failPublishing = true
            f.engine.setEnabled(false); runCurrent()
            assertTrue(f.retired.isEmpty()); assertEquals(1, f.live().size)
            f.data.failPublishing = false; f.engine.resync(); runCurrent()
            assertEquals(listOf(Triple(scope, route, setOf(DataProtocol.digest(image)))), f.retired)
            assertTrue(f.live().isEmpty()); assertEquals(NotificationSyncPhase.DISABLED, f.engine.state.value.phase)
        }
    }
    @Test fun `cache retirement failures preserve successful mirror state and log only safe metadata`(): Unit = runTest {
        Fixture(backgroundScope, listOf(notification(image = byteArrayOf(1)))).use { f ->
            runCurrent()
            f.retirementAction = { error("private cached notification contents") }
            f.source.active = listOf(notification(image = byteArrayOf(2))); f.source.invalidate(); runCurrent()
            assertEquals(NotificationSyncPhase.ACTIVE, f.engine.state.value.phase)
            assertEquals(1, f.engine.state.value.mirroredNotificationCount)
            assertNull(f.engine.state.value.lastIssue)
            f.source.active = emptyList(); f.source.invalidate(); runCurrent()
            assertEquals(NotificationSyncPhase.ACTIVE, f.engine.state.value.phase)
            assertEquals(0, f.engine.state.value.mirroredNotificationCount)
            assertEquals(0, f.engine.state.value.pendingRemovalCount)
            assertNull(f.engine.state.value.lastIssue)
            assertEquals(2, f.logs.count { it.event == "notification_asset_retirement_failed" })
            assertTrue(f.logs.flatMap { it.details.values }.none { it.contains("private cached") })
        }
    }
    @Test fun `DataApi remote observer sees only applied updates and its failure cannot freeze data sync`(): Unit = runTest {
        val store = MemoryDataSyncStore(); val logs = mutableListOf<ProtocolDiagnostic>(); var observed = 0
        val engine = DataSyncEngine(backgroundScope, store, diagnostics = DiagnosticSink { logs += it })
        engine.setRemoteItemObserver { _, _ -> observed++; error("fixture observer failure") }
        engine.attach(DataSyncSession(peer, 1, "phone", "watch", "network", route, {}, negotiatedWireVersion = 1)); runCurrent()
        val wire = DataProtocol.wrapDataItem(DataProtocol.encodeItem(DataWireItem(DataAppKey(route.packageName, route.packageDigest),
            "wear://phone/test", 1, "watch", true, null)))
        assertTrue(engine.acceptInbound(1, wire)); runCurrent()
        assertTrue(engine.acceptInbound(1, wire)); runCurrent()
        assertEquals(1, observed); assertEquals(1L, engine.state.value.receivedItems)
        assertEquals(1L, engine.state.value.duplicateItems)
        assertNotEquals(DataSyncPhase.NEEDS_RESYNC, engine.state.value.phase)
        assertTrue(logs.any { it.event == "remote_item_observer_failed" })
        engine.close()
    }
}
