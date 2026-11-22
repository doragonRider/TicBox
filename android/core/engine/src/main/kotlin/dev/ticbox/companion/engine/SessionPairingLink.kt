package dev.ticbox.companion.engine

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.pairing.PairingLink
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** Joins the service's connection owner without creating a second reconnection loop. */
class SessionPairingLink(
    override val state: StateFlow<ConnectionState>,
    private val connect: suspend (PeerAddress) -> Unit,
    private val open: suspend (PeerAddress, Long, String) -> WearChannel,
) : PairingLink {
    override suspend fun connectAndAwaitReady(peer: PeerAddress): ConnectionState {
        val current = state.value
        if (current.peer == peer && current.phase == ConnectionPhase.LINK_READY) return current
        connect(peer)
        val observed = state.first { it.peer != peer || it.phase in setOf(ConnectionPhase.LINK_READY, ConnectionPhase.FAILED, ConnectionPhase.IDLE) }
        if (observed.peer != peer || observed.phase != ConnectionPhase.LINK_READY) throw NotConnectedException()
        return observed
    }

    override suspend fun openPermissionChannel(peer: PeerAddress, sessionId: Long): WearChannel {
        ensureCurrent(peer, sessionId)
        val channel = open(peer, sessionId, PERMISSION_CHANNEL)
        try {
            ensureCurrent(peer, sessionId)
            if (channel.info.peer != peer || channel.info.sessionId != sessionId) throw NotConnectedException()
            return channel
        } catch (failure: Throwable) { channel.close(); throw failure }
    }

    private fun ensureCurrent(peer: PeerAddress, sessionId: Long) {
        val current = state.value
        if (current.peer != peer || current.sessionId != sessionId || current.phase != ConnectionPhase.LINK_READY) throw NotConnectedException()
    }

    companion object { const val PERMISSION_CHANNEL = "/cdm/channel/permission_sync" }
}
