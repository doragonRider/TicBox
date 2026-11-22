package dev.ticbox.companion.ui.designsystem

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w360dp-h720dp-xhdpi")
class AppBottomSheetTest {
    @get:Rule val compose = createComposeRule()

    /** How far a sheet is from where it rested, a second after being thrown up at the top. */
    private fun driftAfterAThrow(
        sheet: @Composable (@Composable ColumnScope.() -> Unit) -> Unit
    ): Float {
        lateinit var view: View
        var top = 0f
        compose.setContent {
            MaterialTheme {
                sheet {
                    view = LocalView.current
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Box(
                            Modifier.fillMaxWidth()
                                .height(600.dp)
                                .testTag("body")
                                .onGloballyPositioned { top = it.positionInRoot().y }
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val statusBar =
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 64, 0, 0))
                .build()
        compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(view, statusBar) }
        compose.waitForIdle()
        val resting = top
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("body").performTouchInput {
            swipe(center, center.copy(y = center.y - 600f), 40)
        }
        repeat(60) { compose.mainClock.advanceTimeByFrame() }
        return abs(top - resting)
    }

    @Test
    fun thrownUpAtTheTopOfTheScreenItComesBackToRest() {
        assertEquals(0f, driftAfterAThrow { AppBottomSheet(onDismiss = {}, content = it) }, 1f)
    }
}
