package dev.ticbox.companion.engine

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import java.util.ArrayDeque
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One physical session's ChannelApi (origin 0) streams. State changes use a short monitor; an
 * independent bounded writer owns RPC transmission. No app read, wire write, or callback await
 * holds the monitor or stalls dispatch of another logical channel.
 *
 * ACK is deliberately delayed until this API hands the entire DATA block to its reader (or the
 * reader closes input). This bounded direct-reader seam is more conservative than a stock pipe
 * that can buffer bytes before the application reads them. It never acknowledges merely decoded
 * or queued network bytes. Unknown origin/version requests are ignored as in the selected receiver.
 * A single last-consumed block fingerprint permits exact-value duplicate ACK recovery; this is an
 * explicit tolerance extension over stock handlers, which do not replay old ACKs.
 * Retry intervals start after each actual wire batch completes; stock schedules from sendRpc queue
 * submission. This is an explicit local scheduling choice, not an observed wall-clock equivalence.
 */
internal class ChannelMultiplexer(
    scope: CoroutineScope,
    private val peer: PeerAddress,
    private val sessionId: Long,
    private val route: ApplicationRoute,
    private val config: ChannelConfig = ChannelConfig(),
    private val random: Random = Random.Default,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val send: suspend (ChannelRequest, () -> Boolean) -> Unit,
) : AutoCloseable {
    private data class Key(val id: ULong, val localIsOpener: Boolean)
    private class Pending(
        val request: ChannelRequest,
        val intervalMillis: Long,
        val attempts: Int,
        val result: CompletableDeferred<Boolean> = CompletableDeferred(),
    ) { var retry: Job? = null }
    private data class Emission(
        val request: ChannelRequest,
        val stillNeeded: () -> Boolean,
        val sent: CompletableDeferred<Unit>? = null,
    )
    private data class Received(val bytes: ByteArray, val final: Boolean, var offset: Int = 0)
    private data class Consumed(val sequence: Long, val length: Int, val digest: ByteArray, val dataFinal: Boolean, val ack: ChannelAck)

    private val monitor = Any()
    private val lifetime = SupervisorJob(scope.coroutineContext[Job])
    private val work = CoroutineScope(scope.coroutineContext + lifetime)
    private var failure: ChannelClosedException? = null
    private val channels = LinkedHashMap<Key, LogicalChannel>()
    // Never reuse a local ID in this session: a delayed OPEN_ACK must not establish a new OPEN.
    // This set is bounded by maxLocalOpensPerSession, independently of the active-channel budget.
    private val usedLocalIds = HashSet<ULong>()
    private val incoming = ArrayDeque<LogicalChannel>()
    private val incomingChanged = Channel<Unit>(Channel.CONFLATED)
    private var acceptWaiters = 0
    private val outbound = ArrayDeque<Emission>()
    private val emissionSlots = Semaphore(config.maxPendingEmissions)
    private val outboundChanged = Channel<Unit>(Channel.CONFLATED)

    init {
        work.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                while (true) {
                    val emission = synchronized(monitor) { if (outbound.isEmpty()) null else outbound.removeFirst() }
                    if (emission == null) { outboundChanged.receive(); continue }
                    emissionSlots.release()
                    try {
                        if (emission.stillNeeded()) send(emission.request, emission.stillNeeded)
                        emission.sent?.complete(Unit)
                    } catch (cancelled: CancellationException) {
                        emission.sent?.completeExceptionally(cancelled)
                        throw cancelled
                    } catch (problem: Exception) {
                        emission.sent?.completeExceptionally(problem)
                        end(ChannelEndReason.LINK_LOST)
                        break
                    }
                }
            } finally {
                end(ChannelEndReason.LINK_LOST)
            }
        }
        lifetime.invokeOnCompletion { end(ChannelEndReason.LINK_LOST) }
    }

    suspend fun open(path: String, allowOverMetered: Boolean): WearChannel {
        require(path.toByteArray(Charsets.UTF_8).size <= config.maxPathBytes)
        currentCoroutineContext().ensureActive()
        val channel = locked { effects ->
            ensureAliveLocked()
            if (channels.size >= config.maxActiveChannels || usedLocalIds.size >= config.maxLocalOpensPerSession) {
                diagnosticLocked(effects, "local_open_limit", details = mapOf("active" to channels.size.toString(), "usedIds" to usedLocalIds.size.toString(), "decision" to "reject local OPEN without changing live channels"))
                throw ChannelClosedException(ChannelEndReason.RESOURCE_LIMIT, message = "Channel opening budget exceeded")
            }
            var id: ULong? = null
            repeat(5) {
                val candidate = (random.nextLong() and Long.MAX_VALUE).toULong()
                if (id == null && candidate !in usedLocalIds) id = candidate
            }
            val selected = id ?: throw ChannelClosedException(ChannelEndReason.RESOURCE_LIMIT, message = "Unable to allocate a fresh channel ID")
            usedLocalIds.add(selected)
            val created = LogicalChannel(ChannelInfo(peer, sessionId, selected, true, path), allowOverMetered, ChannelPhase.OPENING)
            channels[created.key] = created
            val request = control(created, ChannelCodec.OPEN)
            val pending = Pending(request, config.openRetryIntervalMillis, config.openSendAttempts)
            created.opening = pending
            diagnosticLocked(effects, "local_open_requested", DiagnosticSeverity.INFO, created, details = mapOf("allowOverMetered" to allowOverMetered.toString(), "decision" to "wait for matching OPEN_ACK"))
            startRetryLocked(created, pending, effects)
            created
        }
        try {
            checkNotNull(channel.openingResult).await()
            currentCoroutineContext().ensureActive()
            return suspendCancellableCoroutine { continuation ->
                continuation.resume(channel) { _, _, _ -> abort(channel, ChannelEndReason.CANCELLED) }
            }
        } catch (cancelled: CancellationException) {
            abort(channel, ChannelEndReason.CANCELLED)
            throw cancelled
        }
    }

    suspend fun accept(): WearChannel {
        currentCoroutineContext().ensureActive()
        locked { _ ->
            ensureAliveLocked()
            if (acceptWaiters >= config.maxActiveChannels) throw ChannelClosedException(ChannelEndReason.RESOURCE_LIMIT)
            acceptWaiters++
        }
        var selected: LogicalChannel? = null
        try {
            while (true) {
                selected = locked { effects ->
                    ensureAliveLocked()
                    var candidate: LogicalChannel? = null
                    while (incoming.isNotEmpty() && candidate == null) {
                        val next = incoming.removeFirst()
                        if (channels[next.key] === next && next.phase == ChannelPhase.OPEN) candidate = next
                    }
                    if (incoming.isNotEmpty()) effects += { incomingChanged.trySend(Unit) }
                    candidate
                }
                if (selected != null) {
                    currentCoroutineContext().ensureActive()
                    val delivered = selected
                    return suspendCancellableCoroutine { continuation ->
                        continuation.resume(delivered) { _, _, _ -> abort(delivered, ChannelEndReason.CANCELLED) }
                    }
                }
                incomingChanged.receive()
            }
        } catch (cancelled: CancellationException) {
            selected?.let { abort(it, ChannelEndReason.CANCELLED) }
            throw cancelled
        } finally {
            locked { effects ->
                acceptWaiters--
                if (incoming.isNotEmpty()) effects += { incomingChanged.trySend(Unit) }
            }
        }
    }

    /** Called after outer RPC AppKey/peer/target filtering. Never waits for an application read. */
    fun receive(request: ChannelRequest) {
        locked { effects ->
            if (failure != null) return@locked
            val control = request.control
            val data = request.data
            val ack = request.ack
            val raw = mapOf(
                "minimumVersion" to (request.minimumVersion?.toString() ?: "absent(default=0)"),
                "origin" to (request.origin?.toString() ?: "absent(default=0)"),
                "controlPresent" to (control != null).toString(), "dataPresent" to (data != null).toString(),
                "ackPresent" to (ack != null).toString(), "unknownFields" to request.unknownFields.size.toString(),
            )
            if ((request.minimumVersion ?: 0) > 1 || (request.origin ?: 0) != ChannelCodec.CHANNEL_API) {
                diagnosticLocked(effects, "unsupported_channel_domain", details = raw + ("decision" to "ignore; no ChannelApi dispatch"))
                return@locked
            }
            diagnosticLocked(effects, "channel_request_received", DiagnosticSeverity.INFO, id = control?.channelId ?: data?.header?.channelId ?: ack?.header?.channelId, details = raw)
            // These independent optional members retain the stock control > DATA > ACK priority.
            when {
                control != null -> receiveControlLocked(control, effects)
                data != null -> receiveDataLocked(data, effects)
                ack != null -> receiveAckLocked(ack, effects)
                else -> diagnosticLocked(effects, "empty_channel_request", details = raw + ("decision" to "ignore empty request"))
            }
        }
    }

    override fun close() = end(ChannelEndReason.LINK_LOST)

    private fun end(reason: ChannelEndReason) {
        locked { effects ->
            if (failure != null) return@locked
            val error = ChannelClosedException(reason)
            failure = error
            for (channel in channels.values.toList()) terminateLocked(channel, error, effects)
            incoming.clear()
            effects += { incomingChanged.close(error) }
            val discarded = outbound.toList()
            outbound.clear()
            effects += {
                for (emission in discarded) {
                    emissionSlots.release()
                    emission.sent?.completeExceptionally(error)
                }
                outboundChanged.cancel(CancellationException("Physical Channel session ended"))
            }
            effects += { lifetime.cancel() }
        }
    }

    private fun receiveControlLocked(value: ChannelControl, effects: MutableList<() -> Unit>) {
        val id = value.channelId ?: 0uL
        val remoteRole = value.fromChannelOpener ?: false
        val rawType = value.type ?: ChannelCodec.OPEN
        val dispatchType = if (rawType in ChannelCodec.OPEN..ChannelCodec.CLOSE) rawType else ChannelCodec.OPEN
        val fields = mapOf(
            "rawType" to (value.type?.toString() ?: "absent"), "dispatchType" to dispatchType.toString(),
            "idPresent" to (value.channelId != null).toString(), "senderRole" to remoteRole.toString(),
            "senderRolePresent" to (value.fromChannelOpener != null).toString(), "path" to (value.path ?: "absent"),
            "package" to (value.packageName ?: "absent"), "digestPresent" to (value.packageDigest != null).toString(),
            "applicationError" to (value.closeErrorCode?.toString() ?: "absent(default=0)"),
            "opaqueBytes" to (value.opaque?.size ?: 0).toString(), "unknownFields" to value.unknownFields.size.toString(),
        )
        diagnosticLocked(effects, "control_received", DiagnosticSeverity.INFO, id = id, localRole = if (dispatchType == ChannelCodec.OPEN) false else !remoteRole, details = fields)
        if (dispatchType != rawType || value.type == null) diagnosticLocked(
            effects, "control_default_adopted", if (value.type == null) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
            id = id, localRole = false, details = fields + ("decision" to "use stock OPEN fallback; retain route/path checks"),
        )
        when (dispatchType) {
            ChannelCodec.OPEN -> {
                if (value.packageName != route.packageName || value.packageDigest != route.packageDigest) {
                    diagnosticLocked(effects, "control_identity_mismatch", id = id, localRole = false, details = fields + ("decision" to "ignore foreign OPEN")); return
                }
                val key = Key(id, false) // Stock OPEN does not invert the sender role field.
                val path = value.path ?: ""
                if (path.toByteArray(Charsets.UTF_8).size > config.maxPathBytes) {
                    diagnosticLocked(effects, "open_path_limit", id = id, localRole = false, details = fields + ("decision" to "ignore over-budget path")); return
                }
                if (path.isBlank()) diagnosticLocked(
                    effects, "unusual_open_path", id = id, localRole = false,
                    details = fields + ("pathPresent" to (value.path != null).toString()) +
                        ("decision" to "preserve opaque path; absent uses stock empty-string default"),
                )
                val previous = channels[key]
                if (previous != null) {
                    if (previous.phase == ChannelPhase.OPEN && previous.info.path == path) {
                        diagnosticLocked(effects, "duplicate_open", DiagnosticSeverity.INFO, previous, details = fields + ("decision" to "replay OPEN_ACK without another accept"))
                        enqueueControlLocked(previous, control(previous, ChannelCodec.OPEN_ACK), effects)
                    } else {
                        diagnosticLocked(effects, "conflicting_open", DiagnosticSeverity.ERROR, previous, details = fields + ("expectedPath" to previous.info.path) + ("decision" to "close ambiguous channel tuple"))
                        protocolErrorLocked(previous, effects)
                    }
                    return
                }
                if (channels.size >= config.maxActiveChannels) {
                    diagnosticLocked(effects, "active_channel_limit", id = id, localRole = false, details = fields + ("limit" to config.maxActiveChannels.toString()) + ("decision" to "decline OPEN without allocation")); return
                }
                val channel = LogicalChannel(ChannelInfo(peer, sessionId, id, false, path), value.allowOverMetered ?: false, ChannelPhase.OPEN)
                channels[key] = channel
                enqueueControlLocked(channel, control(channel, ChannelCodec.OPEN_ACK), effects)
                if (channels[key] !== channel) return
                incoming.addLast(channel)
                diagnosticLocked(effects, "channel_opened", DiagnosticSeverity.INFO, channel, details = mapOf("decision" to "queue incoming channel after OPEN_ACK"))
                effects += { incomingChanged.trySend(Unit) }
            }
            ChannelCodec.OPEN_ACK -> {
                val channel = channels[Key(id, !remoteRole)]
                val pending = channel?.opening
                if (channel == null || pending == null || channel.phase != ChannelPhase.OPENING || pending.result.isCompleted) {
                    diagnosticLocked(effects, "unexpected_open_ack", id = id, localRole = !remoteRole, details = fields + ("decision" to "ignore late or unmatched OPEN_ACK")); return
                }
                channel.opening = null
                channel.phase = ChannelPhase.OPEN
                channel.publishLocked(effects)
                diagnosticLocked(effects, "channel_opened", DiagnosticSeverity.INFO, channel, details = mapOf("decision" to "complete matching local OPEN"))
                effects += { pending.result.complete(false); pending.retry?.cancel() }
                effects += { channel.openingResult?.complete(Unit) }
            }
            ChannelCodec.CLOSE -> {
                val channel = channels[Key(id, !remoteRole)]
                if (channel == null) {
                    diagnosticLocked(effects, "unknown_close", DiagnosticSeverity.INFO, id = id, localRole = !remoteRole, details = fields + ("decision" to "ignore closed or unknown tuple")); return
                }
                if (value.packageName != route.packageName || value.packageDigest != route.packageDigest) {
                    diagnosticLocked(effects, "control_identity_mismatch", channel = channel, details = fields + ("decision" to "ignore foreign CLOSE")); return
                }
                terminateLocked(channel, ChannelClosedException(ChannelEndReason.REMOTE_CLOSE, value.closeErrorCode ?: 0), effects)
            }
        }
    }

    private fun receiveDataLocked(data: ChannelData, effects: MutableList<() -> Unit>) {
        val header = data.header ?: ChannelHeader()
        val id = header.channelId ?: 0uL
        val localRole = !(header.fromChannelOpener ?: false)
        val channel = channels[Key(id, localRole)]
        val sequence = header.sequence ?: 0
        val bytes = data.data ?: byteArrayOf()
        val final = data.final ?: false
        val fields = headerDetails(data.header) + mapOf("bytes" to bytes.size.toString(), "final" to final.toString(), "finalPresent" to (data.final != null).toString())
        if (channel == null) {
            diagnosticLocked(effects, "unknown_data", DiagnosticSeverity.INFO, id = id, localRole = localRole, details = fields + ("decision" to "ignore closed or unknown tuple")); return
        }
        diagnosticLocked(effects, "data_received", DiagnosticSeverity.INFO, channel, details = fields)
        if (channel.phase != ChannelPhase.OPEN && channel.phase != ChannelPhase.CLOSING) {
            diagnosticLocked(effects, "data_before_established", channel = channel, details = fields + ("decision" to "wait for matching OPEN_ACK; ignore early DATA")); return
        }
        if (bytes.size > ChannelCodec.MAX_DATA_BYTES) {
            diagnosticLocked(effects, "data_size_limit", DiagnosticSeverity.ERROR, channel, details = fields + ("limit" to ChannelCodec.MAX_DATA_BYTES.toString()) + ("decision" to "close logical channel; never allocate oversized receive buffer"))
            protocolErrorLocked(channel, effects); return
        }
        if (sequence < channel.receiveSequence) {
            val cached = channel.consumed
            if (cached != null && cached.sequence == sequence && cached.length == bytes.size && cached.dataFinal == final && cached.digest.contentEquals(digest(bytes))) {
                diagnosticLocked(effects, "duplicate_data_ack_replayed", DiagnosticSeverity.INFO, channel, details = fields + ("decision" to "replay last consumed ACK; no redelivery or rx increment"))
                enqueueControlLocked(channel, request(ack = cached.ack), effects)
            } else diagnosticLocked(effects, "old_data_ignored", channel = channel, details = fields + ("decision" to "ignore stale or conflicting payload; no cached ACK match"))
            return
        }
        if (channel.receiveWireEnded) {
            diagnosticLocked(effects, "data_after_eof", channel = channel, details = fields + ("decision" to "ignore additional DATA; preserve other direction")); return
        }
        if (channel.received != null) {
            val existing = channel.received!!
            val matching = sequence == channel.receiveSequence && existing.final == final && existing.bytes.contentEquals(bytes)
            diagnosticLocked(effects, if (matching) "duplicate_pending_data" else "unexpected_pending_data", if (matching) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                channel, details = fields + ("decision" to "ignore; preserve current unconsumed block and ACK backpressure"))
            return
        }
        if (sequence != channel.receiveSequence) {
            diagnosticLocked(effects, "future_data_ignored", channel = channel, details = fields + ("decision" to "ignore; do not advance rx or allocate out-of-order storage")); return
        }
        channel.received = Received(bytes.copyOf(), final)
        if (channel.inputLocallyClosed) acknowledgeReceivedLocked(channel, effects, final = true)
        else effects += { channel.readChanged.trySend(Unit) }
    }

    private fun receiveAckLocked(ack: ChannelAck, effects: MutableList<() -> Unit>) {
        val header = ack.header ?: ChannelHeader()
        val id = header.channelId ?: 0uL
        val localRole = !(header.fromChannelOpener ?: false)
        val sequence = header.sequence ?: 0
        val channel = channels[Key(id, localRole)]
        val fields = headerDetails(ack.header) + mapOf("final" to (ack.final?.toString() ?: "absent(default=false)"))
        if (channel == null) {
            diagnosticLocked(effects, "unknown_ack", DiagnosticSeverity.INFO, id = id, localRole = localRole, details = fields + ("decision" to "ignore closed or unknown tuple")); return
        }
        val pending = channel.sending
        if (pending == null || (channel.phase != ChannelPhase.OPEN && channel.phase != ChannelPhase.CLOSING) || pending.result.isCompleted || sequence != channel.sendSequence) {
            diagnosticLocked(effects, "unmatched_ack", channel = channel,
                details = fields + mapOf("pending" to (pending != null).toString(), "decision" to "ignore; never acknowledge unmatched or future DATA")); return
        }
        val observedFinal = ack.final ?: false
        val sentFinal = pending.request.data?.final == true
        val final = observedFinal || sentFinal
        if (sentFinal && !observedFinal) diagnosticLocked(effects, "final_ack_interpreted", channel = channel,
            details = fields + mapOf("sentFinal" to "true", "decision" to "matching sequence confirms final DATA; infer output EOF"))
        if (channel.sendSequence == Long.MAX_VALUE) {
            diagnosticLocked(effects, "sequence_space_exhausted", DiagnosticSeverity.ERROR, channel, details = fields + ("decision" to "close channel rather than wrap sequence"))
            protocolErrorLocked(channel, effects); return
        }
        channel.sendSequence++
        channel.sending = null
        if (final) channel.outputEnded = true
        channel.publishLocked(effects)
        diagnosticLocked(effects, "data_acknowledged", DiagnosticSeverity.INFO, channel, details = fields + ("interpretedFinal" to final.toString()))
        effects += { pending.result.complete(final); pending.retry?.cancel() }
    }

    private fun acknowledgeReceivedLocked(channel: LogicalChannel, effects: MutableList<() -> Unit>, final: Boolean) {
        val received = channel.received ?: return
        if (channel.receiveSequence == Long.MAX_VALUE) {
            protocolErrorLocked(channel, effects); return
        }
        val ack = ChannelAck(header(channel, channel.receiveSequence), final || received.final)
        val request = request(ack = ack)
        channel.consumed = Consumed(channel.receiveSequence, received.bytes.size, digest(received.bytes), received.final, ack)
        diagnosticLocked(effects, "receive_block_consumed", DiagnosticSeverity.INFO, channel, details = mapOf("bytes" to received.bytes.size.toString(), "dataFinal" to received.final.toString(), "ackFinal" to ack.final.toString(), "inputLocallyClosed" to channel.inputLocallyClosed.toString(), "decision" to "queue ACK after delivery or input closure"))
        channel.receiveSequence++
        channel.received = null
        if (final || received.final) {
            channel.inputEnded = true
            channel.receiveWireEnded = true
        }
        channel.publishLocked(effects)
        enqueueControlLocked(channel, request, effects)
    }

    private fun startRetryLocked(channel: LogicalChannel, pending: Pending, effects: MutableList<() -> Unit>) {
        val job = work.launch(start = CoroutineStart.LAZY) {
            try {
                repeat(pending.attempts) { attempt ->
                    if (!isPending(channel, pending)) return@launch
                    locked { callbacks -> diagnosticLocked(callbacks, "channel_send_attempt", if (attempt == 0) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING, channel, details = mapOf("attempt" to (attempt + 1).toString(), "maximumAttempts" to pending.attempts.toString(), "controlType" to (pending.request.control?.type?.toString() ?: "absent"), "sequence" to (pending.request.data?.header?.sequence?.toString() ?: "absent"), "retryIntervalMillis" to pending.intervalMillis.toString(), "decision" to "queue emission; retry timer begins after wire batch")) }
                    val sent = CompletableDeferred<Unit>()
                    enqueue(Emission(pending.request, { isPending(channel, pending) }, sent))
                    sent.await()
                    if (!isPending(channel, pending)) return@launch
                    val completed = withTimeoutOrNull(pending.intervalMillis) { pending.result.await(); true } ?: false
                    if (completed) return@launch
                }
                locked { callbacks ->
                    if (isPendingLocked(channel, pending)) {
                        diagnosticLocked(callbacks, "channel_ack_timeout", DiagnosticSeverity.ERROR, channel, details = mapOf("attempts" to pending.attempts.toString(), "intervalMillis" to pending.intervalMillis.toString(), "decision" to "close channel and send best-effort CLOSE0"))
                        // Timeout sends CLOSE0 for DATA as observed. OPEN also gets a best-effort
                        // cleanup CLOSE to avoid leaving a remote orphan if its OPEN_ACK was lost.
                        terminateLocked(channel, ChannelClosedException(ChannelEndReason.TIMEOUT), callbacks)
                        callbacks += { emitClose(channel, 0) }
                    }
                }
            } catch (_: CancellationException) {
                // Cancels retry scheduling, never the independent wire writer mid-record.
            } catch (_: Exception) {
                if (isPending(channel, pending)) end(ChannelEndReason.LINK_LOST)
            }
        }
        pending.retry = job
        effects += { job.start() }
    }

    private fun isPending(channel: LogicalChannel, pending: Pending): Boolean = synchronized(monitor) { isPendingLocked(channel, pending) }
    private fun isPendingLocked(channel: LogicalChannel, pending: Pending): Boolean =
        failure == null && channels[channel.key] === channel &&
            (channel.opening === pending || channel.sending === pending) && !pending.result.isCompleted

    private suspend fun enqueue(emission: Emission) {
        emissionSlots.acquire()
        try {
            locked { effects ->
                ensureAliveLocked()
                outbound.addLast(emission)
                effects += { outboundChanged.trySend(Unit) }
            }
        } catch (failure: Throwable) {
            emissionSlots.release()
            throw failure
        }
    }

    private fun enqueueControlLocked(channel: LogicalChannel, request: ChannelRequest, effects: MutableList<() -> Unit>) {
        if (!emissionSlots.tryAcquire()) {
            diagnosticLocked(effects, "outbound_queue_limit", DiagnosticSeverity.ERROR, channel, details = mapOf("limit" to config.maxPendingEmissions.toString(), "decision" to "release over-budget logical channel"))
            terminateLocked(channel, ChannelClosedException(ChannelEndReason.RESOURCE_LIMIT), effects)
            return
        }
        outbound.addLast(Emission(request, { synchronized(monitor) { failure == null && channels[channel.key] === channel } }))
        effects += { outboundChanged.trySend(Unit) }
    }

    private fun emitClose(channel: LogicalChannel, errorCode: Int) {
        locked { effects ->
            if (failure != null || !emissionSlots.tryAcquire()) {
                diagnosticLocked(effects, "cleanup_close_not_queued", channel = channel, details = mapOf("decision" to "best-effort CLOSE skipped; transport ended or queue full")); return@locked
            }
            // A terminal CLOSE is independent of live map membership and is never retried.
            outbound.addLast(Emission(control(channel, ChannelCodec.CLOSE, errorCode), { synchronized(monitor) { failure == null && (channels[channel.key] == null || channels[channel.key] === channel) } }))
            effects += { outboundChanged.trySend(Unit) }
        }
    }

    private fun abort(channel: LogicalChannel, reason: ChannelEndReason) {
        locked { effects ->
            if (channels[channel.key] !== channel) return@locked
            terminateLocked(channel, ChannelClosedException(reason), effects)
            effects += { emitClose(channel, 0) }
        }
    }

    private fun protocolErrorLocked(channel: LogicalChannel, effects: MutableList<() -> Unit>) {
        // Selected handlers clean up invalid channel state; they do not define a special wire error.
        terminateLocked(channel, ChannelClosedException(ChannelEndReason.PROTOCOL), effects)
    }

    private fun terminateLocked(channel: LogicalChannel, error: ChannelClosedException, effects: MutableList<() -> Unit>) {
        if (channels[channel.key] !== channel) return
        channels.remove(channel.key)
        incoming.remove(channel)
        channel.terminal = error
        channel.phase = ChannelPhase.CLOSED
        channel.inputEnded = true
        channel.outputEnded = true
        channel.received = null
        channel.consumed = null
        diagnosticLocked(effects, "channel_closed", if (error.reason in setOf(ChannelEndReason.LOCAL_CLOSE, ChannelEndReason.REMOTE_CLOSE)) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING, channel, details = mapOf("reason" to error.reason.name, "applicationError" to (error.applicationErrorCode?.toString() ?: "absent"), "decision" to "release streams, pending operations and tuple"))
        val opening = channel.opening
        val sending = channel.sending
        channel.opening = null
        channel.sending = null
        channel.publishLocked(effects)
        effects += { opening?.result?.completeExceptionally(error); opening?.retry?.cancel() }
        effects += { sending?.result?.completeExceptionally(error); sending?.retry?.cancel() }
        effects += { channel.openingResult?.completeExceptionally(error) }
        effects += { channel.gracefulResult?.completeExceptionally(error) }
        effects += { channel.readChanged.close(error) }
    }

    private fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun headerDetails(value: ChannelHeader?): Map<String, String> = mapOf(
        "headerPresent" to (value != null).toString(), "idPresent" to (value?.channelId != null).toString(),
        "senderRole" to (value?.fromChannelOpener?.toString() ?: "absent(default=false)"),
        "observedSequence" to (value?.sequence?.toString() ?: "absent(default=0)"),
    )

    private fun diagnosticLocked(
        effects: MutableList<() -> Unit>, event: String, severity: DiagnosticSeverity = DiagnosticSeverity.WARNING,
        channel: LogicalChannel? = null, id: ULong? = channel?.info?.id, localRole: Boolean? = channel?.info?.localIsOpener,
        details: Map<String, String> = emptyMap(),
    ) {
        val state = if (channel == null) emptyMap() else mapOf("phase" to channel.phase.name, "rxNext" to channel.receiveSequence.toString(), "txNext" to channel.sendSequence.toString(), "path" to channel.info.path)
        val diagnostic = ProtocolDiagnostic("channel", event, severity, peer, sessionId, id, localRole,
            (state + details).mapValues { it.value.take(1_024) })
        effects += { runCatching { diagnostics.record(diagnostic) } }
    }

    private fun control(channel: LogicalChannel, type: Int, errorCode: Int = 0): ChannelRequest = request(control = ChannelControl(
        type = type,
        channelId = channel.info.id,
        fromChannelOpener = channel.info.localIsOpener,
        packageName = if (type == ChannelCodec.OPEN_ACK) null else route.packageName,
        packageDigest = if (type == ChannelCodec.OPEN_ACK) null else route.packageDigest,
        path = if (type == ChannelCodec.OPEN) channel.info.path else null,
        closeErrorCode = if (type == ChannelCodec.CLOSE) errorCode else null,
        allowOverMetered = if (type == ChannelCodec.CLOSE) null else channel.allowOverMetered,
    ))

    private fun request(control: ChannelControl? = null, data: ChannelData? = null, ack: ChannelAck? = null) =
        ChannelRequest(control, data, ack, minimumVersion = 0, origin = ChannelCodec.CHANNEL_API)
    private fun header(channel: LogicalChannel, sequence: Long) = ChannelHeader(channel.info.id, channel.info.localIsOpener, sequence)
    private fun ensureAliveLocked() { failure?.let { throw it } }

    private inline fun <T> locked(body: (MutableList<() -> Unit>) -> T): T {
        val effects = ArrayList<() -> Unit>()
        return try { synchronized(monitor) { body(effects) } } finally { for (effect in effects) effect() }
    }

    private inner class LogicalChannel(
        override val info: ChannelInfo,
        val allowOverMetered: Boolean,
        var phase: ChannelPhase,
    ) : WearChannel {
        val key = Key(info.id, info.localIsOpener)
        private val stateCell = StateCell(ChannelState(phase))
        override val state: StateFlow<ChannelState> = stateCell
        val outputMutex = Mutex()
        val inputMutex = Mutex()
        val readChanged = Channel<Unit>(Channel.CONFLATED)
        val openingResult = if (info.localIsOpener) CompletableDeferred<Unit>() else null
        var opening: Pending? = null
        var sending: Pending? = null
        var received: Received? = null
        var consumed: Consumed? = null
        var sendSequence = 0L
        var receiveSequence = 0L
        var receiveWireEnded = false
        var inputLocallyClosed = false
        var inputEnded = false
        var outputEnded = false
        var terminal: ChannelClosedException? = null
        var queuedWrites = 0
        var gracefulResult: CompletableDeferred<Unit>? = null

        fun publishLocked(effects: MutableList<() -> Unit>) {
            stateCell.publish(ChannelState(phase, inputEnded, outputEnded, terminal?.reason, terminal?.applicationErrorCode), effects)
        }
        fun ensureLiveLocked(allowClosing: Boolean = false) {
            ensureAliveLocked()
            terminal?.let { throw it }
            check(channels[key] === this)
            if (phase != ChannelPhase.OPEN && !(allowClosing && phase == ChannelPhase.CLOSING)) {
                throw ChannelClosedException(ChannelEndReason.LOCAL_CLOSE, message = "Channel does not accept this operation")
            }
        }

        override suspend fun read(maxBytes: Int): ByteArray? {
            require(maxBytes > 0)
            var consuming = false
            try {
                return inputMutex.withLock {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        var eof = false
                        val bytes = locked { effects ->
                            ensureLiveLocked(allowClosing = true)
                            if (inputEnded) { eof = true; return@locked null }
                            val current = received ?: return@locked null
                            consuming = true
                            val count = min(maxBytes, current.bytes.size - current.offset)
                            val result = if (count > 0) current.bytes.copyOfRange(current.offset, current.offset + count) else null
                            current.offset += count
                            if (current.offset == current.bytes.size) acknowledgeReceivedLocked(this, effects, final = current.final)
                            eof = inputEnded && result == null
                            result
                        }
                        if (bytes != null) {
                            currentCoroutineContext().ensureActive()
                            return@withLock suspendCancellableCoroutine { continuation ->
                                continuation.resume(bytes) { _, _, _ -> abort(this, ChannelEndReason.CANCELLED) }
                            }
                        }
                        if (eof) { currentCoroutineContext().ensureActive(); return@withLock null }
                        readChanged.receive()
                    }
                    @Suppress("UNREACHABLE_CODE") null
                }
            } catch (cancelled: CancellationException) {
                // Once a read has advanced consumption/ACK, cancellation must not silently lose
                // those bytes while leaving a usable stream. A cancelled idle reader is harmless.
                if (consuming) abort(this, ChannelEndReason.CANCELLED)
                throw cancelled
            }
        }

        private fun reserveWrite() = locked { _ ->
            ensureLiveLocked()
            if (queuedWrites >= config.maxQueuedWritesPerChannel) throw ChannelClosedException(ChannelEndReason.RESOURCE_LIMIT)
            queuedWrites++
        }

        override suspend fun write(bytes: ByteArray) {
            require(bytes.size <= config.maxWriteBytes) { "Channel write exceeds the local call budget" }
            currentCoroutineContext().ensureActive()
            reserveWrite()
            var started = false
            try {
                val copy = bytes.copyOf()
                outputMutex.withLock {
                    locked { _ -> ensureLiveLocked(); if (outputEnded) throw ChannelClosedException(ChannelEndReason.REMOTE_CLOSE, message = "Channel output has ended") }
                    var offset = 0
                    while (offset < copy.size) {
                        val count = min(config.sendChunkBytes, copy.size - offset)
                        val pending = beginData(copy.copyOfRange(offset, offset + count), final = false)
                        started = true
                        val remoteEnded = pending.result.await()
                        offset += count
                        if (remoteEnded && offset < copy.size) throw ChannelClosedException(ChannelEndReason.REMOTE_CLOSE, message = "Peer input ended before the complete write")
                    }
                }
            } catch (cancelled: CancellationException) {
                if (started) abort(this, ChannelEndReason.CANCELLED)
                throw cancelled
            } finally { locked { _ -> queuedWrites-- } }
        }

        override suspend fun finishOutput() {
            currentCoroutineContext().ensureActive()
            reserveWrite()
            var started = false
            try {
                outputMutex.withLock {
                    val ended = locked { _ -> ensureLiveLocked(); outputEnded }
                    if (!ended) {
                        val pending = beginData(byteArrayOf(), final = true)
                        started = true
                        pending.result.await()
                    }
                }
            } catch (cancelled: CancellationException) {
                if (started) abort(this, ChannelEndReason.CANCELLED)
                throw cancelled
            } finally { locked { _ -> queuedWrites-- } }
        }

        private fun beginData(bytes: ByteArray, final: Boolean): Pending = locked { effects ->
            ensureLiveLocked(allowClosing = true)
            if (outputEnded) throw ChannelClosedException(ChannelEndReason.REMOTE_CLOSE, message = "Channel output has ended")
            val pending = Pending(request(data = ChannelData(header(this, sendSequence), bytes, final)), config.dataRetryIntervalMillis, config.dataSendAttempts)
            check(sending == null)
            sending = pending
            startRetryLocked(this, pending, effects)
            pending
        }

        override fun closeInput() {
            locked { effects ->
                if (terminal != null || inputLocallyClosed) return@locked
                inputLocallyClosed = true
                inputEnded = true
                if (received != null) acknowledgeReceivedLocked(this, effects, final = true)
                publishLocked(effects)
                diagnosticLocked(effects, "local_input_closed", DiagnosticSeverity.INFO, this, details = mapOf("decision" to "wake reader; final ACK pending or next DATA"))
                effects += { readChanged.trySend(Unit) }
            }
        }

        override suspend fun closeGracefully(errorCode: Int) {
            currentCoroutineContext().ensureActive()
            var owner = false
            val result = locked { effects ->
                terminal?.let { if (it.reason == ChannelEndReason.LOCAL_CLOSE) return; throw it }
                ensureLiveLocked(allowClosing = true)
                gracefulResult ?: CompletableDeferred<Unit>().also {
                    gracefulResult = it
                    owner = true
                    phase = ChannelPhase.CLOSING
                    diagnosticLocked(effects, "graceful_close_requested", DiagnosticSeverity.INFO, this, details = mapOf("applicationError" to errorCode.toString(), "decision" to "drain already-started write, then CLOSE"))
                    inputLocallyClosed = true
                    inputEnded = true
                    if (received != null) acknowledgeReceivedLocked(this, effects, final = true)
                    publishLocked(effects)
                    effects += { readChanged.trySend(Unit) }
                }
            }
            try {
                if (owner) {
                    outputMutex.withLock {
                        locked { _ -> ensureLiveLocked(allowClosing = true) }
                        val sent = CompletableDeferred<Unit>()
                        enqueue(Emission(control(this, ChannelCodec.CLOSE, errorCode), { synchronized(monitor) { failure == null && channels[key] === this } }, sent))
                        sent.await()
                        locked { effects ->
                            // Complete the graceful result successfully, independently from the
                            // closed handle's error used to wake unrelated pending operations.
                            if (channels[key] === this) {
                                gracefulResult = null
                                terminateLocked(this, ChannelClosedException(ChannelEndReason.LOCAL_CLOSE, errorCode), effects)
                                effects += { result.complete(Unit) }
                            }
                        }
                    }
                }
                result.await()
            } catch (cancelled: CancellationException) {
                if (owner) abort(this, ChannelEndReason.CANCELLED)
                throw cancelled
            }
        }

        override fun close() = abort(this, ChannelEndReason.LOCAL_CLOSE)
    }

    /** Atomic current value, with callbacks published only after releasing the protocol monitor. */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class StateCell(initial: ChannelState) : StateFlow<ChannelState> {
        private val latest = AtomicReference(initial)
        private val ticks = MutableStateFlow(Any())
        override val value: ChannelState get() = latest.get()
        override val replayCache: List<ChannelState> get() = listOf(value)
        fun publish(next: ChannelState, effects: MutableList<() -> Unit>) {
            latest.set(next)
            effects += { ticks.value = Any() }
        }
        override suspend fun collect(collector: FlowCollector<ChannelState>): Nothing {
            var previous: ChannelState? = null
            return ticks.collect(object : FlowCollector<Any> {
                override suspend fun emit(value: Any) {
                    val next = this@StateCell.value
                    if (next != previous) { previous = next; collector.emit(next) }
                }
            })
        }
    }

}
