package dev.ticbox.companion.engine.setup

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import java.time.Clock
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Service-owned first-setup workflow. A run owns one Channel and one physical session identity.
 * Channel ACKs, RPC responses, watch COMPLETE and watch OOBE_DONE are separate observations.
 */
class SetupEngine(
    scope: CoroutineScope,
    private val backend: ChannelBackend,
    private val rpc: SetupRpcTransport,
    private val environment: SetupEnvironment = SetupEnvironment.NONE,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val clock: Clock = Clock.systemUTC(),
    private val timeZone: () -> String = { ZoneId.systemDefault().id },
    private val config: SetupConfig = SetupConfig(),
) : SetupClient {
    private val supervisor = SupervisorJob(scope.coroutineContext[Job])
    private val owner = CoroutineScope(scope.coroutineContext + supervisor)
    private val lock = Any()
    private val ids = AtomicLong()
    private val actions = AtomicLong()
    private val mutableState = MutableStateFlow(SetupSnapshot())
    override val state: StateFlow<SetupSnapshot> = mutableState.asStateFlow()
    private var active: Run? = null
    private var closed = false
    private val checkinRegistration = rpc.registerRequestHandler(SetupProtocol.CHECKIN_UPDATE_PATH, ::onCheckinUpdate)

    private data class Observations(
        val status: Int? = null, val statusRevision: Long = 0,
        val system: SetupSystemInfo? = null, val systemRevision: Long = 0,
        val cell: SetupCellularInfo? = null, val connectionRevision: Long = 0,
        val checkin: SetupCheckinStatus? = null, val checkinRevision: Long = 0,
        val queryFailure: SetupFailure? = null,
        val complete: Boolean = false, val oobe: Boolean = false,
        val failure: SetupFailure? = null,
    )
    private data class Query(val kind: SetupQuery, val id: Long?)
    private class Run(
        val id: Long, val peer: PeerAddress, val session: Long,
        val options: SetupOptions?, parent: CoroutineScope,
    ) {
        val job = SupervisorJob(parent.coroutineContext[Job])
        val scope = CoroutineScope(parent.coroutineContext + job)
        val channel = AtomicReference<WearChannel?>()
        val observations = MutableStateFlow(Observations())
        val monitor = Any()
        val sendMutex = Mutex()
        val result = CompletableDeferred<SetupProbe>()
        var pendingQuery: Query? = null
        var nextCommandId = 1L
        var action: Pair<SetupUserAction, CompletableDeferred<SetupUserResponse>>? = null
        var intentionalClose = false
    }

    override suspend fun probe(): SetupProbe {
        val run = newRun(null)
        launchRun(run)
        return try { run.result.await() } catch (e: CancellationException) {
            stop(run)
            throw e
        }
    }

    override suspend fun start(options: SetupOptions) {
        val snapshotOptions = options.copy(consents = options.consents.toList(), optionalSteps = options.optionalSteps.distinct())
        synchronized(lock) {
            val current = active
            if (current != null && current.options == snapshotOptions && current.job.isActive) {
                log(current, "start_already_active", DiagnosticSeverity.INFO)
                return
            }
        }
        launchRun(newRun(snapshotOptions))
    }

    private fun newRun(options: SetupOptions?): Run = synchronized(lock) {
        check(!closed && supervisor.isActive) { "Setup engine is closed" }
        if (active != null) throw SetupBusyException()
        val link = backend.state.value
        val peer = link.peer
        if (link.phase != ConnectionPhase.LINK_READY || peer == null || link.sessionId == 0L) {
            throw SetupWorkflowException(SetupFailure(SetupFailureKind.LINK_LOST, "setup_requires_ready_link"))
        }
        Run(ids.incrementAndGet(), peer, link.sessionId, options, owner).also {
            active = it
            mutableState.value = SetupSnapshot(runId = it.id, peer = peer, sessionId = it.session, phase = SetupPhase.RUNNING)
            val run = it
            run.job.invokeOnCompletion {
                synchronized(lock) {
                    if (active === run) {
                        val snapshot = mutableState.value
                        if (snapshot.phase == SetupPhase.RUNNING || snapshot.phase == SetupPhase.WAITING_FOR_USER) {
                            val failure = run.observations.value.failure
                            mutableState.value = snapshot.copy(phase = if (failure == null) SetupPhase.CANCELLED else SetupPhase.FAILED, failure = failure, action = null)
                        }
                        active = null
                    }
                }
                if (!run.result.isCompleted) run.result.cancel()
            }
        }
    }

    override fun resume(actionId: Long, response: SetupUserResponse): Boolean {
        val run = synchronized(lock) { active } ?: return false
        val accepted = synchronized(run.monitor) {
            val pending = run.action
            if (pending == null || pending.first.id != actionId || response.decision !in pending.first.allowedDecisions) false
            else if (pending.first.kind == SetupActionKind.REVIEW_TERMS && response.decision == SetupDecision.CONTINUE && response.termsAccepted == null) false
            else if (pending.first.kind == SetupActionKind.CHOOSE_ACCOUNT && response.decision == SetupDecision.CONTINUE && response.accountChoice == SetupAccountChoice.ASK) false
            else pending.second.complete(response.copy(consents = response.consents.toList()))
        }
        if (!accepted) log(run, "user_action_ignored", details = mapOf("actionId" to actionId.toString()))
        return accepted
    }

    override fun cancel() { synchronized(lock) { active }?.let(::stop) }

    override fun close() {
        val run = synchronized(lock) { if (closed) return; closed = true; active }
        run?.let(::stop)
        runCatching { checkinRegistration.close() }
        supervisor.cancel()
    }

    private fun stop(run: Run) {
        synchronized(run.monitor) { run.intentionalClose = true }
        run.channel.get()?.close()
        run.job.cancel(CancellationException("setup_cancelled"))
        synchronized(lock) {
            if (active === run) {
                if (mutableState.value.phase in setOf(SetupPhase.RUNNING, SetupPhase.WAITING_FOR_USER)) {
                    mutableState.value = mutableState.value.copy(phase = SetupPhase.CANCELLED, action = null, currentStep = null)
                }
                active = null
            }
        }
        if (!run.result.isCompleted) run.result.cancel()
    }

    private fun launchRun(run: Run) {
        run.scope.launch {
            try {
                coroutineScope {
                    val watcher = launch {
                        backend.state.collect { link ->
                            if (link.phase != ConnectionPhase.LINK_READY || link.peer != run.peer || link.sessionId != run.session) {
                                failRun(run, SetupFailure(SetupFailureKind.LINK_LOST, "setup_session_ended"))
                            }
                        }
                    }
                    step(run, SetupStep.CHANNEL)
                    val channel = withTimeout(config.queryTimeoutMillis) { backend.openChannel(SetupProtocol.CHANNEL_PATH) }
                    run.channel.set(channel)
                    if (channel.info.peer != run.peer || channel.info.sessionId != run.session) {
                        throw SetupWorkflowException(SetupFailure(SetupFailureKind.LINK_LOST, "setup_channel_session_changed"))
                    }
                    stepDone(run, SetupStep.CHANNEL)
                    val reader = launch { read(run, channel) }
                    try {
                        val probe = queryProbe(run)
                        run.result.complete(probe)
                        if (run.options == null) {
                            publish(run) { it.copy(phase = SetupPhase.PROBED, currentStep = null) }
                        } else {
                            workflow(run, run.options)
                            val observed = run.observations.value
                            log(run, "workflow_completed", DiagnosticSeverity.INFO, mapOf(
                                "watchStatus" to (observed.status?.toString() ?: "absent"),
                                "watchCompleteObserved" to observed.complete.toString(),
                                "watchOobeDoneObserved" to observed.oobe.toString(),
                                "checkinChoice" to run.options.checkinChoice.name,
                                "waitForOobeDone" to run.options.waitForOobeDone.toString(),
                                "oobeAdvertised" to (6 in (observed.system?.effectiveSetupCapabilities ?: emptyList())).toString(),
                            ))
                            publish(run) { it.copy(phase = SetupPhase.COMPLETED, currentStep = null, action = null) }
                        }
                    } finally {
                        synchronized(run.monitor) { run.intentionalClose = true }
                        reader.cancel()
                        watcher.cancel()
                    }
                }
            } catch (e: CancellationException) {
                val failure = run.observations.value.failure
                if (failure != null) finishFailure(run, failure)
                else if (e is TimeoutCancellationException) finishFailure(run, SetupFailure(SetupFailureKind.TIMEOUT, "setup_operation_timeout"))
                else publish(run) { it.copy(phase = SetupPhase.CANCELLED, action = null, currentStep = null) }
                if (!run.result.isCompleted) run.result.completeExceptionally(e)
            } catch (e: Exception) {
                val failure = when (e) {
                    is SetupWorkflowException -> e.failure
                    is ProtocolException -> SetupFailure(SetupFailureKind.PROTOCOL, "setup_framing_invalid")
                    is ChannelClosedException -> channelFailure(run, "setup_channel_closed_${e.reason.name.lowercase()}", e.reason)
                    else -> SetupFailure(SetupFailureKind.UNEXPECTED, "setup_operation_failed_${e.javaClass.simpleName}")
                }
                finishFailure(run, failure)
                if (!run.result.isCompleted) run.result.completeExceptionally(SetupWorkflowException(failure))
            } finally {
                synchronized(run.monitor) { run.intentionalClose = true; run.action = null }
                run.channel.getAndSet(null)?.close()
                synchronized(lock) { if (active === run) active = null }
                run.job.cancel()
            }
        }
    }

    private suspend fun queryProbe(run: Run): SetupProbe {
        step(run, SetupStep.PROBE)
        query(run, SetupQuery.GET_STATUS)
        try { query(run, SetupQuery.GET_SYSTEM_INFO) } catch (e: SetupWorkflowException) {
            if (e.failure.kind in setOf(SetupFailureKind.LINK_LOST, SetupFailureKind.CHANNEL_CLOSED)) throw e
            log(run, "system_info_unavailable", details = mapOf("reason" to e.failure.reason))
        }
        // Stock initialization queries status/system info only. On a non-cellular watch the
        // reference connection-info provider returns null, which its response builder rejects.
        // Keep handling incoming requests/responses without probing this optional watch service.
        val observation = run.observations.value
        val probe = SetupProbe(run.peer, run.session, observation.status, observation.system, observation.cell, observation.connectionRevision != 0L)
        publish(run) { it.copy(probe = probe) }
        stepDone(run, SetupStep.PROBE)
        return probe
    }

    private suspend fun workflow(run: Run, options: SetupOptions) {
        step(run, SetupStep.WATCH_READY)
        val ready = await(run, config.readyTimeoutMillis, "watch_ready_timeout") { it.status in setOf(1, 3, 5) }
        stepDone(run, SetupStep.WATCH_READY)
        val info = ready.system ?: SetupSystemInfo().also { log(run, "setup_capabilities_unknown") }
        val context = SetupContext(run.peer, run.session, info)
        if (ready.complete || ready.oobe) {
            log(run, "watch_already_complete", DiagnosticSeverity.INFO, mapOf(
                "watchStatus" to (ready.status?.toString() ?: "absent"),
                "watchCompleteObserved" to ready.complete.toString(), "watchOobeDoneObserved" to ready.oobe.toString(),
            ))
            // A prior run may have delivered COMPLETE while the watch still waits for the
            // restore choice before starting OEM initialization. Resume that local choice.
            if (!ready.oobe) skipUnselectedRestore(run, context, options)
            waitOobe(run, info, options)
            return
        }

        step(run, SetupStep.TIME)
        send(run, SetupProtocol.encodeTime(clock.millis(), timeZone()))
        stepDone(run, SetupStep.TIME)
        configureTether(run, context, options.tetherMode)

        step(run, SetupStep.TERMS)
        val decisions = linkedMapOf<SetupOptinType, SetupConsent>()
        options.consents.forEach { decisions[it.type] = it }
        val terms = options.termsAccepted ?: ask(run, SetupActionKind.REVIEW_TERMS, "terms_decision_required", setOf(SetupDecision.CONTINUE, SetupDecision.CANCEL)).let {
            it.consents.forEach { consent -> decisions[consent.type] = consent }
            it.termsAccepted == true
        }
        if (!terms) throw CancellationException("terms_declined")
        // These encode only caller supplied decisions. The engine never synthesizes ACCEPTED opt-ins.
        for (consent in decisions.values) send(run, SetupProtocol.encodeOptin(consent.type.wireValue, consent.choice.wireValue, consent.decidedAtEpochMillis))
        stepDone(run, SetupStep.TERMS)

        prepareData(run, context, decisions.values.toList(), options.requireInitialDataPreparation)
        account(run, context, options.accountChoice)
        skipUnselectedRestore(run, context, options)
        runCheckin(run, context, options.checkinChoice)
        for (optional in options.optionalSteps) {
            step(run, SetupStep.OPTIONAL)
            val completed = dependency(run, SetupActionKind.OPTIONAL_STEP, "optional_${optional.name.lowercase()}", allowSkip = true, optional = optional) {
                environment.runOptionalStep(context, optional)
            }
            if (!completed && optional == SetupOptionalStep.RESTORE && 1 in info.effectiveSetupCapabilities) notifyRestoreSkipped(run)
            stepDone(run, SetupStep.OPTIONAL, if (completed) SetupStepState.SUCCEEDED else SetupStepState.SKIPPED)
        }
        if (options.awaitDataSyncCompletion) {
            step(run, SetupStep.DATA_SYNC_COMPLETION)
            dependency(run, SetupActionKind.DATA_SYNC_COMPLETION, "data_sync_completion_required", allowSkip = false) {
                environment.awaitDataSyncCompletion(context)
            }
            publish(run) { it.copy(dataSyncCompletionObserved = true) }
            stepDone(run, SetupStep.DATA_SYNC_COMPLETION)
        } else {
            log(run, "data_sync_completion_not_requested", DiagnosticSeverity.INFO)
            stepDone(run, SetupStep.DATA_SYNC_COMPLETION, SetupStepState.SKIPPED)
        }
        complete(run)
        waitOobe(run, info, options)
    }

    private suspend fun configureTether(run: Run, context: SetupContext, mode: SetupTetherMode) {
        step(run, SetupStep.TETHER)
        val capabilities = context.systemInfo.effectiveSetupCapabilities
        val needed = when (mode) { SetupTetherMode.STANDALONE -> 9; SetupTetherMode.RESTRICTED -> 10; SetupTetherMode.TETHERED -> null }
        if (needed != null && needed !in capabilities) {
            throw SetupWorkflowException(SetupFailure(SetupFailureKind.UNSUPPORTED_MODE, "tether_mode_not_advertised"))
        }
        if (5 in capabilities) {
            val response = rpc.request(run.peer, run.session, SetupProtocol.TETHER_CONFIG_PATH, SetupRpcCodec.encodeTetherConfig(mode.wireValue), config.queryTimeoutMillis)
            observeEmptyRpcResponse(run, "tether", response)
            stepDone(run, SetupStep.TETHER)
        } else {
            log(run, "tether_config_not_advertised", DiagnosticSeverity.INFO)
            stepDone(run, SetupStep.TETHER, SetupStepState.SKIPPED)
        }
    }

    private suspend fun skipUnselectedRestore(run: Run, context: SetupContext, options: SetupOptions) {
        if (1 !in context.systemInfo.effectiveSetupCapabilities || SetupOptionalStep.RESTORE in options.optionalSteps) return
        step(run, SetupStep.RESTORE)
        notifyRestoreSkipped(run)
        stepDone(run, SetupStep.RESTORE, SetupStepState.SKIPPED)
    }

    private suspend fun notifyRestoreSkipped(run: Run) {
        // On restore-capable stock firmware, even SKIPPED invokes OemAdapter. Omitting
        // the choice can leave WaitingController waiting after the COMPLETE exchange.
        val response = rpc.request(run.peer, run.session, SetupProtocol.RESTORE_STATUS_PATH, SetupRpcCodec.encodeRestoreSkipped(), config.queryTimeoutMillis)
        observeEmptyRpcResponse(run, "restore", response)
        log(run, "restore_choice_notified", DiagnosticSeverity.INFO, mapOf("status" to "1", "choice" to "SKIPPED"))
    }

    private fun observeEmptyRpcResponse(run: Run, kind: String, response: ByteArray) {
        try {
            val unknown = SetupRpcCodec.decodeEmptyResponse(response)
            if (unknown.isNotEmpty()) log(run, "${kind}_response_extra_fields", details = mapOf("fields" to unknown.take(32).joinToString(",") { it.number.toString() }, "count" to unknown.size.toString()))
        } catch (_: ProtocolException) {
            // Correlation remains useful if a future empty-response RPC appends an opaque
            // body. Only subsequent watch observations can confirm setup completion.
            log(run, "${kind}_response_opaque", details = mapOf("bytes" to response.size.toString()))
        }
    }

    private suspend fun prepareData(run: Run, context: SetupContext, consents: List<SetupConsent>, required: Boolean) {
        step(run, SetupStep.INITIAL_DATA_PREPARATION)
        if (required) {
            dependency(run, SetupActionKind.INITIAL_DATA_PREPARATION, "initial_data_preparation_required", allowSkip = false) { environment.prepareInitialData(context, consents) }
            publish(run) { it.copy(dataPreparationCompleted = true) }
            stepDone(run, SetupStep.INITIAL_DATA_PREPARATION)
        } else {
            val result = invokeDependency(run) { environment.prepareInitialData(context, consents) }
            val completed = result.state == SetupDependencyState.COMPLETE
            publish(run) { it.copy(dataPreparationCompleted = completed) }
            if (!completed) log(run, "initial_data_preparation_unavailable", details = mapOf("reason" to result.reason))
            stepDone(run, SetupStep.INITIAL_DATA_PREPARATION, if (completed) SetupStepState.SUCCEEDED else SetupStepState.SKIPPED)
        }
    }

    private suspend fun account(run: Run, context: SetupContext, initialChoice: SetupAccountChoice) {
        step(run, SetupStep.ACCOUNT)
        var choice = initialChoice
        if (choice == SetupAccountChoice.ASK) choice = ask(run, SetupActionKind.CHOOSE_ACCOUNT, "account_choice_required", setOf(SetupDecision.CONTINUE, SetupDecision.CANCEL)).accountChoice
        if (choice == SetupAccountChoice.SKIP) {
            // The official ordinary setup permits skipping account transfer. GMS check-in is independent.
            stepDone(run, SetupStep.ACCOUNT, SetupStepState.SKIPPED)
        } else {
            val transferred = dependency(run, SetupActionKind.ACCOUNT_TRANSFER, "account_transfer_required", allowSkip = true) { environment.transferGoogleAccount(context) }
            stepDone(run, SetupStep.ACCOUNT, if (transferred) SetupStepState.SUCCEEDED else SetupStepState.SKIPPED)
        }
    }

    private suspend fun runCheckin(run: Run, context: SetupContext, choice: SetupCheckinChoice) {
        step(run, SetupStep.CHECKIN)
        if (choice == SetupCheckinChoice.SKIP) {
            skipCheckin(run, "not_requested")
            return
        }
        if (2 !in context.systemInfo.effectiveSetupCapabilities) {
            skipCheckin(run, "not_advertised")
            return
        }
        while (true) {
            val result = try { attemptCheckin(run) } catch (e: SetupWorkflowException) {
                if (e.failure.kind in setOf(SetupFailureKind.LINK_LOST, SetupFailureKind.CHANNEL_CLOSED)) throw e
                log(run, "checkin_wait_failed", details = mapOf("reason" to e.failure.reason))
                CheckinAttempt.FAILED
            }
            when (result) {
                CheckinAttempt.SUCCEEDED -> { stepDone(run, SetupStep.CHECKIN); return }
                CheckinAttempt.SKIPPED -> { skipCheckin(run, "user_skipped_wait"); return }
                CheckinAttempt.FAILED -> Unit
            }
            val response = ask(run, SetupActionKind.CHECKIN,
                if (run.observations.value.checkin?.factoryReset == true) "watch_reports_factory_reset" else "watch_checkin_not_complete",
                setOf(SetupDecision.RETRY, SetupDecision.SKIP_OPTIONAL, SetupDecision.CANCEL))
            if (response.decision == SetupDecision.SKIP_OPTIONAL) {
                skipCheckin(run, "user_skipped_after_failure")
                return
            }
        }
    }

    private fun skipCheckin(run: Run, reason: String) {
        log(run, "checkin_skipped", DiagnosticSeverity.INFO, mapOf("reason" to reason))
        stepDone(run, SetupStep.CHECKIN, SetupStepState.SKIPPED)
    }

    private enum class CheckinAttempt { SUCCEEDED, FAILED, SKIPPED }

    /** A visible action races the query, so skipping also cancels an RPC that has not replied. */
    private suspend fun attemptCheckin(run: Run): CheckinAttempt = coroutineScope {
        val action = SetupUserAction(actions.incrementAndGet(), SetupActionKind.CHECKIN,
            setOf(SetupDecision.SKIP_OPTIONAL, SetupDecision.CANCEL), "watch_checkin_in_progress")
        val answer = CompletableDeferred<SetupUserResponse>()
        synchronized(run.monitor) { run.action = action to answer }
        publish(run) { it.copy(action = action) }
        log(run, "checkin_wait_started", DiagnosticSeverity.INFO, mapOf("actionId" to action.id.toString()))
        val checking = async { waitCheckin(run) }
        try {
            select {
                answer.onAwait { response ->
                    if (response.decision == SetupDecision.CANCEL) throw CancellationException("setup_user_cancelled")
                    CheckinAttempt.SKIPPED
                }
                checking.onAwait { if (it) CheckinAttempt.SUCCEEDED else CheckinAttempt.FAILED }
            }
        } finally {
            checking.cancel()
            answer.cancel()
            synchronized(run.monitor) { if (run.action?.first?.id == action.id) run.action = null }
            publish(run) { if (it.action?.id == action.id) it.copy(action = null) else it }
        }
    }

    private suspend fun waitCheckin(run: Run): Boolean = try {
        withTimeout(config.checkinTimeoutMillis) {
            var failureRechecked = false
            var queriedThisAttempt = false
            while (true) {
                if (queriedThisAttempt && run.observations.value.checkin?.statusValue == 4) {
                    if (failureRechecked) return@withTimeout false
                    failureRechecked = true
                    // Stock can report FAILURE, wait for our empty RPC reply, then apply
                    // its own non-reset fallback. Re-query once after the report can settle.
                    log(run, "checkin_failure_recheck", DiagnosticSeverity.INFO)
                    delay(config.checkinPollMillis)
                    throwFailure(run.observations.value)
                }
                if (run.observations.value.checkin?.statusValue == 3) return@withTimeout true
                val before = run.observations.value.checkinRevision
                val bytes = rpc.request(run.peer, run.session, SetupProtocol.CHECKIN_REQUEST_PATH, byteArrayOf(), config.queryTimeoutMillis)
                val decoded = SetupRpcCodec.decodeCheckin(bytes)
                val queried = parseCheckin(run, decoded, "response")
                // Explicit retry re-queries immediately, rather than delaying on the last
                // attempt's retained FAILURE observation before contacting the watch.
                queriedThisAttempt = true
                // A later push update can overtake the request response; do not overwrite it with stale state.
                val responseIsCurrent = run.observations.value.checkinRevision == before
                if (responseIsCurrent) updateCheckin(run, queried)
                else log(run, "checkin_response_overtaken", DiagnosticSeverity.INFO)
                val current = run.observations.value.checkin ?: queried
                if (current.statusValue == 3 || (responseIsCurrent && queried.statusValue == 2)) return@withTimeout true
                if (current.statusValue == 4) continue
                val revision = run.observations.value.checkinRevision
                val update = withTimeoutOrNull(config.checkinPollMillis) {
                    run.observations.first { it.checkinRevision > revision || it.failure != null }
                }
                throwFailure(update ?: run.observations.value)
                if (update?.checkin?.statusValue == 3) return@withTimeout true
            }
            @Suppress("UNREACHABLE_CODE") false
        }
    } catch (_: TimeoutCancellationException) {
        log(run, "checkin_timeout")
        false
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        log(run, "checkin_query_failed", details = mapOf("exception" to e.javaClass.simpleName))
        false
    }

    private suspend fun complete(run: Run) {
        step(run, SetupStep.COMPLETE)
        repeat(config.completionAttempts) { attempt ->
            if (run.observations.value.let { it.complete || it.oobe }) { stepDone(run, SetupStep.COMPLETE); return }
            send(run, SetupProtocol.encodeStatus(3))
            val observed = withTimeoutOrNull(config.completionWaitMillis) {
                run.observations.first { it.complete || it.oobe || it.failure != null }.also(::throwFailure)
            }
            if (observed?.let { it.complete || it.oobe } == true) { stepDone(run, SetupStep.COMPLETE); return }
            log(run, "complete_ack_wait_elapsed", details = mapOf("attempt" to (attempt + 1).toString()))
        }
        throw SetupWorkflowException(SetupFailure(SetupFailureKind.TIMEOUT, "watch_complete_not_observed"))
    }

    private suspend fun waitOobe(run: Run, info: SetupSystemInfo, options: SetupOptions) {
        if (!options.waitForOobeDone || 6 !in info.effectiveSetupCapabilities) {
            stepDone(run, SetupStep.OOBE, SetupStepState.SKIPPED)
            return
        }
        step(run, SetupStep.OOBE)
        await(run, config.oobeTimeoutMillis, "watch_oobe_not_observed") { it.oobe }
        stepDone(run, SetupStep.OOBE)
    }

    private suspend fun dependency(run: Run, kind: SetupActionKind, reason: String, allowSkip: Boolean, optional: SetupOptionalStep? = null, block: suspend () -> SetupDependencyResult): Boolean {
        while (true) {
            val result = invokeDependency(run, block)
            if (result.state == SetupDependencyState.COMPLETE) return true
            log(run, "dependency_not_complete", details = mapOf("kind" to kind.name, "state" to result.state.name, "reason" to result.reason))
            val allowed = if (allowSkip) setOf(SetupDecision.RETRY, SetupDecision.SKIP_OPTIONAL, SetupDecision.CANCEL) else setOf(SetupDecision.RETRY, SetupDecision.CANCEL)
            if (ask(run, kind, result.reason.ifEmpty { reason }, allowed, optional).decision == SetupDecision.SKIP_OPTIONAL) return false
        }
    }

    private suspend fun invokeDependency(run: Run, block: suspend () -> SetupDependencyResult): SetupDependencyResult = try {
        withTimeout(config.dependencyTimeoutMillis) { block() }
    } catch (_: TimeoutCancellationException) {
        SetupDependencyResult(SetupDependencyState.RETRYABLE_FAILURE, "dependency_timeout")
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        log(run, "dependency_failed", details = mapOf("exception" to e.javaClass.simpleName))
        SetupDependencyResult(SetupDependencyState.RETRYABLE_FAILURE, "dependency_failed")
    }

    private suspend fun ask(run: Run, kind: SetupActionKind, reason: String, allowed: Set<SetupDecision>, optional: SetupOptionalStep? = null): SetupUserResponse {
        val action = SetupUserAction(actions.incrementAndGet(), kind, allowed, reason, optional)
        val answer = CompletableDeferred<SetupUserResponse>(run.job)
        synchronized(run.monitor) { run.action = action to answer }
        log(run, "user_action_required", DiagnosticSeverity.INFO, mapOf("actionId" to action.id.toString(), "kind" to kind.name, "reason" to reason))
        publish(run) { snapshot -> snapshot.copy(phase = SetupPhase.WAITING_FOR_USER, action = action,
            progress = snapshot.progress.map { if (it.step == snapshot.currentStep) it.copy(state = SetupStepState.WAITING) else it }) }
        val response = try { answer.await() } finally { synchronized(run.monitor) { if (run.action?.first?.id == action.id) run.action = null } }
        if (response.decision == SetupDecision.CANCEL) throw CancellationException("setup_user_cancelled")
        publish(run) { snapshot -> snapshot.copy(phase = SetupPhase.RUNNING, action = null,
            progress = snapshot.progress.map { if (it.step == snapshot.currentStep) it.copy(state = SetupStepState.RUNNING) else it }) }
        return response
    }

    private suspend fun query(run: Run, kind: SetupQuery) {
        val base = run.observations.value
        // Stock's GET_SYSTEM_INFO has no ID; STATUS queries do. Replies contain no echoed ID.
        val id = if (kind == SetupQuery.GET_SYSTEM_INFO) null else run.nextCommandId++
        synchronized(run.monitor) { run.pendingQuery = Query(kind, id); run.observations.value = run.observations.value.copy(queryFailure = null) }
        try {
            log(run, "query_started", DiagnosticSeverity.INFO, mapOf("query" to kind.name, "commandId" to (id?.toString() ?: "absent")))
            send(run, SetupProtocol.encodeQuery(kind, id))
            val observed = await(run, config.queryTimeoutMillis, "query_${kind.name.lowercase()}_timeout") {
                it.queryFailure != null || when (kind) {
                    SetupQuery.GET_STATUS -> it.statusRevision > base.statusRevision
                    SetupQuery.GET_SYSTEM_INFO -> it.systemRevision > base.systemRevision
                    SetupQuery.GET_CONNECTION_INFO -> it.connectionRevision > base.connectionRevision
                }
            }
            observed.queryFailure?.let { throw SetupWorkflowException(it) }
            log(run, "query_finished", DiagnosticSeverity.INFO, mapOf("query" to kind.name))
        } finally { synchronized(run.monitor) { run.pendingQuery = null } }
    }

    private suspend fun await(run: Run, timeout: Long, reason: String, predicate: (Observations) -> Boolean): Observations = try {
        withTimeout(timeout) { run.observations.first { it.failure != null || predicate(it) }.also(::throwFailure) }
    } catch (_: TimeoutCancellationException) { throw SetupWorkflowException(SetupFailure(SetupFailureKind.TIMEOUT, reason)) }

    private suspend fun send(run: Run, payload: ByteArray) = run.sendMutex.withLock {
        throwFailure(run.observations.value)
        val channel = run.channel.get() ?: throw SetupWorkflowException(channelFailure(run, "setup_channel_missing"))
        channel.write(SetupProtocol.frame(payload))
    }

    private suspend fun read(run: Run, channel: WearChannel) {
        val decoder = SetupFrameDecoder()
        try {
            while (true) {
                val chunk = channel.read(config.readChunkBytes) ?: break
                for (frame in decoder.push(chunk)) {
                    try { handle(run, SetupProtocol.decode(frame)) } catch (e: ProtocolException) {
                        // The length frame is intact. The next frame remains independently parseable.
                        log(run, "malformed_setup_message", details = mapOf("bytes" to frame.size.toString(), "exception" to e.javaClass.simpleName))
                    }
                }
            }
            decoder.finish()
            val observed = run.observations.value
            val completionSatisfied = observed.oobe || (observed.complete &&
                (run.options?.waitForOobeDone == false || 6 !in (observed.system?.effectiveSetupCapabilities ?: emptyList())))
            if (!synchronized(run.monitor) { run.intentionalClose } && !completionSatisfied) {
                log(run, "input_ended", details = mapOf(
                    "pendingQuery" to (synchronized(run.monitor) { run.pendingQuery?.kind?.name } ?: "none"),
                    "watchStatus" to (observed.status?.toString() ?: "absent"),
                    "systemInfoReceived" to (observed.systemRevision != 0L).toString(),
                    "connectionInfoReceived" to (observed.connectionRevision != 0L).toString(),
                    "linkPhase" to backend.state.value.phase.name,
                    "channelPhase" to channel.state.value.phase.name,
                    "channelEndReason" to (channel.state.value.endReason?.name ?: "absent"),
                ))
                failRun(run, channelFailure(run, "setup_input_ended", channel.state.value.endReason))
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (!synchronized(run.monitor) { run.intentionalClose }) failRun(run,
                if (e is ProtocolException) SetupFailure(SetupFailureKind.PROTOCOL, "setup_framing_invalid")
                else channelFailure(run, "setup_channel_read_failed", (e as? ChannelClosedException)?.reason))
        }
    }

    private suspend fun handle(run: Run, message: DecodedStruct) {
        logNotices(run, message)
        if (message.unknownFields.isNotEmpty()) log(run, "unknown_setup_fields", details = mapOf("fields" to message.unknownFields.take(32).joinToString(",") { it.number.toString() }, "count" to message.unknownFields.size.toString()))
        val fields = mapOf(1 to "command", 2 to "event", 3 to "status", 4 to "systemInfo", 5 to "companionTime", 7 to "connectionInfo", 8 to "enrollmentInfo")
        val declared = (message["type"] as? Int) ?: 0
        val bodies = fields.filterValues { message[it] is DecodedStruct }
        val expected = when (synchronized(run.monitor) { run.pendingQuery?.kind }) {
            SetupQuery.GET_STATUS -> 3
            SetupQuery.GET_SYSTEM_INFO -> 4
            SetupQuery.GET_CONNECTION_INFO -> 7
            null -> null
        }
        val type = if (fields[declared]?.let { message[it] is DecodedStruct } == true) declared else if (bodies.size == 1) {
            log(run, "setup_type_inferred", details = mapOf("declared" to declared.toString(), "inferred" to bodies.keys.single().toString()))
            bodies.keys.single()
        } else if (expected != null && expected in bodies) {
            log(run, "setup_type_inferred_from_query", details = mapOf("declared" to declared.toString(), "inferred" to expected.toString()))
            expected
        } else {
            log(run, "setup_message_unhandled", details = mapOf("type" to declared.toString(), "bodies" to bodies.keys.joinToString(",")))
            return
        }
        if (bodies.size > 1) log(run, "setup_extra_bodies", details = mapOf("type" to type.toString(), "count" to bodies.size.toString()))
        val body = message[fields.getValue(type)] as DecodedStruct
        when (type) {
            1 -> when (val command = body["type"] as? Int ?: 0) {
                4 -> if (run.options == null) log(run, "probe_declined_time_sync", DiagnosticSeverity.INFO)
                    else {
                        log(run, "time_sync_command_compatibility", DiagnosticSeverity.INFO)
                        send(run, SetupProtocol.encodeTime(clock.millis(), timeZone()))
                    }
                5 -> {
                    val cell = try { withTimeoutOrNull(config.dependencyTimeoutMillis) { environment.cellularInfo(run.peer, run.session) } } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        log(run, "phone_connection_info_unavailable", details = mapOf("exception" to e.javaClass.simpleName)); null
                    }
                    send(run, SetupProtocol.encodeConnectionInfo(cell?.mnc, cell?.mcc, cell?.carrierName))
                }
                else -> log(run, "watch_command_unhandled", details = mapOf("command" to command.toString()))
            }
            2 -> handleEvent(run, body)
            3 -> {
                val status = body["state"] as? Int ?: 0
                if (status !in 0..5) log(run, "unknown_watch_status", details = mapOf("status" to status.toString()))
                log(run, "watch_status_received", DiagnosticSeverity.INFO, mapOf("status" to status.toString()))
                observe(run) { it.copy(status = status, statusRevision = it.statusRevision + 1, complete = it.complete || status == 3, oobe = it.oobe || status == 5) }
                publishObservations(run)
            }
            4 -> {
                fun ints(name: String) = (body[name] as? List<*>)?.filterIsInstance<Int>() ?: emptyList()
                val info = SetupSystemInfo(body["version"] as? Int, ints("capabilities"), body["edition"] as? Int,
                    body["platformVersion"] as? Int, body["maintenanceReleaseVersion"] as? Int, ints("setupCapabilities"), body["setupCapabilityVersion"] as? Int)
                log(run, "system_info_received", DiagnosticSeverity.INFO, mapOf(
                    "version" to (info.versionValue?.toString() ?: "absent"),
                    "platformVersion" to (info.platformVersion?.toString() ?: "absent"),
                    "capabilities" to info.capabilities.take(32).joinToString(","),
                    "setupCapabilities" to info.setupCapabilities.take(32).joinToString(","),
                    "setupCapabilityVersion" to (info.setupCapabilityVersionValue?.toString() ?: "absent"),
                ))
                observe(run) { it.copy(system = info, systemRevision = it.systemRevision + 1) }
                if (info.setupCapabilities.isNotEmpty() && info.effectiveSetupCapabilities.isEmpty()) log(run, "unversioned_setup_capabilities_ignored")
                publishObservations(run)
            }
            7 -> {
                val cell = body["cellInfo"] as? DecodedStruct
                observe(run) { it.copy(cell = cell?.let { SetupCellularInfo(it["mnc"] as? Int, it["mcc"] as? Int, it["carrierName"] as? String) }, connectionRevision = it.connectionRevision + 1) }
                publishObservations(run)
            }
            else -> log(run, "watch_message_observed", DiagnosticSeverity.INFO, mapOf("type" to type.toString()))
        }
    }

    private fun handleEvent(run: Run, body: DecodedStruct) {
        when (val event = body["type"] as? Int ?: 0) {
            3 -> {
                val optin = body["optin"] as? DecodedStruct ?: return
                val observation = SetupOptinObservation(optin["type"] as? Int ?: 0, optin["state"] as? Int ?: 0, optin["time"] as? Long)
                publish(run) { old -> old.copy(observedOptins = (old.observedOptins.filterNot { it.typeValue == observation.typeValue } + observation).takeLast(32)) }
            }
            4, 5 -> {
                val commandId = body["commandId"] as? Long
                val pending = synchronized(run.monitor) { run.pendingQuery }
                if (pending != null && (commandId == pending.id || commandId == null || commandId == 0L)) {
                    if (commandId != pending.id) log(run, "query_failure_id_inferred", details = mapOf("query" to pending.kind.name, "eventId" to (commandId?.toString() ?: "absent")))
                    observe(run) { it.copy(queryFailure = SetupFailure(SetupFailureKind.REMOTE_REJECTED, "watch_${if (event == 4) "unknown" else "unsupported"}_${pending.kind.name.lowercase()}")) }
                } else log(run, "uncorrelated_query_failure", details = mapOf("event" to event.toString(), "commandId" to (commandId?.toString() ?: "absent")))
            }
            else -> log(run, "watch_setup_event", DiagnosticSeverity.INFO, mapOf("event" to event.toString()))
        }
    }

    private suspend fun onCheckinUpdate(request: SetupRpcRequest): ByteArray {
        val run = synchronized(lock) { active }
        if (run == null || run.peer != request.peer || run.session != request.sessionId || run.options == null) {
            log(run, "stale_checkin_update", details = mapOf("sessionId" to request.sessionId.toString()))
            return byteArrayOf()
        }
        try { updateCheckin(run, parseCheckin(run, SetupRpcCodec.decodeCheckin(request.payload), "update")) }
        catch (e: ProtocolException) { log(run, "malformed_checkin_update", details = mapOf("exception" to e.javaClass.simpleName)) }
        // The stock update handler acknowledges with an empty response; it is not a success status.
        return byteArrayOf()
    }

    private fun parseCheckin(run: Run, body: DecodedStruct, source: String): SetupCheckinStatus {
        logNotices(run, body)
        val status = body["status"] as? Int ?: 0
        if (status !in 0..4) log(run, "unknown_checkin_status", details = mapOf("status" to status.toString()))
        if (body.unknownFields.isNotEmpty()) log(run, "checkin_extra_fields", details = mapOf("fields" to body.unknownFields.take(32).joinToString(",") { it.number.toString() }, "count" to body.unknownFields.size.toString()))
        val factoryReset = body["factoryReset"] as? Boolean
        log(run, "checkin_status_received", DiagnosticSeverity.INFO, mapOf("source" to source, "status" to status.toString(), "factoryReset" to (factoryReset?.toString() ?: "absent")))
        if (factoryReset == true) log(run, "watch_checkin_factory_reset_flag", details = mapOf("status" to status.toString()))
        return SetupCheckinStatus(status, factoryReset)
    }

    private fun updateCheckin(run: Run, value: SetupCheckinStatus) {
        observe(run) { it.copy(checkin = value, checkinRevision = it.checkinRevision + 1) }
        publish(run) { it.copy(checkin = value) }
    }

    private fun logNotices(run: Run, message: DecodedStruct) {
        for (notice in message.notices.take(32)) log(run, "protobuf_compatibility", details = mapOf(
            "kind" to notice.kind, "path" to notice.path, "field" to notice.fieldNumber.toString(), "occurrences" to notice.occurrences.toString()))
        if (message.notices.size > 32) log(run, "protobuf_compatibility_omitted", details = mapOf("count" to (message.notices.size - 32).toString()))
    }

    private fun observe(run: Run, update: (Observations) -> Observations) = synchronized(run.monitor) { run.observations.value = update(run.observations.value) }
    private fun channelFailure(run: Run, reason: String, endReason: ChannelEndReason? = null): SetupFailure {
        val link = backend.state.value
        val linkLost = endReason == ChannelEndReason.LINK_LOST || link.phase != ConnectionPhase.LINK_READY || link.peer != run.peer || link.sessionId != run.session
        return SetupFailure(if (linkLost) SetupFailureKind.LINK_LOST else SetupFailureKind.CHANNEL_CLOSED, reason)
    }
    private fun throwFailure(value: Observations) { value.failure?.let { throw SetupWorkflowException(it) } }
    private fun failRun(run: Run, failure: SetupFailure) {
        observe(run) { if (it.failure == null) it.copy(failure = failure) else it }
        run.channel.get()?.close()
        run.job.cancel(CancellationException(failure.reason))
    }
    private fun publishObservations(run: Run) {
        val observed = run.observations.value
        publish(run) { it.copy(
            probe = it.probe?.copy(watchStatusValue = observed.status, systemInfo = observed.system, cellularInfo = observed.cell, connectionInfoReceived = observed.connectionRevision != 0L),
            watchCompleteObserved = observed.complete, watchOobeDoneObserved = observed.oobe,
        ) }
    }
    private fun publish(run: Run, transform: (SetupSnapshot) -> SetupSnapshot) = synchronized(lock) {
        if (active === run && mutableState.value.runId == run.id) mutableState.value = transform(mutableState.value)
    }
    private fun step(run: Run, step: SetupStep) {
        log(run, "step_started", DiagnosticSeverity.INFO, mapOf("step" to step.name))
        publish(run) { it.copy(currentStep = step, progress = it.progress.filterNot { progress -> progress.step == step } + SetupStepProgress(step, SetupStepState.RUNNING)) }
    }
    private fun stepDone(run: Run, step: SetupStep, result: SetupStepState = SetupStepState.SUCCEEDED) {
        log(run, "step_finished", DiagnosticSeverity.INFO, mapOf("step" to step.name, "state" to result.name))
        publish(run) { it.copy(progress = it.progress.filterNot { progress -> progress.step == step } + SetupStepProgress(step, result)) }
    }
    private fun finishFailure(run: Run, failure: SetupFailure) {
        log(run, "workflow_failed", DiagnosticSeverity.ERROR, mapOf("kind" to failure.kind.name, "reason" to failure.reason))
        publish(run) {
            val current = it.currentStep
            it.copy(phase = SetupPhase.FAILED, failure = failure, action = null,
                progress = if (current == null) it.progress else it.progress.filterNot { progress -> progress.step == current } + SetupStepProgress(current, SetupStepState.FAILED))
        }
    }
    private fun log(run: Run?, event: String, severity: DiagnosticSeverity = DiagnosticSeverity.WARNING, details: Map<String, String> = emptyMap()) {
        val channel = run?.channel?.get()?.info
        runCatching { diagnostics.record(ProtocolDiagnostic("setup", event, severity, run?.peer, run?.session ?: 0L, channel?.id, channel?.localIsOpener, details)) }
    }
}
