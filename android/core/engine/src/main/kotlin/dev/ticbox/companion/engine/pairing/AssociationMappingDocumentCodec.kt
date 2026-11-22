package dev.ticbox.companion.engine.pairing

import dev.ticbox.companion.api.AssociationMapping
import dev.ticbox.companion.api.PeerAddress
import java.io.*

/** App-local metadata only. System myAssociations must still confirm ownership on each operation. */
object AssociationMappingDocumentCodec {
    const val MAX_DOCUMENT_BYTES = 256 * 1024
    private const val MAGIC = 0x54414344
    fun encode(mappings: List<AssociationMapping>): ByteArray {
        require(mappings.size <= 4096)
        require(mappings.all { it.associationId > 0 } && mappings.map { it.associationId }.distinct().size == mappings.size)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { writer ->
            writer.writeInt(MAGIC); writer.writeInt(1); writer.writeInt(mappings.size)
            for (mapping in mappings.sortedBy { it.associationId }) {
                writer.writeInt(mapping.associationId)
                writer.writeUTF(mapping.peer.value)
                writer.writeBoolean(mapping.watchProfile)
                writer.writeLong(mapping.observedAtMillis)
            }
        }
        return output.toByteArray().also { require(it.size <= MAX_DOCUMENT_BYTES) }
    }
    fun decode(bytes: ByteArray): List<AssociationMapping> {
        if (bytes.size > MAX_DOCUMENT_BYTES) throw IOException("Association mapping file exceeds local allocation budget")
        try {
            return DataInputStream(ByteArrayInputStream(bytes)).use { reader ->
                if (reader.readInt() != MAGIC || reader.readInt() != 1) throw IOException("Unknown association mapping document")
                val count = reader.readInt()
                if (count !in 0..4096) throw IOException("Invalid association mapping count")
                val mappings = List(count) { AssociationMapping(reader.readInt(), PeerAddress(reader.readUTF()), reader.readBoolean(), reader.readLong()) }
                if (mappings.any { it.associationId <= 0 } || mappings.map { it.associationId }.distinct().size != count || reader.read() != -1) {
                    throw IOException("Invalid association mapping document")
                }
                mappings
            }
        } catch (problem: IllegalArgumentException) { throw IOException("Invalid association mapping document", problem) }
    }
}
