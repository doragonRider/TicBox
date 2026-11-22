package dev.ticbox.companion.engine.calling

import dev.ticbox.companion.api.*
import kotlinx.coroutines.flow.StateFlow

data class CallingPlatformState(
    val access: CallingAccess = CallingAccess(),
    val serviceConnected: Boolean = false,
    val calls: List<CompanionCall> = emptyList(),
    val muted: Boolean? = null,
    val endpoints: List<CallAudioEndpoint> = emptyList(),
    val currentEndpointId: String? = null,
    val silenceRingerSequence: Long = 0,
    val telephonyStateValue: Int? = null,
    val issue: String? = null,
)

interface CallingPlatform : AutoCloseable {
    val state: StateFlow<CallingPlatformState>
    suspend fun refreshAccess()
    suspend fun execute(command: CallCommand, watch: PeerAddress?): CallCommandResult
    override fun close()
}

/** Missing settings return null; corrupt/unreadable settings must report an actual failure. */
interface CallingSettingsStore {
    suspend fun readEnabled(): Boolean?
    suspend fun writeEnabled(enabled: Boolean)
}
