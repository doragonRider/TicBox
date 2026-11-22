@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.ticbox.companion.engine

import dev.ticbox.companion.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class SessionMessageDispatcherTest {
    private val peer = PeerAddress("AA:BB:CC:DD:EE:FF")
    private val route = ApplicationRoute("example", "digest")
    private fun event(id: Int, path: String = "/slow", session: Long = 1, bytes: Int = 1) =
        BackendEvent.MessageReceived(peer, session, InboundMessage(id, path, ByteArray(bytes), "watch", route = route))

    @Test fun `slow handler has its own bounded ordered queue while other paths continue`() = runTest {
        val failures = mutableListOf<MessageDeliveryFailure>(); val done = mutableListOf<Int>(); val fast = mutableListOf<Int>()
        val blocked = CompletableDeferred<Unit>()
        val dispatcher = SessionMessageDispatcher(SessionConfig(businessMessageQueueCapacity = 1)) { _, failure, _ -> failures += failure }
        dispatcher.register("slow", setOf("/slow"), route) { blocked.await(); done += it.message.requestId }
        dispatcher.register("fast", setOf("/fast"), route) { fast += it.message.requestId }
        dispatcher.begin(backgroundScope, peer, 1); runCurrent()
        dispatcher.offer(event(1)); runCurrent()
        dispatcher.offer(event(2)); dispatcher.offer(event(3)); dispatcher.offer(event(4, "/fast")); runCurrent()
        assertEquals(listOf(4), fast); assertTrue(done.isEmpty())
        assertEquals(MessageDeliveryFailure("slow", "/slow", 3, MessageDeliveryFailureKind.QUEUE_FULL), failures.single())
        blocked.complete(Unit); runCurrent(); assertEquals(listOf(1, 2), done)
        dispatcher.offer(event(5)); runCurrent(); assertEquals(listOf(1, 2, 5), done)
        dispatcher.close()
    }

    @Test fun `byte admission includes inflight payload and releases it after delivery`() = runTest {
        val failures = mutableListOf<MessageDeliveryFailure>(); val done = mutableListOf<Int>(); val blocked = CompletableDeferred<Unit>()
        val dispatcher = SessionMessageDispatcher(SessionConfig(businessMessageQueueBytes = 600)) { _, failure, _ -> failures += failure }
        dispatcher.register("bytes", setOf("/slow"), route) { blocked.await(); done += it.message.requestId }
        dispatcher.begin(backgroundScope, peer, 1); runCurrent()
        dispatcher.offer(event(1, bytes = 200)); runCurrent()
        dispatcher.offer(event(2, bytes = 200)); runCurrent()
        assertEquals(2, failures.single().requestId)
        blocked.complete(Unit); runCurrent()
        dispatcher.offer(event(3, bytes = 200)); runCurrent(); assertEquals(listOf(1, 3), done)
        dispatcher.close()
    }

    @Test fun `handler exceptions and timeouts are reported and do not terminate its next delivery`() = runTest {
        val failures = mutableListOf<MessageDeliveryFailure>(); val done = mutableListOf<Int>()
        val dispatcher = SessionMessageDispatcher(SessionConfig(businessMessageTimeoutMillis = 50)) { _, failure, _ -> failures += failure }
        dispatcher.register("commands", setOf("/slow"), route) {
            when (it.message.requestId) { 1 -> throw java.io.IOException("private body"); 2 -> awaitCancellation() }
            done += it.message.requestId
        }
        dispatcher.begin(backgroundScope, peer, 1); runCurrent()
        (1..3).forEach { dispatcher.offer(event(it)) }; runCurrent(); advanceTimeBy(51); runCurrent()
        assertEquals(listOf(MessageDeliveryFailureKind.HANDLER_FAILED, MessageDeliveryFailureKind.TIMED_OUT), failures.map { it.kind })
        assertEquals(listOf(3), done); dispatcher.close()
    }

    @Test fun `session end cancels queued old commands and reconnect reuses only the registration`() = runTest {
        val done = mutableListOf<Pair<Long, Int>>(); val blocked = CompletableDeferred<Unit>()
        val dispatcher = SessionMessageDispatcher(SessionConfig()) { _, _, _ -> fail("unexpected delivery failure") }
        val registration = dispatcher.register("session", setOf("/slow"), route) {
            if (it.sessionId == 1L) blocked.await()
            done += it.sessionId to it.message.requestId
        }
        dispatcher.begin(backgroundScope, peer, 1); runCurrent()
        dispatcher.offer(event(1)); runCurrent(); dispatcher.offer(event(2))
        dispatcher.end(1); dispatcher.begin(backgroundScope, peer, 2); runCurrent()
        blocked.complete(Unit); dispatcher.offer(event(3)); dispatcher.offer(event(4, session = 2)); runCurrent()
        assertEquals(listOf(2L to 4), done)
        registration.close(); registration.close()
        assertFalse(dispatcher.accepts(route, "/slow"))
        dispatcher.offer(event(5, session = 2)); runCurrent(); assertEquals(1, done.size)
        dispatcher.close()
    }
}
