package dev.ticbox.companion.platform.identity

import dev.ticbox.companion.api.NodeIdentity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest

/** App-owned persistence format. The checksum detects damage; it is not an authentication token. */
internal object IdentityDocumentCodec {
    const val MAX_DOCUMENT_BYTES = 4_096
    private const val MAGIC = 0x54574944 // TWID
    private const val VERSION = 1
    private const val DIGEST_BYTES = 32

    fun encode(identity: NodeIdentity): ByteArray {
        val body = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { data ->
                data.writeUTF(identity.nodeId)
                data.writeUTF(identity.networkId)
                data.writeUTF(identity.nodeName)
                data.writeBoolean(identity.androidId != null)
                identity.androidId?.let(data::writeLong)
            }
        }.toByteArray()
        val document = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { data ->
                data.writeInt(MAGIC)
                data.writeInt(VERSION)
                data.writeInt(body.size)
                data.write(body)
                data.write(MessageDigest.getInstance("SHA-256").digest(body))
            }
        }.toByteArray()
        require(document.size <= MAX_DOCUMENT_BYTES) { "Identity document exceeds the local limit" }
        return document
    }

    fun decode(document: ByteArray): NodeIdentity {
        if (document.size !in (12 + DIGEST_BYTES)..MAX_DOCUMENT_BYTES) {
            throw IOException("Invalid identity document size")
        }
        try {
            val data = DataInputStream(ByteArrayInputStream(document))
            if (data.readInt() != MAGIC || data.readInt() != VERSION) {
                throw IOException("Unrecognized identity document version")
            }
            val size = data.readInt()
            if (size < 0 || size != document.size - 12 - DIGEST_BYTES) {
                throw IOException("Invalid identity body length")
            }
            val body = ByteArray(size).also(data::readFully)
            val digest = ByteArray(DIGEST_BYTES).also(data::readFully)
            if (!MessageDigest.isEqual(digest, MessageDigest.getInstance("SHA-256").digest(body))) {
                throw IOException("Identity document checksum mismatch")
            }
            val decoded = DataInputStream(ByteArrayInputStream(body))
            val nodeId = decoded.readUTF()
            val networkId = decoded.readUTF()
            val nodeName = decoded.readUTF()
            val androidId = when (decoded.readUnsignedByte()) {
                0 -> null
                1 -> decoded.readLong()
                else -> throw IOException("Invalid Android ID presence byte")
            }
            if (decoded.available() != 0) throw IOException("Trailing identity document fields")
            return NodeIdentity(nodeId, networkId, nodeName, androidId)
        } catch (failure: IllegalArgumentException) {
            throw IOException("Invalid persisted identity", failure)
        }
    }
}
