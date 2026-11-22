package dev.ticbox.companion.api

import kotlinx.coroutines.flow.StateFlow

data class MediaControlsSettings(
    val enabled: Boolean = true,
    val preferredPackage: String? = null,
    val autoLaunch: Boolean = false,
)

data class MediaSessionSummary(
    val id: String,
    val packageName: String,
    val applicationLabel: String,
    val title: String? = null,
    val artist: String? = null,
    val playing: Boolean = false,
    val positionMillis: Long? = null,
    val durationMillis: Long? = null,
    val playbackSpeed: Float = 1f,
    val transportActions: Long = 0,
    val volume: Float? = null,
    val supportsAbsoluteVolume: Boolean = false,
)

enum class MediaControlsPhase { DISABLED, ACCESS_REQUIRED, SOURCE_UNAVAILABLE, DISCONNECTED, IDLE, ACTIVE, FAILED, CLOSED }
enum class MediaControlsIssue { SOURCE, STORAGE, PUBLISH, COMMAND, MALFORMED_MESSAGE, RESOURCE_LIMIT }

data class MediaControlsState(
    val settings: MediaControlsSettings = MediaControlsSettings(),
    val phase: MediaControlsPhase = MediaControlsPhase.ACCESS_REQUIRED,
    val accessGranted: Boolean = false,
    val sessions: List<MediaSessionSummary> = emptyList(),
    val selectedSessionId: String? = null,
    val sessionId: Long = 0,
    val published: Boolean = false,
    val receivedCommands: Long = 0,
    val acceptedOperations: Long = 0,
    val failedOperations: Long = 0,
    val lastIssue: MediaControlsIssue? = null,
)

/** UI observes typed summaries and changes settings; it never reads or constructs wire payloads. */
interface MediaControlsClient : AutoCloseable {
    val state: StateFlow<MediaControlsState>
    suspend fun setEnabled(enabled: Boolean)
    suspend fun setPreferredPackage(packageName: String?)
    suspend fun setAutoLaunch(enabled: Boolean)
    suspend fun resync()
    override fun close()
}
