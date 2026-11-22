package dev.ticbox.companion.engine.pairing

import dev.ticbox.companion.api.*
import java.io.IOException
import kotlin.test.*

class AssociationMappingDocumentCodecTest {
    @Test fun mappingsRoundTripAndOldOrTruncatedFilesNeverInventAssociations() {
        val values = listOf(AssociationMapping(3, PeerAddress("01:23:45:67:89:AB"), true, 100))
        val document = AssociationMappingDocumentCodec.encode(values)
        assertEquals(values, AssociationMappingDocumentCodec.decode(document))
        for (size in listOf(0, 4, 8, document.size - 1)) assertFailsWith<IOException> { AssociationMappingDocumentCodec.decode(document.copyOf(size)) }
        assertFailsWith<IOException> { AssociationMappingDocumentCodec.decode(document + byteArrayOf(0)) }
    }
}
