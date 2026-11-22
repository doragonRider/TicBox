package dev.ticbox.companion.engine

import dev.ticbox.companion.api.*

/** The host owns its lifetime; the facade only records and queries. */
interface WatchObservationStore : WatchHistoryClient {
    suspend fun append(peer: PeerAddress, kind: WatchSampleKind, atMillis: Long, batteryPercent: Int? = null, steps: StepSnapshot? = null)
}
