package dev.ticbox.companion.engine.cloud

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.health.HealthRecordReader

/** Adapts existing cloud-cache rows to local reads; has no account, credential or HTTP dependency. */
class MobvoiCacheHealthRecordReader(private val cache: MobvoiCloudCacheStore) : HealthRecordReader {
    override suspend fun sources(): List<HealthRecordSource.MobvoiCloud> = cache.owners().map { HealthRecordSource.MobvoiCloud(it) }

    override suspend fun records(kind: HealthRecordKind, query: HealthRecordQuery): HealthRecordPage {
        val source = query.source as? HealthRecordSource.MobvoiCloud
            ?: return HealthRecordPage(emptyList(), null, HealthRecordReadStatus.SOURCE_UNAVAILABLE)
        require(query.offset >= 0 && query.limit in 1..500)
        val selected = MobvoiHealthCacheQuery(source.owner, query.fromMillis, query.toMillis, query.dataType,
            query.sourceName, query.includeDeleted, query.offset, query.limit, query.recordKey)
        val rows = cache.query(when (kind) {
            HealthRecordKind.DATA_SOURCE -> CloudCacheKind.SOURCE
            HealthRecordKind.POINT -> CloudCacheKind.POINT
            HealthRecordKind.SESSION -> CloudCacheKind.SESSION
            HealthRecordKind.SPORT -> CloudCacheKind.SPORT
            HealthRecordKind.PROFILE -> CloudCacheKind.PROFILE
            HealthRecordKind.SETTING -> CloudCacheKind.SETTING
            HealthRecordKind.FITNESS_RECORD, HealthRecordKind.FITNESS_POINT ->
                return HealthRecordPage(emptyList(), null, HealthRecordReadStatus.SOURCE_UNAVAILABLE)
        }, selected)
        return HealthRecordPage(rows.take(query.limit).map {
            HealthRawRecord(source, kind, it.key, it.sourceName, it.dataType, it.startMillis, it.endMillis,
                it.timeZone, it.deleted, it.updatedAtMillis, it.payload)
        }, (query.offset + query.limit).takeIf { rows.size > query.limit })
    }
}
