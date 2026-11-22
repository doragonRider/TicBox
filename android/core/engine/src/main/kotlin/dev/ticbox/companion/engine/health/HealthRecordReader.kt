package dev.ticbox.companion.engine.health

import dev.ticbox.companion.api.*

/**
 * Read-side adapter for an existing local store. Owns source isolation, intersecting filters,
 * ordering and pagination from HealthRecordQuery; returns the full opaque stored record.
 * No acquisition, login or network effects. Storage lifetime remains with its service owner.
 */
interface HealthRecordReader {
    suspend fun sources(): List<HealthRecordSource>
    suspend fun records(kind: HealthRecordKind, query: HealthRecordQuery): HealthRecordPage
}

/** Optional backend-normalized point reader; unavailable kinds remain explicit. */
interface HealthPointReader {
    suspend fun points(query: HealthPointQuery): HealthPointPage
}

interface HealthSleepReader {
    suspend fun sleepRecords(query: HealthSleepQuery): HealthSleepPage
}

interface HealthFitnessReader {
    suspend fun fitnessRecords(query: HealthFitnessRecordQuery): HealthFitnessRecordPage
    suspend fun fitnessPoints(query: HealthFitnessPointQuery): HealthFitnessPointPage
}
