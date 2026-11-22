@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.ticbox.companion.engine.data

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class DataSyncEngineTest {
    private val peer = PeerAddress("AA:BB:CC:DD:EE:FF")
    private val app = ApplicationRoute("watch.app", "digest")
    private val gms = ApplicationRoute("com.google.android.gms", "38918a453d07199354f8b19af05ec6562ced5788")
    private val dataScope = DataScope(peer, "phone", "watch", "net")
    private class Fixture(val engine: DataSyncEngine, val store: DataSyncStore, val writes: MutableList<ByteArray>, val reconnects: MutableList<String>, val logs: MutableList<ProtocolDiagnostic>)
    private fun TestScope.fixture(store: DataSyncStore = MemoryDataSyncStore(), config: DataSyncConfig = DataSyncConfig(), progress: DataSyncProgressTransport? = null, wireVersion: Int = 1): Fixture {
        val writes = mutableListOf<ByteArray>()
        val reconnects = mutableListOf<String>()
        val logs = mutableListOf<ProtocolDiagnostic>()
        val engine = DataSyncEngine(backgroundScope, store, config, DiagnosticSink { logs += it }, progress)
        val fixture = Fixture(engine, store, writes, reconnects, logs)
        attach(fixture, wireVersion = wireVersion)
        return fixture
    }
    private fun attach(f: Fixture, id: Long = 1, remote: String = "watch", wireVersion: Int = 1) = f.engine.attach(DataSyncSession(
        peer, id, "phone", remote, "net", app, { f.writes += it.copyOf() }, { f.reconnects += "$id:$it" },
        negotiatedWireVersion = wireVersion,
    ))
    private fun sync(cursors: Map<String, Long> = emptyMap(), version: Int = 2, legacy: Long = -1): ByteArray = GmsStructs.encode("WearMessage", mapOf(
        "syncStart" to mapOf("version" to version, "receivedSequenceId" to legacy, "syncTable" to cursors.map { mapOf("sourceNodeId" to it.key, "sequenceId" to it.value) }),
    ))
    private fun item(sequence: Long, uri: String = "wear://watch/value", route: ApplicationRoute = app, deleted: Boolean = false,
        source: String? = "watch", assets: List<DataWireAssetRef> = emptyList()): ByteArray = DataProtocol.wrapDataItem(DataProtocol.encodeItem(
        DataWireItem(DataAppKey(route.packageName, route.packageDigest), uri, sequence, source, deleted, byteArrayOf(sequence.toByte()), assets),
    ))
    private suspend fun Fixture.offer(bytes: ByteArray, id: Long = 1) { assertTrue(engine.acceptInbound(id, bytes)) }
    private fun Fixture.members(name: String) = writes.mapNotNull { GmsStructs.decode("WearMessage", it)[name] as? DecodedStruct }
    private fun owners(route: ApplicationRoute = app) = setOf(DataAppKey(route.packageName, route.packageDigest))

    @Test fun `notification diagnostics distinguish peer filtering from a wire write without logging content`() = runTest {
        val f = fixture(); runCurrent()
        f.offer(GmsStructs.encode("WearMessage", mapOf("syncStart" to mapOf(
            "version" to 2, "receivedSequenceId" to -1L,
            "restrictions" to mapOf("packages" to listOf(mapOf("packageName" to "other.app"))),
        )))); runCurrent()
        val uri = "wear://phone" + NotificationProtocol.streamItemPath("phone", "private.package", 7, null, "private-key")
        f.engine.publish(dataScope, app, DataItemDraft(uri, "private-body".toByteArray())); runCurrent()
        assertTrue(f.members("setDataItem").isEmpty())
        assertEquals(listOf("filtered_by_peer"), f.logs.filter { it.event == "notification_item_send" }.map { it.details["decision"] })
        f.offer(sync()); runCurrent()
        assertEquals(uri, f.members("setDataItem").single()["uri"])
        val events = f.logs.filter { it.event == "notification_item_send" }
        assertEquals(listOf("filtered_by_peer", "wire_written"), events.map { it.details["decision"] })
        assertTrue(events.all { it.sessionId == 1L && it.peer == peer && it.severity == DiagnosticSeverity.INFO })
        assertTrue(events.flatMap { it.details.values }.none { it.contains("private") || it.contains("wear://") })
        f.engine.close()
    }

    @Test fun `DataItems persist all namespaces with source cursors duplicates and tombstones`() = runTest {
        val f = fixture(); runCurrent(); f.offer(sync()); runCurrent()
        f.offer(item(1, route = gms)); f.offer(item(2, uri = "wear://watch/other")); f.offer(item(2, uri = "wear://watch/duplicate")); runCurrent()
        assertEquals(2, f.engine.items(dataScope).items.size)
        assertEquals(2L, f.store.cursors(dataScope)["watch"])
        assertEquals(1L, f.engine.state.value.duplicateItems)
        f.offer(item(3, deleted = true, route = gms)); runCurrent()
        assertEquals(1, f.engine.items(dataScope).items.size)
        assertTrue(f.engine.items(dataScope, DataItemQuery(route = gms, includeDeleted = true)).items.single().deleted)
        assertFalse(f.engine.state.value.initialSyncCompletionObserved)
        f.engine.close()
    }
    @Test fun `explicit read-only snapshot observes wire duplicates without losing durable cursors or repeating application delivery`() = runTest {
        val f = fixture(); runCurrent()
        val applied = mutableListOf<Long>(); val observed = mutableListOf<Long>()
        f.engine.setRemoteItemObserver { _, row -> applied += row.sequenceId }
        val subscription = f.engine.observeInboundItems { id, row ->
            assertEquals(1L, id); observed += row.sequenceId
        }
        f.offer(item(18)); runCurrent()
        f.engine.requestRemoteSnapshot(dataScope, 1); runCurrent()
        assertEquals(-1L, f.members("syncStart").last()["receivedSequenceId"])
        assertTrue((f.members("syncStart").last()["syncTable"] as? List<*>).orEmpty().isEmpty())
        f.offer(item(18)); runCurrent()
        assertEquals(listOf(18L), applied)
        assertEquals(listOf(18L, 18L), observed)
        assertEquals(18L, f.store.cursors(dataScope)["watch"])
        assertEquals(1, f.engine.items(dataScope).items.size)
        f.engine.requestSync(); runCurrent()
        assertEquals(18L, f.members("syncStart").last()["receivedSequenceId"])
        assertFailsWith<NotConnectedException> { f.engine.requestRemoteSnapshot(dataScope, 2) }
        subscription.close()
        f.offer(item(19)); runCurrent()
        assertEquals(listOf(18L, 18L), observed)
        f.engine.close()
    }
    @Test fun `offline publication resumes from remote source cursor and preserves unknown original bytes`() = runTest {
        val f = fixture(); runCurrent(); f.engine.detach(1)
        val created = f.engine.publish(dataScope, app, DataItemDraft("wear://phone/out", byteArrayOf(7)))
        assertTrue(created.local)
        attach(f, 2); runCurrent(); f.offer(sync(), 2); runCurrent()
        assertEquals(created.sequenceId, f.members("setDataItem").last()["sequenceId"])
        assertEquals("phone", f.members("setDataItem").last()["sourceNodeId"])
        assertEquals(created.sequenceId, f.store.cursors(dataScope)["phone"])
        f.engine.close()
    }
    @Test fun `concurrent publication keeps transaction allocation and wire order together`() = runTest {
        val memory = MemoryDataSyncStore()
        val firstCommitted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val slow = object : DataSyncStore by memory {
            override suspend fun publishLocal(scope: DataScope, build: (Long) -> StoredDataItem): StoredDataItem {
                val record = memory.publishLocal(scope, build)
                if (record.item.sequenceId == 1L) { firstCommitted.complete(Unit); release.await() }
                return record
            }
        }
        val f = fixture(slow); runCurrent(); f.offer(sync()); runCurrent()
        val first = async { f.engine.publish(dataScope, app, DataItemDraft("wear://phone/one")) }
        firstCommitted.await()
        val second = async { f.engine.publish(dataScope, app, DataItemDraft("wear://phone/two")) }
        runCurrent(); assertFalse(second.isCompleted)
        release.complete(Unit); first.await(); second.await(); runCurrent()
        assertEquals(listOf(1L, 2L), f.members("setDataItem").map { it["sequenceId"] as Long })
        f.engine.close()
    }
    @Test fun `incoming fallback source survives relay and legacy replay sequences remain monotonic`() = runTest {
        val f = fixture(); runCurrent(); f.offer(sync()); runCurrent()
        f.offer(item(80, source = null)); runCurrent()
        val saved = f.store.replay(dataScope).single()
        assertEquals("watch", DataProtocol.decodeItem(saved.wireDataItem).sourceNodeId)
        val local = f.engine.publish(dataScope, app, DataItemDraft("wear://phone/new", byteArrayOf(2)))
        attach(f, 2); runCurrent(); f.offer(sync(version = 1), 2); runCurrent()
        val sends = f.members("setDataItem").takeLast(2).map { it["sequenceId"] as Long }
        assertTrue(sends.zipWithNext().all { (a, b) -> b > a })
        assertTrue(local.sequenceId >= saved.relaySequence)
        f.engine.close()
    }
    @Test fun `node identity change gets separate durable cursors`() = runTest {
        val f = fixture(); runCurrent(); f.offer(item(99)); runCurrent()
        attach(f, 2, "reset-watch"); runCurrent()
        val start = f.members("syncStart").last()
        assertEquals(-1L, start["receivedSequenceId"])
        assertTrue((start["syncTable"] as? List<*>).orEmpty().isEmpty())
        assertEquals(2, f.engine.scopes().size)
        f.engine.close()
    }
    @Test fun `missing file is fetched and permission checked before returning cached bytes`() = runTest {
        val f = fixture(); runCurrent()
        val bytes = "asset-data".toByteArray(); val digest = DataProtocol.digest(bytes)
        f.offer(item(1, assets = listOf(DataWireAssetRef("0", digest)))); runCurrent()
        assertEquals(false, f.members("fetchAsset").single()["permissionOnly"])
        f.offer(DataProtocol.setAsset(digest, owners(), bytes)); runCurrent()
        assertContentEquals(bytes, f.engine.asset(dataScope, app, digest)?.data)
        assertNull(f.engine.asset(dataScope, gms, digest))
        assertEquals(digest, f.members("ackAsset").single()["digest"])
        assertTrue(f.store.missingAssets(dataScope).isEmpty())
        f.engine.close()
    }
    @Test fun `same asset digest resolves independent AppKey permissions without overwriting bytes`() = runTest {
        val f = fixture(); runCurrent()
        val bytes = byteArrayOf(10, 11); val digest = DataProtocol.digest(bytes)
        f.offer(item(1, assets = listOf(DataWireAssetRef("0", digest))));
        f.offer(item(2, "wear://watch/gms", gms, assets = listOf(DataWireAssetRef("0", digest)))); runCurrent()
        f.offer(DataProtocol.setAsset(digest, owners(), bytes)); runCurrent()
        assertEquals(2, f.members("fetchAsset").size)
        assertEquals(true, f.members("fetchAsset").last()["permissionOnly"])
        f.offer(DataProtocol.setAsset(digest, owners(gms))); runCurrent()
        assertContentEquals(bytes, f.engine.asset(dataScope, gms, digest)?.data)
        assertTrue(f.store.missingAssets(dataScope).isEmpty())
        f.engine.close()
    }
    @Test fun `bounded asset pipeline drains backlog and negative replies do not cause retry loops`() = runTest {
        val f = fixture(config = DataSyncConfig(maxPendingAssets = 1)); runCurrent()
        val bytes = listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
        val digests = bytes.map(DataProtocol::digest)
        f.offer(item(1, assets = digests.mapIndexed { i, digest -> DataWireAssetRef(i.toString(), digest) })); runCurrent()
        assertEquals(1, f.members("fetchAsset").size)
        f.offer(DataProtocol.setAsset(digests[0], emptySet())); runCurrent()
        assertEquals(2, f.members("fetchAsset").size)
        assertEquals(1, f.engine.state.value.unavailableAssets)
        f.offer(DataProtocol.setAsset(digests[1], owners(), bytes[1])); runCurrent()
        assertEquals(3, f.members("fetchAsset").size)
        f.offer(DataProtocol.setAsset(digests[2], owners(), bytes[2])); runCurrent()
        assertEquals(3, f.members("fetchAsset").size) // Denied first digest is not immediately fetched again.
        f.engine.requestAsset(dataScope, app, digests[0]); runCurrent()
        assertEquals(4, f.members("fetchAsset").size)
        f.offer(DataProtocol.setAsset(digests[0], owners(), bytes[0])); runCurrent()
        assertTrue(f.store.missingAssets(dataScope).isEmpty())
        assertEquals(0, f.engine.state.value.unavailableAssets)
        f.engine.close()
    }
    @Test fun `metadata response cannot create imaginary file and bad actual digest receives no ACK`() = runTest {
        val f = fixture(); runCurrent()
        val digest = DataProtocol.digest(byteArrayOf(1, 2))
        f.offer(DataProtocol.setAsset(digest, owners())); runCurrent()
        assertNull(f.engine.asset(dataScope, app, digest)?.data)
        assertEquals(1, f.members("ackAsset").size)
        f.offer(DataProtocol.setAsset(digest, owners(), byteArrayOf(9))); runCurrent()
        assertEquals(1, f.members("ackAsset").size)
        assertEquals(DataSyncIssue.ASSET_DIGEST_MISMATCH, f.engine.state.value.lastIssue)
        f.engine.close()
    }
    @Test fun `attachment owner uses exact envelope hash and final file digest is diagnostic only`() = runTest {
        val f = fixture(); runCurrent()
        val bytes = "abcdefgh".toByteArray(); val digest = DataProtocol.digest(bytes)
        // Unknown field99 is part of the owner digest, even though re-encoding known fields would omit it.
        val owner = DataProtocol.setAsset(digest, owners(), hasFile = true) + byteArrayOf(0x98.toByte(), 0x06, 1)
        f.offer(owner); f.offer(DataProtocol.filePiece(DataProtocol.digest(owner), bytes.copyOfRange(0, 3), false));
        f.offer(DataProtocol.filePiece(DataProtocol.digest(owner), bytes.copyOfRange(3, bytes.size), true, "reported-wrong")); runCurrent()
        assertContentEquals(bytes, f.engine.asset(dataScope, app, digest)?.data)
        assertTrue(f.logs.any { it.event == "file_piece_digest_mismatch" })
        assertEquals(0, f.engine.state.value.incomingAttachments)
        f.engine.close()
    }
    @Test fun `partial file is discarded on disconnect and durable reference is fetched again`() = runTest {
        val f = fixture(); runCurrent()
        val bytes = "asset".toByteArray(); val digest = DataProtocol.digest(bytes)
        f.offer(item(1, assets = listOf(DataWireAssetRef("0", digest)))); runCurrent()
        val owner = DataProtocol.setAsset(digest, owners(), hasFile = true)
        f.offer(owner); f.offer(DataProtocol.filePiece(DataProtocol.digest(owner), byteArrayOf(1), false)); runCurrent()
        f.engine.detach(1); attach(f, 2); runCurrent()
        assertFalse(f.engine.acceptInbound(1, DataProtocol.filePiece(DataProtocol.digest(owner), bytes, true)))
        assertEquals(2, f.members("fetchAsset").size)
        assertEquals(1L, f.members("syncStart").last()["receivedSequenceId"])
        assertEquals(0, f.engine.state.value.incomingAttachments)
        f.engine.close()
    }
    @Test fun `notification assets are pushed without FetchAsset and ACK drains a bounded send window`() = runTest {
        val f = fixture(config = DataSyncConfig(maxPendingAssets = 1, filePieceBytes = 3))
        runCurrent(); f.offer(sync()); runCurrent()
        val small = f.engine.putAsset(dataScope, app, byteArrayOf(1, 2))
        val largeBytes = "abcdefgh".toByteArray()
        val large = f.engine.putAsset(dataScope, app, largeBytes)
        val uri = "wear://phone" + NotificationProtocol.streamItemPath("phone", "private.package", 7, null, "private-key")
        f.engine.publish(dataScope, app, DataItemDraft(uri, "private-body".toByteArray(), listOf(
            DataAssetReference("0", small.digest), DataAssetReference("1", large.digest),
        )))
        runCurrent()
        assertEquals(uri, f.members("setDataItem").single()["uri"])
        assertEquals(small.digest, f.members("setAsset").single()["digest"])
        assertNull(f.members("setAsset").single()["inlineData"])
        assertContentEquals(byteArrayOf(1, 2), f.members("filePiece").single()["data"] as ByteArray)
        assertEquals(1, f.engine.state.value.pendingAssetSends)
        f.offer(DataProtocol.ackAsset(small.digest)); runCurrent()
        assertEquals(listOf(small.digest, large.digest), f.members("setAsset").map { it["digest"] })
        assertContentEquals(largeBytes, f.members("filePiece").drop(1).flatMap { (it["data"] as ByteArray).toList() }.toByteArray())
        assertTrue(f.members("filePiece").all { (it["data"] as ByteArray).size <= 3 })
        assertEquals(large.digest, f.members("filePiece").last()["fileDigest"])
        f.offer(DataProtocol.ackAsset(large.digest)); runCurrent()
        assertEquals(0, f.engine.state.value.pendingAssetSends)
        assertEquals(2, f.members("setAsset").size)
        assertTrue(f.writes.map { GmsStructs.decode("WearMessage", it) }.filter { it["setAsset"] != null }.all { it["hasFile"] == true })
        assertTrue(f.logs.filter { it.event == "asset_send" }.all { it.details["wireVersion"] == "1" && it.details["encoding"] == "file_pieces" })
        assertTrue(f.logs.filter { it.event == "asset_send" }.flatMap { it.details.values }.none { it.contains("private") || it.contains("wear://") })
        f.engine.close()
    }
    @Test fun `asset replay repairs a peer that already received the DataItem and deduplicates shared in-flight files`() = runTest {
        val f = fixture(); runCurrent(); f.engine.detach(1)
        val asset = f.engine.putAsset(dataScope, app, byteArrayOf(1, 2, 3))
        val refs = listOf(DataAssetReference("0", asset.digest))
        f.engine.publish(dataScope, app, DataItemDraft("wear://phone/first", assets = refs))
        val latest = f.engine.publish(dataScope, app, DataItemDraft("wear://phone/second", assets = refs))
        attach(f, 2); runCurrent(); f.writes.clear()
        f.offer(sync(cursors = mapOf("phone" to latest.sequenceId)), 2); runCurrent()
        assertTrue(f.members("setDataItem").isEmpty())
        assertEquals(asset.digest, f.members("setAsset").single()["digest"])
        f.offer(DataProtocol.ackAsset(asset.digest), 2); runCurrent()
        assertEquals(0, f.engine.state.value.pendingAssetSends)
        f.engine.detach(2); attach(f, 3); runCurrent(); f.writes.clear()
        f.offer(sync(cursors = mapOf("phone" to latest.sequenceId)), 3); runCurrent()
        assertEquals(asset.digest, f.members("setAsset").single()["digest"])
        assertFalse(f.engine.acceptInbound(2, DataProtocol.ackAsset(asset.digest)))
        assertEquals(1, f.engine.state.value.pendingAssetSends)
        f.engine.close()
    }
    @Test fun `proactive assets honor peer URI restrictions tombstones and file ownership`() = runTest {
        val f = fixture(); runCurrent(); f.engine.detach(1)
        val allowed = f.engine.putAsset(dataScope, app, byteArrayOf(1))
        val blocked = f.engine.putAsset(dataScope, app, byteArrayOf(2))
        val deleted = f.engine.putAsset(dataScope, app, byteArrayOf(3))
        val foreign = f.engine.putAsset(dataScope, gms, byteArrayOf(4))
        f.engine.publish(dataScope, app, DataItemDraft("wear://phone/allowed/value", assets = listOf(DataAssetReference("0", allowed.digest))))
        f.engine.publish(dataScope, app, DataItemDraft("wear://phone/blocked/value", assets = listOf(DataAssetReference("0", blocked.digest))))
        f.engine.publish(dataScope, app, DataItemDraft("wear://phone/allowed/deleted", assets = listOf(DataAssetReference("0", deleted.digest))))
        f.engine.delete(dataScope, app, "wear://phone/allowed/deleted")
        f.engine.publish(dataScope, app, DataItemDraft("wear://phone/allowed/foreign", assets = listOf(DataAssetReference("0", foreign.digest))))
        attach(f, 2); runCurrent()
        f.offer(GmsStructs.encode("WearMessage", mapOf("syncStart" to mapOf(
            "version" to 2, "restrictions" to mapOf("packages" to listOf(mapOf(
                "packageName" to app.packageName, "dataFilters" to mapOf("filters" to listOf(mapOf("kind" to 2, "uri" to "wear://*/allowed/"))),
            ))),
        ))), 2); runCurrent()
        assertEquals(listOf(allowed.digest), f.members("setAsset").map { it["digest"] })
        f.engine.close()
    }
    @Test fun `a later publication survives a full asset window and missing local bytes can be supplied later`() = runTest {
        val f = fixture(config = DataSyncConfig(maxPendingAssets = 1)); runCurrent(); f.offer(sync()); runCurrent()
        val first = f.engine.putAsset(dataScope, app, byteArrayOf(1))
        f.engine.publish(dataScope, app, DataItemDraft("wear://phone/first", assets = listOf(DataAssetReference("0", first.digest))))
        runCurrent()
        val laterBytes = byteArrayOf(2)
        val laterDigest = DataProtocol.digest(laterBytes)
        f.engine.publish(dataScope, app, DataItemDraft("wear://phone/later", assets = listOf(DataAssetReference("0", laterDigest))))
        runCurrent(); f.offer(DataProtocol.ackAsset(first.digest)); runCurrent()
        // The watch committed the first file, but cannot fetch the second file itself.
        assertEquals(listOf(first.digest), f.members("setAsset").map { it["digest"] })
        assertTrue(f.members("setAsset").none { it["digest"] == laterDigest })
        assertEquals(0, f.engine.state.value.pendingAssetSends)
        f.engine.putAsset(dataScope, app, laterBytes); runCurrent()
        assertEquals(listOf(first.digest, laterDigest), f.members("setAsset").map { it["digest"] })
        f.offer(DataProtocol.ackAsset(laterDigest)); runCurrent()
        assertEquals(0, f.engine.state.value.pendingAssetSends)
        f.engine.close()
    }
    @Test fun `V1 outgoing asset uses bounded chunks and duplicate ACKs are harmless`() = runTest {
        val f = fixture(config = DataSyncConfig(filePieceBytes = 3)); runCurrent()
        val bytes = "abcdefgh".toByteArray(); val asset = f.engine.putAsset(dataScope, app, bytes)
        f.offer(DataProtocol.fetchAsset(DataAppKey(app.packageName, app.packageDigest), asset.digest, false)); runCurrent()
        val pieces = f.members("filePiece")
        assertEquals(3, pieces.size)
        assertContentEquals(bytes, pieces.flatMap { (it["data"] as ByteArray).toList() }.toByteArray())
        assertTrue(pieces.last()["finalPiece"] as Boolean)
        assertEquals(asset.digest, pieces.last()["fileDigest"])
        f.offer(DataProtocol.ackAsset(asset.digest)); f.offer(DataProtocol.ackAsset(asset.digest)); runCurrent()
        assertEquals(0, f.engine.state.value.pendingAssetSends)
        assertTrue(f.logs.any { it.event == "unexpected_asset_ack" })
        f.engine.close()
    }
    @Test fun `V0 assets always use inline bytes including files larger than the former size cutoff`() = runTest {
        val f = fixture(config = DataSyncConfig(filePieceBytes = 3), wireVersion = 0); runCurrent()
        for (size in listOf(0, 1, 32 * 1024 + 1)) {
            val bytes = ByteArray(size) { it.toByte() }
            val asset = f.engine.putAsset(dataScope, app, bytes)
            f.offer(DataProtocol.fetchAsset(DataAppKey(app.packageName, app.packageDigest), asset.digest, false)); runCurrent()
            assertContentEquals(bytes, f.members("setAsset").last()["inlineData"] as ByteArray)
            f.offer(DataProtocol.ackAsset(asset.digest)); runCurrent()
        }
        assertTrue(f.members("filePiece").isEmpty())
        assertTrue(f.writes.map { GmsStructs.decode("WearMessage", it) }.none { it["hasFile"] == true })
        assertTrue(f.logs.filter { it.event == "asset_send" }.all { it.details["wireVersion"] == "0" && it.details["encoding"] == "inline" })
        assertEquals(0, f.engine.state.value.pendingAssetSends)
        f.engine.close()
    }
    @Test fun `V1 empty file sends a final piece while a permission-only reply never declares a file`() = runTest {
        val f = fixture(); runCurrent()
        val asset = f.engine.putAsset(dataScope, app, byteArrayOf())
        val owner = DataAppKey(app.packageName, app.packageDigest)
        f.offer(DataProtocol.fetchAsset(owner, asset.digest, true)); runCurrent()
        assertTrue(f.members("filePiece").isEmpty())
        assertTrue(f.writes.map { GmsStructs.decode("WearMessage", it) }.none { it["hasFile"] == true })
        assertNull(f.members("setAsset").single()["inlineData"])
        f.offer(DataProtocol.ackAsset(asset.digest)); runCurrent(); f.writes.clear()
        f.offer(DataProtocol.fetchAsset(owner, asset.digest, false)); runCurrent()
        val ownerBytes = f.writes.single { GmsStructs.decode("WearMessage", it)["setAsset"] != null }
        assertEquals(true, GmsStructs.decode("WearMessage", ownerBytes)["hasFile"])
        assertNull(f.members("setAsset").single()["inlineData"])
        val piece = f.members("filePiece").single()
        assertEquals(DataProtocol.digest(ownerBytes), piece["ownerMessageDigest"])
        assertContentEquals(byteArrayOf(), piece["data"] as ByteArray)
        assertEquals(true, piece["finalPiece"])
        assertEquals(asset.digest, piece["fileDigest"])
        f.offer(DataProtocol.ackAsset(asset.digest)); runCurrent()
        assertEquals(0, f.engine.state.value.pendingAssetSends)
        f.engine.close()
    }
    @Test fun `asset request without AppKey permission sends no file`() = runTest {
        val f = fixture(); runCurrent()
        val asset = f.engine.putAsset(dataScope, app, byteArrayOf(1, 2, 3))
        f.offer(DataProtocol.fetchAsset(DataAppKey(gms.packageName, gms.packageDigest), asset.digest, false)); runCurrent()
        assertNull(f.members("setAsset").single()["inlineData"])
        assertTrue(f.members("filePiece").isEmpty())
        f.engine.close()
    }
    @Test fun `a replay burst waits for bounded capacity then commits every source sequence in order`() = runTest {
        val memory = MemoryDataSyncStore(); val release = CompletableDeferred<Unit>()
        val committed = mutableListOf<Long>()
        val slow = object : DataSyncStore by memory {
            override suspend fun applyRemote(record: StoredDataItem): RemoteApplyResult {
                release.await()
                return memory.applyRemote(record).also { committed += record.item.sequenceId }
            }
        }
        val f = fixture(slow, DataSyncConfig(inboundQueueCapacity = 1)); runCurrent()
        f.offer(item(1)); runCurrent() // Worker is waiting for the first transaction.
        f.offer(item(2)) // The one buffered slot is occupied.
        val incoming = async { f.engine.acceptInbound(1, item(3)) }; runCurrent()
        assertFalse(incoming.isCompleted)
        assertTrue(f.reconnects.isEmpty())
        assertTrue(f.store.cursors(dataScope).isEmpty())
        assertEquals(0L, f.engine.state.value.droppedEnvelopes)
        release.complete(Unit); runCurrent()
        assertTrue(incoming.await())
        assertEquals(listOf(1L, 2L, 3L), committed)
        assertEquals(3L, f.store.cursors(dataScope)["watch"])
        assertTrue(f.logs.any { it.event == "data_queue_backpressure" })
        assertTrue(f.reconnects.isEmpty())
        f.engine.close()
    }

    @Test fun `session replacement releases a blocked queue sender without delivering old data into its replacement`() = runTest {
        val memory = MemoryDataSyncStore(); val release = CompletableDeferred<Unit>()
        val slow = object : DataSyncStore by memory {
            override suspend fun applyRemote(record: StoredDataItem): RemoteApplyResult {
                release.await(); return memory.applyRemote(record)
            }
        }
        val f = fixture(slow, DataSyncConfig(inboundQueueCapacity = 1)); runCurrent()
        f.offer(item(1)); runCurrent(); f.offer(item(2))
        val incoming = async { f.engine.acceptInbound(1, item(3)) }; runCurrent()
        assertFalse(incoming.isCompleted)
        attach(f, 2); f.engine.detach(1); runCurrent()
        assertFalse(incoming.await())
        assertEquals(2L, f.engine.state.value.sessionId)
        assertTrue(f.store.cursors(dataScope).isEmpty())
        release.complete(Unit); f.offer(item(4), 2); runCurrent()
        assertEquals(4L, f.store.cursors(dataScope)["watch"])
        assertEquals(listOf(4L), f.engine.items(dataScope).items.map { it.sequenceId })
        f.engine.close()
    }
    @Test fun `storage failure never advances source cursor or sends false asset ACK`() = runTest {
        val memory = MemoryDataSyncStore()
        val broken = object : DataSyncStore by memory {
            override suspend fun applyRemote(record: StoredDataItem): RemoteApplyResult = error("simulated disk failure")
            override suspend fun putAsset(asset: DataAssetRecord) { error("simulated disk failure") }
        }
        val f = fixture(broken); runCurrent(); f.offer(item(1)); f.offer(item(2)); runCurrent()
        assertTrue(memory.cursors(dataScope).isEmpty())
        assertEquals(DataSyncPhase.NEEDS_RESYNC, f.engine.state.value.phase)
        assertTrue(f.members("ackAsset").isEmpty())
        f.engine.close()
    }
    @Test fun `asset storage failure withholds ACK and requests recovery`() = runTest {
        val memory = MemoryDataSyncStore()
        val broken = object : DataSyncStore by memory {
            override suspend fun putAsset(asset: DataAssetRecord) { error("simulated asset disk failure") }
        }
        val f = fixture(broken); runCurrent()
        val bytes = byteArrayOf(1, 2)
        f.offer(DataProtocol.setAsset(DataProtocol.digest(bytes), owners(), bytes)); runCurrent()
        assertTrue(f.members("ackAsset").isEmpty())
        assertEquals(DataSyncPhase.NEEDS_RESYNC, f.engine.state.value.phase)
        assertNull(memory.asset(dataScope, DataProtocol.digest(bytes)))
        f.engine.close()
    }
    @Test fun `finite progress barrier verifies tracker sequence and assets`() = runTest {
        val transport = DataSyncProgressTransport { _, payload, _ ->
            val request = DataProtocol.decodeProgress(payload)
            DataProtocol.encodeProgress(request.trackerId!!, 2L)
        }
        val f = fixture(progress = transport); runCurrent(); f.offer(sync()); f.offer(item(2)); runCurrent()
        assertEquals(DataSyncBarrierStatus.COMPLETE, f.engine.awaitProgressBarrier().status)
        assertFalse(f.engine.state.value.initialSyncCompletionObserved)
        f.engine.close()
    }
    @Test fun `concurrent progress barriers serialize per peer while queued timeout and cancellation remain cancellable`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var requests = 0
        var activeRequests = 0
        var maximumActive = 0
        val f = fixture(progress = DataSyncProgressTransport { _, payload, _ ->
            requests++; activeRequests++; maximumActive = maxOf(maximumActive, activeRequests)
            try {
                if (requests == 1) { entered.complete(Unit); release.await() }
                val request = DataProtocol.decodeProgress(payload)
                DataProtocol.encodeProgress(request.trackerId!!, -1)
            } finally { activeRequests-- }
        })
        runCurrent()
        val first = async { f.engine.awaitProgressBarrier(10_000) }
        entered.await()
        val timeout = async { f.engine.awaitProgressBarrier(10) }
        runCurrent(); assertEquals(1, requests)
        advanceTimeBy(10); runCurrent()
        assertEquals(DataSyncBarrierStatus.TIMED_OUT, timeout.await().status)
        val cancelled = async { f.engine.awaitProgressBarrier(10_000) }
        runCurrent(); cancelled.cancelAndJoin()
        assertEquals(1, requests)
        val next = async { f.engine.awaitProgressBarrier(10_000) }
        runCurrent(); assertEquals(1, requests)
        release.complete(Unit)
        assertEquals(DataSyncBarrierStatus.COMPLETE, first.await().status)
        assertEquals(DataSyncBarrierStatus.COMPLETE, next.await().status)
        assertEquals(2, requests)
        assertEquals(1, maximumActive)
        f.engine.close()
    }
    @Test fun `tracking request and response barriers only advertise the sequence actually allowed to this peer`() = runTest {
        var requestedSequence: Long? = null
        val f = fixture(progress = DataSyncProgressTransport { _, payload, _ ->
            val request = DataProtocol.decodeProgress(payload)
            requestedSequence = request.sequenceId
            DataProtocol.encodeProgress(request.trackerId!!, -1)
        })
        runCurrent()
        f.offer(GmsStructs.encode("WearMessage", mapOf("syncStart" to mapOf(
            "version" to 2, "receivedSequenceId" to -1L,
            "restrictions" to mapOf("packages" to listOf(mapOf("packageName" to app.packageName,
                "dataFilters" to mapOf("filters" to listOf(mapOf("kind" to 1, "uri" to "wear://local/allowed"))))))
        ))))
        runCurrent()
        val allowed = f.engine.publish(dataScope, app, DataItemDraft("wear://phone/allowed"))
        val filtered = f.engine.publish(dataScope, app, DataItemDraft("wear://phone/private"))
        runCurrent()
        assertTrue(filtered.sequenceId > allowed.sequenceId)
        assertEquals(listOf(allowed.sequenceId), f.members("setDataItem").map { it["sequenceId"] })
        val reply = DataProtocol.decodeProgress(f.engine.respondProgressBarrier(1, DataProtocol.encodeProgress("peer-track", -1)))
        assertEquals(allowed.sequenceId, reply.sequenceId)
        assertEquals(DataSyncBarrierStatus.COMPLETE, f.engine.awaitProgressBarrier().status)
        assertEquals(allowed.sequenceId, requestedSequence)
        f.engine.close()
    }
    @Test fun `closing clears transient unavailable resource counts`() = runTest {
        val f = fixture(); runCurrent()
        val digest = DataProtocol.digest(byteArrayOf(1))
        f.offer(item(1, assets = listOf(DataWireAssetRef("0", digest)))); runCurrent()
        f.offer(DataProtocol.setAsset(digest, emptySet())); runCurrent()
        assertEquals(1, f.engine.state.value.unavailableAssets)
        f.engine.close()
        assertEquals(DataSyncPhase.CLOSED, f.engine.state.value.phase)
        assertEquals(0, f.engine.state.value.unavailableAssets)
    }
    @Test fun `progress responder waits for referenced asset instead of treating an idle reader as complete`() = runTest {
        val f = fixture(); runCurrent()
        val bytes = byteArrayOf(3, 4); val digest = DataProtocol.digest(bytes)
        val response = async { f.engine.respondProgressBarrier(1, DataProtocol.encodeProgress("track", 3)) }
        runCurrent(); assertFalse(response.isCompleted)
        f.offer(item(3, assets = listOf(DataWireAssetRef("0", digest)))); runCurrent(); assertFalse(response.isCompleted)
        f.offer(DataProtocol.setAsset(digest, owners(), bytes)); runCurrent()
        assertEquals("track", DataProtocol.decodeProgress(response.await()).trackerId)
        f.engine.close()
    }
    @Test fun `disabled tracking times out and tracker mismatch is logged while missing sequence stays unverified`() = runTest {
        val f = fixture(progress = DataSyncProgressTransport { _, _, _ -> delay(1000); byteArrayOf() }); runCurrent()
        assertEquals(DataSyncBarrierStatus.TIMED_OUT, f.engine.awaitProgressBarrier(10).status)
        f.engine.setProgressTransport(DataSyncProgressTransport { _, _, _ -> DataProtocol.encodeProgress("other", -1) })
        assertEquals(DataSyncBarrierStatus.COMPLETE, f.engine.awaitProgressBarrier().status)
        assertTrue(f.logs.any { it.event == "data_progress_tracker_mismatch" })
        f.engine.setProgressTransport(DataSyncProgressTransport { _, _, _ -> byteArrayOf() })
        assertEquals(DataSyncBarrierStatus.UNVERIFIED, f.engine.awaitProgressBarrier().status)
        f.engine.close()
    }
}
