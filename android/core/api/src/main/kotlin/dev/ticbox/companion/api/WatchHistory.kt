package dev.ticbox.companion.api

enum class WatchSampleKind { BATTERY, STEPS }

/** Received observations. Times refer to the phone receipt, never an inferred watch sensor time. */
data class WatchHistorySample(
    val id: Long,
    val peer: PeerAddress,
    val kind: WatchSampleKind,
    val receivedAtEpochMillis: Long,
    val batteryPercent: Int? = null,
    val steps: StepSnapshot? = null,
)

interface WatchHistoryClient {
    /** Newest first, with stable id pagination. An empty result means no locally saved sample. */
    suspend fun recent(peer: PeerAddress, kind: WatchSampleKind? = null, limit: Int = 100, beforeId: Long? = null): List<WatchHistorySample>
}
