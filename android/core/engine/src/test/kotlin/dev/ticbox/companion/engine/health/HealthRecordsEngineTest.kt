package dev.ticbox.companion.engine.health

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.cloud.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Read-side fixtures only; does not implement watch acquisition or an ingestion/write API. */
class HealthRecordsEngineTest {
    private val local = HealthRecordSource.LocalDevice("local-profile", PeerAddress("AA:BB:CC:DD:EE:FF"))
    private val cloudOwner = MobvoiCloudOwner(MobvoiCloudRegion.GLOBAL, "local-profile", "cloud-account")
    private val cloud = HealthRecordSource.MobvoiCloud(cloudOwner)
    private val body = """{"string_val":"0,unknown,,9","nested":{"large":9223372036854775807,"optional":null}}"""
    private fun record(source: HealthRecordSource, kind: HealthRecordKind = HealthRecordKind.POINT, json: String = body) =
        HealthRawRecord(source, kind, "same-id", "watch-db", "future-metric", 100, 200, "Unrecognized/Zone", false, 789, json)
    private fun cacheRow(kind: CloudCacheKind = CloudCacheKind.POINT, key: String = "same-id", from: Long? = 100,
        to: Long? = 200, deleted: Boolean = false, json: String = body) = CloudCacheEntry(cloudOwner, kind, key,
            "watch-db", "future-metric", from, to, "Unrecognized/Zone", deleted, json, 789)

    @Test fun `local user and watch can read every raw category without an account or cloud reader`() = runBlocking {
        val queries = mutableListOf<Pair<HealthRecordKind, HealthRecordQuery>>()
        val reader = object : HealthRecordReader {
            override suspend fun sources() = listOf(local)
            override suspend fun records(kind: HealthRecordKind, query: HealthRecordQuery): HealthRecordPage {
                queries += kind to query
                return HealthRecordPage(listOf(record(query.source, kind)), null)
            }
        }
        HealthRecordsEngine(deviceRecords = reader).use { client ->
            assertEquals(listOf(local), client.sources())
            val query = HealthRecordQuery(local, fromMillis = 100, toMillis = 200, dataType = "future-metric",
                sourceName = "watch-db", recordKey = "same-id", includeDeleted = true, offset = 20, limit = 50)
            for (kind in HealthRecordKind.entries) {
                val page = client.records(kind, query)
                assertEquals(HealthRecordReadStatus.AVAILABLE, page.status)
                assertEquals(record(local, kind), page.items.single())
                assertEquals(kind to query, queries.last())
                assertNull(page.nextOffset)
            }
        }
    }

    @Test fun `local profiles watches and cloud accounts remain separate even with matching identifiers`() = runBlocking {
        val otherUser = local.copy(localUserId = "another-user")
        val otherWatch = local.copy(peer = PeerAddress("11:22:33:44:55:66"))
        val locals = listOf(local, otherUser, otherWatch)
        val deviceReader = object : HealthRecordReader {
            override suspend fun sources() = locals
            override suspend fun records(kind: HealthRecordKind, query: HealthRecordQuery) = HealthRecordPage(
                listOf(record(query.source, kind, "local-${locals.indexOf(query.source)}")), null)
        }
        val cache = MemoryMobvoiCloudCacheStore()
        cache.commit(cloudOwner, listOf(cacheRow(json = "cloud-one")))
        val otherAccount = cloudOwner.copy(accountId = "another-account")
        cache.commit(otherAccount, listOf(cacheRow(json = "cloud-two").copy(owner = otherAccount)))
        HealthRecordsEngine(deviceReader, MobvoiCacheHealthRecordReader(cache)).use { client ->
            assertEquals((locals + cloud + HealthRecordSource.MobvoiCloud(otherAccount)).toSet(), client.sources().toSet())
            val origins = locals + cloud + HealthRecordSource.MobvoiCloud(otherAccount)
            val records = origins.map { source -> client.records(HealthRecordKind.POINT, HealthRecordQuery(source)).items.single() }
            assertEquals(origins, records.map { it.source })
            assertEquals(listOf("local-0", "local-1", "local-2", "cloud-one", "cloud-two"), records.map { it.json })
            assertEquals(1, records.map { it.key }.distinct().size)
            cache.clear(cloudOwner)
            assertEquals("local-0", client.records(HealthRecordKind.POINT, HealthRecordQuery(local)).items.single().json)
            assertFalse(cloud in client.sources())
        }
    }

    @Test fun `unintegrated watch reader is distinct from an available empty local cache`() = runBlocking {
        val cache = MemoryMobvoiCloudCacheStore()
        HealthRecordsEngine(mobvoiCache = MobvoiCacheHealthRecordReader(cache)).use { client ->
            assertTrue(client.sources().isEmpty())
            val watch = client.records(HealthRecordKind.POINT, HealthRecordQuery(local))
            assertEquals(HealthRecordReadStatus.SOURCE_UNAVAILABLE, watch.status)
            assertTrue(watch.items.isEmpty()); assertNull(watch.nextOffset)
            val available = client.records(HealthRecordKind.POINT, HealthRecordQuery(cloud))
            assertEquals(HealthRecordReadStatus.AVAILABLE, available.status)
            assertTrue(available.items.isEmpty()); assertNull(available.nextOffset)
        }
    }

    @Test fun `cloud adapter preserves every raw category`() = runBlocking {
        val cache = MemoryMobvoiCloudCacheStore()
        val kinds = listOf(CloudCacheKind.SOURCE, CloudCacheKind.POINT, CloudCacheKind.SESSION,
            CloudCacheKind.SPORT, CloudCacheKind.PROFILE, CloudCacheKind.SETTING)
        cache.commit(cloudOwner, kinds.map { cacheRow(it, json = "{ unreadable-old-${it.name}") })
        val reader = MobvoiCacheHealthRecordReader(cache)
        HealthRecordsEngine(mobvoiCache = reader).use { client ->
            for ((kind, cacheKind) in HealthRecordKind.entries.zip(kinds)) {
                val next = client.records(kind, HealthRecordQuery(cloud, recordKey = "same-id", limit = 1))
                val raw = next.items.single()
                assertEquals("{ unreadable-old-${cacheKind.name}", raw.json)
                assertNull(next.nextOffset)
                assertEquals(cloud, raw.source); assertEquals(kind, raw.kind)
                assertEquals("Unrecognized/Zone", raw.timeZone); assertEquals(789, raw.updatedAtMillis)
                assertFalse(raw.toString().contains("unreadable"))
                assertFalse(raw.toString().contains(cloudOwner.wwid))
            }
        }
    }

    @Test fun `cloud record filters pagination missing times and tombstones retain existing semantics`() = runBlocking {
        val cache = MemoryMobvoiCloudCacheStore()
        cache.commit(cloudOwner, listOf(cacheRow(key = "b"), cacheRow(key = "a"), cacheRow(key = "missing", from = null, to = null),
            cacheRow(key = "before", from = 0, to = 99), cacheRow(key = "after", from = 201, to = 300),
            cacheRow(key = "deleted", deleted = true), cacheRow(CloudCacheKind.SESSION, key = "a")))
        HealthRecordsEngine(mobvoiCache = MobvoiCacheHealthRecordReader(cache)).use { client ->
            val query = HealthRecordQuery(cloud, fromMillis = 100, toMillis = 200, dataType = "future-metric", sourceName = "watch-db", limit = 1)
            var selected = query; val keys = mutableListOf<String>()
            do {
                val page = client.records(HealthRecordKind.POINT, selected)
                assertEquals(HealthRecordReadStatus.AVAILABLE, page.status)
                keys += page.items.map { it.key }
                selected = selected.copy(offset = page.nextOffset ?: 0)
            } while (page.nextOffset != null)
            assertEquals(listOf("missing", "a", "b"), keys)
            assertTrue(client.records(HealthRecordKind.POINT, query.copy(sourceName = "other")).items.isEmpty())
            assertTrue(client.records(HealthRecordKind.POINT, query.copy(dataType = "other")).items.isEmpty())
            val deleted = client.records(HealthRecordKind.POINT, query.copy(recordKey = "deleted", includeDeleted = true)).items.single()
            assertTrue(deleted.deleted); assertEquals(body, deleted.json)
            assertTrue(client.records(HealthRecordKind.POINT, query.copy(recordKey = "key' OR 1=1 --")).items.isEmpty())
        }
    }

    @Test fun `read client lifetime does not own shared storage or the optional cloud workflows`() = runBlocking {
        val cache = MemoryMobvoiCloudCacheStore(); cache.commit(cloudOwner, listOf(cacheRow()))
        val reader = MobvoiCacheHealthRecordReader(cache)
        val client = HealthRecordsEngine(mobvoiCache = reader)
        client.close(); client.close()
        assertFailsWith<BackendClosedException> { client.sources() }
        assertFailsWith<BackendClosedException> { client.records(HealthRecordKind.POINT, HealthRecordQuery(cloud)) }
        assertFailsWith<BackendClosedException> { client.sleepRecords(HealthSleepQuery(local)) }
        assertEquals(body, reader.records(HealthRecordKind.POINT, HealthRecordQuery(cloud)).items.single().json)
        Unit
    }
}
