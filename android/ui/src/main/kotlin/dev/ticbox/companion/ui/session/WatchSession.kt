package dev.ticbox.companion.ui.session

import dev.ticbox.companion.api.WatchClient
import dev.ticbox.companion.ui.WatchUiServices
import kotlinx.coroutines.flow.StateFlow

/**
 * The backend the screens talk to: the local service, or the demo watch. Both implement the same
 * core:api clients, so screens never branch on which one is active.
 */
data class WatchSession(
    /** Stable for the lifetime of one backend; a different key resets navigation. */
    val key: String,
    val isDemo: Boolean,
    val client: WatchClient,
    val services: WatchUiServices,
    /** This phone's local health profile; with the peer it identifies the local record source. */
    val localProfileId: String,
)

sealed interface SessionState {
    /** The local service is starting or rebinding. */
    data object Connecting : SessionState

    /** The local service could not be bound; [SessionController.retry] tries again. */
    data object Unavailable : SessionState

    data class Ready(val session: WatchSession) : SessionState
}

val SessionState.readySession: WatchSession?
    get() = (this as? SessionState.Ready)?.session

/** Owned by the app host. Switching to or from the demo replaces the session. */
interface SessionController {
    val state: StateFlow<SessionState>

    fun retry()

    fun enterDemo()

    fun exitDemo()
}
