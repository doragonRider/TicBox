package dev.ticbox.companion.ui.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// The drawing uses its own units: 240 wide and 280 tall, centered on the display.
private const val Width = 240f
private const val Height = 280f
private const val CaseRadius = 96f
private const val DisplayRadius = 86f
private const val FaceFadeMillis = 300

/** Watch displays are black whatever the phone theme is. */
private val DisplayColor = Color(0xFF101312)

/**
 * A round watch with straps and a crown at three o'clock. The display shows [face], e.g. the
 * preview of the current watch face, or a decorative dial while there is none.
 */
@Composable
fun WatchIllustration(
    modifier: Modifier = Modifier,
    face: ImageBitmap? = null,
    faceDescription: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    // Light accents read well on the black display in both themes.
    val accent =
        if (colors.background.luminance() < 0.5f) colors.primary else colors.primaryContainer
    val faceBrush = remember(face) { face?.let { ShaderBrush(ImageShader(it)) } }
    val faceAlpha by
        animateFloatAsState(if (face == null) 0f else 1f, tween(FaceFadeMillis), label = "face")
    val description =
        if (face != null && faceDescription != null) {
            Modifier.semantics {
                contentDescription = faceDescription
                role = Role.Image
            }
        } else {
            Modifier
        }
    Canvas(modifier.then(description)) {
        val scale = min(size.width / Width, size.height / Height)
        withTransform({
            translate(size.width / 2, size.height / 2)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            drawStrap(colors.outlineVariant, colors.background)
            drawCrown(colors.outline, colors.surfaceContainerHighest)
            drawCircle(colors.outline, CaseRadius, Offset.Zero)
            drawCircle(DisplayColor, DisplayRadius + 3, Offset.Zero)
            if (faceAlpha < 1f) drawDial(accent)
            if (face != null && faceBrush != null) drawFace(face, faceBrush, faceAlpha)
        }
    }
}

/** One band behind the case, as if closed around a wrist, with slots on its lower half. */
private fun DrawScope.drawStrap(color: Color, slots: Color) {
    drawRoundRect(color, Offset(-46f, -138f), Size(92f, 276f), CornerRadius(24f))
    repeat(4) { index ->
        drawRoundRect(slots, Offset(-10f, 102f + index * 8), Size(20f, 4f), CornerRadius(2f))
    }
}

/** The crown sits at three o'clock and partly hides behind the case. */
private fun DrawScope.drawCrown(color: Color, ridges: Color) {
    drawRoundRect(color, Offset(84f, -14f), Size(22f, 28f), CornerRadius(5f))
    for (y in listOf(-7f, 0f, 7f)) {
        drawLine(ridges, Offset(99f, y), Offset(103f, y), strokeWidth = 1.5f, StrokeCap.Round)
    }
}

private fun DrawScope.drawDial(accent: Color) {
    repeat(12) { hour ->
        val angle = hour * PI / 6
        val outer = 74.0
        val inner = if (hour % 3 == 0) 62.0 else 67.0
        drawLine(
            Color.White.copy(alpha = 0.7f),
            Offset((sin(angle) * inner).toFloat(), (-cos(angle) * inner).toFloat()),
            Offset((sin(angle) * outer).toFloat(), (-cos(angle) * outer).toFloat()),
            strokeWidth = 3f,
            cap = StrokeCap.Round,
        )
    }
    drawArc(
        accent,
        startAngle = -90f,
        sweepAngle = 250f,
        useCenter = false,
        topLeft = Offset(-18f, 20f),
        size = Size(36f, 36f),
        style = Stroke(5f, cap = StrokeCap.Round),
    )
    drawLine(Color.White, Offset.Zero, Offset(-30f, -26f), 8f, StrokeCap.Round)
    drawLine(Color.White, Offset.Zero, Offset(38f, -40f), 6f, StrokeCap.Round)
    drawCircle(accent, 6f, Offset.Zero)
}

/** Fills the display with the middle square of [image]. */
private fun DrawScope.drawFace(image: ImageBitmap, brush: ShaderBrush, alpha: Float) {
    val side = min(image.width, image.height).toFloat()
    withTransform({
        scale(DisplayRadius * 2 / side, DisplayRadius * 2 / side, pivot = Offset.Zero)
        translate(-image.width / 2f, -image.height / 2f)
    }) {
        drawCircle(brush, side / 2, Offset(image.width / 2f, image.height / 2f), alpha)
    }
}
