package dev.ticbox.companion.platform.cdm

import dev.ticbox.companion.api.PeerAddress
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals

class AssociationPeerTest {
    private val peer = PeerAddress("AA:BB:CC:DD:EE:FF")

    @Test fun anOwnedAssociationMayUseTheSameAddressWithDifferentCase() {
        requireAssociationMatchesPeer("aa:bb:cc:dd:ee:ff", peer)
    }

    @Test fun anAssociationForAnotherWatchCannotCarryThisWatchsSystemBytes() {
        assertFailsWith<SecurityException> { requireAssociationMatchesPeer("00:11:22:33:44:55", peer) }
    }

    @Test fun unavailableAddressMetadataDoesNotPretendToProveAMatchOrRejectTheAssociation() {
        for (value in listOf(null, "", "unknown", "AA:BB:CC:DD:EE")) {
            assertEquals(AssociationPeerBinding.UNAVAILABLE, requireAssociationMatchesPeer(value, peer))
        }
    }
}
