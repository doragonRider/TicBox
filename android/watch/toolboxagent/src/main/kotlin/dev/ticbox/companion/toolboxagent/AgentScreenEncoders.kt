package dev.ticbox.companion.toolboxagent

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Display
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** What the encoder scrcpy picks for [codec] declares at the size scrcpy will ask of it. */
internal data class ScreenEncoderLimit(
    val codec: String,
    val encoder: String? = null,
    val hardware: Boolean? = null,
    val width: Int = 0,
    val height: Int = 0,
    /** Declared frame rate at [width]×[height], at most the 120 the phone can ask for. */
    val maxFps: Int? = null,
    val maxBitRate: Int? = null,
) {
    val supported: Boolean get() = encoder != null

    fun json(): JsonObject = buildJsonObject {
        put("codec", codec); put("supported", supported)
        if (encoder != null) { put("encoder", encoder); hardware?.let { put("hardware", it) }; put("width", width); put("height", height) }
        maxFps?.let { put("maxFps", it) }; maxBitRate?.let { put("maxBitRate", it) }
    }
}

/**
 * MediaTek's HEVC encoders let their rate control starve for seconds whatever the target, which
 * looks blurry even on a still screen; a QP ceiling stops that, and variable rate keeps to it.
 * Holding the ceiling costs bits in fast motion, so it follows what each pixel of each frame gets:
 * 35 at 40 Mbps for 1072×2400 at 120 fps, and 6 more for each halving (which about halves the
 * bits), between 30 and 45. Null for every other encoder, which keeps its own rate control.
 */
internal fun screenEncoderDefaults(encoder: ScreenEncoderLimit, bitRate: Int, fps: Int): String? {
    val name = encoder.encoder ?: return null
    val mediaTek = name.startsWith("c2.mtk.", ignoreCase = true) || name.startsWith("OMX.MTK.", ignoreCase = true)
    if (encoder.codec != "h265" || !mediaTek || encoder.width <= 0 || encoder.height <= 0 || bitRate <= 0 || fps <= 0) return null
    val bitsPerPixel = bitRate.toDouble() / (encoder.width.toDouble() * encoder.height * fps)
    val qp = (35 + 6 * log2(REFERENCE_BITS_PER_PIXEL / bitsPerPixel)).roundToInt().coerceIn(30, 45)
    return "video-qp-max=$qp,bitrate-mode=1"
}

private const val REFERENCE_BITS_PER_PIXEL = 40_000_000.0 / (1072.0 * 2400 * 120)

/**
 * scrcpy only scales the display to the requested long side and rounds each side down to the
 * encoder's alignment; the encoder's own size limits come into play only after encoding fails.
 */
internal fun scrcpyVideoSize(width: Int, height: Int, maxSize: Int, alignment: Int): Pair<Int, Int> {
    require(width > 0 && height > 0 && alignment > 0)
    var w = width; var h = height
    if (maxSize > 0 && (width > maxSize || height > maxSize)) {
        if (width > height) { w = maxSize; h = height * maxSize / width } else { w = width * maxSize / height; h = maxSize }
    }
    return maxOf(w / alignment * alignment, alignment) to maxOf(h / alignment * alignment, alignment)
}

internal object AgentScreenEncoders {
    val CODECS = listOf("h264", "h265")
    private fun mime(codec: String) = if (codec == "h265") MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

    /** The default display as scrcpy captures it, rotation included. Null if it can't be read. */
    fun displaySize(context: Context): Pair<Int, Int>? = runCatching {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val size = Point()
        @Suppress("DEPRECATION") display.getRealSize(size)
        (size.x to size.y).takeIf { it.first > 0 && it.second > 0 }
    }.getOrNull()

    /** The same platform default scrcpy creates; it's released straight away. */
    fun limit(codec: String, display: Pair<Int, Int>, maxSize: Int): ScreenEncoderLimit {
        val mime = mime(codec)
        val encoder = try { MediaCodec.createEncoderByType(mime) } catch (_: Exception) { return ScreenEncoderLimit(codec) }
        try {
            val video = encoder.codecInfo.getCapabilitiesForType(mime).videoCapabilities ?: return ScreenEncoderLimit(codec)
            val alignment = maxOf(video.widthAlignment, video.heightAlignment, 1)
            val (width, height) = scrcpyVideoSize(display.first, display.second, maxSize, alignment)
            val fastest = try { video.getSupportedFrameRatesFor(width, height).upper } catch (_: IllegalArgumentException) { video.supportedFrameRates.upper.toDouble() }
            return ScreenEncoderLimit(codec, encoder.name, encoder.codecInfo.isHardwareAccelerated, width, height,
                maxFps = floor(fastest).toInt().coerceIn(1, 120),
                maxBitRate = video.bitrateRange.upper.coerceIn(ScreenRequest.MIN_BIT_RATE, ScreenRequest.MAX_BIT_RATE))
        } catch (_: RuntimeException) {
            return ScreenEncoderLimit(codec)
        } finally { runCatching { encoder.release() } }
    }

    /** Null when the display can't be read: nothing is limited then. */
    fun lookup(environment: AgentEnvironment, maxSize: Int): ((String) -> ScreenEncoderLimit)? {
        val display = runCatching { environment.context }.getOrNull()?.let(::displaySize) ?: return null
        val known = mutableMapOf<String, ScreenEncoderLimit>()
        return { codec -> known.getOrPut(codec) { limit(codec, display, maxSize) } }
    }

    /** The advanced page's "screen_encoders" request: both codecs at one requested size. */
    fun describe(environment: AgentEnvironment, maxSize: Int): JsonObject {
        val lookup = lookup(environment, maxSize)
        return buildJsonObject {
            putJsonArray("codecs") { if (lookup != null) CODECS.forEach { add(lookup(it).json()) } }
        }
    }
}
