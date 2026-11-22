package dev.ticbox.companion.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.session.WatchSession
import dev.ticbox.companion.ui.session.readySession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Base for screens backed by the current [WatchSession]. State follows session switches (local
 * service, rebinding, demo) automatically; commands run through a shared [CommandRunner].
 */
abstract class SessionViewModel(dependencies: CompanionDependencies) : ViewModel() {
    private val commands = CommandRunner(dependencies.applicationScope, dependencies.messages)
    protected val messages = dependencies.messages

    /** Keys of commands that are still running; screens disable just those controls. */
    val pending: StateFlow<Set<Any>> = commands.pending

    val session: StateFlow<WatchSession?> =
        dependencies.sessions.state
            .map { it.readySession }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                dependencies.sessions.state.value.readySession,
            )

    /**
     * A state that follows the session. [current] computes the value synchronously, so the first
     * frame already shows real data; [updates] supplies later changes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    protected fun <T> sessionState(
        current: (WatchSession) -> T,
        updates: (WatchSession) -> Flow<T>,
    ): StateFlow<T?> =
        session
            .flatMapLatest { session -> session?.let(updates) ?: flowOf(null) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, session.value?.let(current))

    /** Runs [block] against the current session; does nothing without one. */
    protected fun command(key: Any, block: suspend (WatchSession) -> UiText?) {
        val current = session.value ?: return
        commands.launch(key) { block(current) }
    }
}
