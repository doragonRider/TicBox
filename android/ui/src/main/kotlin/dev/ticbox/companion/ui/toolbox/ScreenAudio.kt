package dev.ticbox.companion.ui.toolbox

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import dev.ticbox.companion.api.ToolboxScreenEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel

/**
 * Plays the stream's Opus sound. It never holds up the picture sharing its connection: when the
 * decoder or the speaker is full, sound is dropped, which also keeps it from falling behind. If
 * playing fails, [onFailure] hears about it once and the picture carries on without sound.
 */
internal class ScreenAudio(
    private val onFailure: () -> Unit,
    private val codecFactory: () -> MediaCodec = {
        MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    },
    private val trackFactory: () -> AudioTrack = ::screenAudioTrack,
    private val threadFactory: () -> HandlerThread = { HandlerThread("ScreenAudio") },
) : AutoCloseable {
    private val freeInputs = Channel<Int>(Channel.UNLIMITED)
    private val closed = AtomicBoolean()
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var thread: HandlerThread? = null
    @Volatile private var decodingFailed = false

    /** Takes one frame at a time, from the stream's collector. */
    fun queue(frame: ToolboxScreenEvent.AudioFrame) {
        if (closed.get()) return
        try {
            check(!decodingFailed)
            if (frame.codecConfig) open(frame.bytes) else decode(frame)
        } catch (_: Exception) {
            // The picture matters more, and nothing here is worth stopping it for.
            if (closed.compareAndSet(false, true)) {
                release()
                onFailure()
            }
        }
    }

    private fun open(head: ByteArray) {
        if (codec != null) return
        val decoder = codecFactory().also { codec = it }
        val speaker = trackFactory().also { track = it }
        val worker = threadFactory().apply { start() }.also { thread = it }
        decoder.setCallback(
            object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    freeInputs.trySend(index)
                }

                override fun onOutputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo,
                ) {
                    // Stopping can overtake sound that was already on its way here.
                    runCatching {
                        codec.getOutputBuffer(index)?.let { pcm ->
                            pcm.position(info.offset).limit(info.offset + info.size)
                            speaker.write(pcm, info.size, AudioTrack.WRITE_NON_BLOCKING)
                        }
                        codec.releaseOutputBuffer(index, false)
                    }
                }

                override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
                    decodingFailed = true
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
            },
            Handler(worker.looper),
        )
        decoder.configure(opusFormat(head), null, null, 0)
        decoder.start()
        speaker.play()
    }

    private fun decode(frame: ToolboxScreenEvent.AudioFrame) {
        val decoder = codec ?: return
        val index = freeInputs.tryReceive().getOrNull() ?: return
        val buffer = decoder.getInputBuffer(index) ?: return
        buffer.clear()
        if (frame.bytes.size > buffer.capacity()) {
            decoder.queueInputBuffer(index, 0, 0, frame.ptsMicros, 0)
            return
        }
        buffer.put(frame.bytes)
        decoder.queueInputBuffer(index, 0, frame.bytes.size, frame.ptsMicros, 0)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }

    private fun release() {
        freeInputs.close()
        codec?.let { codec ->
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        track?.let { track ->
            runCatching { track.stop() }
            runCatching { track.release() }
        }
        thread?.quitSafely()
    }
}

/** Opus samples count at 48 kHz whatever the source's rate. Android wants its timings as csd. */
private fun opusFormat(head: ByteArray): MediaFormat {
    val preSkip =
        ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getShort(10).toInt() and 0xffff
    return MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_OPUS,
            SAMPLE_RATE,
            CHANNELS,
        )
        .apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(head))
            setByteBuffer("csd-1", nanoseconds(preSkip * 1_000_000_000L / SAMPLE_RATE))
            setByteBuffer("csd-2", nanoseconds(OPUS_SEEK_PRE_ROLL_NANOS))
        }
}

private fun nanoseconds(value: Long): ByteBuffer =
    ByteBuffer.allocate(Long.SIZE_BYTES).order(ByteOrder.nativeOrder()).putLong(value).apply {
        flip()
    }

private fun screenAudioTrack(): AudioTrack {
    val channels = AudioFormat.CHANNEL_OUT_STEREO
    val encoding = AudioFormat.ENCODING_PCM_16BIT
    return AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(channels)
                .setEncoding(encoding)
                .build()
        )
        .setBufferSizeInBytes(
            maxOf(AudioTrack.getMinBufferSize(SAMPLE_RATE, channels, encoding), TENTH_OF_A_SECOND)
        )
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        .build()
}

private const val SAMPLE_RATE = 48_000
private const val CHANNELS = 2
private const val TENTH_OF_A_SECOND = SAMPLE_RATE * CHANNELS * Short.SIZE_BYTES / 10
private const val OPUS_SEEK_PRE_ROLL_NANOS = 80_000_000L
