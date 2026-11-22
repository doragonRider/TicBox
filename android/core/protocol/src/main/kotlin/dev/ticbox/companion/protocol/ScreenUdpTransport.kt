package dev.ticbox.companion.protocol

import java.io.IOException
import java.io.InterruptedIOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.GeneralSecurityException
import java.util.BitSet
import java.util.TreeMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Direct, authenticated media transport bootstrapped over the existing encrypted ADB channel.
 * Use a fresh random 32-byte key for each screen session, shared by that session's two streams only.
 * Video/audio and each direction derive different keys. Encryption defaults on; authenticated
 * plaintext requires explicit matching mode selection. No mode or stream fallback exists.
 *
 * Callbacks run on an owned daemon thread and must only enqueue work, never decode or block. Audio
 * and video have separate sockets, receive threads, replay windows and bounded reassembly queues.
 */
class ScreenUdpTransport private constructor(
    key: ByteArray,
    private val stream: Int,
    private val server: Boolean,
    target: InetSocketAddress?,
    private val onMessage: (Message) -> Unit,
    private val onFailure: (IOException) -> Unit,
    private val timing: Timing,
    private val protection: VideoProtection,
    accelerationFactory: ((DatagramSocket) -> ScreenUdpAcceleration)? = null,
    private val security: SecurityMode = SecurityMode.AES_GCM,
    private val collectDiagnostics: Boolean = false,
    independentVideoMessage: ((Int, ByteArray) -> Boolean)? = null,
) : AutoCloseable {
    enum class VideoProtection(val wireName: String, val wireVersion: Int) { NONE("none", 1), RS_16_4("rs_16_4", 2) }
    enum class SecurityMode(val wireName: String) {
        AES_GCM("aes_gcm"), AUTH_ONLY("hmac_sha256_128");
        fun wireVersion(protection: VideoProtection): Int = protection.wireVersion + if (this == AUTH_ONLY) 2 else 0
    }
    data class Fec(val dataShards: Int, val parityShards: Int, val recoveredDataShards: Int, val recoveredBlocks: Int, val decodeNanos: Long = 0)
    data class Message(
        val sequence: Long, val type: Int, val bytes: ByteArray, val discontinuity: Boolean,
        val gap: Gap? = null,
        val fec: Fec? = null,
        /** Receiver System.nanoTime when the message became complete; local metadata, never on the wire. */
        val completedNanos: Long? = null,
        /** Receive-thread time spent authenticating, decrypting and reassembling since the previous delivery. */
        val receiveWorkNanos: Long = 0,
    ) {
        override fun toString() = "ScreenUdpMessage(sequence=$sequence, type=$type, bytes=${bytes.size}, discontinuity=$discontinuity)"
    }

    /**
     * Receiver observations attached once to the next message after a reassembly gap. This is local
     * metadata, never serialized on the wire. Missing fragments count only known incomplete frames;
     * skippedMessages also includes messages for which no fragment was retained. These observations
     * do not distinguish network loss from a sender/receiver pause or rejected authentication.
     *
     * Capacity counters cover admission decisions since the previous delivered gap, including
     * decisions about later frames. They show local pressure, not which skipped message it caused.
     * maxAgeMillis is the greatest retained-entry age at a skip; a wholly missing frame's age is
     * unknown. maxNoProgressMillis measures only incomplete frames that were expired or superseded by an independent frame.
     */
    data class Gap(
        val skippedMessages: Long,
        val incompleteMessages: Long,
        val missingFragments: Long,
        val missingMessageTimeout: Boolean,
        val fragmentTimeout: Boolean,
        val frameDeadline: Boolean,
        val maxAgeMillis: Long,
        val maxNoProgressMillis: Long,
        val capacityEvictions: Long,
        val capacityRejectedFragments: Long,
        /** An authenticated, complete independent frame superseded older dependencies. */
        val independentFrame: Boolean = false,
        /**
         * A later message had already arrived whole, so the missing data was taken as lost after
         * [LATER_COMPLETE_MILLIS] without progress instead of the full reorder deadline.
         */
        val laterComplete: Boolean = false,
    )

    /** One successfully sent message; wireBytes counts encrypted datagram contents, not IP/UDP headers.
     * sendNanos starts after acquiring the media lock and includes fragmentation, crypto, pacing and
     * socket writes. pacingWaitNanos measures actual park elapsed time, including scheduler oversleep.
     */
    data class SendStats(
        val sequence: Long, val payloadBytes: Int, val datagrams: Int, val wireBytes: Long,
        val sendNanos: Long, val pacingWaitNanos: Long,
        val parityDatagrams: Int = 0, val fecEncodeNanos: Long = 0,
        val encryptNanos: Long = 0, val socketSendNanos: Long = 0, val sendCalls: Int = 0,
        val nativeBatch: Boolean = false, val fecImplementation: String = "kotlin",
        val mediaSecurity: String = SecurityMode.AES_GCM.wireName,
        val encryptCpuNanos: Long? = null, val socketSendCpuNanos: Long? = null,
        val socketCopyNanos: Long? = null, val socketCopyCpuNanos: Long? = null,
        val socketNativeNanos: Long? = null, val socketNativeCpuNanos: Long? = null,
        val gsoCalls: Int = 0, val gsoDatagrams: Int = 0, val gsoDisabled: Boolean = false,
    )

    /** Exposed for deterministic short-deadline loopback tests; production callers use defaults. */
    data class Timing(
        val handshakeMillis: Long = 5_000,
        val heartbeatMillis: Long = 500,
        val idleMillis: Long = 5_000,
        val videoReorderMillis: Long = 150,
        val audioReorderMillis: Long = 80,
        /** Local video admission count; memory and reorder deadlines remain independent limits. */
        val videoMaxMessages: Int = 4,
    ) {
        init {
            require(handshakeMillis > 0 && heartbeatMillis > 0 && idleMillis >= heartbeatMillis * 2)
            require(videoReorderMillis > 0 && audioReorderMillis > 0)
            require(videoMaxMessages in 1..32)
        }
    }

    private val outgoing = ScreenUdpCipher(key, stream, fromServer = server, protection = protection, security = security)
    private val incoming = ScreenUdpCipher(key, stream, fromServer = !server, protection = protection, security = security)
    private val socket = DatagramSocket(null)
    private var acceleration: ScreenUdpAcceleration? = null
    private val dataWorkspace = ByteArray(16 * ScreenUdpCipher.PAYLOAD)
    private val parityWorkspace = ByteArray(4 * ScreenUdpCipher.PAYLOAD)
    private val packetWorkspace = ByteArray(MAX_BATCH * ScreenUdpCipher.DATAGRAM)
    private val packetLengths = IntArray(MAX_BATCH)
    private val replay = ScreenUdpReplayWindow()
    private val assembler = ScreenUdpAssembler(if (stream == VIDEO) timing.videoReorderMillis else timing.audioReorderMillis,
        if (stream == VIDEO) timing.videoMaxMessages else ScreenUdpAssembler.MAX_MESSAGES)
    private val fecAssembler = if (protection == VideoProtection.RS_16_4) ScreenFecAssembler(timing.videoReorderMillis, timing.videoMaxMessages, collectDiagnostics, independentVideoMessage) else null
    private val closed = AtomicBoolean()
    private val connected = CountDownLatch(1)
    private val dispositionLock = Any()
    private val outputLock = Any()
    private val mediaLock = Any()
    private data class SendPolicy(val pacer: ScreenUdpPacer?, val batchLimit: Int)
    // Video goes out as fast as the socket takes it, a whole FEC block per call. Its encoder alone
    // decides how much there is to send; holding frames here only made them late.
    @Volatile private var sendPolicy: SendPolicy? = if (stream == VIDEO) SendPolicy(null, MAX_BATCH) else null
    @Volatile private var peer: SocketAddress? = target
    @Volatile private var established = false
    @Volatile private var failure: IOException? = null
    private var packetSequence = 0L
    private var messageSequence = 0L
    // The receive thread alone sends heartbeats and reads their answers.
    private val probes = if (collectDiagnostics && !server) LinkedHashMap<Long, Long>() else null
    private var receiveWork = 0L
    private val receiveCounters = if (collectDiagnostics) ScreenUdpReceiveCounters() else null
    private class RoundTrip(val nanos: Long, val at: Long)
    @Volatile private var roundTrip: RoundTrip? = null
    val port: Int get() = socket.localPort

    /**
     * The phone's latest answered heartbeat, sent to and back from the target over this socket, if
     * one came back in the last few seconds. Datagrams queued ahead of it on either side count.
     */
    fun roundTripNanos(now: Long = System.nanoTime()): Long? =
        roundTrip?.takeIf { now - it.at <= ROUND_TRIP_MAX_AGE_NANOS }?.nanos

    init {
        require(stream == VIDEO || stream == AUDIO)
        require(protection == VideoProtection.NONE || stream == VIDEO)
        try {
            // Build the finite-field tables before advertising a port or accepting media. Their
            // first-use initialization must not occupy the receiver during the first keyframe.
            if (protection == VideoProtection.RS_16_4) ScreenReedSolomon.parityCount(1)
            socket.receiveBufferSize = 1024 * 1024
            socket.sendBufferSize = 256 * 1024
            socket.bind(InetSocketAddress(0))
            target?.let { socket.connect(it) }
            if (server && stream == VIDEO) acceleration = accelerationFactory?.invoke(socket)
            socket.soTimeout = minOf(25L, timing.heartbeatMillis).toInt().coerceAtLeast(1)
            Thread(::receive, "screen-udp-${if (server) "send" else "receive"}-$stream").apply { isDaemon = true; start() }
        } catch (error: Exception) {
            acceleration?.close()
            socket.close()
            throw IOException("screen_udp_io_failed", error)
        }
    }

    /** Waits for an authenticated peer acknowledgement; closing or cancellation unblocks it. */
    fun awaitConnected(timeoutMillis: Long = 5_000) {
        require(timeoutMillis > 0)
        try {
            if (!connected.await(timeoutMillis, TimeUnit.MILLISECONDS)) fail(IOException("screen_udp_unreachable"))
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            close()
            throw InterruptedIOException("screen_udp_interrupted").apply { initCause(error) }
        }
        failure?.let { throw it }
        if (!established || closed.get()) throw IOException("screen_udp_closed")
    }

    /** Limit encoded media bursts without making heartbeat or touch traffic wait behind a frame. */
    fun setSendRate(bytesPerSecond: Long, burstBytes: Int = 32 * ScreenUdpCipher.DATAGRAM) {
        sendPolicy = SendPolicy(ScreenUdpPacer(bytesPerSecond, burstBytes), 1)
    }

    /** Only the target sends media. The phone sends authenticated handshake/keepalive datagrams. */
    fun send(type: Int, bytes: ByteArray) { sendMessage(type, bytes, measure = false) }

    /** Returns metadata after releasing the media lock; no observer runs in the send critical path. */
    fun sendMeasured(type: Int, bytes: ByteArray): SendStats = checkNotNull(sendMessage(type, bytes, measure = true))

    private fun sendMessage(type: Int, bytes: ByteArray, measure: Boolean): SendStats? {
        require(type in 1..255 && bytes.size <= (if (stream == AUDIO) MAX_AUDIO_MESSAGE else ToolboxProtocol.MAX_PACKET))
        if (!server || !established || closed.get()) throw failure ?: IOException("screen_udp_not_connected")
        try {
            return synchronized(mediaLock) {
                if (closed.get()) throw IOException("screen_udp_closed")
                if (messageSequence == Long.MAX_VALUE) throw IOException("screen_udp_sequence_exhausted")
                val sequence = messageSequence++
                val started = if (measure) System.nanoTime() else 0L
                var pacingWaitNanos = 0L
                var fecEncodeNanos = 0L
                var encryptNanos = 0L
                var socketSendNanos = 0L
                var encryptCpuNanos: Long? = if (measure && acceleration != null) 0L else null
                var socketSendCpuNanos: Long? = if (measure && acceleration != null) 0L else null
                var socketCopyNanos: Long? = if (measure && acceleration != null) 0L else null
                var socketCopyCpuNanos = socketCopyNanos
                var socketNativeNanos = socketCopyNanos
                var socketNativeCpuNanos = socketCopyNanos
                var gsoCalls = 0; var gsoDatagrams = 0; var gsoDisabled = false
                fun cpuDelta(start: Long?): Long? = start?.let { first -> acceleration?.threadCpuNanos()?.let { (it - first).coerceAtLeast(0) } }
                fun addCpu(total: Long?, value: Long?): Long? = if (total == null || value == null) null else total + value
                var sendCalls = 0
                var datagrams = 0
                var wireBytes = 0L
                var parityDatagrams = 0
                val count = if (protection == VideoProtection.RS_16_4) ScreenFecLayout.wireShards(bytes.size)
                    else maxOf(1, (bytes.size + ScreenUdpCipher.PAYLOAD - 1) / ScreenUdpCipher.PAYLOAD)
                // Shards past dataShards come from the parity workspace, so a block's data and parity go out together.
                fun transmit(first: Int, source: ByteArray, sourceOffset: Int, dataShards: Int, length: Int, parityShards: Int = 0) {
                    val shards = dataShards + parityShards
                    var done = 0
                    while (done < shards) {
                        // One immutable policy per batch: a concurrent rate change cannot leave
                        // a whole-block reservation waiting on a new single-packet burst budget.
                        val policy = sendPolicy
                        val batch = minOf(policy?.batchLimit ?: 1, shards - done)
                        val packetBytes = length + ScreenUdpCipher.HEADER + ScreenUdpCipher.TAG
                        pacingWaitNanos += awaitSendBudget(batch * packetBytes, policy?.pacer, measure)
                        synchronized(outputLock) {
                            if (closed.get()) throw IOException("screen_udp_closed")
                            val encryptStarted = if (measure) System.nanoTime() else 0L
                            val encryptCpuStarted = if (measure) acceleration?.threadCpuNanos() else null
                            repeat(batch) { item ->
                                val shard = done + item
                                val parity = shard >= dataShards
                                packetLengths[item] = outgoing.encodeInto(ScreenUdpFragment(ScreenUdpCipher.MEDIA, nextPacket(), sequence,
                                    type, bytes.size, first + shard, count, if (parity) parityWorkspace else source,
                                    if (parity) (shard - dataShards) * length else sourceOffset + shard * length, length),
                                    packetWorkspace, item * ScreenUdpCipher.DATAGRAM)
                            }
                            encryptCpuNanos = addCpu(encryptCpuNanos, cpuDelta(encryptCpuStarted))
                            if (measure) encryptNanos += (System.nanoTime() - encryptStarted).coerceAtLeast(0)
                            val destination = peer as? InetSocketAddress ?: throw IOException("screen_udp_not_connected")
                            val sendStarted = if (measure) System.nanoTime() else 0L
                            val sendCpuStarted = if (measure) acceleration?.threadCpuNanos() else null
                            val accelerated = acceleration
                            val native = if (accelerated != null) accelerated.sendBatch(packetWorkspace, packetLengths, batch, destination, measure)
                                else { repeat(batch) { item -> socket.send(DatagramPacket(packetWorkspace, item * ScreenUdpCipher.DATAGRAM, packetLengths[item], destination)) }; null }
                            if (measure) {
                                sendCalls += native?.calls ?: batch
                                socketCopyNanos = addCpu(socketCopyNanos, native?.copyNanos)
                                socketCopyCpuNanos = addCpu(socketCopyCpuNanos, native?.copyCpuNanos)
                                socketNativeNanos = addCpu(socketNativeNanos, native?.nativeNanos)
                                socketNativeCpuNanos = addCpu(socketNativeCpuNanos, native?.nativeCpuNanos)
                                gsoCalls += native?.gsoCalls ?: 0; gsoDatagrams += native?.gsoDatagrams ?: 0
                                gsoDisabled = gsoDisabled || native?.gsoDisabled == true
                                socketSendCpuNanos = addCpu(socketSendCpuNanos, cpuDelta(sendCpuStarted))
                                socketSendNanos += (System.nanoTime() - sendStarted).coerceAtLeast(0)
                            }
                        }
                        done += batch
                        if (measure) { datagrams += batch; wireBytes += batch.toLong() * packetBytes }
                    }
                }
                if (protection == VideoProtection.RS_16_4) {
                    val length = ScreenFecLayout.shardBytes(bytes.size)
                    for (block in 0 until ScreenFecLayout.blocks(bytes.size)) {
                        if (closed.get()) throw IOException("screen_udp_closed")
                        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("screen_udp_interrupted")
                        val dataCount = ScreenFecLayout.dataInBlock(bytes.size, block)
                        val offset = ScreenFecLayout.dataOffset(block, 0)
                        val copied = minOf(bytes.size - offset, dataCount * length)
                        bytes.copyInto(dataWorkspace, 0, offset, offset + copied)
                        dataWorkspace.fill(0, copied, dataCount * length)
                        val encodeStarted = if (measure) System.nanoTime() else 0L
                        if (acceleration != null) acceleration!!.encodeParity(dataWorkspace, dataCount, length, parityWorkspace)
                        else ScreenReedSolomon.encodeInto(dataWorkspace, dataCount, length, parityWorkspace)
                        if (measure) fecEncodeNanos += (System.nanoTime() - encodeStarted).coerceAtLeast(0)
                        val parityCount = ScreenReedSolomon.parityCount(dataCount)
                        transmit(block * ScreenFecLayout.BLOCK, dataWorkspace, 0, dataCount, length, parityCount)
                        if (measure) parityDatagrams += parityCount
                    }
                } else {
                    val full = bytes.size / ScreenUdpCipher.PAYLOAD
                    if (full > 0) transmit(0, bytes, 0, full, ScreenUdpCipher.PAYLOAD)
                    val tail = bytes.size % ScreenUdpCipher.PAYLOAD
                    if (tail > 0 || bytes.isEmpty()) transmit(full, bytes, full * ScreenUdpCipher.PAYLOAD, 1, tail)
                }
                if (!measure) null else SendStats(sequence, bytes.size, datagrams, wireBytes,
                    (System.nanoTime() - started).coerceAtLeast(0), pacingWaitNanos, parityDatagrams, fecEncodeNanos,
                    encryptNanos, socketSendNanos, sendCalls, acceleration != null, acceleration?.fecImplementation ?: "kotlin", security.wireName, encryptCpuNanos, socketSendCpuNanos,
                    socketCopyNanos, socketCopyCpuNanos, socketNativeNanos, socketNativeCpuNanos, gsoCalls, gsoDatagrams, gsoDisabled)
            }
        } catch (error: Exception) {
            val wrapped = IOException("screen_udp_io_failed", error)
            fail(wrapped)
            throw failure ?: wrapped
        }
    }

    private fun awaitSendBudget(bytes: Int, pacer: ScreenUdpPacer?, measure: Boolean): Long {
        var parkedNanos = 0L
        while (true) {
            if (closed.get()) throw IOException("screen_udp_closed")
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("screen_udp_interrupted")
            val wait = pacer?.take(bytes, System.nanoTime()) ?: 0L
            if (wait == 0L) return parkedNanos
            // Closing the transport must interrupt a low-rate frame promptly, without a sleeping lock.
            val before = if (measure) System.nanoTime() else 0L
            LockSupport.parkNanos(minOf(wait, 10_000_000L))
            if (measure) parkedNanos += (System.nanoTime() - before).coerceAtLeast(0)
        }
    }

    private fun nextPacket(): Long {
        if (packetSequence == Long.MAX_VALUE) throw IOException("screen_udp_sequence_exhausted")
        return packetSequence++
    }

    /** A PONG carries the PING's packet sequence back, which is how its sender pairs the two. */
    private fun control(kind: Int, answering: Long = 0): Long? = synchronized(outputLock) {
        if (closed.get()) null else nextPacket().also { write(ScreenUdpFragment(kind, it, answering, 0, 0, 0, 0, byteArrayOf())) }
    }

    private fun ping() {
        val pending = probes
        val sent = if (pending != null) System.nanoTime() else 0L
        val sequence = control(ScreenUdpCipher.PING) ?: return
        if (pending == null) return
        pending[sequence] = sent
        // An unanswered probe is lost or very late; a few are kept for the late ones.
        while (pending.size > MAX_PROBES) pending.remove(pending.keys.first())
    }

    private fun answered(fragment: ScreenUdpFragment, arrived: Long) {
        val sent = probes?.remove(fragment.messageSequence) ?: return
        roundTrip = RoundTrip(arrived - sent, arrived)
    }

    private fun deliver(messages: List<Message>, since: Long) {
        if (!collectDiagnostics) { messages.forEach(onMessage); return }
        val now = System.nanoTime()
        receiveWork += (now - since).coerceAtLeast(0)
        if (messages.isEmpty()) return
        messages.forEachIndexed { index, message ->
            onMessage(message.copy(completedNanos = now, receiveWorkNanos = if (index == 0) receiveWork else 0))
        }
        receiveWork = 0
    }

    private fun write(fragment: ScreenUdpFragment) {
        val destination = peer ?: return
        val bytes = outgoing.encode(fragment)
        socket.send(DatagramPacket(bytes, bytes.size, destination))
    }

    private fun receive() {
        val buffer = ByteArray(ScreenUdpCipher.DATAGRAM + 1)
        val packet = DatagramPacket(buffer, buffer.size)
        val started = monotonicMillis()
        var lastReceived = started
        var lastSent = Long.MIN_VALUE
        try {
            while (!closed.get()) {
                val now = monotonicMillis()
                receiveCounters?.loop(now)
                if (!established && now - started >= timing.handshakeMillis) throw IOException("screen_udp_unreachable")
                if (established && now - lastReceived >= timing.idleMillis) throw IOException("screen_udp_connection_lost")
                if (lastSent == Long.MIN_VALUE || now - lastSent >= timing.heartbeatMillis) {
                    if (established) ping()
                    else if (!server) control(ScreenUdpCipher.HELLO)
                    lastSent = now
                }
                packet.length = buffer.size
                try { socket.receive(packet) } catch (_: SocketTimeoutException) {
                    deliver(expireMedia(monotonicMillis()), if (collectDiagnostics) System.nanoTime() else 0L)
                    continue
                }
                val arrived = if (collectDiagnostics) System.nanoTime() else 0L
                receiveCounters?.let { it.received++ }
                if (packet.length > ScreenUdpCipher.DATAGRAM) { receiveCounters?.let { it.rejected++ }; continue }
                val source = packet.socketAddress
                val selected = peer
                if (selected != null && selected != source) { receiveCounters?.let { it.rejected++ }; continue }
                val fragment = incoming.decode(buffer, packet.length)
                if (fragment == null || (selected == null && (!server || fragment.kind != ScreenUdpCipher.HELLO)) || !replay.accept(fragment.packetSequence)) {
                    receiveCounters?.let { it.rejected++ }; continue
                }
                if (selected == null) peer = source // Only an authenticated HELLO can select the destination.
                lastReceived = monotonicMillis()
                receiveCounters?.let { counts ->
                    counts.authenticated++; counts.lastAuthenticated = lastReceived
                    when (fragment.kind) {
                        ScreenUdpCipher.PING -> { counts.pings++; counts.lastPing = lastReceived }
                        ScreenUdpCipher.PONG -> { counts.pongs++; counts.lastPong = lastReceived }
                        ScreenUdpCipher.MEDIA -> { counts.media++; counts.lastMedia = lastReceived }
                    }
                }
                when (fragment.kind) {
                    ScreenUdpCipher.HELLO -> if (server) {
                        control(ScreenUdpCipher.ACK)
                        established = true
                        connected.countDown()
                    }
                    ScreenUdpCipher.ACK -> if (!server) {
                        established = true
                        connected.countDown()
                    }
                    ScreenUdpCipher.PING -> if (established) control(ScreenUdpCipher.PONG, fragment.packetSequence)
                    ScreenUdpCipher.PONG -> if (collectDiagnostics && !server) answered(fragment, arrived)
                    ScreenUdpCipher.MEDIA -> if (established && !server) deliver(acceptMedia(fragment, lastReceived), arrived)
                }
                deliver(expireMedia(lastReceived), if (collectDiagnostics) System.nanoTime() else 0L)
            }
        } catch (error: Exception) {
            if (!closed.get()) fail(if (error is IOException && error.message in FAILURE_CODES) error else IOException("screen_udp_io_failed", error))
        } finally { socket.close(); connected.countDown() }
    }

    private fun acceptMedia(fragment: ScreenUdpFragment, now: Long) = fecAssembler?.accept(fragment, now) ?: assembler.accept(fragment, now)
    private fun expireMedia(now: Long) = fecAssembler?.expire(now) ?: assembler.expire(now)

    private fun fail(error: IOException) {
        val observed = error as? ScreenUdpTransportException ?: ScreenUdpTransportException(error, receiveCounters?.let { ScreenUdpFailureDiagnostics.snapshot(error, stream, server, monotonicMillis(), it) })
        val notify = synchronized(dispositionLock) {
            if (closed.get()) false else { failure = observed; closed.set(true); true }
        }
        if (notify) {
            acceleration?.close()
            socket.close()
            connected.countDown()
            runCatching { onFailure(observed) }
        }
    }

    override fun close() {
        val dispose = synchronized(dispositionLock) {
            if (closed.get()) false else { closed.set(true); true }
        }
        if (dispose) { acceleration?.close(); socket.close(); connected.countDown() }
    }

    companion object {
        const val VIDEO = 1
        const val AUDIO = 2
        const val MAX_AUDIO_MESSAGE = 64 * 1024
        /**
         * Everything of a message is sent before the next one, and nothing is resent. Once a later
         * message is whole, a gap that made no progress for this long is loss, not reordering.
         */
        const val LATER_COMPLETE_MILLIS = 30L
        /** Datagrams per send call: a whole FEC block, 24 KB, well within one 64 KB GSO send. */
        internal const val MAX_BATCH = ScreenFecLayout.BLOCK
        private val FAILURE_CODES = setOf("screen_udp_unreachable", "screen_udp_connection_lost", "screen_udp_io_failed")
        private const val MAX_PROBES = 8
        private const val ROUND_TRIP_MAX_AGE_NANOS = 3_000_000_000L
        fun listen(
            key: ByteArray, stream: Int, onMessage: (Message) -> Unit = {},
            onFailure: (IOException) -> Unit, timing: Timing = Timing(), protection: VideoProtection = VideoProtection.NONE,
            accelerationFactory: ((DatagramSocket) -> ScreenUdpAcceleration)? = null,
            security: SecurityMode = SecurityMode.AES_GCM,
            collectDiagnostics: Boolean = false,
        ): ScreenUdpTransport = ScreenUdpTransport(key, stream, true, null, onMessage, onFailure, timing, protection, accelerationFactory, security, collectDiagnostics)
        fun connect(
            address: InetAddress, port: Int, key: ByteArray, stream: Int,
            onMessage: (Message) -> Unit, onFailure: (IOException) -> Unit, timing: Timing = Timing(), protection: VideoProtection = VideoProtection.NONE,
            security: SecurityMode = SecurityMode.AES_GCM,
            collectDiagnostics: Boolean = false,
            independentVideoMessage: ((Int, ByteArray) -> Boolean)? = null,
        ): ScreenUdpTransport {
            require(port in 1..65535 && !address.isAnyLocalAddress && !address.isMulticastAddress)
            return ScreenUdpTransport(key, stream, false, InetSocketAddress(address, port), onMessage, onFailure, timing, protection, security = security, collectDiagnostics = collectDiagnostics, independentVideoMessage = independentVideoMessage)
        }
        private fun monotonicMillis() = System.nanoTime() / 1_000_000
    }
}

internal data class ScreenUdpFragment(
    val kind: Int, val packetSequence: Long, val messageSequence: Long, val type: Int,
    val total: Int, val index: Int, val count: Int, val payload: ByteArray,
    val payloadOffset: Int = 0, val payloadLength: Int = payload.size,
)

/** Header fields are authenticated before they may allocate memory, advance replay state or route. */
internal class ScreenUdpCipher(masterKey: ByteArray, private val stream: Int, fromServer: Boolean,
    private val protection: ScreenUdpTransport.VideoProtection = ScreenUdpTransport.VideoProtection.NONE,
    private val security: ScreenUdpTransport.SecurityMode = ScreenUdpTransport.SecurityMode.AES_GCM,
) {
    private val key: SecretKeySpec
    private val version = security.wireVersion(protection)
    private val cipher = if (security == ScreenUdpTransport.SecurityMode.AES_GCM) Cipher.getInstance("AES/GCM/NoPadding") else null
    private val authenticator = if (security == ScreenUdpTransport.SecurityMode.AUTH_ONLY) Mac.getInstance("HmacSHA256") else null
    private val macBytes = ByteArray(32)
    private val expectedTag = ByteArray(TAG)
    private val receivedTag = ByteArray(TAG)
    private val headerBytes = ByteArray(HEADER)
    private val headerBuffer = ByteBuffer.wrap(headerBytes)
    private val nonceBytes = ByteArray(12)
    private val nonceBuffer = ByteBuffer.wrap(nonceBytes)
    init {
        require(masterKey.size == 32 && stream in ScreenUdpTransport.VIDEO..ScreenUdpTransport.AUDIO)
        require(protection == ScreenUdpTransport.VideoProtection.NONE || stream == ScreenUdpTransport.VIDEO)
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(masterKey, "HmacSHA256"))
        key = SecretKeySpec(hmac.doFinal("ticbox-screen-udp-v$version:$stream:${if (fromServer) "server" else "client"}".toByteArray(Charsets.US_ASCII)), if (cipher != null) "AES" else "HmacSHA256")
        authenticator?.init(key)
    }
    fun encode(value: ScreenUdpFragment): ByteArray = ByteArray(HEADER + TAG + value.payloadLength).also { encodeInto(value, it, 0) }
    /** Caller serializes access to the cipher and owns destination until the socket returns. */
    fun encodeInto(value: ScreenUdpFragment, destination: ByteArray, offset: Int): Int {
        require(valid(value))
        require(offset >= 0 && offset <= destination.size - HEADER - TAG - value.payloadLength)
        headerBuffer.clear()
        headerBuffer.apply {
            putInt(MAGIC); put(version.toByte()); put(stream.toByte()); put(value.kind.toByte()); put(value.type.toByte())
            putLong(value.packetSequence); putLong(value.messageSequence); putInt(value.total)
            putShort(value.index.toShort()); putShort(value.count.toShort())
        }
        headerBytes.copyInto(destination, offset)
        if (cipher != null) {
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(value.packetSequence)))
            cipher.updateAAD(headerBytes)
            return HEADER + cipher.doFinal(value.payload, value.payloadOffset, value.payloadLength, destination, offset + HEADER)
        }
        val mac = checkNotNull(authenticator)
        mac.update(headerBytes)
        mac.update(value.payload, value.payloadOffset, value.payloadLength)
        mac.doFinal(macBytes, 0) // resets to the initialized state; no per-packet key setup
        value.payload.copyInto(destination, offset + HEADER, value.payloadOffset, value.payloadOffset + value.payloadLength)
        macBytes.copyInto(destination, offset + HEADER + value.payloadLength, 0, TAG)
        return HEADER + value.payloadLength + TAG
    }
    fun decode(bytes: ByteArray, length: Int = bytes.size): ScreenUdpFragment? {
        if (length !in HEADER + TAG..DATAGRAM || length > bytes.size) return null
        val header = ByteBuffer.wrap(bytes, 0, HEADER)
        if (header.int != MAGIC || header.get().toInt() != version || header.get().toInt() != stream) return null
        val kind = header.get().toInt() and 255
        val type = header.get().toInt() and 255
        val packetSequence = header.long
        val messageSequence = header.long
        val total = header.int
        val index = header.short.toInt() and 65535
        val count = header.short.toInt() and 65535
        if (packetSequence < 0) return null
        val payload = try {
            if (cipher != null) {
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce(packetSequence)))
                cipher.updateAAD(bytes, 0, HEADER)
                cipher.doFinal(bytes, HEADER, length - HEADER)
            } else {
                val mac = checkNotNull(authenticator)
                mac.update(bytes, 0, length - TAG)
                mac.doFinal(macBytes, 0)
                macBytes.copyInto(expectedTag, 0, 0, TAG)
                bytes.copyInto(receivedTag, 0, length - TAG, length)
                if (!MessageDigest.isEqual(expectedTag, receivedTag)) return null
                bytes.copyOfRange(HEADER, length - TAG)
            }
        } catch (_: GeneralSecurityException) { return null }
        return ScreenUdpFragment(kind, packetSequence, messageSequence, type, total, index, count, payload).takeIf(::valid)
    }
    private fun valid(v: ScreenUdpFragment): Boolean {
        if (v.payloadOffset < 0 || v.payloadLength < 0 || v.payloadOffset > v.payload.size - v.payloadLength) return false
        if (v.packetSequence < 0 || v.packetSequence == Long.MAX_VALUE || v.messageSequence < 0 || v.messageSequence == Long.MAX_VALUE) return false
        // Only a PONG carries a sequence: the PING's it answers.
        if ((v.kind == HELLO || v.kind == ACK || v.kind == PING || v.kind == PONG)) return v.type == 0 && (v.kind == PONG || v.messageSequence == 0L) &&
            v.total == 0 && v.index == 0 && v.count == 0 && v.payloadLength == 0
        if (v.kind != MEDIA || v.type !in 1..255 || v.total !in 0..(if (stream == ScreenUdpTransport.AUDIO) ScreenUdpTransport.MAX_AUDIO_MESSAGE else ToolboxProtocol.MAX_PACKET)) return false
        if (protection == ScreenUdpTransport.VideoProtection.RS_16_4) {
            val count = ScreenFecLayout.wireShards(v.total)
            if (v.count != count || v.index !in 0 until count || v.payloadLength != ScreenFecLayout.shardBytes(v.total)) return false
            val block = v.index / ScreenFecLayout.BLOCK
            val shard = v.index % ScreenFecLayout.BLOCK
            val data = ScreenFecLayout.dataInBlock(v.total, block)
            if (shard >= data + ScreenReedSolomon.parityCount(data)) return false
            if (shard < data && block * ScreenFecLayout.DATA + shard == ScreenFecLayout.dataShards(v.total) - 1) {
                val content = v.total - ScreenFecLayout.dataOffset(block, shard)
                for (index in content until v.payloadLength) if (v.payload[v.payloadOffset + index] != 0.toByte()) return false
            }
            return true
        }
        val count = maxOf(1, (v.total + PAYLOAD - 1) / PAYLOAD)
        if (v.count != count || v.index !in 0 until count) return false
        return v.payloadLength == minOf(PAYLOAD, v.total - v.index * PAYLOAD)
    }
    private fun nonce(sequence: Long): ByteArray { nonceBuffer.putLong(4, sequence); return nonceBytes }
    companion object {
        const val DATAGRAM = 1200
        const val HEADER = 32
        const val TAG = 16
        const val PAYLOAD = DATAGRAM - HEADER - TAG
        const val MAGIC = 0x54575531 // TWU1
        const val HELLO = 1
        const val ACK = 2
        const val MEDIA = 3
        const val PING = 4
        const val PONG = 5
    }
}

/** Packet replay protection is independent of media ordering (heartbeats also consume sequences). */
internal class ScreenUdpReplayWindow(private val size: Int = 16_384) {
    private var highest = -1L
    private val seen = BitSet(size)
    init { require(size > 0) }
    fun accept(sequence: Long): Boolean {
        if (sequence < 0 || sequence == Long.MAX_VALUE) return false
        if (sequence > highest) {
            val delta = sequence - highest
            if (delta >= size) seen.clear()
            else {
                var clearing = highest + 1
                while (clearing <= sequence) { seen.clear((clearing % size).toInt()); clearing++ }
            }
            highest = sequence
        } else if (highest - sequence >= size) return false
        val slot = (sequence % size).toInt()
        if (seen[slot]) return false
        seen.set(slot)
        return true
    }
}

/**
 * A bounded message count / 16 MiB are retained. Missing messages wait only the reorder deadline;
 * incomplete frames expire after no fragment progress or the hard two-second bound. The next
 * delivered message explicitly reports discontinuity, so dependent video can wait for a keyframe.
 */
internal class ScreenUdpAssembler(private val expiryMillis: Long, private val maximumMessages: Int = MAX_MESSAGES) {
    init { require(expiryMillis > 0 && maximumMessages in 1..32) }
    private class Partial(val first: ScreenUdpFragment, val arrived: Long) {
        val bytes = ByteArray(first.total)
        val seen = BitSet(first.count)
        var received = 0
        var lastProgress = arrived
        val complete get() = received == first.count
    }
    private val pending = TreeMap<Long, Partial>()
    private var expected = 0L
    private var retained = 0
    private var discontinuity = false
    private var skippedMessages = 0L
    private var incompleteMessages = 0L
    private var missingFragments = 0L
    private var missingMessageTimeout = false
    private var fragmentTimeout = false
    private var frameDeadline = false
    private var maxAgeMillis = 0L
    private var maxNoProgressMillis = 0L
    private var capacityEvictions = 0L
    private var capacityRejectedFragments = 0L
    private var laterComplete = false
    private val lostAfter = minOf(expiryMillis, ScreenUdpTransport.LATER_COMPLETE_MILLIS)
    fun accept(fragment: ScreenUdpFragment, now: Long): List<ScreenUdpTransport.Message> {
        if (fragment.messageSequence < expected) return emptyList()
        val delivered = expire(now).toMutableList()
        if (fragment.messageSequence < expected) return delivered
        var partial = pending[fragment.messageSequence]
        if (partial == null) {
            // The packet that fills the current gap can unlock the entire pending queue. A
            // complete message needs no retained slot: dispatch it before applying that budget.
            if (fragment.messageSequence == expected && fragment.count == 1) {
                delivered += message(expected, fragment.type, fragment.payload.copyOf())
                expected++
                delivered += expire(now)
                return delivered
            }
            // Preserve the earliest decodable chain, especially an incomplete keyframe.
            // Plan the complete admission first: if earlier messages leave too little space,
            // rejecting this arrival must not also discard otherwise usable future entries.
            val evicted = ArrayList<Long>(maximumMessages)
            var retainedAfter = retained
            var countAfter = pending.size
            val candidates = pending.descendingMap().entries.iterator()
            while (countAfter >= maximumMessages || retainedAfter + fragment.total > MAX_BYTES) {
                if (!candidates.hasNext()) {
                    capacityRejectedFragments = saturatedAdd(capacityRejectedFragments, 1)
                    return delivered
                }
                val furthest = candidates.next()
                if (fragment.messageSequence > furthest.key) {
                    capacityRejectedFragments = saturatedAdd(capacityRejectedFragments, 1)
                    return delivered
                }
                evicted += furthest.key
                retainedAfter -= furthest.value.bytes.size
                countAfter--
            }
            // Only mark a gap when an evicted sequence is actually skipped; earlier pending
            // messages are still continuous and may be delivered before reaching that gap.
            evicted.forEach(::remove)
            capacityEvictions = saturatedAdd(capacityEvictions, evicted.size.toLong())
            partial = Partial(fragment, now)
            pending[fragment.messageSequence] = partial
            retained += fragment.total
        }
        val first = partial.first
        if (first.total != fragment.total || first.type != fragment.type || first.count != fragment.count) return delivered
        if (!partial.seen[fragment.index]) {
            fragment.payload.copyInto(partial.bytes, fragment.index * ScreenUdpCipher.PAYLOAD)
            partial.seen.set(fragment.index); partial.received++; partial.lastProgress = now
        }
        delivered += expire(now)
        return delivered
    }
    fun expire(now: Long): List<ScreenUdpTransport.Message> {
        val result = mutableListOf<ScreenUdpTransport.Message>()
        while (pending.isNotEmpty()) {
            val first = pending.firstEntry()
            val id = first.key
            val value = first.value
            val later = pending.tailMap(id, false).values.any { it.complete }
            if (id > expected) {
                val early = value.complete || later
                if (now - value.arrived < if (early) lostAfter else expiryMillis) break
                skippedMessages = saturatedAdd(skippedMessages, id - expected)
                missingMessageTimeout = missingMessageTimeout || now - value.arrived >= expiryMillis
                laterComplete = laterComplete || early && now - value.arrived < expiryMillis
                maxAgeMillis = maxOf(maxAgeMillis, now - value.arrived)
                expected = id; discontinuity = true
            }
            if (value.complete) {
                remove(id)
                result += message(id, value.first.type, value.bytes)
                expected = id + 1
            } else if (now - value.lastProgress >= expiryMillis || now - value.arrived >= MAX_FRAME_MILLIS ||
                later && now - value.lastProgress >= lostAfter) {
                laterComplete = laterComplete || now - value.lastProgress < expiryMillis && now - value.arrived < MAX_FRAME_MILLIS
                skippedMessages = saturatedAdd(skippedMessages, 1)
                incompleteMessages = saturatedAdd(incompleteMessages, 1)
                missingFragments = saturatedAdd(missingFragments, (value.first.count - value.received).toLong())
                fragmentTimeout = fragmentTimeout || now - value.lastProgress >= expiryMillis
                frameDeadline = frameDeadline || now - value.arrived >= MAX_FRAME_MILLIS
                maxAgeMillis = maxOf(maxAgeMillis, now - value.arrived)
                maxNoProgressMillis = maxOf(maxNoProgressMillis, now - value.lastProgress)
                remove(id); expected = id + 1; discontinuity = true
            } else break
        }
        return result
    }
    private fun message(sequence: Long, type: Int, bytes: ByteArray): ScreenUdpTransport.Message {
        val gap = if (discontinuity) ScreenUdpTransport.Gap(
            skippedMessages, incompleteMessages, missingFragments, missingMessageTimeout, fragmentTimeout,
            frameDeadline, maxAgeMillis, maxNoProgressMillis, capacityEvictions, capacityRejectedFragments,
            laterComplete = laterComplete,
        ) else null
        val result = ScreenUdpTransport.Message(sequence, type, bytes, discontinuity, gap)
        if (discontinuity) {
            discontinuity = false; laterComplete = false
            skippedMessages = 0; incompleteMessages = 0; missingFragments = 0
            missingMessageTimeout = false; fragmentTimeout = false; frameDeadline = false
            maxAgeMillis = 0; maxNoProgressMillis = 0
            capacityEvictions = 0; capacityRejectedFragments = 0
        }
        return result
    }
    private fun saturatedAdd(value: Long, increment: Long) = if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment
    private fun remove(id: Long) { pending.remove(id)?.let { retained -= it.bytes.size } }
    internal val retainedBytes: Int get() = retained
    internal val retainedMessages: Int get() = pending.size
    companion object { const val MAX_MESSAGES = 4; const val MAX_BYTES = 16 * 1024 * 1024; const val MAX_FRAME_MILLIS = 2_000L }
}

/** A token budget for one media stream. A denied attempt consumes nothing. */
internal class ScreenUdpPacer(bytesPerSecond: Long, burstBytes: Int) {
    private val budget = Budget(bytesPerSecond, burstBytes)

    @Synchronized fun take(bytes: Int, nowNanos: Long): Long {
        require(bytes in 0..ScreenUdpTransport.MAX_BATCH * ScreenUdpCipher.DATAGRAM)
        val wait = budget.waitFor(bytes, nowNanos)
        if (wait != 0L) return wait
        budget.consume(bytes)
        return 0
    }

    private class Budget(private val bytesPerSecond: Long, private val burstBytes: Int) {
        private var tokens = burstBytes.toDouble()
        private var updated: Long? = null
        init { require(bytesPerSecond in 1..1_000_000_000L); require(burstBytes in ScreenUdpCipher.DATAGRAM..1_048_576) }
        fun waitFor(bytes: Int, nowNanos: Long): Long {
            updated?.let { previous ->
                val elapsed = (nowNanos - previous).coerceAtLeast(0)
                tokens = minOf(burstBytes.toDouble(), tokens + elapsed.toDouble() * bytesPerSecond / 1_000_000_000.0)
            }
            updated = nowNanos
            return if (tokens >= bytes) 0 else kotlin.math.ceil((bytes - tokens) * 1_000_000_000.0 / bytesPerSecond).toLong().coerceAtLeast(1)
        }
        fun consume(bytes: Int) { tokens -= bytes }
    }
}
