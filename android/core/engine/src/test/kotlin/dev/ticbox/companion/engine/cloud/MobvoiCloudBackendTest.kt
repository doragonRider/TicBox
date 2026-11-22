package dev.ticbox.companion.engine.cloud

import com.sun.net.httpserver.HttpServer
import dev.ticbox.companion.api.*
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class MobvoiCloudBackendTest {
    private data class Request(val method: String, val path: String, val query: Map<String, String>, val body: String, val token: String?)
    private data class Reply(val body: String, val status: Int = 200, val gzip: Boolean = false)
    private class Fixture : AutoCloseable {
        val requests = CopyOnWriteArrayList<Request>()
        var handler: (Request) -> Reply = { request -> when {
            request.path.startsWith("/v3/") || request.path == "/time-zone/save" -> Reply(LOGIN)
            request.path.endsWith("data_sources") -> Reply("""{"err_code":0,"data_sources":[{"data_source_name":"source-1","data_source_id":1,"device":{"id":"watch-1","model":"Enduro"},"future":true}]}""")
            request.path.contains("data-sets") -> Reply(DATASET)
            request.path.contains("data-sessions") -> Reply(SESSION)
            request.path.endsWith("/motion") -> Reply("""{"err_code":0,"records":[]} """)
            else -> Reply("{}", 404)
        } }
        private val executor = Executors.newCachedThreadPool { task -> Thread(task).apply { isDaemon = true } }
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@Fixture.executor
            createContext("/") { exchange ->
                try {
                    val query = exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate { part ->
                        val pair = part.split('=', limit = 2)
                        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
                    }
                    val request = Request(exchange.requestMethod, exchange.requestURI.path, query,
                        exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }, exchange.requestHeaders.getFirst("Token"))
                    requests += request
                    val reply = handler(request)
                    var bytes = reply.body.toByteArray(Charsets.UTF_8)
                    if (reply.gzip) {
                        val out = java.io.ByteArrayOutputStream(); java.util.zip.GZIPOutputStream(out).use { it.write(bytes) }; bytes = out.toByteArray()
                        exchange.responseHeaders.add("Content-Encoding", "gzip")
                    }
                    exchange.responseHeaders.add("Content-Type", "application/json; charset=UTF-8")
                    exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } catch (_: Exception) { exchange.close() }
            }
            start()
        }
        val base = "http://127.0.0.1:${server.address.port}/"
        val transport = UrlConnectionCloudHttpTransport(readTimeoutMillis = 5000)
        val credentials = MemoryMobvoiCredentialStore()
        val windows = CopyOnWriteArrayList<CloudSyncWindow>()
        private val memory = MemoryMobvoiCloudCacheStore()
        val cache = object : MobvoiCloudCacheStore by memory {
            override suspend fun commit(owner: MobvoiCloudOwner, entries: List<CloudCacheEntry>, window: CloudSyncWindow?) {
                memory.commit(owner, entries, window); window?.let { windows += it }
            }
        }
        val config = MobvoiCloudConfig(base, base, base, base, base, readAttempts = 3, retryDelayMillis = 1, profileBaseUrl = base)
        val logs = CopyOnWriteArrayList<ProtocolDiagnostic>()
        val account = MobvoiAccountEngine(transport, credentials, cache, config, DiagnosticSink { logs += it })
        val health = MobvoiCloudHealthEngine(account, transport, cache, config, DiagnosticSink { logs += it })
        suspend fun login() = account.loginPassword(MobvoiLoginIdentifier(MobvoiIdentifierKind.EMAIL, "fixture@example.test"), "password".toCharArray(), MobvoiCloudRegion.GLOBAL)
        override fun close() { health.close(); account.close(); transport.close(); server.stop(0); executor.shutdownNow() }
    }

    @Test fun `login hashes UTF8 password and restore keeps credentials out of public state and diagnostics`() = runBlocking {
        Fixture().use { f ->
            val profile = f.login()
            val sent = Json.parseToJsonElement(f.requests.single().body).jsonObject
            assertEquals("5f4dcc3b5aa765d61d8327deb882cf99", sent["password"]?.jsonPrimitive?.content)
            assertEquals("fixture@example.test", sent["email"]?.jsonPrimitive?.content)
            assertFalse("app" in sent)
            assertEquals("fixture-account", profile.owner.accountId)
            assertEquals("i18n", f.requests.single().query["region"])
            assertFalse(f.account.state.value.toString().contains("fixture-session"))
            assertTrue(f.logs.flatMap { it.details.values }.none { it.contains("fixture-session") || it.contains("5f4dcc3b") })
            val restored = MobvoiAccountEngine(f.transport, f.credentials, f.cache, f.config)
            assertEquals(profile, restored.restore()); assertEquals(1, f.requests.size)
            restored.close()
        }
    }
    @Test fun `captcha uses captured endpoint and never automatically resends a side effect GET`() = runBlocking {
        Fixture().use { f ->
            f.handler = { if (it.path == "/v3/captcha/sms") Reply("{}", 503) else Reply(LOGIN) }
            assertFailsWith<MobvoiCloudException> { f.account.requestCaptcha(MobvoiLoginIdentifier(MobvoiIdentifierKind.PHONE, "+1000"), "zh-CN") }
            assertEquals(1, f.requests.count { it.path == "/v3/captcha/sms" })
            f.account.loginCaptcha("+1000", "123456")
            val request = f.requests.last()
            assertEquals("/v3/login/captcha", request.path)
            assertEquals("123456", Json.parseToJsonElement(request.body).jsonObject["captcha_value"]?.jsonPrimitive?.content)
        }
    }
    @Test fun `profile update preserves current fields re reads server result and timezone uses captured body`() = runBlocking {
        Fixture().use { f ->
            f.login(); val default = f.handler
            f.handler = { req -> if (req.path == "/v3/info/session-id") Reply(LOGIN.replace("Fixture", "Server normalized")) else default(req) }
            val profile = f.account.updateProfile(MobvoiProfileUpdate(nickname = "requested"))
            assertEquals("Server normalized", profile.nickname)
            val body = Json.parseToJsonElement(f.requests.first { it.path == "/v3/info/update" }.body).jsonObject
            assertEquals("requested", body["nickname"]?.jsonPrimitive?.content)
            assertEquals("65", body["weight"]?.jsonPrimitive?.content)
            assertEquals("170", body["height"]?.jsonPrimitive?.content)
            assertEquals("fixture-session", body["session_id"]?.jsonPrimitive?.content)
            f.account.saveTimeZone("Pacific/Chatham")
            val zone = Json.parseToJsonElement(f.requests.last().body).jsonObject
            assertEquals("Pacific/Chatham", zone["timeZone"]?.jsonPrimitive?.content)
            assertEquals("fixture-account", zone["accountId"]?.jsonPrimitive?.content)
        }
    }
    @Test fun `health HTTP fixtures preserve zero values timezone unknown types and deduplicate window retries`() = runBlocking {
        Fixture().use { f ->
            val owner = f.login().owner
            val report = f.health.sync(MobvoiHealthSyncRequest(0, 2000))
            assertFalse(report.partial)
            val points = f.health.points(MobvoiHealthCacheQuery(owner)).items
            assertEquals(1, points.size); assertEquals(0.0, points.single().floatingValue)
            assertEquals(0L, points.single().integerValue)
            assertEquals("Asia/Shanghai", points.single().timeZone)
            assertEquals("future_sensor", points.single().dataType)
            assertEquals("{\"v\":1}", points.single().stringValue)
            f.health.sync(MobvoiHealthSyncRequest(0, 2000))
            assertEquals(1, f.health.points(MobvoiHealthCacheQuery(owner)).items.size)
            assertEquals(1, f.health.sessions(MobvoiHealthCacheQuery(owner)).items.size)
            assertTrue(f.requests.filter { it.path.startsWith("/v1/") }.all { it.token == "fixture-session" })
            val request = f.requests.first { it.path.contains("data-sets") }
            assertTrue(request.path.contains("derived:com.mobvoi.fitness"))
            assertEquals(1, f.health.cachedOwners().size)
        }
    }
    @Test fun `point and session deletion markers hide records without discarding tombstones`() = runBlocking {
        Fixture().use { f ->
            val owner = f.login().owner
            f.health.sync(MobvoiHealthSyncRequest(0, 2000, sports = false))
            val default = f.handler
            f.handler = { req -> when {
                req.path.contains("data-sets") -> Reply(DATASET.replace("\"deleted\":0", "\"deleted\":1"))
                req.path.contains("data-sessions") -> Reply(SESSION.replace("\"deleted\":0", "\"deleted\":1"))
                else -> default(req)
            } }
            f.health.sync(MobvoiHealthSyncRequest(0, 2000, sports = false))
            assertTrue(f.health.points(MobvoiHealthCacheQuery(owner)).items.isEmpty())
            assertTrue(f.health.sessions(MobvoiHealthCacheQuery(owner)).items.isEmpty())
            assertTrue(f.health.points(MobvoiHealthCacheQuery(owner, includeDeleted = true)).items.single().deleted)
            assertTrue(f.health.sessions(MobvoiHealthCacheQuery(owner, includeDeleted = true)).items.single().deleted)
        }
    }
    @Test fun `sports download pages overlap dedup track details and delete only after server success`() = runBlocking {
        Fixture().use { f ->
            val owner = f.login().owner
            val default = f.handler
            f.handler = { req -> when {
                !req.path.endsWith("/motion") -> default(req)
                req.method == "DELETE" -> Reply("{\"err_code\":0}")
                "motionIds" in req.query -> Reply("""{"err_code":0,"records":[{"id":"r1","start_at":1000,"track_points":[{"timestamp":5,"gps_point":"1.2,3.4","heart_rate":80}]}]}""")
                req.query["timeBefore"] == "4000" -> Reply("""{"err_code":0,"records":[{"id":"r3","start_at":3000},{"id":"r2","start_at":2000}]}""")
                req.query["timeBefore"] == "2000" -> Reply("""{"err_code":0,"records":[{"id":"r2","start_at":2000},{"id":"r1","start_at":1000}]}""")
                else -> Reply("""{"err_code":0,"records":[]}""")
            } }
            val report = f.health.sync(MobvoiHealthSyncRequest(0, 4000, dataSets = false, sessions = false, sportPageSize = 2))
            assertEquals(3, report.sportCount)
            assertEquals(3, f.health.sports(MobvoiHealthCacheQuery(owner)).items.size)
            val detailed = f.health.downloadSportRecord("r1")!!
            assertEquals("1.2,3.4", detailed.tracks.single().gpsPoint)
            assertEquals(80, detailed.tracks.single().heartRate)
            assertTrue(f.requests.filter { it.path.endsWith("/motion") }.all { it.query["sessionId"] == "fixture-session" })
            f.health.deleteSportRecord("r1")
            assertEquals(2, f.health.sports(MobvoiHealthCacheQuery(owner)).items.size)
        }
    }
    @Test fun `sport summary HTTP refresh retains downloaded detail while explicit detail empty clears it`() = runBlocking {
        Fixture().use { f ->
            val owner = f.login().owner
            val normal = f.handler
            var emptyDetail = false
            f.handler = { request -> when {
                !request.path.endsWith("/motion") -> normal(request)
                "motionIds" in request.query -> Reply(if (emptyDetail) """{"err_code":0,"records":[{"id":"s1","track_points":[]}]}"""
                    else """{"err_code":0,"records":[{"id":"s1","type":"run","start_at":1000,"track_points":[{"heart_rate":80}],"laps":[{"opaque":true}]}]}""")
                else -> Reply("""{"err_code":0,"records":[{"id":"s1","start_at":1000,"total_steps":12,"track_points":[]}]}""")
            } }
            assertEquals(80, f.health.downloadSportRecord("s1")!!.tracks.single().heartRate)
            f.health.sync(MobvoiHealthSyncRequest(0, 2000, dataSets = false, sessions = false, includeSportTracks = false))
            assertEquals("true", f.requests.last().query["noPoints"])
            assertEquals(80, f.health.sports(MobvoiHealthCacheQuery(owner)).items.single().tracks.single().heartRate)
            val raw = MobvoiCacheHealthRecordReader(f.cache).records(HealthRecordKind.SPORT,
                HealthRecordQuery(HealthRecordSource.MobvoiCloud(owner), recordKey = "s1")).items.single()
            assertTrue("laps" in Json.parseToJsonElement(raw.json).jsonObject)
            emptyDetail = true
            assertTrue(f.health.downloadSportRecord("s1")!!.tracks.isEmpty())
            assertTrue(f.health.sports(MobvoiHealthCacheQuery(owner)).items.single().tracks.isEmpty())
        }
    }

    @Test fun `read retries transient status but mutation failure leaves cache intact`() = runBlocking {
        Fixture().use { f ->
            val owner = f.login().owner; val default = f.handler; var attempts = 0
            f.handler = { req -> if (req.path.endsWith("data_sources") && ++attempts < 3) Reply("{}", 503) else default(req) }
            f.health.sync(MobvoiHealthSyncRequest(0, 2000, dataSets = false, sessions = false, sports = false))
            assertEquals(3, attempts)
            f.handler = { req -> when {
                req.path.endsWith("/motion") && req.method == "DELETE" -> Reply("{}", 503)
                req.path.endsWith("/motion") -> Reply("""{"err_code":0,"records":[{"id":"r1","start_at":1000}]}""")
                else -> default(req)
            } }
            f.health.downloadSportRecord("r1")
            assertFailsWith<MobvoiCloudException> { f.health.deleteSportRecord("r1") }
            assertEquals(1, f.requests.count { it.method == "DELETE" })
            assertEquals(1, f.health.sports(MobvoiHealthCacheQuery(owner)).items.size)
        }
    }
    @Test fun `server expiration clears protected session with no invented refresh exchange`() = runBlocking {
        Fixture().use { f ->
            f.login(); val default = f.handler
            f.handler = { if (it.path.endsWith("data_sources")) Reply("{\"err_code\":400100}") else default(it) }
            assertFailsWith<MobvoiCloudException> { f.health.sync(MobvoiHealthSyncRequest(0, 2000)) }
            assertEquals(MobvoiAccountPhase.EXPIRED, f.account.state.value.phase)
            assertNull(f.credentials.load())
            assertTrue(f.requests.none { it.path.contains("refresh") })
        }
    }
    @Test fun `logout cancels an in flight HTTP read and prevents late cache writes`() = runBlocking {
        Fixture().use { f ->
            val owner = f.login().owner; val started = CompletableDeferred<Unit>(); val release = java.util.concurrent.CountDownLatch(1)
            val default = f.handler
            f.handler = { req -> if (req.path.endsWith("data_sources")) { started.complete(Unit); release.await(3, TimeUnit.SECONDS); default(req) } else default(req) }
            val syncing = async { f.health.sync(MobvoiHealthSyncRequest(0, 2000)) }
            withTimeout(3000) { started.await() }
            f.account.logout(revokeRemote = false)
            assertFailsWith<CancellationException> { syncing.await() }
            release.countDown()
            assertEquals(MobvoiAccountPhase.SIGNED_OUT, f.account.state.value.phase)
            assertNull(f.credentials.load())
            assertTrue(f.health.sources(owner).isEmpty())
        }
    }
    @Test fun `remote logout failure still leaves local account signed out`() = runBlocking {
        Fixture().use { f ->
            f.login(); f.handler = { Reply("{}", 503) }
            f.account.logout()
            assertEquals(MobvoiAccountPhase.SIGNED_OUT, f.account.state.value.phase)
            assertNull(f.credentials.load())
            assertEquals(1, f.requests.count { it.path == "/v3/logout" })
        }
    }
    @Test fun `gzip JSON and missing optional profile fields are tolerated`() = runBlocking {
        Fixture().use { f ->
            f.handler = { req -> if (req.path.contains("login")) Reply("""{"status":"success","result":{"wwid":"minimal","session_id":"fixture-session","future":123}}""", gzip = true) else Reply("{\"err_code\":0,\"data_sources\":[]}", gzip = true) }
            val profile = f.login()
            assertNull(profile.nickname); assertNull(profile.owner.accountId)
            assertFalse(f.health.sync(MobvoiHealthSyncRequest(0, 1000, dataSets = false, sessions = false, sports = false)).partial)
        }
    }
    @Test fun `missing and mixed health lists keep usable rows while withholding completed windows`() = runBlocking {
        Fixture().use { f ->
            val owner = f.login().owner; val default = f.handler
            f.handler = { req -> when {
                req.path.endsWith("data_sources") -> Reply("{\"err_code\":0}")
                req.path.contains("data-sets") -> Reply(DATASET.replace("\"points\":[", "\"points\":[null,"))
                req.path.contains("data-sessions") -> Reply(SESSION.replace("\"data_sessions\":[", "\"data_sessions\":[false,"))
                req.path.endsWith("/motion") -> Reply("{\"err_code\":0,\"records\":{}}")
                else -> default(req)
            } }
            val report = f.health.sync(MobvoiHealthSyncRequest(0, 2000))
            assertTrue(report.partial); assertEquals(MobvoiHealthSyncPhase.PARTIAL, f.health.state.value.phase)
            assertEquals(4, report.issues.size)
            assertEquals(1, f.health.points(MobvoiHealthCacheQuery(owner)).items.size)
            assertEquals(1, f.health.sessions(MobvoiHealthCacheQuery(owner)).items.size)
            assertTrue(f.windows.isEmpty())
            f.handler = { req -> when {
                req.path.endsWith("data_sources") -> Reply("{\"err_code\":0,\"data_sources\":[]}")
                req.path.contains("data-sets") -> Reply("{\"err_code\":0,\"data_sets\":[]}")
                req.path.contains("data-sessions") -> Reply("{\"err_code\":0,\"data_sessions\":[]}")
                req.path.endsWith("/motion") -> Reply("{\"err_code\":0,\"records\":[]}")
                else -> default(req)
            } }
            assertFalse(f.health.sync(MobvoiHealthSyncRequest(0, 2000)).partial)
            assertEquals(3, f.windows.size)
            assertEquals(1, f.health.points(MobvoiHealthCacheQuery(owner)).items.size)
        }
    }
    @Test fun `health profile and extensible settings use separate captured service and protected session`() = runBlocking {
        Fixture().use { f ->
            val owner = f.login().owner; val default = f.handler
            f.handler = { req -> when {
                req.path == "/user/userInfo" -> Reply("""{"err_code":0,"user_info":{"birthday":"1990.01.01","gender":2,"height":172.5,"weight":60,"unit":"metric","goal_today_step":10000,"expected_sleep_time":1320,"future":true}}""")
                req.path == "/v1/users/setting/base" && req.method == "POST" -> Reply("{\"err_code\":0}")
                req.path == "/v1/users/setting/base" -> Reply("""{"err_code":0,"list":[{"type":"rri","value":"1"},{"type":"future_type","value":"opaque"}]}""")
                else -> default(req)
            } }
            val profile = f.health.refreshUserProfile()
            assertEquals(2, profile.gender); assertEquals("172.5", profile.height); assertEquals(10000, profile.goalTodayStep)
            assertEquals(profile, f.health.cachedUserProfile(owner))
            assertEquals(2, f.account.state.value.profile?.healthGender); assertEquals(1, f.account.state.value.profile?.sex)
            assertEquals(listOf(MobvoiHealthSetting("rri", "1"), MobvoiHealthSetting("future_type", "opaque")), f.health.readUserSettings("rri"))
            f.health.writeUserSetting(MobvoiHealthSetting("rri", "0"))
            assertEquals("fixture-user", f.requests.first { it.path == "/user/userInfo" }.query["wwid"])
            assertEquals("fixture-session", f.requests.first { it.path == "/user/userInfo" }.token)
            val body = Json.parseToJsonElement(f.requests.last().body).jsonObject
            assertEquals("rri", body["type"]?.jsonPrimitive?.content); assertEquals("0", body["value"]?.jsonPrimitive?.content)
            f.account.logout(revokeRemote = false)
            assertEquals(profile, f.health.cachedUserProfile(owner))
        }
    }
    @Test fun `credential deletion failure reports storage without restoring signed in state`(): Unit = runBlocking {
        Fixture().use { f ->
            val store = object : MobvoiCredentialStore by f.credentials { override suspend fun clear() { error("fixture disk unavailable") } }
            val account = MobvoiAccountEngine(f.transport, store, f.cache, f.config)
            try {
                account.loginPassword(MobvoiLoginIdentifier(MobvoiIdentifierKind.EMAIL, "fixture@example.test"), "password".toCharArray())
                val failure = assertFailsWith<MobvoiCloudException> { account.logout(revokeRemote = false) }
                assertEquals(MobvoiCloudIssueKind.STORAGE, failure.issue.kind)
                assertEquals(MobvoiAccountPhase.SIGNED_OUT, account.state.value.phase)
                assertEquals(MobvoiCloudIssueKind.STORAGE, account.state.value.lastIssue?.kind)
                assertFailsWith<MobvoiCloudException> { account.refreshProfile() }
            } finally { account.close() }
        }
    }
    @Test fun `credential decoder failures cannot expose protected plaintext through public exceptions`() = runBlocking {
        Fixture().use { f ->
            val store = object : MobvoiCredentialStore by f.credentials {
                override suspend fun load(): MobvoiCloudCredential? = error("decoder input contains fixture-session")
            }
            val account = MobvoiAccountEngine(f.transport, store, f.cache, f.config)
            try {
                val failure = assertFailsWith<MobvoiCloudException> { account.restore() }
                assertEquals(MobvoiCloudIssueKind.STORAGE, failure.issue.kind)
                assertFalse(failure.toString().contains("fixture-session")); assertNull(failure.cause)
                assertFalse(account.state.value.toString().contains("fixture-session"))
                assertTrue(f.requests.isEmpty())
            } finally { account.close() }
        }
    }
    @Test fun `server expiration survives unavailable credential file deletion`() = runBlocking {
        Fixture().use { f ->
            val store = object : MobvoiCredentialStore by f.credentials { override suspend fun clear() { error("fixture disk unavailable") } }
            val account = MobvoiAccountEngine(f.transport, store, f.cache, f.config)
            val health = MobvoiCloudHealthEngine(account, f.transport, f.cache, f.config)
            try {
                account.loginPassword(MobvoiLoginIdentifier(MobvoiIdentifierKind.EMAIL, "fixture@example.test"), "password".toCharArray())
                f.handler = { Reply("{\"err_code\":400100}") }
                val failure = assertFailsWith<MobvoiCloudException> { health.sync(MobvoiHealthSyncRequest(0, 2000)) }
                assertEquals(MobvoiCloudIssueKind.EXPIRED, failure.issue.kind)
                assertEquals(MobvoiAccountPhase.EXPIRED, account.state.value.phase)
            } finally { health.close(); account.close() }
        }
    }
    @Test fun `transport close cancels queued and running reads with bounded workers`() = runBlocking {
        Fixture().use { f -> supervisorScope {
            val started = java.util.concurrent.CountDownLatch(4); val release = java.util.concurrent.CountDownLatch(1)
            f.handler = { started.countDown(); release.await(5, TimeUnit.SECONDS); Reply("{}") }
            val reads = (1..9).map { async { f.transport.execute(CloudHttpRequest("GET", f.base + "waiting")) } }
            withTimeout(3000) { withContext(Dispatchers.IO) { assertTrue(started.await(2, TimeUnit.SECONDS)) } }
            f.transport.close()
            withTimeout(2000) { reads.forEach { assertFailsWith<CancellationException> { it.await() } } }
            release.countDown()
        } }
    }
    @Test fun `watch account bridge uses correct direction and seconds birthday with explicit timezone`() = runBlocking {
        Fixture().use { f ->
            val bridge = MobvoiWatchAccountBridge(f.account)
            val host = MobvoiCompanionHostInfo("FixturePhone", "14", "1.0", "1", "fixture-device")
            val sent = mutableListOf<Pair<String, ByteArray>>()
            bridge.handleLoginRequest(host) { path, bytes -> sent += path to bytes }
            assertTrue(f.account.state.value.watchLoginRequested); assertTrue(sent.isEmpty())
            f.login()
            bridge.handleLoginRequest(host) { path, bytes -> sent += path to bytes }
            assertEquals("/companion/send_companion_info", sent.single().first)
            assertEquals("fixture-session", Json.parseToJsonElement(sent.single().second.toString(Charsets.UTF_8)).jsonObject["sessionId"]?.jsonPrimitive?.content)
            val updated = bridge.acceptCompactProfile("""{"b":0,"h":171.5,"u":"metric","g":2,"t":999} """.toByteArray(), java.time.ZoneId.of("America/Los_Angeles"))!!
            assertEquals("1969.12.31", updated.birthday); assertEquals("171.5", updated.height)
            assertEquals("65", updated.weight); assertEquals("metric", updated.unit)
            assertEquals(2, updated.healthGender); assertEquals(1, updated.sex)
            assertEquals(updated, f.credentials.load()?.profile)
            bridge.publishCompanionInfo(host) { _, bytes ->
                assertEquals("FEMALE", Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject["sex"]?.jsonPrimitive?.content)
            }
            f.account.loginCaptcha("+1000", "123456", MobvoiCloudRegion.CHINA)
            val domestic = bridge.acceptCompactProfile("""{"g":0}""".toByteArray())!!
            assertEquals(0, domestic.healthGender); assertEquals(1, domestic.sex)
            bridge.publishCompanionInfo(host) { _, bytes ->
                assertEquals("FEMALE", Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject["sex"]?.jsonPrimitive?.content)
            }
        }
    }
    companion object {
        private const val LOGIN = """{"status":"success","err_code":0,"result":{"session_id":"fixture-session","wwid":"fixture-user","email":"fixture@example.test","info":{"account_id":"fixture-account","nickname":"Fixture","height":"170","weight":"65","sex":1,"pii":true}}}"""
        private const val DATASET = """{"err_code":0,"data_sets":[{"data_source_name":"source-1","time_zone":"Asia/Shanghai","points":[{"data_type":"future_sensor","start_time_millis":1000,"end_time_millis":1001,"deleted":0,"fp_val":0,"int_val":0,"string_val":"{\"v\":1}","future":true}]}]}"""
        private const val SESSION = """{"err_code":0,"data_sessions":[{"data_source_name":"source-1","session_id":"s1","activity_type":"future_activity","start_time_ms":1000,"end_time_ms":1200,"time_zone":"Unknown/Preserved","deleted":0,"points":[]}]}"""
    }
}
