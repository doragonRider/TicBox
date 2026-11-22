package dev.ticbox.companion.engine.proxy

import dev.ticbox.companion.api.BackendClosedException
import dev.ticbox.companion.api.ConnectionFailure
import dev.ticbox.companion.api.DiagnosticSeverity
import dev.ticbox.companion.api.DiagnosticSink
import dev.ticbox.companion.api.FailureKind
import dev.ticbox.companion.api.NetworkProxy
import dev.ticbox.companion.api.NetworkProxyPhase
import dev.ticbox.companion.api.NetworkProxyState
import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.api.ProtocolDiagnostic
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Selection and lifecycle are testable without an Android adapter or a real watch. */
class Flow15ProxyServer(
    parentScope: CoroutineScope,
    private val listeners: ProxyListenerFactory,
    private val network: ProxyNetwork,
    private val networks: ProxyNetworkMonitor = ProxyNetworkMonitor.DISCONNECTED,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val policy: Flow15ProxyPolicy = Flow15ProxyPolicy(),
) : NetworkProxy {
    private val owner = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + owner)
    private val lifecycle = Mutex()
    private val closed = AtomicBoolean()
    private val current = AtomicReference<Run?>()
    private val sequence = AtomicLong()
    private val publication = Any()
    private val mutableState = MutableStateFlow(NetworkProxyState())
    override val state = mutableState.asStateFlow()

    init {
        owner.invokeOnCompletion {
            val run = synchronized(publication) {
                current.getAndSet(null).also { mutableState.value = NetworkProxyState() }
            }
            run?.release()
        }
    }

    override suspend fun start(peer: PeerAddress) = lifecycle.withLock {
        if (closed.get() || !owner.isActive) throw BackendClosedException()
        val existing = current.get()
        if (existing?.peer == peer && existing.job.isActive) return@withLock
        stopCurrent()
        val run = Run(peer)
        synchronized(publication) {
            if (closed.get() || !owner.isActive) throw BackendClosedException()
            current.set(run)
            mutableState.value = NetworkProxyState(NetworkProxyPhase.STARTING, peer)
        }
        run.job = scope.launch(start = CoroutineStart.LAZY) { serve(run) }
        // close() may race this suspend API; never publish a new listener after owner shutdown.
        if (closed.get() || !owner.isActive) {
            synchronized(publication) {
                if (current.compareAndSet(run, null)) mutableState.value = NetworkProxyState()
            }
            run.release()
            throw BackendClosedException()
        }
        run.job.start()
    }

    override suspend fun stop() = lifecycle.withLock {
        stopCurrent()
        mutableState.value = NetworkProxyState()
    }

    private suspend fun stopCurrent() {
        val run = synchronized(publication) {
            current.getAndSet(null).also { mutableState.value = NetworkProxyState() }
        } ?: return
        run.release()
        run.job.cancelAndJoin()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val run = synchronized(publication) {
            current.getAndSet(null).also { mutableState.value = NetworkProxyState() }
        }
        run?.release()
        owner.cancel()
    }

    private suspend fun serve(run: Run) = coroutineScope {
        try {
            val listener = listeners.listen(run.peer)
            if (!run.install(listener)) return@coroutineScope
            publish(run, NetworkProxyState(NetworkProxyPhase.LISTENING, run.peer))
            while (true) {
                val accepted = listener.accept()
                var transferred = false
                try {
                    if (current.get() !== run || run.released.get()) { accepted.transport.close(); break }
                    if (accepted.peer != run.peer || !accepted.isBonded) {
                        accepted.transport.close()
                        diagnostic(run, "peer_rejected", mapOf("selectedMatch" to (accepted.peer == run.peer).toString(), "bonded" to accepted.isBonded.toString()))
                        continue
                    }
                    // A fresh connection from the same selected peer replaces its previous generation.
                    run.session.getAndSet(null)?.close()
                    run.sessionJob?.cancelAndJoin()
                    val session = Flow15ProxySession(
                        run.peer, sequence.incrementAndGet(), accepted.transport, network, networks,
                        diagnostics, policy,
                    )
                    run.session.set(session)
                    transferred = true
                    if (run.released.get()) { session.close(); break }
                    run.sessionJob = launch {
                        val observer = launch { session.state.collect { snapshot ->
                            if (run.session.get() === session) publish(run, snapshot)
                        } }
                        var failure: ConnectionFailure? = null
                        try { session.run() }
                        catch (timeout: kotlinx.coroutines.TimeoutCancellationException) { failure = proxyFailure(timeout) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { failure = proxyFailure(error) }
                        finally {
                            session.close()
                            observer.cancel()
                            if (run.session.compareAndSet(session, null)) {
                                publish(run, NetworkProxyState(NetworkProxyPhase.LISTENING, run.peer, failure = failure))
                            }
                        }
                    }
                } finally {
                    // Cancellation while retiring the previous generation must not leak this
                    // newly accepted socket before a Flow15ProxySession has taken ownership.
                    if (!transferred) accepted.transport.close()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            diagnostic(run, "listener_failed", mapOf("exception" to error.javaClass.simpleName))
            publish(run, NetworkProxyState(NetworkProxyPhase.FAILED, run.peer, failure = proxyFailure(error)))
        } finally { run.release() }
    }

    private fun publish(run: Run, snapshot: NetworkProxyState) {
        synchronized(publication) {
            if (current.get() === run && !closed.get() && !run.released.get()) mutableState.value = snapshot
        }
    }

    private fun diagnostic(run: Run, event: String, details: Map<String, String>) {
        runCatching { diagnostics.record(ProtocolDiagnostic("Flow15", event, DiagnosticSeverity.WARNING, run.peer, details = details)) }
    }

    private class Run(val peer: PeerAddress) {
        val released = AtomicBoolean()
        val listener = AtomicReference<ProxyListener?>()
        val session = AtomicReference<Flow15ProxySession?>()
        lateinit var job: Job
        var sessionJob: Job? = null
        fun install(value: ProxyListener): Boolean {
            listener.set(value)
            if (released.get()) { listener.getAndSet(null)?.close(); return false }
            return true
        }
        fun release() {
            released.set(true)
            runCatching { listener.getAndSet(null)?.close() }
            session.getAndSet(null)?.close()
            if (::job.isInitialized) job.cancel()
        }
    }
}

internal fun proxyFailure(error: Exception): ConnectionFailure = ConnectionFailure(
    when {
        error is SecurityException -> FailureKind.PERMISSION_REQUIRED
        error is Flow15ProtocolException -> FailureKind.PROTOCOL
        error is kotlinx.coroutines.TimeoutCancellationException || error.cause is kotlinx.coroutines.TimeoutCancellationException -> FailureKind.TIMEOUT
        error is java.io.IOException -> FailureKind.TRANSPORT
        else -> FailureKind.UNEXPECTED
    },
    // Exception messages may contain hostnames, platform paths or packet contents. Keep metadata only.
    "Flow15: ${error.javaClass.simpleName}",
)
