package dev.ticbox.companion.engine.cloud

import dev.ticbox.companion.api.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class CloudHttpRequest(val method: String, val url: String, val headers: Map<String, String> = emptyMap(), body: ByteArray? = null) {
    private val bytes = body?.copyOf()
    val body: ByteArray? get() = bytes?.copyOf()
    override fun toString() = "CloudHttpRequest(method=$method, url=<redacted>)"
}
class CloudHttpResponse(val status: Int, body: ByteArray, val headers: Map<String, List<String>> = emptyMap()) {
    private val bytes = body.copyOf()
    val body: ByteArray get() = bytes.copyOf()
    override fun toString() = "CloudHttpResponse(status=$status, bytes=${bytes.size})"
}
fun interface CloudHttpTransport { suspend fun execute(request: CloudHttpRequest): CloudHttpResponse }

/** Internal backend credential; never publish this object in frontend state or diagnostics. */
class MobvoiCloudCredential(val profile: MobvoiAccountProfile, val sessionId: String) {
    override fun toString() = "MobvoiCloudCredential(<redacted>)"
}
interface MobvoiCredentialStore {
    suspend fun load(): MobvoiCloudCredential?
    suspend fun save(credential: MobvoiCloudCredential)
    suspend fun clear()
}
class MemoryMobvoiCredentialStore : MobvoiCredentialStore {
    private var credential: MobvoiCloudCredential? = null
    override suspend fun load() = synchronized(this) { credential }
    override suspend fun save(credential: MobvoiCloudCredential) { synchronized(this) { this.credential = credential } }
    override suspend fun clear() { synchronized(this) { credential = null } }
}

data class MobvoiCloudConfig(
    val accountBaseUrl: String = "https://ticwear-account.mobvoi.com/",
    val globalHealthBaseUrl: String = "https://api-tichealth.mobvoi.com/",
    val chinaHealthBaseUrl: String = "https://fitness.mobvoi.com/",
    val globalSportBaseUrl: String = "https://api-ticfitness.mobvoi.com/",
    val chinaSportBaseUrl: String = "https://health.ticwear.com/",
    val packageName: String = "dev.ticbox.companion",
    /** The official client has no initialized value for this field; omit unless configured. */
    val applicationId: String? = null,
    val versionName: String? = null,
    val versionCode: String? = null,
    val language: String = "en-US",
    val phoneId: String? = null,
    val maxResponseBytes: Int = 16 * 1024 * 1024,
    val readAttempts: Int = 3,
    val retryDelayMillis: Long = 300,
    val maxSportPages: Int = 100,
    /** The official client initializes this separate user-setting service to the global URL. */
    val profileBaseUrl: String = "https://api-tichealth.mobvoi.com/",
) {
    init { require(maxResponseBytes > 0 && readAttempts > 0 && retryDelayMillis >= 0 && maxSportPages > 0) }
    fun health(region: MobvoiCloudRegion) = if (region == MobvoiCloudRegion.GLOBAL) globalHealthBaseUrl else chinaHealthBaseUrl
    fun sport(region: MobvoiCloudRegion) = if (region == MobvoiCloudRegion.GLOBAL) globalSportBaseUrl else chinaSportBaseUrl
}

enum class CloudCacheKind { OWNER, SOURCE, POINT, SESSION, SPORT, PROFILE, SETTING }
/** Stored JSON is available to the frontend through typed convenience and raw-record reads. */
data class CloudCacheEntry(val owner: MobvoiCloudOwner, val kind: CloudCacheKind, val key: String,
    val sourceName: String? = null, val dataType: String? = null, val startMillis: Long? = null, val endMillis: Long? = null,
    val timeZone: String? = null, val deleted: Boolean = false, val payload: String, val updatedAtMillis: Long,
    /** Write hint only: noPoints responses cannot withdraw an already cached sport track. */
    val sportTracksRequested: Boolean = true)
data class CloudSyncWindow(val kind: CloudCacheKind, val fromMillis: Long, val toMillis: Long)
interface MobvoiCloudCacheStore {
    /**
     * Item upserts and a completed window commit together. Absence never deletes previous records.
     * SPORT updates shallowly retain omitted detail fields; explicit values replace them. Empty
     * track placeholders from a noPoints response do not clear a previously downloaded track.
     */
    suspend fun commit(owner: MobvoiCloudOwner, entries: List<CloudCacheEntry>, window: CloudSyncWindow? = null)
    suspend fun query(kind: CloudCacheKind, query: MobvoiHealthCacheQuery): List<CloudCacheEntry>
    suspend fun owners(): List<MobvoiCloudOwner>
    suspend fun remove(owner: MobvoiCloudOwner, kind: CloudCacheKind, key: String)
    suspend fun clear(owner: MobvoiCloudOwner)
}

object CloudIdentity {
    fun key(owner: MobvoiCloudOwner): String = hash(listOf(owner.region.name, owner.wwid, owner.accountId))
    fun hash(parts: List<String?>): String {
        val out = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(out).use { data -> parts.forEach { value ->
            if (value == null) data.writeInt(-1) else { val bytes = value.toByteArray(Charsets.UTF_8); data.writeInt(bytes.size); data.write(bytes) }
        } }
        return MessageDigest.getInstance("SHA-256").digest(out.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
internal fun encodeComponent(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
internal fun cloudUrl(base: String, path: String, query: Map<String, String?> = emptyMap()): String = base.trimEnd('/') + "/" + path.trimStart('/') +
    query.filterValues { it != null }.entries.joinToString("&", prefix = if (query.values.any { it != null }) "?" else "") { encodeComponent(it.key) + "=" + encodeComponent(it.value!!) }
