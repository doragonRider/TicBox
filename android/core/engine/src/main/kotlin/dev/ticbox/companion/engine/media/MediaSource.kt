package dev.ticbox.companion.engine.media

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.MediaCommand
import dev.ticbox.companion.protocol.MediaContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class MediaSourceState(val accessGranted: Boolean = false, val available: Boolean = false)
data class PhoneMediaSession(val summary: MediaSessionSummary, val content: MediaContent)
data class MediaCommandOutcome(val attempted: Int, val accepted: Int, val failed: Int = attempted - accepted)

interface MediaSource {
    val state: StateFlow<MediaSourceState>
    /** Coalesced invalidation hints; snapshot remains authoritative. */
    val events: Flow<Unit>
    /** Android priority order. Throw on unreadable state instead of returning a fake empty list. */
    suspend fun snapshot(): List<PhoneMediaSession>
    /** Session id is the exact source identity selected when this command was received. */
    suspend fun execute(sessionId: String, command: MediaCommand): MediaCommandOutcome
}

interface MediaSettingsStore {
    suspend fun load(): MediaControlsSettings
    suspend fun save(settings: MediaControlsSettings)
}
class MemoryMediaSettingsStore(initial: MediaControlsSettings = MediaControlsSettings()) : MediaSettingsStore {
    private var value = initial
    override suspend fun load(): MediaControlsSettings = synchronized(this) { value }
    override suspend fun save(settings: MediaControlsSettings) { synchronized(this) { value = settings } }
}

/** The platform rechecks live references and other owners before clearing cached bytes. */
fun interface MediaAssetRetirement {
    suspend fun retire(scope: DataScope, route: ApplicationRoute, digests: Set<String>)
    companion object { val NONE = MediaAssetRetirement { _, _, _ -> } }
}
