package dev.ticbox.companion.api

import kotlinx.coroutines.flow.StateFlow

enum class NetworkProxyPhase { STOPPED, STARTING, LISTENING, HANDSHAKING, ACTIVE, FAILED }

/** Observations of the independent, watch-initiated network proxy connection. */
data class NetworkProxyState(
    val phase: NetworkProxyPhase = NetworkProxyPhase.STOPPED,
    val peer: PeerAddress? = null,
    /** Local accepted-connection generation; unrelated to the main Data Layer session ID. */
    val connectionId: Long = 0,
    val peerHandshakeVersion: Int? = null,
    val tcpConnections: Int = 0,
    val udpConnections: Int = 0,
    val networkType: Int? = null,
    val isMetered: Boolean? = null,
    val failure: ConnectionFailure? = null,
)

/**
 * Service-owned Flow15 listener. The phone accepts a secure RFCOMM connection from the selected,
 * already bonded peer. Starting this listener does not complete setup or the main watch link.
 */
interface NetworkProxy : AutoCloseable {
    val state: StateFlow<NetworkProxyState>

    /** Start or replace the selected peer. Completion confirms only local scheduling. */
    suspend fun start(peer: PeerAddress)

    /** Stop accepting and close every socket owned by the current proxy; reusable afterwards. */
    suspend fun stop()

    /** Permanently release the listener, accepted connection and all network resources. */
    override fun close()
}
