package dev.ticbox.companion.engine

import dev.ticbox.companion.api.ApplicationRoute
import dev.ticbox.companion.api.BackendClosedException
import dev.ticbox.companion.api.BackendEvent
import dev.ticbox.companion.api.DiagnosticSink
import dev.ticbox.companion.api.DiagnosticSeverity
import dev.ticbox.companion.api.ProtocolDiagnostic
import dev.ticbox.companion.api.ChannelBackend
import dev.ticbox.companion.api.WearChannel
import dev.ticbox.companion.api.ConnectionFailure
import dev.ticbox.companion.api.ConnectionPhase
import dev.ticbox.companion.api.ConnectionState
import dev.ticbox.companion.api.DuplexTransport
import dev.ticbox.companion.api.FailureKind
import dev.ticbox.companion.api.IdentityStore
import dev.ticbox.companion.api.InboundMessage
import dev.ticbox.companion.api.NodeIdentity
import dev.ticbox.companion.api.NotConnectedException
import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.api.TransmissionReceipt
import dev.ticbox.companion.api.TransportFactory
import dev.ticbox.companion.protocol.ChannelRequest
import dev.ticbox.companion.protocol.ConnectMessage
import dev.ticbox.companion.protocol.GmsRecord
import dev.ticbox.companion.protocol.GmsRecordDecoder
import dev.ticbox.companion.protocol.MessageAssembler
import dev.ticbox.companion.protocol.MessagePieces
import dev.ticbox.companion.protocol.RpcMessage
import dev.ticbox.companion.protocol.RpcKind
import dev.ticbox.companion.protocol.WearMessages
import dev.ticbox.companion.protocol.DecodedStruct
import dev.ticbox.companion.protocol.DataProtocol
import java.io.EOFException
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.min
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Main secure-RFCOMM record/CONNECT/MessageApi session. This is not the first-activation workflow:
 * normal compatible CONNECT establishes LINK_READY; migration first completes the accountless
 * control exchange before business traffic resumes on the same stream. Channel streams are scoped to this physical
 * attempt; DataApi and domain acknowledgements remain separate layers. No native crypto, account transfer, or permission restoration runs
 * here. The injected scope belongs to the Android service/application, never to a page ViewModel.
 */
class SessionEngine(
    private val scope: CoroutineScope,
    private val transports: TransportFactory,
    private val identities: IdentityStore,
    private val route: ApplicationRoute,
    private val config: SessionConfig = SessionConfig(),
    private val senderSdkVersion: Int? = null,
    private val random: Random = Random.Default,
    private val channelConfig: ChannelConfig = ChannelConfig(),
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    extensions: List<SessionExtension> = emptyList(),
    private val rpcDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * Where inbound frames and envelopes are decoded. Handlers still run in [scope], so callers
     * that rely on its thread keep their ordering; only the pure parsing work moves.
     */
    private val parseContext: CoroutineContext = EmptyCoroutineContext,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) : ChannelBackend, RoutedMessageSource {
    private val extensions = extensions.toList()
    private val requestHandlers = linkedMapOf<RoutePath, suspend (SessionRpcRequest) -> ByteArray>()
    private val observedMessageRoutes = linkedMapOf<RoutePath, Int>()
    private val lifecycle = Mutex()
    // Every generation change and state publication uses this same short monitor. A check followed
    // by an unprotected StateFlow write could otherwise publish a stale callback after disconnect.
    private val stateLock = Any()
    // The lifecycle generation cancels a whole reconnect loop. Public session IDs instead identify
    // each individual transport attempt so domain observations cannot become fresh on reconnect.
    private var generation = 0L
    private var sessionCounter = 0L
    private var closed = false
    private var desiredPeer: PeerAddress? = null
    private var worker: Job? = null
    private var active: ActiveSession? = null
    private val mutableState = MutableStateFlow(ConnectionState())
    private val messages = SessionMessageDispatcher(config) { event, failure, exception ->
        recordDiagnostic(ProtocolDiagnostic("session", "business_message_delivery_failed", DiagnosticSeverity.WARNING,
            event.peer, event.sessionId, details = mapOf("handler" to failure.handler, "path" to failure.path,
                "requestId" to failure.requestId.toString(), "kind" to failure.kind.name,
                "decision" to "report failed delivery; keep other handlers and Channel reader running") +
                (exception?.let { mapOf("exception" to it) } ?: emptyMap())))
        synchronized(stateLock) {
            val old = mutableState.value
            if (old.peer == event.peer && old.sessionId == event.sessionId) mutableState.value = old.copy(
                failedBusinessMessages = if (old.failedBusinessMessages == Long.MAX_VALUE) Long.MAX_VALUE else old.failedBusinessMessages + 1,
                lastMessageDeliveryFailure = failure)
        }
    }
    private val mutableEvents = MutableSharedFlow<BackendEvent>(
        replay = 0,
        extraBufferCapacity = config.eventBufferCapacity,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    override val state: StateFlow<ConnectionState> = mutableState.asStateFlow()
    override val events: SharedFlow<BackendEvent> = mutableEvents.asSharedFlow()

    override suspend fun connect(peer: PeerAddress) = lifecycle.withLock {
        scope.coroutineContext.ensureActive()
        currentCoroutineContext().ensureActive()
        synchronized(stateLock) {
            if (closed) throw BackendClosedException()
            if (desiredPeer == peer && worker?.isActive == true &&
                mutableState.value.phase != ConnectionPhase.FAILED
            ) return@withLock
        }
        stopCurrent()
        currentCoroutineContext().ensureActive()
        val epoch = synchronized(stateLock) {
            if (closed) throw BackendClosedException()
            ++generation
        }
        val next = scope.launch(start = CoroutineStart.LAZY) { reconnectLoop(epoch, peer) }
        val installed = synchronized(stateLock) {
            if (closed || epoch != generation) {
                false
            } else {
                desiredPeer = peer
                worker = next
                mutableState.value = ConnectionState(ConnectionPhase.CONNECTING, peer)
                true
            }
        }
        if (!installed) {
            next.cancel()
            throw BackendClosedException()
        }
        next.invokeOnCompletion { cause ->
            synchronized(stateLock) {
                if (worker === next) worker = null
                if (epoch == generation && cause is CancellationException) {
                    desiredPeer = null
                    mutableState.value = ConnectionState()
                }
            }
        }
        next.start()
        Unit
    }

    override suspend fun disconnect() = lifecycle.withLock { stopCurrent() }

    override fun close() {
        val previous = synchronized(stateLock) {
            if (closed) return
            closed = true
            ++generation
            val old = Stopped(worker, active?.transport, active?.channels, active)
            worker = null
            active = null
            desiredPeer = null
            mutableState.value = ConnectionState()
            old
        }
        // Close before cancelling/joining: Bluetooth streams may be blocked in a native read.
        previous.transport.closeQuietly()
        closeChannelManagers(previous.session)
        previous.session?.let(::endRequests)
        messages.close()
        previous.job?.cancel()
    }

    override suspend fun sendMessage(path: String, payload: ByteArray): TransmissionReceipt {
        require(path.isNotBlank() && path.toByteArray(Charsets.UTF_8).size <= config.maxPathBytes) {
            "Message path exceeds the client limit or is blank"
        }
        require(payload.size <= config.maxBusinessPayloadBytes) { "Business payload exceeds the client limit" }
        // Copy before the first suspension, including time waiting behind another writer.
        val body = payload.copyOf()
        val session = synchronized(stateLock) {
            if (closed) throw BackendClosedException()
            active?.takeIf { mutableState.value.phase == ConnectionPhase.LINK_READY }
                ?: throw NotConnectedException()
        }
        return checkNotNull(sendRpc(session, RpcKind.MESSAGE, path, body))
    }

    /** Domain work keeps its physical-session identity across suspensions and queued writes. */
    suspend fun sendMessage(peer: PeerAddress, sessionId: Long, path: String, payload: ByteArray,
        route: ApplicationRoute = this.route): TransmissionReceipt {
        checkRpcBudget(path, payload)
        val body = payload.copyOf()
        val session = synchronized(stateLock) {
            if (closed) throw BackendClosedException()
            active?.takeIf { it.peer == peer && it.sessionId == sessionId && mutableState.value.phase == ConnectionPhase.LINK_READY }
                ?: throw NotConnectedException()
        }
        return checkNotNull(sendRpc(session, RpcKind.MESSAGE, path, body, applicationRoute = route))
    }

    override suspend fun openChannel(path: String, allowOverMetered: Boolean): WearChannel =
        currentChannelSession().open(path, allowOverMetered)

    suspend fun openChannel(peer: PeerAddress, sessionId: Long, path: String, allowOverMetered: Boolean = true,
        route: ApplicationRoute = this.route): WearChannel {
        val channels = synchronized(stateLock) {
            if (closed) throw BackendClosedException()
            val current = active?.takeIf { it.peer == peer && it.sessionId == sessionId && mutableState.value.phase == ConnectionPhase.LINK_READY }
                ?: throw NotConnectedException()
            if (route == this.route) current.channels ?: throw NotConnectedException()
            else current.additionalChannels.getOrPut(route) {
                check(current.additionalChannels.size < 8) { "Channel application-route budget exceeded" }
                ChannelMultiplexer(scope = checkNotNull(current.channelScope), peer = peer, sessionId = sessionId,
                    route = route, config = channelConfig, random = random, diagnostics = diagnostics,
                    send = { request, guard -> sendRpc(current, RpcKind.CHANNEL, "", channel = request,
                        stillNeeded = guard, applicationRoute = route) })
            }
        }
        return channels.open(path, allowOverMetered)
    }

    override suspend fun acceptChannel(): WearChannel = currentChannelSession().accept()

    /** Source-backed sendRequest correlation; response bytes are independent from local writes. */
    suspend fun request(
        peer: PeerAddress,
        sessionId: Long,
        path: String,
        payload: ByteArray,
        timeoutMillis: Long = 60_000,
        route: ApplicationRoute = this.route,
    ): ByteArray {
        require(timeoutMillis > 0)
        checkRpcBudget(path, payload)
        val session = synchronized(stateLock) {
            if (closed) throw BackendClosedException()
            active?.takeIf { it.peer == peer && it.sessionId == sessionId && mutableState.value.phase == ConnectionPhase.LINK_READY }
                ?: throw NotConnectedException()
        }
        val pending = PendingRpc(CompletableDeferred(), path, route)
        var token: Int? = null
        try {
            return clientTimeout(timeoutMillis, "RPC response") {
                sendRpc(session, RpcKind.SERVICE, path, payload.copyOf(), expectsResponse = true, generation = 0, applicationRoute = route,
                    prepared = { requestId ->
                        val selected = responseToken(0, requestId)
                        synchronized(stateLock) {
                            ensureSession(session, requireReady = true)
                            check(session.pendingRpcs.size < MAX_PENDING_RPCS) { "Pending RPC budget exceeded" }
                            check(selected !in session.pendingRpcs) { "RPC correlation token is still in use" }
                            token = selected
                            session.pendingRpcs[selected] = pending
                        }
                    })
                pending.result.await().copyOf()
            }
        } finally {
            synchronized(stateLock) { token?.let { if (session.pendingRpcs[it] === pending) session.pendingRpcs.remove(it) } }
            pending.result.cancel()
        }
    }

    /** One handler per path. Closing an older registration cannot remove a replacement. */
    fun registerRequestHandler(path: String, route: ApplicationRoute = this.route, handler: suspend (SessionRpcRequest) -> ByteArray): AutoCloseable {
        require(path.toByteArray(Charsets.UTF_8).size <= config.maxPathBytes)
        val key = RoutePath(route, path)
        synchronized(stateLock) {
            if (closed) throw BackendClosedException()
            check(key !in requestHandlers) { "A request handler is already registered for this route and path" }
            requestHandlers[key] = handler
        }
        return AutoCloseable { synchronized(stateLock) { if (requestHandlers[key] === handler) requestHandlers.remove(key) } }
    }

    /** Explicit alternate MessageApi paths, e.g. the independently routed GMS MDM protocol. */
    override fun registerMessageHandler(name: String, paths: Set<String>, route: ApplicationRoute?,
        handler: suspend (BackendEvent.MessageReceived) -> Unit): AutoCloseable {
        paths.forEach { checkRpcBudget(it, byteArrayOf()) }
        synchronized(stateLock) { if (closed) throw BackendClosedException() }
        return messages.register(name, paths, route ?: this.route, handler)
    }

    /** Explicit observation-only alternate routes. Business handlers register their routes themselves. */
    fun registerMessageRoutes(route: ApplicationRoute, paths: Set<String>): AutoCloseable {
        paths.forEach { checkRpcBudget(it, byteArrayOf()) }
        val keys = paths.map { RoutePath(route, it) }
        synchronized(stateLock) {
            if (closed) throw BackendClosedException()
            keys.forEach { key -> observedMessageRoutes[key] = (observedMessageRoutes[key] ?: 0) + 1 }
        }
        val released = java.util.concurrent.atomic.AtomicBoolean()
        return AutoCloseable {
            if (released.compareAndSet(false, true)) synchronized(stateLock) {
                keys.forEach { key ->
                    val count = observedMessageRoutes[key] ?: 0
                    if (count <= 1) observedMessageRoutes.remove(key) else observedMessageRoutes[key] = count - 1
                }
            }
        }
    }

    private fun checkRpcBudget(path: String, payload: ByteArray) {
        require(path.toByteArray(Charsets.UTF_8).size <= config.maxPathBytes && payload.size <= config.maxBusinessPayloadBytes)
    }

    private fun currentChannelSession(): ChannelMultiplexer = synchronized(stateLock) {
        if (closed) throw BackendClosedException()
        active?.takeIf { mutableState.value.phase == ConnectionPhase.LINK_READY }?.channels
            ?: throw NotConnectedException()
    }

    private suspend fun sendRpc(
        session: ActiveSession,
        kind: RpcKind,
        path: String,
        payload: ByteArray = byteArrayOf(),
        channel: ChannelRequest? = null,
        stillNeeded: () -> Boolean = { true },
        expectsResponse: Boolean? = null,
        responseCorrelationToken: Int? = null,
        generation: Int? = if (kind == RpcKind.CHANNEL) 0 else null,
        applicationRoute: ApplicationRoute = route,
        prepared: (Int) -> Unit = {},
    ): TransmissionReceipt? {
        var writing = false
        var outcome = "waiting_for_writer"
        var allocatedRequestId: Int? = null
        var problem: Exception? = null
        return try {
            session.writeMutex.withLock {
                ensureSession(session, requireReady = true)
                // Recheck after waiting for the physical writer: a queued old Channel emission
                // may have become obsolete while a different Message/RPC held this mutex.
                if (!stillNeeded()) { outcome = "obsolete_guard_skipped"; return@withLock null }
                val requestId = session.nextRequestId()
                allocatedRequestId = requestId
                prepared(requestId)
                val envelope = WearMessages.encodeRpc(RpcMessage(
                    requestId = requestId,
                    packageName = applicationRoute.packageName,
                    packageDigest = applicationRoute.packageDigest,
                    targetNodeId = checkNotNull(session.peerConnect).nodeId,
                    path = path,
                    payload = payload,
                    sourceNodeId = session.identity.nodeId,
                    kind = kind,
                    channel = channel,
                    generation = generation,
                    expectsResponse = expectsResponse,
                    responseCorrelationToken = responseCorrelationToken,
                    payloadPresent = kind != RpcKind.CHANNEL,
                ))
                val records = encodeRecords(session, envelope)
                writing = true
                outcome = "wire_batch_started"
                clientTimeout(config.writeTimeoutMillis, "RPC write") {
                    for (record in records) session.transport.write(record)
                }
                ensureSession(session, requireReady = true)
                outcome = "wire_batch_written"
                TransmissionReceipt(requestId, records.sumOf { it.size })
            }
        } catch (failure: Exception) {
            problem = failure
            // Cancellation/failure after a partial record invalidates the physical byte stream.
            // Channel operation cancellation never cancels its independent writer mid-record.
            if (writing) session.transport.closeQuietly()
            throw failure
        } finally {
            recordDiagnostic(ProtocolDiagnostic("session", "rpc_write", if (problem == null) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING, session.peer, session.sessionId, channel?.control?.channelId ?: channel?.data?.header?.channelId ?: channel?.ack?.header?.channelId, channel?.control?.fromChannelOpener ?: channel?.data?.header?.fromChannelOpener ?: channel?.ack?.header?.fromChannelOpener, mapOf("kind" to kind.name, "requestId" to (allocatedRequestId?.toString() ?: "unallocated"), "path" to path.take(1_024), "payloadBytes" to payload.size.toString(), "controlType" to (channel?.control?.type?.toString() ?: "absent"), "sequence" to (channel?.data?.header?.sequence?.toString() ?: channel?.ack?.header?.sequence?.toString() ?: "absent"), "decision" to outcome, "exception" to (problem?.javaClass?.simpleName ?: "none"), "message" to (problem?.message?.take(1_024) ?: "none"))))
        }
    }

    private suspend fun stopCurrent() {
        val previous = synchronized(stateLock) {
            ++generation
            val old = Stopped(worker, active?.transport, active?.channels, active)
            worker = null
            active = null
            desiredPeer = null
            mutableState.value = ConnectionState()
            old
        }
        previous.transport.closeQuietly()
        closeChannelManagers(previous.session)
        previous.session?.let(::endRequests)
        if (previous.job != currentCoroutineContext()[Job]) {
            // A cancelled UI caller must not interrupt cleanup and leave an old socket behind.
            withContext(NonCancellable) { previous.job?.cancelAndJoin() }
        } else {
            previous.job?.cancel()
        }
    }

    private suspend fun sendEnvelope(session: ActiveSession, input: ByteArray): TransmissionReceipt {
        require(input.size <= dev.ticbox.companion.protocol.ProtocolLimits.MAX_MESSAGE_BYTES)
        val bytes = input.copyOf()
        var writing = false
        try {
            return session.writeMutex.withLock {
                ensureSession(session, requireReady = true)
                val records = encodeRecords(session, bytes)
                writing = true
                clientTimeout(config.writeTimeoutMillis, "Wear envelope write") {
                    for (record in records) session.transport.write(record)
                }
                ensureSession(session, requireReady = true)
                TransmissionReceipt(0, records.sumOf { it.size })
            }
        } catch (problem: Exception) {
            if (writing) session.transport.closeQuietly()
            throw problem
        }
    }

    private fun notifyExtensions(session: ActiveSession, action: String, block: (SessionExtension, EstablishedSession) -> Unit) {
        val context = session.context ?: return
        synchronized(session.extensionsLock) {
            if (session.extensionsEnded) return
            if (action == "closed") session.extensionsEnded = true
            for (extension in extensions) {
                if (session.extensionsEnded && action != "closed") return
                try { block(extension, context) }
                catch (problem: Exception) { extensionFailure(session, action, extension, problem) }
            }
        }
    }

    private suspend fun notifyEnvelope(session: ActiveSession, message: ByteArray) {
        val context = synchronized(session.extensionsLock) {
            if (session.extensionsEnded) return
            session.context
        } ?: return
        for (extension in extensions) {
            if (synchronized(session.extensionsLock) { session.extensionsEnded }) return
            // Never hold the lifecycle lock across a suspended queue send: disconnect must
            // still cancel that queue and release the reader without waiting for its worker.
            try { extension.onEnvelope(context, message.copyOf()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Exception) { extensionFailure(session, "envelope", extension, problem) }
        }
    }

    private fun extensionFailure(session: ActiveSession, action: String, extension: SessionExtension, problem: Exception) {
        recordDiagnostic(ProtocolDiagnostic("session", "extension_failure", peer = session.peer, sessionId = session.sessionId,
            details = mapOf("callback" to action, "extension" to extension.javaClass.simpleName, "exception" to problem.javaClass.simpleName,
                "decision" to "isolate extension failure")))
    }

    private fun endRequests(session: ActiveSession) {
        messages.end(session.sessionId)
        val pending = synchronized(stateLock) {
            val old = session.pendingRpcs.values.toList()
            session.pendingRpcs.clear()
            session.requestReplies.clear()
            session.replyBytes = 0
            old
        }
        for (entry in pending) entry.result.completeExceptionally(NotConnectedException())
        session.rpcLifetime?.cancel()
        notifyExtensions(session, "closed") { extension, context -> extension.onClosed(context) }
    }

    private fun deliverResponse(session: ActiveSession, request: RpcMessage): Boolean {
        val token = request.responseCorrelationToken ?: return false
        val pending = synchronized(stateLock) {
            session.pendingRpcs[token]?.takeIf { it.route.packageName == request.packageName && it.route.packageDigest == request.packageDigest }
                ?.also { session.pendingRpcs.remove(token) }
        }
        if (pending == null) recordDiagnostic(ProtocolDiagnostic("rpc", "unknown_response", peer = session.peer, sessionId = session.sessionId,
            details = mapOf("token" to token.toString(), "requestId" to request.requestId.toString(), "decision" to "ignore late or unregistered response")))
        else pending.result.complete(request.payload.copyOf())
        return true
    }

    private fun dispatchRequest(session: ActiveSession, request: RpcMessage, source: String) {
        val applicationRoute = ApplicationRoute(request.packageName, request.packageDigest)
        val key = RequestKey(source, applicationRoute, request.generation ?: 0, request.requestId)
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(request.payload)
        var cached: ByteArray? = null
        var duplicate = false
        var conflict = false
        val handler = synchronized(stateLock) {
            val previous = session.requestReplies[key]
            if (previous != null) {
                duplicate = true
                conflict = previous.path != request.path || !previous.fingerprint.contentEquals(fingerprint)
                if (!conflict) cached = previous.response?.copyOf()
                null
            } else {
                val selected = requestHandlers[RoutePath(applicationRoute, request.path)]
                if (selected != null && session.incomingRequests < MAX_INBOUND_REQUESTS) {
                    session.incomingRequests++
                    session.requestReplies[key] = ReplyState(request.path, fingerprint)
                    selected
                } else null
            }
        }
        if (duplicate) {
            recordDiagnostic(ProtocolDiagnostic("rpc", if (conflict) "conflicting_request" else "duplicate_request", peer = session.peer, sessionId = session.sessionId,
                details = mapOf("path" to request.path, "requestId" to request.requestId.toString(), "decision" to if (conflict) "ignore ambiguous reused request identity" else if (cached == null) "retain running handler" else "replay cached response")))
            cached?.let { reply -> session.rpcScope?.launch { runCatching { replyTo(session, request, reply) } } }
            return
        }
        if (handler == null) {
            recordDiagnostic(ProtocolDiagnostic("rpc", "request_not_handled", peer = session.peer, sessionId = session.sessionId,
                details = mapOf("path" to request.path, "requestId" to request.requestId.toString(), "decision" to "no registered or available handler; do not fabricate response")))
            return
        }
        session.rpcScope?.launch {
            try {
                val reply = clientTimeout(60_000, "RPC handler") {
                    handler(SessionRpcRequest(session.peer, session.sessionId, request.path, source, request.payload.copyOf(), applicationRoute))
                }
                require(reply.size <= config.maxBusinessPayloadBytes) { "RPC response exceeds local budget" }
                ensureSession(session, requireReady = true)
                synchronized(stateLock) {
                    val entry = session.requestReplies[key]
                    if (entry != null && entry.response == null) {
                        entry.response = reply.copyOf()
                        session.replyBytes += reply.size
                        while (session.replyBytes > MAX_REPLY_CACHE_BYTES || session.requestReplies.size > MAX_REPLY_CACHE_ENTRIES) {
                            val victim = session.requestReplies.entries.firstOrNull { it.value.response != null } ?: break
                            session.replyBytes -= victim.value.response!!.size
                            session.requestReplies.remove(victim.key)
                        }
                    }
                }
                replyTo(session, request, reply)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Exception) {
                synchronized(stateLock) { if (session.requestReplies[key]?.response == null) session.requestReplies.remove(key) }
                recordDiagnostic(ProtocolDiagnostic("rpc", "request_handler_failed", peer = session.peer, sessionId = session.sessionId,
                    details = mapOf("path" to request.path, "requestId" to request.requestId.toString(), "exception" to problem.javaClass.simpleName, "decision" to "retain session; no synthetic success response")))
            } finally { synchronized(stateLock) { session.incomingRequests-- } }
        }
    }

    private suspend fun replyTo(session: ActiveSession, request: RpcMessage, reply: ByteArray) {
        sendRpc(session, RpcKind.SERVICE, request.path, reply,
            responseCorrelationToken = responseToken(request.generation ?: 0, request.requestId), generation = 0,
            applicationRoute = ApplicationRoute(request.packageName, request.packageDigest))
    }

    private suspend fun reconnectLoop(epoch: Long, peer: PeerAddress) {
        val identity = try {
            identities.loadOrCreate()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            publish(epoch, ConnectionState(
                phase = ConnectionPhase.FAILED,
                peer = peer,
                failure = ConnectionFailure(FailureKind.IDENTITY, "Unable to load the persistent local identity"),
            ))
            return
        }
        var retryAttempt = 0
        var migrating = false
        while (isCurrent(epoch)) {
            currentCoroutineContext().ensureActive()
            val sessionId = beginAttempt(epoch, peer, retryAttempt) ?: return
            val readiness = AttemptReadiness(nextMigrationMode = migrating)
            try {
                runAttempt(epoch, sessionId, peer, identity, readiness)
                throw EOFException("Transport ended")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!isCurrent(epoch)) return
                migrating = readiness.nextMigrationMode
                if (readiness.readyAtMillis?.let { monotonicMillis() - it >= config.reconnectResetAfterMillis } == true) retryAttempt = 0
                retryAttempt = if (retryAttempt == Int.MAX_VALUE) Int.MAX_VALUE else retryAttempt + 1
                val description = describe(failure)
                recordDiagnostic(ProtocolDiagnostic("session", "connection_interrupted",
                    if (failure is ConnectModeChangedException) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                    peer, sessionId, details = mapOf("kind" to description.kind.name, "message" to description.message,
                        "exception" to failure.javaClass.simpleName, "cause" to (failure.cause?.javaClass?.simpleName ?: "none"),
                        "nextMigrationMode" to migrating.toString(), "retryAttempt" to retryAttempt.toString())))
                val retry = isTransient(failure) &&
                    (config.maxReconnectAttempts == null || retryAttempt <= config.maxReconnectAttempts)
                val retryDelay = if (retry) backoffMillis(retryAttempt) else null
                val droppedMessages = synchronized(stateLock) {
                    mutableState.value.takeIf { it.sessionId == sessionId }?.droppedMessageEvents ?: 0
                }
                val published = publish(epoch, ConnectionState(
                    phase = if (retry) ConnectionPhase.RETRYING else ConnectionPhase.FAILED,
                    peer = peer,
                    sessionId = sessionId,
                    retryAttempt = retryAttempt,
                    retryDelayMillis = retryDelay,
                    failure = description,
                    droppedMessageEvents = droppedMessages,
                ))
                if (!published) return
                // Diagnostics cannot stall reconnection behind a slow observer. StateFlow carries
                // the latest failure even if this bounded live event stream has no room.
                mutableEvents.tryEmit(BackendEvent.ConnectionInterrupted(peer, sessionId, description, retry))
                if (!retry) return
                delay(checkNotNull(retryDelay))
            }
        }
    }

    private suspend fun runAttempt(
        epoch: Long,
        sessionId: Long,
        peer: PeerAddress,
        identity: NodeIdentity,
        readiness: AttemptReadiness,
    ) {
        var acquired: DuplexTransport? = null
        var session: ActiveSession? = null
        val decoder = GmsRecordDecoder()
        val assembler = MessageAssembler()
        var stage = "opening_rfcomm"
        var incomingChunks = 0L
        var incomingBytes = 0L
        var incomingMessages = 0L
        var outgoingBytes = 0L
        fun trace(event: String, problem: Exception? = null) {
            recordDiagnostic(ProtocolDiagnostic("session", event,
                if (problem != null && problem !is CancellationException) DiagnosticSeverity.WARNING else DiagnosticSeverity.INFO,
                peer, sessionId, details = buildMap {
                    put("stage", stage); put("incomingChunks", incomingChunks.toString())
                    put("incomingBytes", incomingBytes.toString()); put("decodedMessages", incomingMessages.toString())
                    put("outgoingBytes", outgoingBytes.toString())
                    problem?.let { put("exception", it.javaClass.simpleName) }
                }))
        }
        try {
            // Assignment happens inside the timeout, so finally also owns a transport acquired
            // just as the timeout expires. Acquisition's earlier race is the factory's contract.
            trace("transport_connect_started")
            clientTimeout(config.connectTimeoutMillis, "Transport connection") { acquired = transports.open(peer) }
            stage = "sending_connect"
            trace("rfcomm_opened")
            val current = ActiveSession(epoch, sessionId, peer, checkNotNull(acquired), identity)
            current.rpcLifetime = SupervisorJob(currentCoroutineContext()[Job])
            current.rpcScope = CoroutineScope(currentCoroutineContext() + checkNotNull(current.rpcLifetime) + rpcDispatcher)
            session = current
            val installed = synchronized(stateLock) {
                if (!closed && epoch == generation) {
                    active = current
                    mutableState.value = ConnectionState(ConnectionPhase.NEGOTIATING, peer, sessionId)
                    true
                } else false
            }
            if (!installed) throw CancellationException("Session replaced")

            val buffered = ArrayList<ByteArray>()
            var bufferedBytes = 0
            fun buffer(message: ByteArray, enforceLimit: Boolean = true) {
                if (enforceLimit && (buffered.size >= config.maxPreConnectMessages ||
                    message.size > config.maxPreConnectBytes - bufferedBytes)
                ) throw ProtocolSessionException("Session negotiation buffering exceeds the client limit")
                bufferedBytes += message.size
                buffered.add(message)
            }
            clientTimeout(config.handshakeTimeoutMillis, "CONNECT negotiation") {
                val hello = WearMessages.encodeConnect(ConnectMessage(
                    nodeId = identity.nodeId,
                    nodeName = identity.nodeName,
                    androidId = identity.androidId,
                    networkId = identity.networkId,
                    companionPackage = route.packageName,
                    senderSdkVersion = senderSdkVersion,
                    isMigrating = readiness.nextMigrationMode.takeIf { it },
                ))
                current.writeMutex.withLock {
                    for (record in encodeRecords(current, hello)) {
                        current.transport.write(record)
                        outgoingBytes += record.size
                    }
                }
                stage = "waiting_peer_connect"
                trace("connect_sent")
                while (current.peerConnect == null) {
                    val chunk = current.transport.read() ?: endOfStream(decoder, assembler)
                    incomingChunks++; incomingBytes += chunk.size
                    val decoded = decodeChunk(decoder, assembler, chunk).map { message ->
                        message to decodeEnvelope(current, message)
                    }
                    // A terminal control in this read must survive even when earlier pending
                    // business envelopes would exceed the handoff budget.
                    for ((_, envelope) in decoded) envelope?.let(::checkSessionControl)
                    for ((message, envelope) in decoded) {
                        incomingMessages++
                        val awaitingConnect = current.peerConnect == null
                        val connect = envelope?.let(WearMessages::connectOrNull)
                        if (connect != null) {
                            validateConnect(connect, identity, current.peerConnect)
                            current.peerConnect = connect
                            stage = "peer_connect_received"
                            trace("peer_connect_received")
                        }
                        // Defer every business handler until the CONNECT timeout scope has ended
                        // and the peer version check has succeeded, preserving stream order.
                        buffer(message, enforceLimit = awaitingConnect || current.peerConnect?.isMigrating == true)
                    }
                    // A few metadata-only observations are enough to distinguish silence, partial
                    // framing and decoded non-CONNECT input without logging any peer payload.
                    if (incomingChunks <= 4) trace("handshake_progress")
                }
            }
            ensureSession(current, requireReady = false)
            val connect = checkNotNull(current.peerConnect)
            val peerMigrating = connect.isMigrating == true
            if (peerMigrating != readiness.nextMigrationMode) {
                readiness.nextMigrationMode = peerMigrating
                recordDiagnostic(ProtocolDiagnostic("migration", "connect_mode_observed", peer = peer, sessionId = sessionId,
                    details = mapOf("isMigrating" to peerMigrating.toString(), "decision" to "negotiate on a fresh physical connection")))
                throw ConnectModeChangedException("Peer CONNECT requires migration=$peerMigrating")
            }
            if (peerMigrating) {
                stage = "migration_accounts"
                recordDiagnostic(ProtocolDiagnostic("migration", "negotiation_started", peer = peer, sessionId = sessionId,
                    details = mapOf("previousNodePresent" to (connect.previousNodeId != null).toString(),
                        "previousNodeMatchesLocal" to (connect.previousNodeId == identity.nodeId).toString())))
                var initialBatch = true
                var dataDeferred = false
                clientTimeout(config.migrationTimeoutMillis, "Watch transfer negotiation") {
                    AccountlessMigration(
                        queryIntervalMillis = config.migrationQueryIntervalMillis,
                        send = { envelope ->
                            clientTimeout(config.writeTimeoutMillis, "Migration control write") {
                                current.writeMutex.withLock {
                                    ensureSession(current, requireReady = false)
                                    for (record in encodeRecords(current, envelope)) {
                                        current.transport.write(record)
                                        outgoingBytes += record.size
                                    }
                                }
                            }
                        },
                        receive = {
                            val retainBatch = !initialBatch
                            val messages = if (initialBatch) {
                                initialBatch = false
                                buffered.toList()
                            } else {
                                val chunk = current.transport.read() ?: endOfStream(decoder, assembler)
                                incomingChunks++; incomingBytes += chunk.size
                                decodeChunk(decoder, assembler, chunk).also { incomingMessages += it.size }
                            }
                            val envelopes = messages.mapNotNull { message -> decodeEnvelope(current, message)?.also { envelope ->
                                checkSessionControl(envelope)
                                WearMessages.connectOrNull(envelope)?.let { validateConnect(it, identity, connect) }
                                if (!dataDeferred && DataProtocol.hasData(message)) {
                                    dataDeferred = true
                                    recordDiagnostic(ProtocolDiagnostic("migration", "data_buffered", peer = peer, sessionId = sessionId,
                                        details = mapOf("decision" to "retain stream order until accountless confirmation")))
                                }
                            } }
                            if (retainBatch) for (message in messages) buffer(message)
                            envelopes
                        },
                        diagnostic = { event, details -> recordDiagnostic(ProtocolDiagnostic("migration", event,
                            peer = peer, sessionId = sessionId, details = details)) },
                    ).awaitConfirmation()
                }
                // Stock peers resume their existing data writers after account matching. Keep
                // this transport, decoder, assembler and writer; only a real later disconnect
                // starts an ordinary CONNECT. The buffered confirmation batch has been fully
                // checked for cancellation or termination before any business callback starts.
                readiness.nextMigrationMode = false
            }
            current.channelScope = CoroutineScope(currentCoroutineContext())
            current.channels = ChannelMultiplexer(
                scope = checkNotNull(current.channelScope),
                peer = peer,
                sessionId = sessionId,
                route = route,
                config = channelConfig,
                random = random,
                diagnostics = diagnostics,
                send = { request, guard -> sendRpc(current, RpcKind.CHANNEL, "", channel = request, stillNeeded = guard) },
            )
            val negotiatedWireVersion = min(LOCAL_WIRE_VERSION, connect.wireVersion)
            current.context = EstablishedSession(peer, sessionId, identity, connect, route, negotiatedWireVersion,
                send = { sendEnvelope(current, it) },
                reconnect = { reason ->
                    val selected = synchronized(stateLock) { active === current && !closed && current.epoch == generation }
                    if (selected) {
                        recordDiagnostic(ProtocolDiagnostic("session", "resync_reconnect_requested", peer = peer, sessionId = sessionId,
                            details = mapOf("reason" to reason, "decision" to "close this physical stream; reconnect from durable checkpoints")))
                        current.transport.closeQuietly()
                    }
                })
            val ready = publish(epoch, ConnectionState(
                phase = ConnectionPhase.LINK_READY,
                peer = peer,
                sessionId = sessionId,
                peerName = connect.nodeName,
                negotiatedWireVersion = negotiatedWireVersion,
            ))
            if (!ready) throw CancellationException("Session replaced")
            recordDiagnostic(ProtocolDiagnostic("session", "link_ready", DiagnosticSeverity.INFO, peer, sessionId, details = mapOf("peerNode" to connect.nodeId, "wireVersion" to negotiatedWireVersion.toString())))
            readiness.wasReady = true
            readiness.readyAtMillis = monotonicMillis()
            stage = "ready"
            notifyExtensions(current, "ready") { extension, context -> extension.onReady(context) }
            messages.begin(checkNotNull(current.channelScope), peer, sessionId)
            for (message in buffered) handleInbound(current, message)
            buffered.clear()
            while (true) {
                ensureSession(current, requireReady = true)
                val chunk = current.transport.read() ?: endOfStream(decoder, assembler)
                val decoded = withContext(parseContext) {
                    decodeChunk(decoder, assembler, chunk).map { message -> message to decodeEnvelope(current, message) }
                }
                for ((message, envelope) in decoded) handleInbound(current, message, envelope)
            }
        } catch (problem: Exception) {
            if (!readiness.wasReady) trace("handshake_incomplete", problem)
            throw problem
        } finally {
            closeChannelManagers(session)
            session?.let(::endRequests)
            synchronized(stateLock) {
                if (active === session) active = null
            }
            assembler.reset()
            acquired.closeQuietly()
        }
    }

    private suspend fun handleInbound(session: ActiveSession, message: ByteArray) =
        handleInbound(session, message, decodeEnvelope(session, message))

    private suspend fun handleInbound(session: ActiveSession, message: ByteArray, decoded: DecodedStruct?) {
        ensureSession(session, requireReady = true)
        val envelope = decoded ?: run {
            // A malformed DataApi child still needs its recovery handler. Silently skipping it
            // could allow a later source cursor to advance past an uncommitted DataItem.
            if (runCatching { DataProtocol.hasData(message) }.getOrDefault(false)) {
                notifyEnvelope(session, message)
            }
            return
        }
        WearMessages.connectOrNull(envelope)?.let { connect ->
            validateConnect(connect, session.identity, session.peerConnect)
        }
        checkSessionControl(envelope)
        notifyEnvelope(session, message)
        val requests = WearMessages.decodeRpcs(envelope)
        for (request in requests) {
            // AppKey selects a wire dispatch route; it does not grant CDM or Android privileges.
            val applicationRoute = runCatching { ApplicationRoute(request.packageName, request.packageDigest) }.getOrNull()
            if (applicationRoute == null) {
                recordDiagnostic(ProtocolDiagnostic("session", "rpc_route_unassigned", peer = session.peer, sessionId = session.sessionId,
                    details = mapOf("packageCharacters" to request.packageName.length.toString(), "digestCharacters" to request.packageDigest.length.toString(),
                        "decision" to "no local route matches these fields; ignore this RPC and retain the session")))
                continue
            }
            val acceptedRoute = applicationRoute == route || (request.kind == RpcKind.CHANNEL && synchronized(stateLock) {
                applicationRoute in session.additionalChannels
            }) || (request.kind != RpcKind.CHANNEL && synchronized(stateLock) {
                RoutePath(applicationRoute, request.path) in requestHandlers ||
                    (request.kind == RpcKind.MESSAGE && (RoutePath(applicationRoute, request.path) in observedMessageRoutes ||
                        messages.accepts(applicationRoute, request.path))) ||
                    request.responseCorrelationToken?.let { session.pendingRpcs[it]?.route == applicationRoute } == true
            })
            if (!acceptedRoute || request.targetNodeId != session.identity.nodeId) {
                recordDiagnostic(ProtocolDiagnostic("session", "rpc_route_rejected", DiagnosticSeverity.WARNING, session.peer, session.sessionId, details = mapOf("kind" to request.kind.name, "observedPackage" to request.packageName.take(256), "expectedPackage" to route.packageName, "digestMatches" to (request.packageDigest == route.packageDigest).toString(), "targetMatches" to (request.targetNodeId == session.identity.nodeId).toString(), "decision" to "ignore foreign RPC route")))
                continue
            }
            val source = request.sourceNodeId?.takeIf { it.isNotEmpty() } ?: checkNotNull(session.peerConnect).nodeId
            if (source != session.peerConnect?.nodeId && (request.kind != RpcKind.MESSAGE || request.expectsResponse == true || request.responseCorrelationToken != null)) {
                recordDiagnostic(ProtocolDiagnostic("session", "rpc_source_rejected", peer = session.peer, sessionId = session.sessionId,
                    details = mapOf("observedSource" to source, "expectedSource" to session.peerConnect?.nodeId.orEmpty(), "decision" to "ignore RPC from another node")))
                continue
            }
            when (request.kind) {
                RpcKind.CHANNEL -> {
                    if (request.channel == null) recordDiagnostic(ProtocolDiagnostic("session", "channel_body_missing", DiagnosticSeverity.WARNING, session.peer, session.sessionId, details = mapOf("requestId" to request.requestId.toString(), "decision" to "ignore empty Channel RPC")))
                    request.channel?.let { requestChannel ->
                        val manager = synchronized(stateLock) {
                            if (applicationRoute == route) session.channels else session.additionalChannels[applicationRoute]
                        }
                        manager?.receive(requestChannel)
                    }
                    continue
                }
                RpcKind.SERVICE -> Unit
                RpcKind.MESSAGE -> Unit
            }
            if (request.payload.size > config.maxBusinessPayloadBytes ||
                request.path.toByteArray(Charsets.UTF_8).size > config.maxPathBytes
            ) {
                recordDiagnostic(ProtocolDiagnostic("rpc", "rpc_budget_exceeded", peer = session.peer, sessionId = session.sessionId,
                    details = mapOf("pathBytes" to request.path.toByteArray(Charsets.UTF_8).size.toString(), "payloadBytes" to request.payload.size.toString(), "decision" to "ignore over-budget logical RPC; keep shared reader")))
                continue
            }
            if (deliverResponse(session, request)) continue
            if (request.expectsResponse == true) {
                dispatchRequest(session, request, source)
                continue
            }
            ensureSession(session, requireReady = true)
            val event = BackendEvent.MessageReceived(
                peer = session.peer,
                sessionId = session.sessionId,
                message = InboundMessage(
                    request.requestId, request.path, request.payload, request.sourceNodeId,
                    request.expectsResponse == true,
                    applicationRoute,
                ),
            )
            messages.offer(event)
            val emitted = mutableEvents.tryEmit(event)
            if (!emitted) {
                recordDiagnostic(ProtocolDiagnostic("session", "message_observation_dropped", DiagnosticSeverity.WARNING, session.peer, session.sessionId, details = mapOf("requestId" to request.requestId.toString(), "path" to request.path.take(1_024), "decision" to "drop new raw Message observation; keep Channel reader running")))
                synchronized(stateLock) {
                if (active === session && mutableState.value.sessionId == session.sessionId) {
                    val old = mutableState.value
                    mutableState.value = old.copy(droppedMessageEvents = if (old.droppedMessageEvents == Long.MAX_VALUE) Long.MAX_VALUE else old.droppedMessageEvents + 1)
                }
                }
            }
        }
    }

    private fun decodeEnvelope(session: ActiveSession, bytes: ByteArray): DecodedStruct? = try {
        WearMessages.decode(bytes).also { envelope ->
            for (notice in envelope.notices) recordDiagnostic(ProtocolDiagnostic("protobuf", notice.kind,
                DiagnosticSeverity.WARNING, session.peer, session.sessionId,
                details = mapOf("path" to notice.path, "field" to notice.fieldNumber.toString(), "occurrences" to notice.occurrences.toString(),
                    "decision" to "apply protobuf compatible interpretation; retain unknown fields")))
        }
    } catch (problem: Exception) {
        recordDiagnostic(ProtocolDiagnostic("protobuf", "malformed_envelope", DiagnosticSeverity.WARNING, session.peer, session.sessionId,
            details = mapOf("bytes" to bytes.size.toString(), "exception" to problem.javaClass.simpleName,
                "reason" to problem.message.orEmpty().take(256), "decision" to "discard this framed envelope and continue")))
        null
    }

    private fun recordDiagnostic(diagnostic: ProtocolDiagnostic) {
        runCatching { diagnostics.record(diagnostic) }
    }

    private fun decodeChunk(
        decoder: GmsRecordDecoder,
        assembler: MessageAssembler,
        chunk: ByteArray,
    ): List<ByteArray> = protocolDecode {
        if (chunk.isEmpty()) throw ProtocolSessionException("Transport returned an empty chunk")
        if (chunk.size > config.maxReadChunkBytes) throw ProtocolSessionException("Read chunk exceeds the client limit")
        buildList {
            for (record in decoder.push(chunk)) assembler.accept(record)?.let(::add)
        }
    }

    private fun endOfStream(decoder: GmsRecordDecoder, assembler: MessageAssembler): Nothing {
        try {
            decoder.finish()
            assembler.finish()
        } catch (failure: Exception) {
            // A link can drop in the middle of a legitimate record/piece. Discard it and retry a
            // fresh session; never emit a partial message or carry fragments across reconnects.
            throw EOFException("Transport ended during a record or fragmented message").apply { initCause(failure) }
        }
        throw EOFException("Transport reached EOF")
    }

    private fun validateConnect(connect: ConnectMessage, identity: NodeIdentity, previous: ConnectMessage?) {
        if (connect.nodeId.isBlank() || connect.nodeId == identity.nodeId) {
            throw ProtocolSessionException("Invalid peer node identity")
        }
        if (previous != null && connect.nodeId != previous.nodeId) {
            throw ProtocolSessionException("Peer changed node identity within a session")
        }
        // Missing optional versions decode to protobuf's actual zero default. Presence is not
        // inferred from ConnectMessage's outbound constructor defaults, and no extra field is
        // made mandatory here. Only a genuinely disjoint/inverted range is rejected.
        if (connect.minWireVersion < 0 || connect.wireVersion < connect.minWireVersion ||
            connect.wireVersion < LOCAL_MIN_WIRE_VERSION || connect.minWireVersion > LOCAL_WIRE_VERSION
        ) throw ProtocolSessionException("Peer wire version range is incompatible")
    }

    private fun encodeRecords(session: ActiveSession, message: ByteArray): List<ByteArray> {
        val queueId = if (message.size > config.fragmentBytes) session.nextQueueId() else -1
        return MessagePieces.encode(message, config.fragmentBytes, queueId).map { GmsRecord.encode(it) }
    }

    private fun ensureSession(session: ActiveSession, requireReady: Boolean) {
        synchronized(stateLock) {
            if (closed || active !== session || session.epoch != generation ||
                (requireReady && mutableState.value.phase != ConnectionPhase.LINK_READY)
            ) throw NotConnectedException()
        }
    }

    private fun publish(epoch: Long, update: ConnectionState): Boolean = synchronized(stateLock) {
        if (closed || generation != epoch) false else {
            mutableState.value = update
            true
        }
    }

    private fun beginAttempt(epoch: Long, peer: PeerAddress, retryAttempt: Int): Long? = synchronized(stateLock) {
        if (closed || generation != epoch) null else {
            check(sessionCounter < Long.MAX_VALUE) { "Public session ID space exhausted" }
            val sessionId = ++sessionCounter
            mutableState.value = ConnectionState(
                phase = ConnectionPhase.CONNECTING,
                peer = peer,
                sessionId = sessionId,
                retryAttempt = retryAttempt,
            )
            sessionId
        }
    }

    private fun isCurrent(epoch: Long): Boolean = synchronized(stateLock) { !closed && generation == epoch }

    private fun backoffMillis(attempt: Int): Long {
        var base = config.initialReconnectDelayMillis
        repeat(min(attempt - 1, 63)) {
            base = if (base > config.maxReconnectDelayMillis / 2) config.maxReconnectDelayMillis
            else min(config.maxReconnectDelayMillis, base * 2)
        }
        val radius = (base * config.reconnectJitterRatio).toLong()
        if (radius == 0L) return base
        val lower = (base - radius).coerceAtLeast(1)
        val upper = if (radius > config.maxReconnectDelayMillis - base) config.maxReconnectDelayMillis else base + radius
        if (lower >= upper) return lower
        return random.nextLong(lower, upper + if (upper == Long.MAX_VALUE) 0 else 1)
    }

    private suspend fun <T> clientTimeout(millis: Long, operation: String, body: suspend () -> T): T = try {
        withTimeout(millis) { body() }
    } catch (failure: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw ClientTimeoutException("$operation timed out", failure)
    }

    private fun isTransient(failure: Exception): Boolean = failure is IOException &&
        failure !is ProtocolSessionException && failure !is MigrationProtocolException &&
        failure !is AssociationTerminatedException

    private fun describe(failure: Exception): ConnectionFailure = ConnectionFailure(
        kind = when (failure) {
            is AssociationTerminatedException -> FailureKind.ASSOCIATION_TERMINATED
            is ProtocolSessionException, is MigrationProtocolException -> FailureKind.PROTOCOL
            is ClientTimeoutException -> FailureKind.TIMEOUT
            is SecurityException -> FailureKind.PERMISSION_REQUIRED
            is IOException -> FailureKind.TRANSPORT
            else -> FailureKind.UNEXPECTED
        },
        message = failure.message?.take(256) ?: "Connection failed",
    )

    private inline fun <T> protocolDecode(body: () -> T): T = try {
        body()
    } catch (failure: ProtocolSessionException) {
        throw failure
    } catch (failure: Exception) {
        throw ProtocolSessionException("Invalid inbound protocol data", failure)
    }

    private fun closeChannelManagers(session: ActiveSession?) {
        val managers = synchronized(stateLock) {
            if (session == null) emptyList() else listOfNotNull(session.channels) + session.additionalChannels.values.toList()
        }
        managers.forEach { it.close() }
    }

    private class ActiveSession(
        val epoch: Long,
        val sessionId: Long,
        val peer: PeerAddress,
        val transport: DuplexTransport,
        val identity: NodeIdentity,
    ) {
        val writeMutex = Mutex()
        var peerConnect: ConnectMessage? = null
        var channels: ChannelMultiplexer? = null
        var channelScope: CoroutineScope? = null
        val additionalChannels = linkedMapOf<ApplicationRoute, ChannelMultiplexer>()
        var context: EstablishedSession? = null
        val extensionsLock = Any()
        var extensionsEnded = false
        var rpcLifetime: Job? = null
        var rpcScope: CoroutineScope? = null
        val pendingRpcs = linkedMapOf<Int, PendingRpc>()
        val requestReplies = linkedMapOf<RequestKey, ReplyState>()
        var replyBytes = 0
        var incomingRequests = 0
        private var requestId = 0
        private var queueId = 0
        fun nextRequestId(): Int {
            requestId = if (requestId == Int.MAX_VALUE) 1 else requestId + 1
            return requestId
        }
        fun nextQueueId(): Int {
            queueId = if (queueId == Int.MAX_VALUE) 1 else queueId + 1
            return queueId
        }
    }

    private class AttemptReadiness(
        var wasReady: Boolean = false,
        var readyAtMillis: Long? = null,
        var nextMigrationMode: Boolean = false,
    )
    private data class RoutePath(val route: ApplicationRoute, val path: String)
    private data class PendingRpc(val result: CompletableDeferred<ByteArray>, val path: String, val route: ApplicationRoute)
    private data class RequestKey(val source: String, val route: ApplicationRoute, val generation: Int, val requestId: Int)
    private class ReplyState(val path: String, val fingerprint: ByteArray, var response: ByteArray? = null)
    private data class Stopped(val job: Job?, val transport: DuplexTransport?, val channels: ChannelMultiplexer?, val session: ActiveSession?)
    private class ClientTimeoutException(message: String, cause: Throwable) : IOException(message, cause)
    private class ProtocolSessionException(message: String, cause: Throwable? = null) : IOException(message, cause)
    private class ConnectModeChangedException(message: String) : IOException(message)

    private companion object {
        const val LOCAL_WIRE_VERSION = 1
        const val LOCAL_MIN_WIRE_VERSION = 0
        const val MAX_PENDING_RPCS = 16
        const val MAX_INBOUND_REQUESTS = 16
        const val MAX_REPLY_CACHE_ENTRIES = 64
        const val MAX_REPLY_CACHE_BYTES = 2 * 1024 * 1024
        fun responseToken(generation: Int, requestId: Int): Int = ((generation + 527) * 31) + requestId
        fun DuplexTransport?.closeQuietly() {
            try { this?.close() } catch (_: Exception) { /* Always continue ownership cleanup. */ }
        }
    }
}
