package dev.ticbox.companion.ui.health

import dev.ticbox.companion.api.HealthRecordSource
import dev.ticbox.companion.api.HealthRecordsClient
import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.session.WatchSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Where a page reads the selected watch's stored records. */
data class RecordAccess(
    val client: HealthRecordsClient,
    val source: HealthRecordSource.LocalDevice,
)

/** Base for pages backed by the records stored on this phone for the selected watch. */
abstract class LocalHealthViewModel(dependencies: CompanionDependencies) :
    SessionViewModel(dependencies) {
    protected val source: StateFlow<HealthRecordSource.LocalDevice?> =
        sessionState(
            current = { session ->
                val peer = session.client.state.value.connection.peer
                sourceOf(session, peer ?: session.services.recovery.state.value.desiredPeer)
            },
            updates = { session ->
                combine(session.client.state, session.services.recovery.state) { watch, recovery ->
                        sourceOf(session, watch.connection.peer ?: recovery.desiredPeer)
                    }
                    .distinctUntilChanged()
            },
        )

    private val lastSync: StateFlow<Long?> =
        sessionState(
            current = { it.services.localHealth.state.value.lastSuccessAtMillis },
            updates = { session ->
                session.services.localHealth.state
                    .map { it.lastSuccessAtMillis }
                    .distinctUntilChanged()
            },
        )

    /** Emits when the watch changes and after each completed sync, so pages can read again. */
    protected val recordAccess: Flow<RecordAccess?> =
        combine(source, lastSync) { source, _ ->
            val client = session.value?.services?.healthRecords
            if (source != null && client != null) RecordAccess(client, source) else null
        }

    private fun sourceOf(session: WatchSession, peer: PeerAddress?) = peer?.let {
        HealthRecordSource.LocalDevice(session.localProfileId, it)
    }
}
