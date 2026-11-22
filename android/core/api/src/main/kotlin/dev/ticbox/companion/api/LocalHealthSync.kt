package dev.ticbox.companion.api

import kotlinx.coroutines.flow.StateFlow

enum class LocalHealthSyncPhase { IDLE, CONNECTING, READING, RETRYING, COMPLETED, PARTIAL, UNAVAILABLE, FAILED, DISCONNECTED, CLOSED }
/** FULL rereads all enabled sources; it never deletes the existing phone archive. */
enum class LocalHealthSyncMode { INCREMENTAL, FULL }

data class LocalHealthSyncState(
    val phase: LocalHealthSyncPhase = LocalHealthSyncPhase.IDLE,
    val source: HealthRecordSource.LocalDevice? = null,
    val sessionId: Long = 0,
    val pagesCommitted: Int = 0,
    val recordsCommitted: Int = 0,
    /** Also restored from the database after a restart, so it may predate this process. */
    val lastSuccessAtMillis: Long? = null,
    /** Stable metadata reason; never includes row values, account IDs or SQL parameters. */
    val reason: String? = null,
    /** Where the most recent finished run spent its time; null until a run finishes. */
    val lastRun: LocalHealthSyncTiming? = null,
    val mode: LocalHealthSyncMode = LocalHealthSyncMode.INCREMENTAL,
    /** Table wire names the watch offered in the latest handshake; null until one succeeds. */
    val advertisedTables: Set<String>? = null,
)

/** Time spent in one stage of a sync run, summed over its repetitions. */
data class LocalHealthSyncStage(
    /** Table wire name, or null for a stage that belongs to the whole run. */
    val table: String?,
    /**
     * Stable identifier: checkpoint, open, wake, wait, connect, handshake, read, save, ack or retry_wait. A bridge that reports
     * its own time splits read into watch (its work on the pages) and transfer (Bluetooth both ways and parsing here).
     */
    val name: String,
    val millis: Long,
    /** How often the stage ran, e.g. connection attempts or pages. */
    val count: Int = 1,
    /** Bytes received during the stage; only read counts them. */
    val bytes: Long = 0,
)

/** Diagnostics for one sync run; stages keep the order in which they first ran. */
data class LocalHealthSyncTiming(
    val startedAtMillis: Long,
    val totalMillis: Long,
    val stages: List<LocalHealthSyncStage>,
)

/** Phone-triggered, local Bluetooth only. Reads are independently available through healthRecords. */
interface LocalHealthSyncClient : AutoCloseable {
    val state: StateFlow<LocalHealthSyncState>

    /** Starts service-owned work. Calls coalesce; FULL replaces an active incremental run.
     * Cancellation preserves committed phone rows and the watch's pending deliveries. */
    suspend fun sync(mode: LocalHealthSyncMode = LocalHealthSyncMode.INCREMENTAL)

    /**
     * Automatic refresh, e.g. when the app returns to the foreground. Behaves like [sync] unless
     * the last successful sync finished less than [AUTO_SYNC_INTERVAL_MILLIS] ago.
     */
    suspend fun syncIfStale()

    suspend fun cancel()

    /** Reads fixed journal metadata; does not install or repair a journal. Requires the root switch on the watch. */
    suspend fun journalStatus(source: LocalHealthJournalSource): LocalHealthJournalStatus =
        throw LocalHealthJournalException("journal_unavailable")

    /**
     * Explicitly installs/removes the same-database journal on this watch. Installing changes OEM schema,
     * not health values. Log failures may abort the OEM write; disabling removes the owned triggers.
     * This is never replayed automatically after a timeout or reconnect. No automatic opt-in.
     * Its own deadline throws LocalHealthJournalException("journal_operation_unconfirmed"):
     * installation/removal may already have committed; query status before another explicit write.
     * Caller cancellation still propagates CancellationException.
     */
    suspend fun setJournalEnabled(source: LocalHealthJournalSource, enabled: Boolean): LocalHealthJournalStatus =
        throw LocalHealthJournalException("journal_unavailable")

    override fun close()

    companion object {
        /** Automatic syncs are skipped while the last successful one is younger than this. */
        const val AUTO_SYNC_INTERVAL_MILLIS = 5 * 60_000L
    }
}
