package dev.ticbox.companion.platform.pairing

import dev.ticbox.companion.api.AssociationMapping
import dev.ticbox.companion.api.PairingAssociation
import dev.ticbox.companion.api.PairingFailure
import dev.ticbox.companion.api.PairingFailureKind
import dev.ticbox.companion.api.PairingWorkflowException
import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.engine.pairing.AssociationMappingStore
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
class AndroidWatchUnpairCleanupTest {
    private val peer = PeerAddress("01:23:45:67:89:AB")
    private val other = PeerAddress("11:23:45:67:89:AB")

    @Test fun bondIsRemovedBeforeAllSelectedOwnedAssociationsAndOnlyTheirMappings() = runTest {
        val platform = FakePlatform(peer to BondState.BONDED).apply {
            owned += listOf(PairingAssociation(42, peer), PairingAssociation(43, peer), PairingAssociation(44, other))
        }
        val mappings = FakeMappings(platform.events, row(42), row(43), row(99), row(44, other))
        AndroidWatchUnpairCleanup(platform, mappings).clear(peer)
        assertEquals(listOf(42), platform.removalRequests)
        assertEquals(listOf(42, 43), platform.disassociated)
        assertEquals(listOf(PairingAssociation(44, other)), platform.owned)
        assertEquals(listOf(row(44, other)), mappings.rows)
        assertTrue(platform.events.indexOf("remove:42") < platform.events.indexOf("disassociate:42"))
        assertTrue(platform.events.indexOf("disassociate:42") < platform.events.indexOf("mapping:42"))
        assertTrue(platform.events.indexOf("disassociate:43") < platform.events.indexOf("mapping:43"))
        assertEquals(1, platform.observersClosed)
        assertTrue(platform.listeners.isEmpty())
    }

    @Test fun withoutOwnedAssociationBondForgettingUsesSettingsInsteadOfCdmRemoval() = runTest {
        val platform = FakePlatform(peer to BondState.BONDED)
        val mappings = FakeMappings(platform.events, row(99), row(44, other))
        val result = async { AndroidWatchUnpairCleanup(platform, mappings).clear(peer) }
        runCurrent()
        assertEquals(peer, platform.settingsPeer)
        assertFalse(result.isCompleted)
        assertTrue(platform.removalRequests.isEmpty())
        assertEquals(2, mappings.rows.size)
        platform.stateChanged(peer, BondState.NONE, BondState.BONDED)
        platform.settingsAnswer.complete(Unit)
        result.await()
        assertTrue(platform.disassociated.isEmpty())
        assertEquals(listOf(row(44, other)), mappings.rows)
        assertEquals(1, platform.observersClosed)
    }

    @Test fun noBondAndNoAssociationOnlyRemovesThisPeersStaleMappings() = runTest {
        val platform = FakePlatform()
        val mappings = FakeMappings(platform.events, row(99), row(44, other))
        AndroidWatchUnpairCleanup(platform, mappings).clear(peer)
        assertTrue(platform.removalRequests.isEmpty())
        assertTrue(platform.disassociated.isEmpty())
        assertEquals(null, platform.settingsPeer)
        assertEquals(listOf(row(44, other)), mappings.rows)
    }

    @Test fun systemChangedBondIsPreservedAndCannotBeReportedAsDisassociated() = runTest {
        val platform = FakePlatform(peer to BondState.BONDED).apply {
            owned += PairingAssociation(42, peer)
            duringAssociations = { call -> if (call == 1) emit(BondSignal.Pairing(peer, true)) }
        }
        val mappings = FakeMappings(platform.events, row(42))
        val failure = assertFailsWith<PairingWorkflowException> { AndroidWatchUnpairCleanup(platform, mappings).clear(peer) }
        assertEquals("watch_bond_changed_forget_in_settings_required", failure.failure.reason)
        assertTrue(platform.removalRequests.isEmpty())
        assertTrue(platform.disassociated.isEmpty())
        assertEquals(listOf(row(42)), mappings.rows)
        assertEquals(1, platform.observersClosed)
    }

    @Test fun bondReappearingAfterRemovalStopsAssociationDeletion() = runTest {
        val platform = FakePlatform(peer to BondState.BONDED).apply {
            owned += PairingAssociation(42, peer)
            duringAssociations = { call -> if (call == 2) stateChanged(peer, BondState.BONDED, BondState.BONDING) }
        }
        val mappings = FakeMappings(platform.events, row(42))
        val failure = assertFailsWith<PairingWorkflowException> { AndroidWatchUnpairCleanup(platform, mappings).clear(peer) }
        assertEquals("bluetooth_device_not_forgotten", failure.failure.reason)
        assertTrue(platform.disassociated.isEmpty())
        assertEquals(listOf(row(42)), mappings.rows)
    }

    @Test fun returningFromSettingsWithoutForgettingKeepsEveryAssociationMapping() = runTest {
        val platform = FakePlatform(peer to BondState.BONDED).apply { settingsAnswer.complete(Unit) }
        val mappings = FakeMappings(platform.events, row(99))
        val failure = assertFailsWith<PairingWorkflowException> { AndroidWatchUnpairCleanup(platform, mappings).clear(peer) }
        assertEquals(PairingFailureKind.BOND, failure.failure.kind)
        assertEquals("bluetooth_device_not_forgotten", failure.failure.reason)
        assertEquals(listOf(row(99)), mappings.rows)
        assertTrue(platform.disassociated.isEmpty())
    }

    @Test fun BluetoothBeingDisabledOnSettingsReturnCannotConfirmNone() = runTest {
        val platform = FakePlatform(peer to BondState.BONDED)
        val mappings = FakeMappings(platform.events, row(99))
        val result = async {
            assertFailsWith<PairingWorkflowException> { AndroidWatchUnpairCleanup(platform, mappings).clear(peer) }
        }
        runCurrent()
        platform.states[peer] = BondState.NONE
        platform.readyFailure = PairingWorkflowException(PairingFailure(PairingFailureKind.BOND, "bluetooth_disabled_during_bond"))
        platform.settingsAnswer.complete(Unit)
        assertEquals("bluetooth_disabled_during_bond", result.await().failure.reason)
        assertEquals(listOf(row(99)), mappings.rows)
    }

    @Test fun permissionFailureDoesNotStartObservationOrDeleteRecords() = runTest {
        val platform = FakePlatform(peer to BondState.BONDED).apply { readyFailure = SecurityException("revoked") }
        val mappings = FakeMappings(platform.events, row(99))
        assertEquals(PairingFailureKind.PERMISSION_DENIED,
            assertFailsWith<PairingWorkflowException> { AndroidWatchUnpairCleanup(platform, mappings).clear(peer) }.failure.kind)
        assertTrue(platform.listeners.isEmpty())
        assertEquals(0, platform.observersClosed)
        assertEquals(listOf(row(99)), mappings.rows)
    }

    @Test fun callerTimeoutReleasesSettingsAndObserverWithoutChangingMappings() = runTest {
        val platform = FakePlatform(peer to BondState.BONDED)
        val mappings = FakeMappings(platform.events, row(99))
        assertFailsWith<CancellationException> {
            withTimeout(100) { AndroidWatchUnpairCleanup(platform, mappings).clear(peer) }
        }
        assertTrue(platform.settingsAnswer.isCancelled)
        assertTrue(platform.listeners.isEmpty())
        assertEquals(1, platform.observersClosed)
        assertEquals(listOf(row(99)), mappings.rows)
    }

    @Test fun callerTimeoutAlsoReleasesTheExistingHelpersPendingRemoval() = runTest {
        val platform = FakePlatform(peer to BondState.BONDED).apply {
            owned += PairingAssociation(42, peer)
            completeRemoval = false
        }
        val mappings = FakeMappings(platform.events, row(42))
        assertFailsWith<CancellationException> {
            withTimeout(100) { AndroidWatchUnpairCleanup(platform, mappings).clear(peer) }
        }
        assertEquals(listOf(42), platform.removalRequests)
        assertTrue(platform.disassociated.isEmpty())
        assertEquals(listOf(row(42)), mappings.rows)
        assertTrue(platform.listeners.isEmpty())
        assertEquals(1, platform.observersClosed)
    }

    @Test fun missingAssociationAddressRequiresThisAppsExistingMapping() = runTest {
        val platform = FakePlatform(peer to BondState.BONDED).apply { owned += PairingAssociation(42, null) }
        val mappings = FakeMappings(platform.events, row(42))
        AndroidWatchUnpairCleanup(platform, mappings).clear(peer)
        assertEquals(listOf(42), platform.removalRequests)
        assertEquals(listOf(42), platform.disassociated)
        assertTrue(mappings.rows.isEmpty())

        val unknown = FakePlatform().apply { owned += PairingAssociation(43, null) }
        AndroidWatchUnpairCleanup(unknown, FakeMappings(unknown.events)).clear(peer)
        assertTrue(unknown.disassociated.isEmpty())
        assertEquals(listOf(PairingAssociation(43, null)), unknown.owned)
    }

    @Test fun knownSystemAddressOverridesAStaleMappingForAnotherDevice() = runTest {
        val platform = FakePlatform().apply { owned += PairingAssociation(42, other) }
        val mappings = FakeMappings(platform.events, row(42), row(44, other))
        AndroidWatchUnpairCleanup(platform, mappings).clear(peer)
        assertTrue(platform.disassociated.isEmpty())
        assertEquals(listOf(PairingAssociation(42, other)), platform.owned)
        assertEquals(listOf(row(44, other)), mappings.rows)
    }

    @Test fun failingDisassociationKeepsItsMappingAndReportsPartialCleanup() = runTest {
        val platform = FakePlatform().apply {
            owned += listOf(PairingAssociation(42, peer), PairingAssociation(43, peer))
            failDisassociationId = 43
        }
        val mappings = FakeMappings(platform.events, row(42), row(43))
        val cleanup = AndroidWatchUnpairCleanup(platform, mappings)
        assertFailsWith<PairingWorkflowException> { cleanup.clear(peer) }
        assertEquals(listOf(42), platform.disassociated)
        assertEquals(listOf(row(43)), mappings.rows)
        platform.failDisassociationId = null
        cleanup.clear(peer)
        assertTrue(platform.owned.isEmpty())
        assertTrue(mappings.rows.isEmpty())
    }

    @Test fun cleanupIdsArePositiveDistinctAndOutsideTheNormalPairingRange() = runTest {
        val first = FakePlatform()
        val second = FakePlatform()
        AndroidWatchUnpairCleanup(first, FakeMappings(first.events)).clear(peer)
        AndroidWatchUnpairCleanup(second, FakeMappings(second.events)).clear(peer)
        val firstId = first.cancelledUi.first()
        val secondId = second.cancelledUi.first()
        assertTrue(firstId >= 1L shl 50)
        assertTrue(secondId > firstId)
    }

    private fun row(id: Int, address: PeerAddress = peer) = AssociationMapping(id, address, true, 1)

    private class FakeMappings(val events: MutableList<String>, vararg initial: AssociationMapping) : AssociationMappingStore {
        val rows = initial.toMutableList()
        override suspend fun all() = rows.toList()
        override suspend fun put(mapping: AssociationMapping) { error("Cleanup cannot create mappings") }
        override suspend fun remove(associationId: Int) {
            events += "mapping:$associationId"
            rows.removeAll { it.associationId == associationId }
        }
    }

    private class FakePlatform(vararg initial: Pair<PeerAddress, BondState>) : WatchUnpairPlatform {
        val states = initial.toMap().toMutableMap()
        val owned = mutableListOf<PairingAssociation>()
        val events = mutableListOf<String>()
        val removalRequests = mutableListOf<Int>()
        val disassociated = mutableListOf<Int>()
        val listeners = mutableListOf<(BondSignal) -> Unit>()
        val cancelledUi = mutableListOf<Long>()
        val settingsAnswer = CompletableDeferred<Unit>()
        var settingsOperation: Long? = null
        var settingsPeer: PeerAddress? = null
        var readyFailure: Exception? = null
        var completeRemoval = true
        var observersClosed = 0
        var associationReads = 0
        var duringAssociations: ((Int) -> Unit)? = null
        var failDisassociationId: Int? = null

        override fun ready() { readyFailure?.let { throw it } }
        override fun associations(): List<PairingAssociation> {
            associationReads += 1
            duringAssociations?.invoke(associationReads)
            return owned.toList()
        }
        override fun state(peer: PeerAddress) = states[peer] ?: BondState.NONE
        override fun openBonds() = AndroidPairingBonds(object : PairingBondPlatform {
            override fun ready() = this@FakePlatform.ready()
            override fun observe(listener: (BondSignal) -> Unit): AutoCloseable {
                listeners += listener
                return AutoCloseable { if (listeners.remove(listener)) observersClosed += 1 }
            }
            override fun bondedPeers() = states.filterValues { it == BondState.BONDED }.keys.toSet()
            override fun state(peer: PeerAddress) = this@FakePlatform.state(peer)
            override fun createBond(peer: PeerAddress): Boolean = error("Unpair cleanup must not create bonds")
            override fun removeBond(association: PairingAssociation): Boolean {
                removalRequests += association.id
                events += "remove:${association.id}"
                if (completeRemoval) stateChanged(requireNotNull(association.peer), BondState.NONE, BondState.BONDED)
                return true
            }
            override suspend fun forgetInSettings(operationId: Long, peer: PeerAddress) = this@FakePlatform.forgetInSettings(operationId, peer)
            override fun cancelUi(operationId: Long) = this@FakePlatform.cancelUi(operationId)
        })
        override suspend fun forgetInSettings(operationId: Long, peer: PeerAddress) {
            settingsOperation = operationId
            settingsPeer = peer
            settingsAnswer.await()
        }
        override fun disassociate(association: PairingAssociation) {
            if (association.id == failDisassociationId) {
                throw PairingWorkflowException(PairingFailure(PairingFailureKind.ASSOCIATION, "system_disassociate_failed"))
            }
            check(state(requireNotNull(association.peer)) == BondState.NONE)
            disassociated += association.id
            events += "disassociate:${association.id}"
            owned.removeAll { it.id == association.id }
        }
        override fun cancelUi(operationId: Long) {
            cancelledUi += operationId
            if (settingsOperation == operationId) settingsAnswer.cancel()
        }
        fun emit(signal: BondSignal) { listeners.toList().forEach { it(signal) } }
        fun stateChanged(peer: PeerAddress, current: BondState, previous: BondState) {
            states[peer] = current
            emit(BondSignal.State(peer, current, previous))
        }
    }
}
