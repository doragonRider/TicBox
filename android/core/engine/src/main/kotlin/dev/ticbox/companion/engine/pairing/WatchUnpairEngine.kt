package dev.ticbox.companion.engine.pairing

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.CompanionFeatureSession
import dev.ticbox.companion.protocol.DataMapCodec
import dev.ticbox.companion.protocol.DataMapValue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The analyzed WCS skip-wipe receiver only. Older receivers can ignore the flags and reset the watch;
 * version discovery is therefore mandatory and bound to a new wire event in this physical session.
 */
class WatchUnpairEngine(
    parentScope: CoroutineScope,
    private val requestSnapshot: suspend (CompanionFeatureSession) -> Unit,
    private val cleanup: suspend (PeerAddress) -> Unit,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val identityTimeoutMillis: Long = 15_000,
    private val ackTimeoutMillis: Long = 10_000,
    private val remoteCleanupGraceMillis: Long = 5_000,
) : WatchUnpairClient {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val commands = Mutex()
    private val lock = Any()
    private val mutableState = MutableStateFlow(WatchUnpairState())
    override val state = mutableState.asStateFlow()
    private var active: Run? = null
    private var retiredSessionId = 0L
    private var closed = false
    private var identityProbe: IdentityProbe? = null
    private var pendingAck: PendingAck? = null

    init { require(identityTimeoutMillis > 0 && ackTimeoutMillis > 0 && remoteCleanupGraceMillis >= 0) }

    private class Run(val session: CompanionFeatureSession) {
        val ended = CompletableDeferred<Unit>()
        var identity: Identity? = null
    }
    private data class Identity(val code: Long?, val name: String?, val support: WatchUnpairSupport, val reason: String,
        val sdk: Int? = null)
    private class IdentityProbe(val run: Run) { val result = CompletableDeferred<Identity>() }
    private class PendingAck(val run: Run) {
        val result = CompletableDeferred<Unit>()
        var writeAttempted = false
    }
    private data class ProbeResult(val run: Run?, val identity: Identity)
    private class LinkEnded : IllegalStateException("watch_unpair_link_ended")
    private class IdentityChanged : IllegalStateException("home_version_changed_before_write")

    fun attach(session: CompanionFeatureSession) = synchronized(lock) {
        if (closed || !lifetime.isActive) return@synchronized
        val previous = active
        if (previous?.session === session) return@synchronized
        if (session.sessionId <= retiredSessionId || (previous != null && session.sessionId <= previous.session.sessionId))
            return@synchronized
        previous?.let { retiredSessionId = maxOf(retiredSessionId, it.session.sessionId); it.ended.complete(Unit) }
        active = Run(session)
        identityProbe = null
        mutableState.value = WatchUnpairState(WatchUnpairPhase.IDLE, session.peer, session.sessionId,
            WatchUnpairSupport.UNKNOWN, reason = "fresh_home_info_required", commandRunning = mutableState.value.commandRunning)
    }

    fun detach(sessionId: Long) = synchronized(lock) {
        val run = active?.takeIf { it.session.sessionId == sessionId } ?: return@synchronized
        retiredSessionId = maxOf(retiredSessionId, sessionId)
        active = null
        identityProbe = null
        run.ended.complete(Unit)
        mutableState.value = WatchUnpairState(reason = "watch_link_ended", lastResult = mutableState.value.lastResult,
            commandRunning = mutableState.value.commandRunning)
    }

    /** Call only for fresh remote wire items, including duplicates; never replay SQLite/cache rows. */
    fun offerRemoteItem(sessionId: Long, item: DataItemRecord): Boolean {
        val run = synchronized(lock) {
            active?.takeIf { it.session.sessionId == sessionId }
        } ?: return false
        val session = run.session
        if (item.scope != session.scope || item.route != session.companionRoute || item.local ||
            item.sourceNodeId != session.scope.remoteNodeId || item.uri != "wear://${session.scope.remoteNodeId}$HOME_INFO_PATH")
            return false
        val identity = if (item.deleted || item.data == null) {
            Identity(null, null, WatchUnpairSupport.UNKNOWN, "home_info_unavailable")
        } else try {
            val values = DataMapCodec.decode(checkNotNull(item.data)).values
            fun number(key: String) = when (val value = values[key]) {
                is DataMapValue.IntNumber -> value.value.toLong()
                is DataMapValue.LongNumber -> value.value
                else -> null
            }
            val code = number("HOME_VERSION_CODE")
            val name = (values["HOME_VERSION_NAME"] as? DataMapValue.Text)?.value
            val verified = code == VERIFIED_HOME_VERSION_CODE && name == VERIFIED_HOME_VERSION_NAME
            Identity(code, name, if (verified) WatchUnpairSupport.SUPPORTED else WatchUnpairSupport.UNSUPPORTED,
                if (verified) "verified_skip_wipe_receiver" else "home_version_not_verified",
                number("WEAR_ANDROID_SDK_VERSION")?.toInt())
        } catch (problem: Exception) {
            log(run, "home_info_unreadable", mapOf("exception" to problem.javaClass.simpleName))
            Identity(null, null, WatchUnpairSupport.UNKNOWN, "home_info_unreadable")
        }
        return synchronized(lock) {
            if (active !== run) return@synchronized false
            run.identity = identity
            mutableState.value = mutableState.value.copy(support = identity.support,
                homeVersionCode = identity.code, homeVersionName = identity.name, watchSdk = identity.sdk,
                reason = identity.reason)
            identityProbe?.takeIf { it.run === run }?.result?.complete(identity)
            true
        }
    }

    fun offerMessage(peer: PeerAddress, sessionId: Long, path: String, bytes: ByteArray,
        route: ApplicationRoute?): Boolean = synchronized(lock) {
        val pending = pendingAck ?: return@synchronized false
        val session = pending.run.session
        if (active !== pending.run || !pending.writeAttempted || path != ACK_PATH || peer != session.peer ||
            sessionId != session.sessionId || route != session.companionRoute) return@synchronized false
        if (bytes.isNotEmpty()) log(pending.run, "ack_payload_ignored", mapOf("bytes" to bytes.size.toString()))
        pending.result.complete(Unit).also { first ->
            if (first) log(pending.run, "ack_received", mapOf("remoteCompletion" to "unconfirmed"))
        }
    }

    override suspend fun probe(): WatchUnpairState = command {
        val checked = probeCurrent()
        // Return the observation made by this command, even if a reconnect has already
        // installed a fresh UNKNOWN state for a different physical session.
        WatchUnpairState(
            phase = if (checked.identity.support == WatchUnpairSupport.UNAVAILABLE) WatchUnpairPhase.DISCONNECTED else WatchUnpairPhase.IDLE,
            peer = checked.run?.session?.peer, sessionId = checked.run?.session?.sessionId ?: 0,
            support = checked.identity.support, homeVersionCode = checked.identity.code,
            homeVersionName = checked.identity.name, watchSdk = checked.identity.sdk,
            reason = checked.identity.reason,
        )
    }

    private suspend fun probeCurrent(): ProbeResult {
        val probe = synchronized(lock) {
            val run = active
            if (closed || run == null) null else IdentityProbe(run).also {
                run.identity = null
                identityProbe = it
                mutableState.value = WatchUnpairState(WatchUnpairPhase.PROBING, run.session.peer, run.session.sessionId,
                    WatchUnpairSupport.UNKNOWN, reason = "waiting_fresh_home_info", commandRunning = true)
            }
        } ?: return ProbeResult(null, Identity(null, null, WatchUnpairSupport.UNAVAILABLE, "watch_not_connected"))
        val observed = try {
            withTimeoutOrNull(identityTimeoutMillis) {
                whileAttached(probe.run) { requestSnapshot(probe.run.session); probe.result.await() }
            } ?: Identity(null, null, WatchUnpairSupport.UNKNOWN, "fresh_home_info_timeout")
        } catch (_: LinkEnded) {
            Identity(null, null, WatchUnpairSupport.UNAVAILABLE, "watch_link_ended")
        } catch (cancelled: CancellationException) {
            update(probe.run) { it.copy(phase = WatchUnpairPhase.IDLE, reason = "probe_cancelled") }
            throw cancelled
        } catch (problem: Exception) {
            log(probe.run, "snapshot_refresh_failed", mapOf("exception" to problem.javaClass.simpleName))
            Identity(null, null, WatchUnpairSupport.UNKNOWN, "snapshot_refresh_failed")
        } finally {
            synchronized(lock) { if (identityProbe === probe) identityProbe = null }
            probe.result.cancel()
        }
        val identity = synchronized(lock) {
            if (active === probe.run && observed.support == WatchUnpairSupport.SUPPORTED)
                probe.run.identity ?: observed else observed
        }
        update(probe.run) { it.copy(phase = WatchUnpairPhase.IDLE, support = identity.support,
            homeVersionCode = identity.code, homeVersionName = identity.name, watchSdk = identity.sdk,
            reason = identity.reason) }
        log(probe.run, "support_observed", mapOf("support" to identity.support.name,
            "reason" to identity.reason, "homeVersionCode" to (identity.code?.toString() ?: "absent"),
            "homeVersionName" to (identity.name ?: "absent"), "watchSdk" to (identity.sdk?.toString() ?: "absent")))
        return ProbeResult(probe.run, identity)
    }

    override suspend fun unpairWithoutReset(): WatchUnpairResult = command {
        val checked = probeCurrent()
        val run = checked.run
        if (run == null || checked.identity.support != WatchUnpairSupport.SUPPORTED) {
            return@command recordResult(run, WatchUnpairResult(WatchUnpairOutcome.NOT_SENT, run?.session?.peer,
                run?.session?.sessionId ?: 0, checked.identity.reason))
        }
        val pending = PendingAck(run)
        var attempted = false
        var reason = "remote_unpair_unconfirmed"
        var result: WatchUnpairResult? = null
        try {
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (active !== run || closed) throw LinkEnded()
                pendingAck = pending
                mutableState.value = mutableState.value.copy(phase = WatchUnpairPhase.SENDING, reason = "sending_skip_wipe_unpair")
            }
            val received = withTimeoutOrNull(ackTimeoutMillis) {
                whileAttached(run) {
                    currentCoroutineContext().ensureActive()
                    synchronized(lock) {
                        if (active !== run || closed) throw LinkEnded()
                        if (run.identity?.support != WatchUnpairSupport.SUPPORTED) throw IdentityChanged()
                        pending.writeAttempted = true
                        attempted = true
                    }
                    run.session.sendMessage(REQUEST_PATH, byteArrayOf(0x08, 0x00, 0x10, 0x01))
                    log(run, "request_written", mapOf("skipWipe" to "true", "eraseEsim" to "false"))
                    update(run) { it.copy(phase = WatchUnpairPhase.WAITING_FOR_ACK, reason = "waiting_unpair_receipt") }
                    pending.result.await()
                }
                true
            }
            reason = when {
                received == true -> "ack_received_receipt_only"
                pending.result.isCompleted && !pending.result.isCancelled -> "write_timeout_after_ack"
                else -> "unpair_ack_timeout"
            }
        } catch (cancelled: CancellationException) {
            reason = "unpair_cancelled"
            throw cancelled
        } catch (_: LinkEnded) {
            reason = "watch_link_ended"
        } catch (_: IdentityChanged) {
            reason = "home_version_changed_before_write"
        } catch (problem: Exception) {
            reason = "unpair_write_failed"
            log(run, "write_failed", mapOf("exception" to problem.javaClass.simpleName))
        } finally {
            synchronized(lock) { if (pendingAck === pending) pendingAck = null }
            val acknowledged = pending.result.isCompleted && !pending.result.isCancelled
            pending.result.cancel()
            if (attempted) {
                withContext(NonCancellable) {
                    if (acknowledged) awaitRemoteCleanup(run)
                    synchronized(lock) { run.identity = null }
                    update(run) { it.copy(phase = WatchUnpairPhase.CLEANING_UP, support = WatchUnpairSupport.UNKNOWN,
                        homeVersionCode = null, homeVersionName = null, watchSdk = null, reason = "local_cleanup") }
                    val cleanupFailure = try { cleanup(run.session.peer); null }
                    catch (problem: Exception) {
                        log(run, "local_cleanup_failed", mapOf("exception" to problem.javaClass.simpleName))
                        problem.javaClass.simpleName
                    }
                    result = recordResult(run, WatchUnpairResult(
                        if (acknowledged) WatchUnpairOutcome.ACK_RECEIVED else WatchUnpairOutcome.UNCONFIRMED,
                        run.session.peer, run.session.sessionId, reason, cleanupFailure))
                }
            } else result = recordResult(run, WatchUnpairResult(WatchUnpairOutcome.NOT_SENT,
                run.session.peer, run.session.sessionId, reason))
        }
        checkNotNull(result)
    }

    /** Sends nothing, so it can't reset a watch and needs no version check. */
    override suspend fun clearLocalPairing(peer: PeerAddress): WatchUnpairResult = command {
        val blocking = synchronized(lock) {
            val run = active
            if (closed || run != null) return@synchronized run to true
            mutableState.value = mutableState.value.copy(phase = WatchUnpairPhase.CLEANING_UP, reason = "local_cleanup")
            null to false
        }
        if (blocking.second) {
            return@command recordResult(blocking.first, WatchUnpairResult(WatchUnpairOutcome.NOT_SENT, peer,
                blocking.first?.session?.sessionId ?: 0, "watch_still_connected"))
        }
        withContext(NonCancellable) {
            val cleanupFailure = try { cleanup(peer); null }
            catch (problem: Exception) {
                log(null, "local_cleanup_failed", mapOf("exception" to problem.javaClass.simpleName))
                problem.javaClass.simpleName
            }
            recordResult(null, WatchUnpairResult(WatchUnpairOutcome.LOCAL_ONLY, peer, 0,
                "local_only_nothing_sent", cleanupFailure))
        }
    }

    /**
     * WCS executes the command only after its ACK send future succeeds. Closing RFCOMM/removing
     * the bond on receipt can race that callback. Let the watch close first, or retain the live
     * transport for up to five seconds, based on the stock client's bond-removal delay.
     * Neither observation proves cleanup.
     * This also runs after caller cancellation so cancelling the phone UI cannot cut that window.
     */
    private suspend fun awaitRemoteCleanup(run: Run) {
        if (remoteCleanupGraceMillis == 0L || run.ended.isCompleted) return
        update(run) { it.copy(phase = WatchUnpairPhase.WAITING_FOR_WATCH, reason = "waiting_remote_unpair_execution") }
        log(run, "remote_cleanup_wait_started", mapOf("maximumMillis" to remoteCleanupGraceMillis.toString()))
        val watchEndedLink = withTimeoutOrNull(remoteCleanupGraceMillis) { run.ended.await(); true } == true
        log(run, "remote_cleanup_wait_finished", mapOf("watchLinkEnded" to watchEndedLink.toString(),
            "remoteCompletion" to "unconfirmed"))
    }

    /** Both a blocked snapshot refresh and a blocked send must stop when this physical link ends. */
    private suspend fun <T> whileAttached(run: Run, work: suspend () -> T): T = supervisorScope {
        val task = async {
            synchronized(lock) { if (active !== run || closed) throw LinkEnded() }
            work()
        }
        try {
            select {
                run.ended.onAwait { throw LinkEnded() }
                task.onAwait { it }
            }
        } finally { task.cancel() }
    }

    private suspend fun <T> command(work: suspend () -> T): T = commands.withLock {
        synchronized(lock) { mutableState.value = mutableState.value.copy(commandRunning = true) }
        try {
            coroutineScope {
                val job = currentCoroutineContext()[Job]
                val registration = lifetime.invokeOnCompletion { job?.cancel(CancellationException("watch_unpair_closed")) }
                try { currentCoroutineContext().ensureActive(); work() }
                finally { registration.dispose() }
            }
        } finally {
            synchronized(lock) { mutableState.value = mutableState.value.copy(commandRunning = false) }
        }
    }

    private fun update(run: Run, transform: (WatchUnpairState) -> WatchUnpairState) = synchronized(lock) {
        if (!closed && active === run) mutableState.value = transform(mutableState.value)
    }

    private fun recordResult(run: Run?, result: WatchUnpairResult): WatchUnpairResult {
        synchronized(lock) {
            val snapshot = mutableState.value
            mutableState.value = snapshot.copy(
                phase = if (!closed && (active == null || active === run)) WatchUnpairPhase.FINISHED else snapshot.phase,
                reason = if (!closed && (active == null || active === run)) result.reason else snapshot.reason,
                lastResult = result,
            )
        }
        log(run, "unpair_result", mapOf("outcome" to result.outcome.name, "reason" to result.reason,
            "localCleanupFailure" to (result.localCleanupFailure ?: "none"), "remoteCompletion" to "unconfirmed"))
        return result
    }

    private fun log(run: Run?, event: String, details: Map<String, String>) {
        runCatching { diagnostics.record(ProtocolDiagnostic("watch_unpair", event, peer = run?.session?.peer,
            sessionId = run?.session?.sessionId ?: 0, details = details)) }
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        active?.ended?.complete(Unit)
        active = null
        identityProbe = null
        mutableState.value = WatchUnpairState(phase = WatchUnpairPhase.CLOSED, reason = "watch_unpair_closed",
            lastResult = mutableState.value.lastResult, commandRunning = mutableState.value.commandRunning)
        lifetime.cancel()
    }

    companion object {
        const val HOME_INFO_PATH = "/settings/home_info"
        const val REQUEST_PATH = "/companion_unpaired/rpc"
        const val ACK_PATH = "/companion_unpaired/ack"
        const val VERIFIED_HOME_VERSION_CODE = 790_427_547L
        const val VERIFIED_HOME_VERSION_NAME = "1.15.81.626317553"
    }
}
