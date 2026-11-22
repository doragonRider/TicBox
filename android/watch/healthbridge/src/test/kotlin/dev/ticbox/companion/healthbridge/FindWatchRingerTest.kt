package dev.ticbox.companion.healthbridge

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Looper
import android.os.Vibrator
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real Android audio/vibrator APIs under Robolectric, including asynchronous preparation and teardown. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class FindWatchRingerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var audio: AudioManager
    private lateinit var vibrator: Vibrator
    private lateinit var ringer: AndroidFindWatchRinger
    private val output = mutableListOf<FindWatchOutput>()
    private val players = mutableListOf<MediaPlayer>()

    @Before fun setup() {
        audio = context.getSystemService(AudioManager::class.java)
        @Suppress("DEPRECATION")
        vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        shadowOf(vibrator).setHasVibrator(false)
        ShadowMediaPlayer.setCreateListener { player, _ -> players += player }
        prepareMedia(delayMillis = 50)
        ringer = AndroidFindWatchRinger(context)
    }

    private fun prepareMedia(delayMillis: Int) {
        val tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, tone), ShadowMediaPlayer.MediaInfo(10_000, delayMillis))
    }

    @After fun cleanup() {
        ringer.stop()
        ShadowMediaPlayer.setCreateListener(null)
    }

    @Test fun audioFlagWaitsForRealStartAndStopRestoresOurVolumeIncrease() {
        ringer.start(output::add)
        assertTrue(output.last().preparingAudio)
        assertFalse(output.any { it.audioPlaybackStarted })
        assertEquals(2, audio.getStreamVolume(AudioManager.STREAM_ALARM))
        shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
        assertTrue(players.single().isPlaying)
        assertTrue(output.last().audioPlaybackStarted)
        assertFalse(output.last().preparingAudio)
        assertEquals(audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), audio.getStreamVolume(AudioManager.STREAM_ALARM))
        ringer.stop()
        assertEquals(2, audio.getStreamVolume(AudioManager.STREAM_ALARM))
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(players.single()).state)
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun stopKeepsVolumeChangedByUserWhileFindingWasActive() {
        ringer.start(output::add)
        shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        ringer.stop()
        assertEquals(1, audio.getStreamVolume(AudioManager.STREAM_ALARM))
        ringer.stop()
        assertEquals(1, audio.getStreamVolume(AudioManager.STREAM_ALARM))
    }

    @Test fun stopDuringPreparationReleasesPlayerAndLateCallbackCannotRestartIt() {
        ringer.start(output::add)
        val player = players.single()
        val prepared = shadowOf(player).onPreparedListener!!
        ringer.stop()
        prepared.onPrepared(player)
        shadowOf(Looper.getMainLooper()).idleFor(10_000, TimeUnit.MILLISECONDS)
        assertFalse(output.any { it.audioPlaybackStarted })
        assertEquals(2, audio.getStreamVolume(AudioManager.STREAM_ALARM))
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(player).state)
    }

    @Test fun deniedAudioFocusKeepsIndependentVibrationAndStopCancelsIt() {
        shadowOf(vibrator).setHasVibrator(true)
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        ringer.start(output::add)
        assertTrue(players.isEmpty())
        assertTrue(output.last().active)
        assertFalse(output.last().audioPlaybackStarted)
        assertFalse(output.last().preparingAudio)
        assertTrue(output.last().vibrationRequested)
        assertEquals("audio_focus_denied", output.last().reason)
        assertTrue(shadowOf(vibrator).isVibrating)
        ringer.stop()
        assertTrue(shadowOf(vibrator).isCancelled)
    }

    @Test fun stalledAudioPreparationTimesOutWithoutClaimingPlayback() {
        prepareMedia(delayMillis = 20_000)
        ringer.start(output::add)
        shadowOf(Looper.getMainLooper()).idleFor(8_000, TimeUnit.MILLISECONDS)
        assertEquals("audio_prepare_timeout", output.last().reason)
        assertFalse(output.last().active)
        assertFalse(output.last().preparingAudio)
        assertFalse(output.any { it.audioPlaybackStarted })
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(players.single()).state)
        assertEquals(2, audio.getStreamVolume(AudioManager.STREAM_ALARM))
    }
}
