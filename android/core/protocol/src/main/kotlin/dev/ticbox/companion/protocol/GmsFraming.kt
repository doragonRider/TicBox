package dev.ticbox.companion.protocol

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

/** Classic secure RFCOMM data service; the phone is the client of this UUID. */
const val GMS_DATA_UUID = "5e8945b0-9525-11e3-a5e2-0800200c9a66"

/** Only length framing is applied. Record bodies may be ciphertext on other transport branches. */
object GmsRecord {
    fun encode(payload: ByteArray): ByteArray = lengthFrame(payload, allowEmpty = false)
}

internal fun lengthFrame(payload: ByteArray, allowEmpty: Boolean): ByteArray {
    protocolCheck((allowEmpty || payload.isNotEmpty()) && payload.size <= ProtocolLimits.MAX_MESSAGE_BYTES, "Invalid or excessive record length")
    return ByteBuffer.allocate(4 + payload.size).putInt(payload.size).put(payload).array()
}

/** Incremental signed int32 BE frame reader. Buffers at most one pending body plus its header.
 * A malformed frame poisons the reader; callers must close/reset their session rather than guess
 * where a subsequent packet starts. Completed output corresponds to the caller's input chunk;
 * transport adapters should use bounded reads. A chunk may contain many complete frames.
 */
internal class LengthFrameDecoder(private val allowEmpty: Boolean, private val maxBytes: Int) {
    private val header = ByteArray(4)
    private var headerBytes = 0
    private var body: ByteArray? = null
    private var bodyBytes = 0
    private var failed = false
    private var ended = false

    init { require(maxBytes in 1..ProtocolLimits.MAX_MESSAGE_BYTES) }

    fun push(input: ByteArray): List<ByteArray> {
        check(!failed && !ended) { "Frame decoder is closed" }
        val output = ArrayList<ByteArray>()
        var offset = 0
        try {
            while (offset < input.size) {
                if (body == null) {
                    val take = minOf(4 - headerBytes, input.size - offset)
                    input.copyInto(header, headerBytes, offset, offset + take)
                    offset += take
                    headerBytes += take
                    if (headerBytes < 4) break
                    val size = ByteBuffer.wrap(header).int
                    protocolCheck(size >= if (allowEmpty) 0 else 1, "Invalid record length")
                    protocolCheck(size <= maxBytes, "Record length budget exceeded")
                    if (size == 0) {
                        output += byteArrayOf()
                        headerBytes = 0
                        continue
                    }
                    body = ByteArray(size)
                    bodyBytes = 0
                }
                val destination = body!!
                val take = minOf(destination.size - bodyBytes, input.size - offset)
                input.copyInto(destination, bodyBytes, offset, offset + take)
                offset += take
                bodyBytes += take
                if (bodyBytes == destination.size) {
                    output += destination
                    body = null
                    headerBytes = 0
                    bodyBytes = 0
                }
            }
            return output
        } catch (error: Exception) {
            failed = true
            body = null
            throw error
        }
    }

    fun finish() {
        check(!failed && !ended) { "Frame decoder is closed" }
        ended = true
        if (headerBytes != 0 || body != null) {
            body = null
            throw ProtocolException("Truncated record at EOF")
        }
    }
}

class GmsRecordDecoder(maxBytes: Int = ProtocolLimits.MAX_MESSAGE_BYTES) {
    private val decoder = LengthFrameDecoder(allowEmpty = false, maxBytes = maxBytes)
    fun push(chunk: ByteArray): List<ByteArray> = decoder.push(chunk)
    fun finish() = decoder.finish()
}

data class MessagePiece(
    val fragment: ByteArray,
    val digest: String,
    val pieceIndex: Int,
    val pieceCount: Int,
    val queueId: Int,
    val unknownFields: List<ProtoField> = emptyList(),
)

object MessagePieces {
    internal fun digest(message: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-1").digest(message))

    fun decode(pieceBytes: ByteArray): MessagePiece {
        val raw = GmsStructs.decode("MessagePiece", pieceBytes)
        val count = raw["pieceCount"] as Int
        val index = raw["pieceIndex"] as Int
        val queue = raw["queueId"] as? Int ?: -1
        val digest = raw["digest"] as String
        protocolCheck(count > 0 && index in 1..count, "Invalid one-based MessagePiece index/count")
        protocolCheck(digest.matches(Regex("[A-Za-z0-9_-]{27}")), "Invalid MessagePiece SHA-1 base64url digest")
        protocolCheck(count == 1 || queue != -1, "Fragmented MessagePiece requires a queue ID")
        return MessagePiece(raw["fragment"] as ByteArray, digest, index, count, queue, raw.unknownFields)
    }

    fun encode(message: ByteArray, fragmentBytes: Int = 8192, queueId: Int = -1): List<ByteArray> {
        protocolCheck(message.isNotEmpty() && message.size <= ProtocolLimits.MAX_MESSAGE_BYTES, "Invalid message length")
        require(fragmentBytes in 1..ProtocolLimits.MAX_MESSAGE_BYTES)
        val count = (message.size - 1) / fragmentBytes + 1
        protocolCheck(count == 1 || queueId != -1, "Fragmentation requires an explicit queue ID")
        protocolCheck(count <= 16384, "Outgoing fragment-count budget exceeded")
        val digest = digest(message)
        return List(count) { index ->
            GmsStructs.encode("MessagePiece", linkedMapOf<String, Any>(
                "fragment" to message.copyOfRange(index * fragmentBytes, minOf(message.size, (index + 1) * fragmentBytes)),
                "digest" to digest,
                "pieceIndex" to index + 1,
                "pieceCount" to count,
            ).apply { if (count > 1 || queueId != -1) put("queueId", queueId) })
        }
    }
}

/** Bounded receiver-side assembly. Serialized calls are required.
 * Identical retransmitted pieces are idempotent; conflicting duplicates fail closed.
 * Detecting an expired assembly on a later accept fails the session explicitly.
 * Out-of-order storage is a client feature, not a claim that the original peer permits it.
 * SHA-1 here reproduces the observed integrity field; it is not an authentication primitive.
 */
class MessageAssembler(
    private val maxMessageBytes: Int = ProtocolLimits.MAX_MESSAGE_BYTES,
    private val maxPendingMessages: Int = 8,
    private val maxBufferedBytes: Int = 2 * ProtocolLimits.MAX_MESSAGE_BYTES,
    private val maxPiecesPerMessage: Int = 16384,
    private val assemblyTimeoutNanos: Long = 30_000_000_000L,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private data class Key(val queueId: Int, val digest: String)
    private class Pending(val count: Int, val createdAt: Long) {
        val pieces = HashMap<Int, ByteArray>()
        var bytes = 0
    }
    private val pending = LinkedHashMap<Key, Pending>()
    private var bufferedBytes = 0
    val pendingMessageCount: Int get() = pending.size
    val bufferedByteCount: Int get() = bufferedBytes

    init {
        require(maxMessageBytes in 1..ProtocolLimits.MAX_MESSAGE_BYTES)
        require(maxPendingMessages > 0 && maxBufferedBytes > 0 && maxPiecesPerMessage > 0 && assemblyTimeoutNanos > 0)
    }

    fun accept(pieceBytes: ByteArray): ByteArray? {
        protocolCheck(expire() == 0, "Incomplete MessagePiece assembly expired")
        val piece = MessagePieces.decode(pieceBytes)
        protocolCheck(piece.pieceCount <= maxPiecesPerMessage, "Incoming fragment-count budget exceeded")
        protocolCheck(piece.fragment.size <= maxMessageBytes, "Message size budget exceeded")
        if (piece.pieceCount == 1) {
            protocolCheck(MessagePieces.digest(piece.fragment) == piece.digest, "MessagePiece digest mismatch")
            return piece.fragment.copyOf()
        }
        val key = Key(piece.queueId, piece.digest)
        val assembly = pending[key] ?: run {
            protocolCheck(pending.size < maxPendingMessages, "Pending-message budget exceeded")
            Pending(piece.pieceCount, nowNanos()).also { pending[key] = it }
        }
        try {
            protocolCheck(assembly.count == piece.pieceCount, "Conflicting MessagePiece count")
            val previous = assembly.pieces[piece.pieceIndex]
            if (previous != null) {
                protocolCheck(previous.contentEquals(piece.fragment), "Conflicting duplicate MessagePiece")
                return null
            }
            protocolCheck(piece.fragment.size <= maxMessageBytes - assembly.bytes, "Message size budget exceeded")
            protocolCheck(piece.fragment.size <= maxBufferedBytes - bufferedBytes, "Buffered-message budget exceeded")
            assembly.pieces[piece.pieceIndex] = piece.fragment.copyOf()
            assembly.bytes += piece.fragment.size
            bufferedBytes += piece.fragment.size
            if (assembly.pieces.size != assembly.count) return null
            val message = ByteArray(assembly.bytes)
            var offset = 0
            for (index in 1..assembly.count) {
                val fragment = assembly.pieces.getValue(index)
                fragment.copyInto(message, offset)
                offset += fragment.size
            }
            protocolCheck(MessagePieces.digest(message) == piece.digest, "MessagePiece digest mismatch")
            discard(key)
            return message
        } catch (error: Exception) {
            discard(key)
            throw error
        }
    }

    /** Drops incomplete messages whose full assembly deadline elapsed. No bytes are emitted. */
    fun expire(): Int {
        val now = nowNanos()
        val expired = pending.filterValues { now - it.createdAt >= assemblyTimeoutNanos }.keys.toList()
        expired.forEach(::discard)
        return expired.size
    }

    fun finish() {
        val incomplete = pending.isNotEmpty()
        reset()
        protocolCheck(!incomplete, "Incomplete MessagePiece assembly at EOF")
    }

    fun reset() {
        pending.clear()
        bufferedBytes = 0
    }

    private fun discard(key: Key) {
        val removed = pending.remove(key) ?: return
        bufferedBytes -= removed.bytes
    }
}
