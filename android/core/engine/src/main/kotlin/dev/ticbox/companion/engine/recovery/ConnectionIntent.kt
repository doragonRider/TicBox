package dev.ticbox.companion.engine.recovery

import dev.ticbox.companion.api.PeerAddress
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/** A null peer clears selection. A temporary disconnect keeps the peer with reconnect disabled. */
data class ConnectionIntent(val revision: String, val peer: PeerAddress?, val associationId: Int? = null,
    val reconnectEnabled: Boolean = true) {
    companion object {
        fun selected(peer: PeerAddress?, associationId: Int? = null) =
            ConnectionIntent(UUID.randomUUID().toString(), peer, associationId)
    }
}

interface ConnectionIntentStore {
    suspend fun read(): ConnectionIntent?
    suspend fun write(intent: ConnectionIntent)
}

/** Local app state, not a watch protocol decoder. Small bounded format kept out of Android backup. */
object ConnectionIntentCodec {
    const val MAX_BYTES = 512
    private const val MAGIC = 0x54435231
    fun encode(intent: ConnectionIntent): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeUTF(intent.revision)
            out.writeBoolean(intent.peer != null)
            intent.peer?.let { out.writeUTF(it.value) }
            out.writeBoolean(intent.associationId != null)
            intent.associationId?.let(out::writeInt)
            out.writeBoolean(intent.reconnectEnabled)
        }
    }.toByteArray().also { require(it.size <= MAX_BYTES) }

    fun decode(bytes: ByteArray): ConnectionIntent {
        require(bytes.size <= MAX_BYTES) { "Connection intent exceeds local document limit" }
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC) { "Unknown connection intent document" }
            val revision = input.readUTF()
            require(revision.isNotBlank())
            val peer = if (input.readBoolean()) PeerAddress(input.readUTF()) else null
            val association = if (input.readBoolean()) input.readInt() else null
            // The existing document has an optional trailing reconnect switch. Missing means
            // enabled; a null peer still never connects. Identity and database formats are unchanged.
            val reconnectEnabled = if (input.available() > 0) input.readBoolean() else true
            ConnectionIntent(revision, peer, association, reconnectEnabled)
        }
    }
}
