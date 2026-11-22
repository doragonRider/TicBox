package dev.ticbox.companion.engine.pairing

import dev.ticbox.companion.api.*
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** CDM association, system bond, transport and permission transfer retain distinct outcomes. */
class PairingCoordinator(
    scope: CoroutineScope,
    private val platform: PairingPlatform,
    private val mappings: AssociationMappingStore,
    private val link: PairingLink,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val clock: Clock = Clock.systemUTC(),
    private val config: PairingConfig = PairingConfig(),
    /** Service-owned stop before allocating the new operation; cannot cancel that same operation. */
    private val prepareNewPairing: suspend () -> Unit = {},
) : PairingClient {
    private val ownerJob = SupervisorJob(scope.coroutineContext[Job])
    private val owner = CoroutineScope(scope.coroutineContext + ownerJob)
    private val lock = Any()
    private val starts = Mutex()
    private val ids = AtomicLong()
    private val mutableState = MutableStateFlow(PairingSnapshot())
    override val state = mutableState.asStateFlow()
    override val actions: StateFlow<List<PairingUserAction>> get() = platform.actions
    private var active: Operation? = null
    private var closed = false

    private class Operation(val id: Long, val options: PairingOptions, parent: CoroutineScope) {
        val job = SupervisorJob(parent.coroutineContext[Job])
        val scope = CoroutineScope(parent.coroutineContext + job)
        val channel = AtomicReference<WearChannel?>()
        var linkFailure: PairingFailure? = null
    }

    override suspend fun start(options: PairingOptions): Long = starts.withLock {
        synchronized(lock) {
            check(!closed && ownerJob.isActive) { "Pairing coordinator is closed" }
            active?.let {
                if (it.options == options && it.job.isActive) return@withLock it.id
                throw PairingBusyException()
            }
        }
        if (options.replaceExistingBond) prepareNewPairing()
        val operation = synchronized(lock) {
            check(!closed && ownerJob.isActive) { "Pairing coordinator is closed" }
            Operation(ids.incrementAndGet(), options.copy(), owner).also { operation ->
                active = operation
                mutableState.value = PairingSnapshot(operationId = operation.id, phase = PairingPhase.PREPARING, peer = options.peer)
                operation.job.invokeOnCompletion {
                    synchronized(lock) {
                        if (active === operation) {
                            if (mutableState.value.phase !in TERMINAL) {
                                mutableState.value = mutableState.value.copy(phase = PairingPhase.CANCELLED)
                            }
                            active = null
                        }
                    }
                }
            }
        }
        operation.scope.launch { execute(operation) }
        operation.id
    }

    override suspend fun syncPermissions(peer: PeerAddress) = start(PairingOptions(peer = peer, syncPermissions = true, replaceExistingBond = false))

    override fun cancel() {
        val id = synchronized(lock) { active?.id } ?: return
        cancel(id)
    }

    override fun cancel(operationId: Long): Boolean {
        val operation = synchronized(lock) {
            val current = active?.takeIf { it.id == operationId } ?: return false
            if (mutableState.value.phase !in TERMINAL) mutableState.value = mutableState.value.copy(phase = PairingPhase.CANCELLED,
                permissionTransfer = cancelledTransfer(mutableState.value.permissionTransfer))
            active = null
            current
        }
        platform.cancel(operation.id)
        operation.channel.getAndSet(null)?.close()
        operation.job.cancel(CancellationException("pairing_cancelled"))
        return true
    }

    override fun close() {
        synchronized(lock) { if (closed) return; closed = true }
        cancel()
        platform.close()
        ownerJob.cancel()
    }

    private suspend fun execute(operation: Operation) {
        try {
            within(config.preparationTimeoutMillis, "pairing_preparation_timeout") { platform.prepare(operation.id) }
            val previousBonds = if (operation.options.replaceExistingBond) platform.captureBonds(operation.id) else emptySet()
            phase(operation, PairingPhase.ASSOCIATING)
            val associations = platform.associations()
            val persisted = mappings.all()
            val ids = associations.map { it.id }.toSet()
            for (stale in persisted.filter { it.associationId !in ids }) mappings.remove(stale.associationId)
            val existing = if (operation.options.reuseAssociation && operation.options.peer != null) {
                associations.firstOrNull { association ->
                    val address = association.peer ?: persisted.firstOrNull { it.associationId == association.id }?.peer
                    address == operation.options.peer && (!operation.options.requestWatchProfile || association.watchProfile)
                }
            } else null
            var association = existing ?: within(config.associationTimeoutMillis, "association_timeout") { platform.associate(operation.id, operation.options) }
            val mappedPeer = if (association.peer == null && existing != null) persisted.firstOrNull { it.associationId == association.id }?.peer else null
            val peer = association.peer ?: mappedPeer ?: throw PairingWorkflowException(PairingFailure(PairingFailureKind.ASSOCIATION, "association_bluetooth_address_unavailable"))
            if (operation.options.peer != null && operation.options.peer != peer) {
                throw PairingWorkflowException(PairingFailure(PairingFailureKind.ASSOCIATION, "association_selected_different_device"))
            }
            if (operation.options.requestWatchProfile && !association.watchProfile) {
                throw PairingWorkflowException(PairingFailure(PairingFailureKind.ASSOCIATION, "watch_profile_not_granted"))
            }
            association = association.copy(peer = peer)
            mappings.put(AssociationMapping(association.id, peer, association.watchProfile, clock.millis()))
            update(operation) { it.copy(peer = peer, associationId = association.id, associationObserved = true,
                preExistingBond = peer in previousBonds) }
            log(operation, if (existing == null) "association_created" else "association_reused", mapOf("associationId" to association.id.toString()))

            if (operation.options.replaceExistingBond && peer in previousBonds) {
                phase(operation, PairingPhase.CLEARING_OLD_BOND)
                val cleared = within(config.bondTimeoutMillis, "old_bond_removal_timeout") { platform.clearBond(operation.id, association) }
                update(operation) { it.copy(oldBondCleared = cleared) }
            }
            phase(operation, PairingPhase.BONDING)
            within(config.bondTimeoutMillis, "bond_timeout") { platform.ensureBond(operation.id, peer) }
            update(operation) { it.copy(bonded = true) }
            phase(operation, PairingPhase.CONNECTING)
            val ready = within(config.connectionTimeoutMillis, "pairing_link_timeout") { link.connectAndAwaitReady(peer) }
            if (ready.phase != ConnectionPhase.LINK_READY || ready.peer != peer || ready.sessionId == 0L) {
                throw PairingWorkflowException(PairingFailure(PairingFailureKind.LINK, "pairing_link_not_ready"))
            }
            update(operation) { it.copy(linkSessionId = ready.sessionId) }
            if (operation.options.syncPermissions) transfer(operation, association, peer, ready.sessionId)
            phase(operation, PairingPhase.COMPLETED)
        } catch (cancelled: CancellationException) {
            val failure = synchronized(lock) { operation.linkFailure }
            if (failure == null) phase(operation, PairingPhase.CANCELLED) else fail(operation, failure)
        } catch (problem: PairingWorkflowException) {
            fail(operation, problem.failure)
        } catch (problem: Exception) {
            val kind = if (problem is SecurityException) PairingFailureKind.PERMISSION_DENIED else when (state.value.phase) {
                PairingPhase.ASSOCIATING -> PairingFailureKind.ASSOCIATION
                PairingPhase.CLEARING_OLD_BOND, PairingPhase.BONDING -> PairingFailureKind.BOND
                PairingPhase.CONNECTING -> PairingFailureKind.LINK
                PairingPhase.REQUESTING_PERMISSION_CONSENT, PairingPhase.TRANSFERRING_PERMISSIONS -> PairingFailureKind.PERMISSION_TRANSFER
                else -> PairingFailureKind.UNEXPECTED
            }
            fail(operation, PairingFailure(kind,
                "pairing_${problem.javaClass.simpleName}"))
        } finally {
            operation.channel.getAndSet(null)?.close()
            platform.cancel(operation.id)
            synchronized(lock) { if (active === operation) active = null }
            operation.job.cancel()
        }
    }

    private suspend fun transfer(operation: Operation, association: PairingAssociation, peer: PeerAddress, sessionId: Long) = coroutineScope {
        val watcher = launch {
            link.state.collect { current ->
                if (current.phase != ConnectionPhase.LINK_READY || current.peer != peer || current.sessionId != sessionId) {
                    synchronized(lock) { operation.linkFailure = PairingFailure(PairingFailureKind.LINK, "permission_transfer_session_ended") }
                    operation.channel.get()?.close()
                    operation.job.cancel(CancellationException("permission_transfer_session_ended"))
                }
            }
        }
        try {
            phase(operation, PairingPhase.REQUESTING_PERMISSION_CONSENT)
            update(operation) { it.copy(permissionTransfer = PairingPermissionTransfer.WAITING_FOR_CONSENT) }
            val consent = within(config.consentTimeoutMillis, "permission_consent_timeout") { platform.permissionConsent(operation.id, association) }
            update(operation) { it.copy(permissionConsent = consent) }
            if (consent == PairingConsent.DECLINED) {
                update(operation) { it.copy(permissionTransfer = PairingPermissionTransfer.DECLINED) }
                log(operation, "permission_transfer_declined")
                return@coroutineScope
            }
            if (consent == PairingConsent.NOT_REQUESTED) throw PairingWorkflowException(PairingFailure(PairingFailureKind.PERMISSION_TRANSFER, "permission_consent_not_requested"))
            val channel = within(config.connectionTimeoutMillis, "permission_channel_timeout") { link.openPermissionChannel(peer, sessionId) }
            operation.channel.set(channel)
            if (channel.info.peer != peer || channel.info.sessionId != sessionId || channel.info.path != PERMISSION_SYNC_PATH) {
                throw PairingWorkflowException(PairingFailure(PairingFailureKind.LINK, "permission_channel_session_mismatch"))
            }
            phase(operation, PairingPhase.TRANSFERRING_PERMISSIONS)
            update(operation) { it.copy(permissionTransfer = PairingPermissionTransfer.TRANSFERRING) }
            within(config.transferTimeoutMillis, "permission_transfer_timeout") { platform.transferPermissions(association, channel, config.transferTimeoutMillis) }
            update(operation) { it.copy(permissionTransfer = PairingPermissionTransfer.COMPLETED) }
            log(operation, "permission_transfer_platform_callback_completed")
        } finally {
            watcher.cancel()
            operation.channel.getAndSet(null)?.close()
        }
    }

    private suspend fun <T> within(timeout: Long, reason: String, block: suspend () -> T): T = try {
        withTimeout(timeout) { block() }
    } catch (_: TimeoutCancellationException) { throw PairingWorkflowException(PairingFailure(PairingFailureKind.TIMEOUT, reason)) }

    private fun phase(operation: Operation, phase: PairingPhase) {
        log(operation, "phase", mapOf("phase" to phase.name))
        update(operation) { it.copy(phase = phase, permissionTransfer = if (phase == PairingPhase.CANCELLED) cancelledTransfer(it.permissionTransfer) else it.permissionTransfer) }
    }
    private fun fail(operation: Operation, failure: PairingFailure) {
        log(operation, "failed", mapOf("kind" to failure.kind.name, "reason" to failure.reason))
        update(operation) { it.copy(phase = PairingPhase.FAILED, failure = failure,
            permissionTransfer = if (it.permissionTransfer in setOf(PairingPermissionTransfer.WAITING_FOR_CONSENT, PairingPermissionTransfer.TRANSFERRING)) PairingPermissionTransfer.FAILED else it.permissionTransfer) }
    }
    private fun update(operation: Operation, change: (PairingSnapshot) -> PairingSnapshot) = synchronized(lock) {
        if (active === operation) mutableState.value = change(mutableState.value)
    }
    private fun log(operation: Operation, event: String, details: Map<String, String> = emptyMap()) {
        val snapshot = state.value
        runCatching { diagnostics.record(ProtocolDiagnostic("pairing", event, DiagnosticSeverity.INFO,
            operation.options.peer ?: snapshot.peer, snapshot.linkSessionId ?: 0L, details = details + ("operationId" to operation.id.toString()))) }
    }
    private fun cancelledTransfer(value: PairingPermissionTransfer) = if (value in setOf(PairingPermissionTransfer.WAITING_FOR_CONSENT, PairingPermissionTransfer.TRANSFERRING)) PairingPermissionTransfer.CANCELLED else value
    companion object {
        const val PERMISSION_SYNC_PATH = "/cdm/channel/permission_sync"
        private val TERMINAL = setOf(PairingPhase.COMPLETED, PairingPhase.CANCELLED, PairingPhase.FAILED)
    }
}
