package dev.ticbox.companion.engine.google

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.SessionEngine
import dev.ticbox.companion.protocol.GoogleAccountFrameDecoder
import dev.ticbox.companion.protocol.GoogleAccountProtocol
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first

/** The recovered phone-initiated /accounts/sync START -> watch STARTED -> SmartDevice relay. */
class GoogleAccountTransferEngine(
    private val channels: ChannelBackend,
    private val provider: GoogleServiceProvider,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val policy: GoogleServicePolicy = GoogleServicePolicy(),
) : AutoCloseable {
    private class Run(val job: Job) {
        val channel = AtomicReference<WearChannel?>()
        val pipe = AtomicReference<GoogleAccountPipe?>()
        val closed = AtomicBoolean()
        fun release() {
            closed.set(true)
            channel.getAndSet(null)?.close()
            pipe.getAndSet(null)?.close()
        }
        fun own(channel: WearChannel) { this.channel.set(channel); if (closed.get()) this.channel.getAndSet(null)?.close() }
        fun own(pipe: GoogleAccountPipe) { this.pipe.set(pipe); if (closed.get()) this.pipe.getAndSet(null)?.close() }
    }
    private val current = AtomicReference<Run?>()
    private val closed = AtomicBoolean()

    suspend fun transfer(
        peer: PeerAddress,
        sessionId: Long,
        request: GoogleAccountTransferRequest,
        phase: (GoogleAccountTransferPhase) -> Unit = {},
    ): GoogleServiceResult {
        if (closed.get()) return failure(GoogleServiceStatus.UNAVAILABLE, "closed")
        return try {
            coroutineScope {
                val run = Run(currentCoroutineContext().job)
                if (!current.compareAndSet(null, run)) return@coroutineScope failure(GoogleServiceStatus.FAILED, "transfer_already_running")
                try {
                    if (closed.get()) throw CancellationException("closed")
                    val connectionMonitor = launch {
                        channels.state.first { it.phase != ConnectionPhase.LINK_READY || it.peer != peer || it.sessionId != sessionId }
                        run.release()
                        throw GoogleServiceException(GoogleServiceStatus.FAILED, "physical_session_ended")
                    }
                    try { withTimeout(policy.transferMillis) {
                        ensureSession(peer, sessionId)
                        phase(GoogleAccountTransferPhase.OPENING_CHANNEL)
                        // Own inside timeout so cancellation cannot lose a just-acquired resource.
                        val channel = withTimeout(policy.channelOpenMillis) {
                            (if (channels is SessionEngine) channels.openChannel(peer, sessionId, GoogleAccountProtocol.CHANNEL_PATH)
                            else channels.openChannel(GoogleAccountProtocol.CHANNEL_PATH)).also { run.own(it) }
                        }
                        currentCoroutineContext().ensureActive()
                        if (channel.info.peer != peer || channel.info.sessionId != sessionId) throw GoogleServiceException(GoogleServiceStatus.FAILED, "stale_channel")
                        ensureSession(peer, sessionId)
                        coroutineScope {
                            val started = CompletableDeferred<Unit>()
                            val watchBytes = Channel<ByteArray>(policy.pendingPipeChunks)
                            val reader = launch {
                                val decoder = GoogleAccountFrameDecoder()
                                try {
                                    while (true) {
                                        val chunk = channel.read(policy.readChunkBytes) ?: break
                                        ensureSession(peer, sessionId)
                                        for (envelope in decoder.feed(chunk)) {
                                            envelope.status?.let { status ->
                                                log(peer, sessionId, "watch_status", mapOf("status" to status.toString()))
                                                if (status == GoogleAccountProtocol.STARTED_STATUS) started.complete(Unit)
                                            }
                                            if (envelope.unknownFieldNumbers.isNotEmpty() || envelope.type !in 1..4) {
                                                log(peer, sessionId, "unknown_envelope_fields", mapOf("type" to envelope.type.toString(), "fields" to envelope.unknownFieldNumbers.joinToString(",")))
                                            }
                                            envelope.payload?.let { watchBytes.send(it) }
                                        }
                                    }
                                    decoder.finish()
                                    if (!run.closed.get() && run.pipe.get()?.completion?.isCompleted != true) throw EOFException("account_channel_eof")
                                } catch (problem: IOException) {
                                    if (!run.closed.get()) throw problem
                                } finally { watchBytes.close() }
                            }
                            var outbound: Job? = null
                            var inbound: Job? = null
                            try {
                                channel.write(GoogleAccountProtocol.frame(GoogleAccountProtocol.start()))
                                phase(GoogleAccountTransferPhase.WAITING_FOR_WATCH)
                                withTimeout(policy.watchStartedMillis) { started.await() }
                                ensureSession(peer, sessionId)
                                phase(GoogleAccountTransferPhase.CONNECTING_GOOGLE)
                                val pipe = provider.startAccountTransfer(request) { phase(GoogleAccountTransferPhase.AWAITING_USER) }.also { run.own(it) }
                                currentCoroutineContext().ensureActive()
                                phase(if (provider.actions.value.isEmpty()) GoogleAccountTransferPhase.TRANSFERRING else GoogleAccountTransferPhase.AWAITING_USER)
                                inbound = launch {
                                    try { for (bytes in watchBytes) pipe.write(bytes) }
                                    catch (problem: IOException) { if (!run.closed.get()) throw problem }
                                }
                                outbound = launch {
                                    try { while (true) {
                                        val bytes = pipe.read() ?: break
                                        ensureSession(peer, sessionId)
                                        channel.write(GoogleAccountProtocol.frame(GoogleAccountProtocol.payload(bytes)))
                                    } } catch (problem: IOException) { if (!run.closed.get()) throw problem }
                                    log(peer, sessionId, "google_pipe_eof", emptyMap())
                                }
                                val result = pipe.completion.await()
                                ensureSession(peer, sessionId)
                                result
                            } finally {
                                // Closing the descriptors unblocks native reads before joining jobs.
                                run.release()
                                reader.cancel(); inbound?.cancel(); outbound?.cancel(); watchBytes.cancel()
                            }
                        }
                    } } finally { connectionMonitor.cancel() }
                } finally {
                    run.release()
                    current.compareAndSet(run, null)
                }
            }
        } catch (problem: TimeoutCancellationException) {
            log(peer, sessionId, "account_timeout", mapOf("exception" to problem.javaClass.simpleName))
            failure(GoogleServiceStatus.TIMED_OUT, "local_timeout")
        } catch (problem: CancellationException) {
            log(peer, sessionId, "account_cancelled", emptyMap())
            throw problem
        } catch (problem: Exception) {
            log(peer, sessionId, "account_failed", mapOf("exception" to problem.javaClass.simpleName, "reason" to ((problem as? GoogleServiceException)?.reasonCode ?: "transport_or_provider")))
            when (problem) {
                is GoogleServiceException -> GoogleServiceResult(problem.status, "account_transfer", problem.reasonCode, problem.serviceStatusCode)
                is SecurityException -> failure(GoogleServiceStatus.PERMISSION_DENIED, "android_or_google_permission_denied")
                else -> failure(GoogleServiceStatus.FAILED, "transport_or_provider_${problem.javaClass.simpleName}")
            }
        }
    }

    private fun ensureSession(peer: PeerAddress, sessionId: Long) {
        val state = channels.state.value
        if (state.phase != ConnectionPhase.LINK_READY || state.peer != peer || state.sessionId != sessionId) {
            throw GoogleServiceException(GoogleServiceStatus.FAILED, "physical_session_ended")
        }
    }

    fun abort() { current.get()?.let { it.release(); it.job.cancel(CancellationException("account_transfer_aborted")) } }
    override fun close() { closed.set(true); abort() }
    private fun failure(status: GoogleServiceStatus, reason: String) = GoogleServiceResult(status, "account_transfer", reason)
    private fun log(peer: PeerAddress, sessionId: Long, event: String, details: Map<String, String>) {
        runCatching { diagnostics.record(ProtocolDiagnostic("google_account", event, peer = peer, sessionId = sessionId, details = details)) }
    }
}
