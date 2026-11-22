package dev.ticbox.companion.api

/**
 * Exact storage partition, independent of the currently signed-in account or Bluetooth session.
 * Local IDs belong to this app's user/profile and must be reused across reconnects. A cloud cache
 * is a separate source, never an implicit alias for a local user or watch with similar IDs.
 */
sealed interface HealthRecordSource {
    data class LocalDevice(val localUserId: String, val peer: PeerAddress) : HealthRecordSource {
        override fun toString(): String = "HealthRecordSource.LocalDevice(identity=<redacted>)"
    }
    data class MobvoiCloud(val owner: MobvoiCloudOwner) : HealthRecordSource {
        override fun toString(): String = "HealthRecordSource.MobvoiCloud(identity=<redacted>)"
    }
}

enum class HealthRecordKind {
    DATA_SOURCE, POINT, SESSION, SPORT, PROFILE, SETTING,
    /** Full TicFitness private tables; separate from the ten-column SPORT provider summary. */
    FITNESS_RECORD, FITNESS_POINT,
}

/**
 * Filters intersect within [source]. Time bounds use inclusive interval overlap; missing times
 * match. dataType/sourceName are outer indexes, not searches of nested JSON. Order is start time
 * then key ascending, missing starts first. Local SESSION and FITNESS_RECORD rows with a
 * nonpositive end are treated as open for overlap filtering. Offset pages are not snapshots of
 * concurrent changes.
 */
data class HealthRecordQuery(
    val source: HealthRecordSource,
    val fromMillis: Long? = null,
    val toMillis: Long? = null,
    val dataType: String? = null,
    val sourceName: String? = null,
    val includeDeleted: Boolean = false,
    val offset: Int = 0,
    val limit: Int = 200,
    val recordKey: String? = null,
) {
    override fun toString(): String = "HealthRecordQuery(filters=<redacted>, offset=$offset, limit=$limit)"
}

/**
 * Opaque locally stored JSON, including unknown/nested fields and uninterpretable vendor values.
 * Index metadata can be absent. updatedAtMillis is phone storage time, not sensor sampling time.
 * The backend parses fields for points()/sleepRecords()/fitnessRecords()/fitnessPoints();
 * the frontend chooses presentation and derived metrics.
 * Never put this record or its JSON in logs.
 */
data class HealthRawRecord(
    val source: HealthRecordSource,
    val kind: HealthRecordKind,
    val key: String,
    val sourceName: String?,
    val dataType: String?,
    val startMillis: Long?,
    val endMillis: Long?,
    val timeZone: String?,
    val deleted: Boolean,
    val updatedAtMillis: Long,
    val json: String,
) {
    override fun toString(): String = "HealthRawRecord(kind=$kind, contents=<redacted>)"
}

enum class HealthRecordReadStatus {
    /** Reader available; an empty page only means no matching phone records. */
    AVAILABLE,
    /** No readable local cache for this kind/source yet (unintegrated or not successfully acquired).
     * Provider denial is exposed by localHealth sync state; it must not be rendered as an empty history.
     * This is not a statement about Bluetooth or whether the watch contains records. */
    SOURCE_UNAVAILABLE,
}
data class HealthRecordPage(val items: List<HealthRawRecord>, val nextOffset: Int?,
    val status: HealthRecordReadStatus = HealthRecordReadStatus.AVAILABLE)

/** Local-only, service-owned reads. No account login, HTTP, sync or cloud opt-in is performed. */
interface HealthRecordsClient : AutoCloseable {
    /** Sources already known to the integrated local stores; does not discover or contact devices. */
    suspend fun sources(): List<HealthRecordSource>
    suspend fun records(kind: HealthRecordKind, query: HealthRecordQuery): HealthRecordPage
    /** Backend-normalized point records; current watch-only reader, no login or network effects. */
    suspend fun points(query: HealthPointQuery): HealthPointPage
    /** AutoSleep sessions and their associated raw/parsed measurements, read from the phone only. */
    suspend fun sleepRecords(query: HealthSleepQuery): HealthSleepPage
    /** Full TicFitness RECORD rows, parsed from the phone's local store. */
    suspend fun fitnessRecords(query: HealthFitnessRecordQuery): HealthFitnessRecordPage
    /** POINT samples for one local RECORD._id, with that parent's mode-dependent column meanings. */
    suspend fun fitnessPoints(query: HealthFitnessPointQuery): HealthFitnessPointPage
    override fun close()
}
