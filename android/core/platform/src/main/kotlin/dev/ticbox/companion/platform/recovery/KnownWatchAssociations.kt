package dev.ticbox.companion.platform.recovery

import dev.ticbox.companion.api.AssociationMapping
import dev.ticbox.companion.api.PairingAssociation
import dev.ticbox.companion.api.PeerAddress

/** A stored mapping only resolves an actually owned association; it never supplies ownership. */
internal fun knownWatchAssociations(
    owned: List<PairingAssociation>,
    mappings: List<AssociationMapping>,
    bonded: (PeerAddress) -> Boolean,
): List<PairingAssociation> = owned.mapNotNull { association ->
    val mapped = mappings.firstOrNull { it.associationId == association.id }
    val peer = association.peer ?: mapped?.peer ?: return@mapNotNull null
    val watch = association.watchProfile || mapped?.takeIf { it.peer == peer }?.watchProfile == true
    if (!watch || !bonded(peer)) null else association.copy(peer = peer, watchProfile = true)
}.distinctBy { it.peer }
