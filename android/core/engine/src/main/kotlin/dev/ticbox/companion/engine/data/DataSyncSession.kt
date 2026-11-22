package dev.ticbox.companion.engine.data

import dev.ticbox.companion.api.ApplicationRoute
import dev.ticbox.companion.api.DataScope
import dev.ticbox.companion.api.PeerAddress

/** One established physical session; the injected writer owns framing and its session guard. */
class DataSyncSession(
    val peer: PeerAddress,
    val sessionId: Long,
    val localNodeId: String,
    val remoteNodeId: String,
    val networkId: String? = null,
    val route: ApplicationRoute,
    val sendEnvelope: suspend (ByteArray) -> Unit,
    /** Must close only this physical session; stale callbacks must never close its replacement. */
    val onResyncRequired: (String) -> Unit = {},
    /** Physical CONNECT negotiation, independent of the DataApi SyncStart version. */
    val negotiatedWireVersion: Int,
) {
    init { require(negotiatedWireVersion in 0..1) }
    val dataScope = DataScope(peer, localNodeId, remoteNodeId, networkId)
}

/** Local allocation budgets, not inferred peer protocol restrictions. */
data class DataSyncConfig(
    val inboundQueueCapacity: Int = 64,
    val maxItemBytes: Int = 512 * 1024,
    val maxAssetBytes: Int = 16 * 1024 * 1024,
    val maxIncomingAttachments: Int = 4,
    val maxPendingAssets: Int = 128,
    val filePieceBytes: Int = 60 * 1024,
) {
    init {
        require(inboundQueueCapacity > 0 && maxItemBytes > 0 && maxAssetBytes > 0)
        require(maxIncomingAttachments > 0 && maxPendingAssets > 0)
        require(filePieceBytes in 1..65536)
    }
}
