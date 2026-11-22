package dev.ticbox.companion.engine

import dev.ticbox.companion.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Ordered per-handler delivery for ordinary messages. Registration survives reconnect; queued work does not. */
interface RoutedMessageSource {
    fun registerMessageHandler(name: String, paths: Set<String>, route: ApplicationRoute? = null,
        handler: suspend (BackendEvent.MessageReceived) -> Unit): AutoCloseable
}

/** Reader-side admission never waits for domain IO. Each handler owns an independent bounded lane. */
internal class SessionMessageDispatcher(
    private val config: SessionConfig,
    private val failure: (BackendEvent.MessageReceived, MessageDeliveryFailure, String?) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private val handlers = linkedSetOf<Registration>()
    private var run: Run? = null
    private var closed = false

    fun register(name: String, paths: Set<String>, route: ApplicationRoute,
        handler: suspend (BackendEvent.MessageReceived) -> Unit): AutoCloseable {
        val entry = Registration(name, paths.toSet(), route, handler)
        val lane = synchronized(lock) {
            check(!closed) { "Message dispatcher closed" }
            handlers += entry
            run?.let { startLane(entry, it) }
        }
        lane?.job?.start()
        return AutoCloseable {
            val removed = synchronized(lock) {
                handlers.remove(entry)
                entry.lane.also { entry.lane = null; it?.owner?.lanes?.remove(it) }
            }
            removed?.close()
        }
    }

    fun accepts(route: ApplicationRoute, path: String): Boolean = synchronized(lock) {
        handlers.any { it.route == route && path in it.paths }
    }

    fun begin(scope: CoroutineScope, peer: PeerAddress, sessionId: Long) {
        val (old, lanes) = synchronized(lock) {
            if (closed) return
            val previous = run
            val next = Run(peer, sessionId, SupervisorJob(scope.coroutineContext[Job]), scope)
            run = next
            previous to handlers.map { startLane(it, next) }
        }
        old?.close()
        lanes.forEach { it.job.start() }
    }

    fun offer(event: BackendEvent.MessageReceived) {
        val lanes = synchronized(lock) {
            val current = run?.takeIf { it.peer == event.peer && it.sessionId == event.sessionId } ?: return
            handlers.filter { it.route == event.message.route && event.message.path in it.paths }
                .mapNotNull { it.lane?.takeIf { lane -> lane.owner === current } }
        }
        lanes.forEach { it.offer(event) }
    }

    fun end(sessionId: Long) {
        val old = synchronized(lock) {
            run?.takeIf { it.sessionId == sessionId }?.also { run = null; handlers.forEach { it.lane = null } }
        }
        old?.close()
    }

    override fun close() {
        val old = synchronized(lock) {
            closed = true
            run.also { run = null; handlers.forEach { it.lane = null }; handlers.clear() }
        }
        old?.close()
    }

    private fun startLane(entry: Registration, owner: Run): Lane = Lane(entry, owner).also { entry.lane = it; owner.lanes += it }
    private class Registration(val name: String, val paths: Set<String>, val route: ApplicationRoute,
        val handler: suspend (BackendEvent.MessageReceived) -> Unit) { var lane: Lane? = null }
    private class Run(val peer: PeerAddress, val sessionId: Long, val lifetime: Job, parent: CoroutineScope) : AutoCloseable {
        val scope = CoroutineScope(parent.coroutineContext + lifetime)
        val lanes = mutableListOf<Lane>()
        override fun close() { lanes.forEach { it.close() }; lifetime.cancel() }
    }
    private inner class Lane(val entry: Registration, val owner: Run) : AutoCloseable {
        private val admission = Any()
        private var outstandingBytes = 0L
        private val queue = Channel<BackendEvent.MessageReceived>(config.businessMessageQueueCapacity,
            onUndeliveredElement = { release(it) })
        val job = owner.scope.launch(start = CoroutineStart.LAZY) {
            try {
                for (event in queue) try {
                    // Even an immediate dispatcher must not execute user code inside trySend.
                    yield()
                    withTimeout(config.businessMessageTimeoutMillis) { entry.handler(event) }
                } catch (problem: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    failed(event, MessageDeliveryFailureKind.TIMED_OUT, problem)
                } catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    failed(event, MessageDeliveryFailureKind.HANDLER_FAILED, cancelled)
                } catch (problem: Exception) {
                    failed(event, MessageDeliveryFailureKind.HANDLER_FAILED, problem)
                } finally { release(event) }
            } finally { queue.cancel() }
        }
        fun offer(event: BackendEvent.MessageReceived) {
            val overflow = synchronized(admission) {
                if (!job.isActive && !job.start()) return
                val bytes = size(event)
                if (outstandingBytes + bytes > config.businessMessageQueueBytes) true
                else {
                    outstandingBytes += bytes
                    val result = queue.trySend(event)
                    if (result.isFailure) outstandingBytes -= bytes
                    result.isFailure && !result.isClosed
                }
            }
            if (overflow) failed(event, MessageDeliveryFailureKind.QUEUE_FULL)
        }
        private fun size(event: BackendEvent.MessageReceived): Long = event.message.payloadSize.toLong() +
            (event.message.path.length.toLong() + (event.message.sourceNodeId?.length ?: 0)) * 2 + 256
        private fun release(event: BackendEvent.MessageReceived) { synchronized(admission) { outstandingBytes -= size(event) } }
        private fun failed(event: BackendEvent.MessageReceived, kind: MessageDeliveryFailureKind, problem: Exception? = null) {
            failure(event, MessageDeliveryFailure(entry.name, event.message.path, event.message.requestId, kind), problem?.javaClass?.simpleName)
        }
        override fun close() { job.cancel(); queue.cancel() }
    }
}
