package dev.ticbox.companion.platform.recovery

import dev.ticbox.companion.api.AssociationMapping
import dev.ticbox.companion.api.PairingAssociation
import dev.ticbox.companion.api.PeerAddress
import kotlin.test.*

class KnownWatchAssociationsTest {
    private val watch = PeerAddress("01:23:45:67:89:AB")
    private val other = PeerAddress("01:23:45:67:89:CD")

    @Test fun onlyOwnedBondedWatchesAreOfferedAndMappingsDoNotInventOwnership() {
        val mappings = listOf(AssociationMapping(7, watch, true, 0), AssociationMapping(99, other, true, 0))
        val owned = listOf(PairingAssociation(7, null, "Watch"), PairingAssociation(8, other, "Headset"))
        val known = knownWatchAssociations(owned, mappings) { true }
        assertEquals(listOf(watch), known.map { it.peer })
        assertEquals(7, known.single().id)
        assertEquals("Watch", known.single().displayName)
        assertTrue(knownWatchAssociations(owned, mappings) { false }.isEmpty())
        assertTrue(knownWatchAssociations(emptyList(), mappings) { true }.isEmpty())
    }

    @Test fun currentSystemAddressWinsOverAnObsoleteMappingAndDuplicatesStayOneWatch() {
        val owned = listOf(PairingAssociation(7, watch, watchProfile = true), PairingAssociation(8, watch, watchProfile = true))
        val mappings = listOf(AssociationMapping(7, other, true, 0))
        assertEquals(listOf(watch), knownWatchAssociations(owned, mappings) { true }.map { it.peer })
    }
}
