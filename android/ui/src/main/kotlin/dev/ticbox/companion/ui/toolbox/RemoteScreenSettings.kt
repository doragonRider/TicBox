package dev.ticbox.companion.ui.toolbox

import androidx.annotation.StringRes
import dev.ticbox.companion.api.ToolboxBackendKind
import dev.ticbox.companion.api.ToolboxScreenCodecOptions
import dev.ticbox.companion.api.ToolboxScreenEncoder
import dev.ticbox.companion.api.ToolboxScreenOptions
import dev.ticbox.companion.api.ToolboxVideoCodec
import dev.ticbox.companion.ui.R
import java.math.BigDecimal
import kotlin.math.roundToInt

enum class ScreenQuality(@param:StringRes val label: Int, val options: ToolboxScreenOptions) {
    LIGHT(
        R.string.screen_quality_light,
        ToolboxScreenOptions(maxSize = 480, maxFps = 10, bitRate = 300_000),
    ),
    BALANCED(R.string.screen_quality_balanced, ToolboxScreenOptions()),
    SHARP(
        R.string.screen_quality_sharp,
        ToolboxScreenOptions(maxSize = 1_280, maxFps = 30, bitRate = 4_000_000),
    ),
}

/** Bluetooth carries far less than wireless debugging, so root starts on the lightest stream. */
internal fun defaultScreenQuality(backend: ToolboxBackendKind?): ScreenQuality =
    if (backend == ToolboxBackendKind.ROOT_BRIDGE) ScreenQuality.LIGHT else ScreenQuality.BALANCED

internal val SCREEN_SIZES = 64..2_560
internal val SCREEN_FPS = 1..120
internal val SCREEN_BIT_RATES = 100_000..1_000_000_000

/** How the mirror should run. The Advanced values stay while a preset is in use. */
data class RemoteScreenSettings(
    /** Null until the user picks one, which leaves it to the backend. */
    val preset: ScreenQuality? = null,
    val advanced: Boolean = false,
    val audio: Boolean = false,
    /** Pixels on the long side. */
    val maxSize: Int = 450,
    val maxFps: Int = 60,
    val bitRate: Int = 8_000_000,
    val h265: Boolean = false,
    /** Asks the device to keep frame rate and bit rate within what its encoder declares. */
    val limitToEncoder: Boolean = true,
    /** Experimental MediaCodec options for Advanced only; see [ToolboxScreenCodecOptions]. */
    val codecOptions: String = "",
    val frameStats: Boolean = false,
    /** Shows pictures that arrive together one after another, at up to a few frames more delay. */
    val smoothPlayback: Boolean = false,
    /** Direct authenticated video/audio datagrams over the wireless ADB network. */
    val udp: Boolean = false,
    val udpEncryption: Boolean = true,
) {
    fun options(backend: ToolboxBackendKind?): ToolboxScreenOptions =
        if (advanced) {
            ToolboxScreenOptions(
                maxSize = maxSize.coerceIn(SCREEN_SIZES),
                maxFps = maxFps.coerceIn(SCREEN_FPS),
                bitRate = bitRate.coerceIn(SCREEN_BIT_RATES),
                codec = if (h265) ToolboxVideoCodec.H265 else ToolboxVideoCodec.H264,
                audio = audio,
                udp = udp && backend == ToolboxBackendKind.ADB,
                udpEncryption = !udp || backend != ToolboxBackendKind.ADB || udpEncryption,
                collectDiagnostics = frameStats,
                limitToEncoder = limitToEncoder,
                videoCodecOptions = ToolboxScreenCodecOptions.normalize(codecOptions).orEmpty(),
            )
        } else {
            (preset ?: defaultScreenQuality(backend))
                .options
                .copy(collectDiagnostics = frameStats, limitToEncoder = limitToEncoder)
        }
}

/**
 * What the settings come to on a device whose encoders declared what was read, the way the device
 * limits them itself: frame rate and bit rate no higher than the chosen encoder allows, and H.264
 * instead of an H.265 it has no encoder for. Without a declaration nothing is known to change.
 */
internal data class ScreenEncoderFit(
    /** False only when the device said it has no H.265 encoder. */
    val h265Available: Boolean = true,
    /** The encoder the stream would use, if it declared its limits. */
    val encoder: ToolboxScreenEncoder? = null,
) {
    val maxFps: Int?
        get() = encoder?.maxFps

    val maxBitRate: Int?
        get() = encoder?.maxBitRate

    fun fps(requested: Int): Int = maxFps?.let { minOf(requested, it) } ?: requested

    fun bitRate(requested: Int): Int = maxBitRate?.let { minOf(requested, it) } ?: requested
}

internal fun RemoteScreenSettings.encoderFit(
    declared: List<ToolboxScreenEncoder>?
): ScreenEncoderFit {
    if (!limitToEncoder || declared == null) return ScreenEncoderFit()
    fun of(codec: ToolboxVideoCodec) = declared.firstOrNull { it.codec == codec }
    val h265Available = of(ToolboxVideoCodec.H265)?.supported != false
    val codec = if (h265 && h265Available) ToolboxVideoCodec.H265 else ToolboxVideoCodec.H264
    return ScreenEncoderFit(h265Available, of(codec)?.takeIf { it.supported })
}

internal fun parseWithin(text: String, range: IntRange): Int? =
    text.toIntOrNull()?.takeIf { it in range }

/** Fractions are allowed because Bluetooth can't carry even 1 Mbps. */
internal fun parseBitRate(mbps: String): Int? =
    mbps
        .replace(',', '.')
        .toDoubleOrNull()
        ?.let { (it * 1_000_000).roundToInt() }
        ?.takeIf { it in SCREEN_BIT_RATES }

internal fun bitRateInMbps(bitRate: Int): String =
    BigDecimal(bitRate).movePointLeft(6).stripTrailingZeros().toPlainString()
