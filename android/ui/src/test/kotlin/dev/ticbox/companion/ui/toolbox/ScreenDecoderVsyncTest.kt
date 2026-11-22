package dev.ticbox.companion.ui.toolbox

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowMediaCodec

/** Real Android callback plumbing with synthetic decoded outputs; no hardware codec claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ScreenDecoderVsyncTest.RecordingCodec::class])
class ScreenDecoderVsyncTest {
    @Test
    fun onlyAStreamAsFastAsTheDisplayAsksForARefreshRate() {
        assertNull(screenRefreshVote(10, 120f), "a 10 FPS vote would slow the page to 30")
        assertNull(screenRefreshVote(60, 120f))
        assertEquals(120f, screenRefreshVote(120, 119.88f))
        assertEquals(60f, screenRefreshVote(60, 60f))
        assertNull(screenRefreshVote(120, null), "unknown display")
        assertNull(screenRefreshVote(120, Float.NaN))
    }

    @Test
    fun outputCallbackWaitsForVsyncUsesLocalTimeAndCloseRetiresPendingBuffers() {
        RecordingCodec.released.clear()
        RecordingCodec.callback = null
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val thread = HandlerThread("test-paced-screen-decoder")
        lateinit var fake: Clock
        lateinit var codec: MediaCodec
        val decoder =
            ScreenDecoder(
                surface,
                320,
                320,
                codecFactory = { MediaCodec.createDecoderByType("video/avc").also { codec = it } },
                threadFactory = { thread },
                outputClockFactory = { _, frame, timeout ->
                    Clock(frame, timeout).also { fake = it }
                },
            )
        val handler = Handler(thread.looper)
        try {
            val callback = checkNotNull(RecordingCodec.callback)
            onHandler(handler) {
                callback.onOutputBufferAvailable(codec, 20, info(8_000_000_000))
                callback.onOutputBufferAvailable(codec, 21, info(8_000_016_000))
            }
            assertTrue(RecordingCodec.released.isEmpty())
            assertEquals(1, fake.frameRequests)
            onHandler(handler) { fake.frame(16_000_000) }
            assertEquals(listOf(Release(20, 16_000_000)), RecordingCodec.released)
            decoder.close()
            thread.join(2_000)
            assertFalse(thread.isAlive)
            assertEquals(
                listOf(Release(20, 16_000_000), Release(21, null)),
                RecordingCodec.released,
            )
            fake.frame(32_000_000)
            fake.timeout()
            callback.onOutputBufferAvailable(codec, 22, info(8_000_032_000))
            assertEquals(2, RecordingCodec.released.size)
        } finally {
            decoder.close()
            thread.quitSafely()
            surface.release()
            texture.release()
        }
    }

    private class Clock(val frame: (Long) -> Unit, val timeout: () -> Unit) : ScreenOutputClock {
        var frameRequests = 0

        override fun requestFrame() {
            frameRequests++
        }

        override fun cancelFrame() = Unit

        override fun requestTimeout(delayMillis: Long) = Unit

        override fun cancelTimeout() = Unit
    }

    @Implements(MediaCodec::class)
    class RecordingCodec : ShadowMediaCodec() {
        @Implementation
        override fun native_setCallback(value: MediaCodec.Callback?) {
            callback = value
            super.native_setCallback(value)
        }

        @Implementation
        override fun releaseOutputBuffer(index: Int, render: Boolean) {
            check(!render) { "Presentation must use a local timestamp" }
            released += Release(index, null)
        }

        @Implementation
        override fun releaseOutputBuffer(index: Int, renderTimestampNs: Long) {
            released += Release(index, renderTimestampNs)
        }

        companion object {
            @Volatile var callback: MediaCodec.Callback? = null
            val released = mutableListOf<Release>()
        }
    }

    data class Release(val index: Int, val atNanos: Long?)

    companion object {
        private fun info(pts: Long) = MediaCodec.BufferInfo().apply { set(0, 1, pts, 0) }

        private fun onHandler(handler: Handler, action: () -> Unit) {
            val finished = CountDownLatch(1)
            var failure: Throwable? = null
            check(
                handler.post {
                    try {
                        action()
                    } catch (error: Throwable) {
                        failure = error
                    } finally {
                        finished.countDown()
                    }
                }
            )
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            failure?.let { throw it }
        }
    }
}
