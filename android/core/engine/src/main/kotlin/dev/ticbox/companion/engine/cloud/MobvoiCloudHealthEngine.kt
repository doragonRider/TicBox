package dev.ticbox.companion.engine.cloud

import dev.ticbox.companion.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

class MobvoiCloudHealthEngine(
    private val account: MobvoiAccountEngine,
    transport: CloudHttpTransport,
    private val cache: MobvoiCloudCacheStore,
    private val config: MobvoiCloudConfig = MobvoiCloudConfig(),
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
) : MobvoiCloudHealthClient {
    private val http = CloudHttp(transport, config, diagnostics)
    private val syncMutex = Mutex()
    private val stateValue = MutableStateFlow(MobvoiHealthSyncState())
    override val state = stateValue.asStateFlow()
    @Volatile private var active: Job? = null
    @Volatile private var closed = false

    override suspend fun sync(request: MobvoiHealthSyncRequest): MobvoiHealthSyncReport = syncMutex.withLock { coroutineScope {
        alive(); require(request.fromMillis <= request.toMillis && request.sportPageSize in 1..1000)
        active = currentCoroutineContext().job
        try {
            account.withSession { lease ->
                val owner = lease.credential.profile.owner
                stateValue.value = MobvoiHealthSyncState(MobvoiHealthSyncPhase.SYNCING, owner)
                var sources = 0; var points = 0; var sessions = 0; var sports = 0
                val issues = mutableListOf<MobvoiCloudIssue>()
                suspend fun operation(name: String, block: suspend () -> Unit) {
                    stateValue.value = stateValue.value.copy(operation = name)
                    try { block() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        val failure = (error as? MobvoiCloudException)?.issue ?: MobvoiCloudIssue(MobvoiCloudIssueKind.STORAGE, name)
                        issues += failure; log("sync_operation_failed", name, failure)
                        if (failure.kind == MobvoiCloudIssueKind.EXPIRED) throw error
                    }
                    stateValue.value = stateValue.value.copy(sourceCount = sources, pointCount = points, sessionCount = sessions, sportCount = sports)
                }
                operation("sources") {
                    val response = read(lease, "sources", "/v1/users/me/data_sources")
                    val entries = collection(response, "data_sources", "sources", issues).map { raw ->
                        val value = MobvoiCloudJson.source(raw)
                        entry(owner, CloudCacheKind.SOURCE, value.name.ifEmpty { CloudIdentity.hash(listOf(MobvoiCloudJson.canonical(raw))) }, raw, source = value.name)
                    }
                    account.commitIfCurrent(lease) { cache.commit(owner, entries) }; sources = entries.size
                }
                val sourcePath = "/v1/users/me/data-sources/${encodeComponent(request.sourceName)}"
                val range = "${request.fromMillis}-${request.toMillis}"
                if (request.dataSets) operation("data_sets") {
                    val issueCount = issues.size
                    val response = read(lease, "data_sets", "$sourcePath/data-sets/$range", mapOf("data_type" to request.dataType))
                    val entries = collection(response, "data_sets", "data_sets", issues).flatMap { set ->
                        val source = set.text("data_source_name") ?: request.sourceName
                        val zone = set.text("time_zone")
                        collection(set, "points", "data_set_points", issues).map { raw ->
                            val value = MobvoiCloudJson.point(raw, source, zone)
                            entry(owner, CloudCacheKind.POINT, value.key, raw, source, value.dataType, value.startMillis, value.endMillis, zone, value.deleted)
                        }
                    }
                    val window = CloudSyncWindow(CloudCacheKind.POINT, request.fromMillis, request.toMillis).takeIf { issues.size == issueCount }
                    account.commitIfCurrent(lease) { cache.commit(owner, entries, window) }; points = entries.size
                }
                if (request.sessions) operation("sessions") {
                    val issueCount = issues.size
                    val response = read(lease, "sessions", "$sourcePath/data-sessions/$range", mapOf("activity_type" to request.activityType))
                    val entries = collection(response, "data_sessions", "sessions", issues).map { raw ->
                        val normalized = if (raw.text("data_source_name") == null) JsonObject(raw + ("data_source_name" to JsonPrimitive(request.sourceName))) else raw
                        val value = MobvoiCloudJson.session(normalized)
                        entry(owner, CloudCacheKind.SESSION, value.key, normalized, value.sourceName, value.activityType, value.startMillis, value.endMillis, value.timeZone, value.deleted)
                    }
                    val window = CloudSyncWindow(CloudCacheKind.SESSION, request.fromMillis, request.toMillis).takeIf { issues.size == issueCount }
                    account.commitIfCurrent(lease) { cache.commit(owner, entries, window) }; sessions = entries.size
                }
                if (request.sports) operation("sports") {
                    val issueCount = issues.size
                    var before = request.toMillis
                    val seen = linkedSetOf<String>()
                    var completed = false
                    repeat(config.maxSportPages) {
                        if (completed) return@repeat
                        val response = readSport(lease, "sports", query = mapOf("timeBefore" to before.toString(), "timeAfter" to request.fromMillis.toString(),
                            "size" to request.sportPageSize.toString(), "motionType" to request.sportType, "noPoints" to (!request.includeSportTracks).toString()))
                        val records = collection(response, "records", "sports", issues)
                        val entries = records.map { raw ->
                            val value = MobvoiCloudJson.sport(raw)
                            entry(owner, CloudCacheKind.SPORT, value.key, raw, type = value.type, from = value.startMillis, to = value.endMillis)
                                .copy(sportTracksRequested = request.includeSportTracks)
                        }
                        val fresh = entries.filter { seen.add(it.key) }
                        account.commitIfCurrent(lease) { cache.commit(owner, fresh) }; sports += fresh.size
                        if (records.size < request.sportPageSize) completed = true else {
                            val next = entries.mapNotNull { it.startMillis }.minOrNull()
                            if (next == null || next >= before || fresh.isEmpty()) {
                                issues += MobvoiCloudIssue(MobvoiCloudIssueKind.MALFORMED_RESPONSE, "sport_pagination_stalled")
                                completed = true
                            } else before = next // Overlap the boundary and deduplicate; do not guess a hidden page token.
                        }
                    }
                    if (!completed) issues += MobvoiCloudIssue(MobvoiCloudIssueKind.RESOURCE_LIMIT, "sport_page_budget")
                    if (issues.size == issueCount) account.commitIfCurrent(lease) {
                        cache.commit(owner, emptyList(), CloudSyncWindow(CloudCacheKind.SPORT, request.fromMillis, request.toMillis))
                    }
                }
                stateValue.value = stateValue.value.copy(phase = if (issues.isEmpty()) MobvoiHealthSyncPhase.COMPLETED else MobvoiHealthSyncPhase.PARTIAL,
                    operation = null, lastIssue = issues.lastOrNull())
                MobvoiHealthSyncReport(owner, sources, points, sessions, sports, issues.isNotEmpty(), issues.toList())
            }
        } catch (cancelled: CancellationException) {
            if (!closed) stateValue.value = stateValue.value.copy(phase = MobvoiHealthSyncPhase.CANCELLED, operation = null,
                lastIssue = MobvoiCloudIssue(MobvoiCloudIssueKind.CANCELLED, "sync"))
            throw cancelled
        } catch (failure: Exception) {
            if (!closed) stateValue.value = stateValue.value.copy(phase = MobvoiHealthSyncPhase.FAILED, operation = null,
                lastIssue = (failure as? MobvoiCloudException)?.issue ?: MobvoiCloudIssue(MobvoiCloudIssueKind.NETWORK, "sync"))
            throw failure
        } finally { active = null }
    } }
    override fun cancelSync() { active?.cancel(CancellationException("Cloud sync cancelled")) }
    override suspend fun cachedOwners(): List<MobvoiCloudOwner> { alive(); return cache.owners() }
    override suspend fun sources(owner: MobvoiCloudOwner): List<MobvoiHealthDataSource> {
        alive(); val values = mutableListOf<MobvoiHealthDataSource>(); var offset = 0
        while (true) {
            val page = cache.query(CloudCacheKind.SOURCE, MobvoiHealthCacheQuery(owner, offset = offset, limit = 500))
            values += page.take(500).map { MobvoiCloudJson.source(MobvoiCloudJson.parse(it.payload.toByteArray())) }
            if (page.size <= 500) return values
            offset += 500
        }
    }
    override suspend fun refreshUserProfile(): MobvoiHealthUserProfile = account.withSession { lease ->
        alive(); val owner = lease.credential.profile.owner
        val response = readProfile(lease, "health_profile", "/user/userInfo", mapOf("wwid" to owner.wwid))
        val raw = response["user_info"] as? JsonObject
            ?: throw MobvoiCloudException(MobvoiCloudIssue(MobvoiCloudIssueKind.MALFORMED_RESPONSE, "health_profile"))
        val profile = MobvoiCloudJson.healthProfile(owner, raw)
        account.commitIfCurrent(lease) { cache.commit(owner, listOf(entry(owner, CloudCacheKind.PROFILE, "user_info", raw))) }
        account.applyProfile(lease) { old -> old.copy(birthday = profile.birthday ?: old.birthday,
            healthGender = profile.gender ?: old.healthGender,
            sex = profile.gender?.let { if (owner.region == MobvoiCloudRegion.GLOBAL) { if (it == 2) 1 else 0 } else { if (it == 0) 1 else 0 } } ?: old.sex,
            height = profile.height ?: old.height, weight = profile.weight ?: old.weight, unit = profile.unit ?: old.unit) }
        profile
    }
    override suspend fun cachedUserProfile(owner: MobvoiCloudOwner): MobvoiHealthUserProfile? {
        alive()
        return cache.query(CloudCacheKind.PROFILE, MobvoiHealthCacheQuery(owner, limit = 1)).firstOrNull()
            ?.let { MobvoiCloudJson.healthProfile(owner, MobvoiCloudJson.parse(it.payload.toByteArray())) }
    }
    override suspend fun readUserSettings(type: String): List<MobvoiHealthSetting> = account.withSession { lease ->
        alive()
        val response = readProfile(lease, "health_settings", "/v1/users/setting/base", mapOf("type" to type))
        val rows = collection(response, "list", "health_settings")
        val entries = rows.map { raw -> entry(lease.credential.profile.owner, CloudCacheKind.SETTING, raw.text("type") ?: CloudIdentity.hash(listOf(MobvoiCloudJson.canonical(raw))), raw) }
        account.commitIfCurrent(lease) { cache.commit(lease.credential.profile.owner, entries) }
        rows.map { MobvoiHealthSetting(it.text("type") ?: type, it.text("value")) }
    }
    override suspend fun writeUserSetting(setting: MobvoiHealthSetting) = account.withSession { lease ->
        alive()
        val raw = buildJsonObject { put("type", setting.type); setting.value?.let { put("value", it) } }
        readProfile(lease, "health_setting_write", "/v1/users/setting/base", method = "POST", body = raw)
        // Accepted mutation is not a server read-back. Leave cached values until a GET confirms them.
        Unit
    }
    override suspend fun points(query: MobvoiHealthCacheQuery): MobvoiCloudPage<MobvoiHealthPoint> = cached(CloudCacheKind.POINT, query) {
        MobvoiCloudJson.point(MobvoiCloudJson.parse(it.payload.toByteArray()), it.sourceName ?: "", it.timeZone)
    }
    override suspend fun sessions(query: MobvoiHealthCacheQuery): MobvoiCloudPage<MobvoiHealthSession> = cached(CloudCacheKind.SESSION, query) {
        MobvoiCloudJson.session(MobvoiCloudJson.parse(it.payload.toByteArray()))
    }
    override suspend fun sports(query: MobvoiHealthCacheQuery): MobvoiCloudPage<MobvoiSportRecord> = cached(CloudCacheKind.SPORT, query) {
        MobvoiCloudJson.sport(MobvoiCloudJson.parse(it.payload.toByteArray()))
    }
    private suspend fun <T> cached(kind: CloudCacheKind, query: MobvoiHealthCacheQuery, decode: (CloudCacheEntry) -> T): MobvoiCloudPage<T> {
        alive(); require(query.limit in 1..500 && query.offset >= 0)
        val rows = cache.query(kind, query)
        return MobvoiCloudPage(rows.take(query.limit).map(decode), (query.offset + query.limit).takeIf { rows.size > query.limit })
    }
    override suspend fun downloadSportRecord(recordId: String): MobvoiSportRecord? = account.withSession { lease ->
        alive()
        val response = readSport(lease, "sport_detail", query = mapOf("motionIds" to recordId))
        val rows = collection(response, "records", "sport_detail")
        val entries = rows.map { raw -> val value = MobvoiCloudJson.sport(raw); entry(lease.credential.profile.owner, CloudCacheKind.SPORT, value.key, raw, type = value.type, from = value.startMillis, to = value.endMillis) }
        account.commitIfCurrent(lease) { cache.commit(lease.credential.profile.owner, entries) }
        if (rows.none { it.text("id") == recordId }) null
        else cache.query(CloudCacheKind.SPORT, MobvoiHealthCacheQuery(lease.credential.profile.owner, recordKey = recordId, limit = 1))
            .firstOrNull()?.let { MobvoiCloudJson.sport(MobvoiCloudJson.parse(it.payload.toByteArray())) }
    }
    override suspend fun deleteSportRecord(recordId: String) = account.withSession { lease ->
        alive()
        if (lease.credential.profile.pii != true) throw MobvoiCloudException(MobvoiCloudIssue(MobvoiCloudIssueKind.CONSENT_REQUIRED, "sport_delete"))
        readSport(lease, "sport_delete", method = "DELETE", query = mapOf("recordId" to recordId))
        account.commitIfCurrent(lease) { cache.remove(lease.credential.profile.owner, CloudCacheKind.SPORT, recordId) }
    }
    override suspend fun clearCache(owner: MobvoiCloudOwner) { alive(); cache.clear(owner) }
    private suspend fun read(lease: MobvoiAccountLease, operation: String, path: String, query: Map<String, String?> = emptyMap()): JsonObject = authenticated(lease, operation,
        CloudHttpRequest("GET", cloudUrl(config.health(lease.credential.profile.owner.region), path, query), mapOf("Token" to lease.credential.sessionId, "Accept" to "application/json")))
    private suspend fun readProfile(lease: MobvoiAccountLease, operation: String, path: String, query: Map<String, String?> = emptyMap(), method: String = "GET", body: JsonObject? = null): JsonObject =
        authenticated(lease, operation, CloudHttpRequest(method, cloudUrl(config.profileBaseUrl, path, query),
            mapOf("token" to lease.credential.sessionId, "Accept" to "application/json") + if (body == null) emptyMap() else mapOf("Content-Type" to "application/json"), body?.toString()?.toByteArray(Charsets.UTF_8)))
    private suspend fun readSport(lease: MobvoiAccountLease, operation: String, method: String = "GET", query: Map<String, String?>): JsonObject {
        val owner = lease.credential.profile.owner
        val accountId = owner.accountId ?: throw MobvoiCloudException(MobvoiCloudIssue(MobvoiCloudIssueKind.AUTHENTICATION, "sport_account_id"))
        return authenticated(lease, operation, CloudHttpRequest(method, cloudUrl(config.sport(owner.region), "/data/accounts/${encodeComponent(accountId)}/records/motion",
            query + ("sessionId" to lease.credential.sessionId)), mapOf("Token" to lease.credential.sessionId, "Accept" to "application/json")))
    }
    private suspend fun authenticated(lease: MobvoiAccountLease, operation: String, request: CloudHttpRequest): JsonObject = try { http.json(operation, request) }
        catch (failure: MobvoiCloudException) { if (failure.issue.kind == MobvoiCloudIssueKind.EXPIRED) account.expire(lease, failure.issue); throw failure }
    private fun collection(raw: JsonObject, name: String, operation: String, issues: MutableList<MobvoiCloudIssue>? = null): List<JsonObject> {
        val values = raw[name] as? JsonArray
        if (values == null || values.any { it !is JsonObject }) {
            val failure = MobvoiCloudIssue(MobvoiCloudIssueKind.MALFORMED_RESPONSE, operation + "_collection")
            log(if (values == null) "collection_missing_or_wrong_type" else "unknown_collection_member", operation, failure)
            if (issues == null && values == null) throw MobvoiCloudException(failure)
            issues?.add(failure)
        }
        if (values == null) return emptyList()
        return values.filterIsInstance<JsonObject>()
    }
    private fun entry(owner: MobvoiCloudOwner, kind: CloudCacheKind, key: String, raw: JsonObject, source: String? = null, type: String? = null,
        from: Long? = null, to: Long? = null, zone: String? = null, deleted: Boolean = false) =
        CloudCacheEntry(owner, kind, key, source, type, from, to, zone, deleted, raw.toString(), System.currentTimeMillis())
    private fun alive() { if (closed) throw BackendClosedException() }
    private fun log(event: String, operation: String, issue: MobvoiCloudIssue? = null) { runCatching {
        diagnostics.record(ProtocolDiagnostic("cloud_health", event, details = mapOf("operation" to operation, "kind" to (issue?.kind?.name ?: "notice"), "serverCode" to issue?.serverCode.toString())))
    } }
    override fun close() { closed = true; active?.cancel(); stateValue.value = stateValue.value.copy(phase = MobvoiHealthSyncPhase.CLOSED) }
}
