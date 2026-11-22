@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.ticbox.companion.engine.toolbox

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class UdpVideoRecoveryLoopTest {
    private val healthy = UdpVideoRecoveryState(observedFrame = true, needsKeyFrame = false, acceptedKeyFrames = 1)

    @Test fun confirmedLossWakesWorkerWithoutAdvancingToNextTimerTick() = runTest {
        var state = healthy
        val requests = mutableListOf<Long>()
        val loop = UdpVideoRecoveryLoop(500, 3, { state }, { requests += currentTime }, { fail("unexpected exhaustion") }, { currentTime })
        val running = backgroundScope.launch { loop.run() }
        try {
            runCurrent()
            advanceTimeBy(1) // The ordinary timer would not run until t=100.
            state = healthy.copy(needsKeyFrame = true)
            loop.stateChanged()
            runCurrent()
            assertEquals(listOf(1L), requests)
            // Once the reference is restored, the old timer must not emit another request.
            state = healthy.copy(acceptedKeyFrames = 2)
            loop.stateChanged()
            advanceTimeBy(2_000); runCurrent()
            assertEquals(listOf(1L), requests)
        } finally { loop.close(); running.cancelAndJoin() }
    }

    @Test fun repeatedWakeupsDoNotBypassTheCooldownAndANewLossWaitsOnlyItsSpacing() = runTest {
        var state = healthy
        val requests = mutableListOf<Long>()
        var failures = 0
        val loop = UdpVideoRecoveryLoop(500, 3, { state }, { requests += currentTime }, { failures++ }, { currentTime })
        val running = backgroundScope.launch { loop.run() }
        try {
            runCurrent(); advanceTimeBy(1)
            state = healthy.copy(needsKeyFrame = true)
            repeat(100) { loop.stateChanged() }; runCurrent()
            assertEquals(listOf(1L), requests)
            // A recovered reference followed immediately by another loss starts a new episode,
            // which may ask again once the short spacing since the last request is over.
            state = state.copy(acceptedKeyFrames = 2)
            repeat(100) { loop.stateChanged() }; runCurrent()
            assertEquals(listOf(1L), requests)
            advanceTimeBy(UdpVideoRecovery.NEW_LOSS_MILLIS - 1); runCurrent()
            assertEquals(listOf(1L), requests)
            advanceTimeBy(1); runCurrent()
            assertEquals(listOf(1L, 151L), requests)
            // Its retries keep the full cooldown and the bounded attempts.
            advanceTimeBy(1_500); runCurrent()
            assertEquals(listOf(1L, 151L, 651L, 1_151L), requests)
            assertEquals(1, failures)
            assertTrue(running.isCompleted)
        } finally { loop.close(); running.cancelAndJoin() }
    }

    @Test fun coldDecoderAndCloseDoNotIssueSpuriousRequestsOrLeaveAnAwaiter() = runTest {
        var state = UdpVideoRecoveryState(waitingForDecoder = true)
        var requests = 0
        val loop = UdpVideoRecoveryLoop(500, 3, { state }, { requests++ }, { fail("unexpected exhaustion") }, { currentTime })
        val running = backgroundScope.launch { loop.run() }
        runCurrent(); advanceTimeBy(1)
        state = state.copy(observedFrame = true)
        loop.stateChanged(); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(0, requests)
        loop.close(); runCurrent()
        assertTrue(running.isCompleted)
        loop.stateChanged() // A late receiver event cannot revive a closed worker.
        advanceTimeBy(1_000); runCurrent()
        assertEquals(0, requests)
    }
    @Test fun closeDiscardsPendingLossBeforeItCanWriteARequest() = runTest {
        var state = healthy
        var requests = 0
        val loop = UdpVideoRecoveryLoop(500, 3, { state }, { requests++ }, { fail("unexpected exhaustion") }, { currentTime })
        val running = backgroundScope.launch { loop.run() }
        runCurrent(); advanceTimeBy(1)
        state = healthy.copy(needsKeyFrame = true)
        loop.stateChanged()
        loop.close()
        runCurrent()
        assertEquals(0, requests)
        assertTrue(running.isCompleted)
    }

}
