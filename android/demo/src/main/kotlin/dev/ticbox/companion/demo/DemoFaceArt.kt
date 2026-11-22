package dev.ticbox.companion.demo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import dev.ticbox.companion.api.WatchFaceImage
import java.io.ByteArrayOutputStream
import kotlin.math.cos
import kotlin.math.sin

/** Original, simple analog faces rendered locally; stands in for watch-provided previews. */
internal object DemoFaceArt {
    data class Palette(val background: Int, val ticks: Int, val hands: Int, val accent: Int)

    val palettes =
        listOf(
            Palette(0xFF101820.toInt(), 0xFFB8C4CC.toInt(), 0xFFF2F5F7.toInt(), 0xFF3CC8B4.toInt()),
            Palette(0xFF1F1A17.toInt(), 0xFFD2C4B4.toInt(), 0xFFFFF4E8.toInt(), 0xFFFF7A3D.toInt()),
            Palette(0xFFE9EEF0.toInt(), 0xFF5B6770.toInt(), 0xFF1C2328.toInt(), 0xFF2E7DD7.toInt()),
            Palette(0xFF14231A.toInt(), 0xFFA7C4A0.toInt(), 0xFFEAF3E6.toInt(), 0xFFE2C044.toInt()),
        )

    fun render(style: Int, size: Int = 320): WatchFaceImage {
        val palette = palettes[style % palettes.size]
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val center = size / 2f
        val radius = size / 2f

        paint.color = palette.background
        canvas.drawCircle(center, center, radius, paint)

        paint.strokeCap = Paint.Cap.ROUND
        for (tick in 0 until 60) {
            val major = tick % 5 == 0
            val angle = Math.toRadians(tick * 6.0)
            val outer = radius * 0.92f
            val inner = radius * if (major) 0.80f else 0.87f
            paint.color = palette.ticks
            paint.strokeWidth = size * if (major) 0.018f else 0.007f
            canvas.drawLine(
                center + (sin(angle) * inner).toFloat(),
                center - (cos(angle) * inner).toFloat(),
                center + (sin(angle) * outer).toFloat(),
                center - (cos(angle) * outer).toFloat(),
                paint,
            )
        }

        // A progress ring in the lower half, like a step-goal complication.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.025f
        paint.color = palette.ticks and 0x40FFFFFF
        val ring =
            RectF(
                center - radius * 0.2f,
                center + radius * 0.18f,
                center + radius * 0.2f,
                center + radius * 0.58f,
            )
        canvas.drawArc(ring, 0f, 360f, false, paint)
        paint.color = palette.accent
        canvas.drawArc(ring, -90f, 70f + style * 60f, false, paint)
        paint.style = Paint.Style.FILL

        fun hand(degrees: Double, length: Float, width: Float, color: Int) {
            val angle = Math.toRadians(degrees)
            paint.color = color
            paint.strokeWidth = width
            canvas.drawLine(
                center,
                center,
                center + (sin(angle) * length).toFloat(),
                center - (cos(angle) * length).toFloat(),
                paint,
            )
        }
        hand(305.0, radius * 0.48f, size * 0.035f, palette.hands)
        hand(60.0, radius * 0.72f, size * 0.024f, palette.hands)
        hand(180.0 + style * 40.0, radius * 0.78f, size * 0.01f, palette.accent)
        paint.color = palette.accent
        canvas.drawCircle(center, center, size * 0.025f, paint)

        val bytes = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        bitmap.recycle()
        return WatchFaceImage(bytes.toByteArray(), "image/png")
    }
}
