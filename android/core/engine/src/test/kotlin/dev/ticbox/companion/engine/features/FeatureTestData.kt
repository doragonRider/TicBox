package dev.ticbox.companion.engine.features

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.CompanionFeatureSession
import kotlinx.coroutines.flow.MutableStateFlow

internal class FeatureTestData : DataSyncClient {
    val scope = DataScope(PeerAddress("AA:BB:CC:DD:EE:01"), "phone", "watch", "network")
    val route = ApplicationRoute("companion.test", "digest")
    override val state = MutableStateFlow(DataSyncState(DataSyncPhase.ACTIVE, scope, 1))
    val records = linkedMapOf<Pair<ApplicationRoute, String>, DataItemRecord>()
    val published = mutableListOf<DataItemRecord>()
    val assetRequests = mutableListOf<String>()
    var onSend: suspend (ApplicationRoute, String, ByteArray) -> Unit = { _, _, _ -> }
    var onRequest: suspend (ApplicationRoute, String, ByteArray) -> ByteArray = { _, _, _ -> byteArrayOf() }
    val sent = mutableListOf<Triple<ApplicationRoute, String, ByteArray>>()
    fun session(id: Long = 1) = CompanionFeatureSession(id, scope, route,
        send = { key, path, bytes -> sent += Triple(key, path, bytes.copyOf()); onSend(key, path, bytes) },
        request = { key, path, bytes, _ -> onRequest(key, path, bytes) },
        open = { _, _ -> error("No Channel in this fixture") })
    override suspend fun scopes(peer: PeerAddress?) = listOf(scope)
    override suspend fun items(scope: DataScope, query: DataItemQuery): DataItemPage {
        val rows = records.values.filter { it.scope == scope && (query.route == null || it.route == query.route) &&
            (query.includeDeleted || !it.deleted) && (query.uriPrefix == null || it.uri.startsWith(query.uriPrefix!!)) }
        return DataItemPage(rows.drop(query.offset).take(query.limit), (query.offset + query.limit).takeIf { it < rows.size })
    }
    override suspend fun publish(scope: DataScope, route: ApplicationRoute, draft: DataItemDraft): DataItemRecord {
        val record = DataItemRecord(scope, route, draft.uri, scope.localNodeId, published.size + 1L, draft.deleted,
            draft.data, draft.assets, draft.lastModifiedMillis, 1, true)
        records[route to draft.uri] = record; published += record
        state.value = state.value.copy(sentItems = state.value.sentItems + 1)
        return record
    }
    override suspend fun delete(scope: DataScope, route: ApplicationRoute, uri: String) = publish(scope, route, DataItemDraft(uri, deleted = true))
    override suspend fun putAsset(scope: DataScope, route: ApplicationRoute, bytes: ByteArray) = error("Not used")
    override suspend fun asset(scope: DataScope, route: ApplicationRoute, digest: String): DataAssetRecord? = null
    override suspend fun requestAsset(scope: DataScope, route: ApplicationRoute, digest: String, permissionOnly: Boolean) { assetRequests += digest }
    override suspend fun awaitProgressBarrier(timeoutMillis: Long) = DataSyncBarrierResult(DataSyncBarrierStatus.COMPLETE, state.value.sessionId)
    override suspend fun requestSync() = Unit
    override fun close() = Unit
    fun remote(path: String, body: ByteArray, route: ApplicationRoute = this.route, assets: List<DataAssetReference> = emptyList()) {
        val uri = "wear://watch$path"
        records[route to uri] = DataItemRecord(scope, route, uri, "watch", 1, false, body, assets, receivedAtEpochMillis = 1)
        state.value = state.value.copy(receivedItems = state.value.receivedItems + 1)
    }
}
