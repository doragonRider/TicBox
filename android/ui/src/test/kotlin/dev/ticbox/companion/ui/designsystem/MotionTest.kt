package dev.ticbox.companion.ui.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h800dp")
class MotionTest {
    @get:Rule val compose = createComposeRule()

    private fun runFor(millis: Long) {
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    private fun positions(vararg sizes: Int): IntArray {
        val positions = IntArray(sizes.size)
        with(GrowingGaps(20.dp)) { with(Density(1f)) { arrange(0, sizes, positions) } }
        return positions
    }

    @Test
    fun somethingGrowingFromNothingBringsItsGapWithIt() {
        assertContentEquals(intArrayOf(0, 20, 20), positions(10, 0, 5))
        assertContentEquals(intArrayOf(0, 20, 34), positions(10, 7, 5))
        assertContentEquals(intArrayOf(0, 50, 100), positions(30, 30, 30))
    }

    @Test
    fun aRevealedValueCanStillBeReadWhileItLeaves() {
        var value by mutableStateOf<String?>("Battery low")
        compose.setContent { Column { RevealValue(value) { Text(it) } } }
        compose.onNodeWithText("Battery low").assertIsDisplayed()

        compose.mainClock.autoAdvance = false
        value = null
        runFor(48)
        compose.onNodeWithText("Battery low").assertExists()

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("Battery low").assertDoesNotExist()
    }

    /** A ListItem going between one line and several moves everything below in a single frame. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun whatIsBelowASwitchRowFollowsItsSummaryWithoutJumping() {
        var summary by mutableStateOf("One line")
        compose.setContent {
            Column {
                SwitchRow("Title", checked = false, onCheckedChange = {}, summary = summary)
                Text("Below")
            }
        }
        compose.waitForIdle()
        fun top() = compose.onNodeWithText("Below").fetchSemanticsNode().boundsInRoot.top

        compose.mainClock.autoAdvance = false
        listOf("One\nTwo\nThree", "One line").forEach { next ->
            val start = top()
            summary = next
            val tops =
                List(60) {
                    compose.mainClock.advanceTimeByFrame()
                    top()
                }
            val largest = (listOf(start) + tops).zipWithNext { a, b -> abs(b - a) }.max()
            assertTrue(abs(tops.last() - start) > 30 * compose.density.density, "$next didn't move")
            assertTrue(largest < 8 * compose.density.density, "$next moved $largest in a frame")
        }
        compose.mainClock.autoAdvance = true
    }

    private fun shownProgress() =
        compose
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo]
            .current

    @Test
    fun theBarTravelsBetweenTheStepsProgressIsReportedIn() {
        var fraction by mutableStateOf(0.2f)
        compose.setContent { ProgressBar(fraction) }
        compose.waitForIdle()
        assertEquals(0.2f, shownProgress(), 0.001f)

        compose.mainClock.autoAdvance = false
        fraction = 0.8f
        runFor(48)
        runFor(48)
        val partWay = shownProgress()
        assertTrue(partWay > 0.2f && partWay < 0.8f, "at $partWay")

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(0.8f, shownProgress(), 0.001f)
    }
}
