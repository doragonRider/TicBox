package dev.ticbox.companion.platform.notification

import dev.ticbox.companion.engine.notification.NotificationSourceEvent
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationChangeSignalsTest {
    @Test
    fun sustainedCallbacksStillTriggerSnapshotsWithoutWaitingForTheStreamToBecomeQuiet() = runTest {
        val changes = MutableStateFlow(0L)
        val events = mutableListOf<NotificationSourceEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coalescedNotificationChanges(changes).collect(events::add)
        }
        repeat(100) { changes.value += 1; advanceTimeBy(10); runCurrent() }
        assertTrue(events.size in 9..11, "A busy stream must make bounded progress: ${events.size}")
        val beforeQuiet = events.size
        advanceTimeBy(10_000); runCurrent()
        assertTrue(events.size - beforeQuiet in 0..1, "No periodic work after the final coalesced change")
    }

    @Test
    fun aBurstIsConflatedAndCancellationDoesNotLeaveADelayedRescan() = runTest {
        val changes = MutableStateFlow(0L)
        val events = mutableListOf<NotificationSourceEvent>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coalescedNotificationChanges(changes).collect(events::add)
        }
        repeat(10_000) { changes.value += 1 }
        advanceTimeBy(250); runCurrent()
        assertTrue(events.size in 1..2)
        changes.value += 1
        runCurrent()
        job.cancelAndJoin()
        val before = events.size
        advanceTimeBy(1_000); runCurrent()
        assertEquals(before, events.size)
    }
}
