package dev.ticbox.companion.ui.settings

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.ticbox.companion.ui.AppIconStyle
import dev.ticbox.companion.ui.CompanionApp
import dev.ticbox.companion.ui.FaceShortcut
import dev.ticbox.companion.ui.FakeSessions
import dev.ticbox.companion.ui.NoSystemActions
import dev.ticbox.companion.ui.TestDependencies
import dev.ticbox.companion.ui.demoWatch
import dev.ticbox.companion.ui.navigation.AppearanceRoute
import dev.ticbox.companion.ui.session
import dev.ticbox.companion.ui.session.SessionState
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h891dp-mdpi")
class AppIconChoiceTest {
    @get:Rule val compose = createComposeRule()

    private class FakeFaceShortcut : FaceShortcut {
        override val face = MutableStateFlow<ImageBitmap?>(null)
        var pins = 0

        override fun offer(face: ImageBitmap) {
            this.face.value = face
        }

        override fun pin(): Boolean {
            pins++
            return true
        }
    }

    private val shortcut = FakeFaceShortcut()
    private val dependencies =
        TestDependencies(
            FakeSessions(
                SessionState.Ready(demoWatch(RuntimeEnvironment.getApplication()).session())
            ),
            faceShortcut = shortcut,
        )

    private fun openAppearance() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            CompanionApp(dependencies, NoSystemActions, nav)
        }
        compose.waitForIdle()
        compose.runOnUiThread { nav.navigate(AppearanceRoute) }
        compose.waitForIdle()
    }

    @Test
    fun choosingAnIconKeepsItAndMarksItAsChosen() {
        openAppearance()
        compose.onNodeWithTag("app_icon_WARM_WHITE").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("app_icon_NAVY").performClick()
        compose.waitForIdle()
        assertEquals(AppIconStyle.NAVY, dependencies.preferences.appearance.value.appIcon)
        compose.onNodeWithTag("app_icon_NAVY").assertIsSelected()
        compose.onNodeWithTag("app_icon_WARM_WHITE").assertIsNotSelected()
    }

    @Test
    fun theFaceShortcutWaitsForARealWatchsFaceThenPinsIt() {
        openAppearance()
        // The demo watch is the only one here, and its face must not become the shortcut's.
        compose.onNodeWithTag("face_shortcut").performScrollTo().assertIsNotEnabled()
        shortcut.offer(ImageBitmap(8, 8))
        compose.waitForIdle()
        compose.onNodeWithTag("face_shortcut").assertIsEnabled().performClick()
        assertEquals(1, shortcut.pins)
    }
}
