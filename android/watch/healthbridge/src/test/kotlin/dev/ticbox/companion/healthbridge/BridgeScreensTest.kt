package dev.ticbox.companion.healthbridge

import android.app.Application
import android.os.Bundle
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performRotaryScrollInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import dev.ticbox.companion.healthbridge.ui.ExportForm
import dev.ticbox.companion.protocol.LocalHealthTable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val WATCH = "w233dp-h233dp-round-watch-xhdpi"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "en-rUS-$WATCH")
class BridgeSetupScreenTest {
    @get:Rule val compose = createAndroidComposeRule<BridgeSetupActivity>()

    @Test
    fun theFirstSwitchIsOnTheFirstScreenAndIsKept() {
        compose.onNodeWithText("Local Bridge").assertIsDisplayed()
        compose.onNodeWithText("Allow phone to read").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertTrue(BridgeSettings.enabled(ApplicationProvider.getApplicationContext()))
    }

    @Test
    @Config(qualifiers = "zh-rCN-$WATCH")
    fun simplifiedChineseHasItsOwnName() {
        compose.onNodeWithText("本地桥接").assertIsDisplayed()
        compose.onNodeWithText("允许手机读取").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "b+zh+Hant+TW-$WATCH")
    fun traditionalChineseHasItsOwnName() {
        compose.onNodeWithText("本地橋接").assertIsDisplayed()
        compose.onNodeWithText("允許手機讀取").assertIsDisplayed()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "en-rUS-$WATCH")
class HealthExportScreenTest {
    @get:Rule val compose = createAndroidComposeRule<HealthExportActivity>()
    private lateinit var downloads: DownloadsFixtureProvider

    @Before fun prepareDownloads() {
        downloads = DownloadsFixtureProvider.install(ApplicationProvider.getApplicationContext())
    }

    @After fun cleanupDownloads() { downloads.shutdown() }

    private val list
        get() = compose.onNode(hasScrollAction())

    /** Like the edge button on any watch list, this one only appears at the end of the list. */
    private fun tapExport() {
        list.performScrollToNode(hasText("Rows are read", substring = true))
        compose.onNodeWithText("Export").performClick()
    }

    private fun waitForStatus(text: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText(text, substring = true)).assertIsDisplayed()
    }

    @Test
    fun aBadSelectionIsExplainedWithoutExporting() {
        list.performScrollToNode(hasText("Rows per source"))
        compose.onNode(hasSetTextAction() and hasText("200")).performTextReplacement("0")
        tapExport()
        waitForStatus("Choose at least one source")
    }

    @Test
    fun anExportSaysWhereItsFileWentRightAboveTheButton() {
        tapExport()
        waitForStatus("Download/TicBox/health-export-")
        assertEquals(1, downloads.entries.size)
    }

    @Test
    fun leavingThePageMidExportKeepsNoFile() {
        val export = CountDownLatch(1)
        downloads.holdOpen = export
        tapExport()
        compose.waitUntil(10_000) { downloads.entries.size == 1 }
        compose.activityRule.scenario.close()
        export.countDown()
        assertTrue(downloads.removed.await(10, TimeUnit.SECONDS))
        assertTrue(downloads.entries.isEmpty())
    }

    @Test
    fun aWorkoutsPointsHaveNoTypesToFilterBy() {
        val points = "Workout points and GPS (root POINT)"
        val sport = "Workout summaries (SPORT)"
        list.performScrollToNode(hasText(sport))
        compose.onNodeWithText(sport).performClick()
        compose.waitForIdle()
        compose.onNode(hasText("Uses its own workout IDs")).assertExists()
        compose.onNodeWithText(sport).performClick()
        list.performScrollToNode(hasText(points))
        compose.onNodeWithText(points).performClick()
        compose.waitForIdle()
        compose.onNode(hasText("Uses its own workout IDs")).assertDoesNotExist()
    }

    private fun scrolled() =
        list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun turnCrown(pixels: Float) {
        list.performRotaryScrollInput { rotateToScrollVertically(pixels) }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    @Test
    fun theCrownStillScrollsAfterTyping() {
        list.performScrollToNode(hasText("Type IDs"))
        val types = compose.onAllNodes(hasSetTextAction())[0]
        types.performClick()
        types.assertIsFocused()
        types.performImeAction()
        compose.waitForIdle()
        types.assertIsNotFocused()
        val before = scrolled()
        turnCrown(500f)
        assertTrue(scrolled() > before)
    }

    @Test
    fun theCrownKeepsScrollingWhenAFocusedFieldScrollsAway() {
        list.performScrollToNode(hasText("Type IDs"))
        val types = compose.onAllNodes(hasSetTextAction())[0]
        types.performClick()
        types.assertIsFocused()
        turnCrown(1_000f)
        types.assertIsNotDisplayed()
        val before = scrolled()
        turnCrown(300f)
        assertTrue(scrolled() > before)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class ExportFormTest {
    @Test
    fun theSelectionIsReadFromWhatWasEntered() {
        val form = ExportForm(null)
        form.useSleepAndOxygenPreset()
        form.from = "2026-11-03"
        form.to = "2026-11-03"
        val selection = assertNotNull(form.selection())
        assertEquals(
            mapOf(LocalHealthTable.POINT to setOf(2L, 28L), LocalHealthTable.SESSION to setOf(100L)),
            selection.tables,
        )
        assertEquals(200, selection.maxRowsPerTable)
        assertTrue(selection.redactIdentities)
    }

    @Test
    fun unusableEntriesGiveNoSelection() {
        fun entered(change: ExportForm.() -> Unit) = ExportForm(null).apply(change).selection()
        assertNull(entered { sources.forEach { it.selected = false } })
        assertNull(entered { maximum = "5001" })
        assertNull(entered { from = "soon" })
        assertNull(entered { sources.first().types = "oxygen" })
        assertNull(
            entered {
                from = "2026-11-04"
                to = "2026-11-03"
            }
        )
    }

    @Test
    fun whatWasEnteredSurvivesRecreation() {
        val saved = Bundle()
        ExportForm(null)
            .apply {
                sources.last().selected = true
                sources.first().types = "28"
                maximum = "50"
                redaction = false
                from = "2026-11-03"
            }
            .save(saved)
        val restored = ExportForm(saved)
        assertTrue(restored.sources.last().selected)
        assertEquals("28", restored.sources.first().types)
        assertEquals("50", restored.maximum)
        assertFalse(restored.redaction)
        assertEquals("2026-11-03", restored.from)
    }
}
