package dev.ticbox.companion.ui.toolbox

import dev.ticbox.companion.api.ToolboxException
import dev.ticbox.companion.api.ToolboxScreenEvent
import dev.ticbox.companion.api.ToolboxScreenInput
import dev.ticbox.companion.api.ToolboxScreenSession
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ScreenControlQueueTest {
    private fun touch(action: Int, id: Long = 1, x: Int = 0) =
        ToolboxScreenInput.Touch(
            action,
            id,
            x,
            0,
            1000,
            1000,
            pressure = if (action == TOUCH_UP) 0f else 1f,
        )

    @Test
    fun slowTransportKeepsLatestMoveAndDeliversReleaseBeforeTheNextGesture() = runTest {
        val queue = ScreenControlQueue()
        val unblock = CompletableDeferred<Unit>()
        val session = RecordingScreen {
            if ((it as? ToolboxScreenInput.Touch)?.action == TOUCH_DOWN && it.pointerId == 1L)
                unblock.await()
        }
        queue.touch(touch(TOUCH_DOWN))
        val first = async { queue.sendNext(session) }
        runCurrent()
        repeat(10_000) { queue.touch(touch(TOUCH_MOVE, x = it % 1000)) }
        queue.touch(touch(TOUCH_UP, x = 999))
        queue.touch(touch(TOUCH_DOWN, id = 2))
        queue.touch(touch(TOUCH_UP, id = 2))
        unblock.complete(Unit)
        assertTrue(first.await())
        repeat(4) { assertTrue(queue.sendNext(session)) }
        assertEquals(
            listOf(TOUCH_DOWN, TOUCH_MOVE, TOUCH_UP, TOUCH_DOWN, TOUCH_UP),
            session.inputs.map { (it as ToolboxScreenInput.Touch).action },
        )
        assertEquals(999, (session.inputs[1] as ToolboxScreenInput.Touch).x)
        assertEquals(
            listOf(1L, 1L, 1L, 2L, 2L),
            session.inputs.map { (it as ToolboxScreenInput.Touch).pointerId },
        )
        queue.close()
        assertFalse(queue.sendNext(session))
    }

    @Test
    fun twoFingersRetainTheirOwnLatestPositionAndBothUpBoundaries() = runTest {
        val queue = ScreenControlQueue()
        val session = RecordingScreen()
        queue.touch(touch(TOUCH_DOWN, id = 1))
        queue.touch(touch(TOUCH_DOWN, id = 2))
        repeat(1000) {
            queue.touch(touch(TOUCH_MOVE, id = 1, x = it))
            queue.touch(touch(TOUCH_MOVE, id = 2, x = 999 - it))
        }
        queue.touch(touch(TOUCH_UP, id = 2))
        queue.touch(touch(TOUCH_MOVE, id = 1, x = 123))
        queue.touch(touch(TOUCH_UP, id = 1))
        repeat(6) { assertTrue(queue.sendNext(session)) }
        val sent = session.inputs.map { it as ToolboxScreenInput.Touch }
        assertEquals(
            listOf(TOUCH_DOWN, TOUCH_DOWN, TOUCH_MOVE, TOUCH_MOVE, TOUCH_UP, TOUCH_MOVE, TOUCH_UP),
            sent.map { it.action },
        )
        assertEquals(listOf(1L, 2L, 1L, 2L, 2L, 1L, 1L), sent.map { it.pointerId })
        assertEquals(listOf(999, 0), sent.slice(2..3).map { it.x })
        queue.close()
    }

    @Test
    fun keysAndClipboardRemainOrderedBarriersBetweenMoves() = runTest {
        val queue = ScreenControlQueue()
        val session = RecordingScreen()
        queue.touch(touch(TOUCH_MOVE, x = 1))
        queue.action {
            it.input(ToolboxScreenInput.Key(4, true))
            it.input(ToolboxScreenInput.Key(4, false))
        }
        queue.touch(touch(TOUCH_MOVE, x = 2))
        queue.action { it.setClipboard("text", paste = true) }
        queue.touch(touch(TOUCH_MOVE, x = 3))
        repeat(5) { assertTrue(queue.sendNext(session)) }
        assertEquals(
            listOf("touch:1", "key:true", "key:false", "touch:2", "paste", "touch:3"),
            session.order,
        )
        queue.close()
    }

    @Test
    fun fullBoundaryQueueFailsTheSessionInsteadOfDroppingAnUpOrLeakingMemory() = runTest {
        val queue = ScreenControlQueue()
        repeat(129) { queue.touch(touch(if (it % 2 == 0) TOUCH_DOWN else TOUCH_UP)) }
        assertEquals(
            "screen_control_backlog",
            assertFailsWith<ToolboxException> { queue.sendNext(RecordingScreen()) }.code,
        )
        queue.close()
    }

    @Test
    fun closeUnblocksTheIdleSenderAndDiscardsOldSessionEvents() = runTest {
        val queue = ScreenControlQueue()
        val session = RecordingScreen()
        val waiting = async { queue.sendNext(session) }
        runCurrent()
        queue.close()
        queue.touch(touch(TOUCH_DOWN))
        assertFalse(waiting.await())
        assertTrue(session.inputs.isEmpty())
    }

    @Test
    fun disabledStatisticsNeverReadTheDiagnosticClockOrReportControlTiming() = runTest {
        val queue = ScreenControlQueue { error("diagnostic clock used while disabled") }
        val session = RecordingScreen()
        queue.touch(touch(TOUCH_DOWN))
        queue.touch(touch(TOUCH_UP))
        assertTrue(queue.sendNext(session))
        assertTrue(queue.sendNext(session))
        assertEquals(2, session.inputs.size)
        assertTrue(session.queued.isEmpty())
        queue.close()
    }

    @Test
    fun onlyDownAndUpReportQueueTimeWithoutChangingMovesKeysOrClipboard() = runTest {
        var nanos = 0L
        val queue = ScreenControlQueue(collectDiagnostics = true) { nanos }
        val session = RecordingScreen()
        queue.touch(touch(TOUCH_DOWN, id = 91, x = 123))
        nanos = 5_000_000
        queue.touch(touch(TOUCH_MOVE, id = 91, x = 456))
        queue.touch(touch(TOUCH_UP, id = 91, x = 789))
        queue.action { it.setClipboard("private clipboard", paste = true) }
        nanos = 23_000_000
        assertTrue(queue.sendNext(session))
        assertTrue(queue.sendNext(session))
        nanos = 25_000_000
        assertTrue(queue.sendNext(session))
        assertTrue(queue.sendNext(session))
        assertEquals(listOf(TOUCH_DOWN to 23L, TOUCH_UP to 20L), session.queued)
        assertEquals(listOf("touch:123", "touch:456", "touch:789", "paste"), session.order)
        queue.close()
    }

    @Test
    fun diagnosticFailureCannotDropTheControlAction() = runTest {
        val queue = ScreenControlQueue(collectDiagnostics = true) { 0L }
        val recorded = RecordingScreen()
        val session =
            object : ToolboxScreenSession by recorded {
                override fun reportControlQueued(action: Int, waitMillis: Long) {
                    error("diagnostics unavailable")
                }
            }
        queue.touch(touch(TOUCH_UP))
        assertTrue(queue.sendNext(session))
        assertEquals(TOUCH_UP, (recorded.inputs.single() as ToolboxScreenInput.Touch).action)
        queue.close()
    }

    private class RecordingScreen(private val before: suspend (ToolboxScreenInput) -> Unit = {}) :
        ToolboxScreenSession {
        val inputs = mutableListOf<ToolboxScreenInput>()
        val queued = mutableListOf<Pair<Int, Long>>()
        val order = mutableListOf<String>()
        override val events = emptyFlow<ToolboxScreenEvent>()

        override fun reportControlQueued(action: Int, waitMillis: Long) {
            queued += action to waitMillis
        }

        override suspend fun input(event: ToolboxScreenInput) {
            before(event)
            inputs += event
            order +=
                when (event) {
                    is ToolboxScreenInput.Touch -> "touch:${event.x}"
                    is ToolboxScreenInput.Key -> "key:${event.down}"
                    else -> "other"
                }
        }

        override suspend fun getClipboard() = Unit

        override suspend fun setClipboard(text: String, paste: Boolean): Long {
            order += "paste"
            return 1
        }

        override fun close() = Unit
    }
}
