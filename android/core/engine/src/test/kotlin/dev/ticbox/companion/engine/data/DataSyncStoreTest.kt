package dev.ticbox.companion.engine.data

import dev.ticbox.companion.api.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class DataSyncStoreTest {
    private val scope = DataScope(PeerAddress("AA:BB:CC:DD:EE:FF"), "phone", "watch", "network")
    private val app = ApplicationRoute("p", "c")
    private fun stored(sequence: Long, local: Boolean = false, uri: String = "wear://watch/x") = StoredDataItem(
        DataItemRecord(scope, app, uri, if (local) "phone" else "watch", sequence, false,
            byteArrayOf(1), listOf(DataAssetReference("0", "digest")), receivedAtEpochMillis = 1, local = local), byteArrayOf(9),
    )
    @Test fun `failed local construction rolls back its sequence and row`() = runTest {
        val store = MemoryDataSyncStore()
        assertFailsWith<IllegalStateException> { store.publishLocal(scope) { error("encoding failed") } }
        assertTrue(store.cursors(scope).isEmpty())
        assertTrue(store.items(scope, DataItemQuery()).items.isEmpty())
        val saved = store.publishLocal(scope) { stored(it, local = true) }
        assertEquals(1L, saved.item.sequenceId)
        assertEquals(1L, store.cursors(scope)["phone"])
        store.close()
    }
    @Test fun `source cursors and rows update together across namespaces and duplicate sequence`() = runTest {
        val store = MemoryDataSyncStore()
        assertEquals(RemoteApplyResult.APPLIED, store.applyRemote(stored(9)))
        assertEquals(RemoteApplyResult.DUPLICATE, store.applyRemote(stored(8, uri = "wear://watch/late")))
        assertEquals(9L, store.cursors(scope)["watch"])
        assertEquals(1, store.items(scope, DataItemQuery()).items.size)
        assertTrue(store.items(scope.copy(remoteNodeId = "reset-watch"), DataItemQuery()).items.isEmpty())
        store.close()
    }
    @Test fun `asset budget failure cannot mutate earlier permissions or cached bytes`() = runTest {
        val store = MemoryDataSyncStore(maxAssetStorageBytes = 2)
        store.putAsset(DataAssetRecord(scope, "digest", setOf(app), byteArrayOf(1, 2), 1))
        val other = ApplicationRoute("q", "other")
        assertFailsWith<IllegalStateException> { store.putAsset(DataAssetRecord(scope, "digest", setOf(other), byteArrayOf(3, 4, 5), 2)) }
        val previous = store.asset(scope, "digest")!!
        assertEquals(setOf(app), previous.owners)
        assertContentEquals(byteArrayOf(1, 2), previous.data)
        store.putAsset(DataAssetRecord(scope, "digest", setOf(other), null, 3))
        assertEquals(setOf(app, other), store.asset(scope, "digest")!!.owners)
        assertContentEquals(byteArrayOf(1, 2), store.asset(scope, "digest")!!.data)
        store.close()
    }
    @Test fun `all exposed item and asset bytes are defensive snapshots`() = runTest {
        val store = MemoryDataSyncStore()
        val body = byteArrayOf(1, 2)
        val asset = DataAssetRecord(scope, "digest", setOf(app), body, 1)
        body[0] = 9
        store.putAsset(asset)
        val exposed = store.asset(scope, "digest")!!.data!!; exposed[0] = 8
        assertContentEquals(byteArrayOf(1, 2), store.asset(scope, "digest")!!.data)
        val saved = store.publishLocal(scope) { stored(it, true) }
        val wire = saved.wireDataItem; wire[0] = 0
        assertContentEquals(byteArrayOf(9), store.replay(scope).single().wireDataItem)
        store.close()
    }
}
