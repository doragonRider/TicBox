package dev.ticbox.companion.api

import kotlinx.coroutines.flow.StateFlow

/** A persisted DataApi namespace. A reset/replaced peer node does not reuse its old cursors. */
data class DataScope(
    val peer: PeerAddress,
    val localNodeId: String,
    val remoteNodeId: String,
    val networkId: String? = null,
)

data class DataAssetReference(val name: String, val digest: String, val type: Int = 4)

/** Latest value/tombstone for one AppKey + complete wear URI; not a health history record. */
class DataItemRecord(
    val scope: DataScope,
    val route: ApplicationRoute,
    val uri: String,
    val sourceNodeId: String,
    val sequenceId: Long,
    val deleted: Boolean,
    data: ByteArray?,
    assets: List<DataAssetReference> = emptyList(),
    val lastModifiedMillis: Long? = null,
    val receivedAtEpochMillis: Long,
    val local: Boolean = false,
) {
    private val body = data?.copyOf()
    val data: ByteArray? get() = body?.copyOf()
    val assets: List<DataAssetReference> = assets.toList()
}

class DataItemDraft(
    val uri: String,
    data: ByteArray? = null,
    assets: List<DataAssetReference> = emptyList(),
    val deleted: Boolean = false,
    val lastModifiedMillis: Long? = null,
) {
    private val body = data?.copyOf()
    val data: ByteArray? get() = body?.copyOf()
    val assets: List<DataAssetReference> = assets.toList()
}

data class DataItemQuery(
    val route: ApplicationRoute? = null,
    val uriPrefix: String? = null,
    val includeDeleted: Boolean = false,
    val offset: Int = 0,
    val limit: Int = 100,
)
data class DataItemPage(val items: List<DataItemRecord>, val nextOffset: Int?)

/** The bytes are present only after digest verification and a matching AppKey permission. */
class DataAssetRecord(
    val scope: DataScope,
    val digest: String,
    owners: Set<ApplicationRoute>,
    data: ByteArray?,
    val receivedAtEpochMillis: Long,
) {
    val owners: Set<ApplicationRoute> = owners.toSet()
    private val body = data?.copyOf()
    val data: ByteArray? get() = body?.copyOf()
    val sizeBytes: Int? get() = body?.size
}

enum class DataSyncPhase { DISCONNECTED, RESUMING, ACTIVE, NEEDS_RESYNC, FAILED, CLOSED }
enum class DataSyncIssue { MALFORMED_MESSAGE, STORAGE, SEND_FAILED, RESOURCE_LIMIT, ASSET_DIGEST_MISMATCH, ASSET_PERMISSION_MISSING }

data class DataSyncState(
    val phase: DataSyncPhase = DataSyncPhase.DISCONNECTED,
    val scope: DataScope? = null,
    val sessionId: Long = 0,
    val receivedItems: Long = 0,
    val sentItems: Long = 0,
    val duplicateItems: Long = 0,
    val pendingAssetFetches: Int = 0,
    val pendingAssetSends: Int = 0,
    val incomingAttachments: Int = 0,
    val droppedEnvelopes: Long = 0,
    val lastIssue: DataSyncIssue? = null,
    /** DataApi has no observed end-of-history marker. ACTIVE is not a full-sync success. */
    val initialSyncCompletionObserved: Boolean = false,
    /** Unavailable/denied responses retained for an explicit retry or reconnect. */
    val unavailableAssets: Int = 0,
)

/**
 * Typed backend workflow API. Storage queries work offline for a previously known scope.
 * publish/delete commit before network submission and are replayed from peer cursors on reconnect.
 * Calls returning do not assert remote application delivery or complete health history export.
 */
enum class DataSyncBarrierStatus { COMPLETE, UNAVAILABLE, TIMED_OUT, INTERRUPTED, UNVERIFIED }

/** A finite source-sequence plus referenced-asset barrier, never a health-history completeness claim. */
data class DataSyncBarrierResult(
    val status: DataSyncBarrierStatus,
    val sessionId: Long,
    val trackerId: String? = null,
    val sentSequenceId: Long? = null,
    val receivedSequenceId: Long? = null,
)

interface DataSyncClient : AutoCloseable {
    val state: StateFlow<DataSyncState>
    suspend fun scopes(peer: PeerAddress? = null): List<DataScope>
    suspend fun items(scope: DataScope, query: DataItemQuery = DataItemQuery()): DataItemPage
    suspend fun publish(scope: DataScope, route: ApplicationRoute, draft: DataItemDraft): DataItemRecord
    suspend fun delete(scope: DataScope, route: ApplicationRoute, uri: String): DataItemRecord
    suspend fun putAsset(scope: DataScope, route: ApplicationRoute, bytes: ByteArray): DataAssetRecord
    suspend fun asset(scope: DataScope, route: ApplicationRoute, digest: String): DataAssetRecord?
    suspend fun requestAsset(scope: DataScope, route: ApplicationRoute, digest: String, permissionOnly: Boolean = false)
    suspend fun awaitProgressBarrier(timeoutMillis: Long = 60_000): DataSyncBarrierResult
    suspend fun requestSync()
    override fun close()
}
