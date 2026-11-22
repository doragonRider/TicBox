package dev.ticbox.companion.engine

import dev.ticbox.companion.api.*
import java.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

/** Typed subset supported by the watch companion's handlers. See docs/spec/companion.md. */
class WatchClientFacade(
    scope: CoroutineScope,
    private val session: CompanionBackend,
    private val clock: Clock = Clock.systemUTC(),
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val history: WatchObservationStore? = null,
    private val cloudSettings: () -> CloudPrivacySettings = { CloudPrivacySettings() },
    messageSource: RoutedMessageSource? = session as? RoutedMessageSource,
) : WatchClient {
    private data class Sample<T>(val peer: PeerAddress, val sessionId: Long, val value: T, val at: Long)
    private val lifetime = SupervisorJob(scope.coroutineContext[Job])
    private val worker = CoroutineScope(scope.coroutineContext + lifetime)
    private val lock = Any()
    private var battery: Sample<Int>? = null
    private var steps: Sample<StepSnapshot>? = null
    private var issue: Sample<WatchDataIssue>? = null
    private val mutableState = MutableStateFlow(WatchSnapshot(connection = session.state.value))
    override val state: StateFlow<WatchSnapshot> = mutableState.asStateFlow()
    private val messageRegistration: AutoCloseable?

    init {
        worker.launch(start = CoroutineStart.UNDISPATCHED) {
            session.state.collect { connection ->
                synchronized(lock) { publish() }
                val peer = connection.peer
                if (peer != null && history != null) {
                    try {
                        val recentBattery = history.recent(peer, WatchSampleKind.BATTERY, 1).firstOrNull()
                        val recentSteps = history.recent(peer, WatchSampleKind.STEPS, 1).firstOrNull()
                        synchronized(lock) {
                            if (session.state.value.peer == peer) {
                                recentBattery?.let { saved -> saved.batteryPercent?.let { if (battery == null) battery = Sample(peer, 0, it, saved.receivedAtEpochMillis) } }
                                recentSteps?.let { saved -> saved.steps?.let { if (steps == null) steps = Sample(peer, 0, it, saved.receivedAtEpochMillis) } }
                                publish()
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (problem: Exception) { historyIssue(peer, connection.sessionId, "history_restore_failed", problem) }
                }
            }
        }
        messageRegistration = messageSource?.registerMessageHandler("watch_status", setOf(BATTERY, STEPS)) { handleMessage(it) }
        lifetime.invokeOnCompletion { messageRegistration?.close() }
        if (messageSource == null) worker.launch(start = CoroutineStart.UNDISPATCHED) {
            session.events.collect { event -> if (event is BackendEvent.MessageReceived) handleMessage(event) }
        }
    }

    private suspend fun handleMessage(event: BackendEvent.MessageReceived) {
        val notes = mutableListOf<ProtocolDiagnostic>()
        var savedBattery: Sample<Int>? = null
        var savedSteps: Sample<StepSnapshot>? = null
        synchronized(lock) {
            val connection = session.state.value
            if (connection.phase != ConnectionPhase.LINK_READY ||
                connection.peer != event.peer || connection.sessionId != event.sessionId
            ) return@synchronized
            // A peer RPC request is not a status observation. Its caller still needs
            // a real response handler; this facade must not pretend to complete it.
            if (event.message.expectsResponse) return@synchronized
            when (event.message.path) {
                BATTERY -> {
                    try {
                        val value = WatchStatusCodec.battery(event.message.payload) { notes += note(event, it.event, it.details) }
                        battery = Sample(event.peer, event.sessionId, value, clock.millis())
                        savedBattery = battery
                        issue = null
                    } catch (problem: Exception) {
                        issue = Sample(event.peer, event.sessionId, WatchDataIssue.INVALID_BATTERY, clock.millis())
                        notes += unreadable(event, WatchDataIssue.INVALID_BATTERY, problem)
                    }
                }
                STEPS -> {
                    try {
                        val json = Json.parseToJsonElement(WatchStatusCodec.text(event.message.payload) { notes += note(event, it.event, it.details) }) as? JsonObject
                            ?: throw StatusReadException("steps", "expected_object")
                        fun number(name: String): Int {
                            val primitive = json[name] as? JsonPrimitive
                                ?: throw StatusReadException(name, "missing_or_nonprimitive_field")
                            return WatchStatusCodec.integer(primitive.content, name, if (primitive.isString) "string" else "number") { notes += note(event, it.event, it.details) }
                        }
                        val value = StepSnapshot(number("current_step"), number("goal"))
                        if (value.currentSteps < 0) throw StatusReadException("current_step", "negative_count")
                        if (value.goal < 0) throw StatusReadException("goal", "negative_count")
                        steps = Sample(event.peer, event.sessionId, value, clock.millis())
                        savedSteps = steps
                        issue = null
                    } catch (problem: Exception) {
                        issue = Sample(event.peer, event.sessionId, WatchDataIssue.INVALID_STEPS, clock.millis())
                        notes += unreadable(event, WatchDataIssue.INVALID_STEPS, problem)
                    }
                }
            }
            publish()
        }
        // A diagnostic callback cannot delay file IO under the observation lock or
        // terminate the status collector if an injected sink fails.
        for (note in notes) runCatching { diagnostics.record(note) }
        if (history != null) try {
            savedBattery?.let { history.append(it.peer, WatchSampleKind.BATTERY, it.at, batteryPercent = it.value) }
            savedSteps?.let { history.append(it.peer, WatchSampleKind.STEPS, it.at, steps = it.value) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) { historyIssue(event.peer, event.sessionId, "history_write_failed", problem) }
    }

    private fun historyIssue(peer: PeerAddress, sessionId: Long, event: String, problem: Exception) {
        runCatching { diagnostics.record(ProtocolDiagnostic("history", event, peer = peer, sessionId = sessionId,
            details = mapOf("exception" to problem.javaClass.simpleName, "decision" to "preserve live observation; report persistence failure"))) }
    }

    private fun unreadable(event: BackendEvent.MessageReceived, issue: WatchDataIssue, problem: Exception) = note(
        event, "status_observation_unreadable", mapOf(
            "issue" to issue.name, "exception" to problem.javaClass.simpleName,
            "decision" to "preserve prior observation and continue collecting; no synthetic value",
        ) + if (problem is StatusReadException) mapOf("field" to problem.field, "reason" to problem.reason)
            else mapOf("reason" to "unreadable_text_json_or_resource_budget"),
    )

    private fun note(event: BackendEvent.MessageReceived, name: String, details: Map<String, String>) = ProtocolDiagnostic(
        "business", name, peer = event.peer, sessionId = event.sessionId,
        details = mapOf("path" to event.message.path, "requestId" to event.message.requestId.toString(), "payloadBytes" to event.message.payloadSize.toString()) + details,
    )

    /** Always derive freshness from the latest session, even if collectors were scheduled later. */
    private fun publish() {
        val connection = session.state.value
        fun <T> observation(sample: Sample<T>?): WatchObservation<T>? = sample
            ?.takeIf { connection.peer == null || it.peer == connection.peer }
            ?.let {
                WatchObservation(it.value, it.at,
                    connection.phase != ConnectionPhase.LINK_READY || connection.sessionId != it.sessionId)
            }
        if (connection.peer != null) {
            if (battery?.peer != connection.peer) battery = null
            if (steps?.peer != connection.peer) steps = null
        }
        val currentIssue = issue?.takeIf { it.peer == connection.peer && it.sessionId == connection.sessionId }?.value
        mutableState.value = WatchSnapshot(connection, observation(battery), observation(steps), currentIssue)
    }

    override suspend fun connect(peer: PeerAddress) = session.connect(peer)
    override suspend fun disconnect() = session.disconnect()
    override suspend fun refreshBattery() { session.sendMessage(BATTERY, byteArrayOf()) }
    /** Stock handles this command by scheduling cloud sync too; it is not a local database read. */
    override suspend fun refreshHealth() {
        requireHealthCloudEnabled()
        session.sendMessage(REFRESH_HEALTH, byteArrayOf())
    }
    /** Stock also schedules cloud sync on this. Callers warn about it; the cloud switch doesn't apply. */
    override suspend fun setStepGoal(goal: Int) {
        require(goal > 0) { "The watch accepts positive Java int step goals" }
        session.sendMessage(UPDATE_GOAL, "{\"goal\":$goal,\"current_step\":0}".toByteArray(Charsets.UTF_8))
    }

    private fun requireHealthCloudEnabled() {
        if (!cloudSettings().allows(CloudCapability.MOBVOI_CLOUD)) {
            runCatching { diagnostics.record(ProtocolDiagnostic("privacy", "health_cloud_command_disabled")) }
            throw CloudFeatureDisabledException(CloudCapability.MOBVOI_CLOUD)
        }
    }

    override fun close() {
        messageRegistration?.close()
        session.close()
        lifetime.cancel()
        synchronized(lock) { publish() }
    }

    private companion object {
        const val BATTERY = "/companion/sync_wear_battery"
        const val STEPS = "/health/cmd_new_wear_push_to_phone"
        const val REFRESH_HEALTH = "/health/cmd_wear_push_to_phone"
        const val UPDATE_GOAL = "/health/cmd_wear_pull_from_phone"
    }
}
