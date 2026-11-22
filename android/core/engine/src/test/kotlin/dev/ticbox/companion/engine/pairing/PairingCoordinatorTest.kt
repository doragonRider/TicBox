package dev.ticbox.companion.engine.pairing

import dev.ticbox.companion.api.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PairingCoordinatorTest {
    private val peer = PeerAddress("01:23:45:67:89:AB")
    private val other = PeerAddress("01:23:45:67:89:AC")
    private val config = PairingConfig(100, 100, 100, 100, 100, 100)

    @Test fun associationBondLinkConsentAndTransferHaveSeparateEvidence() = runTest {
        val platform = FakePlatform().apply { transferGate = CompletableDeferred() }
        val store = Store()
        val link = Link()
        val coordinator = PairingCoordinator(backgroundScope, platform, store, link, config = config)
        try {
            coordinator.start(PairingOptions(peer = peer))
            runCurrent()
            assertEquals(PairingPhase.TRANSFERRING_PERMISSIONS, coordinator.state.value.phase)
            assertTrue(coordinator.state.value.associationObserved)
            assertTrue(coordinator.state.value.bonded)
            assertEquals(71L, coordinator.state.value.linkSessionId)
            assertEquals(PairingPermissionTransfer.TRANSFERRING, coordinator.state.value.permissionTransfer)
            assertEquals(listOf("prepare", "associations", "associate", "bond", "consent", "transfer"), platform.calls)
            assertEquals(AssociationMapping(11, peer, true, store.values.single().observedAtMillis), store.values.single())
            platform.transferGate!!.complete(Unit)
            runCurrent()
            assertEquals(PairingPhase.COMPLETED, coordinator.state.value.phase)
            assertEquals(PairingPermissionTransfer.COMPLETED, coordinator.state.value.permissionTransfer)
            assertTrue(link.channels.single().closed)
        } finally { coordinator.close() }
    }

    @Test fun declinedPermissionConsentDoesNotOpenChannelOrClaimTransfer() = runTest {
        val platform = FakePlatform().apply { consent = PairingConsent.DECLINED }
        val link = Link()
        val coordinator = PairingCoordinator(backgroundScope, platform, Store(), link, config = config)
        try {
            coordinator.start(PairingOptions(peer = peer))
            runCurrent()
            assertEquals(PairingPhase.COMPLETED, coordinator.state.value.phase)
            assertEquals(PairingPermissionTransfer.DECLINED, coordinator.state.value.permissionTransfer)
            assertTrue(coordinator.state.value.bonded)
            assertTrue(link.channels.isEmpty())
            assertFalse(platform.calls.contains("transfer"))
        } finally { coordinator.close() }
    }

    @Test fun ownedAddresslessAssociationReusesOnlyItsPersistedBindingAndRemovesStaleMappings() = runTest {
        val platform = FakePlatform().apply { owned = listOf(PairingAssociation(11, null, watchProfile = true)) }
        val store = Store().apply { values += AssociationMapping(11, peer, true, 1); values += AssociationMapping(9, other, true, 1) }
        val coordinator = PairingCoordinator(backgroundScope, platform, store, Link(), config = config)
        try {
            coordinator.start(PairingOptions(peer = peer, syncPermissions = false))
            runCurrent()
            assertEquals(PairingPhase.COMPLETED, coordinator.state.value.phase)
            assertFalse(platform.calls.contains("associate"))
            assertEquals(listOf(11), store.values.map { it.associationId })
            assertEquals(PairingPermissionTransfer.NOT_REQUESTED, coordinator.state.value.permissionTransfer)
        } finally { coordinator.close() }
    }

    @Test fun ordinaryAssociationDoesNotSilentlySatisfyRequestedWatchProfile() = runTest {
        val platform = FakePlatform().apply {
            owned = listOf(PairingAssociation(9, peer, watchProfile = false))
            selected = PairingAssociation(11, peer, watchProfile = false)
        }
        val coordinator = PairingCoordinator(backgroundScope, platform, Store(), Link(), config = config)
        try {
            coordinator.start(PairingOptions(peer = peer))
            runCurrent()
            assertEquals(PairingPhase.FAILED, coordinator.state.value.phase)
            assertEquals("watch_profile_not_granted", coordinator.state.value.failure?.reason)
            assertTrue(platform.calls.contains("associate"))
            assertFalse(platform.calls.contains("bond"))
        } finally { coordinator.close() }
    }

    @Test fun associationForDifferentDeviceCannotBecomeBondOrPermissionTransfer() = runTest {
        val platform = FakePlatform().apply { selected = PairingAssociation(11, other, watchProfile = true) }
        val coordinator = PairingCoordinator(backgroundScope, platform, Store(), Link(), config = config)
        try {
            coordinator.start(PairingOptions(peer = peer))
            runCurrent()
            assertEquals("association_selected_different_device", coordinator.state.value.failure?.reason)
            assertFalse(coordinator.state.value.bonded)
        } finally { coordinator.close() }
    }

    @Test fun disconnectClosesPermissionChannelAndCannotCompleteOnLateTransferCallback() = runTest {
        val platform = FakePlatform().apply { transferGate = CompletableDeferred() }
        val link = Link()
        val coordinator = PairingCoordinator(backgroundScope, platform, Store(), link, config = config)
        try {
            coordinator.start(PairingOptions(peer = peer))
            runCurrent()
            link.mutable.value = ConnectionState(ConnectionPhase.LINK_READY, peer, sessionId = 72)
            runCurrent()
            assertEquals(PairingPhase.FAILED, coordinator.state.value.phase)
            assertEquals(PairingFailureKind.LINK, coordinator.state.value.failure?.kind)
            assertTrue(link.channels.single().closed)
            platform.transferGate!!.complete(Unit)
            runCurrent()
            assertEquals(PairingPhase.FAILED, coordinator.state.value.phase)
        } finally { coordinator.close() }
    }

    @Test fun cancelCanRestartBeforeOldFinallyRunsAndIdenticalStartIsIdempotent() = runTest {
        val platform = FakePlatform().apply { associationGate = CompletableDeferred() }
        val coordinator = PairingCoordinator(backgroundScope, platform, Store(), Link(), config = config)
        try {
            val options = PairingOptions(peer = peer, syncPermissions = false)
            coordinator.start(options)
            runCurrent()
            val first = coordinator.state.value.operationId
            coordinator.start(options)
            assertEquals(first, coordinator.state.value.operationId)
            coordinator.cancel()
            platform.associationGate = null
            coordinator.start(options)
            assertFalse(coordinator.cancel(first))
            runCurrent()
            assertTrue(coordinator.state.value.operationId > first)
            assertEquals(PairingPhase.COMPLETED, coordinator.state.value.phase)
            assertTrue(first in platform.cancelled)
        } finally { coordinator.close() }
    }

    @Test fun unknownStoredConsentIsNotApprovalAndStillRequiresActualSystemTransferOutcome() = runTest {
        val platform = FakePlatform().apply { consent = PairingConsent.UNKNOWN_SYSTEM_STATE; transferGate = CompletableDeferred() }
        val link = Link()
        val coordinator = PairingCoordinator(backgroundScope, platform, Store(), link, config = config)
        try {
            coordinator.syncPermissions(peer)
            runCurrent()
            assertEquals(PairingConsent.UNKNOWN_SYSTEM_STATE, coordinator.state.value.permissionConsent)
            assertEquals(PairingPermissionTransfer.TRANSFERRING, coordinator.state.value.permissionTransfer)
            platform.transferGate!!.completeExceptionally(PairingWorkflowException(PairingFailure(PairingFailureKind.PERMISSION_DENIED, "stored_consent_denied")))
            runCurrent()
            assertEquals(PairingPhase.FAILED, coordinator.state.value.phase)
            assertEquals(PairingPermissionTransfer.FAILED, coordinator.state.value.permissionTransfer)
            assertEquals(PairingConsent.UNKNOWN_SYSTEM_STATE, coordinator.state.value.permissionConsent)
            assertTrue(link.channels.single().closed)
        } finally { coordinator.close() }
    }

    @Test fun timeoutCannotBeReportedAsPairingOrPermissionSuccess() = runTest {
        val platform = FakePlatform().apply { associationGate = CompletableDeferred() }
        val coordinator = PairingCoordinator(backgroundScope, platform, Store(), Link(), config = config)
        try {
            coordinator.start(PairingOptions(peer = peer))
            runCurrent()
            advanceTimeBy(101)
            runCurrent()
            assertEquals(PairingPhase.FAILED, coordinator.state.value.phase)
            assertEquals(PairingFailureKind.TIMEOUT, coordinator.state.value.failure?.kind)
            assertFalse(coordinator.state.value.associationObserved)
            assertFalse(coordinator.state.value.bonded)
        } finally { coordinator.close() }
    }

    @Test fun newPairingClearsOnlyTheSelectedPreExistingBondBeforeCreatingItsReplacement() = runTest {
        val platform = FakePlatform().apply {
            previousBonds = setOf(peer, other)
            clearGate = CompletableDeferred()
        }
        var stopped = 0
        val coordinator = PairingCoordinator(backgroundScope, platform, Store(), Link(), config = config,
            prepareNewPairing = { stopped++ })
        try {
            coordinator.start(PairingOptions(syncPermissions = false))
            runCurrent()
            assertEquals(1, stopped)
            assertEquals(PairingPhase.CLEARING_OLD_BOND, coordinator.state.value.phase)
            assertTrue(coordinator.state.value.preExistingBond)
            assertFalse(platform.calls.contains("bond"))
            assertEquals(listOf(peer), platform.cleared)
            platform.clearGate!!.complete(true)
            runCurrent()
            assertTrue(coordinator.state.value.oldBondCleared)
            assertTrue(coordinator.state.value.bonded)
            assertEquals(PairingPhase.COMPLETED, coordinator.state.value.phase)
            assertTrue(platform.calls.indexOf("clear_bond") < platform.calls.indexOf("bond"))
        } finally { coordinator.close() }
    }

    @Test fun permissionSyncKeepsExistingBondAndDoesNotStopTheCurrentLink() = runTest {
        val platform = FakePlatform().apply { previousBonds = setOf(peer) }
        val coordinator = PairingCoordinator(backgroundScope, platform, Store(), Link(), config = config,
            prepareNewPairing = { error("permission transfer must keep the existing link") })
        try {
            coordinator.syncPermissions(peer)
            runCurrent()
            assertTrue(platform.cleared.isEmpty())
            assertEquals(0, platform.captures)
            assertEquals(PairingPermissionTransfer.COMPLETED, coordinator.state.value.permissionTransfer)
        } finally { coordinator.close() }
    }

    @Test fun chooserCreatedOrSystemRepairedBondsAreNotReportedAsRemovedOldKeys() = runTest {
        val platform = FakePlatform()
        val coordinator = PairingCoordinator(backgroundScope, platform, Store(), Link(), config = config)
        try {
            coordinator.start(PairingOptions(syncPermissions = false))
            runCurrent()
            assertTrue(platform.cleared.isEmpty()) // Bond created after capture, e.g. by CDM.
            assertFalse(coordinator.state.value.preExistingBond)
            platform.previousBonds = setOf(peer)
            platform.clearResult = false // System changed this bond while the chooser was open.
            coordinator.start(PairingOptions(syncPermissions = false))
            runCurrent()
            assertTrue(coordinator.state.value.preExistingBond)
            assertFalse(coordinator.state.value.oldBondCleared)
            assertEquals(PairingPhase.COMPLETED, coordinator.state.value.phase)
        } finally { coordinator.close() }
    }

    @Test fun cancellingOldBondRemovalDoesNotStartNewBondFromItsLateCallback() = runTest {
        val platform = FakePlatform().apply { previousBonds = setOf(peer); clearGate = CompletableDeferred() }
        val coordinator = PairingCoordinator(backgroundScope, platform, Store(), Link(), config = config)
        try {
            val id = coordinator.start(PairingOptions(syncPermissions = false))
            runCurrent()
            assertEquals(PairingPhase.CLEARING_OLD_BOND, coordinator.state.value.phase)
            assertTrue(coordinator.cancel(id))
            platform.clearGate!!.complete(true)
            runCurrent()
            assertEquals(PairingPhase.CANCELLED, coordinator.state.value.phase)
            assertFalse(platform.calls.contains("bond"))
        } finally { coordinator.close() }
    }

    private inner class FakePlatform : PairingPlatform {
        override val actions = MutableStateFlow<List<PairingUserAction>>(emptyList())
        val calls = mutableListOf<String>()
        val cancelled = mutableListOf<Long>()
        var selected = PairingAssociation(11, peer, "TicWatch", true)
        var owned = emptyList<PairingAssociation>()
        var consent = PairingConsent.USER_APPROVED
        var associationGate: CompletableDeferred<PairingAssociation>? = null
        var transferGate: CompletableDeferred<Unit>? = null
        var previousBonds = emptySet<PeerAddress>()
        var captures = 0
        var clearResult = true
        var clearGate: CompletableDeferred<Boolean>? = null
        val cleared = mutableListOf<PeerAddress>()
        override suspend fun prepare(operationId: Long) { calls += "prepare" }
        override suspend fun captureBonds(operationId: Long): Set<PeerAddress> { captures++; return previousBonds }
        override suspend fun associations(): List<PairingAssociation> { calls += "associations"; return owned }
        override suspend fun associate(operationId: Long, options: PairingOptions): PairingAssociation { calls += "associate"; return associationGate?.await() ?: selected }
        override suspend fun clearBond(operationId: Long, association: PairingAssociation): Boolean {
            calls += "clear_bond"; cleared += association.peer!!; return clearGate?.await() ?: clearResult
        }
        override suspend fun ensureBond(operationId: Long, peer: PeerAddress) { calls += "bond" }
        override suspend fun permissionConsent(operationId: Long, association: PairingAssociation): PairingConsent { calls += "consent"; return consent }
        override suspend fun transferPermissions(association: PairingAssociation, channel: WearChannel, timeoutMillis: Long) { calls += "transfer"; transferGate?.await() }
        override fun cancel(operationId: Long) { cancelled += operationId }
        override fun close() = Unit
    }
    private class Store : AssociationMappingStore {
        val values = mutableListOf<AssociationMapping>()
        override suspend fun all() = values.toList()
        override suspend fun put(mapping: AssociationMapping) { values.removeAll { it.associationId == mapping.associationId }; values += mapping }
        override suspend fun remove(associationId: Int) { values.removeAll { it.associationId == associationId } }
    }
    private inner class Link : PairingLink {
        val mutable = MutableStateFlow(ConnectionState(ConnectionPhase.LINK_READY, peer, sessionId = 71))
        override val state = mutable.asStateFlow()
        val channels = mutableListOf<FakeChannel>()
        override suspend fun connectAndAwaitReady(peer: PeerAddress): ConnectionState = mutable.value
        override suspend fun openPermissionChannel(peer: PeerAddress, sessionId: Long): WearChannel = FakeChannel(ChannelInfo(peer, sessionId, 1uL, true, PairingCoordinator.PERMISSION_SYNC_PATH)).also { channels += it }
    }
    private class FakeChannel(override val info: ChannelInfo) : WearChannel {
        override val state = MutableStateFlow(ChannelState(ChannelPhase.OPEN))
        var closed = false
        override suspend fun read(maxBytes: Int): ByteArray? = awaitCancellation()
        override suspend fun write(bytes: ByteArray) = Unit
        override suspend fun finishOutput() = Unit
        override fun closeInput() = Unit
        override suspend fun closeGracefully(errorCode: Int) = close()
        override fun close() { closed = true }
    }
}
