@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.ticbox.companion.engine

import dev.ticbox.companion.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

class SessionPairingLinkTest {
    private val peer = PeerAddress("AA:BB:CC:DD:EE:FF")
    private val other = PeerAddress("00:11:22:33:44:55")
    private fun ready(id: Long = 1) = ConnectionState(ConnectionPhase.LINK_READY, peer, id)

    @Test fun aCurrentReadyLinkDoesNotStartASecondConnectionOwner() = runTest {
        val state = MutableStateFlow(ready())
        val link = SessionPairingLink(state, { error("already connected") }, { _, _, _ -> error("not requested") })
        assertEquals(1, link.connectAndAwaitReady(peer).sessionId)
    }

    @Test fun serviceConnectAcceptanceWaitsForActualLinkReady() = runTest {
        val state = MutableStateFlow(ConnectionState())
        var commands = 0
        val link = SessionPairingLink(state, { commands++; state.value = ConnectionState(ConnectionPhase.CONNECTING, peer) }, { _, _, _ -> error("not requested") })
        val pending = async { link.connectAndAwaitReady(peer) }
        runCurrent()
        assertFalse(pending.isCompleted)
        state.value = ready(3)
        assertEquals(3, pending.await().sessionId)
        assertEquals(1, commands)
    }

    @Test fun selectingAnotherWatchDoesNotResumeAnOlderPairingOperation() = runTest {
        val state = MutableStateFlow(ConnectionState())
        val link = SessionPairingLink(state, { state.value = ConnectionState(ConnectionPhase.CONNECTING, peer) }, { _, _, _ -> error("not requested") })
        val pending = async { runCatching { link.connectAndAwaitReady(peer) } }
        runCurrent()
        state.value = ConnectionState(ConnectionPhase.LINK_READY, other, 7)
        assertIs<NotConnectedException>(pending.await().exceptionOrNull())
    }

    @Test fun aChannelOpenedWhileThePhysicalLinkChangesIsClosedAndRejected() = runTest {
        val state = MutableStateFlow(ready())
        val channel = TestChannel(peer, 1)
        val link = SessionPairingLink(state, {}, { _, _, path ->
            assertEquals(SessionPairingLink.PERMISSION_CHANNEL, path)
            state.value = ready(2)
            channel
        })
        assertFailsWith<NotConnectedException> { link.openPermissionChannel(peer, 1) }
        assertTrue(channel.closed)
    }

    @Test fun aStaleConsentNeverOpensAChannelOnTheReplacementLink() = runTest {
        val state = MutableStateFlow(ready(2))
        val link = SessionPairingLink(state, {}, { _, _, _ -> error("stale operation must not open") })
        assertFailsWith<NotConnectedException> { link.openPermissionChannel(peer, 1) }
    }

    private class TestChannel(peer: PeerAddress, id: Long) : WearChannel {
        override val info = ChannelInfo(peer, id, 1u, true, SessionPairingLink.PERMISSION_CHANNEL)
        override val state = MutableStateFlow(ChannelState(ChannelPhase.OPEN))
        var closed = false
        override suspend fun read(maxBytes: Int): ByteArray? = null
        override suspend fun write(bytes: ByteArray) = Unit
        override suspend fun finishOutput() = Unit
        override fun closeInput() = Unit
        override suspend fun closeGracefully(errorCode: Int) { close() }
        override fun close() { closed = true }
    }
}
