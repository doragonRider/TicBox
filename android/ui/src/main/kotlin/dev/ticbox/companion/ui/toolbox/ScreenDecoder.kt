package dev.ticbox.companion.ui.toolbox

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Surface
import dev.ticbox.companion.api.ToolboxException
import dev.ticbox.companion.api.ToolboxScreenEvent
import dev.ticbox.companion.api.ToolboxScreenInput
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel

/** Draws the stream, as [mime], onto [surface]. Queuing waits while the decoder is full. */
internal class ScreenDecoder(
    private val surface: Surface,
    width: Int,
    height: Int,
    mime: String = MediaFormat.MIMETYPE_VIDEO_AVC,
    private val codecFactory: () -> MediaCodec = { MediaCodec.createDecoderByType(mime) },
    private val threadFactory: () -> HandlerThread = {
        HandlerThread("ScreenDecoder", Process.THREAD_PRIORITY_MORE_FAVORABLE)
    },
    private val stats: ScreenFrameStats? = null,
    private val targetFps: Int = 60,
    fastestRefreshHz: Float? = null,
    private val outputClockFactory: (Handler, (Long) -> Unit, () -> Unit) -> ScreenOutputClock =
        ::AndroidScreenOutputClock,
    /** See [ScreenOutputPacer]: pictures that arrive together wait instead of being dropped. */
    private val smoothPlayback: Boolean = false,
) : AutoCloseable {
    private val freeInputs = Channel<Int>(Channel.UNLIMITED)
    private val readyForInput = CompletableDeferred<Unit>()
    private val thread = threadFactory().apply { start() }
    private var ownedCodec: MediaCodec? = null
    private val codec: MediaCodec
        get() = checkNotNull(ownedCodec)

    private val closed = AtomicBoolean()
    private val h264 =
        if (mime == MediaFormat.MIMETYPE_VIDEO_AVC) H264DecoderInput { stats?.h264LowDelay(false) }
        else null
    private val refreshVote = screenRefreshVote(targetFps, fastestRefreshHz)
    private var outputPacer: ScreenOutputPacer? = null

    init {
        try {
            require(targetFps in 1..120)
            ownedCodec = codecFactory()
            runCatching { stats?.decoder(codec.name, codec.codecInfo.isHardwareAccelerated) }
            if (h264 != null) stats?.h264LowDelay(true)
            val handler = Handler(thread.looper)
            val renderingCodec = codec
            val clock =
                outputClockFactory(
                    handler,
                    { outputPacer?.onVsync(it) },
                    { outputPacer?.onTimeout() },
                )
            outputPacer =
                ScreenOutputPacer(
                    clock,
                    targetFps = targetFps,
                    smooth = smoothPlayback,
                    release = { output, atNanos ->
                        if (atNanos == null) {
                            stats?.discardedOutput(output.ptsMicros)
                            renderingCodec.releaseOutputBuffer(output.index, false)
                        } else {
                            stats?.scheduledOutput(output.ptsMicros, System.nanoTime())
                            // Remote PTS is on another device's clock. Surface scheduling uses this
                            // display's local vsync (or the bounded no-vsync watchdog's local
                            // time).
                            renderingCodec.releaseOutputBuffer(output.index, atNanos)
                        }
                    },
                    onFailure = { error ->
                        readyForInput.completeExceptionally(
                            ToolboxException("screen_decoder_failed", error)
                        )
                        freeInputs.close(ToolboxException("screen_decoder_failed", error))
                    },
                    observe =
                        stats?.let { enabled ->
                            { event, nanos ->
                                enabled.outputObservation(event, nanos, System.nanoTime())
                            }
                        },
                )
            codec.setCallback(
                object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                        if (!closed.get() && freeInputs.trySend(index).isSuccess)
                            readyForInput.complete(Unit)
                    }

                    override fun onOutputBufferAvailable(
                        codec: MediaCodec,
                        index: Int,
                        info: MediaCodec.BufferInfo,
                    ) {
                        // Decoder release owns any callbacks that arrive after close.
                        if (closed.get()) return
                        stats?.decoded(info.presentationTimeUs, System.nanoTime())
                        outputPacer?.offer(ScreenDecodedOutput(index, info.presentationTimeUs))
                    }

                    override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
                        readyForInput.completeExceptionally(
                            ToolboxException("screen_decoder_failed", error)
                        )
                        freeInputs.close(ToolboxException("screen_decoder_failed", error))
                        outputPacer?.close()
                    }

                    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) =
                        Unit
                },
                handler,
            )
            if (stats != null) {
                codec.setOnFrameRenderedListener(
                    { _, ptsMicros, nanoTime ->
                        if (!closed.get()) stats.rendered(ptsMicros, nanoTime, System.nanoTime())
                    },
                    handler,
                )
            }
            val format = MediaFormat.createVideoFormat(mime, width, height)
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, targetFps)
            // A seamless preference, not a persistent device refresh-rate setting.
            refreshVote?.let { vote ->
                runCatching {
                    surface.setFrameRate(
                        vote,
                        Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                        Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
                    )
                }
            }
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxFrameBytes(width, height))
            codec.configure(format, surface, null, 0)
            codec.start()
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    /**
     * A started codec may still be initializing; do not release the UDP gate before it accepts
     * input.
     */
    suspend fun awaitReady() = readyForInput.await()

    suspend fun queue(frame: ToolboxScreenEvent.Frame) {
        if (closed.get()) throw ToolboxException("screen_decoder_closed")
        if (!frame.codecConfig)
            stats?.arrived(frame.ptsMicros, frame.bytes.size, System.nanoTime(), frame.receipt)
        stats?.inputWaitStarted(System.nanoTime())
        val index =
            try {
                freeInputs.receive()
            } finally {
                stats?.inputWaitFinished(System.nanoTime())
            }
        if (closed.get()) throw ToolboxException("screen_decoder_closed")
        val buffer = codec.getInputBuffer(index) ?: return
        buffer.clear()
        val bytes = h264?.bytes(frame) ?: frame.bytes
        if (bytes.size > buffer.capacity()) {
            stats?.oversizedFrame()
            codec.queueInputBuffer(index, 0, 0, frame.ptsMicros, 0)
            return
        }
        buffer.put(bytes)
        var flags = 0
        if (frame.codecConfig) flags = flags or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
        if (frame.keyFrame) flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        codec.queueInputBuffer(index, 0, bytes.size, frame.ptsMicros, flags)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        readyForInput.cancel()
        freeInputs.close()
        try {
            outputPacer?.close()
            ownedCodec?.let { codec ->
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
        } finally {
            if (refreshVote != null)
                runCatching {
                    surface.setFrameRate(
                        0f,
                        Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                        Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
                    )
                }
            thread.quitSafely()
        }
    }
}

/**
 * The refresh rate the mirror asks the display for, or null to leave it to the system. Android 14
 * and later may slow all of an app's drawing to the rate its surfaces ask for, down to 30 Hz and
 * even under a finger, so a 10 FPS stream asking for 10 made the page around it scroll at 30. Only
 * a stream at least as fast as [fastestHz], the quickest seamless rate, asks: that keeps the
 * display there and slows nothing.
 */
internal fun screenRefreshVote(targetFps: Int, fastestHz: Float?): Float? =
    targetFps.toFloat().takeIf {
        fastestHz != null && fastestHz.isFinite() && fastestHz > 0 && it >= fastestHz - 0.5f
    }

/**
 * Where a finger on the shown video lands on the watch. The view has the frame's shape, so scaling
 * each axis is enough. Clamp to valid scrcpy coordinates; TouchLayer releases a finger as soon as
 * it leaves the video's outline.
 */
internal fun screenTouch(
    action: Int,
    pointerId: Long,
    viewX: Float,
    viewY: Float,
    viewWidth: Int,
    viewHeight: Int,
    frameWidth: Int,
    frameHeight: Int,
): ToolboxScreenInput.Touch =
    ToolboxScreenInput.Touch(
        action = action,
        pointerId = pointerId,
        x = (viewX / viewWidth * frameWidth).toInt().coerceIn(0, frameWidth - 1),
        y = (viewY / viewHeight * frameHeight).toInt().coerceIn(0, frameHeight - 1),
        width = frameWidth,
        height = frameHeight,
        pressure = if (action == TOUCH_UP) 0f else 1f,
        buttons = 0,
    )

/** Room for a whole frame at any bit rate: it won't compress to more than it is uncompressed. */
private fun maxFrameBytes(width: Int, height: Int): Int =
    (width.toLong() * height * 3 / 2).coerceIn(MIN_FRAME_BYTES, MAX_FRAME_BYTES).toInt()

private const val MIN_FRAME_BYTES = 1_048_576L

/** No frame gets across a toolbox connection whole if it's bigger than its packets. */
private const val MAX_FRAME_BYTES = 8 * 1_048_576L

internal const val TOUCH_DOWN = 0
internal const val TOUCH_UP = 1
internal const val TOUCH_MOVE = 2
