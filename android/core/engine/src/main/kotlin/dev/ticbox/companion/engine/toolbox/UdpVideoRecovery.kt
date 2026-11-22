package dev.ticbox.companion.engine.toolbox

/** Published atomically by the UDP video receiver, read by the independent recovery timer. */
internal data class UdpVideoRecoveryState(
    val observedFrame: Boolean = false,
    val needsKeyFrame: Boolean = true,
    val acceptedKeyFrames: Long = 0,
    val waitingForDecoder: Boolean = false,
    val decoderPrepared: Boolean = false,
    val captureGeneration: Long = 0,
)

/**
 * Request a fresh video chain promptly after dependency loss in an established stream. Cold
 * startup still gets a grace period. Retries for the same loss wait [intervalMillis]; a new loss
 * after the requested key arrived waits only [newLossMillis] since the last request, so two losses
 * in quick succession don't freeze the picture for the whole cooldown.
 * An encoder's GOP length is not a reliable time bound at variable FPS.
 */
internal class UdpVideoRecovery(
    private val intervalMillis: Long = 2_000,
    private val maximumAttempts: Int = 3,
    private val restartAllowed: Boolean = false,
    private val restartTimeoutMillis: Long = 10_000,
    private val newLossMillis: Long = NEW_LOSS_MILLIS,
) {
    enum class Action { NONE, REQUEST_KEYFRAME, RESTART_VIDEO, FAILED }
    init { require(intervalMillis > 0 && maximumAttempts > 0 && restartTimeoutMillis > 0 && newLossMillis > 0) }
    private var waitingSince: Long? = null
    private var lastRequest: Long? = null
    /** Keys accepted when the last request went out; more since then means this is a new loss. */
    private var requestKeyFrames = 0L
    private var keyFrames = 0L
    private var attempts = 0
    private var restartUsed = false
    private var restartStarted: Long? = null
    private var restartGeneration = 0L
    private var restartKeyFrames = 0L

    /** nowMillis must use a monotonic clock. A new key clears an episode even between polls. */
    fun poll(state: UdpVideoRecoveryState, nowMillis: Long): Action {
        restartStarted?.let { started ->
            if (nowMillis - started >= restartTimeoutMillis) return Action.FAILED
            // An old in-flight key or a new header alone cannot confirm a restarted reference.
            if (state.captureGeneration > restartGeneration && state.acceptedKeyFrames > restartKeyFrames &&
                !state.waitingForDecoder && !state.needsKeyFrame) {
                restartStarted = null; keyFrames = state.acceptedKeyFrames; waitingSince = null; attempts = 0
            } else {
                return Action.NONE
            }
        }
        if (state.acceptedKeyFrames != keyFrames) {
            keyFrames = state.acceptedKeyFrames
            waitingSince = null
            attempts = 0
        }
        if (state.waitingForDecoder || !state.observedFrame || !state.needsKeyFrame) {
            waitingSince = null
            attempts = 0
            return Action.NONE
        }
        val since = waitingSince ?: nowMillis.also { waitingSince = it }
        // Reassembly has already allowed its reorder deadline before declaring a missing
        // dependency. Once a key has established the stream, waiting another full GOP here
        // turns a small packet loss into a seconds-long freeze. Issue the first request at
        // this poll. Before any accepted key, preserve the encoder's cold-start grace period.
        // Repeated deltas/configuration packets cannot postpone that deadline, and the global
        // cooldown remains in force even when a key and another loss occur between polls.
        val initialWait = if (state.acceptedKeyFrames == 0L && !state.decoderPrepared) intervalMillis else 0L
        if (nowMillis - since < initialWait || cooldownLeft(nowMillis) > 0) return Action.NONE
        if (attempts >= maximumAttempts) {
            // A sync request need not generate output on a static surface. Once a stream has
            // actually worked, permit one capture-only reset with a separate finite deadline.
            // Never retry a failed initial UDP connection or silently switch media transports.
            if (restartAllowed && !restartUsed && state.captureGeneration > 0 && state.acceptedKeyFrames > 0) {
                restartUsed = true; restartStarted = nowMillis
                restartGeneration = state.captureGeneration; restartKeyFrames = state.acceptedKeyFrames
                lastRequest = nowMillis; requestKeyFrames = keyFrames
                return Action.RESTART_VIDEO
            }
            return Action.FAILED
        }
        attempts++
        lastRequest = nowMillis; requestKeyFrames = keyFrames
        return Action.REQUEST_KEYFRAME
    }

    /** How long the cooldown still holds the next request back, as of the last poll; 0 if it doesn't. */
    fun cooldownLeft(nowMillis: Long): Long {
        val last = lastRequest ?: return 0
        val cooldown = if (keyFrames > requestKeyFrames) minOf(intervalMillis, newLossMillis) else intervalMillis
        return (last + cooldown - nowMillis).coerceAtLeast(0)
    }

    companion object {
        const val NEW_LOSS_MILLIS = 150L
    }
}
