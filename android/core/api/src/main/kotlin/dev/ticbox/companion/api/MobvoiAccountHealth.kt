package dev.ticbox.companion.api

import kotlinx.coroutines.flow.StateFlow

enum class MobvoiCloudRegion { GLOBAL, CHINA }
enum class MobvoiAccountPhase { SIGNED_OUT, RESTORING, AUTHENTICATING, SIGNED_IN, EXPIRED, CLOSED }
enum class MobvoiCloudIssueKind { AUTHENTICATION, EXPIRED, HTTP, NETWORK, MALFORMED_RESPONSE, STORAGE, CANCELLED, CONSENT_REQUIRED, RESOURCE_LIMIT }
data class MobvoiCloudIssue(val kind: MobvoiCloudIssueKind, val operation: String, val httpStatus: Int? = null, val serverCode: Int? = null, val retryable: Boolean = false)
class MobvoiCloudException(val issue: MobvoiCloudIssue) : IllegalStateException("${issue.operation}:${issue.kind}")

data class MobvoiCloudOwner(val region: MobvoiCloudRegion, val wwid: String, val accountId: String?)
data class MobvoiAccountProfile(
    val owner: MobvoiCloudOwner,
    val email: String? = null,
    val phone: String? = null,
    val nickname: String? = null,
    val avatarUrl: String? = null,
    val birthday: String? = null,
    val sex: Int? = null,
    val height: String? = null,
    val weight: String? = null,
    val pii: Boolean? = null,
    val agree: Boolean? = null,
    val region: String? = null,
    val unit: String? = null,
    /** Health-profile g enum; independent of the account service's binary sex field. */
    val healthGender: Int? = null,
)
data class MobvoiAccountState(val phase: MobvoiAccountPhase = MobvoiAccountPhase.SIGNED_OUT, val profile: MobvoiAccountProfile? = null, val lastIssue: MobvoiCloudIssue? = null, val watchLoginRequested: Boolean = false)
enum class MobvoiIdentifierKind { EMAIL, PHONE }
data class MobvoiLoginIdentifier(val kind: MobvoiIdentifierKind, val value: String)
data class MobvoiProfileUpdate(val nickname: String? = null, val birthday: String? = null, val sex: Int? = null,
    val height: String? = null, val weight: String? = null, val avatarUrl: String? = null, val pii: Boolean? = null, val agree: Boolean? = null)

/** Service-owned. Profile state excludes session IDs; passwords are never persisted. */
interface MobvoiAccountClient : AutoCloseable {
    val state: StateFlow<MobvoiAccountState>
    suspend fun restore(): MobvoiAccountProfile?
    suspend fun loginPassword(identifier: MobvoiLoginIdentifier, password: CharArray, region: MobvoiCloudRegion = MobvoiCloudRegion.GLOBAL): MobvoiAccountProfile
    suspend fun requestCaptcha(identifier: MobvoiLoginIdentifier, language: String = "en-US")
    suspend fun loginCaptcha(phone: String, code: String, region: MobvoiCloudRegion = MobvoiCloudRegion.GLOBAL): MobvoiAccountProfile
    suspend fun refreshProfile(): MobvoiAccountProfile
    suspend fun updateProfile(update: MobvoiProfileUpdate): MobvoiAccountProfile
    suspend fun saveTimeZone(timeZoneId: String)
    /** Local sign-out precedes remote revocation. A failed credential-file clear reports STORAGE. */
    suspend fun logout(revokeRemote: Boolean = true, clearCachedHealth: Boolean = false)
    override fun close()
}

data class MobvoiCloudDevice(val id: String?, val type: String?, val model: String?, val version: String?)
data class MobvoiHealthDataSource(val name: String, val numericId: Long? = null, val device: MobvoiCloudDevice? = null,
    val applicationPackage: String? = null, val applicationVersion: String? = null)
data class MobvoiHealthPoint(
    val key: String, val sourceName: String, val dataType: String?, val startMillis: Long?, val endMillis: Long?,
    val timeZone: String?, val deleted: Boolean, val stringValue: String?, val floatingValue: Double?, val integerValue: Long?,
)
data class MobvoiHealthSession(val key: String, val sourceName: String, val sessionId: String?, val activityType: String?,
    val startMillis: Long?, val endMillis: Long?, val timeZone: String?, val deleted: Boolean, val points: List<MobvoiHealthPoint>)
data class MobvoiSportTrackPoint(val time: Long?, val wallClockTimestamp: Long?, val gpsPoint: String?, val gpsState: String?,
    val heartRate: Int?, val cumulativeSteps: Long?, val cumulativeDistance: Double?, val velocity: Double?, val elevation: Double?, val calorie: Double?)
data class MobvoiSportRecord(val key: String, val recordId: String?, val deviceId: String?, val type: String?,
    val startMillis: Long?, val endMillis: Long?, val totalMotionTime: Long?, val totalDistance: Double?, val totalCalorie: Double?,
    val totalSteps: Long?, val averageHeartRate: Double?, val gpsType: Int?, val tracks: List<MobvoiSportTrackPoint>)

data class MobvoiHealthSyncRequest(
    val fromMillis: Long, val toMillis: Long, val sourceName: String = "derived:com.mobvoi.fitness",
    val dataType: String? = null, val activityType: String? = null,
    val dataSets: Boolean = true, val sessions: Boolean = true, val sports: Boolean = true,
    val sportType: String? = null, val sportPageSize: Int = 100, val includeSportTracks: Boolean = true,
)
enum class MobvoiHealthSyncPhase { IDLE, SYNCING, COMPLETED, PARTIAL, FAILED, CANCELLED, CLOSED }
data class MobvoiHealthSyncState(val phase: MobvoiHealthSyncPhase = MobvoiHealthSyncPhase.IDLE,
    val owner: MobvoiCloudOwner? = null, val operation: String? = null, val sourceCount: Int = 0,
    val pointCount: Int = 0, val sessionCount: Int = 0, val sportCount: Int = 0, val lastIssue: MobvoiCloudIssue? = null)
data class MobvoiHealthSyncReport(val owner: MobvoiCloudOwner, val sourceCount: Int, val pointCount: Int,
    val sessionCount: Int, val sportCount: Int, val partial: Boolean, val issues: List<MobvoiCloudIssue>)
/**
 * Mobvoi cloud-cache filters intersect. For source-neutral local reads use HealthRecordQuery.
 * Time bounds use inclusive interval overlap; missing times match.
 * dataType is the outer index (point data_type / session activity_type / sport type), not a child search.
 * Order is start time then key ascending, with missing starts first. Offset pages are not snapshots.
 */
data class MobvoiHealthCacheQuery(val owner: MobvoiCloudOwner, val fromMillis: Long? = null, val toMillis: Long? = null,
    val dataType: String? = null, val sourceName: String? = null, val includeDeleted: Boolean = false, val offset: Int = 0, val limit: Int = 200,
    /** Exact backend record key; combine with owner and kind to read a cached detail without HTTP. */
    val recordKey: String? = null)
data class MobvoiCloudPage<T>(val items: List<T>, val nextOffset: Int?)
data class MobvoiHealthUserProfile(val owner: MobvoiCloudOwner, val birthday: String?, val gender: Int?,
    val height: String?, val weight: String?, val unit: String?, val goalTodayActive: Int?, val goalTodayExercise: Int?,
    val goalTodayStep: Int?, val expectedSleepTime: Int?, val expectedGetUpTime: Int?,
    val heartRateWarningFitnessUpperLimit: Int?, val heartRateWarningRestLowerLimit: Int?, val heartRateWarningRestUpperLimit: Int?)
/** Source's extensible setting type/value pair, without guessing units or coercing unknown types. */
data class MobvoiHealthSetting(val type: String, val value: String?)

interface MobvoiCloudHealthClient : AutoCloseable {
    val state: StateFlow<MobvoiHealthSyncState>
    suspend fun sync(request: MobvoiHealthSyncRequest): MobvoiHealthSyncReport
    fun cancelSync()
    suspend fun cachedOwners(): List<MobvoiCloudOwner>
    suspend fun sources(owner: MobvoiCloudOwner): List<MobvoiHealthDataSource>
    suspend fun refreshUserProfile(): MobvoiHealthUserProfile
    suspend fun cachedUserProfile(owner: MobvoiCloudOwner): MobvoiHealthUserProfile?
    suspend fun readUserSettings(type: String): List<MobvoiHealthSetting>
    suspend fun writeUserSetting(setting: MobvoiHealthSetting)
    suspend fun points(query: MobvoiHealthCacheQuery): MobvoiCloudPage<MobvoiHealthPoint>
    suspend fun sessions(query: MobvoiHealthCacheQuery): MobvoiCloudPage<MobvoiHealthSession>
    suspend fun sports(query: MobvoiHealthCacheQuery): MobvoiCloudPage<MobvoiSportRecord>
    /** Fetch detailed track points by the actual sport record identifier. */
    suspend fun downloadSportRecord(recordId: String): MobvoiSportRecord?
    /** Source-backed server DELETE; cache is removed only after a successful response. */
    suspend fun deleteSportRecord(recordId: String)
    suspend fun clearCache(owner: MobvoiCloudOwner)
    override fun close()
}
