package dev.ticbox.companion.platform.pairing

import dev.ticbox.companion.api.PairingAssociation
import dev.ticbox.companion.api.PairingFailure
import dev.ticbox.companion.api.PairingFailureKind
import dev.ticbox.companion.api.PairingWorkflowException
import dev.ticbox.companion.api.PeerAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidPairingBondsTest {
    private val peer = PeerAddress("01:23:45:67:89:AB")
    private val other = PeerAddress("11:23:45:67:89:AB")
    private val association = PairingAssociation(42, peer)

    @Test fun snapshotObserverExistsBeforeReadingAndPreservesChangesDuringThatRead() = runTest {
        val platform = FakePlatform(peer to BondState.BONDED)
        platform.duringSnapshot = {
            assertEquals(1, platform.listeners.size)
            platform.emit(BondSignal.State(peer, BondState.BONDED, BondState.BONDING))
        }
        val bonds = AndroidPairingBonds(platform)
        assertEquals(setOf(peer), bonds.capture(1))
        assertFalse(bonds.clear(1, association))
        assertTrue(platform.removals.isEmpty())
        bonds.ensure(1, peer)
        assertTrue(platform.creations.isEmpty())
        bonds.close()
    }

    @Test fun captureIsIdempotentAndNormalEnsureKeepsAnExistingBond() = runTest {
        val platform = FakePlatform(peer to BondState.BONDED)
        val bonds = AndroidPairingBonds(platform)
        assertEquals(bonds.capture(1), bonds.capture(1))
        assertEquals(1, platform.snapshotReads)
        assertEquals(1, platform.listeners.size)
        bonds.ensure(1, peer)
        assertTrue(platform.removals.isEmpty())
        assertTrue(platform.creations.isEmpty())
        bonds.cancel(1)
        assertEquals(1, platform.observersClosed)
    }

    @Test fun acceptedRemovalWaitsForNoneAndOnlyThenCanBondBeCreatedAgain() = runTest {
        val platform = FakePlatform(peer to BondState.BONDED)
        val bonds = AndroidPairingBonds(platform)
        bonds.capture(1)
        val removal = async { bonds.clear(1, association) }
        runCurrent()
        assertEquals(listOf(association), platform.removals)
        assertFalse(removal.isCompleted)
        assertTrue(platform.creations.isEmpty())
        platform.stateChanged(peer, BondState.NONE, BondState.BONDED)
        assertTrue(removal.await())
        val newBond = async { bonds.ensure(1, peer) }
        runCurrent()
        assertEquals(listOf(peer), platform.creations)
        assertFalse(newBond.isCompleted)
        platform.stateChanged(peer, BondState.BONDED, BondState.BONDING)
        newBond.await()
        bonds.close()
    }

    @Test fun alreadyRemovedBondNeedsNoSecondRemoveRequest() = runTest {
        val platform = FakePlatform(peer to BondState.BONDED)
        val bonds = AndroidPairingBonds(platform)
        bonds.capture(1)
        platform.stateChanged(peer, BondState.NONE, BondState.BONDED)
        assertTrue(bonds.clear(1, association))
        assertTrue(platform.removals.isEmpty())
        bonds.close()
    }

    @Test fun peerAbsentFromSnapshotIsNeverRemovedEvenIfBondAppearsLater() = runTest {
        val platform = FakePlatform()
        val bonds = AndroidPairingBonds(platform)
        bonds.capture(1)
        platform.states[peer] = BondState.BONDED
        assertFalse(bonds.clear(1, association))
        assertTrue(platform.removals.isEmpty())
        bonds.close()
    }

    @Test fun systemRepairAndSuccessfulBredrEncryptionAreConservativelyPreserved() = runTest {
        for (signal in listOf(BondSignal.Pairing(peer, true), BondSignal.Encrypted(peer))) {
            val platform = FakePlatform(peer to BondState.BONDED)
            val bonds = AndroidPairingBonds(platform)
            bonds.capture(1)
            platform.emit(signal)
            assertFalse(bonds.clear(1, association))
            assertTrue(platform.removals.isEmpty())
            bonds.close()
        }
    }

    @Test fun keyMissingSupportsDiagnosticsWithoutAutomaticallyDeletingOrConfirmingReset() = runTest {
        val platform = FakePlatform(peer to BondState.BONDED)
        val bonds = AndroidPairingBonds(platform)
        bonds.capture(1)
        platform.emit(BondSignal.KeyMissing(peer, 1))
        bonds.ensure(1, peer)
        assertTrue(platform.removals.isEmpty())
        assertTrue(platform.creations.isEmpty())
        bonds.close()
    }

    @Test fun pairingAlreadyInProgressIsNotRemovedAndEnsureWaitsForItsResult() = runTest {
        val platform = FakePlatform(peer to BondState.BONDED)
        val bonds = AndroidPairingBonds(platform)
        bonds.capture(1)
        platform.states[peer] = BondState.BONDING
        assertFalse(bonds.clear(1, association))
        val ensure = async { bonds.ensure(1, peer) }
        runCurrent()
        assertTrue(platform.creations.isEmpty())
        assertFalse(ensure.isCompleted)
        platform.stateChanged(peer, BondState.BONDED, BondState.BONDING)
        ensure.await()
        bonds.close()
    }

    @Test fun unsupportedPublicRemovalUsesSettingsAndChecksActualState() = runTest {
        val platform = FakePlatform(peer to BondState.BONDED).apply { removalAccepted = null }
        val bonds = AndroidPairingBonds(platform)
        bonds.capture(1)
        val removal = async { bonds.clear(1, association) }
        runCurrent()
        assertEquals(listOf(1L to peer), platform.settingsRequests)
        assertFalse(removal.isCompleted)
        platform.stateChanged(peer, BondState.NONE, BondState.BONDED)
        platform.settingsAnswer.complete(Unit)
        assertTrue(removal.await())
        bonds.close()
    }

    @Test fun rejectedPublicRemovalAlsoFallsBackToSettings() = runTest {
        val platform = FakePlatform(peer to BondState.BONDED).apply { removalAccepted = false }
        val bonds = AndroidPairingBonds(platform)
        bonds.capture(1)
        val removal = async { bonds.clear(1, association) }
        runCurrent()
        assertEquals(1, platform.settingsRequests.size)
        platform.stateChanged(peer, BondState.NONE, BondState.BONDED)
        platform.settingsAnswer.complete(Unit)
        assertTrue(removal.await())
        bonds.close()
    }

    @Test fun returningFromSettingsWithoutForgettingIsAnExplicitFailure() = runTest {
        val platform = FakePlatform(peer to BondState.BONDED).apply {
            removalAccepted = null
            settingsAnswer.complete(Unit)
        }
        val bonds = AndroidPairingBonds(platform)
        bonds.capture(1)
        val failure = assertFailsWith<PairingWorkflowException> { bonds.clear(1, association) }
        assertEquals(PairingFailureKind.BOND, failure.failure.kind)
        assertEquals("bluetooth_device_not_forgotten", failure.failure.reason)
        assertTrue(platform.creations.isEmpty())
        bonds.close()
    }

    @Test fun turningBluetoothOffCannotMasqueradeAsSuccessfulForgetting() = runTest {
        val platform = FakePlatform(peer to BondState.BONDED).apply { removalAccepted = null }
        val bonds = AndroidPairingBonds(platform)
        bonds.capture(1)
        val result = async {
            assertFailsWith<PairingWorkflowException> { bonds.clear(1, association) }
        }
        runCurrent()
        platform.readyFailure = PairingWorkflowException(PairingFailure(PairingFailureKind.BOND, "bluetooth_disabled_during_bond"))
        platform.states[peer] = BondState.NONE // Android can return NONE while its adapter is disabled.
        platform.settingsAnswer.complete(Unit)
        assertEquals("bluetooth_disabled_during_bond", result.await().failure.reason)
        assertTrue(platform.creations.isEmpty())
        bonds.close()
    }

    @Test fun adapterBecomingUnavailableDuringStateReadDoesNotConfirmNone() = runTest {
        val platform = FakePlatform(peer to BondState.BONDED)
        val bonds = AndroidPairingBonds(platform)
        bonds.capture(1)
        platform.states[peer] = BondState.NONE
        platform.duringStateRead = {
            platform.readyFailure = PairingWorkflowException(PairingFailure(PairingFailureKind.BOND, "bluetooth_disabled_during_bond"))
        }
        assertEquals("bluetooth_disabled_during_bond",
            assertFailsWith<PairingWorkflowException> { bonds.clear(1, association) }.failure.reason)
        assertTrue(platform.removals.isEmpty())
        bonds.close()
    }

    @Test fun apiRejectionWithConnectStillGrantedMayUseSettingsButRevocationCannot() = runTest {
        val platform = FakePlatform(peer to BondState.BONDED).apply {
            removalFailure = SecurityException("system rejected request")
        }
        val bonds = AndroidPairingBonds(platform)
        bonds.capture(1)
        val result = async { bonds.clear(1, association) }
        runCurrent()
        assertEquals(1, platform.settingsRequests.size)
        platform.stateChanged(peer, BondState.NONE, BondState.BONDED)
        platform.settingsAnswer.complete(Unit)
        assertTrue(result.await())
        bonds.close()

        val revoked = FakePlatform(peer to BondState.BONDED)
        val second = AndroidPairingBonds(revoked)
        second.capture(2)
        revoked.readyFailure = SecurityException("permission revoked")
        assertEquals(PairingFailureKind.PERMISSION_DENIED,
            assertFailsWith<PairingWorkflowException> { second.clear(2, association) }.failure.kind)
        assertTrue(revoked.settingsRequests.isEmpty())
        second.close()
    }

    @Test fun anotherDevicesBondChangesNeitherClearNorCompleteTheSelectedPeersRemoval() = runTest {
        val platform = FakePlatform(peer to BondState.BONDED)
        val bonds = AndroidPairingBonds(platform)
        bonds.capture(1)
        platform.stateChanged(other, BondState.BONDED, BondState.BONDING)
        val removal = async { bonds.clear(1, association) }
        runCurrent()
        assertEquals(listOf(association), platform.removals)
        platform.stateChanged(other, BondState.NONE, BondState.BONDED)
        runCurrent()
        assertFalse(removal.isCompleted)
        platform.stateChanged(peer, BondState.NONE, BondState.BONDED)
        assertTrue(removal.await())
        bonds.close()
    }

    @Test fun cancelledOperationReleasesItsObserverAndPendingSettingsOnly() = runTest {
        val platform = FakePlatform(peer to BondState.BONDED).apply { removalAccepted = null }
        val bonds = AndroidPairingBonds(platform)
        bonds.capture(1)
        bonds.capture(2)
        val result = async { bonds.clear(1, association) }
        runCurrent()
        bonds.cancel(1)
        assertFailsWith<CancellationException> { result.await() }
        assertEquals(listOf(1L), platform.cancelledUi)
        assertEquals(1, platform.listeners.size)
        bonds.ensure(2, peer)
        bonds.close()
        assertEquals(2, platform.observersClosed)
    }

    @Test fun lateCallbacksFromCancelledOperationCannotCompleteItsReplacement() = runTest {
        val platform = FakePlatform()
        val bonds = AndroidPairingBonds(platform)
        bonds.capture(1)
        val late = platform.listeners.single()
        val first = async { bonds.ensure(1, peer) }
        runCurrent()
        bonds.cancel(1)
        assertFailsWith<CancellationException> { first.await() }
        bonds.capture(2)
        val second = async { bonds.ensure(2, peer) }
        runCurrent()
        late(BondSignal.State(peer, BondState.BONDED, BondState.BONDING))
        platform.stateChanged(other, BondState.BONDED, BondState.BONDING)
        runCurrent()
        assertFalse(second.isCompleted)
        platform.stateChanged(peer, BondState.BONDED, BondState.BONDING)
        second.await()
        bonds.close()
    }

    @Test fun callerTimeoutCancelsBondObserverAndDoesNotKeepWaiting() = runTest {
        val platform = FakePlatform()
        val bonds = AndroidPairingBonds(platform)
        bonds.capture(1)
        assertFailsWith<CancellationException> {
            withTimeout(100) { bonds.ensure(1, peer) }
        }
        assertTrue(platform.listeners.isEmpty())
        assertEquals(1, platform.observersClosed)
        assertFailsWith<CancellationException> { bonds.ensure(1, peer) }
        bonds.close()
    }

    @Test fun rejectedCreateAndFailedBondAreDistinctFromAnAcceptedPendingRequest() = runTest {
        val platform = FakePlatform().apply { creationAccepted = false }
        val bonds = AndroidPairingBonds(platform)
        bonds.capture(1)
        assertEquals("system_bond_request_rejected",
            assertFailsWith<PairingWorkflowException> { bonds.ensure(1, peer) }.failure.reason)
        platform.creationAccepted = true
        val result = async { assertFailsWith<PairingWorkflowException> { bonds.ensure(1, peer) } }
        runCurrent()
        platform.stateChanged(peer, BondState.NONE, BondState.BONDING)
        assertEquals("system_bond_failed_or_declined", result.await().failure.reason)
        bonds.close()
    }

    @Test fun associationMismatchNeverFallsBackToDeletingAnArbitraryDevice() = runTest {
        val platform = FakePlatform(peer to BondState.BONDED).apply {
            removalFailure = PairingWorkflowException(PairingFailure(PairingFailureKind.ASSOCIATION, "association_device_changed"))
        }
        val bonds = AndroidPairingBonds(platform)
        bonds.capture(1)
        val problem = assertFailsWith<PairingWorkflowException> { bonds.clear(1, association) }
        assertEquals(PairingFailureKind.ASSOCIATION, problem.failure.kind)
        assertTrue(platform.settingsRequests.isEmpty())
        bonds.close()
    }

    @Test fun snapshotFailureAndCloseReleaseTheirOwnedObserversExactlyOnce() = runTest {
        val broken = FakePlatform().apply { duringSnapshot = { throw IllegalStateException("unavailable") } }
        val first = AndroidPairingBonds(broken)
        assertFailsWith<PairingWorkflowException> { first.capture(1) }
        first.close()
        assertEquals(1, broken.observersClosed)
        assertTrue(broken.listeners.isEmpty())
        val platform = FakePlatform()
        val bonds = AndroidPairingBonds(platform)
        bonds.capture(1)
        val wait = async { bonds.ensure(1, peer) }
        runCurrent()
        bonds.close()
        bonds.close()
        assertFailsWith<CancellationException> { wait.await() }
        assertEquals(1, platform.observersClosed)
        assertTrue(platform.listeners.isEmpty())
        assertFailsWith<PairingWorkflowException> { bonds.capture(2) }
    }

    private class FakePlatform(vararg initial: Pair<PeerAddress, BondState>) : PairingBondPlatform {
        val states = initial.toMap().toMutableMap()
        val listeners = mutableListOf<(BondSignal) -> Unit>()
        val removals = mutableListOf<PairingAssociation>()
        val creations = mutableListOf<PeerAddress>()
        val settingsRequests = mutableListOf<Pair<Long, PeerAddress>>()
        val cancelledUi = mutableListOf<Long>()
        val settingsAnswer = CompletableDeferred<Unit>()
        var observersClosed = 0
        var snapshotReads = 0
        var duringSnapshot: (() -> Unit)? = null
        var duringStateRead: (() -> Unit)? = null
        var removalAccepted: Boolean? = true
        var creationAccepted = true
        var readyFailure: Exception? = null
        var removalFailure: Exception? = null

        override fun ready() { readyFailure?.let { throw it } }
        override fun observe(listener: (BondSignal) -> Unit): AutoCloseable {
            listeners += listener
            return AutoCloseable { if (listeners.remove(listener)) observersClosed += 1 }
        }
        override fun bondedPeers(): Set<PeerAddress> {
            snapshotReads += 1
            duringSnapshot?.invoke()
            return states.filterValues { it == BondState.BONDED }.keys.toSet()
        }
        override fun state(peer: PeerAddress): BondState {
            val value = states[peer] ?: BondState.NONE
            duringStateRead?.invoke()
            return value
        }
        override fun createBond(peer: PeerAddress): Boolean {
            creations += peer
            if (creationAccepted) states[peer] = BondState.BONDING
            return creationAccepted
        }
        override fun removeBond(association: PairingAssociation): Boolean? {
            removals += association
            removalFailure?.let { throw it }
            return removalAccepted
        }
        override suspend fun forgetInSettings(operationId: Long, peer: PeerAddress) {
            settingsRequests += operationId to peer
            settingsAnswer.await()
        }
        override fun cancelUi(operationId: Long) {
            cancelledUi += operationId
            if (settingsRequests.any { it.first == operationId }) settingsAnswer.cancel()
        }
        fun emit(signal: BondSignal) { listeners.toList().forEach { it(signal) } }
        fun stateChanged(peer: PeerAddress, current: BondState, previous: BondState) {
            states[peer] = current
            emit(BondSignal.State(peer, current, previous))
        }
    }
}
