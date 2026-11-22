package dev.ticbox.companion.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What root can see around the module, which is what can say why it isn't running. */
data class WatchModuleHostSnapshot(
    /** Magisk's own setting, which it reads at boot. Null when Magisk wouldn't say. */
    val zygiskOn: Boolean?,
    val healthOnDenyList: Boolean,
    /** Whether the module knows this version of the health app. Null when it isn't installed. */
    val healthSupported: Boolean?,
    val healthRunning: Boolean,
    /** Whether the notification extension knows this system UI. Null when it can't be read. */
    val systemUiSupported: Boolean?,
)
enum class WatchModuleHostPhase { DISCONNECTED, READY, BUSY, UNAVAILABLE, CLOSED }
data class WatchModuleHostState(
    val phase: WatchModuleHostPhase = WatchModuleHostPhase.DISCONNECTED,
    val snapshot: WatchModuleHostSnapshot? = null,
    /** False means [snapshot] says nothing about the current connection. */
    val current: Boolean = false,
    val reason: String? = null,
)
/**
 * The system-level steps around the module, over root Local Bridge and for explicit requests only:
 * Magisk's Zygisk setting, a normal restart and restarting the system UI alone. Reads and writes
 * are fixed commands.
 */
interface WatchModuleHostClient : AutoCloseable {
    val state: StateFlow<WatchModuleHostState>
    suspend fun refresh()
    /**
     * Keeps the watch from sleeping for a few minutes, or until it restarts. A sleeping watch all
     * but stops running Magisk's installer once the upload's Bluetooth traffic has ended.
     */
    suspend fun stayAwake()
    /** Takes effect at the next boot. A write is never repeated, and is always followed by a read. */
    suspend fun turnOnZygisk()
    /** Asks for a normal restart and returns at once. The link dropping is what confirms it. */
    suspend fun restart()
    /**
     * Stops the system UI, which the system starts again, and keeps the screen lit meanwhile, as a
     * dark watch seems to hold it back. Returns at once, with the watch and its link staying up, so
     * whether it came back is for the next read to say.
     */
    suspend fun restartSystemUi()
    override fun close()
    companion object {
        val UNAVAILABLE = object : WatchModuleHostClient {
            override val state = MutableStateFlow(WatchModuleHostState(phase = WatchModuleHostPhase.UNAVAILABLE))
            override suspend fun refresh() = Unit
            override suspend fun stayAwake() = Unit
            override suspend fun turnOnZygisk() = Unit
            override suspend fun restart() = Unit
            override suspend fun restartSystemUi() = Unit
            override fun close() = Unit
        }
    }
}
