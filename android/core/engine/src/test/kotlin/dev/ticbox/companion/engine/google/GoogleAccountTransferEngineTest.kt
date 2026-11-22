@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.ticbox.companion.engine.google

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class GoogleAccountTransferEngineTest {
    @Test fun `waits for watch started then relays opaque bytes both ways and completes only on provider result`() = runTest {
        val f = Fixture()
        val result = async { f.engine.transfer(PEER, 7, GoogleAccountTransferRequest()) }
        runCurrent()
        assertContentEquals(hex("0000000412020802"), f.channel.writes.single())
        assertEquals(0, f.provider.starts); assertFalse(result.isCompleted)
        f.channel.incoming.send(hex("000000041a020802") + GoogleAccountProtocol.frame(GoogleAccountProtocol.payload(hex("deadbeef"))))
        runCurrent()
        assertEquals(1, f.provider.starts)
        assertContentEquals(hex("deadbeef"), f.provider.pipe.writes.single())
        f.provider.pipe.incoming.send(hex("00ffab")); runCurrent()
        val decoded = GoogleAccountFrameDecoder().feed(f.channel.writes[1]).single()
        assertContentEquals(hex("00ffab"), decoded.payload); assertFalse(result.isCompleted)
        f.provider.pipe.completion.complete(GoogleServiceResult(GoogleServiceStatus.PARTIAL, "account_transfer", transferredAccounts = 1, failedAccounts = 1))
        assertEquals(GoogleServiceStatus.PARTIAL, result.await().status)
        assertTrue(f.channel.closed); assertTrue(f.provider.pipe.closed)
    }
    @Test fun `stale channel is closed before sending START or acquiring a Google pipe`() = runTest {
        val f = Fixture(channel = FakeChannel(sessionId = 6))
        val result = f.engine.transfer(PEER, 7, GoogleAccountTransferRequest())
        assertEquals("stale_channel", result.reason)
        assertTrue(f.channel.closed); assertTrue(f.channel.writes.isEmpty()); assertEquals(0, f.provider.starts)
    }
    @Test fun `physical-session loss releases channel and pipe even without further bytes`() = runTest {
        val f = Fixture()
        val result = async { f.engine.transfer(PEER, 7, GoogleAccountTransferRequest()) }
        runCurrent(); f.channel.incoming.send(hex("000000041a020802")); runCurrent()
        f.backend.state.value = ConnectionState(ConnectionPhase.IDLE, PEER, 7)
        runCurrent()
        assertEquals(GoogleServiceStatus.FAILED, result.await().status)
        assertTrue(f.provider.pipe.closed); assertTrue(f.channel.closed)
    }
    @Test fun `watch readiness timeout is local failure and never calls provider`() = runTest {
        val f = Fixture(policy = GoogleServicePolicy(watchStartedMillis = 100, transferMillis = 1000))
        val result = async { f.engine.transfer(PEER, 7, GoogleAccountTransferRequest()) }
        runCurrent(); advanceTimeBy(101); runCurrent()
        assertEquals(GoogleServiceStatus.TIMED_OUT, result.await().status)
        assertEquals(0, f.provider.starts); assertTrue(f.channel.closed)
    }
    @Test fun `unknown envelopes do not fake status or trigger another protocol operation`() = runTest {
        val f = Fixture()
        val result = async { f.engine.transfer(PEER, 7, GoogleAccountTransferRequest()) }
        runCurrent(); f.channel.incoming.send(hex("00000005082f2a0100")); runCurrent()
        assertEquals(0, f.provider.starts); assertFalse(result.isCompleted)
        assertTrue(f.logs.any { it.event == "unknown_envelope_fields" })
        f.engine.abort(); runCurrent()
        assertFailsWith<CancellationException> { result.await() }
        assertTrue(f.channel.closed)
    }
    @Test fun `real provider permission denial is explicit and contains no account name in diagnostics`() = runTest {
        val f = Fixture(); f.provider.failure = GoogleServiceException(GoogleServiceStatus.PERMISSION_DENIED, "gms_get_service_permission_denied", 10)
        val result = async { f.engine.transfer(PEER, 7, GoogleAccountTransferRequest(GoogleAccountSelection("private@example.invalid"))) }
        runCurrent(); f.channel.incoming.send(hex("000000041a020802")); runCurrent()
        assertEquals(GoogleServiceStatus.PERMISSION_DENIED, result.await().status)
        assertEquals(10, result.await().serviceStatusCode)
        assertFalse(f.logs.any { it.details.toString().contains("private@") })
        assertTrue(f.channel.closed)
    }
    @Test fun `abort closes a blocked pipe immediately and engine can be reused`() = runTest {
        val f = Fixture()
        val first = async { f.engine.transfer(PEER, 7, GoogleAccountTransferRequest()) }
        runCurrent(); f.channel.incoming.send(hex("000000041a020802")); runCurrent()
        f.engine.abort(); runCurrent()
        assertTrue(f.provider.pipe.closed); assertTrue(f.channel.closed)
        assertFailsWith<CancellationException> { first.await() }
        f.engine.close()
        assertEquals(GoogleServiceStatus.UNAVAILABLE, f.engine.transfer(PEER, 7, GoogleAccountTransferRequest()).status)
    }
    @Test fun `malformed frame closes stream and does not start provider`() = runTest {
        val f = Fixture()
        val result = async { f.engine.transfer(PEER, 7, GoogleAccountTransferRequest()) }
        runCurrent(); f.channel.incoming.send(hex("ffffffff")); runCurrent()
        assertEquals(GoogleServiceStatus.FAILED, result.await().status)
        assertTrue(f.channel.closed); assertEquals(0, f.provider.starts)
    }

    private class Fixture(val channel: FakeChannel = FakeChannel(), policy: GoogleServicePolicy = GoogleServicePolicy()) {
        val provider = FakeProvider()
        val backend = FakeBackend(channel)
        val logs = mutableListOf<ProtocolDiagnostic>()
        val engine = GoogleAccountTransferEngine(backend, provider, DiagnosticSink(logs::add), policy)
    }
    internal class FakeProvider : GoogleServiceProvider {
        val pipe = FakePipe()
        var starts = 0
        var failure: GoogleServiceException? = null
        val cloudCalls = mutableListOf<Pair<String, Boolean>>()
        override suspend fun startAccountTransfer(request: GoogleAccountTransferRequest, awaitingUser: () -> Unit): GoogleAccountPipe {
            starts++; failure?.let { throw it }; return pipe
        }
        override suspend fun optInCloudSync(enabled: Boolean): GoogleServiceResult {
            cloudCalls += "opt_in" to enabled
            failure?.let { throw it }
            return GoogleServiceResult(GoogleServiceStatus.COMPLETE, "cloud_sync_consent")
        }
        override suspend fun setCloudSyncSetting(enabled: Boolean): GoogleServiceResult {
            cloudCalls += "setting" to enabled
            return GoogleServiceResult(GoogleServiceStatus.COMPLETE, "cloud_sync_setting")
        }
        override fun close() { pipe.close() }
    }
    internal class FakePipe : GoogleAccountPipe {
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        val writes = mutableListOf<ByteArray>()
        var closed = false
        override val completion = CompletableDeferred<GoogleServiceResult>()
        override suspend fun read(): ByteArray? = incoming.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) { writes += bytes.copyOf() }
        override fun close() { closed = true; incoming.cancel() }
    }
    private class FakeChannel(sessionId: Long = 7) : WearChannel {
        override val info = ChannelInfo(PEER, sessionId, 2uL, true, GoogleAccountProtocol.CHANNEL_PATH)
        override val state = MutableStateFlow(ChannelState(ChannelPhase.OPEN))
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        val writes = mutableListOf<ByteArray>()
        var closed = false
        override suspend fun read(maxBytes: Int): ByteArray? = incoming.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) { check(!closed); writes += bytes.copyOf() }
        override suspend fun finishOutput() {}
        override fun closeInput() { incoming.cancel() }
        override suspend fun closeGracefully(errorCode: Int) = close()
        override fun close() { closed = true; incoming.cancel() }
    }
    private class FakeBackend(private val channel: FakeChannel) : ChannelBackend {
        override val state = MutableStateFlow(ConnectionState(ConnectionPhase.LINK_READY, PEER, 7))
        override val events = MutableSharedFlow<BackendEvent>()
        override suspend fun openChannel(path: String, allowOverMetered: Boolean) = channel
        override suspend fun acceptChannel(): WearChannel = error("not used")
        override suspend fun connect(peer: PeerAddress) {}
        override suspend fun disconnect() {}
        override suspend fun sendMessage(path: String, payload: ByteArray): TransmissionReceipt = error("not used")
        override fun close() {}
    }
    private companion object {
        val PEER = PeerAddress("AA:BB:CC:DD:EE:FF")
        fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
