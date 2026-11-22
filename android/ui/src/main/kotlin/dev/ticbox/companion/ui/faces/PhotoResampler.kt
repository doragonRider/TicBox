package dev.ticbox.companion.ui.faces

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Rows of a picture, read one at a time so a large photo is never copied whole. */
interface PixelRows {
    val width: Int
    val height: Int

    /** Writes row [y] as ARGB into [into], which holds [width] pixels. */
    fun read(y: Int, into: IntArray)
}

/** How the selected area is scaled to the watch screen. */
enum class ResampleFilter(val support: Double) {
    BILINEAR(1.0),
    BICUBIC(2.0),
    LANCZOS3(3.0);

    fun weight(x: Double): Double {
        val t = abs(x)
        return when (this) {
            BILINEAR -> max(0.0, 1 - t)
            // Keys' cubic with a = -0.5 (Catmull-Rom), the usual "bicubic".
            BICUBIC ->
                when {
                    t < 1 -> (1.5 * t - 2.5) * t * t + 1
                    t < 2 -> ((-0.5 * t + 2.5) * t - 4) * t + 2
                    else -> 0.0
                }
            LANCZOS3 ->
                when {
                    t < 1e-9 -> 1.0
                    t < 3 -> 3 * sin(PI * t) * sin(PI * t / 3) / (PI * PI * t * t)
                    else -> 0.0
                }
        }
    }
}

/** Unsharp masking after scaling: how much of the difference from a slight blur is added back. */
enum class Sharpening(val amount: Double) {
    OFF(0.0),
    LIGHT(0.4),
    MEDIUM(0.8),
    STRONG(1.3),
}

/**
 * A square of a photo in its own pixels. It may reach past the photo's edges; what lies outside
 * comes out black, as the watch shows around a photo.
 */
data class PhotoCrop(val centerX: Double, val centerY: Double, val side: Double) {
    val left: Double
        get() = centerX - side / 2

    val top: Double
        get() = centerY - side / 2

    /**
     * Kept to sensible bounds for a [width] × [height] photo scaled to [output]: at most the whole
     * photo inside the square, at least a quarter of [output] (four times enlarged). Along each
     * side the square stays inside the photo, or the photo inside the square when it is smaller.
     */
    fun within(width: Int, height: Int, output: Int): PhotoCrop {
        val largest = max(width, height).toDouble()
        val side = side.coerceIn(min(output / MAX_ENLARGEMENT, largest), largest)
        fun axis(center: Double, length: Int): Double {
            val a = side / 2
            val b = length - side / 2
            return center.coerceIn(min(a, b), max(a, b))
        }
        return PhotoCrop(axis(centerX, width), axis(centerY, height), side)
    }

    /**
     * After a gesture on a view [view] pixels wide: the photo moved by [panX], [panY] view pixels
     * and zoomed by [zoom] around [focusX], [focusY], where the fingers are now.
     */
    fun moved(
        view: Float,
        focusX: Float,
        focusY: Float,
        panX: Float,
        panY: Float,
        zoom: Float,
    ): PhotoCrop {
        val before = side / view
        val side = side / zoom
        val after = side / view
        // The point that was under the fingers stays under them.
        val left = left + (focusX - panX) * before - focusX * after
        val top = top + (focusY - panY) * before - focusY * after
        return PhotoCrop(left + side / 2, top + side / 2, side)
    }

    companion object {
        /** Enlarging more than this only makes a blur, so zooming in stops there. */
        const val MAX_ENLARGEMENT = 4.0

        /** The largest square the photo fills, in its middle. */
        fun fill(width: Int, height: Int) =
            PhotoCrop(width / 2.0, height / 2.0, min(width, height).toDouble())
    }
}

/**
 * Renders [crop] of [source] as an opaque [size] × [size] ARGB picture with [filter], then sharpens
 * it. Transparent parts of the source become black. [check] runs between rows, so a caller can stop
 * work that is no longer wanted by throwing from it.
 */
fun renderCrop(
    source: PixelRows,
    crop: PhotoCrop,
    size: Int,
    filter: ResampleFilter,
    sharpening: Sharpening,
    check: () -> Unit = {},
): IntArray {
    require(size > 0 && crop.side > 0 && source.width > 0 && source.height > 0)
    val scale = crop.side / size
    val columns = Taps(crop.left, scale, size, source.width, filter)
    val rows = Taps(crop.top, scale, size, source.height, filter)
    val first = rows.lowest
    val stride = size * 3
    // Each source row the output needs, already scaled across.
    val across = FloatArray((rows.highest - first + 1) * stride)
    val line = IntArray(source.width)
    val red = FloatArray(source.width)
    val green = FloatArray(source.width)
    val blue = FloatArray(source.width)
    for (y in first..rows.highest) {
        check()
        source.read(y, line)
        for (x in line.indices) {
            val pixel = line[x]
            val alpha = (pixel ushr 24) / 255f
            red[x] = (pixel shr 16 and 0xFF) * alpha
            green[x] = (pixel shr 8 and 0xFF) * alpha
            blue[x] = (pixel and 0xFF) * alpha
        }
        var at = (y - first) * stride
        for (x in 0 until size) {
            var r = 0f
            var g = 0f
            var b = 0f
            for (tap in columns.start[x] until columns.start[x + 1]) {
                val index = columns.index[tap]
                val weight = columns.weight[tap]
                r += red[index] * weight
                g += green[index] * weight
                b += blue[index] * weight
            }
            across[at++] = r
            across[at++] = g
            across[at++] = b
        }
    }
    val out = FloatArray(size * stride)
    for (y in 0 until size) {
        check()
        for (tap in rows.start[y] until rows.start[y + 1]) {
            val from = (rows.index[tap] - first) * stride
            val weight = rows.weight[tap]
            val to = y * stride
            for (i in 0 until stride) out[to + i] += across[from + i] * weight
        }
        for (x in 0 until size) {
            val cover = rows.coverage[y] * columns.coverage[x]
            if (cover < 1f) for (c in 0 until 3) out[y * stride + x * 3 + c] *= cover
        }
    }
    if (sharpening != Sharpening.OFF) sharpen(out, size, sharpening.amount.toFloat(), check)
    return IntArray(size * size) { i ->
        fun channel(c: Int) = (out[i * 3 + c] + 0.5f).toInt().coerceIn(0, 255)
        (0xFF shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
    }
}

/**
 * Which source pixels make up each output pixel along one axis, and with what weights. Shrinking
 * stretches the filter over as many source pixels as one output pixel covers, so nothing is
 * skipped; taps past the photo's edge repeat the edge pixel, and [coverage] says how much of each
 * output pixel the photo covers at all.
 */
private class Taps(
    start: Double,
    scale: Double,
    size: Int,
    length: Int,
    filter: ResampleFilter,
) {
    val start = IntArray(size + 1)
    var index = IntArray(size * 8)
    var weight = FloatArray(size * 8)
    val coverage = FloatArray(size)
    var lowest = Int.MAX_VALUE
    var highest = Int.MIN_VALUE

    init {
        val stretch = max(scale, 1.0)
        val reach = filter.support * stretch
        val weights = DoubleArray(2 * ceil(reach).toInt() + 3)
        var count = 0
        for (i in 0 until size) {
            this.start[i] = count
            val center = start + (i + 0.5) * scale
            val from = floor(center - reach).toInt()
            val to = ceil(center + reach).toInt()
            var total = 0.0
            for (j in from..to) {
                val w = filter.weight((j + 0.5 - center) / stretch)
                weights[j - from] = w
                total += w
            }
            for (j in from..to) {
                val w = weights[j - from]
                if (w == 0.0) continue
                if (count == index.size) {
                    index = index.copyOf(count * 2)
                    weight = weight.copyOf(count * 2)
                }
                val pixel = j.coerceIn(0, length - 1)
                index[count] = pixel
                weight[count] = (w / total).toFloat()
                lowest = min(lowest, pixel)
                highest = max(highest, pixel)
                count++
            }
            val left = start + i * scale
            val inside = min(left + scale, length.toDouble()) - max(left, 0.0)
            coverage[i] = (inside / scale).coerceIn(0.0, 1.0).toFloat()
        }
        this.start[size] = count
    }
}

/** Unsharp masking: adds [amount] times the difference from a Gaussian blur of radius 0.8. */
private fun sharpen(pixels: FloatArray, size: Int, amount: Float, check: () -> Unit) {
    val sigma = 0.8
    val radius = ceil(3 * sigma).toInt()
    val kernel =
        FloatArray(2 * radius + 1) {
            exp(-((it - radius) * (it - radius)) / (2 * sigma * sigma)).toFloat()
        }
    val sum = kernel.sum()
    for (i in kernel.indices) kernel[i] /= sum
    val stride = size * 3
    fun blur(from: FloatArray, to: FloatArray, alongRows: Boolean) {
        for (y in 0 until size) {
            check()
            for (x in 0 until size) {
                for (c in 0 until 3) {
                    var value = 0f
                    for (k in kernel.indices) {
                        val offset = k - radius
                        val sx = if (alongRows) (x + offset).coerceIn(0, size - 1) else x
                        val sy = if (alongRows) y else (y + offset).coerceIn(0, size - 1)
                        value += from[sy * stride + sx * 3 + c] * kernel[k]
                    }
                    to[y * stride + x * 3 + c] = value
                }
            }
        }
    }
    val half = FloatArray(pixels.size)
    val blurred = FloatArray(pixels.size)
    blur(pixels, half, alongRows = true)
    blur(half, blurred, alongRows = false)
    for (i in pixels.indices) pixels[i] += amount * (pixels[i] - blurred[i])
}

/** Whether a selected area of [side] pixels is shrunk, enlarged or kept for an [output] screen. */
enum class Scaling {
    DOWN,
    EXACT,
    UP;

    companion object {
        fun of(side: Double, output: Int): Scaling {
            val pixels = side.roundToInt()
            return when {
                pixels > output -> DOWN
                pixels < output -> UP
                else -> EXACT
            }
        }
    }
}
