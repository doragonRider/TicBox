package dev.ticbox.companion.engine.proxy

import dev.ticbox.companion.api.DiagnosticSeverity
import dev.ticbox.companion.api.DiagnosticSink
import dev.ticbox.companion.api.DuplexTransport
import dev.ticbox.companion.api.NetworkProxyPhase
import dev.ticbox.companion.api.NetworkProxyState
import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.api.ProtocolDiagnostic
import dev.ticbox.companion.protocol.Flow15Codec
import dev.ticbox.companion.protocol.Flow15Frame
import dev.ticbox.companion.protocol.Flow15Opcode as Op
import dev.ticbox.companion.protocol.Flow15StreamDecoder
import dev.ticbox.companion.protocol.Flow15StreamStatus
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

class Flow15ProtocolException(message: String) : IOException(message)

/**
 * One accepted Bluetooth stream. Socket workers isolate individual network failures. All wire writes
 * share one mutex, and network readers wait on it for bounded backpressure. run() owns its children;
 * closing or cancelling it releases the transport and every acquired/connecting network socket.
 */
class Flow15ProxySession(
    private val peer: PeerAddress,
    private val connectionId: Long,
    private val transport: DuplexTransport,
    private val network: ProxyNetwork,
    private val networks: ProxyNetworkMonitor = ProxyNetworkMonitor.DISCONNECTED,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val policy: Flow15ProxyPolicy = Flow15ProxyPolicy(),
    private val monotonicNanos: () -> Long = System::nanoTime,
) : AutoCloseable {
    private val started = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val terminal = AtomicReference<Exception?>()
    private val runJob = AtomicReference<Job?>()
    private val output = Mutex()
    private val resourceLock = Any()
    private val tcp = HashMap<Int, TcpSlot>()
    private val udp = HashMap<UdpKey, UdpSlot>()
    private val udpChanges = Channel<Unit>(Channel.CONFLATED)
    private val udpIdleTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(policy.udpIdleTimeoutMillis)
    private val pulls = HashSet<Job>()
    private val pushes = LinkedHashMap<Int, PushProbe>()
    private val networkLock = Mutex()
    private var latestNetwork: ProxyNetworkStatus? = null
    private var sentNetwork: ProxyNetworkStatus? = null
    private var handshake = 0 // accepting -> acknowledged -> done; peer version is observed only.
    private lateinit var workers: CoroutineScope
    private val mutableState = MutableStateFlow(NetworkProxyState(NetworkProxyPhase.HANDSHAKING, peer, connectionId))
    val state = mutableState.asStateFlow()

    suspend fun run() = supervisorScope {
        check(started.compareAndSet(false, true)) { "A Flow15 session can run only once" }
        if (closed.get()) return@supervisorScope
        workers = this
        runJob.set(coroutineContext[Job])
        if (closed.get()) { runJob.get()?.cancel(); return@supervisorScope }
        val decoder = Flow15StreamDecoder(policy.decoderBufferBytes)
        launch {
            try {
                networks.observe().collect { value -> networkLock.withLock {
                    latestNetwork = value
                    mutableState.update { it.copy(networkType = value.networkType, isMetered = value.isMetered) }
                    if (handshake == 2) sendNetwork(value)
                } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { log("network_monitor_failed", error = error) }
        }
        launch { expireIdleUdpSockets() }
        try {
            while (isActive && !closed.get()) {
                val chunk = transport.read() ?: break
                if (chunk.isEmpty()) throw Flow15ProtocolException("Empty transport read")
                val frames = try { decoder.push(chunk) }
                catch (error: IllegalArgumentException) {
                    log("decoder_resource_limit", mapOf("bufferLimit" to policy.decoderBufferBytes.toString()))
                    throw Flow15ProtocolException("Local Flow15 decoder limit exceeded")
                }
                for (frame in frames) handle(frame)
                decoder.stoppedOpcode?.let { opcode ->
                    log("unknown_opcode_stream_stopped", mapOf("opcode" to opcode.toString(), "retainedBytes" to decoder.remainder.size.toString()))
                    throw Flow15ProtocolException("Unknown Flow15 packet boundary")
                }
            }
            terminal.get()?.let { throw it }
            if (!closed.get() && decoder.status == Flow15StreamStatus.INCOMPLETE) {
                log("truncated_stream", mapOf("retainedBytes" to decoder.remainder.size.toString()))
                throw Flow15ProtocolException("Truncated Flow15 stream")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val failure = terminal.get() ?: error
            log("stream_failed", error = failure)
            throw failure
        }
        finally {
            // Closing before cancelling unblocks platform blocking I/O immediately.
            releaseResources()
            coroutineContext[Job]?.children?.forEach { it.cancel() }
            runJob.set(null)
        }
    }

    override fun close() {
        releaseResources()
        runJob.get()?.cancel()
    }

    private fun releaseResources() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { transport.close() }
        udpChanges.cancel()
        synchronized(resourceLock) {
            tcp.values.forEach { it.abort() }
            udp.values.forEach(UdpSlot::abort)
            pulls.forEach { it.cancel() }
            tcp.clear(); udp.clear(); pulls.clear(); pushes.clear()
            publishCounts()
        }
    }

    private suspend fun handle(frame: Flow15Frame) {
        when (frame) {
            is Flow15Frame.Handshake -> when (frame.opcode) {
                Op.HANDSHAKE_START -> {
                    if (handshake != 0) throw Flow15ProtocolException("Out-of-order handshake START")
                    log("handshake_start", mapOf("version" to frame.version.toString(), "extensionBytes" to frame.extensions.size.toString()), severity = DiagnosticSeverity.INFO)
                    send(Flow15Codec.handshakeAck())
                    handshake = 1
                    mutableState.update { it.copy(peerHandshakeVersion = frame.version) }
                }
                Op.HANDSHAKE_DONE -> networkLock.withLock {
                    if (handshake != 1) throw Flow15ProtocolException("Out-of-order handshake DONE")
                    handshake = 2
                    mutableState.update { it.copy(phase = NetworkProxyPhase.ACTIVE, peerHandshakeVersion = frame.version) }
                    log("handshake_done", mapOf("version" to frame.version.toString()), severity = DiagnosticSeverity.INFO)
                    latestNetwork?.let { sendNetwork(it) }
                }
                else -> unsupported(frame)
            }
            is Flow15Frame.TcpOpen -> openTcp(frame)
            is Flow15Frame.TcpClose -> {
                val slot = synchronized(resourceLock) { tcp[frame.id] }
                if (slot == null) log("tcp_close_missing", id(frame.id)) else slot.finish()
            }
            is Flow15Frame.Data -> when (frame.opcode) {
                Op.TCP_WRITE -> {
                    val slot = synchronized(resourceLock) { tcp[frame.id] }
                    if (slot == null) log("tcp_write_missing", id(frame.id))
                    else if (slot.enqueue(frame.payload)) send(Flow15Frame.Ack(frame.id))
                    else {
                        log("tcp_write_resource_limit_or_closed", id(frame.id))
                        synchronized(resourceLock) {
                            if (tcp[frame.id] === slot) { tcp.remove(frame.id); publishCounts() }
                        }
                        slot.abort()
                        send(Flow15Frame.TcpClose(frame.id))
                    }
                }
                Op.PUSH_DATA -> receivePush(frame.id, frame.payload.size)
                else -> unsupported(frame)
            }
            is Flow15Frame.Datagram -> if (frame.failure) unsupported(frame)
                else sendDatagram(UdpKey(null, ProxyEndpoint(frame.host, frame.port)), frame.payload, null)
            is Flow15Frame.DatagramV2 -> if (frame.opaque == null) unsupported(frame)
                else sendDatagram(UdpKey(ProxyEndpoint(frame.sourceHost, frame.sourcePort), ProxyEndpoint(frame.destinationHost, frame.destinationPort)), frame.payload, frame.opaque)
            is Flow15Frame.Ping -> send(frame)
            is Flow15Frame.Request -> if (frame.opcode == Op.PULL_REQUEST) pull(frame.id, frame.requestedBytes)
                else trackPush(frame.id, frame.requestedBytes)
            is Flow15Frame.PackageName -> log("package_name_consumed", mapOf("opaquePrefix" to frame.opaquePrefix.toString(), "payloadBytes" to frame.payload.size.toString()), severity = DiagnosticSeverity.INFO)
            else -> unsupported(frame)
        }
    }

    private suspend fun openTcp(frame: Flow15Frame.TcpOpen) {
        val slot = TcpSlot(frame.id)
        val admitted = synchronized(resourceLock) {
            if (closed.get()) return
            val previous = tcp.remove(frame.id)
            if (previous != null) { previous.abort(); log("tcp_id_replaced", id(frame.id)) }
            if (tcp.size >= policy.maxTcpConnections) {
                log("tcp_connection_resource_limit", id(frame.id))
                false
            } else {
                tcp[frame.id] = slot
                publishCounts()
                true
            }
        }
        if (!admitted) { send(Flow15Frame.TcpClose(frame.id)); return }
        slot.job = workers.launch {
            try {
                var installed = false
                val socket = withTimeout(policy.connectTimeoutMillis) {
                    network.openTcp(ProxyEndpoint(frame.host, frame.port)).also { installed = slot.install(it) }
                }
                // Install inside the timeout block: timeout may discard its successfully acquired
                // return value before the outer assignment, but the slot still owns and closes it.
                if (!installed) return@launch
                log("tcp_opened", id(frame.id) + ("opaque" to frame.opaque.toString()), severity = DiagnosticSeverity.INFO)
                coroutineScope {
                    val reader = launch {
                        while (isActive && !slot.remoteClosed.get()) {
                            val payload = socket.read(policy.tcpReadBytes) ?: throw TcpEof()
                            if (payload.isEmpty() || payload.size > policy.tcpReadBytes) throw IOException("Invalid TCP read size")
                            if (!slot.remoteClosed.get()) send(Flow15Frame.Data(Op.TCP_WRITE, frame.id, payload))
                        }
                    }
                    for (payload in slot.pending) {
                        try { withTimeout(policy.writeTimeoutMillis) { socket.write(payload) } }
                        finally { slot.dequeue(payload.size) }
                    }
                    socket.close()
                    reader.cancelAndJoin()
                }
            } catch (cancelled: CancellationException) {
                if (cancelled is kotlinx.coroutines.TimeoutCancellationException) log("tcp_timeout", id(frame.id), cancelled)
                else throw cancelled
            } catch (_: TcpEof) { log("tcp_eof", id(frame.id), severity = DiagnosticSeverity.INFO) }
            catch (error: Exception) { log("tcp_failed", id(frame.id), error) }
            finally {
                slot.abort(cancelJob = false)
                // Retain admission until the close is written: otherwise repeated failed
                // opens could accumulate unbounded close-writer coroutines under congestion.
                try {
                    val wasCurrent = synchronized(resourceLock) { tcp[frame.id] === slot }
                    if (wasCurrent && !closed.get() && !slot.remoteClosed.get()) send(Flow15Frame.TcpClose(frame.id))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { log("tcp_close_write_failed", id(frame.id), error) }
                finally {
                    synchronized(resourceLock) {
                        if (tcp[frame.id] === slot) { tcp.remove(frame.id); publishCounts() }
                    }
                }
            }
        }
    }

    private suspend fun sendDatagram(key: UdpKey, payload: ByteArray, opaque: Int?) {
        // Renew an existing lease while selecting it, before a potentially suspending write.
        var slot = synchronized(resourceLock) { udp[key]?.also { it.lastActivity = monotonicNanos() } }
        var acquiring: ProxyDatagramSocket? = null
        try {
            if (slot == null) {
                if (synchronized(resourceLock) { udp.size >= policy.maxUdpConnections }) throw LocalResourceLimit()
                val socket = withTimeout(policy.connectTimeoutMillis) {
                    network.openUdp(key.destination).also { acquiring = it }
                }
                val created = UdpSlot(socket, opaque, monotonicNanos())
                synchronized(resourceLock) {
                    if (closed.get()) { created.abort(); return }
                    udp[key] = created
                    publishCounts()
                    udpChanges.trySend(Unit)
                }
                slot = created
                acquiring = null
                created.job = workers.launch {
                    try {
                        while (isActive) {
                            val response = socket.receive(65_535)
                            if (response.size > 65_535) throw IOException("Oversized UDP response")
                            if (!recordUdpActivity(key, created)) return@launch
                            send(datagramFrame(key, response, created.opaque, failure = false))
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { log("udp_receive_failed", error = error) }
                    finally { removeUdp(key, created) }
                }
            }
            val activeSlot = checkNotNull(slot)
            withTimeout(policy.writeTimeoutMillis) { activeSlot.socket.send(payload) }
            recordUdpActivity(key, activeSlot)
        } catch (cancelled: CancellationException) {
            runCatching { acquiring?.close() }
            if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) throw cancelled
            log("udp_send_timeout", error = cancelled)
            slot?.let { removeUdp(key, it) }
            send(datagramFrame(key, payload, opaque, failure = true))
        } catch (error: Exception) {
            runCatching { acquiring?.close() }
            log(if (error is LocalResourceLimit) "udp_connection_resource_limit" else "udp_send_failed", error = error)
            slot?.let { removeUdp(key, it) }
            send(datagramFrame(key, payload, opaque, failure = true))
        }
    }

    private fun removeUdp(key: UdpKey, slot: UdpSlot) {
        synchronized(resourceLock) {
            if (udp[key] === slot) {
                udp.remove(key)
                publishCounts()
                udpChanges.trySend(Unit)
            }
        }
        slot.abort()
    }

    private fun recordUdpActivity(key: UdpKey, slot: UdpSlot): Boolean = synchronized(resourceLock) {
        if (udp[key] !== slot) return false
        slot.lastActivity = monotonicNanos()
        true
    }

    private suspend fun expireIdleUdpSockets() {
        while (currentCoroutineContext().isActive) {
            val expired = mutableListOf<UdpSlot>()
            val waitNanos = synchronized(resourceLock) {
                // Consume changes already reflected in this snapshot; later changes interrupt the wait.
                udpChanges.tryReceive()
                if (udp.isEmpty()) null else {
                    val now = monotonicNanos()
                    var nearest = Long.MAX_VALUE
                    val entries = udp.entries.iterator()
                    while (entries.hasNext()) {
                        val slot = entries.next().value
                        val idleNanos = now - slot.lastActivity
                        if (idleNanos >= udpIdleTimeoutNanos) {
                            entries.remove()
                            expired.add(slot)
                        } else nearest = minOf(nearest, udpIdleTimeoutNanos - idleNanos)
                    }
                    if (expired.isNotEmpty()) publishCounts()
                    if (udp.isEmpty()) null else nearest
                }
            }
            // Claim expiry under the same lock as activity updates, then close outside the lock.
            expired.forEach { slot ->
                log("udp_idle_closed", severity = DiagnosticSeverity.INFO)
                slot.abort()
            }
            if (waitNanos == null) udpChanges.receive()
            else {
                // Round up to avoid an immediate retry for a sub-millisecond remainder.
                val waitMillis = waitNanos / 1_000_000 + if (waitNanos % 1_000_000 == 0L) 0 else 1
                // Activity only extends a lease. Recheck at the armed deadline instead of waking
                // this worker for every packet; additions/removals interrupt it via udpChanges.
                withTimeoutOrNull(waitMillis) { udpChanges.receive() }
            }
        }
    }

    private fun datagramFrame(key: UdpKey, payload: ByteArray, opaque: Int?, failure: Boolean): Flow15Frame =
        if (key.source == null) Flow15Frame.Datagram(key.destination.addressBits, key.destination.port, payload, failure)
        else Flow15Frame.DatagramV2(key.source.addressBits, key.source.port, key.destination.addressBits,
            key.destination.port, if (failure) null else opaque, payload)

    private fun pull(id: Int, requestedBytes: Int) {
        if (requestedBytes < 0) { log("pull_negative_length", id(id)); return }
        if (requestedBytes > policy.maxPullResponseBytes) { log("pull_resource_limit", id(id) + ("requestedBytes" to requestedBytes.toString())); return }
        synchronized(resourceLock) {
            if (pulls.size >= policy.maxConcurrentPulls) { log("pull_concurrency_resource_limit", id(id)); return }
            if (requestedBytes == 0) return
            val job = workers.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    var remaining = requestedBytes
                    while (remaining > 0) {
                        val count = minOf(65_535, remaining)
                        // This opcode is a throughput probe, not a TCP socket pull/credit operation.
                        send(Flow15Frame.Data(Op.PULL_DATA, id, ByteArray(count)))
                        remaining -= count
                        yield()
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { log("pull_write_failed", id(id), error) }
                finally { synchronized(resourceLock) { pulls.remove(coroutineContext[Job]) } }
            }
            pulls.add(job)
            job.start()
        }
    }

    private fun trackPush(id: Int, requestedBytes: Int) = synchronized(resourceLock) {
        if (pushes.size >= policy.maxTrackedPushes && id !in pushes) {
            pushes.remove(pushes.keys.first())
            log("push_tracking_resource_limit")
        }
        pushes[id] = PushProbe(requestedBytes, System.nanoTime())
    }

    private fun receivePush(id: Int, count: Int) = synchronized(resourceLock) {
        val probe = pushes[id]
        if (probe == null) log("push_data_missing", id(id))
        else {
            probe.received += count
            if (probe.received >= probe.expected) {
                pushes.remove(id)
                log("push_completed", id(id) + mapOf("receivedBytes" to probe.received.toString(), "expectedBytes" to probe.expected.toString(),
                    "elapsedNanos" to (System.nanoTime() - probe.started).toString()), severity = DiagnosticSeverity.INFO)
            }
        }
    }

    private suspend fun sendNetwork(value: ProxyNetworkStatus) {
        if (sentNetwork == value) return
        send(Flow15Frame.NetworkState(value.networkType, if (value.isMetered) 1 else 0))
        sentNetwork = value
    }

    private suspend fun send(frame: Flow15Frame) = send(Flow15Codec.encode(frame))
    private suspend fun send(bytes: ByteArray) {
        output.withLock {
            if (closed.get()) return
            // Cancelling one network worker must not tear a partially written shared-Bluetooth
            // frame. Session.close still closes transport synchronously, unblocking this operation.
            withContext(NonCancellable) {
                try { withTimeout(policy.writeTimeoutMillis) { transport.write(bytes) } }
                catch (cancelled: CancellationException) {
                    if (cancelled is kotlinx.coroutines.TimeoutCancellationException && !closed.get()) {
                        log("transport_write_timeout", error = cancelled)
                        terminal.compareAndSet(null, IOException("Flow15 transport write timeout", cancelled))
                        runCatching { transport.close() }
                    }
                    throw cancelled
                } catch (error: Exception) {
                    if (!closed.get()) {
                        log("transport_write_failed", error = error)
                        terminal.compareAndSet(null, error)
                        runCatching { transport.close() }
                    }
                    throw error
                }
            }
        }
    }

    private fun unsupported(frame: Flow15Frame) = log("incoming_operation_unimplemented_or_spurious", mapOf("opcode" to frame.opcode.toString()))
    private fun id(value: Int) = mapOf("id" to value.toString())
    private fun publishCounts() { mutableState.update { it.copy(tcpConnections = tcp.size, udpConnections = udp.size) } }
    private fun log(event: String, details: Map<String, String> = emptyMap(), error: Exception? = null,
        severity: DiagnosticSeverity = DiagnosticSeverity.WARNING) {
        val fields = if (error == null) details else details + ("exception" to error.javaClass.simpleName)
        runCatching { diagnostics.record(ProtocolDiagnostic("Flow15", event, severity, peer, connectionId, details = fields)) }
    }

    private inner class TcpSlot(val id: Int) {
        val pending = Channel<ByteArray>(policy.maxQueuedTcpFrames)
        val remoteClosed = AtomicBoolean()
        private val disposed = AtomicBoolean()
        private val socket = AtomicReference<ProxyTcpSocket?>()
        var job: Job? = null
        private var queuedBytes = 0
        fun install(value: ProxyTcpSocket): Boolean {
            socket.set(value)
            if (disposed.get() || closed.get()) { socket.getAndSet(null)?.close(); return false }
            return true
        }
        fun enqueue(bytes: ByteArray): Boolean = synchronized(this) {
            if (disposed.get() || remoteClosed.get() || bytes.size > policy.maxQueuedTcpBytes - queuedBytes) return false
            queuedBytes += bytes.size
            if (pending.trySend(bytes).isSuccess) true else { queuedBytes -= bytes.size; false }
        }
        fun dequeue(count: Int) = synchronized(this) { queuedBytes -= count }
        fun finish() { remoteClosed.set(true); pending.close() }
        fun abort(cancelJob: Boolean = true) {
            disposed.set(true)
            runCatching { socket.getAndSet(null)?.close() }
            pending.cancel()
            // Cancelling this job also cancels a still-pending network acquisition.
            if (cancelJob) job?.cancel()
        }
    }
    private class UdpSlot(val socket: ProxyDatagramSocket, val opaque: Int?, var lastActivity: Long) {
        private val disposed = AtomicBoolean()
        @Volatile var job: Job? = null
            set(value) {
                field = value
                if (disposed.get()) value?.cancel()
            }
        fun abort() { if (disposed.compareAndSet(false, true)) { runCatching { socket.close() }; job?.cancel() } }
    }
    private data class UdpKey(val source: ProxyEndpoint?, val destination: ProxyEndpoint)
    private class PushProbe(val expected: Int, val started: Long, var received: Long = 0)
    private class TcpEof : IOException()
    private class LocalResourceLimit : IOException()
}
