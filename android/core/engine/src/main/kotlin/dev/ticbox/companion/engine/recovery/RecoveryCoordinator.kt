package dev.ticbox.companion.engine.recovery

import dev.ticbox.companion.api.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RecoveryEnvironmentState(
    val bluetoothPermission: Boolean = true,
    val bluetoothAvailable: Boolean = true,
    val bluetoothEnabled: Boolean = true,
    val bonded: Boolean = true,
    val associationId: Int? = null,
    val savedAssociationExists: Boolean = true,
)

interface RecoveryEnvironment {
    suspend fun inspect(intent: ConnectionIntent): RecoveryEnvironmentState
    /** Existing owned, bonded watch associations for explicit user selection only. */
    suspend fun pairedWatches(): List<PairingAssociation> = emptyList()
    suspend fun observe(intent: ConnectionIntent) = Unit
    suspend fun stopObserving(intent: ConnectionIntent) = Unit
}

interface RecoveryLink {
    /** Includes service foreground retention. Must delegate retry scheduling to the existing engine. */
    suspend fun connect(peer: PeerAddress)
    suspend fun disconnect(userRequested: Boolean = false)
    /** Sticky session failure; lifecycle wakes must not restart an association ended by the watch. */
    fun associationTerminated(peer: PeerAddress): Boolean = false
    /** Release peer-scoped work without replacing the engine's terminal failure with IDLE. */
    suspend fun releaseTerminatedPeer() = Unit
    /** Retain an already requested Android owner while waiting for the adapter to be enabled. */
    suspend fun waitForBluetooth() = Unit
}

class RecoveryStartNotAllowedException(cause: Throwable) : IllegalStateException("Foreground start refused", cause)

/** Serialized, event-driven recovery. No timers, parallel reconnect loops, or battery exemption requests. */
class RecoveryCoordinator(
    private val store: ConnectionIntentStore,
    private val environment: RecoveryEnvironment,
    private val link: RecoveryLink,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
) : ConnectionRecoveryClient, AutoCloseable {
    private val mutable = MutableStateFlow(ConnectionRecoveryState())
    override val state = mutable.asStateFlow()
    private val commands = Mutex()
    private val generation = AtomicLong()
    @Volatile private var closed = false
    private var desired: ConnectionIntent? = null
    private var disconnectNotDurable = false

    suspend fun connect(peer: PeerAddress) {
        val request = generation.incrementAndGet()
        commands.withLock {
            if (!current(request)) return
            val previous = desired ?: read(request) ?: if (!current(request)) return else null
            var next = ConnectionIntent.selected(peer)
            try {
                val observed = environment.inspect(next)
                next = next.copy(associationId = observed.associationId)
            } catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                log("association_lookup_unavailable", problem)
            }
            if (!current(request)) return
            try {
                store.write(next)
            } catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                fail("intent_persistence_failed", ConnectionRecoveryIssue.CONNECTION_NOT_SAVED, problem, peer, durable = false)
                try { link.disconnect() } catch (cleanup: Exception) {
                    if (cleanup is CancellationException) throw cleanup
                    log("disconnect_cleanup_failed", cleanup)
                }
                return
            }
            desired = next
            disconnectNotDurable = false
            if (!current(request)) return
            if (previous?.peer != null && (previous.peer != next.peer || previous.associationId != next.associationId)) stopObservation(previous)
            apply(next, request, explicitlyRequested = true)
        }
    }

    suspend fun disconnect() = stop(forget = false)

    /** Only explicit unpair cleanup clears selection; ordinary disconnect remains resumable. */
    suspend fun forget() = stop(forget = true)

    /**
     * Persist a pause for an exact failed session, without clearing its identity, bond or CDM
     * association. The engine has already closed the transport; keep its failure visible.
     */
    suspend fun onAssociationTerminated(peer: PeerAddress, sessionStillCurrent: () -> Boolean) {
        val queuedGeneration = generation.get()
        commands.withLock {
            if (!current(queuedGeneration) || !sessionStillCurrent()) return
            val previous = desired ?: read(queuedGeneration)
            if (!current(queuedGeneration) || !sessionStillCurrent() || previous?.peer != peer) return
            if (!previous.reconnectEnabled) return
            pauseTerminatedAssociation(previous, generation.incrementAndGet())
        }
    }

    private suspend fun pauseTerminatedAssociation(previous: ConnectionIntent, request: Long) {
        val paused = ConnectionIntent.selected(previous.peer, previous.associationId).copy(reconnectEnabled = false)
        var saved = true
        try { store.write(paused) } catch (problem: Exception) {
            if (problem is CancellationException) throw problem
            saved = false
            log("association_pause_persistence_failed", problem)
        }
        desired = paused
        disconnectNotDurable = !saved
        if (!current(request)) return
        try { link.releaseTerminatedPeer() } catch (problem: Exception) {
            if (problem is CancellationException) throw problem
            log("terminated_peer_cleanup_failed", problem)
        }
        if (!current(request)) return
        stopObservation(previous)
        if (current(request)) mutable.value = ConnectionRecoveryState(
            if (saved) ConnectionRecoveryPhase.IDLE else ConnectionRecoveryPhase.FAILED,
            desiredPeer = paused.peer, associationId = paused.associationId,
            reason = "association_terminated", durable = saved,
            issue = if (saved) null else ConnectionRecoveryIssue.DISCONNECT_NOT_SAVED,
            paused = true,
        )
        log("association_terminated_paused", details = mapOf("durable" to saved.toString()))
    }

    private suspend fun stop(forget: Boolean) {
        val request = generation.incrementAndGet()
        commands.withLock {
            if (!current(request)) return
            val previous = desired ?: try { store.read() } catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                log("disconnect_previous_intent_unavailable", problem)
                null
            }
            val tombstone = if (forget) ConnectionIntent.selected(null) else
                ConnectionIntent.selected(previous?.peer, previous?.associationId).copy(reconnectEnabled = false)
            var saved = true
            try { store.write(tombstone) } catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                saved = false
                log("disconnect_persistence_failed", problem)
            }
            desired = tombstone // Never reconnect automatically after an explicit disconnect.
            disconnectNotDurable = !saved
            try { link.disconnect(userRequested = true) } catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                log("disconnect_cleanup_failed", problem)
            }
            previous?.let { stopObservation(it) }
            if (current(request)) mutable.value = ConnectionRecoveryState(
                if (saved) ConnectionRecoveryPhase.IDLE else ConnectionRecoveryPhase.FAILED,
                desiredPeer = tombstone.peer, associationId = tombstone.associationId,
                reason = if (saved) null else "disconnect_persistence_failed", durable = saved,
                issue = if (saved) null else ConnectionRecoveryIssue.DISCONNECT_NOT_SAVED,
                paused = !forget && tombstone.peer != null,
            )
            log(if (forget) "watch_selection_cleared" else "user_disconnected")
        }
    }

    override suspend fun resume() = restore("visible_host", resumePaused = true)

    /** Revision is optional only for direct lifecycle/environment events, never copied into a new intent. */
    suspend fun restore(trigger: String, expectedRevision: String? = null, resumePaused: Boolean = false) {
        val queuedGeneration = generation.get()
        commands.withLock {
            if (!current(queuedGeneration)) return
            if (disconnectNotDurable && !resumePaused) {
                log("restore_held_after_failed_disconnect_commit")
                return
            }
            val loaded = if (resumePaused && disconnectNotDurable) desired else read(queuedGeneration)
            if (loaded == null && mutable.value.issue == ConnectionRecoveryIssue.SAVED_CONNECTION_UNREADABLE) return
            var saved = loaded ?: ConnectionIntent.selected(null)
            if (!current(queuedGeneration)) return
            if (expectedRevision != null && saved.revision != expectedRevision) {
                log("stale_wake_ignored", details = mapOf("trigger" to trigger))
                return
            }
            if (resumePaused && (disconnectNotDurable || (saved.peer != null && !saved.reconnectEnabled))) {
                val resumed = ConnectionIntent.selected(saved.peer, saved.associationId)
                try { store.write(resumed) } catch (problem: Exception) {
                    if (problem is CancellationException) throw problem
                    if (current(queuedGeneration)) fail("resume_persistence_failed", ConnectionRecoveryIssue.CONNECTION_NOT_SAVED,
                        problem, saved.peer, durable = false)
                    return
                }
                saved = resumed
                disconnectNotDurable = false
            }
            val request = generation.incrementAndGet()
            desired = saved
            log("restore_requested", details = mapOf("trigger" to trigger))
            apply(saved, request, explicitlyRequested = resumePaused)
        }
    }

    suspend fun onEnvironmentChanged() = restore("environment_changed")

    private suspend fun read(request: Long): ConnectionIntent? = try {
        val value = store.read()
        if (value == null && current(request)) mutable.value = ConnectionRecoveryState(durable = true)
        value
    } catch (problem: Exception) {
        if (problem is CancellationException) throw problem
        if (current(request)) fail("intent_read_failed", ConnectionRecoveryIssue.SAVED_CONNECTION_UNREADABLE, problem, durable = false)
        null
    }

    private suspend fun apply(intent: ConnectionIntent, request: Long, explicitlyRequested: Boolean = false) {
        val peer = intent.peer
        if (peer == null) {
            val paired = try { environment.pairedWatches() } catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                log("paired_watches_unavailable", problem)
                emptyList()
            }
            if (current(request)) mutable.value = ConnectionRecoveryState(durable = true, pairedWatches = paired)
            log("paired_watches_observed", details = mapOf("count" to paired.size.toString()))
            return
        }
        if (!intent.reconnectEnabled) {
            if (current(request)) mutable.value = ConnectionRecoveryState(desiredPeer = peer,
                associationId = intent.associationId, durable = true, paused = true)
            log("restore_paused")
            return
        }
        // Also check the engine directly: a presence/bond wake can arrive before its state
        // collector persists the pause, and the live event stream is deliberately lossy.
        if (!explicitlyRequested && link.associationTerminated(peer)) {
            pauseTerminatedAssociation(intent, request)
            return
        }
        mutable.value = ConnectionRecoveryState(ConnectionRecoveryPhase.RESTORING, peer, intent.associationId, durable = true)
        try {
            val environmentState = environment.inspect(intent)
            if (!current(request)) return
            var observedIntent = intent
            // Permission may have been absent when the peer was first selected. Adopt an actual
            // owned association once Android can report it, without requiring a new UI connect.
            if (intent.associationId == null && environmentState.associationId != null) {
                val discovered = intent.copy(associationId = environmentState.associationId)
                try {
                    store.write(discovered)
                    observedIntent = discovered
                    desired = discovered
                    if (current(request)) mutable.value = mutable.value.copy(associationId = discovered.associationId)
                } catch (problem: Exception) {
                    if (problem is CancellationException) throw problem
                    log("association_intent_update_failed", problem)
                }
                if (!current(request)) return
            }
            val wait = when {
                !environmentState.bluetoothPermission -> ConnectionRecoveryPhase.WAITING_PERMISSION to "bluetooth_connect_required"
                !environmentState.bluetoothAvailable -> ConnectionRecoveryPhase.FAILED to "bluetooth_unavailable"
                !environmentState.savedAssociationExists -> ConnectionRecoveryPhase.WAITING_ASSOCIATION to "association_removed"
                !environmentState.bluetoothEnabled -> ConnectionRecoveryPhase.WAITING_BLUETOOTH to "bluetooth_disabled"
                !environmentState.bonded -> ConnectionRecoveryPhase.WAITING_BOND to "bluetooth_bond_required"
                else -> null
            }
            if (wait != null) {
                link.disconnect()
                if (!current(request)) return
                if (wait.first == ConnectionRecoveryPhase.WAITING_BLUETOOTH) {
                    try { environment.observe(observedIntent) } catch (problem: Exception) {
                        if (problem is CancellationException) throw problem
                        log("presence_observation_unavailable", problem)
                    }
                    if (!current(request)) return
                    link.waitForBluetooth()
                }
                if (current(request)) mutable.value = mutable.value.copy(phase = wait.first, reason = wait.second, issue = null)
                log("restore_waiting", details = mapOf("reason" to wait.second))
                return
            }
            // Presence observation improves cold-process recovery; a platform refusal does not make
            // an otherwise usable manually selected Bluetooth peer fail to connect.
            try { environment.observe(observedIntent) } catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                log("presence_observation_unavailable", problem)
            }
            if (!current(request)) return
            link.connect(peer)
            if (current(request)) mutable.value = mutable.value.copy(phase = ConnectionRecoveryPhase.RUNNING, reason = null, issue = null)
        } catch (problem: Exception) {
            if (problem is CancellationException) throw problem
            if (!current(request)) return
            val phase = when (problem) {
                is RecoveryStartNotAllowedException -> ConnectionRecoveryPhase.WAITING_START_ALLOWED
                is SecurityException -> ConnectionRecoveryPhase.WAITING_PERMISSION
                else -> ConnectionRecoveryPhase.FAILED
            }
            mutable.value = mutable.value.copy(phase = phase, reason = problem.javaClass.simpleName,
                issue = if (phase == ConnectionRecoveryPhase.FAILED) ConnectionRecoveryIssue.RESTORE_FAILED else null)
            log("restore_failed", problem)
        }
    }

    private suspend fun stopObservation(intent: ConnectionIntent) {
        try { environment.stopObserving(intent) } catch (problem: Exception) {
            if (problem is CancellationException) throw problem
            log("presence_cleanup_unavailable", problem)
        }
    }
    private fun current(request: Long) = !closed && generation.get() == request
    private fun fail(reason: String, issue: ConnectionRecoveryIssue, problem: Exception, peer: PeerAddress? = null, durable: Boolean) {
        mutable.value = ConnectionRecoveryState(ConnectionRecoveryPhase.FAILED, peer, reason = reason, durable = durable, issue = issue)
        log(reason, problem)
    }
    private fun log(event: String, problem: Exception? = null, details: Map<String, String> = emptyMap()) {
        runCatching { diagnostics.record(ProtocolDiagnostic("recovery", event,
            if (problem == null) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
            details = details + (problem?.let { mapOf("exception" to it.javaClass.simpleName) } ?: emptyMap()))) }
    }
    /** Resource teardown deliberately preserves the saved user choice. */
    override fun close() {
        closed = true
        generation.incrementAndGet()
        mutable.value = mutable.value.copy(phase = ConnectionRecoveryPhase.CLOSED)
    }
}
