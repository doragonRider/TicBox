package dev.ticbox.companion.ui.toolbox

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Process
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.currentLocale
import dev.ticbox.companion.ui.common.formatDecimal
import dev.ticbox.companion.ui.common.formatNumber
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * One background-priority thread for the mirror's statistics and their graphs, apart from the UI
 * thread, the decoder's and VSync's threads and the session's control work.
 */
internal val ScreenStatsDispatcher: CoroutineDispatcher by lazy {
    Executors.newSingleThreadExecutor { task ->
            Thread(
                    {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                        task.run()
                    },
                    "ScreenStats",
                )
                .apply { isDaemon = true }
        }
        .asCoroutineDispatcher()
}

/** One tick of the overlay. Null wasn't measured; latencies are in milliseconds. */
@Immutable
data class ScreenStatsSample(
    val fps: Float?,
    val low1Fps: Float?,
    val roundTripMillis: Float?,
    val encoderMillis: Float?,
    val decoderMillis: Float?,
)

/** The overlay's last [LENGTH] ticks, oldest first. */
@Immutable
data class ScreenStatsHistory(
    val targetFps: Int,
    val samples: List<ScreenStatsSample> = emptyList(),
) {
    operator fun plus(sample: ScreenStatsSample) =
        copy(samples = (samples + sample).takeLast(LENGTH))

    companion object {
        const val LENGTH = 60
    }
}

/**
 * Four small graphs in the corners of the picture, over it rather than beside it, so turning them
 * on takes no room from it: frame rate, 1% low, network latency and each end's processing time.
 * They're drawn on [ScreenStatsDispatcher]; the UI thread only puts the finished images up.
 */
@Composable
internal fun ScreenStatsOverlay(history: ScreenStatsHistory?, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val labels =
        StatsLabels(
            stringResource(R.string.screen_stats_frame_rate),
            stringResource(R.string.screen_stats_low),
            stringResource(R.string.screen_stats_network),
            stringResource(R.string.screen_stats_encoder),
            stringResource(R.string.screen_stats_decoder),
            stringResource(R.string.screen_stats_millis),
            currentLocale(),
        )
    // Keeps the last images while they fade out.
    val panels by
        produceState<StatsPanels?>(null, history, density, labels) {
            val shown = history ?: return@produceState
            value =
                withContext(ScreenStatsDispatcher) {
                    StatsPainter(labels, density.density, density.fontScale).paint(shown)
                }
        }
    AnimatedVisibility(
        history != null && panels != null,
        modifier,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        panels?.let { StatsCorners(it) }
    }
}

@Composable
private fun StatsCorners(panels: StatsPanels) {
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)) {
        PanelImage(panels.fps, Modifier.align(Alignment.TopStart))
        PanelImage(panels.low, Modifier.align(Alignment.TopEnd))
        PanelImage(panels.network, Modifier.align(Alignment.BottomStart))
        PanelImage(panels.processing, Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
private fun PanelImage(panel: StatsPanel, modifier: Modifier) {
    val density = LocalDensity.current
    val size = with(density) { Modifier.size(panel.image.width.toDp(), panel.image.height.toDp()) }
    Image(panel.image, panel.description, modifier.then(size))
}

@Immutable
private data class StatsLabels(
    val fps: String,
    val low: String,
    val network: String,
    val encoder: String,
    val decoder: String,
    val millis: String,
    val locale: Locale,
)

@Immutable private class StatsPanel(val image: ImageBitmap, val description: String)

@Immutable
private class StatsPanels(
    val fps: StatsPanel,
    val low: StatsPanel,
    val network: StatsPanel,
    val processing: StatsPanel,
)

// Fixed rather than themed, as they sit on the picture. Checked apart on a dark surface,
// colour-blind views included.
private val FpsLine = Color(0xFF199E70)
private val LowLine = Color(0xFFC98500)
private val NetworkLine = Color(0xFF9085E9)
private val EncoderLine = Color(0xFFD95926)
private val DecoderLine = Color(0xFF3987E5)
private val PanelColor = Color.Black.copy(alpha = 0.6f)
private val TitleColor = Color.White.copy(alpha = 0.72f)
private val BaselineColor = Color.White.copy(alpha = 0.24f)

private const val NONE = "–"

/** Draws with the platform's canvas into fresh bitmaps, so it never touches what's on screen. */
private class StatsPainter(
    private val labels: StatsLabels,
    private val density: Float,
    fontScale: Float,
) {
    private val text = TEXT_SP * density * fontScale.coerceAtMost(MAX_FONT_SCALE)
    private val title =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = text
            color = TitleColor.toArgb()
            textLocale = labels.locale
        }
    private val value =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = text
            color = Color.White.toArgb()
            typeface = Typeface.DEFAULT_BOLD
            fontFeatureSettings = "tnum"
            textLocale = labels.locale
        }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    private val metrics = title.fontMetrics
    private val headerHeight = ceil(metrics.descent - metrics.ascent)
    private val padX = 6 * density
    private val padY = 4 * density
    private val plotTop = padY + headerHeight + 2 * density
    private val height = ceil(plotTop + PLOT_DP * density + padY).toInt()

    fun paint(history: ScreenStatsHistory): StatsPanels {
        val samples = history.samples
        val latest = samples.lastOrNull()
        val rates =
            maxOf(
                history.targetFps.toFloat(),
                samples.peak { it.fps },
                samples.peak { it.low1Fps },
            )
        val network =
            latest?.roundTripMillis?.let { String.format(labels.locale, labels.millis, millis(it)) }
        val encoder = latest?.encoderMillis?.let(::millis) ?: NONE
        val decoder = latest?.decoderMillis?.let(::millis) ?: NONE
        val work =
            latencyCeiling(
                maxOf(samples.peak { it.encoderMillis }, samples.peak { it.decoderMillis })
            )
        val ping = latencyCeiling(samples.peak { it.roundTripMillis })
        return StatsPanels(
            single(labels.fps, rate(latest?.fps), samples, rates, FpsLine) { it.fps },
            single(labels.low, rate(latest?.low1Fps), samples, rates, LowLine) { it.low1Fps },
            single(labels.network, network ?: NONE, samples, ping, NetworkLine) {
                it.roundTripMillis
            },
            processing(encoder, decoder, samples, work),
        )
    }

    /** A title on the left and its value on the right. */
    private fun single(
        name: String,
        shown: String,
        samples: List<ScreenStatsSample>,
        ceiling: Float,
        color: Color,
        line: (ScreenStatsSample) -> Float?,
    ): StatsPanel {
        val width = width(title.measureText(name) + 6 * density + value.measureText(shown))
        return panel(width, "$name $shown", samples, listOf(color to line), ceiling) { canvas ->
            val baseline = padY - metrics.ascent
            canvas.drawText(name, padX, baseline, title)
            canvas.drawText(shown, width - padX - value.measureText(shown), baseline, value)
        }
    }

    /** Each end's colour beside its name, which says whose line is whose. */
    private fun processing(
        encoder: String,
        decoder: String,
        samples: List<ScreenStatsSample>,
        ceiling: Float,
    ): StatsPanel {
        fun legend(name: String, shown: String) =
            SWATCH_DP * density +
                2 * density +
                title.measureText(name) +
                3 * density +
                value.measureText(shown)
        val width =
            width(legend(labels.encoder, encoder) + 6 * density + legend(labels.decoder, decoder))
        val lines =
            listOf<Pair<Color, (ScreenStatsSample) -> Float?>>(
                EncoderLine to { it.encoderMillis },
                DecoderLine to { it.decoderMillis },
            )
        val description = "${labels.encoder} $encoder ${labels.decoder} $decoder"
        return panel(width, description, samples, lines, ceiling) { canvas ->
            val baseline = padY - metrics.ascent
            val middle = padY + headerHeight / 2
            fun legend(x: Float, color: Color, name: String, shown: String) {
                fill.color = color.toArgb()
                val swatch = SWATCH_DP * density
                canvas.drawRoundRect(
                    x,
                    middle - density,
                    x + swatch,
                    middle + density,
                    density,
                    density,
                    fill,
                )
                val nameX = x + swatch + 2 * density
                canvas.drawText(name, nameX, baseline, title)
                canvas.drawText(
                    shown,
                    nameX + title.measureText(name) + 3 * density,
                    baseline,
                    value,
                )
            }
            legend(padX, EncoderLine, labels.encoder, encoder)
            legend(
                width - padX - legend(labels.decoder, decoder),
                DecoderLine,
                labels.decoder,
                decoder,
            )
        }
    }

    private fun width(content: Float) = maxOf(PANEL_DP * density, content + 2 * padX)

    private fun panel(
        width: Float,
        description: String,
        samples: List<ScreenStatsSample>,
        lines: List<Pair<Color, (ScreenStatsSample) -> Float?>>,
        ceiling: Float,
        header: (Canvas) -> Unit,
    ): StatsPanel {
        val bitmap = Bitmap.createBitmap(ceil(width).toInt(), height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = 8 * density
        fill.color = PanelColor.toArgb()
        canvas.drawRoundRect(0f, 0f, bitmap.width.toFloat(), height.toFloat(), radius, radius, fill)
        header(canvas)
        plot(canvas, padX, bitmap.width - padX, samples, lines, ceiling)
        bitmap.prepareToDraw()
        return StatsPanel(bitmap.asImageBitmap(), description)
    }

    /** Newest at the right edge; a missing tick breaks the line, and a lone one is a dot. */
    private fun plot(
        canvas: Canvas,
        left: Float,
        right: Float,
        samples: List<ScreenStatsSample>,
        lines: List<Pair<Color, (ScreenStatsSample) -> Float?>>,
        ceiling: Float,
    ) {
        val half = stroke.strokeWidth / 2
        val bottom = plotTop + PLOT_DP * density - half
        val top = plotTop + half
        val step = (right - left) / (ScreenStatsHistory.LENGTH - 1)
        fill.color = BaselineColor.toArgb()
        canvas.drawRect(left, bottom - density / 2, right, bottom + density / 2, fill)
        for ((color, line) in lines) {
            stroke.color = color.toArgb()
            fill.color = color.toArgb()
            val path = Path()
            var run = 0
            samples.forEachIndexed { index, sample ->
                val measured = line(sample)
                if (measured == null) {
                    run = 0
                    return@forEachIndexed
                }
                val x = right - (samples.size - 1 - index) * step
                val y = bottom - (measured / ceiling).coerceIn(0f, 1f) * (bottom - top)
                if (run == 0) path.moveTo(x, y) else path.lineTo(x, y)
                if (run == 0 && samples.getOrNull(index + 1)?.let(line) == null)
                    canvas.drawCircle(x, y, half * 1.5f, fill)
                run++
            }
            canvas.drawPath(path, stroke)
        }
    }

    private fun rate(fps: Float?) =
        fps?.let { formatNumber(it.roundToInt(), labels.locale) } ?: NONE

    /** Tenths only under 10, where they still say something. */
    private fun millis(value: Float) =
        if (value < 10f) formatDecimal(value, labels.locale)
        else formatNumber(value.roundToInt(), labels.locale)

    private companion object {
        const val TEXT_SP = 11f
        const val MAX_FONT_SCALE = 1.3f
        const val PANEL_DP = 104f
        const val PLOT_DP = 24f
        const val SWATCH_DP = 6f
    }
}

private fun List<ScreenStatsSample>.peak(value: (ScreenStatsSample) -> Float?): Float =
    mapNotNull(value).maxOrNull() ?: 0f

/** A round top with some room over the highest point, never under 10 ms. */
private fun latencyCeiling(peak: Float): Float {
    val wanted = peak * 1.2f
    return generateSequence(10f) { it * 2 }.first { it >= wanted }
}
