package dev.ticbox.companion.engine.cloud

import dev.ticbox.companion.api.*
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

class MobvoiAccountLease internal constructor(internal val generation: Long, val credential: MobvoiCloudCredential)

/** Account/session owner. There is no invented refresh-token exchange: expiration requires login. */
class MobvoiAccountEngine(
    transport: CloudHttpTransport,
    private val credentials: MobvoiCredentialStore,
    private val cache: MobvoiCloudCacheStore,
    private val config: MobvoiCloudConfig = MobvoiCloudConfig(),
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
) : MobvoiAccountClient {
    private val http = CloudHttp(transport, config, diagnostics)
    private val lock = Mutex()
    private val stateValue = MutableStateFlow(MobvoiAccountState())
    override val state = stateValue.asStateFlow()
    private var generation = 0L
    private var credential: MobvoiCloudCredential? = null
    private var authJob: Job? = null
    private val readers = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()
    @Volatile private var closed = false

    override suspend fun restore(): MobvoiAccountProfile? = coroutineScope {
        val epoch = beginAuth(MobvoiAccountPhase.RESTORING)
        try {
            val saved = credentialStorage("restore") { credentials.load() }
            lock.withLock {
                checkEpoch(epoch)
                credential = saved
                stateValue.value = MobvoiAccountState(if (saved == null) MobvoiAccountPhase.SIGNED_OUT else MobvoiAccountPhase.SIGNED_IN, saved?.profile)
                authJob = null
            }
            saved?.profile
        } catch (cancelled: CancellationException) { finishCancelled(epoch); throw cancelled }
        catch (error: Exception) { val failure = issue(error, "restore", MobvoiCloudIssueKind.STORAGE); failAuth(epoch, failure); throw MobvoiCloudException(failure) }
    }
    override suspend fun loginPassword(identifier: MobvoiLoginIdentifier, password: CharArray, region: MobvoiCloudRegion): MobvoiAccountProfile {
        val copy = password.copyOf()
        val encoded = try { MessageDigest.getInstance("MD5").digest(String(copy).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) } }
            finally { copy.fill('\u0000') }
        return login("v3/login", buildJsonObject {
            put(if (identifier.kind == MobvoiIdentifierKind.EMAIL) "email" else "phone", identifier.value)
            put("password", encoded); config.applicationId?.let { put("app", it) }
        }, region)
    }
    override suspend fun loginCaptcha(phone: String, code: String, region: MobvoiCloudRegion): MobvoiAccountProfile = login("v3/login/captcha", buildJsonObject {
        put("phone", phone); put("captcha_value", code); config.applicationId?.let { put("app", it) }
    }, region)
    private suspend fun login(path: String, body: JsonObject, region: MobvoiCloudRegion): MobvoiAccountProfile = coroutineScope {
        val epoch = beginAuth(MobvoiAccountPhase.AUTHENTICATING)
        try {
            val raw = http.json("login", accountRequest("POST", path, region, body = body), account = true)
            val result = MobvoiCloudJson.loginCredential(raw, region)
            lock.withLock {
                checkEpoch(epoch); credentialStorage("login_store") { credentials.save(result) }; checkEpoch(epoch); credential = result
                stateValue.value = MobvoiAccountState(MobvoiAccountPhase.SIGNED_IN, result.profile); authJob = null
            }
            log("login_complete"); result.profile
        } catch (cancelled: CancellationException) { finishCancelled(epoch); throw cancelled }
        catch (error: Exception) { val failure = issue(error, "login"); failAuth(epoch, failure); throw MobvoiCloudException(failure) }
    }
    override suspend fun requestCaptcha(identifier: MobvoiLoginIdentifier, language: String) {
        alive()
        val type = if (identifier.kind == MobvoiIdentifierKind.PHONE) "sms" else "email"
        http.json("request_captcha", accountRequest("GET", "v3/captcha/$type", state.value.profile?.owner?.region ?: MobvoiCloudRegion.GLOBAL,
            query = mapOf((if (type == "sms") "phone" else "email") to identifier.value, "usage" to "login", "language" to language)), account = true, retryRead = false)
    }
    override suspend fun refreshProfile(): MobvoiAccountProfile = withSession { lease ->
        val raw = authenticatedAccount(lease, "profile_refresh", "GET", "v3/info/session-id",
            query = mapOf("session_id" to lease.credential.sessionId, "phoneId" to config.phoneId, "app" to config.applicationId))
        val next = MobvoiCloudJson.loginCredential(raw, lease.credential.profile.owner.region, lease.credential)
        commitIfCurrent(lease) { credentialStorage("profile_store") { credentials.save(next) }; checkEpoch(lease.generation); credential = next; stateValue.value = MobvoiAccountState(MobvoiAccountPhase.SIGNED_IN, next.profile) }
        next.profile
    }
    override suspend fun updateProfile(update: MobvoiProfileUpdate): MobvoiAccountProfile = withSession { lease ->
        val changes = buildJsonObject {
            // Source posts AccountInfo, not a JSON Patch. Preserve the known current fields.
            MobvoiCloudJson.profileJson(lease.credential.profile).filterKeys {
                it in setOf("email", "nickname", "head_image_url", "birthday", "sex", "height", "weight", "pii", "agree", "region")
            }.forEach { (name, value) -> put(name, value) }
            put("wwid", lease.credential.profile.owner.wwid); put("session_id", lease.credential.sessionId)
            lease.credential.profile.owner.accountId?.let { put("account_id", it) }; config.applicationId?.let { put("app", it) }
            update.nickname?.let { put("nickname", it) }; update.birthday?.let { put("birthday", it) }; update.sex?.let { put("sex", it) }
            update.height?.let { put("height", it) }; update.weight?.let { put("weight", it) }; update.avatarUrl?.let { put("head_image_url", it) }
            update.pii?.let { put("pii", it) }; update.agree?.let { put("agree", it) }
        }
        authenticatedAccount(lease, "profile_update", "POST", "v3/info/update", body = changes)
        // Re-read server profile; an accepted update is not proof of a particular normalized value.
        val raw = authenticatedAccount(lease, "profile_refresh", "GET", "v3/info/session-id", query = mapOf("session_id" to lease.credential.sessionId, "phoneId" to config.phoneId, "app" to config.applicationId))
        val next = MobvoiCloudJson.loginCredential(raw, lease.credential.profile.owner.region, lease.credential)
        commitIfCurrent(lease) { credentialStorage("profile_store") { credentials.save(next) }; checkEpoch(lease.generation); credential = next; stateValue.value = MobvoiAccountState(MobvoiAccountPhase.SIGNED_IN, next.profile) }
        next.profile
    }
    override suspend fun saveTimeZone(timeZoneId: String) = withSession { lease ->
        authenticatedAccount(lease, "time_zone", "POST", "time-zone/save", body = buildJsonObject {
            put("wwid", lease.credential.profile.owner.wwid); lease.credential.profile.owner.accountId?.let { put("accountId", it) }; put("timeZone", timeZoneId)
        }); Unit
    }
    override suspend fun logout(revokeRemote: Boolean, clearCachedHealth: Boolean) {
        var storageFailure: MobvoiCloudIssue? = null
        val old = lock.withLock {
            alive(); generation++; val old = credential; credential = null
            authJob?.cancel(CancellationException("Account signed out")); authJob = null
            readers.toList().forEach { it.cancel(CancellationException("Account signed out")) }; readers.clear()
            // Persist local sign-out before attempting a network revocation.
            try { withContext(NonCancellable) { credentials.clear() } }
            catch (_: Exception) { storageFailure = MobvoiCloudIssue(MobvoiCloudIssueKind.STORAGE, "logout_local_clear") }
            if (!closed) stateValue.value = MobvoiAccountState(lastIssue = storageFailure)
            old
        }
        if (old != null && clearCachedHealth) try { cache.clear(old.profile.owner) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { storageFailure = storageFailure ?: MobvoiCloudIssue(MobvoiCloudIssueKind.STORAGE, "logout_cache_clear") }
        if (old != null && revokeRemote) {
            try { http.json("logout", accountRequest("POST", "v3/logout", old.profile.owner.region, query = mapOf("session_id" to old.sessionId)), account = true) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { log("logout_remote_failed", issue(error, "logout")) }
        }
        log("logout_local_complete")
        storageFailure?.let { failure ->
            lock.withLock { if (!closed && credential == null) stateValue.value = stateValue.value.copy(lastIssue = failure) }
            log("logout_storage_failed", failure)
            throw MobvoiCloudException(failure)
        }
    }

    /** Backend-only protected credential lease. Logout cancels its child scope, not a UI owner job. */
    suspend fun <T> withSession(block: suspend (MobvoiAccountLease) -> T): T = coroutineScope {
        val job = currentCoroutineContext().job
        val lease = lock.withLock {
            alive()
            val selected = credential?.takeIf { state.value.phase == MobvoiAccountPhase.SIGNED_IN }
                ?: throw MobvoiCloudException(MobvoiCloudIssue(MobvoiCloudIssueKind.AUTHENTICATION, "session_required"))
            readers += job; MobvoiAccountLease(generation, selected)
        }
        try { block(lease) } finally { withContext(NonCancellable) { lock.withLock { readers -= job } } }
    }
    suspend fun <T> commitIfCurrent(lease: MobvoiAccountLease, block: suspend () -> T): T = lock.withLock {
        checkEpoch(lease.generation); if (credential?.sessionId != lease.credential.sessionId || credential?.profile?.owner != lease.credential.profile.owner) throw CancellationException("Account session expired")
        block()
    }
    suspend fun expire(lease: MobvoiAccountLease, failure: MobvoiCloudIssue) {
        val caller = currentCoroutineContext().job
        lock.withLock {
            if (lease.generation != generation || closed || credential?.sessionId != lease.credential.sessionId || credential?.profile?.owner != lease.credential.profile.owner) return
            generation++; val profile = credential?.profile; credential = null
            readers.filter { it !== caller }.forEach { it.cancel(CancellationException("Account session expired")) }
            try { withContext(NonCancellable) { credentials.clear() } }
            catch (_: Exception) { log("expired_credential_clear_failed", MobvoiCloudIssue(MobvoiCloudIssueKind.STORAGE, "expire_local_clear")) }
            if (!closed) stateValue.value = MobvoiAccountState(MobvoiAccountPhase.EXPIRED, profile, failure)
        }
        log("account_expired", failure)
    }
    suspend fun markWatchLoginRequested() = lock.withLock {
        alive(); stateValue.value = stateValue.value.copy(watchLoginRequested = true)
    }
    suspend fun applyWatchProfile(transform: (MobvoiAccountProfile) -> MobvoiAccountProfile): MobvoiAccountProfile = withSession { lease -> applyProfile(lease, transform) }
    suspend fun applyProfile(lease: MobvoiAccountLease, transform: (MobvoiAccountProfile) -> MobvoiAccountProfile): MobvoiAccountProfile = commitIfCurrent(lease) {
        val current = credential ?: throw CancellationException("Account session expired")
        val next = MobvoiCloudCredential(transform(current.profile), current.sessionId)
        credentialStorage("profile_store") { credentials.save(next) }; checkEpoch(lease.generation); credential = next
        stateValue.value = MobvoiAccountState(MobvoiAccountPhase.SIGNED_IN, next.profile)
        next.profile
    }

    private suspend fun authenticatedAccount(lease: MobvoiAccountLease, operation: String, method: String, path: String,
        query: Map<String, String?> = emptyMap(), body: JsonObject? = null): JsonObject = try {
        http.json(operation, accountRequest(method, path, lease.credential.profile.owner.region, query, body), account = true)
    } catch (failure: MobvoiCloudException) { if (failure.issue.kind == MobvoiCloudIssueKind.EXPIRED) expire(lease, failure.issue); throw failure }
    private fun accountRequest(method: String, path: String, region: MobvoiCloudRegion, query: Map<String, String?> = emptyMap(), body: JsonObject? = null): CloudHttpRequest {
        val parameters = linkedMapOf<String, String?>("pkg" to config.packageName, "platform" to "android", "region" to if (region == MobvoiCloudRegion.GLOBAL) "i18n" else "china", "vn" to config.versionName, "vc" to config.versionCode) + query
        return CloudHttpRequest(method, cloudUrl(config.accountBaseUrl, path, parameters), mapOf("Accept-Language" to config.language, "Accept" to "application/json") +
            if (body == null) emptyMap() else mapOf("Content-Type" to "application/json"), body?.toString()?.toByteArray(Charsets.UTF_8))
    }
    private suspend fun beginAuth(phase: MobvoiAccountPhase): Long = lock.withLock {
        alive(); generation++; authJob?.cancel(CancellationException("Account operation replaced")); authJob = currentCoroutineContext().job
        readers.toList().forEach { it.cancel(CancellationException("Account operation replaced")) }; readers.clear()
        stateValue.value = MobvoiAccountState(phase, credential?.profile)
        generation
    }
    private suspend fun finishCancelled(epoch: Long) = withContext(NonCancellable) { lock.withLock {
        if (epoch == generation && !closed) { authJob = null; stateValue.value = MobvoiAccountState(if (credential == null) MobvoiAccountPhase.SIGNED_OUT else MobvoiAccountPhase.SIGNED_IN, credential?.profile) }
    } }
    private suspend fun failAuth(epoch: Long, failure: MobvoiCloudIssue) = lock.withLock {
        if (epoch == generation && !closed) { authJob = null; stateValue.value = MobvoiAccountState(if (credential == null) MobvoiAccountPhase.SIGNED_OUT else MobvoiAccountPhase.SIGNED_IN, credential?.profile, failure) }
        log("account_operation_failed", failure)
    }
    private fun checkEpoch(epoch: Long) { alive(); if (generation != epoch) throw CancellationException("Stale account operation") }
    private fun alive() { if (closed) throw BackendClosedException() }
    private suspend fun <T> credentialStorage(operation: String, block: suspend () -> T): T = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw MobvoiCloudException(MobvoiCloudIssue(MobvoiCloudIssueKind.STORAGE, operation)) }
    private fun issue(error: Exception, operation: String, fallback: MobvoiCloudIssueKind = MobvoiCloudIssueKind.NETWORK) = (error as? MobvoiCloudException)?.issue ?: MobvoiCloudIssue(fallback, operation)
    private fun log(event: String, issue: MobvoiCloudIssue? = null) { runCatching { diagnostics.record(ProtocolDiagnostic("cloud_account", event, details = issue?.let { mapOf("kind" to it.kind.name, "operation" to it.operation, "httpStatus" to it.httpStatus.toString(), "serverCode" to it.serverCode.toString()) } ?: emptyMap())) } }
    override fun close() { closed = true; authJob?.cancel(); readers.toList().forEach { it.cancel() }; stateValue.value = stateValue.value.copy(phase = MobvoiAccountPhase.CLOSED) }
}
