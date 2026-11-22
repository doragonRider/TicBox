package dev.ticbox.companion.ui.designsystem

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ThemeColorsTest {
    @Test
    fun aPaletteWithTwoPageColorsEndsUpWithOne() {
        // Background and surface differ, so the top bar would stand out from the page.
        val palette =
            lightColorScheme(
                background = Color(0xFFF1F1F3),
                onBackground = Color(0xFF111111),
                surface = Color(0xFFFCFCFE),
                onSurface = Color(0xFF222222),
            )
        val colors = palette.withOnePageColor()
        assertEquals(palette.surface, colors.background)
        assertEquals(palette.onSurface, colors.onBackground)
    }
}
