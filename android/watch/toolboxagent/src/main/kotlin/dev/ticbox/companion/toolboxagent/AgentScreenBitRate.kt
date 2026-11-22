package dev.ticbox.companion.toolboxagent

import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Lowers the encoder's target while pictures queue up on this device and brings it back slowly once
 * they stop. Video goes out as fast as the socket takes it, so a queue here means the encoder makes
 * more than the network carries, which some encoders do two or three times over for seconds. A
 * frame's age is the time from its capture to its arrival from the server. Only the youngest frame
 * of each window counts, so one picture an encoder held through an idle stretch isn't taken for a
 * queue; the floor is the youngest age seen over the last seconds.
 */
internal class ScreenBitRateController(
    private val base: Int,
    private val apply: (Int) -> Unit,
    private val report: ((reason: String, target: Int, queueMicros: Long) -> Unit)? = null,
) {
    var target = base
        private set
    private val lowest = maxOf(base / 5, MIN_TARGET).coerceAtMost(base)
    private var first = -1L
    private var windowStart = 0L
    private var windowFrames = 0
    private var windowYoungest = Long.MAX_VALUE
    private val floors = LongArray(FLOOR_SECONDS) { Long.MAX_VALUE }
    private var floorIndex = 0
    private var floorStart = 0L
    private var lastDecrease = Long.MIN_VALUE / 2
    private var lastChange = Long.MIN_VALUE / 2
    private var calmSince = 0L

    /** Called on the video relay thread for each picture, with its capture time and now. */
    fun frame(ptsMicros: Long, nowMicros: Long) {
        val age = nowMicros - ptsMicros
        if (age !in 0..MAX_AGE_MICROS) return
        if (first < 0) { first = nowMicros; windowStart = nowMicros; floorStart = nowMicros; calmSince = nowMicros }
        advanceFloor(nowMicros)
        windowFrames++
        windowYoungest = minOf(windowYoungest, age)
        if (nowMicros - windowStart < WINDOW_MICROS) return
        if (windowFrames >= MIN_WINDOW_FRAMES) judge(windowYoungest, nowMicros)
        if (target < base && nowMicros - calmSince >= CALM_MICROS && nowMicros - lastChange >= STEP_MICROS) {
            change(minOf(base, target + base / 10), "increase", 0, nowMicros)
        }
        windowStart = nowMicros; windowFrames = 0; windowYoungest = Long.MAX_VALUE
    }

    private fun judge(youngest: Long, now: Long) {
        floors[floorIndex] = minOf(floors[floorIndex], youngest)
        val queue = youngest - floors.min()
        if (queue > LOW_QUEUE_MICROS) calmSince = now
        if (queue > HIGH_QUEUE_MICROS && now - first >= WARMUP_MICROS && now - lastDecrease >= HOLD_MICROS) {
            change(maxOf(lowest, (target * 7L / 10).toInt()), "decrease", queue, now)
            lastDecrease = now
        }
    }

    private fun change(value: Int, reason: String, queue: Long, now: Long) {
        if (value == target) return
        target = value; lastChange = now
        apply(value)
        report?.invoke(reason, value, queue)
    }

    private fun advanceFloor(now: Long) {
        if (now - floorStart >= FLOOR_SECONDS * SECOND_MICROS) {
            floors.fill(Long.MAX_VALUE); floorStart = now; return
        }
        while (now - floorStart >= SECOND_MICROS) {
            floorIndex = (floorIndex + 1) % FLOOR_SECONDS
            floors[floorIndex] = Long.MAX_VALUE
            floorStart += SECOND_MICROS
        }
    }

    companion object {
        const val MIN_TARGET = 200_000
        const val WINDOW_MICROS = 200_000L
        const val MIN_WINDOW_FRAMES = 3
        const val HIGH_QUEUE_MICROS = 80_000L
        const val LOW_QUEUE_MICROS = 25_000L
        const val HOLD_MICROS = 400_000L
        const val CALM_MICROS = 1_500_000L
        const val STEP_MICROS = 1_000_000L
        const val WARMUP_MICROS = 2_000_000L
        const val MAX_AGE_MICROS = 5_000_000L
        private const val SECOND_MICROS = 1_000_000L
        private const val FLOOR_SECONDS = 8
    }
}

/** The pinned server's control socket, written by the phone's controls and the bit rate controller. */
internal class ScrcpyControl(private val output: OutputStream) {
    @Synchronized fun write(bytes: ByteArray) { output.write(bytes); output.flush() }

    /** TicBox scrcpy extension: opcode 126 and the encoder's new target in bits per second. */
    fun setVideoBitRate(bitRate: Int) = write(ByteBuffer.allocate(5).put(SET_VIDEO_BIT_RATE).putInt(bitRate).array())

    companion object { const val SET_VIDEO_BIT_RATE: Byte = 126 }
}
