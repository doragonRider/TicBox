package dev.ticbox.companion.ui.toolbox

/** A decoded buffer is already independent of the decoder's reference pictures. */
internal data class ScreenDecodedOutput(val index: Int, val ptsMicros: Long)

/** Causes, not guesses from an undifferentiated displayed-frame counter. */
internal enum class ScreenOutputObservation {
    VSYNC,
    EMPTY_VSYNC,
    QUEUE_OVERFLOW,
    WATCHDOG_DROP,
    WATCHDOG_PRESENT,
}

/** Callback scheduling only. Android and deterministic tests provide their own clock adapters. */
internal interface ScreenOutputClock : AutoCloseable {
    fun requestFrame()

    fun cancelFrame()

    fun requestTimeout(delayMillis: Long)

    fun cancelTimeout()

    fun setFailureHandler(handler: (Throwable) -> Unit) = Unit

    override fun close() = Unit
}

/**
 * Keeps at most two decoded outputs: one due at the next display tick and one frame of jitter
 * allowance. Dropping here releases an already decoded display buffer; encoded reference frames are
 * never removed. All transitions, including close versus a callback, share the same guard.
 *
 * With [smooth], up to [SMOOTH_CAPACITY] wait, so pictures that arrive together are shown one per
 * tick rather than the older ones dropped. A queue still two deep after a tick for
 * [SMOOTH_TRIM_MILLIS] loses its oldest picture, so the delay a burst adds doesn't stay.
 */
internal class ScreenOutputPacer(
    private val clock: ScreenOutputClock,
    private val release: (ScreenDecodedOutput, Long?) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
    private val timeoutMillis: Long = 100,
    private val targetFps: Int = 0,
    private val warmMillis: Long = 250,
    private val observe: ((ScreenOutputObservation, Long) -> Unit)? = null,
    private val smooth: Boolean = false,
) : AutoCloseable {
    private val capacity = if (smooth) SMOOTH_CAPACITY else 2
    private val pending = ArrayDeque<ScreenDecodedOutput>(capacity)
    private var crowdedSince: Long? = null
    private var closed = false
    private var frameScheduled = false
    private var timeoutScheduled = false
    private var deadlineNanos: Long? = null
    private var lastPresentedNanos: Long? = null
    private var lastOfferedNanos: Long? = null

    init {
        require(timeoutMillis in 1..1_000)
        require(targetFps in 0..120)
        require(warmMillis in 0..1_000)
        clock.setFailureHandler { error ->
            synchronized(this) {
                close()
                runCatching { onFailure(error) }
            }
        }
    }

    @Synchronized
    fun offer(output: ScreenDecodedOutput) {
        if (closed) return // The owner retires all codec buffers when closing the decoder.
        val now = nanoTime()
        lastOfferedNanos = now
        val overflow = if (pending.size == capacity) pending.removeFirst() else null
        pending.addLast(output)
        if (overflow != null) {
            observed(ScreenOutputObservation.QUEUE_OVERFLOW)
            if (!retire(overflow, null)) return
        }
        requestFrame()
        // New output must not postpone the first waiting frame's no-vsync deadline forever.
        if (!timeoutScheduled) scheduleTimeout(now)
    }

    @Synchronized
    fun onVsync(frameTimeNanos: Long) {
        frameScheduled = false
        if (closed) return
        val now = nanoTime()
        observed(ScreenOutputObservation.VSYNC, (now - frameTimeNanos).takeIf { it >= 0 } ?: -1)
        if (pending.isEmpty()) {
            observed(ScreenOutputObservation.EMPTY_VSYNC)
            keepClockWarm(now)
            return
        }
        // An already dispatched callback may arrive after watchdog presentation. Never render
        // another buffer against that stale tick, or twice against the same display timestamp.
        if (lastPresentedNanos?.let { frameTimeNanos <= it } == true) {
            requestFrame()
            return
        }
        // Do not consume two stream frames on adjacent fast-display ticks (e.g. 60 on 120 Hz).
        if (
            targetFps > 0 &&
                lastPresentedNanos?.let { frameTimeNanos - it < 800_000_000L / targetFps } == true
        ) {
            requestFrame()
            return
        }
        cancelTimeout()
        if (!retire(pending.removeFirst(), frameTimeNanos)) return
        lastPresentedNanos = frameTimeNanos
        if (smooth && !trim(now)) return
        if (pending.isNotEmpty()) {
            requestFrame()
            scheduleTimeout(nanoTime())
        } else keepClockWarm(now)
    }

    @Synchronized
    fun onTimeout() {
        if (closed || pending.isEmpty() || !timeoutScheduled) return
        val now = nanoTime()
        val deadline = deadlineNanos ?: return
        if (now < deadline) {
            // Handler clocks and monotonic nanos can round differently; keep the original
            // deadline instead of treating an early callback as another full grace period.
            clock.requestTimeout(((deadline - now + 999_999) / 1_000_000).coerceAtLeast(1))
            return
        }
        cancelFrame()
        cancelTimeout()
        // A display callback may stop while the surface remains valid. Retire stale buffers
        // and present the newest one using THIS device's clock, so a final static image is not
        // lost forever. This is a bounded safety path, not a second periodic rendering loop.
        while (pending.size > 1) {
            observed(ScreenOutputObservation.WATCHDOG_DROP)
            if (!retire(pending.removeFirst(), null)) return
        }
        observed(ScreenOutputObservation.WATCHDOG_PRESENT)
        if (!retire(pending.removeFirst(), now)) return
        lastPresentedNanos = now
        lastOfferedNanos = null // No periodic clock retry after the no-vsync safety path.
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        lastOfferedNanos = null
        cancelFrame()
        cancelTimeout()
        while (pending.isNotEmpty()) runCatching { release(pending.removeFirst(), null) }
        runCatching { clock.close() }
    }

    /**
     * Keep the next tick armed across short decoder/network gaps, then go fully idle. This holds no
     * extra buffer and never submits a duplicate image just to sustain the clock.
     */
    private fun keepClockWarm(now: Long) {
        val offered = lastOfferedNanos ?: return
        if (warmMillis > 0 && now - offered in 0 until warmMillis * 1_000_000) requestFrame()
    }

    /** Smooth only: once two or more stay waiting after each tick, the oldest goes. */
    private fun trim(now: Long): Boolean {
        if (pending.size < 2) {
            crowdedSince = null
            return true
        }
        val since = crowdedSince ?: now.also { crowdedSince = it }
        if (now - since < SMOOTH_TRIM_MILLIS * 1_000_000) return true
        crowdedSince = now
        observed(ScreenOutputObservation.QUEUE_OVERFLOW)
        return retire(pending.removeFirst(), null)
    }

    private fun observed(event: ScreenOutputObservation, durationNanos: Long = 0) {
        observe?.let { runCatching { it(event, durationNanos) } }
    }

    private fun retire(output: ScreenDecodedOutput, atNanos: Long?): Boolean =
        try {
            release(output, atNanos)
            true
        } catch (error: Throwable) {
            close()
            runCatching { onFailure(error) }
            false
        }

    private fun requestFrame() {
        if (!frameScheduled) {
            frameScheduled = true
            try {
                clock.requestFrame()
            } catch (error: Throwable) {
                close()
                runCatching { onFailure(error) }
            }
        }
    }

    private fun scheduleTimeout(now: Long) {
        if (closed) return
        timeoutScheduled = true
        deadlineNanos = now + timeoutMillis * 1_000_000
        try {
            clock.requestTimeout(timeoutMillis)
        } catch (error: Throwable) {
            close()
            runCatching { onFailure(error) }
        }
    }

    private fun cancelFrame() {
        if (!frameScheduled) return
        frameScheduled = false
        runCatching { clock.cancelFrame() }
    }

    private fun cancelTimeout() {
        if (!timeoutScheduled) return
        timeoutScheduled = false
        deadlineNanos = null
        runCatching { clock.cancelTimeout() }
    }

    @get:Synchronized
    internal val pendingOutputs: Int
        get() = pending.size

    companion object {
        const val SMOOTH_CAPACITY = 4
        const val SMOOTH_TRIM_MILLIS = 250L
    }
}
