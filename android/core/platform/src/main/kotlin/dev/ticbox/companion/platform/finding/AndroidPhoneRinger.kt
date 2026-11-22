package dev.ticbox.companion.platform.finding

import android.content.Context
import android.media.*
import android.os.*
import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.finding.PhoneRinger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Public Android APIs only. The service supplies a visible mediaPlayback FGS before audio starts. */
class AndroidPhoneRinger(
    context: Context,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val foreground: (Boolean) -> Unit = {},
) : PhoneRinger {
    private val context = context.applicationContext
    private val audio = this.context.getSystemService(AudioManager::class.java)
    private val vibrator = this.context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(PhoneRingingState())
    override val state = mutableState.asStateFlow()
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var previousVolume: Int? = null
    private var boostedVolume: Int? = null
    private var closed = false
    private var generation = 0L
    private var preparationTimeout: Runnable? = null

    override suspend fun start() = withContext(Dispatchers.Main.immediate) {
        check(!closed)
        if (mutableState.value.active) return@withContext
        release()
        val selectedGeneration = generation
        var audioStarted = false
        var preparing = false
        var vibrationStarted = false
        var issue: String? = null
        try {
            foreground(true)
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) release()
                }, handler).build()
            if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                issue = "audio_focus_denied"
            } else {
                focus = request
                try {
                    val before = audio.getStreamVolume(AudioManager.STREAM_ALARM)
                    val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                    audio.setStreamVolume(AudioManager.STREAM_ALARM, maximum, 0)
                    previousVolume = before; boostedVolume = maximum
                } catch (problem: Exception) { log("volume_change_unavailable", problem) }
                val tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val next = MediaPlayer()
                player = next
                next.setAudioAttributes(attributes)
                next.setDataSource(this@AndroidPhoneRinger.context, tone)
                next.isLooping = true
                next.setOnPreparedListener {
                    if (closed || generation != selectedGeneration || player !== next) return@setOnPreparedListener
                    preparationTimeout?.let(handler::removeCallbacks); preparationTimeout = null
                    try {
                        next.start()
                        mutableState.update { it.copy(active = true, audioPlaybackStarted = true, preparingAudio = false) }
                    } catch (problem: Exception) { audioFailed(next, selectedGeneration, "audio_start_failed", problem) }
                }
                next.setOnErrorListener { _, what, extra ->
                    log("playback_error", details = mapOf("what" to what.toString(), "extra" to extra.toString()))
                    audioFailed(next, selectedGeneration, "playback_error"); true
                }
                next.prepareAsync()
                preparing = true
                preparationTimeout = Runnable { audioFailed(next, selectedGeneration, "audio_prepare_timeout") }.also {
                    handler.postDelayed(it, 8_000)
                }
            }
        } catch (cancelled: CancellationException) { release(); throw cancelled }
        catch (problem: Exception) {
            issue = "audio_${problem.javaClass.simpleName}"; log("audio_start_failed", problem)
            releaseAudio()
        }
        try {
            if (vibrator?.hasVibrator() == true) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 0),
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
                vibrationStarted = true
            }
        } catch (problem: Exception) { log("vibration_unavailable", problem) }
        val active = audioStarted || vibrationStarted || preparing
        if (!active) foreground(false)
        mutableState.value = PhoneRingingState(active, audioStarted, vibrationStarted, issue, preparing)
    }

    private fun audioFailed(selected: MediaPlayer, selectedGeneration: Long, reason: String, problem: Throwable? = null) {
        if (generation != selectedGeneration || player !== selected) return
        log(reason, problem)
        releaseAudio()
        val vibrating = mutableState.value.vibrationRequested
        mutableState.value = PhoneRingingState(active = vibrating, vibrationRequested = vibrating, failure = reason)
        if (!vibrating) runCatching { foreground(false) }
    }

    override suspend fun stop() = withContext(Dispatchers.Main.immediate) { release() }
    private fun releaseAudio() {
        preparationTimeout?.let(handler::removeCallbacks); preparationTimeout = null
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }; player = null
        focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }; focus = null
        val saved = previousVolume; val boosted = boostedVolume
        if (saved != null && boosted != null) runCatching {
            // Preserve a volume choice made by the user while the ringer was active.
            if (audio.getStreamVolume(AudioManager.STREAM_ALARM) == boosted) audio.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0)
        }.onFailure { log("volume_restore_failed", it) }
        previousVolume = null; boostedVolume = null
    }
    private fun release() {
        generation++
        releaseAudio(); runCatching { vibrator?.cancel() }; runCatching { foreground(false) }
        mutableState.value = PhoneRingingState()
    }
    private fun log(event: String, problem: Throwable? = null, details: Map<String, String> = emptyMap()) {
        runCatching { diagnostics.record(ProtocolDiagnostic("finding_android", event,
            details = details + (problem?.let { mapOf("exception" to it.javaClass.simpleName) } ?: emptyMap()))) }
    }
    override fun close() {
        closed = true
        if (Looper.myLooper() == Looper.getMainLooper()) release() else handler.post { release() }
    }
}
