package dev.ticbox.companion.ui.toolbox

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.ticbox.companion.api.ToolboxScreenInput
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteScreenTouchTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun crossingAnyRectangleEdgeReleasesBeforePhysicalUpAndReentryRequiresANewDown() {
        val sent = mutableListOf<ToolboxScreenInput.Touch>()
        compose.setContent {
            TouchLayer(480, 960, sent::add, Modifier.size(200.dp, 300.dp).testTag("screen"))
        }
        val screen = compose.onNodeWithTag("screen")
        repeat(4) { edge ->
            compose.runOnIdle { sent.clear() }
            screen.performTouchInput {
                down(center)
                moveBy(Offset(2f, 2f))
                moveTo(
                    when (edge) {
                        0 -> Offset(-1f, center.y)
                        1 -> Offset(width.toFloat(), center.y)
                        2 -> Offset(center.x, -1f)
                        else -> Offset(center.x, height.toFloat())
                    }
                )
            }
            compose.runOnIdle {
                assertEquals(listOf(TOUCH_DOWN, TOUCH_MOVE, TOUCH_UP), sent.map { it.action })
                assertEquals(sent[1].x, sent[2].x)
                assertEquals(sent[1].y, sent[2].y)
                assertEquals(0f, sent[2].pressure)
                assertEquals(0, sent[2].buttons)
            }
            screen.performTouchInput {
                moveTo(center)
                up()
            }
            compose.runOnIdle { assertEquals(3, sent.size) }
            screen.performTouchInput {
                down(center)
                up()
            }
            compose.runOnIdle {
                assertEquals(
                    listOf(TOUCH_DOWN, TOUCH_MOVE, TOUCH_UP, TOUCH_DOWN, TOUCH_UP),
                    sent.map { it.action },
                )
            }
        }
    }

    @Test
    fun crossingTheCircleReleasesEvenWhileStillInsideItsBoundingSquare() {
        val sent = mutableListOf<ToolboxScreenInput.Touch>()
        compose.setContent {
            TouchLayer(
                454,
                454,
                sent::add,
                Modifier.size(200.dp).testTag("screen"),
                CircleShape,
            )
        }
        val screen = compose.onNodeWithTag("screen")
        repeat(4) { corner ->
            compose.runOnIdle { sent.clear() }
            screen.performTouchInput {
                down(center)
                moveBy(Offset(2f, 2f))
                moveTo(
                    Offset(
                        width * if (corner % 2 == 0) 0.02f else 0.98f,
                        height * if (corner < 2) 0.02f else 0.98f,
                    )
                )
            }
            compose.runOnIdle {
                assertEquals(listOf(TOUCH_DOWN, TOUCH_MOVE, TOUCH_UP), sent.map { it.action })
                assertEquals(sent[1].x, sent[2].x)
                assertEquals(sent[1].y, sent[2].y)
            }
            screen.performTouchInput {
                moveTo(center)
                up()
            }
            compose.runOnIdle { assertEquals(3, sent.size) }
        }
    }

    @Test
    fun aDownOutsideTheCircleCannotBecomeAPressBySlidingInside() {
        val sent = mutableListOf<ToolboxScreenInput.Touch>()
        compose.setContent {
            TouchLayer(
                454,
                454,
                sent::add,
                Modifier.size(200.dp).testTag("screen"),
                CircleShape,
            )
        }
        val screen = compose.onNodeWithTag("screen")
        screen.performTouchInput {
            down(Offset(1f, 1f))
            moveTo(center)
            up()
        }
        compose.runOnIdle { assertTrue(sent.isEmpty()) }
        screen.performTouchInput {
            down(center)
            up()
        }
        compose.runOnIdle { assertEquals(listOf(TOUCH_DOWN, TOUCH_UP), sent.map { it.action }) }
    }

    @Test
    fun rectangularScreensKeepTheirCornersTouchableEvenWithASquareVideo() {
        val sent = mutableListOf<ToolboxScreenInput.Touch>()
        compose.setContent {
            TouchLayer(480, 480, sent::add, Modifier.size(200.dp).testTag("screen"))
        }
        compose.onNodeWithTag("screen").performTouchInput {
            down(Offset(1f, 1f))
            moveBy(Offset(2f, 2f))
            up()
        }
        compose.runOnIdle {
            assertEquals(listOf(TOUCH_DOWN, TOUCH_MOVE, TOUCH_UP), sent.map { it.action })
        }
    }

    @Test
    fun roundedRectangleCornersUseTheSameBoundaryAsThePicture() {
        val sent = mutableListOf<ToolboxScreenInput.Touch>()
        compose.setContent {
            TouchLayer(
                480,
                960,
                sent::add,
                Modifier.size(200.dp, 300.dp).testTag("screen"),
                RoundedCornerShape(40.dp),
            )
        }
        compose.onNodeWithTag("screen").performTouchInput {
            down(center)
            moveTo(Offset(1f, 1f))
        }
        compose.runOnIdle { assertEquals(listOf(TOUCH_DOWN, TOUCH_UP), sent.map { it.action }) }
        compose.onNodeWithTag("screen").performTouchInput { up() }
        compose.runOnIdle { assertEquals(2, sent.size) }
    }

    @Test
    fun oneFingerLeavingDoesNotReleaseTheOtherOrSendDuplicateUpsDuringCleanup() {
        val showing = mutableStateOf(true)
        val sent = mutableListOf<ToolboxScreenInput.Touch>()
        compose.setContent {
            if (showing.value)
                TouchLayer(480, 960, sent::add, Modifier.size(200.dp, 300.dp).testTag("screen"))
        }
        compose.onNodeWithTag("screen").performTouchInput {
            down(0, center)
            down(1, center + Offset(10f, 10f))
            moveTo(0, Offset(-1f, center.y))
            moveBy(1, Offset(5f, 5f))
        }
        compose.runOnIdle {
            assertEquals(
                listOf(TOUCH_DOWN, TOUCH_DOWN, TOUCH_UP, TOUCH_MOVE),
                sent.map { it.action },
            )
            assertEquals(sent[0].pointerId, sent[2].pointerId)
            assertEquals(sent[1].pointerId, sent[3].pointerId)
            showing.value = false
        }
        compose.runOnIdle {
            val ups = sent.filter { it.action == TOUCH_UP }
            assertEquals(2, ups.size)
            assertEquals(2, ups.map { it.pointerId }.distinct().size)
            assertEquals(sent[1].pointerId, ups.last().pointerId)
        }
    }

    @Test
    fun newVideoDimensionsDoNotBreakTheGestureOrKeepUsingTheOldCoordinateSpace() {
        val frameWidth = mutableStateOf(480)
        val sent = mutableListOf<ToolboxScreenInput.Touch>()
        compose.setContent {
            TouchLayer(frameWidth.value, 480, sent::add, Modifier.size(200.dp).testTag("screen"))
        }
        compose.onNodeWithTag("screen").performTouchInput { down(Offset(20f, 30f)) }
        compose.runOnIdle { frameWidth.value = 800 }
        compose.onNodeWithTag("screen").performTouchInput {
            moveBy(Offset(10f, 10f))
            up()
        }
        compose.runOnIdle {
            assertEquals(listOf(TOUCH_DOWN, TOUCH_MOVE, TOUCH_UP), sent.map { it.action })
            assertEquals(listOf(480, 800, 800), sent.map { it.width })
            assertEquals(1, sent.map { it.pointerId }.distinct().size)
        }
    }

    @Test
    fun cancellationAndDisablingTouchReleaseEveryPressedFingerExactlyOnce() {
        val showing = mutableStateOf(true)
        val sent = mutableListOf<ToolboxScreenInput.Touch>()
        compose.setContent {
            if (showing.value)
                TouchLayer(480, 480, sent::add, Modifier.size(200.dp).testTag("screen"))
        }
        compose.onNodeWithTag("screen").performTouchInput {
            down(0, Offset(20f, 30f))
            down(1, Offset(60f, 70f))
            moveBy(0, Offset(10f, 10f))
        }
        compose.runOnIdle { showing.value = false }
        compose.runOnIdle {
            val downs = sent.filter { it.action == TOUCH_DOWN }
            val ups = sent.filter { it.action == TOUCH_UP }
            assertEquals(2, downs.size)
            assertEquals(2, ups.size)
            assertEquals(downs.map { it.pointerId }.toSet(), ups.map { it.pointerId }.toSet())
            assertTrue(ups.all { it.pressure == 0f && it.buttons == 0 })
        }
    }

    @Test
    fun systemCancelDoesNotLeaveAnOldPointerPressedOnTheNextGesture() {
        val sent = mutableListOf<ToolboxScreenInput.Touch>()
        compose.setContent {
            TouchLayer(480, 480, sent::add, Modifier.size(200.dp).testTag("screen"))
        }
        compose.onNodeWithTag("screen").performTouchInput {
            down(Offset(20f, 30f))
            moveBy(Offset(15f, 15f))
            cancel()
            down(Offset(80f, 90f))
            up()
        }
        compose.runOnIdle {
            val boundaries = sent.filter { it.action != TOUCH_MOVE }
            assertEquals(
                listOf(TOUCH_DOWN, TOUCH_UP, TOUCH_DOWN, TOUCH_UP),
                boundaries.map { it.action },
            )
            assertEquals(boundaries[0].pointerId, boundaries[1].pointerId)
            assertEquals(boundaries[2].pointerId, boundaries[3].pointerId)
        }
    }
}
