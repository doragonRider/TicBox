@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.ticbox.companion.engine.watchface

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.CompanionFeatureSession
import dev.ticbox.companion.protocol.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*

class WatchFaceManagementEngineTest {
    @Test fun `photo preview accepts large signed byte arrays within the JSON byte budget`() {
        val image = ByteArray(60_000) { (it % 256).toByte() }
        val payload = "{\"class_name\":\"photo/C\",\"image\":[" + image.joinToString(",") + "]}"
        val decoded = WatchFaceMobvoiJson.image(payload.toByteArray())
        assertEquals("photo/C", decoded.first)
        assertContentEquals(image, decoded.second!!.bytes)
    }

    @Test fun `photo JSON retains byte and nesting budgets without counting array punctuation as fields`() {
        assertFailsWith<IllegalArgumentException> { WatchFaceMobvoiJson.parse(ByteArray(1024 * 1024 + 1)) }
        assertFailsWith<IllegalArgumentException> {
            WatchFaceMobvoiJson.parse(("{\"unknown\":" + "[".repeat(33) + "0" + "]".repeat(33) + "}").toByteArray())
        }
        val escaped = WatchFaceMobvoiJson.settings("""{"name":"commas,,,, and brackets [ are text","category":2}""".toByteArray())
        assertEquals("commas,,,, and brackets [ are text", escaped.name)
    }
    @Test fun `inventory reads actual installed active favorite and order data plus preview Assets`() = runTest {
        Fixture(backgroundScope).use { f ->
            f.data.rows += row(WatchFaceProtocol.INSTALLED_PREFIX + "p/C", DataMapCodec.encode(mapOf(
                "KEY_WATCH_FACE_PACKAGE" to DataMapValue.Text("p"), "KEY_WATCH_FACE_ACTIVITY" to DataMapValue.Text("C"),
                "KEY_WATCH_FACE_DISPLAYED_NAME" to DataMapValue.Text("Local face"), "KEY_WATCH_FACE_PREVIEW" to DataMapValue.AssetIndex(0),
            )), listOf(DataAssetReference("0", "preview")))
            f.data.rows += row(WatchFaceProtocol.ACTIVE_PATH, ws(1, "p/C") + wi(2, 7))
            f.data.rows += row(WatchFaceProtocol.ORDER_PATH, b(1, byteArrayOf(7)))
            f.data.rows += row(WatchFaceProtocol.FAVORITE_PREFIX + "7/", wi(1, 7) + ws(2, "p/C") + ws(5, "7-preview"), listOf(DataAssetReference("7-preview", "preview")))
            f.data.rows += row(WatchFaceProtocol.INSTALLED_PREFIX + "foreign/C", byteArrayOf(), owner = "different")
            f.data.assetBytes = byteArrayOf(1, 2, 3)
            val state = f.engine.refresh()
            assertEquals(listOf("p/C"), state.installed.map { it.componentName })
            assertEquals("preview", state.installed.single().previewDigest)
            assertEquals(WatchFaceCurrent("p/C", 7), state.current)
            assertEquals(listOf(7), state.favoriteOrder)
            assertEquals("preview", state.favorites.single().previewDigest)
            assertFalse(state.inventoryComplete)
            assertContentEquals(byteArrayOf(1, 2, 3), f.engine.previewAsset("preview")!!.bytes)
        }
    }

    @Test fun `favorite operations wait for ordinary matching routed responses and keep unknown statuses`() = runTest {
        Fixture(backgroundScope).use { f ->
            val task = async { f.engine.changeActiveFavorite(7) }; runCurrent()
            val sent = f.messages.single(); assertEquals(WatchFaceProtocol.CHANGE_ACTIVE_PATH, sent.path)
            assertFalse(f.engine.offerMessage(peer, 2, WatchFaceProtocol.OPERATION_RESPONSE, wi(1, 1), route))
            assertFalse(f.engine.offerMessage(peer, 1, WatchFaceProtocol.OPERATION_RESPONSE, wi(1, 1), ApplicationRoute("other", "cert")))
            assertTrue(f.engine.offerMessage(peer, 1, WatchFaceProtocol.OPERATION_RESPONSE, wi(1, 99), route)); runCurrent()
            assertFalse(task.isCompleted)
            f.engine.offerMessage(peer, 1, WatchFaceProtocol.OPERATION_RESPONSE, wi(1, 1), route); runCurrent()
            assertEquals(WatchFaceOutcome.ACKNOWLEDGED, task.await().outcome)
            val remove = async { f.engine.removeFavorite(7, 8) }; runCurrent()
            f.engine.offerMessage(peer, 1, WatchFaceProtocol.OPERATION_RESPONSE, wi(1, 2) + n(2, 47), route); runCurrent()
            assertEquals(WatchFaceOutcome.UNKNOWN, remove.await().outcome)
        }
    }

    @Test fun `automatic inventory refresh survives a detached session during a cache read`() = runTest {
        Fixture(backgroundScope).use { f ->
            val gate = CompletableDeferred<Unit>()
            f.data.beforeQuery = { f.data.beforeQuery = {}; gate.await() }
            f.data.rows += row(WatchFaceProtocol.ACTIVE_PATH, ws(1, "p/C") + wi(2, 7))
            runCurrent()
            f.engine.detach(1)
            f.engine.attach(CompanionFeatureSession(2, dataScope, route, { _, _, _ -> },
                { _, _, _, _ -> error("No RequestApi expected") }, { _, _ -> error("No Channel expected") }))
            gate.complete(Unit)
            runCurrent()
            assertEquals(2L, f.engine.state.value.sessionId)
            assertEquals(WatchFaceCurrent("p/C", 7), f.engine.state.value.current)
        }
    }

    @Test fun `missing acknowledgement timeouts differ from a submitted Mobvoi selector`() = runTest {
        Fixture(backgroundScope, timeout = 100).use { f ->
            val pending = async { f.engine.addFavorite("p/C") }; runCurrent(); advanceTimeBy(101); runCurrent()
            assertEquals(WatchFaceOutcome.TIMED_OUT, pending.await().outcome)
            assertEquals(WatchFaceOutcome.SUBMITTED, f.engine.selectMobvoiFace(WatchFacePhotoSettings("p", "C", 2)).outcome)
            assertEquals(WatchFaceProtocol.MOBVOI_SET, f.messages.last().path)
            assertTrue(f.messages.last().bytes.toString(Charsets.UTF_8).contains("\"pkg\":\"p\""))
        }
    }

    @Test fun `photo query signed image array and combined save use same-path and shared confirmations`() = runTest {
        Fixture(backgroundScope).use { f ->
            f.onMessage = { sent -> when (sent.path) {
                WatchFaceProtocol.MOBVOI_PHOTO_INFO -> f.engine.offerMessage(peer, 1, sent.path, "{\"pkg\":\"p\",\"class_name\":\"C\",\"category\":1,\"show_battery\":1}".toByteArray(), route)
                WatchFaceProtocol.MOBVOI_IMAGE -> f.engine.offerMessage(peer, 1, sent.path, "{\"class_name\":\"C\",\"image\":[-1,0,127,-128]}".toByteArray(), route)
                WatchFaceProtocol.MOBVOI_PHOTO_COMBINED -> f.engine.offerMessage(peer, 1, WatchFaceProtocol.MOBVOI_PHOTO_CONFIRM, byteArrayOf(), route)
            } }
            val photo = f.engine.queryPhoto()
            assertEquals(1, photo.settings!!.showBattery)
            assertContentEquals(byteArrayOf(-1, 0, 127, -128), photo.image!!.bytes)
            val result = f.engine.setPhoto(photo.settings, byteArrayOf(8, 9))
            assertEquals(WatchFaceOutcome.ACKNOWLEDGED, result.outcome)
            assertEquals("uncorrelated_background_save_confirmation", result.detail)
            assertContentEquals(byteArrayOf(8, 9), WatchFacePhotoProtocol.split(f.messages.last().bytes).second)
            assertContentEquals(byteArrayOf(8, 9), f.engine.state.value.photo!!.image!!.bytes)
        }
    }

    @Test fun `full editor flow starts collects metadata renders style and complications then commits`() = runTest {
        Fixture(backgroundScope).use { f ->
            f.channel.respond = { body ->
                val request = ProtoWire.decodeFields(ProtoWire.decodeFields(body).single().value as ByteArray).single()
                val requestFields = ProtoWire.decodeFields(request.value as ByteArray)
                when (request.number) {
                    1 -> {
                        f.channel.incoming.trySend(WatchFaceProtocol.frame(b(2, b(1, ws(1, "token") + n(2, 1)))))
                        f.channel.incoming.trySend(WatchFaceProtocol.frame(b(3, b(1, byteArrayOf(8, 1)) + b(2, byteArrayOf(8, 2)))))
                    }
                    4, 5 -> {
                        val id = wrapperInt(requestFields, 1)
                        f.channel.incoming.trySend(WatchFaceProtocol.frame(b(2, b(3, wi(1, id) + n(2, 1) + b(3, byteArrayOf(7, 8))))))
                    }
                    2 -> f.channel.incoming.trySend(WatchFaceProtocol.frame(b(2, b(2, ws(1, "token") + n(2, 1)))))
                }
            }
            val start = async { f.engine.startEditing(7, 400, 400) }; runCurrent()
            assertEquals(WatchFaceEditPhase.ACTIVE, start.await().phase)
            assertContentEquals(byteArrayOf(8, 1), f.engine.state.value.editing.styleSchema)
            val preview = async { f.engine.updateEditing(WatchFacePreviewOptions(WatchFaceStyle(mapOf("color" to byteArrayOf(4))))) }; runCurrent()
            assertContentEquals(byteArrayOf(7, 8), preview.await()!!.bytes)
            val complication = async { f.engine.previewComplication(WatchFaceComplication(1, "p/Provider", 3), calendarTimeMillis = 100) }; runCurrent()
            assertContentEquals(byteArrayOf(7, 8), complication.await()!!.bytes)
            val commit = async {
                f.engine.endEditing(WatchFaceFavorite(7, "p/C", preview = WatchFaceImage(byteArrayOf(7, 8))))
            }
            runCurrent()
            assertEquals(WatchFaceOutcome.ACKNOWLEDGED, commit.await().outcome)
            assertEquals(WatchFaceEditPhase.ENDED, f.engine.state.value.editing.phase)
            assertTrue(f.channel.closed)
        }
    }

    @Test fun `stale channel is closed before editing bytes and detach cancels outstanding work`() = runTest {
        Fixture(backgroundScope).use { f ->
            f.channel.channelSession = 99
            f.engine.startEditing(7, 400, 400)
            assertTrue(f.channel.closed); assertTrue(f.channel.writes.isEmpty())
            val pending = async { f.engine.removeFavorite(7) }; runCurrent()
            f.engine.detach(1); runCurrent()
            assertEquals(WatchFaceOutcome.INTERRUPTED, pending.await().outcome)
            assertFalse(f.engine.offerMessage(peer, 1, WatchFaceProtocol.OPERATION_RESPONSE, wi(1, 1), route))
        }
    }

    @Test fun `explicit MWF route streams local ZIP and reports transfer without inventing install success`() = runTest {
        Fixture(backgroundScope).use { f ->
            val bytes = zip("face/config.json", "{}".toByteArray())
            val archive = object : WatchFaceArchive { override val sizeBytes = bytes.size.toLong(); override fun open() = ByteArrayInputStream(bytes) }
            val independent = ApplicationRoute("explicit.mwf.phone", "explicit-certificate")
            val result = f.engine.transferMwfArchive(archive, "/caller/selected/path/face", independent)
            assertEquals(WatchFaceOutcome.TRANSFERRED_UNVERIFIED, result.outcome)
            assertEquals(independent, f.openRoute)
            assertContentEquals(bytes, f.channel.writes.reduce { a, b -> a + b })
            assertTrue(f.channel.finished); assertTrue(f.channel.closed)
        }
    }

    private data class Sent(val route: ApplicationRoute, val path: String, val bytes: ByteArray)
    private class Fixture(scope: CoroutineScope, timeout: Long = 1000) : AutoCloseable {
        val data = Data(); val messages = mutableListOf<Sent>(); val channel = Stream()
        var onMessage: (Sent) -> Unit = {}; var openRoute: ApplicationRoute? = null
        val engine = WatchFaceManagementEngine(scope, data, config = WatchFaceManagementConfig(timeoutMillis = timeout))
        init { engine.attach(CompanionFeatureSession(1, dataScope, route, { route, path, bytes ->
            Sent(route, path, bytes).also { messages += it; onMessage(it) }
        }, { _, _, _, _ -> error("No RequestApi expected") }, { selected, _ -> openRoute = selected; channel })) }
        override fun close() { engine.close() }
    }
    private class Stream : WearChannel {
        var channelSession = 1L
        override val info get() = ChannelInfo(peer, channelSession, 1uL, true, WatchFaceProtocol.EDIT_CHANNEL)
        override val state = MutableStateFlow(ChannelState(ChannelPhase.OPEN))
        val incoming = Channel<ByteArray>(Channel.UNLIMITED); val writes = mutableListOf<ByteArray>()
        var respond: ((ByteArray) -> Unit)? = null; var closed = false; var finished = false
        override suspend fun read(maxBytes: Int) = incoming.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) { writes += bytes.copyOf(); respond?.invoke(bytes.copyOfRange(4, bytes.size)) }
        override suspend fun finishOutput() { finished = true }
        override fun closeInput() {}
        override suspend fun closeGracefully(errorCode: Int) { close() }
        override fun close() { closed = true; incoming.close(); state.value = ChannelState(ChannelPhase.CLOSED) }
    }
    private class Data : DataSyncClient {
        override val state = MutableStateFlow(DataSyncState(DataSyncPhase.ACTIVE, dataScope, 1))
        val rows = mutableListOf<DataItemRecord>(); var assetBytes: ByteArray? = null
        var beforeQuery: suspend () -> Unit = {}
        override suspend fun scopes(peer: PeerAddress?) = listOf(dataScope)
        override suspend fun items(scope: DataScope, query: DataItemQuery): DataItemPage {
            beforeQuery()
            val values = rows.filter { it.scope == scope && (query.route == null || query.route == it.route) &&
                (query.uriPrefix == null || it.uri.startsWith(query.uriPrefix!!)) && (query.includeDeleted || !it.deleted) }
            val page = values.drop(query.offset).take(query.limit)
            return DataItemPage(page, (query.offset + page.size).takeIf { it < values.size })
        }
        override suspend fun asset(scope: DataScope, route: ApplicationRoute, digest: String) = DataAssetRecord(scope, digest, setOf(route), assetBytes, 0)
        override suspend fun requestAsset(scope: DataScope, route: ApplicationRoute, digest: String, permissionOnly: Boolean) {}
        override suspend fun requestSync() {}
        override suspend fun awaitProgressBarrier(timeoutMillis: Long) = DataSyncBarrierResult(DataSyncBarrierStatus.UNVERIFIED, 1)
        override suspend fun publish(scope: DataScope, route: ApplicationRoute, draft: DataItemDraft): DataItemRecord = error("No DataApi writes expected")
        override suspend fun delete(scope: DataScope, route: ApplicationRoute, uri: String): DataItemRecord = error("No DataApi deletes expected")
        override suspend fun putAsset(scope: DataScope, route: ApplicationRoute, bytes: ByteArray): DataAssetRecord = error("No Asset writes expected")
        override fun close() {}
    }
    companion object {
        private val peer = PeerAddress("AA:BB:CC:DD:EE:FF")
        private val dataScope = DataScope(peer, "phone", "watch")
        private val route = ApplicationRoute("com.mobvoi.companion.at", "cert")
        private fun row(path: String, body: ByteArray, assets: List<DataAssetReference> = emptyList(), owner: String = "watch") =
            DataItemRecord(dataScope, route, "wear://$owner$path", owner, 1, false, body, assets, receivedAtEpochMillis = 0)
        private fun b(n: Int, bytes: ByteArray): ByteArray { require(bytes.size < 128); return byteArrayOf(((n shl 3) or 2).toByte(), bytes.size.toByte()) + bytes }
        private fun n(n: Int, value: Int) = byteArrayOf((n shl 3).toByte(), value.toByte())
        private fun wi(number: Int, value: Int) = b(number, if (value == 0) byteArrayOf() else n(1, value))
        private fun ws(n: Int, value: String) = b(n, b(1, value.toByteArray()))
        private fun wrapperInt(fields: List<ProtoField>, n: Int) = ((ProtoWire.decodeFields(fields.single { it.number == n }.value as ByteArray).firstOrNull()?.value as? ULong)?.toInt()) ?: 0
        internal fun zip(name: String, content: ByteArray): ByteArray = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { it.putNextEntry(ZipEntry(name)); it.write(content); it.closeEntry() } }.toByteArray()
    }
}
