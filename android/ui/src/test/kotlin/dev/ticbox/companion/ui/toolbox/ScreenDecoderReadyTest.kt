package dev.ticbox.companion.ui.toolbox

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.view.Surface
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowMediaCodec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ScreenDecoderReadyTest.DelayedCodec::class])
class ScreenDecoderReadyTest {
    @Test
    fun startReturningDoesNotAcknowledgeReadinessBeforeAnInputBufferArrives() =
        runBlocking<Unit> {
            val texture = SurfaceTexture(0)
            val surface = Surface(texture)
            lateinit var codec: MediaCodec
            val decoder =
                ScreenDecoder(
                    surface,
                    320,
                    320,
                    codecFactory = {
                        MediaCodec.createDecoderByType("video/avc").also { codec = it }
                    },
                )
            try {
                val ready = async(start = CoroutineStart.UNDISPATCHED) { decoder.awaitReady() }
                assertFalse(ready.isCompleted)
                checkNotNull(DelayedCodec.callback).onInputBufferAvailable(codec, 0)
                withTimeout(1_000) { ready.await() }
                assertTrue(ready.isCompleted)
            } finally {
                decoder.close()
                surface.release()
                texture.release()
            }
        }

    @Test
    fun closeCancelsReadinessAndLateInputCallbacksCannotReviveIt() =
        runBlocking<Unit> {
            val texture = SurfaceTexture(0)
            val surface = Surface(texture)
            lateinit var codec: MediaCodec
            val decoder =
                ScreenDecoder(
                    surface,
                    320,
                    320,
                    codecFactory = {
                        MediaCodec.createDecoderByType("video/avc").also { codec = it }
                    },
                )
            try {
                val ready = async(start = CoroutineStart.UNDISPATCHED) { decoder.awaitReady() }
                assertFalse(ready.isCompleted)
                decoder.close()
                assertFailsWith<CancellationException> { ready.await() }
                checkNotNull(DelayedCodec.callback).onInputBufferAvailable(codec, 0)
                assertFailsWith<CancellationException> { decoder.awaitReady() }
            } finally {
                decoder.close()
                surface.release()
                texture.release()
            }
        }

    @Implements(MediaCodec::class)
    class DelayedCodec : ShadowMediaCodec() {
        @Implementation
        override fun native_setCallback(value: MediaCodec.Callback?) {
            callback = value
            super.native_setCallback(value)
        }

        @Implementation
        override fun native_start() =
            Unit // Driver returns from start before sending any input callback.

        companion object {
            @Volatile var callback: MediaCodec.Callback? = null
        }
    }
}
