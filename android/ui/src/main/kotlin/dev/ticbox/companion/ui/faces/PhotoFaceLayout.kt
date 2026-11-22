package dev.ticbox.companion.ui.faces

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import dev.ticbox.companion.api.WatchFacePhotoSettings
import dev.ticbox.companion.ui.R
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Where the photo face puts the time. [wire] is the protocol's `time_place`; a watch that has none
 * saved uses the top. Listed in screen order, which is not the order of the values.
 */
enum class TimePlace(val wire: Int, @param:StringRes val title: Int) {
    TOP(0, R.string.faces_photo_time_top),
    MIDDLE(2, R.string.faces_photo_time_middle),
    BOTTOM(1, R.string.faces_photo_time_bottom);

    companion object {
        fun of(wire: Int?): TimePlace = entries.firstOrNull { it.wire == wire } ?: TOP
    }
}

/** The photo face's own settings: the time's place and whether battery and date show. */
data class PhotoLayout(
    val time: TimePlace = TimePlace.TOP,
    val battery: Boolean = false,
    val date: Boolean = false,
) {
    fun applyTo(settings: WatchFacePhotoSettings) =
        settings.copy(
            timePlace = time.wire,
            showBattery = if (battery) 1 else 0,
            showDateAndWeek = if (date) 1 else 0,
        )

    companion object {
        /** The watch shows battery and date only for 1. */
        fun of(settings: WatchFacePhotoSettings) =
            PhotoLayout(
                TimePlace.of(settings.timePlace),
                settings.showBattery == 1,
                settings.showDateAndWeek == 1,
            )
    }
}

/**
 * The time, date and battery over the photo, placed as the stock companion app's preview places
 * them. Positions are fractions of the screen's width, measured to text baselines; the battery
 * icon's position is its center.
 */
@Composable
fun PhotoFaceOverlay(layout: PhotoLayout, battery: Int?, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val texts =
        remember(locale) {
            val now = LocalDateTime.now()
            val time = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
            val date = DateFormat.getBestDateTimePattern(locale, "Md")
            Triple(
                now.format(DateTimeFormatter.ofPattern(time, locale)),
                now.format(DateTimeFormatter.ofPattern(date, locale)),
                now.format(DateTimeFormatter.ofPattern("EEE", locale)),
            )
        }
    val (time, date, week) = texts
    val lines =
        when (layout.time) {
            TimePlace.TOP -> Lines(date = 0.17763157f, week = 0.17324561f, time = 0.42543858f)
            TimePlace.BOTTOM -> Lines(date = 0.5767544f, week = 0.57236844f, time = 0.82894737f)
            TimePlace.MIDDLE -> Lines(date = 0.37719297f, week = 0.37280703f, time = 0.62938595f)
        }
    Canvas(modifier) {
        val w = size.width
        // Plain white sans-serif in the regular weight, left-aligned on its baseline.
        fun text(value: String, x: Float, baseline: Float, height: Float) {
            val style =
                TextStyle(
                    color = Color.White,
                    fontSize = (height * w).toSp(),
                    fontWeight = FontWeight.Normal,
                )
            val result = measurer.measure(value, style)
            drawText(result, topLeft = Offset(x * w, baseline * w - result.firstBaseline))
        }
        val circle = Path().apply { addOval(Rect(Offset.Zero, size)) }
        clipPath(circle) {
            text(time, 0.18311404f, lines.time, 0.25877193f)
            // The date and the weekday sit above the time and move with it.
            if (layout.date) {
                text(date, 0.3002193f, lines.date, 0.07236842f)
                text(week, 0.51578945f, lines.week, 0.06140351f)
            }
            // The battery stays near the bottom wherever the time is.
            if (layout.battery) {
                text(battery?.let { "$it%" } ?: "--%", 0.49122807f, 0.91885966f, 0.06140351f)
                batteryIcon(Offset(0.42105263f * w, 0.89912283f * w), w / 456f)
            }
        }
    }
}

/**
 * A battery outline with three bars, 37 × 22 units at [unit] pixels each, centered on [center]: the
 * size of the watch's own icon at 456 units to the screen.
 */
private fun DrawScope.batteryIcon(center: Offset, unit: Float) {
    val origin = center - Offset(37 / 2f * unit, 22 / 2f * unit)
    fun at(x: Float, y: Float) = origin + Offset(x * unit, y * unit)
    drawRoundRect(
        Color.White,
        at(1.5f, 1.5f),
        Size(29 * unit, 19 * unit),
        CornerRadius(1.5f * unit),
        style = Stroke(3 * unit),
    )
    for (bar in 0 until 3) drawRect(Color.White, at(5f + 8 * bar, 5f), Size(6 * unit, 12 * unit))
    drawRoundRect(Color.White, at(34f, 5f), Size(3 * unit, 12 * unit), CornerRadius(1.5f * unit))
}

private class Lines(val date: Float, val week: Float, val time: Float)
