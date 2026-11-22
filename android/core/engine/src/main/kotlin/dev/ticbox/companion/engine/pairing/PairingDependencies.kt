package dev.ticbox.companion.engine.pairing

import dev.ticbox.companion.api.*
import kotlinx.coroutines.flow.StateFlow

/** Real platform boundaries. Each suspend operation must release its callbacks on cancellation. */
interface PairingPlatform : AutoCloseable {
    val actions: StateFlow<List<PairingUserAction>>
    suspend fun prepare(operationId: Long)
    /** Captures bonds before opening a chooser, and observes changes until this operation ends. */
    suspend fun captureBonds(operationId: Long): Set<PeerAddress>
    suspend fun associations(): List<PairingAssociation>
    suspend fun associate(operationId: Long, options: PairingOptions): PairingAssociation
    /** True only after removal is observed. False keeps a bond changed by the system meanwhile. */
    suspend fun clearBond(operationId: Long, association: PairingAssociation): Boolean
    suspend fun ensureBond(operationId: Long, peer: PeerAddress)
    suspend fun permissionConsent(operationId: Long, association: PairingAssociation): PairingConsent
    suspend fun transferPermissions(association: PairingAssociation, channel: WearChannel, timeoutMillis: Long)
    fun cancel(operationId: Long)
    override fun close()
}

interface AssociationMappingStore {
    suspend fun all(): List<AssociationMapping>
    suspend fun put(mapping: AssociationMapping)
    /** Only forgets an app mapping whose system association has already disappeared. */
    suspend fun remove(associationId: Int)
}

/** The app/service controls peer selection, proxy startup and the physical connection owner. */
interface PairingLink {
    val state: StateFlow<ConnectionState>
    suspend fun connectAndAwaitReady(peer: PeerAddress): ConnectionState
    suspend fun openPermissionChannel(peer: PeerAddress, sessionId: Long): WearChannel
}

data class PairingConfig(
    val preparationTimeoutMillis: Long = 180_000,
    val associationTimeoutMillis: Long = 180_000,
    val bondTimeoutMillis: Long = 120_000,
    val connectionTimeoutMillis: Long = 90_000,
    val consentTimeoutMillis: Long = 180_000,
    val transferTimeoutMillis: Long = 45_000,
) {
    init { require(listOf(preparationTimeoutMillis, associationTimeoutMillis, bondTimeoutMillis, connectionTimeoutMillis, consentTimeoutMillis, transferTimeoutMillis).all { it > 0 }) }
}
