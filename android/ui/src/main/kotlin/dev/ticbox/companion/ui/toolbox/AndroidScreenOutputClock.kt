package dev.ticbox.companion.ui.toolbox

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.view.Choreographer
import java.util.concurrent.atomic.AtomicBoolean

/** Separate from both UI work and bursts of codec callbacks. No spinning or permanent timer. */
internal class AndroidScreenOutputClock(
    @Suppress("UNUSED_PARAMETER") decoderHandler: Handler,
    onFrame: (Long) -> Unit,
    onTimeout: () -> Unit,
) : ScreenOutputClock {
    private val closed = AtomicBoolean()
    private val thread =
        HandlerThread("ScreenVsync", Process.THREAD_PRIORITY_MORE_FAVORABLE).apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var failureHandler: (Throwable) -> Unit = {}
    private var choreographer: Choreographer? = null
    private val frame = Choreographer.FrameCallback { safe { if (!closed.get()) onFrame(it) } }
    private val timeout = Runnable { safe { if (!closed.get()) onTimeout() } }

    private fun dispatch(action: () -> Unit) {
        if (closed.get()) return
        if (Looper.myLooper() === handler.looper) action()
        else
            check(handler.post { safe { if (!closed.get()) action() } }) {
                "screen_frame_clock_closed"
            }
    }

    private fun safe(action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            failureHandler(error)
        }
    }

    override fun setFailureHandler(handler: (Throwable) -> Unit) {
        failureHandler = handler
    }

    override fun requestFrame() = dispatch {
        val clock = choreographer ?: Choreographer.getInstance().also { choreographer = it }
        clock.removeFrameCallback(frame)
        clock.postFrameCallback(frame)
    }

    override fun cancelFrame() = dispatch { choreographer?.removeFrameCallback(frame) }

    override fun requestTimeout(delayMillis: Long) {
        handler.removeCallbacks(timeout)
        if (!closed.get())
            check(handler.postDelayed(timeout, delayMillis)) { "screen_frame_clock_closed" }
    }

    override fun cancelTimeout() {
        handler.removeCallbacks(timeout)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        // No join under the pacer's monitor: an in-flight callback can be waiting for that monitor.
        handler.post { choreographer?.removeFrameCallback(frame) }
        thread.quitSafely()
    }
}
