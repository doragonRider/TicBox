package dev.ticbox.companion.ui.toolbox

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import org.junit.Test

class ScreenOutputPacerTest {
    @Test
    fun briefUnderrunKeepsTheNextTickArmedWithoutRepeatingAnImageOrExtendingIdleDeadline() {
        val f = Fixture()
        f.offer(1)
        f.tick(8)
        assertEquals(listOf(Release(1, ms(8))), f.released)
        assertEquals(0, f.pacer.pendingOutputs)
        assertEquals(2, f.clock.frameRequests) // Next callback is already armed with no buffer.
        assertFalse(f.clock.timeoutPending)
        f.tick(16)
        assertEquals(3, f.clock.frameRequests)
        assertEquals(1, f.released.size) // Empty ticks cannot turn into repeated display frames.
        f.now = ms(17)
        f.offer(2)
        assertEquals(3, f.clock.frameRequests) // The producer doesn't have to re-register vsync.
        f.tick(24)
        assertEquals(Release(2, ms(24)), f.released.last())
        f.tick(250)
        val armed = f.clock.frameRequests
        f.tick(267) // Exactly 250ms since the last decoded output, not the last empty callback.
        assertEquals(armed, f.clock.frameRequests)
        assertFalse(f.clock.timeoutPending)
        assertEquals(2, f.released.size)
        f.now = ms(300)
        f.offer(3) // A new frame resumes an expired clock normally.
        assertEquals(armed + 1, f.clock.frameRequests)
        f.tick(304)
        assertEquals(Release(3, ms(304)), f.released.last())
        f.pacer.close()
    }

    @Test
    fun closeCancelsAnEmptyWarmClockAndWatchdogDoesNotStartAnIdleRetryLoop() {
        val f = Fixture()
        f.offer(1)
        f.tick(16)
        f.pacer.close()
        assertEquals(1, f.clock.frameCancels)
        val requests = f.clock.frameRequests
        f.tick(32)
        assertEquals(requests, f.clock.frameRequests)
        assertEquals(listOf(Release(1, ms(16))), f.released)

        val watchdog = Fixture()
        watchdog.offer(1)
        watchdog.now = ms(100)
        watchdog.pacer.onTimeout()
        val beforeLateTick = watchdog.clock.frameRequests
        watchdog.tick(116)
        assertEquals(beforeLateTick, watchdog.clock.frameRequests)
        assertEquals(listOf(Release(1, ms(100))), watchdog.released)
        watchdog.pacer.close()
    }

    @Test
    fun observationsDistinguishOverflowWatchdogAndEmptyTickWithoutChangingReleaseOwnership() {
        var now = 0L
        val events = mutableListOf<Pair<ScreenOutputObservation, Long>>()
        val releases = mutableListOf<Release>()
        val pacer =
            ScreenOutputPacer(
                Clock(),
                { output, time -> releases += Release(output.index, time) },
                { throw it },
                nanoTime = { now },
                observe = { event, nanos -> events += event to nanos },
            )
        pacer.offer(output(1))
        pacer.offer(output(2))
        pacer.offer(output(3))
        now = ms(100)
        pacer.onTimeout()
        now = ms(132)
        pacer.onVsync(ms(116))
        assertEquals(listOf(Release(1, null), Release(2, null), Release(3, ms(100))), releases)
        assertEquals(
            listOf(
                ScreenOutputObservation.QUEUE_OVERFLOW to 0L,
                ScreenOutputObservation.WATCHDOG_DROP to 0L,
                ScreenOutputObservation.WATCHDOG_PRESENT to 0L,
                ScreenOutputObservation.VSYNC to ms(16),
                ScreenOutputObservation.EMPTY_VSYNC to 0L,
            ),
            events,
        )
        pacer.close()
        assertEquals(5, events.size) // Lifecycle disposal doesn't count as a playback overflow.
    }

    @Test
    fun failingDiagnosticObserverCannotInterruptDisplayOrWarmClockCleanup() {
        var now = 0L
        val releases = mutableListOf<Release>()
        val clock = Clock()
        val pacer =
            ScreenOutputPacer(
                clock,
                { output, time -> releases += Release(output.index, time) },
                { throw it },
                nanoTime = { now },
                observe = { _, _ -> error("diagnostics unavailable") },
            )
        pacer.offer(output(1))
        pacer.offer(output(2))
        pacer.offer(output(3))
        now = ms(8)
        pacer.onVsync(now)
        now = ms(16)
        pacer.onVsync(now)
        now = ms(24)
        pacer.onVsync(now)
        pacer.close()
        assertEquals(listOf(Release(1, null), Release(2, ms(8)), Release(3, ms(16))), releases)
        assertEquals(1, clock.frameCancels)
    }

    @Test
    fun asynchronousClockFailureRetiresPendingOutputsAndClosesClock() {
        lateinit var fail: (Throwable) -> Unit
        var closed = false
        val releases = mutableListOf<Int>()
        val errors = mutableListOf<Throwable>()
        val clock =
            object : ScreenOutputClock {
                override fun requestFrame() = Unit

                override fun cancelFrame() = Unit

                override fun requestTimeout(delayMillis: Long) = Unit

                override fun cancelTimeout() = Unit

                override fun setFailureHandler(handler: (Throwable) -> Unit) {
                    fail = handler
                }

                override fun close() {
                    closed = true
                }
            }
        val pacer = ScreenOutputPacer(clock, { output, _ -> releases += output.index }, errors::add)
        pacer.offer(output(1))
        fail(IllegalStateException("frame clock stopped"))
        assertTrue(closed)
        assertEquals(listOf(1), releases)
        assertEquals(1, errors.size)
        pacer.close()
        pacer.onVsync(ms(16))
        assertEquals(listOf(1), releases)
    }

    @Test
    fun sixtyFpsContentDoesNotConsumeTwoOutputsOnAdjacent120HzTicks() {
        val releases = mutableListOf<Long?>()
        val clock = Clock()
        val pacer =
            ScreenOutputPacer(clock, { _, at -> releases += at }, { throw it }, targetFps = 60)
        pacer.offer(output(1))
        pacer.offer(output(2))
        pacer.onVsync(8_333_333)
        pacer.onVsync(16_666_666)
        assertEquals(listOf<Long?>(8_333_333L), releases)
        pacer.onVsync(24_999_999)
        assertEquals(listOf<Long?>(8_333_333L, 24_999_999L), releases)
        pacer.close()
    }

    @Test
    fun decodedBurstKeepsTwoNewestOutputsAndPresentsOnePerLocalTick() {
        val f = Fixture()
        f.offer(1)
        f.offer(2)
        f.offer(3)
        assertEquals(listOf(Release(1, null)), f.released)
        assertEquals(2, f.pacer.pendingOutputs)
        assertEquals(1, f.clock.frameRequests)
        f.tick(16)
        assertEquals(Release(2, ms(16)), f.released.last())
        assertEquals(1, f.pacer.pendingOutputs)
        f.tick(16) // An already dispatched duplicate display tick cannot consume another output.
        assertEquals(2, f.released.size)
        f.tick(32)
        assertEquals(Release(3, ms(32)), f.released.last())
        assertEquals(0, f.pacer.pendingOutputs)
        assertFalse(f.clock.timeoutPending)
        f.pacer.close()
        assertEquals(3, f.released.size)
    }

    @Test
    fun noVsyncWatchdogKeepsOriginalDeadlineAndRendersTheNewestStaticFrame() {
        val f = Fixture()
        f.offer(1)
        f.now = ms(90)
        f.offer(2)
        f.offer(3)
        assertEquals(listOf(100L), f.clock.timeoutDelays)
        f.now = ms(99)
        f.pacer.onTimeout()
        assertEquals(listOf(100L, 1L), f.clock.timeoutDelays)
        f.now = ms(100)
        f.pacer.onTimeout()
        assertEquals(listOf(Release(1, null), Release(2, null), Release(3, ms(100))), f.released)
        assertEquals(0, f.pacer.pendingOutputs)
        assertEquals(1, f.clock.frameCancels)
        assertFalse(f.clock.timeoutPending)
        f.offer(4)
        f.tick(99) // A stale callback from before the timeout cannot present the new buffer.
        assertEquals(3, f.released.size)
        f.tick(116)
        assertEquals(Release(4, ms(116)), f.released.last())
        f.pacer.close()
    }

    @Test
    fun closeCancelsBothCallbacksAndRetiresEveryPendingOutputExactlyOnce() {
        val f = Fixture()
        f.offer(1)
        f.offer(2)
        f.pacer.close()
        f.pacer.close()
        assertEquals(listOf(Release(1, null), Release(2, null)), f.released)
        assertEquals(1, f.clock.frameCancels)
        assertEquals(1, f.clock.timeoutCancels)
        f.offer(3)
        f.tick(200)
        f.pacer.onTimeout()
        assertEquals(2, f.released.size)
        assertEquals(0, f.pacer.pendingOutputs)
    }

    @Test
    fun aReleaseFailureRetiresTheOtherOutputsWithoutReleasingTheFailedOneTwice() {
        val clock = Clock()
        val attempts = mutableListOf<Release>()
        val errors = mutableListOf<Throwable>()
        val pacer =
            ScreenOutputPacer(
                clock,
                { output, timestamp ->
                    attempts += Release(output.index, timestamp)
                    if (output.index == 1) error("codec ownership failure")
                },
                errors::add,
                { 0 },
            )
        pacer.offer(output(1))
        pacer.offer(output(2))
        pacer.onVsync(ms(16))
        assertEquals(listOf(Release(1, ms(16)), Release(2, null)), attempts)
        assertEquals(1, errors.size)
        assertEquals(0, pacer.pendingOutputs)
        pacer.close()
        pacer.onVsync(ms(32))
        pacer.onTimeout()
        assertEquals(2, attempts.size)
    }

    @Test
    fun failedFrameSchedulingCannotLeaveCodecBuffersHeld() {
        val errors = mutableListOf<Throwable>()
        val releases = mutableListOf<Release>()
        val clock =
            object : ScreenOutputClock {
                override fun requestFrame() {
                    error("looper stopped")
                }

                override fun cancelFrame() = Unit

                override fun requestTimeout(delayMillis: Long) {
                    error("must not schedule after failure")
                }

                override fun cancelTimeout() = Unit
            }
        val pacer =
            ScreenOutputPacer(
                clock,
                { frame, timestamp -> releases += Release(frame.index, timestamp) },
                errors::add,
            )
        pacer.offer(output(1))
        assertEquals(listOf(Release(1, null)), releases)
        assertEquals(1, errors.size)
        assertEquals(0, pacer.pendingOutputs)
    }

    @Test
    fun closeAndInFlightDisplayTickCannotDoubleReleaseOrUseCodecAfterRetirement() {
        val clock = Clock()
        val rendering = CountDownLatch(1)
        val completeRender = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val releases = mutableListOf<Release>()
        val pacer =
            ScreenOutputPacer(
                clock,
                { frame, timestamp ->
                    if (frame.index == 1) {
                        rendering.countDown()
                        check(completeRender.await(2, TimeUnit.SECONDS))
                    }
                    releases += Release(frame.index, timestamp)
                },
                { failure.set(it) },
                { 0 },
            )
        pacer.offer(output(1))
        pacer.offer(output(2))
        val tick = Thread { pacer.onVsync(ms(16)) }.apply { start() }
        val stop = Thread {
            closing.countDown()
            pacer.close()
            closed.countDown()
        }
        try {
            assertTrue(rendering.await(2, TimeUnit.SECONDS))
            stop.start()
            assertTrue(closing.await(2, TimeUnit.SECONDS))
            completeRender.countDown()
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            tick.join(1_000)
            stop.join(1_000)
            assertFalse(tick.isAlive)
            assertFalse(stop.isAlive)
            assertNull(failure.get())
            assertEquals(listOf(Release(1, ms(16)), Release(2, null)), releases)
            pacer.onVsync(ms(32))
            pacer.onTimeout()
            pacer.offer(output(3))
            assertEquals(2, releases.size)
        } finally {
            completeRender.countDown()
            tick.join(1_000)
            if (stop.state != Thread.State.NEW) stop.join(1_000)
            pacer.close()
        }
    }

    @Test
    fun smoothPlaybackShowsABurstOneFramePerTickInsteadOfDroppingTheOlderOnes() {
        val dropping = Fixture()
        val smooth = Fixture(smooth = true)
        for (f in listOf(dropping, smooth)) {
            f.offer(1)
            f.offer(2)
            f.offer(3)
            f.tick(8)
            f.tick(16)
            f.tick(24)
        }
        assertEquals(
            listOf(Release(1, null), Release(2, ms(8)), Release(3, ms(16))),
            dropping.released,
        )
        assertEquals(
            listOf(Release(1, ms(8)), Release(2, ms(16)), Release(3, ms(24))),
            smooth.released,
        )
        // Beyond its capacity even the smooth queue lets the oldest go.
        val flood = Fixture(smooth = true)
        (1..ScreenOutputPacer.SMOOTH_CAPACITY + 1).forEach(flood::offer)
        assertEquals(listOf(Release(1, null)), flood.released)
        dropping.pacer.close()
        smooth.pacer.close()
        flood.pacer.close()
    }

    @Test
    fun aSmoothQueueThatStaysDeepLosesItsOldestPictureAfterAWhile() {
        val f = Fixture(smooth = true)
        f.offer(1)
        f.offer(2)
        f.offer(3)
        // One new picture per tick keeps two waiting after each one shown.
        var next = 4
        var millis = 8L
        while (millis < 8 + ScreenOutputPacer.SMOOTH_TRIM_MILLIS) {
            f.tick(millis)
            f.offer(next++)
            millis += 8
        }
        assertTrue(f.released.none { it.atNanos == null })
        f.tick(millis)
        val dropped = f.released.filter { it.atNanos == null }
        assertEquals(1, dropped.size)
        assertEquals(1, f.pacer.pendingOutputs)
        // One waiting after each tick is the allowance the queue keeps; nothing more goes.
        repeat(40) {
            f.offer(next++)
            millis += 8
            f.tick(millis)
        }
        assertEquals(1, f.released.count { it.atNanos == null })
        f.pacer.close()
    }

    private class Fixture(smooth: Boolean = false) {
        var now = 0L
        val clock = Clock()
        val released = mutableListOf<Release>()
        val pacer =
            ScreenOutputPacer(
                clock,
                { frame, timestamp -> released += Release(frame.index, timestamp) },
                { throw AssertionError(it) },
                { now },
                smooth = smooth,
            )

        fun offer(index: Int) = pacer.offer(output(index))

        fun tick(millis: Long) {
            now = ms(millis)
            pacer.onVsync(now)
        }
    }

    private class Clock : ScreenOutputClock {
        var frameRequests = 0
        var frameCancels = 0
        var timeoutCancels = 0
        var timeoutPending = false
        val timeoutDelays = mutableListOf<Long>()

        override fun requestFrame() {
            frameRequests++
        }

        override fun cancelFrame() {
            frameCancels++
        }

        override fun requestTimeout(delayMillis: Long) {
            timeoutPending = true
            timeoutDelays += delayMillis
        }

        override fun cancelTimeout() {
            timeoutPending = false
            timeoutCancels++
        }
    }

    private data class Release(val index: Int, val atNanos: Long?)

    companion object {
        private fun ms(value: Long) = value * 1_000_000

        private fun output(index: Int) = ScreenDecodedOutput(index, 9_000_000_000L + index)
    }
}
