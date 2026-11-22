package dev.ticbox.companion.ui.faces

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.ticbox.companion.api.WatchFacePhoto
import dev.ticbox.companion.api.WatchFacePhotoSettings
import dev.ticbox.companion.ui.R
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

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

/** The time, date and weekday as the face shows them now. */
data class PhotoFaceTexts(val time: String, val date: String, val week: String) {
    companion object {
        fun now(context: Context, locale: Locale): PhotoFaceTexts {
            val now = LocalDateTime.now()
            val time = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
            val date = DateFormat.getBestDateTimePattern(locale, "Md")
            return PhotoFaceTexts(
                now.format(DateTimeFormatter.ofPattern(time, locale)),
                now.format(DateTimeFormatter.ofPattern(date, locale)),
                now.format(DateTimeFormatter.ofPattern("EEE", locale)),
            )
        }
    }
}

/** [drawPhotoFaceOverlay] over the photo, inside a round screen. */
@Composable
fun PhotoFaceOverlay(layout: PhotoLayout, battery: Int?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val texts = remember(locale) { PhotoFaceTexts.now(context, locale) }
    Canvas(modifier) {
        drawIntoCanvas { drawPhotoFaceOverlay(it.nativeCanvas, size.width, layout, texts, battery) }
    }
}

/**
 * The time, date and battery placed as the stock companion app's preview places them, on a round
 * screen [width] pixels across. Positions are fractions of the width, measured to text baselines;
 * the battery icon's position is its center. The text is plain white sans-serif in the regular
 * weight, left-aligned. The date and weekday sit above the time and move with it; the battery stays
 * near the bottom.
 */
fun drawPhotoFaceOverlay(
    canvas: AndroidCanvas,
    width: Float,
    layout: PhotoLayout,
    texts: PhotoFaceTexts,
    battery: Int?,
) {
    val w = width
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    fun text(value: String, x: Float, baseline: Float, height: Float) {
        paint.textSize = height * w
        canvas.drawText(value, x * w, baseline * w, paint)
    }
    val lines =
        when (layout.time) {
            TimePlace.TOP -> Lines(date = 0.17763157f, week = 0.17324561f, time = 0.42543858f)
            TimePlace.BOTTOM -> Lines(date = 0.5767544f, week = 0.57236844f, time = 0.82894737f)
            TimePlace.MIDDLE -> Lines(date = 0.37719297f, week = 0.37280703f, time = 0.62938595f)
        }
    canvas.save()
    canvas.clipPath(Path().apply { addOval(RectF(0f, 0f, w, w), Path.Direction.CW) })
    text(texts.time, 0.18311404f, lines.time, 0.25877193f)
    if (layout.date) {
        text(texts.date, 0.3002193f, lines.date, 0.07236842f)
        text(texts.week, 0.51578945f, lines.week, 0.06140351f)
    }
    if (layout.battery) {
        text(battery?.let { "$it%" } ?: "--%", 0.49122807f, 0.91885966f, 0.06140351f)
        batteryIcon(canvas, paint, 0.42105263f * w, 0.89912283f * w, w / 456f)
    }
    canvas.restore()
}

/**
 * A battery outline with three bars, 37 × 22 units at [unit] pixels each, centered on [x], [y]: the
 * size of the watch's own icon at 456 units to the screen.
 */
private fun batteryIcon(canvas: AndroidCanvas, paint: Paint, x: Float, y: Float, unit: Float) {
    val left = x - 37 / 2f * unit
    val top = y - 22 / 2f * unit
    fun rect(l: Float, t: Float, r: Float, b: Float) =
        RectF(left + l * unit, top + t * unit, left + r * unit, top + b * unit)
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 3 * unit
    canvas.drawRoundRect(rect(1.5f, 1.5f, 30.5f, 20.5f), 1.5f * unit, 1.5f * unit, paint)
    paint.style = Paint.Style.FILL
    for (bar in 0 until 3) canvas.drawRect(rect(5f + 8 * bar, 5f, 11f + 8 * bar, 17f), paint)
    canvas.drawRoundRect(rect(34f, 5f, 37f, 17f), 1.5f * unit, 1.5f * unit, paint)
}

private class Lines(val date: Float, val week: Float, val time: Float)

/**
 * The photo face as it looks, for places that show the current face as a picture: the photo the
 * watch has, round, with the time, date and battery over it. Null when [photo] can't be read.
 */
fun photoFacePicture(
    photo: ByteArray,
    layout: PhotoLayout,
    texts: PhotoFaceTexts,
    battery: Int?,
): Bitmap? {
    val image = BitmapFactory.decodeByteArray(photo, 0, photo.size) ?: return null
    val side = PhotoFacePictureSize
    val picture = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(picture)
    val round =
        Path().apply { addOval(RectF(0f, 0f, side.toFloat(), side.toFloat()), Path.Direction.CW) }
    canvas.save()
    canvas.clipPath(round)
    canvas.drawColor(android.graphics.Color.BLACK)
    // Fills the screen and stays centered, as the watch draws it.
    val scale = maxOf(side.toFloat() / image.width, side.toFloat() / image.height)
    val width = image.width * scale
    val height = image.height * scale
    val target =
        RectF((side - width) / 2, (side - height) / 2, (side + width) / 2, (side + height) / 2)
    canvas.drawBitmap(image, null, target, Paint(Paint.FILTER_BITMAP_FLAG))
    canvas.restore()
    drawPhotoFaceOverlay(canvas, side.toFloat(), layout, texts, battery)
    return picture
}

/** Large enough for the home page's watch at any density, small enough to decode quickly. */
private const val PhotoFacePictureSize = 466

/** Draws the photo face for the pages that show the watch's current face as a picture. */
class PhotoFacePictures(private val store: PhotoFaceStore, private val context: Context) {
    /** Whether [component] is the photo face, as far as the photo face page found. Reads a file. */
    fun isPhotoFace(component: String): Boolean =
        store.component()?.let { fullComponentName(it) == fullComponentName(component) } == true

    fun draw(photo: WatchFacePhoto, battery: Int?): Bitmap? {
        val bytes = photo.image?.bytes ?: return null
        val layout = photo.settings?.let(PhotoLayout::of) ?: PhotoLayout()
        val locale = context.resources.configuration.locales[0]
        return photoFacePicture(bytes, layout, PhotoFaceTexts.now(context, locale), battery)
    }
}

@Composable
fun rememberPhotoFacePictures(): PhotoFacePictures {
    val store = rememberPhotoFaceStore()
    val context = LocalContext.current.applicationContext
    return remember(store, context) { PhotoFacePictures(store, context) }
}
