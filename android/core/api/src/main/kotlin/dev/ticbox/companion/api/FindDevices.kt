package dev.ticbox.companion.api

import kotlinx.coroutines.flow.StateFlow

/** Local Bluetooth finding. No location service or Internet request is involved. */
data class FindDeviceSettings(val enabled: Boolean = true)
enum class FindWatchStatus { IDLE, STARTING, RINGING, STOPPING, STOPPED, UNAVAILABLE, TIMED_OUT, DISCONNECTED, FAILED }
data class PhoneRingingState(
    val active: Boolean = false,
    /** MediaPlayer.start succeeded; this does not measure audible sound or bypass DND. */
    val audioPlaybackStarted: Boolean = false,
    val vibrationRequested: Boolean = false,
    val failure: String? = null,
    val preparingAudio: Boolean = false,
)
/** Category of the latest finding problem. [FindDevicesIssue.detail] is diagnostic only. */
enum class FindDevicesIssueKind { SETTINGS_RESTORE, PHONE_SOUND, PHONE_RING, WATCH_FIND, RING_REPLY }
data class FindDevicesIssue(val kind: FindDevicesIssueKind, val detail: String)
data class FindDeviceState(
    val settings: FindDeviceSettings = FindDeviceSettings(),
    val sessionId: Long = 0,
    val capabilityPublished: Boolean = false,
    val phone: PhoneRingingState = PhoneRingingState(),
    /** Local watch companion extension. STARTING only means the remote launch was submitted. */
    val watchStatus: FindWatchStatus = FindWatchStatus.DISCONNECTED,
    /** The watch reported that its player or vibrator started; this does not measure audible sound. */
    val watchRingingConfirmed: Boolean = false,
    val watchAudioPlaybackStarted: Boolean = false,
    val watchVibrationRequested: Boolean = false,
    /** A current request can still be stopped, including when its playback feedback timed out. */
    val watchStopAvailable: Boolean = false,
    val lastIssue: FindDevicesIssue? = null,
    /** Local preferences have finished restoring; finding commands await this automatically. */
    val settingsReady: Boolean = false,
)
interface FindDevicesClient : AutoCloseable {
    val state: StateFlow<FindDeviceState>
    suspend fun setEnabled(enabled: Boolean)
    suspend fun ringPhone()
    suspend fun stopPhone()
    /** Opens the installed watch component over the local link and waits asynchronously for feedback. */
    suspend fun ringWatch()
    suspend fun stopWatch()
    override fun close()
}
