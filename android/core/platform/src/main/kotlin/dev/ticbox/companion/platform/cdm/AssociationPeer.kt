package dev.ticbox.companion.platform.cdm

import dev.ticbox.companion.api.PeerAddress

internal enum class AssociationPeerBinding { MATCHED, UNAVAILABLE }

/** Known mismatches are rejected; unavailable platform metadata is reported to the coordinator. */
internal fun requireAssociationMatchesPeer(associationAddress: String?, peer: PeerAddress): AssociationPeerBinding {
    val associatedPeer = try {
        associationAddress?.let(::PeerAddress)
    } catch (_: IllegalArgumentException) {
        null
    }
    if (associatedPeer == null) return AssociationPeerBinding.UNAVAILABLE
    if (associatedPeer != peer) {
        throw SecurityException("Permission transport requires an association for the same Bluetooth device")
    }
    return AssociationPeerBinding.MATCHED
}
