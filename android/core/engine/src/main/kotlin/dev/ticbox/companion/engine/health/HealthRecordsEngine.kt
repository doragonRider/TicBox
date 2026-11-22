package dev.ticbox.companion.engine.health

import dev.ticbox.companion.api.*

/** Read-only composition of local bridge records and the independently selected cloud cache. */
class HealthRecordsEngine(
    private val deviceRecords: HealthRecordReader? = null,
    private val mobvoiCache: HealthRecordReader? = null,
) : HealthRecordsClient {
    @Volatile private var closed = false

    override suspend fun sources(): List<HealthRecordSource> {
        alive()
        return listOfNotNull(deviceRecords, mobvoiCache).flatMap { it.sources() }.distinct()
    }

    override suspend fun records(kind: HealthRecordKind, query: HealthRecordQuery): HealthRecordPage {
        alive()
        require(query.offset >= 0 && query.limit in 1..500)
        val reader = when (query.source) {
            is HealthRecordSource.LocalDevice -> deviceRecords
            is HealthRecordSource.MobvoiCloud -> mobvoiCache
        }
        return reader?.records(kind, query) ?: HealthRecordPage(emptyList(), null, HealthRecordReadStatus.SOURCE_UNAVAILABLE)
    }

    override suspend fun points(query: HealthPointQuery): HealthPointPage {
        alive()
        require(query.offset >= 0 && query.limit in 1..500)
        return (deviceRecords as? HealthPointReader)?.points(query)
            ?: HealthPointPage(emptyList(), null, HealthRecordReadStatus.SOURCE_UNAVAILABLE)
    }

    override suspend fun sleepRecords(query: HealthSleepQuery): HealthSleepPage {
        alive()
        require(query.offset >= 0 && query.limit in 1..500)
        return (deviceRecords as? HealthSleepReader)?.sleepRecords(query)
            ?: HealthSleepPage(emptyList(), null, HealthRecordReadStatus.SOURCE_UNAVAILABLE)
    }

    override suspend fun fitnessRecords(query: HealthFitnessRecordQuery): HealthFitnessRecordPage {
        alive()
        require(query.offset >= 0 && query.limit in 1..500)
        return (deviceRecords as? HealthFitnessReader)?.fitnessRecords(query)
            ?: HealthFitnessRecordPage(emptyList(), null, HealthRecordReadStatus.SOURCE_UNAVAILABLE)
    }

    override suspend fun fitnessPoints(query: HealthFitnessPointQuery): HealthFitnessPointPage {
        alive()
        require(query.offset >= 0 && query.limit in 1..500)
        return (deviceRecords as? HealthFitnessReader)?.fitnessPoints(query)
            ?: HealthFitnessPointPage(emptyList(), null, HealthRecordReadStatus.SOURCE_UNAVAILABLE)
    }

    private fun alive() { if (closed) throw BackendClosedException() }
    override fun close() { closed = true }
}
