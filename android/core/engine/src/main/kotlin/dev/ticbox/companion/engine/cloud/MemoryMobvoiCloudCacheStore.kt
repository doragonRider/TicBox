package dev.ticbox.companion.engine.cloud

import dev.ticbox.companion.api.*

class MemoryMobvoiCloudCacheStore(private val diagnostics: DiagnosticSink = DiagnosticSink.NONE) : MobvoiCloudCacheStore {
    private val lock = Any()
    private val records = linkedMapOf<Triple<String, CloudCacheKind, String>, CloudCacheEntry>()
    private val owners = linkedSetOf<MobvoiCloudOwner>()
    private val windows = linkedSetOf<Pair<MobvoiCloudOwner, CloudSyncWindow>>()
    override suspend fun commit(owner: MobvoiCloudOwner, entries: List<CloudCacheEntry>, window: CloudSyncWindow?) = synchronized(lock) {
        require(entries.all { it.owner == owner })
        owners += owner
        entries.forEach { entry ->
            val key = Triple(CloudIdentity.key(owner), entry.kind, entry.key)
            records[key] = MobvoiSportCache.merge(records[key], entry, diagnostics)
        }
        if (window != null) windows += owner to window
    }
    override suspend fun query(kind: CloudCacheKind, query: MobvoiHealthCacheQuery): List<CloudCacheEntry> = synchronized(lock) {
        require(query.offset >= 0 && query.limit in 1..500)
        records.values.filter { it.owner == query.owner && it.kind == kind && (query.includeDeleted || !it.deleted) &&
            (query.recordKey == null || it.key == query.recordKey) &&
            (query.sourceName == null || it.sourceName == query.sourceName) && (query.dataType == null || it.dataType == query.dataType) &&
            (query.fromMillis == null || it.endMillis == null || it.endMillis >= query.fromMillis!!) &&
            (query.toMillis == null || it.startMillis == null || it.startMillis <= query.toMillis!!)
        }.sortedWith(compareBy<CloudCacheEntry> { it.startMillis ?: Long.MIN_VALUE }.thenBy { it.key }).drop(query.offset).take(query.limit + 1)
    }
    override suspend fun owners(): List<MobvoiCloudOwner> = synchronized(lock) { owners.toList() }
    override suspend fun remove(owner: MobvoiCloudOwner, kind: CloudCacheKind, key: String) { synchronized(lock) { records.remove(Triple(CloudIdentity.key(owner), kind, key)) } }
    override suspend fun clear(owner: MobvoiCloudOwner) { synchronized(lock) { records.entries.removeAll { it.value.owner == owner }; windows.removeAll { it.first == owner }; owners.remove(owner) } }
}
