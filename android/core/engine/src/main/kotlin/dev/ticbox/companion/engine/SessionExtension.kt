package dev.ticbox.companion.engine

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.ConnectMessage

/**
 * Backend-only hook. Lifecycle callbacks must return promptly. Envelope delivery may suspend
 * for bounded queue capacity; storage and network work remain on the extension's worker.
 */
interface SessionExtension {
    fun onReady(session: EstablishedSession) {}
    suspend fun onEnvelope(session: EstablishedSession, envelope: ByteArray) {}
    fun onClosed(session: EstablishedSession) {}
}

/** A physical-session lease. Sends and reconnect requests never migrate to a newer connection. */
class EstablishedSession internal constructor(
    val peer: PeerAddress,
    val sessionId: Long,
    val localIdentity: NodeIdentity,
    val remoteConnect: ConnectMessage,
    val route: ApplicationRoute,
    val negotiatedWireVersion: Int,
    private val send: suspend (ByteArray) -> TransmissionReceipt,
    private val reconnect: (String) -> Unit,
) {
    val remoteNodeId: String get() = remoteConnect.nodeId
    val remoteNetworkId: String? get() = remoteConnect.networkId
    suspend fun sendEnvelope(bytes: ByteArray): TransmissionReceipt = send(bytes)
    fun requestReconnect(reason: String) = reconnect(reason)
}

/** Routed RequestApi input. Body copies protect the shared session from application mutation. */
class SessionRpcRequest(
    val peer: PeerAddress,
    val sessionId: Long,
    val path: String,
    val sourceNodeId: String,
    payload: ByteArray,
    val route: ApplicationRoute,
) {
    private val body = payload.copyOf()
    val payload: ByteArray get() = body.copyOf()
}
