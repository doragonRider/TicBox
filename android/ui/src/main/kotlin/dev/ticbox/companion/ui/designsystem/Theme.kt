package dev.ticbox.companion.ui.designsystem

import android.graphics.Color.TRANSPARENT
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.drawable.toDrawable
import dev.ticbox.companion.ui.AppearanceSettings
import dev.ticbox.companion.ui.ThemeMode

private val BrandLight =
    lightColorScheme(
        primary = Color(0xFF3F6837),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFC0EFB1),
        onPrimaryContainer = Color(0xFF002201),
        secondary = Color(0xFF54634D),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFD7E8CD),
        onSecondaryContainer = Color(0xFF121F0E),
        tertiary = Color(0xFF386568),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFBCEBEE),
        onTertiaryContainer = Color(0xFF002022),
        background = Color(0xFFF8FBF1),
        onBackground = Color(0xFF191D17),
        surface = Color(0xFFF8FBF1),
        onSurface = Color(0xFF191D17),
        surfaceVariant = Color(0xFFDFE4D7),
        onSurfaceVariant = Color(0xFF43483F),
        outline = Color(0xFF73796E),
        outlineVariant = Color(0xFFC3C8BC),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF2F5EB),
        surfaceContainer = Color(0xFFECEFE6),
        surfaceContainerHigh = Color(0xFFE6E9E0),
        surfaceContainerHighest = Color(0xFFE1E4DA),
    )

private val BrandDark =
    darkColorScheme(
        primary = Color(0xFFA5D396),
        onPrimary = Color(0xFF10380B),
        primaryContainer = Color(0xFF285020),
        onPrimaryContainer = Color(0xFFC0EFB1),
        secondary = Color(0xFFBBCBB2),
        onSecondary = Color(0xFF263422),
        secondaryContainer = Color(0xFF3C4B37),
        onSecondaryContainer = Color(0xFFD7E8CD),
        tertiary = Color(0xFFA0CFD2),
        onTertiary = Color(0xFF003739),
        tertiaryContainer = Color(0xFF1E4D50),
        onTertiaryContainer = Color(0xFFBCEBEE),
        background = Color(0xFF11140F),
        onBackground = Color(0xFFE1E4DA),
        surface = Color(0xFF11140F),
        onSurface = Color(0xFFE1E4DA),
        surfaceVariant = Color(0xFF43483F),
        onSurfaceVariant = Color(0xFFC3C8BC),
        outline = Color(0xFF8D9387),
        outlineVariant = Color(0xFF43483F),
        surfaceContainerLowest = Color(0xFF0C0F0A),
        surfaceContainerLow = Color(0xFF191D17),
        surfaceContainer = Color(0xFF1D211B),
        surfaceContainerHigh = Color(0xFF272B25),
        surfaceContainerHighest = Color(0xFF32362F),
    )

/**
 * Fixed colors for health data. They do not follow the wallpaper, so heart rate never looks like an
 * error and each metric keeps its color across screens.
 */
@Immutable
data class HealthColors(
    val heart: Color,
    val sleep: Color,
    val oxygen: Color,
    val activity: Color,
    val awake: Color,
    val rem: Color,
    val lightSleep: Color,
    val deepSleep: Color,
)

private val LightHealthColors =
    HealthColors(
        heart = Color(0xFFD9475A),
        sleep = Color(0xFF6750C9),
        oxygen = Color(0xFF1A7FC1),
        activity = Color(0xFFC96A00),
        awake = Color(0xFFE8A33D),
        rem = Color(0xFF3DA5D9),
        lightSleep = Color(0xFF8C7AE6),
        deepSleep = Color(0xFF4A35A8),
    )

private val DarkHealthColors =
    HealthColors(
        heart = Color(0xFFFF8A96),
        sleep = Color(0xFFB9A8FF),
        oxygen = Color(0xFF7FC8F5),
        activity = Color(0xFFFFB466),
        awake = Color(0xFFF5C46E),
        rem = Color(0xFF7FD0F2),
        lightSleep = Color(0xFFA899F5),
        deepSleep = Color(0xFF7562E0),
    )

val LocalHealthColors = staticCompositionLocalOf { LightHealthColors }

/**
 * Some makers' wallpaper palettes, Samsung's among them, give background and surface different
 * values. Scaffold fills pages with one and top bars use the other, so they would show as boxes.
 */
internal fun ColorScheme.withOnePageColor() = copy(background = surface, onBackground = onSurface)

/** The chosen colors' dark side, and how many pages on screen are showing it. */
private class DarkSide(val colors: ColorScheme, val pages: MutableIntState)

private val LocalDarkSide = staticCompositionLocalOf { DarkSide(BrandDark, mutableIntStateOf(0)) }

/**
 * A page that stays dark in both themes, in the chosen colors. The window follows it for as long as
 * it's there, or the system bar icons would be dark on dark.
 */
@Composable
fun DarkPage(content: @Composable () -> Unit) {
    val side = LocalDarkSide.current
    val pages = side.pages
    DisposableEffect(pages) {
        pages.intValue++
        onDispose { pages.intValue-- }
    }
    CompositionLocalProvider(LocalHealthColors provides DarkHealthColors) {
        MaterialTheme(colorScheme = side.colors, content = content)
    }
}

/** For what stays on screen around the pages, which goes dark along with a [DarkPage]. */
@Composable
fun AroundPages(content: @Composable () -> Unit) {
    val side = LocalDarkSide.current
    FadeSwitch(side.pages.intValue > 0) { dark ->
        MaterialTheme(
            colorScheme = if (dark) side.colors else MaterialTheme.colorScheme,
            content = content,
        )
    }
}

@Composable
fun CompanionTheme(
    appearance: AppearanceSettings = AppearanceSettings(),
    content: @Composable () -> Unit,
) {
    val wantsDark =
        when (appearance.themeMode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
    val darkPages = remember { mutableIntStateOf(0) }
    ThemeFade(wantsDark to appearance.dynamicColor) { (dark, dynamicColor) ->
        val context = LocalContext.current
        val darkColors =
            if (dynamicColor) dynamicDarkColorScheme(context).withOnePageColor() else BrandDark
        val colors =
            when {
                dark -> darkColors
                dynamicColor -> dynamicLightColorScheme(context).withOnePageColor()
                else -> BrandLight
            }
        WindowStyle(dark, colors.background, darkColors.background, darkPages)
        CompositionLocalProvider(
            LocalHealthColors provides if (dark) DarkHealthColors else LightHealthColors,
            LocalDarkSide provides DarkSide(darkColors, darkPages),
        ) {
            MaterialTheme(colorScheme = colors, content = content)
        }
    }
}

/**
 * Changes the theme under a fading picture of the old one, so everything on screen moves together.
 * Only some components animate their own colors, the top bar and the tabs among them, which on
 * their own look out of step with the pages around them. Animating the whole scheme instead would
 * recompose every page on every frame, since it is a static local, and would still leave out what
 * is chosen by how dark a color is. Those components finish early under the picture, so the fade
 * starts gently enough not to show them doing it.
 */
@Composable
private fun <T> ThemeFade(wanted: T, content: @Composable (T) -> Unit) {
    var shown by remember { mutableStateOf(wanted) }
    var picture by remember { mutableStateOf<ImageBitmap?>(null) }
    val fade = remember { Animatable(0f) }
    val screen = rememberGraphicsLayer()
    LaunchedEffect(wanted) {
        if (wanted != shown) {
            // In the middle of a fade this holds what it had reached, so turning back doesn't jump.
            val current = if (screen.size == IntSize.Zero) null else screen.toImageBitmap()
            fade.snapTo(1f)
            picture = current
            shown = wanted
        }
        fade.animateTo(0f, tween(ThemeFadeMillis, easing = EaseInOut))
        picture = null
    }
    Box(
        Modifier.fillMaxSize().drawWithContent {
            val over = picture
            val alpha = fade.value
            screen.record {
                this@drawWithContent.drawContent()
                over?.let { drawImage(it, alpha = alpha) }
            }
            drawLayer(screen)
        }
    ) {
        content(shown)
    }
}

private const val ThemeFadeMillis = 400

/**
 * The Activity is not recreated when the theme changes, so the window follows it here: the system
 * bar icons, and the background that shows around the content, e.g. while rotating. It reads
 * [darkPages] itself, so that a page opening restyles the window and recomposes nothing else.
 */
@Composable
private fun WindowStyle(
    dark: Boolean,
    background: Color,
    darkBackground: Color,
    darkPages: IntState,
) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    val darkPage = darkPages.intValue > 0
    SideEffect {
        val style = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark || darkPage }
        activity.enableEdgeToEdge(style, style)
        val behind = if (darkPage) darkBackground else background
        activity.window.setBackgroundDrawable(behind.toArgb().toDrawable())
    }
}
