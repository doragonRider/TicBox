package dev.ticbox.companion.engine.cloud

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.health.HealthRecordsEngine
import dev.ticbox.companion.engine.privacy.ConfiguredCloudHttpTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/** Phone-cache fixtures only: no watch Provider, new ingestion API, login or vendor requests. */
class MobvoiRawHealthReadTest {
    private val owner = MobvoiCloudOwner(MobvoiCloudRegion.GLOBAL, "fixture-owner", "fixture-account")
    private val origin = HealthRecordSource.MobvoiCloud(owner)
    private val other = owner.copy(wwid = "other-owner")
    private class Fixture : AutoCloseable {
        val cache = MemoryMobvoiCloudCacheStore()
        var transportCreations = 0
        val transport = ConfiguredCloudHttpTransport({ CloudPrivacySettings() }) {
            transportCreations++
            CloudHttpTransport { error("Local reads must not execute HTTP") }
        }
        val logs = mutableListOf<ProtocolDiagnostic>()
        val account = MobvoiAccountEngine(transport, MemoryMobvoiCredentialStore(), cache)
        val health = MobvoiCloudHealthEngine(account, transport, cache, diagnostics = DiagnosticSink { logs += it })
        val records = HealthRecordsEngine(mobvoiCache = MobvoiCacheHealthRecordReader(cache))
        override fun close() { records.close(); health.close(); account.close(); transport.close() }
    }
    private fun row(kind: CloudCacheKind = CloudCacheKind.POINT, key: String = "fixture-key", payload: String = "{}",
        from: Long? = 100, to: Long? = 200, deleted: Boolean = false, source: String? = "watch-source", type: String? = "future_metric") =
        CloudCacheEntry(owner, kind, key, source, type, from, to, "Unrecognized/Zone", deleted, payload, 789)

    @Test fun `every raw category is readable while signed out with all cloud settings disabled`() = runBlocking {
        Fixture().use { f ->
            val categories = listOf(
                CloudCacheKind.SOURCE to HealthRecordKind.DATA_SOURCE,
                CloudCacheKind.POINT to HealthRecordKind.POINT,
                CloudCacheKind.SESSION to HealthRecordKind.SESSION,
                CloudCacheKind.SPORT to HealthRecordKind.SPORT,
                CloudCacheKind.PROFILE to HealthRecordKind.PROFILE,
                CloudCacheKind.SETTING to HealthRecordKind.SETTING,
            )
            f.cache.commit(owner, categories.map { (kind, _) -> row(kind, payload = """{"extension":{"value":"opaque-${kind.name}"}}""") })
            assertEquals(listOf(owner), f.health.cachedOwners())
            for ((cacheKind, publicKind) in categories) {
                val page = f.records.records(publicKind, HealthRecordQuery(origin))
                assertEquals("""{"extension":{"value":"opaque-${cacheKind.name}"}}""", page.items.single().json)
                assertEquals(publicKind, page.items.single().kind)
                assertNull(page.nextOffset)
            }
            assertEquals(MobvoiAccountPhase.SIGNED_OUT, f.account.state.value.phase)
            assertEquals(MobvoiHealthSyncPhase.IDLE, f.health.state.value.phase)
            assertEquals(0, f.transportCreations)
        }
    }

    @Test fun `raw values preserve mixed vectors zero null nested fields and integer precision`() = runBlocking {
        Fixture().use { f ->
            val payload = """{"data_type":"vo2max","string_val":"42,Running,1700000000000,record-A","fp_val":0.0,"int_val":0,"optional":null,"future":{"large":9223372036854775807,"values":["1.5,bad,,3",true,null]}}"""
            f.cache.commit(owner, listOf(row(payload = payload, type = "vo2max")))
            val record = f.records.records(HealthRecordKind.POINT, HealthRecordQuery(origin)).items.single()
            assertEquals(payload, record.json)
            val json = Json.parseToJsonElement(record.json).jsonObject
            assertEquals("42,Running,1700000000000,record-A", json.getValue("string_val").jsonPrimitive.content)
            assertEquals("9223372036854775807", json.getValue("future").jsonObject.getValue("large").jsonPrimitive.content)
            assertEquals(JsonNull, json["optional"])
            assertEquals("Unrecognized/Zone", record.timeZone)
            assertEquals(100L, record.startMillis)
            assertEquals(200L, record.endMillis)
            assertEquals(789L, record.updatedAtMillis)
            assertTrue(f.logs.isEmpty())
            assertFalse(record.toString().contains("record-A"))
            assertFalse(record.toString().contains(owner.wwid))
        }
    }

    @Test fun `session children and full sport details remain accessible without typed projections`() = runBlocking {
        Fixture().use { f ->
            val session = """{"activity_type":"auto_sleep","points":[{"data_type":"sleep_motion","string_val":"9,10","future_stage":17},{"data_type":"future_sensor","unknown":{"v":0}}],"future_session":[1,2]}"""
            val sport = """{"id":"ride","track_points":[{"timestamp":12,"gps_point":"1,2","future_track":[3,4]}],"laps":[{"opaque":true}],"future_summary":0}"""
            f.cache.commit(owner, listOf(row(CloudCacheKind.SESSION, payload = session), row(CloudCacheKind.SPORT, key = "ride", payload = sport)))
            assertEquals(session, f.records.records(HealthRecordKind.SESSION, HealthRecordQuery(origin)).items.single().json)
            val detail = f.records.records(HealthRecordKind.SPORT, HealthRecordQuery(origin, recordKey = "ride")).items.single()
            assertEquals(sport, detail.json)
            assertEquals(0, f.transportCreations)
        }
    }

    @Test fun `exact keys intersect with kind owner and existing filters without crossing accounts`() = runBlocking {
        Fixture().use { f ->
            val key = "key' OR 1=1 --"
            f.cache.commit(owner, listOf(row(key = key), row(key = "another"), row(CloudCacheKind.SESSION, key = key)))
            f.cache.commit(other, listOf(row(key = key).copy(owner = other, payload = "other account")))
            val query = HealthRecordQuery(origin, dataType = "future_metric", sourceName = "watch-source", recordKey = key)
            assertEquals(listOf(key), f.records.records(HealthRecordKind.POINT, query).items.map { it.key })
            assertTrue(f.records.records(HealthRecordKind.POINT, query.copy(sourceName = "other-source")).items.isEmpty())
            assertTrue(f.records.records(HealthRecordKind.POINT, query.copy(dataType = "other-type")).items.isEmpty())
            assertTrue(f.records.records(HealthRecordKind.POINT, query.copy(recordKey = "missing")).items.isEmpty())
            assertEquals("other account", f.records.records(HealthRecordKind.POINT, query.copy(source = HealthRecordSource.MobvoiCloud(other))).items.single().json)
        }
    }

    @Test fun `raw pagination uses stable time and key order including equal timestamps`() = runBlocking {
        Fixture().use { f ->
            f.cache.commit(owner, listOf(row(key = "b"), row(key = "a"), row(key = "c", from = 300, to = 400)))
            var query = HealthRecordQuery(origin, limit = 1)
            val keys = mutableListOf<String>()
            do {
                val page = f.records.records(HealthRecordKind.POINT, query)
                keys += page.items.map { it.key }
                query = query.copy(offset = page.nextOffset ?: 0)
            } while (page.nextOffset != null)
            assertEquals(listOf("a", "b", "c"), keys)
        }
    }

    @Test fun `overlap boundaries missing timestamps and tombstones keep existing read semantics`() = runBlocking {
        Fixture().use { f ->
            f.cache.commit(owner, listOf(row(key = "before", from = 0, to = 99), row(key = "touch", from = 0, to = 100),
                row(key = "inside", from = 150, to = 160), row(key = "after", from = 201, to = 300),
                row(key = "unknown", from = null, to = null), row(key = "deleted", deleted = true)))
            val query = HealthRecordQuery(origin, fromMillis = 100, toMillis = 200)
            assertEquals(setOf("touch", "inside", "unknown"), f.records.records(HealthRecordKind.POINT, query).items.map { it.key }.toSet())
            val tombstone = f.records.records(HealthRecordKind.POINT, query.copy(includeDeleted = true, recordKey = "deleted")).items.single()
            assertTrue(tombstone.deleted)
            assertEquals("{}", tombstone.json)
        }
    }

    @Test fun `uninterpretable cached payload is returned intact for frontend best effort decoding`() = runBlocking {
        Fixture().use { f ->
            val payload = "{ unrecognized-vendor-payload"
            f.cache.commit(owner, listOf(row(payload = payload)))
            assertEquals(payload, f.records.records(HealthRecordKind.POINT, HealthRecordQuery(origin)).items.single().json)
            assertTrue(f.logs.isEmpty())
            assertEquals(0, f.transportCreations)
        }
    }

    @Test fun `typed convenience and raw reads share storage with independent lifetime`() = runBlocking {
        Fixture().use { f ->
            val payload = """{"data_type":"heart_rate","start_time_millis":100,"end_time_millis":200,"string_val":"70,other","future":true}"""
            f.cache.commit(owner, listOf(row(payload = payload, type = "heart_rate")))
            val query = HealthRecordQuery(origin)
            assertEquals("70,other", f.health.points(MobvoiHealthCacheQuery(owner)).items.single().stringValue)
            assertEquals(payload, f.records.records(HealthRecordKind.POINT, query).items.single().json)
            f.health.close()
            assertEquals(payload, f.records.records(HealthRecordKind.POINT, query).items.single().json)
            f.records.close()
            assertFailsWith<BackendClosedException> { f.records.records(HealthRecordKind.POINT, query) }
            Unit
        }
    }
}
