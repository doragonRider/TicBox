@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.ticbox.companion.engine.toolbox

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class UdpVideoRestartTest {
    private val lost = UdpVideoRecoveryState(observedFrame = true, acceptedKeyFrames = 1, decoderPrepared = true, captureGeneration = 1)
    private fun exhaust(policy: UdpVideoRecovery) {
        for (time in listOf(0L, 500L, 1_000L)) assertEquals(UdpVideoRecovery.Action.REQUEST_KEYFRAME, policy.poll(lost, time))
        assertEquals(UdpVideoRecovery.Action.NONE, policy.poll(lost, 1_499))
    }

    @Test fun threeLightRequestsAllowOnlyOneRestartAndRequireACompleteNewGeneration() {
        val policy = UdpVideoRecovery(500, 3, restartAllowed = true)
        exhaust(policy)
        assertEquals(UdpVideoRecovery.Action.RESTART_VIDEO, policy.poll(lost, 1_500))
        assertEquals(UdpVideoRecovery.Action.NONE, policy.poll(lost.copy(needsKeyFrame = false, acceptedKeyFrames = 2), 1_600))
        val preparing = lost.copy(captureGeneration = 2, waitingForDecoder = true, observedFrame = false)
        assertEquals(UdpVideoRecovery.Action.NONE, policy.poll(preparing, 1_700))
        assertEquals(UdpVideoRecovery.Action.NONE, policy.poll(preparing.copy(waitingForDecoder = false), 1_800))
        val healthy = lost.copy(captureGeneration = 2, acceptedKeyFrames = 3, needsKeyFrame = false)
        assertEquals(UdpVideoRecovery.Action.NONE, policy.poll(healthy, 1_900))
        // A later loss has light requests but cannot loop through repeated codec recreation.
        val anotherLoss = healthy.copy(needsKeyFrame = true)
        assertEquals(UdpVideoRecovery.Action.REQUEST_KEYFRAME, policy.poll(anotherLoss, 2_000))
        assertEquals(UdpVideoRecovery.Action.REQUEST_KEYFRAME, policy.poll(anotherLoss, 2_500))
        assertEquals(UdpVideoRecovery.Action.REQUEST_KEYFRAME, policy.poll(anotherLoss, 3_000))
        assertEquals(UdpVideoRecovery.Action.FAILED, policy.poll(anotherLoss, 3_500))
    }

    @Test fun staleKeysHeadersAndDecoderPreparationCannotExtendRestartDeadline() {
        for (state in listOf(lost, lost.copy(needsKeyFrame = false, acceptedKeyFrames = 2),
            lost.copy(captureGeneration = 2, waitingForDecoder = true, observedFrame = false),
            lost.copy(captureGeneration = 2, waitingForDecoder = false, acceptedKeyFrames = 1))) {
            val policy = UdpVideoRecovery(500, 3, restartAllowed = true)
            exhaust(policy); policy.poll(lost, 1_500)
            assertEquals(UdpVideoRecovery.Action.NONE, policy.poll(state, 11_499))
            assertEquals(UdpVideoRecovery.Action.FAILED, policy.poll(state, 11_500))
        }
    }

    @Test fun aLateNewReferenceCannotReviveAnExpiredRestart() {
        val policy = UdpVideoRecovery(500, 3, restartAllowed = true)
        exhaust(policy); policy.poll(lost, 1_500)
        val late = lost.copy(captureGeneration = 2, acceptedKeyFrames = 2, needsKeyFrame = false)
        assertEquals(UdpVideoRecovery.Action.FAILED, policy.poll(late, 11_500))
    }

    @Test fun missingFirstReferenceAndUnnegotiatedGenerationNeverRestart() {
        for (state in listOf(lost.copy(acceptedKeyFrames = 0), lost.copy(captureGeneration = 0))) {
            val policy = UdpVideoRecovery(500, 3, restartAllowed = true)
            for (time in listOf(0L, 500L, 1_000L)) assertEquals(UdpVideoRecovery.Action.REQUEST_KEYFRAME, policy.poll(state, time))
            assertEquals(UdpVideoRecovery.Action.FAILED, policy.poll(state, 1_500))
        }
        val initial = UdpVideoRecoveryState()
        assertEquals(UdpVideoRecovery.Action.NONE, UdpVideoRecovery(500, 3, restartAllowed = true).poll(initial, 30_000))
    }

    @Test fun restartWorkerStopsAtDeadlineAndCloseCancelsItsPendingWait() = runTest {
        for (closeEarly in listOf(false, true)) {
            var requests = 0; var restarts = 0; var failures = 0
            val loop = UdpVideoRecoveryLoop(500, 3, { lost }, { requests++ }, { failures++ }, { currentTime },
                restart = { restarts++ }, restartTimeoutMillis = 1_000)
            val worker = backgroundScope.launch { loop.run() }
            runCurrent(); advanceTimeBy(1_500); runCurrent()
            assertEquals(3, requests); assertEquals(1, restarts); assertEquals(0, failures)
            if (closeEarly) loop.close()
            advanceTimeBy(1_000); runCurrent()
            assertEquals(if (closeEarly) 0 else 1, failures)
            assertEquals(1, restarts)
            assertTrue(worker.isCompleted)
            loop.close(); worker.cancelAndJoin()
        }
    }
}
