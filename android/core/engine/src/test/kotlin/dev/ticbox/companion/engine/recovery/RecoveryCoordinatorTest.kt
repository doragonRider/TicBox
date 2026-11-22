package dev.ticbox.companion.engine.recovery

import dev.ticbox.companion.api.*
import java.io.IOException
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryCoordinatorTest {
    private val peer = PeerAddress("01:23:45:67:89:AB")
    private val other = PeerAddress("01:23:45:67:89:CD")

    @Test fun localDocumentRetainsPeerAssociationAndDisconnectRevision() {
        val selected = ConnectionIntent.selected(peer, 7)
        assertEquals(selected, ConnectionIntentCodec.decode(ConnectionIntentCodec.encode(selected)))
        val clear = ConnectionIntent.selected(null)
        assertEquals(clear, ConnectionIntentCodec.decode(ConnectionIntentCodec.encode(clear)))
        val paused = selected.copy(reconnectEnabled = false)
        assertEquals(paused, ConnectionIntentCodec.decode(ConnectionIntentCodec.encode(paused)))
        assertTrue(ConnectionIntentCodec.decode(ConnectionIntentCodec.encode(selected).dropLast(1).toByteArray()).reconnectEnabled)
        assertNotEquals(selected.revision, clear.revision)
        assertFailsWith<IllegalArgumentException> { ConnectionIntentCodec.decode(ByteArray(513)) }
    }

    @Test fun connectPersistsBeforeStartingAndDoesNotInventLinkReadiness() = runTest {
        val store = Store()
        val link = Link { assertEquals(peer, store.value?.peer) }
        val coordinator = RecoveryCoordinator(store, Environment(), link)
        coordinator.connect(peer)
        assertEquals(7, store.value?.associationId)
        assertEquals(listOf(peer), link.connections)
        assertEquals(ConnectionRecoveryPhase.RUNNING, coordinator.state.value.phase)
        assertTrue(coordinator.state.value.durable)
    }

    @Test fun processRecreationRestoresSavedChoiceAndDisconnectRejectsLateWake() = runTest {
        val store = Store(ConnectionIntent.selected(peer, 7))
        val oldRevision = store.value!!.revision
        val link = Link()
        val coordinator = RecoveryCoordinator(store, Environment(), link)
        coordinator.restore("sticky_restart")
        assertEquals(listOf(peer), link.connections)
        coordinator.disconnect()
        coordinator.restore("late_presence", oldRevision)
        assertEquals(peer, store.value?.peer)
        assertFalse(store.value!!.reconnectEnabled)
        assertEquals(1, link.connections.size)
        assertEquals(ConnectionRecoveryPhase.IDLE, coordinator.state.value.phase)
        val recreatedLink = Link()
        val recreated = RecoveryCoordinator(store, Environment(), recreatedLink)
        recreated.restore("bound_host")
        assertTrue(recreatedLink.connections.isEmpty())
        assertTrue(recreated.state.value.paused)
        assertEquals(peer, recreated.state.value.desiredPeer)
        recreated.resume()
        assertEquals(listOf(peer), recreatedLink.connections)
        assertTrue(store.value!!.reconnectEnabled)
    }

    @Test fun bluetoothOffWaitsWithoutRetryLoopAndOnEventRestores() = runTest {
        val environment = Environment().apply { snapshot = snapshot.copy(bluetoothEnabled = false) }
        val link = Link()
        val coordinator = RecoveryCoordinator(Store(), environment, link)
        coordinator.connect(peer)
        assertEquals(ConnectionRecoveryPhase.WAITING_BLUETOOTH, coordinator.state.value.phase)
        assertEquals(1, link.waits)
        assertTrue(link.connections.isEmpty())
        advanceTimeBy(1_000_000)
        runCurrent()
        assertTrue(link.connections.isEmpty())
        environment.snapshot = environment.snapshot.copy(bluetoothEnabled = true)
        coordinator.onEnvironmentChanged()
        assertEquals(listOf(peer), link.connections)
    }

    @Test fun missingPermissionAssociationOrBondStopsAndExposesSpecificAction() = runTest {
        listOf(
            RecoveryEnvironmentState(bluetoothPermission = false) to ConnectionRecoveryPhase.WAITING_PERMISSION,
            RecoveryEnvironmentState(savedAssociationExists = false) to ConnectionRecoveryPhase.WAITING_ASSOCIATION,
            RecoveryEnvironmentState(bonded = false) to ConnectionRecoveryPhase.WAITING_BOND,
        ).forEach { (snapshot, expected) ->
            val link = Link()
            val environment = Environment().apply { this.snapshot = snapshot }
            val coordinator = RecoveryCoordinator(Store(ConnectionIntent.selected(peer, 7)), environment, link)
            coordinator.resume()
            assertEquals(expected, coordinator.state.value.phase)
            assertEquals(1, link.disconnects)
            assertTrue(link.connections.isEmpty())
        }
    }

    @Test fun platformStartDenialRetainsIntentAndWaitsForExplicitResume() = runTest {
        val store = Store()
        val link = Link().apply { denied = true }
        val coordinator = RecoveryCoordinator(store, Environment(), link)
        coordinator.connect(peer)
        assertEquals(ConnectionRecoveryPhase.WAITING_START_ALLOWED, coordinator.state.value.phase)
        assertEquals(peer, store.value?.peer)
        advanceUntilIdle()
        assertEquals(1, link.attempts)
        link.denied = false
        coordinator.resume()
        assertEquals(ConnectionRecoveryPhase.RUNNING, coordinator.state.value.phase)
        assertEquals(2, link.attempts)
    }

    @Test fun manualPeerDoesNotRequireNewCompanionAssociation() = runTest {
        val environment = Environment().apply { snapshot = snapshot.copy(associationId = null) }
        val link = Link()
        val coordinator = RecoveryCoordinator(Store(), environment, link)
        coordinator.connect(peer)
        assertEquals(ConnectionRecoveryPhase.RUNNING, coordinator.state.value.phase)
        assertNull(coordinator.state.value.associationId)
    }

    @Test fun delayedWakeDoesNotSupersedeNewConnectWhileItsWriteSuspends() = runTest {
        val previous = ConnectionIntent.selected(peer, 7)
        val store = Store(previous)
        val writeGate = CompletableDeferred<Unit>()
        store.writeGate = writeGate
        val link = Link()
        val coordinator = RecoveryCoordinator(store, Environment(), link)
        val connect = async { coordinator.connect(other) }
        runCurrent()
        val wake = async { coordinator.restore("old_presence", previous.revision) }
        runCurrent()
        writeGate.complete(Unit)
        connect.await(); wake.await()
        assertEquals(listOf(other), link.connections)
        assertEquals(other, store.value?.peer)
        assertEquals(ConnectionRecoveryPhase.RUNNING, coordinator.state.value.phase)
    }

    @Test fun laterDisconnectWinsOverConnectWaitingForPersistence() = runTest {
        val store = Store()
        val gate = CompletableDeferred<Unit>()
        store.writeGate = gate
        val link = Link()
        val coordinator = RecoveryCoordinator(store, Environment(), link)
        val connect = async { coordinator.connect(peer) }
        runCurrent()
        val disconnect = async { coordinator.disconnect() }
        runCurrent()
        gate.complete(Unit)
        connect.await(); disconnect.await()
        assertTrue(link.connections.isEmpty())
        assertEquals(peer, store.value?.peer)
        assertFalse(store.value!!.reconnectEnabled)
        assertEquals(ConnectionRecoveryPhase.IDLE, coordinator.state.value.phase)
    }

    @Test fun disconnectStillReleasesTransportWhenPersistenceFailsWithoutClaimingDurableClear() = runTest {
        val store = Store(ConnectionIntent.selected(peer, 7)).apply { failWrites = true }
        val link = Link()
        val coordinator = RecoveryCoordinator(store, Environment(), link)
        coordinator.disconnect()
        assertEquals(1, link.disconnects)
        assertEquals(peer, store.value?.peer)
        assertFalse(coordinator.state.value.durable)
        assertEquals("disconnect_persistence_failed", coordinator.state.value.reason)
        assertEquals(ConnectionRecoveryIssue.DISCONNECT_NOT_SAVED, coordinator.state.value.issue)
        coordinator.resume()
        assertTrue(link.connections.isEmpty())
    }

    @Test fun closePreservesUserIntentForNextServiceInstance() = runTest {
        val store = Store()
        val coordinator = RecoveryCoordinator(store, Environment(), Link())
        coordinator.connect(peer)
        coordinator.close()
        assertEquals(peer, store.value?.peer)
        val next = Link()
        RecoveryCoordinator(store, Environment(), next).resume()
        assertEquals(listOf(peer), next.connections)
    }

    @Test fun observationFailureIsLoggedButDoesNotBlockAnExistingBluetoothBond() = runTest {
        val records = mutableListOf<ProtocolDiagnostic>()
        val environment = Environment().apply { failObservation = true }
        val link = Link()
        val coordinator = RecoveryCoordinator(Store(), environment, link, DiagnosticSink(records::add))
        coordinator.connect(peer)
        assertEquals(listOf(peer), link.connections)
        assertTrue(records.any { it.event == "presence_observation_unavailable" })
    }

    @Test fun grantedPermissionAdoptsExistingOwnedAssociationAndBrokenLoggerCannotStopRecovery() = runTest {
        val store = Store(ConnectionIntent.selected(peer))
        val link = Link()
        val coordinator = RecoveryCoordinator(store, Environment(), link, DiagnosticSink { throw IOException("simulated logger failure") })
        coordinator.resume()
        assertEquals(7, store.value?.associationId)
        assertEquals(listOf(peer), link.connections)
        coordinator.disconnect()
        assertEquals(peer, store.value?.peer)
        assertFalse(store.value!!.reconnectEnabled)
        assertEquals(ConnectionRecoveryPhase.IDLE, coordinator.state.value.phase)
    }

    @Test fun noSelectedPeerListsOwnedWatchesWithoutConnectingOrWriting() = runTest {
        val known = PairingAssociation(7, peer, "Existing watch", watchProfile = true)
        val store = Store(ConnectionIntent.selected(null))
        val original = store.value
        val environment = Environment().apply { paired = listOf(known) }
        val link = Link()
        val coordinator = RecoveryCoordinator(store, environment, link)
        coordinator.restore("bound_host")
        assertEquals(listOf(known), coordinator.state.value.pairedWatches)
        assertTrue(link.connections.isEmpty())
        assertEquals(original, store.value)
        coordinator.connect(peer)
        assertEquals(listOf(peer), link.connections)
        assertEquals(peer, store.value!!.peer)
    }

    @Test fun explicitForgetClearsSelectionWhileOrdinaryDisconnectKeepsIt() = runTest {
        val store = Store()
        val link = Link()
        val coordinator = RecoveryCoordinator(store, Environment(), link)
        coordinator.connect(peer)
        coordinator.disconnect()
        assertEquals(peer, coordinator.state.value.desiredPeer)
        assertTrue(coordinator.state.value.paused)
        coordinator.forget()
        assertNull(store.value!!.peer)
        assertNull(coordinator.state.value.desiredPeer)
        assertFalse(coordinator.state.value.paused)
        coordinator.restore("late_presence")
        assertEquals(listOf(peer), link.connections)
    }

    @Test fun aFailedForgetDoesNotClaimDurableClearOnResumeUntilItCommits() = runTest {
        val store = Store(ConnectionIntent.selected(peer, 7)).apply { failWrites = true }
        val link = Link()
        val coordinator = RecoveryCoordinator(store, Environment(), link)
        coordinator.forget()
        coordinator.resume()
        assertFalse(coordinator.state.value.durable)
        assertEquals(peer, store.value?.peer)
        assertTrue(link.connections.isEmpty())
        store.failWrites = false
        coordinator.resume()
        assertTrue(coordinator.state.value.durable)
        assertNull(store.value?.peer)
        assertTrue(link.connections.isEmpty())
    }

    @Test fun unreadableSavedChoiceStaysAFailureInsteadOfBecomingAnEmptyWelcome() = runTest {
        val broken = object : ConnectionIntentStore {
            override suspend fun read(): ConnectionIntent? = throw IOException("unreadable")
            override suspend fun write(intent: ConnectionIntent) = error("not requested")
        }
        val coordinator = RecoveryCoordinator(broken, Environment(), Link())
        coordinator.restore("bound_host")
        assertEquals(ConnectionRecoveryIssue.SAVED_CONNECTION_UNREADABLE, coordinator.state.value.issue)
        assertFalse(coordinator.state.value.durable)
    }

    @Test fun terminatedAssociationPausesPresenceAndSurvivesProcessRecreationWithoutClearingSelection() = runTest {
        val store = Store()
        val environment = Environment()
        val link = Link()
        val coordinator = RecoveryCoordinator(store, environment, link)
        coordinator.connect(peer)
        coordinator.onAssociationTerminated(peer) { true }
        assertEquals(peer, store.value!!.peer)
        assertEquals(7, store.value!!.associationId)
        assertFalse(store.value!!.reconnectEnabled)
        assertEquals(listOf(peer), environment.stopped)
        assertEquals(0, link.disconnects) // Retain the engine's exact terminal failure.
        assertEquals(1, link.terminatedReleases)
        coordinator.restore("late_presence")
        val next = Link()
        val recreated = RecoveryCoordinator(store, Environment(), next)
        recreated.restore("sticky_restart")
        assertTrue(next.connections.isEmpty())
        assertTrue(recreated.state.value.paused)
        recreated.resume()
        assertEquals(listOf(peer), next.connections)
    }

    @Test fun lifecycleWakeChecksStickyTerminationBeforeItsStateCollectorRuns() = runTest {
        val store = Store()
        val environment = Environment()
        val link = Link()
        val coordinator = RecoveryCoordinator(store, environment, link)
        coordinator.connect(peer)
        link.terminated = peer
        coordinator.restore("bond_changed")
        assertEquals(listOf(peer), link.connections)
        assertFalse(store.value!!.reconnectEnabled)
        assertEquals(listOf(peer), environment.stopped)
        coordinator.resume() // A new explicit request can try the selected peer again.
        assertEquals(listOf(peer, peer), link.connections)
    }

    @Test fun staleTerminatedSessionCannotPauseNewPeerOrNewSessionForTheSamePeer() = runTest {
        val store = Store()
        val environment = Environment()
        val coordinator = RecoveryCoordinator(store, environment, Link())
        coordinator.connect(other)
        coordinator.onAssociationTerminated(peer) { true }
        assertEquals(other, store.value!!.peer)
        assertTrue(store.value!!.reconnectEnabled)
        coordinator.connect(peer)
        coordinator.onAssociationTerminated(peer) { false }
        assertTrue(store.value!!.reconnectEnabled)
        assertTrue(environment.stopped.none { it == peer })
    }

    @Test fun userConnectQueuedDuringTerminationPersistenceWins() = runTest {
        val store = Store()
        val link = Link()
        val environment = Environment()
        val coordinator = RecoveryCoordinator(store, environment, link)
        coordinator.connect(peer)
        val gate = CompletableDeferred<Unit>()
        store.writeGate = gate
        val termination = async { coordinator.onAssociationTerminated(peer) { true } }
        runCurrent()
        val reconnect = async { coordinator.connect(peer) }
        runCurrent()
        gate.complete(Unit)
        termination.await(); reconnect.await()
        assertTrue(store.value!!.reconnectEnabled)
        assertEquals(listOf(peer, peer), link.connections)
        assertTrue(environment.stopped.isEmpty())
    }

    @Test fun failedTerminationPauseCommitBlocksAutomaticWakesWithoutClaimingDurability() = runTest {
        val store = Store()
        val link = Link()
        val environment = Environment()
        val coordinator = RecoveryCoordinator(store, environment, link)
        coordinator.connect(peer)
        store.failWrites = true
        coordinator.onAssociationTerminated(peer) { true }
        assertFalse(coordinator.state.value.durable)
        assertTrue(coordinator.state.value.paused)
        assertEquals(ConnectionRecoveryIssue.DISCONNECT_NOT_SAVED, coordinator.state.value.issue)
        assertEquals(listOf(peer), environment.stopped)
        coordinator.restore("late_presence")
        assertEquals(listOf(peer), link.connections)
        store.failWrites = false
        coordinator.resume()
        assertTrue(coordinator.state.value.durable)
        assertEquals(listOf(peer, peer), link.connections)
    }

    private class Store(var value: ConnectionIntent? = null) : ConnectionIntentStore {
        var writeGate: CompletableDeferred<Unit>? = null
        var failWrites = false
        override suspend fun read() = value
        override suspend fun write(intent: ConnectionIntent) {
            writeGate?.await()
            if (failWrites) throw IOException("simulated write failure")
            value = intent
        }
    }
    private class Environment : RecoveryEnvironment {
        var snapshot = RecoveryEnvironmentState(associationId = 7)
        var failObservation = false
        var paired = emptyList<PairingAssociation>()
        val stopped = mutableListOf<PeerAddress>()
        override suspend fun pairedWatches() = paired
        override suspend fun inspect(intent: ConnectionIntent) = snapshot
        override suspend fun observe(intent: ConnectionIntent) { if (failObservation) throw SecurityException("simulated OEM refusal") }
        override suspend fun stopObserving(intent: ConnectionIntent) { intent.peer?.let(stopped::add) }
    }
    private class Link(private val onConnect: () -> Unit = {}) : RecoveryLink {
        val connections = mutableListOf<PeerAddress>()
        var attempts = 0
        var disconnects = 0
        var waits = 0
        var denied = false
        var terminated: PeerAddress? = null
        var terminatedReleases = 0
        override fun associationTerminated(peer: PeerAddress) = terminated == peer
        override suspend fun releaseTerminatedPeer() { terminatedReleases += 1 }
        override suspend fun connect(peer: PeerAddress) {
            attempts += 1
            if (denied) throw RecoveryStartNotAllowedException(IllegalStateException("simulated system refusal"))
            onConnect(); connections += peer
        }
        override suspend fun disconnect(userRequested: Boolean) { disconnects += 1 }
        override suspend fun waitForBluetooth() { waits += 1 }
    }
}
