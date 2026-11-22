package dev.ticbox.companion.engine.cloud

import dev.ticbox.companion.api.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class MobvoiSportCacheTest {
    private val owner = MobvoiCloudOwner(MobvoiCloudRegion.GLOBAL, "fixture-owner", "fixture-account")
    private fun row(payload: String, requested: Boolean = true) = CloudCacheEntry(owner, CloudCacheKind.SPORT, "s1",
        dataType = "run", startMillis = 100, endMillis = 200, payload = payload, updatedAtMillis = 10, sportTracksRequested = requested)
    private val detail = """{"id":"s1","type":"run","start_at":100,"end_at":200,"track_points":[{"timestamp":1,"heart_rate":70}],"laps":[{"future":true}],"nested":{"old":1}}"""

    @Test fun `summary retains missing detail and index fields without deep merging objects`() = runBlocking {
        val store = MemoryMobvoiCloudCacheStore()
        store.commit(owner, listOf(row(detail)))
        store.commit(owner, listOf(row("""{"id":"s1","total_steps":30,"nested":{"new":2}}""", requested = false)
            .copy(dataType = null, startMillis = null, endMillis = null, updatedAtMillis = 20)))
        val saved = store.query(CloudCacheKind.SPORT, MobvoiHealthCacheQuery(owner, fromMillis = 150, recordKey = "s1")).single()
        val json = Json.parseToJsonElement(saved.payload).jsonObject
        assertEquals("run", saved.dataType)
        assertEquals(100L, saved.startMillis)
        assertEquals(200L, saved.endMillis)
        assertEquals(20L, saved.updatedAtMillis)
        assertEquals(70, json.getValue("track_points").jsonArray.single().jsonObject.getValue("heart_rate").jsonPrimitive.int)
        assertEquals(1, json.getValue("laps").jsonArray.size)
        assertEquals(setOf("new"), json.getValue("nested").jsonObject.keys)
        assertEquals(30, json.getValue("total_steps").jsonPrimitive.int)
    }

    @Test fun `noPoints placeholders do not withdraw tracks but explicit detail empty or null does`() {
        val previous = row(detail)
        for (value in listOf("[]", "null")) {
            val incoming = """{"id":"s1","track_points":$value,"laps":[]}"""
            val overview = Json.parseToJsonElement(MobvoiSportCache.merge(previous, row(incoming, requested = false)).payload).jsonObject
            assertEquals(1, overview.getValue("track_points").jsonArray.size)
            assertTrue(overview.getValue("laps").jsonArray.isEmpty())
            val fetched = Json.parseToJsonElement(MobvoiSportCache.merge(previous, row(incoming)).payload).jsonObject
            assertEquals(Json.parseToJsonElement(value), fetched["track_points"])
        }
    }

    @Test fun `explicit null index fields clear old values and other cache kinds remain replace only`() {
        val nulled = MobvoiSportCache.merge(row(detail), row("""{"id":"s1","type":null,"start_at":null,"end_at":null}""")
            .copy(dataType = null, startMillis = null, endMillis = null))
        assertNull(nulled.dataType); assertNull(nulled.startMillis); assertNull(nulled.endMillis)
        val ordinary = row("""{"only_new":true}""").copy(kind = CloudCacheKind.POINT)
        assertEquals(ordinary, MobvoiSportCache.merge(row(detail).copy(kind = CloudCacheKind.POINT), ordinary))
    }

    @Test fun `account separation and explicit cache deletion do not resurrect old sport detail`() = runBlocking {
        val store = MemoryMobvoiCloudCacheStore()
        store.commit(owner, listOf(row(detail)))
        val other = owner.copy(wwid = "another-owner")
        store.commit(other, listOf(row("""{"id":"s1"}""").copy(owner = other)))
        assertFalse(store.query(CloudCacheKind.SPORT, MobvoiHealthCacheQuery(other)).single().payload.contains("track_points"))
        store.remove(owner, CloudCacheKind.SPORT, "s1")
        store.commit(owner, listOf(row("""{"id":"s1"}""", requested = false)))
        assertFalse(store.query(CloudCacheKind.SPORT, MobvoiHealthCacheQuery(owner)).single().payload.contains("track_points"))
    }

    @Test fun `concurrent sport projections merge inside the store commit boundary`() = runBlocking {
        val store = MemoryMobvoiCloudCacheStore()
        store.commit(owner, listOf(row(detail)))
        coroutineScope {
            (1..20).map { index -> async(Dispatchers.Default) {
                store.commit(owner, listOf(row("""{"id":"s1","extension_$index":$index}""", requested = false)))
            } }.awaitAll()
        }
        val json = Json.parseToJsonElement(store.query(CloudCacheKind.SPORT, MobvoiHealthCacheQuery(owner)).single().payload).jsonObject
        assertTrue((1..20).all { "extension_$it" in json })
        assertEquals(1, json.getValue("track_points").jsonArray.size)
    }
}
