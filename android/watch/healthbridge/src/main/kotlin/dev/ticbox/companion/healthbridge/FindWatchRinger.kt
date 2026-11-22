package dev.ticbox.companion.healthbridge

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

internal data class FindWatchOutput(
    val audioPlaybackStarted: Boolean = false,
    val vibrationRequested: Boolean = false,
    val preparingAudio: Boolean = false,
    val reason: String? = null,
) {
    val active get() = audioPlaybackStarted || vibrationRequested
}

internal interface FindWatchRinger {
    fun start(changed: (FindWatchOutput) -> Unit)
    fun stop()
}

/** Public APIs, owned by a resumed, visible Activity. No background service starts playback. */
internal class AndroidFindWatchRinger(context: Context) : FindWatchRinger {
    private val context = context.applicationContext
    private val audio = this.context.getSystemService(AudioManager::class.java)
    @Suppress("DEPRECATION")
    private val vibrator = if (Build.VERSION.SDK_INT >= 31)
        this.context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else this.context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var previousVolume: Int? = null
    private var appliedVolume: Int? = null
    private var generation = 0L
    private var preparationTimeout: Runnable? = null
    private var changed: ((FindWatchOutput) -> Unit)? = null
    private var output = FindWatchOutput()

    override fun start(changed: (FindWatchOutput) -> Unit) {
        stop()
        this.changed = changed
        val selectedGeneration = generation
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val vibrating = try {
            if (vibrator?.hasVibrator() == true) {
                @Suppress("DEPRECATION")
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0), attributes)
                true
            } else false
        } catch (problem: Exception) { findingLog("vibration_unavailable", problem); false }
        publish(FindWatchOutput(vibrationRequested = vibrating, preparingAudio = true))
        // A synchronous consumer may stop while accepting the initial vibration update.
        if (generation != selectedGeneration) return
        try {
            val requested = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
                    if (generation == selectedGeneration &&
                        (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
                        audioFailed(selectedGeneration, "audio_focus_lost")
                }, handler).build()
            if (audio.requestAudioFocus(requested) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                audioFailed(selectedGeneration, "audio_focus_denied")
                return
            }
            focus = requested
            val tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            if (tone == null) { audioFailed(selectedGeneration, "alarm_tone_unavailable"); return }
            val next = MediaPlayer()
            player = next
            next.setAudioAttributes(attributes)
            next.setDataSource(this.context, tone)
            next.isLooping = true
            next.setOnPreparedListener {
                if (generation != selectedGeneration || player !== next) return@setOnPreparedListener
                preparationTimeout?.let(handler::removeCallbacks); preparationTimeout = null
                try {
                    raiseAlarmVolume()
                    next.start()
                    publish(output.copy(audioPlaybackStarted = true, preparingAudio = false, reason = null))
                } catch (problem: Exception) { audioFailed(selectedGeneration, "audio_start_failed", problem) }
            }
            next.setOnErrorListener { _, what, extra ->
                findingLog("audio_error_$what:$extra")
                if (player === next) audioFailed(selectedGeneration, "playback_error")
                true
            }
            preparationTimeout = Runnable { audioFailed(selectedGeneration, "audio_prepare_timeout") }.also {
                handler.postDelayed(it, 8_000)
            }
            next.prepareAsync()
        } catch (problem: Exception) { audioFailed(selectedGeneration, "audio_unavailable", problem) }
    }

    private fun publish(next: FindWatchOutput) { output = next; changed?.invoke(next) }

    private fun audioFailed(selectedGeneration: Long, reason: String, problem: Throwable? = null) {
        if (generation != selectedGeneration) return
        findingLog(reason, problem)
        releaseAudio()
        publish(output.copy(audioPlaybackStarted = false, preparingAudio = false, reason = reason))
    }

    private fun raiseAlarmVolume() {
        try {
            val before = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (before >= maximum) return
            audio.setStreamVolume(AudioManager.STREAM_ALARM, maximum, 0)
            previousVolume = before
            appliedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        } catch (problem: Exception) { findingLog("volume_change_unavailable", problem) }
    }

    private fun releaseAudio() {
        preparationTimeout?.let(handler::removeCallbacks); preparationTimeout = null
        player?.let { selected ->
            selected.setOnPreparedListener(null); selected.setOnErrorListener(null)
            runCatching { selected.stop() }; runCatching { selected.release() }
        }
        player = null
        focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }; focus = null
        val saved = previousVolume; val applied = appliedVolume
        if (saved != null && applied != null) runCatching {
            // Restore only our own increase; keep a subsequent volume choice made on the watch.
            if (audio.getStreamVolume(AudioManager.STREAM_ALARM) == applied)
                audio.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0)
        }.onFailure { findingLog("volume_restore_failed", it) }
        previousVolume = null; appliedVolume = null
    }

    override fun stop() {
        generation++
        changed = null
        releaseAudio()
        runCatching { vibrator?.cancel() }.onFailure { findingLog("vibration_stop_failed", it) }
        output = FindWatchOutput()
    }
}
