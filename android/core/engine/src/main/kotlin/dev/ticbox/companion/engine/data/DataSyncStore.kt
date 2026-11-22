package dev.ticbox.companion.engine.data

import dev.ticbox.companion.api.*

/** Opaque source-preserving body used for reconnect replay, independent of UI models. */
class StoredDataItem(val item: DataItemRecord, wireDataItem: ByteArray, val relaySequence: Long = 0) {
    private val wire = wireDataItem.copyOf()
    val wireDataItem: ByteArray get() = wire.copyOf()
}

enum class RemoteApplyResult { APPLIED, DUPLICATE }

/**
 * Implementations serialize/transactionalize every method. applyRemote MUST commit the item and
 * its source cursor in one transaction, or neither. Local sequence allocation is also atomic.
 * No wire ACK may precede a successful asset commit.
 */
interface DataSyncStore : AutoCloseable {
    suspend fun rememberScope(scope: DataScope)
    suspend fun scopes(peer: PeerAddress? = null): List<DataScope>
    suspend fun cursors(scope: DataScope): Map<String, Long>
    suspend fun applyRemote(record: StoredDataItem): RemoteApplyResult
    suspend fun publishLocal(scope: DataScope, build: (Long) -> StoredDataItem): StoredDataItem
    suspend fun items(scope: DataScope, query: DataItemQuery): DataItemPage
    suspend fun replay(scope: DataScope): List<StoredDataItem>
    suspend fun asset(scope: DataScope, digest: String): DataAssetRecord?
    /** Permissions are additive, matching the observed Asset ACL store. Null data preserves bytes. */
    suspend fun putAsset(asset: DataAssetRecord)
    suspend fun missingAssets(scope: DataScope): List<Pair<ApplicationRoute, String>>
    override fun close()
}

internal fun DataItemQuery.checkBudget() {
    require(offset >= 0 && limit in 1..500) { "DataItem page budget exceeded" }
}

internal fun <T> List<T>.page(query: DataItemQuery): Pair<List<T>, Int?> {
    query.checkBudget()
    val end = (query.offset.toLong() + query.limit).coerceAtMost(size.toLong()).toInt()
    val start = query.offset.coerceAtMost(size)
    return subList(start, end).toList() to end.takeIf { it < size }
}

/** Transactional JVM implementation also useful for previews; production uses SQLite. */
class MemoryDataSyncStore(private val maxAssetStorageBytes: Long = 64L * 1024 * 1024) : DataSyncStore {
    private val lock = Any()
    private var closed = false
    private val knownScopes = linkedSetOf<DataScope>()
    private val records = linkedMapOf<ItemKey, StoredDataItem>()
    private val received = mutableMapOf<DataScope, MutableMap<String, Long>>()
    private val sequence = mutableMapOf<DataScope, Long>()
    private val assets = mutableMapOf<Pair<DataScope, String>, DataAssetRecord>()
    private data class ItemKey(val scope: DataScope, val route: ApplicationRoute, val uri: String)
    private fun alive() { check(!closed) { "Data sync store closed" } }

    override suspend fun rememberScope(scope: DataScope) = synchronized(lock) { alive(); knownScopes.add(scope); Unit }
    override suspend fun scopes(peer: PeerAddress?) = synchronized(lock) { alive(); knownScopes.filter { peer == null || it.peer == peer } }
    override suspend fun cursors(scope: DataScope) = synchronized(lock) { alive(); received[scope]?.toMap() ?: emptyMap() }
    override suspend fun applyRemote(record: StoredDataItem): RemoteApplyResult = synchronized(lock) {
        alive()
        val item = record.item
        val previous = received[item.scope]?.get(item.sourceNodeId) ?: -1L
        if (item.sequenceId <= previous) return@synchronized RemoteApplyResult.DUPLICATE
        val previousRelay = maxOf(sequence[item.scope] ?: 0L, if (item.sourceNodeId == item.scope.localNodeId) item.sequenceId else 0L)
        check(previousRelay < Long.MAX_VALUE) { "Local relay sequence exhausted" }
        val relay = previousRelay + 1
        records[ItemKey(item.scope, item.route, item.uri)] = StoredDataItem(item, record.wireDataItem, relay)
        sequence[item.scope] = relay
        received.getOrPut(item.scope) { mutableMapOf() }[item.sourceNodeId] = item.sequenceId
        knownScopes.add(item.scope)
        RemoteApplyResult.APPLIED
    }
    override suspend fun publishLocal(scope: DataScope, build: (Long) -> StoredDataItem): StoredDataItem = synchronized(lock) {
        alive()
        val previous = maxOf(sequence[scope] ?: 0L, received[scope]?.get(scope.localNodeId) ?: 0L)
        check(previous < Long.MAX_VALUE) { "Local DataItem sequence exhausted" }
        val next = previous + 1
        val record = build(next) // Construct fully before mutating either sequence or row.
        require(record.item.scope == scope && record.item.sequenceId == next && record.item.local)
        val stored = StoredDataItem(record.item, record.wireDataItem, next)
        records[ItemKey(scope, record.item.route, record.item.uri)] = stored
        sequence[scope] = next
        received.getOrPut(scope) { mutableMapOf() }[scope.localNodeId] = next
        knownScopes.add(scope)
        stored
    }
    override suspend fun items(scope: DataScope, query: DataItemQuery): DataItemPage = synchronized(lock) {
        alive()
        val list = records.values.map { it.item }.filter {
            it.scope == scope && (query.route == null || it.route == query.route) &&
                (query.uriPrefix == null || it.uri.startsWith(query.uriPrefix.orEmpty())) && (query.includeDeleted || !it.deleted)
        }.sortedWith(compareBy<DataItemRecord> { it.receivedAtEpochMillis }.thenBy { it.route.packageName }.thenBy { it.route.packageDigest }.thenBy { it.uri })
        val (page, next) = list.page(query)
        DataItemPage(page, next)
    }
    override suspend fun replay(scope: DataScope) = synchronized(lock) {
        alive(); records.values.filter { it.item.scope == scope }.sortedWith(compareBy<StoredDataItem> { it.item.sourceNodeId }.thenBy { it.item.sequenceId })
    }
    override suspend fun asset(scope: DataScope, digest: String) = synchronized(lock) { alive(); assets[scope to digest] }
    override suspend fun putAsset(asset: DataAssetRecord) = synchronized(lock) {
        alive()
        val key = asset.scope to asset.digest
        val old = assets[key]
        val bytes = asset.data ?: old?.data
        val total = assets.values.sumOf { it.sizeBytes?.toLong() ?: 0L } - (old?.sizeBytes ?: 0) + (bytes?.size ?: 0)
        check(total <= maxAssetStorageBytes) { "Asset storage budget exceeded" }
        assets[key] = DataAssetRecord(asset.scope, asset.digest, (old?.owners ?: emptySet()) + asset.owners, bytes, asset.receivedAtEpochMillis)
        knownScopes.add(asset.scope)
        Unit
    }
    override suspend fun missingAssets(scope: DataScope) = synchronized(lock) {
        alive()
        records.values.map { it.item }.filter { it.scope == scope && !it.deleted }.flatMap { item ->
            item.assets.filter { it.digest.isNotEmpty() }.mapNotNull { reference ->
                val asset = assets[scope to reference.digest]
                (item.route to reference.digest).takeIf { asset?.data == null || item.route !in asset.owners }
            }
        }.distinct()
    }
    override fun close() = synchronized(lock) { closed = true }
}
