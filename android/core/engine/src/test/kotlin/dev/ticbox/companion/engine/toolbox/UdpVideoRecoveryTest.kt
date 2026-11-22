package dev.ticbox.companion.engine.toolbox

import kotlin.test.*

class UdpVideoRecoveryTest {
    private val missing = UdpVideoRecoveryState(observedFrame = true)

    @Test fun `startup without video and healthy streams do not reset encoders`() {
        val recovery = UdpVideoRecovery()
        assertEquals(UdpVideoRecovery.Action.NONE, recovery.poll(UdpVideoRecoveryState(), 0))
        assertEquals(UdpVideoRecovery.Action.NONE, recovery.poll(UdpVideoRecoveryState(), 60_000))
        assertEquals(UdpVideoRecovery.Action.NONE, recovery.poll(missing.copy(needsKeyFrame = false, acceptedKeyFrames = 1), 100_000))
    }

    @Test fun `low frame rates use elapsed time not frame count and repeated deltas cannot postpone recovery`() {
        val recovery = UdpVideoRecovery()
        assertEquals(UdpVideoRecovery.Action.NONE, recovery.poll(missing, 0))
        assertEquals(UdpVideoRecovery.Action.NONE, recovery.poll(missing, 1_000))
        assertEquals(UdpVideoRecovery.Action.REQUEST_KEYFRAME, recovery.poll(missing, 2_000))
        assertEquals(UdpVideoRecovery.Action.NONE, recovery.poll(missing, 2_001))
        assertEquals(UdpVideoRecovery.Action.REQUEST_KEYFRAME, recovery.poll(missing, 4_000))
        assertEquals(UdpVideoRecovery.Action.REQUEST_KEYFRAME, recovery.poll(missing, 6_000))
        assertEquals(UdpVideoRecovery.Action.NONE, recovery.poll(missing, 7_999))
        assertEquals(UdpVideoRecovery.Action.FAILED, recovery.poll(missing, 8_000))
    }

    @Test fun `a key frame clears the episode even when another drop happens between timer polls`() {
        val recovery = UdpVideoRecovery()
        recovery.poll(missing, 0)
        assertEquals(UdpVideoRecovery.Action.REQUEST_KEYFRAME, recovery.poll(missing, 2_000))
        assertEquals(UdpVideoRecovery.Action.REQUEST_KEYFRAME, recovery.poll(missing, 4_000))
        assertEquals(UdpVideoRecovery.Action.REQUEST_KEYFRAME, recovery.poll(missing, 6_000))
        val later = missing.copy(acceptedKeyFrames = 1)
        assertEquals(UdpVideoRecovery.Action.NONE, recovery.poll(later, 6_100))
        assertEquals(UdpVideoRecovery.Action.REQUEST_KEYFRAME, recovery.poll(later, 6_150))
        assertEquals(UdpVideoRecovery.Action.NONE, recovery.poll(later.copy(needsKeyFrame = false), 8_200))
        assertEquals(UdpVideoRecovery.Action.NONE, recovery.poll(later.copy(needsKeyFrame = false), 20_000))
    }

    @Test fun `established stream loss requests immediately then observes the global cooldown`() {
        val recovery = UdpVideoRecovery()
        val healthy = missing.copy(needsKeyFrame = false, acceptedKeyFrames = 1)
        assertEquals(UdpVideoRecovery.Action.NONE, recovery.poll(healthy, 0))
        // A long static period is healthy: absence of new frames is not a lost dependency.
        assertEquals(UdpVideoRecovery.Action.NONE, recovery.poll(healthy, 30_000))
        val lost = healthy.copy(needsKeyFrame = true)
        assertEquals(UdpVideoRecovery.Action.REQUEST_KEYFRAME, recovery.poll(lost, 30_100))
        assertEquals(UdpVideoRecovery.Action.NONE, recovery.poll(lost, 30_200))
        assertEquals(UdpVideoRecovery.Action.NONE, recovery.poll(lost, 32_099))
        assertEquals(UdpVideoRecovery.Action.REQUEST_KEYFRAME, recovery.poll(lost, 32_100))
        assertEquals(UdpVideoRecovery.Action.REQUEST_KEYFRAME, recovery.poll(lost, 34_100))
        assertEquals(UdpVideoRecovery.Action.FAILED, recovery.poll(lost, 36_100))
    }

    @Test fun `a new loss after the recovery key waits only the short spacing, its retries the full cooldown`() {
        val recovery = UdpVideoRecovery()
        val lost = missing.copy(acceptedKeyFrames = 1)
        assertEquals(UdpVideoRecovery.Action.REQUEST_KEYFRAME, recovery.poll(lost, 0))
        // The key arrived and the stream broke again before the next poll: a new loss.
        val recoveredAndLost = lost.copy(acceptedKeyFrames = 2)
        assertEquals(UdpVideoRecovery.Action.NONE, recovery.poll(recoveredAndLost, UdpVideoRecovery.NEW_LOSS_MILLIS - 1))
        assertEquals(UdpVideoRecovery.Action.REQUEST_KEYFRAME, recovery.poll(recoveredAndLost, UdpVideoRecovery.NEW_LOSS_MILLIS))
        // The accepted key cleared the old episode's attempts, even if both transitions were
        // observed in the same snapshot; this episode still has its own three bounded tries.
        val first = UdpVideoRecovery.NEW_LOSS_MILLIS
        assertEquals(UdpVideoRecovery.Action.NONE, recovery.poll(recoveredAndLost, first + 1_999))
        assertEquals(UdpVideoRecovery.Action.REQUEST_KEYFRAME, recovery.poll(recoveredAndLost, first + 2_000))
        assertEquals(UdpVideoRecovery.Action.REQUEST_KEYFRAME, recovery.poll(recoveredAndLost, first + 4_000))
        assertEquals(UdpVideoRecovery.Action.FAILED, recovery.poll(recoveredAndLost, first + 6_000))
    }
}
