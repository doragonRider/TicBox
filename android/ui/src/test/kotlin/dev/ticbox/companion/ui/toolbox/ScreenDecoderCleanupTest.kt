package dev.ticbox.companion.ui.toolbox

import android.graphics.SurfaceTexture
import android.os.HandlerThread
import android.view.Surface
import dev.ticbox.companion.api.ToolboxScreenEvent
import kotlin.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreenDecoderCleanupTest {
    @Test
    fun aPhoneThatCantPlayTheSoundLosesOnlyThatAndOnlySaysSoOnce() {
        var failures = 0
        val audio =
            ScreenAudio(onFailure = { failures++ }, codecFactory = { error("codec unavailable") })
        audio.queue(ToolboxScreenEvent.AudioFrame(0, codecConfig = false, byteArrayOf(1)))
        assertEquals(0, failures, "nothing to play before the header")
        audio.queue(ToolboxScreenEvent.AudioFrame(0, codecConfig = true, ByteArray(19)))
        audio.queue(ToolboxScreenEvent.AudioFrame(1, codecConfig = false, byteArrayOf(1)))
        audio.close()
        assertEquals(1, failures)
    }

    @Test
    fun codecCreationFailureRetiresTheAlreadyStartedThread() {
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val thread = HandlerThread("test-failed-screen-decoder")
        try {
            assertFailsWith<IllegalStateException> {
                ScreenDecoder(
                    surface,
                    320,
                    320,
                    codecFactory = { error("codec unavailable") },
                    threadFactory = { thread },
                )
            }
            thread.join(2000)
            assertFalse(thread.isAlive)
        } finally {
            thread.quitSafely()
            surface.release()
            texture.release()
        }
    }
}
