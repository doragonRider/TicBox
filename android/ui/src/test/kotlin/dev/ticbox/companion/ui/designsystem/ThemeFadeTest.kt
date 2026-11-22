package dev.ticbox.companion.ui.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.ticbox.companion.ui.CompanionApp
import dev.ticbox.companion.ui.FakeSessions
import dev.ticbox.companion.ui.NoSystemActions
import dev.ticbox.companion.ui.TestDependencies
import dev.ticbox.companion.ui.ThemeMode
import dev.ticbox.companion.ui.demoWatch
import dev.ticbox.companion.ui.navigation.AppearanceRoute
import dev.ticbox.companion.ui.session
import dev.ticbox.companion.ui.session.SessionState
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Empty parts of a top bar and a page, which share one color when the page isn't scrolled. */
private class Screen(image: ImageBitmap) {
    private val pixels = image.toPixelMap()
    val bar = pixels[image.width * 9 / 10, image.height / 30]
    val page = pixels[image.width / 2, image.height * 19 / 20]
}

private fun Color.brightness() = (red + green + blue) / 3

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h891dp-mdpi")
class ThemeFadeTest {
    @get:Rule val compose = createComposeRule()

    private val session = demoWatch(RuntimeEnvironment.getApplication()).session(key = "real")
    private val dependencies = TestDependencies(FakeSessions(SessionState.Ready(session)))
    private lateinit var light: Screen
    private lateinit var dark: Screen

    private fun screen() = Screen(compose.onRoot().captureToImage())

    private fun setTheme(mode: ThemeMode) {
        dependencies.preferences.setThemeMode(mode)
    }

    private fun runFor(millis: Long) {
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    @Before
    fun openAppearanceAndLearnBothThemes() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            CompanionApp(dependencies, NoSystemActions, nav)
        }
        compose.waitForIdle()
        compose.runOnUiThread { nav.navigate(AppearanceRoute) }
        compose.waitForIdle()
        setTheme(ThemeMode.DARK)
        compose.waitForIdle()
        dark = screen()
        setTheme(ThemeMode.LIGHT)
        compose.waitForIdle()
        light = screen()
        compose.mainClock.autoAdvance = false
    }

    @Test
    fun theBarAndThePageChangeTogetherAndEndOnTheNewTheme() {
        assertEquals(light.bar, light.page)
        setTheme(ThemeMode.DARK)
        // Each of these is a moment when one on its own would stand out from the other.
        for (millis in listOf(48L, 48L, 48L, 96L)) {
            runFor(millis)
            val now = screen()
            assertTrue(abs(now.bar.brightness() - now.page.brightness()) < 0.03f, "$millis ms")
        }
        val halfway = screen().page.brightness()
        assertTrue(halfway < light.page.brightness() - 0.1f, "still looks like the old theme")
        assertTrue(halfway > dark.page.brightness() + 0.1f, "already looks like the new theme")
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(dark.page, screen().page)
        assertEquals(dark.bar, screen().bar)
    }

    @Test
    fun turningBackMidwayCarriesOnFromWhereTheFadeHadGot() {
        setTheme(ThemeMode.DARK)
        runFor(208)
        val before = screen().page.brightness()
        assertTrue(before < light.page.brightness() - 0.1f)
        setTheme(ThemeMode.LIGHT)
        runFor(32)
        assertTrue(abs(screen().page.brightness() - before) < 0.03f)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(light.page, screen().page)
    }
}
