package dev.ticbox.companion.toolboxagent

import java.io.ByteArrayOutputStream
import kotlin.test.*
import org.junit.Test

class AgentScreenBitRateTest {
    private class Run(base: Int = 30_000_000) {
        val applied = mutableListOf<Int>()
        val reasons = mutableListOf<String>()
        val controller = ScreenBitRateController(base, applied::add) { reason, _, _ -> reasons += reason }
        var now = 10_000_000L
        /** Frames every [stepMicros] for [durationMicros], each [ageMicros] old on arrival. */
        fun frames(durationMicros: Long, ageMicros: (Long) -> Long, stepMicros: Long = 8_333) {
            val end = now + durationMicros
            while (now < end) { controller.frame(now - ageMicros(now), now); now += stepMicros }
        }
    }

    @Test fun aQueueBuildingUpLowersTheTargetStepByStepAndCalmBringsItBack() {
        val run = Run()
        run.frames(3_000_000, { 40_000 })
        assertTrue(run.applied.isEmpty(), "a steady encoder pipeline is not a queue")
        run.frames(1_000_000, { 400_000 })
        assertEquals(listOf(21_000_000, 14_700_000), run.applied, "one cut per hold period")
        run.frames(2_000_000, { 40_000 })
        assertEquals(17_700_000, run.controller.target, "back up by a tenth once it stays calm")
        run.frames(10_000_000, { 40_000 })
        assertEquals(30_000_000, run.controller.target)
        assertEquals("increase", run.reasons.last())
    }

    @Test fun oneOldPictureAfterAnIdleStretchIsNotAQueue() {
        val run = Run()
        run.frames(3_000_000, { 40_000 })
        run.now += 4_000_000
        // The held copy arrives with the age of the idle stretch, then the pipeline is normal again.
        run.controller.frame(run.now - 3_900_000, run.now)
        run.frames(1_000_000, { 40_000 })
        assertTrue(run.applied.isEmpty())
    }

    @Test fun warmUpUnusableClocksAndTheFloorAreRespected() {
        val warming = Run()
        warming.frames(1_500_000, { 600_000 })
        assertTrue(warming.applied.isEmpty(), "startup frames don't count")
        val unusable = Run()
        unusable.frames(4_000_000, { -5_000 })
        unusable.frames(4_000_000, { 9_000_000 })
        assertTrue(unusable.applied.isEmpty(), "a PTS on another clock is ignored")
        val floor = Run(base = 1_000_000)
        floor.frames(3_000_000, { 40_000 })
        floor.frames(20_000_000, { it % 10_000_000 + 40_000 })
        assertTrue(floor.applied.isNotEmpty())
        assertTrue(floor.applied.all { it >= 200_000 })
    }

    @Test fun theControlMessageIsOpcode126AndABigEndianRate() {
        val bytes = ByteArrayOutputStream()
        ScrcpyControl(bytes).setVideoBitRate(12_000_000)
        assertContentEquals(byteArrayOf(126, 0, 0xB7.toByte(), 0x1B, 0), bytes.toByteArray())
    }
}
