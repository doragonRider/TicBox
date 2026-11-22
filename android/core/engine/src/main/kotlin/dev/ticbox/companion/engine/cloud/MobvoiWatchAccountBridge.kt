package dev.ticbox.companion.engine.cloud

import dev.ticbox.companion.api.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.*

/** Host/device settings whose original values came from Android and CompanionSetting. */
data class MobvoiCompanionHostInfo(
    val model: String, val systemVersion: String, val versionName: String, val versionCode: String,
    val deviceId: String, val capabilities: String? = null, val unit: String? = null,
    val temperature: String? = null, val dateFormat: String? = null, val isVpa: Boolean = false,
    val sexNameOverride: String? = null,
)

/** Account bytes stay in a backend session lease; frontend receives neither sessionId nor JSON. */
class MobvoiWatchAccountBridge(private val account: MobvoiAccountEngine, private val diagnostics: DiagnosticSink = DiagnosticSink.NONE) {
    /** Caller supplies a physical-session-bound MessageApi writer. */
    suspend fun publishCompanionInfo(host: MobvoiCompanionHostInfo, send: suspend (String, ByteArray) -> Unit) = account.withSession { lease ->
        val profile = lease.credential.profile
        val genderCode = if (profile.owner.region == MobvoiCloudRegion.GLOBAL) profile.healthGender ?: 1 else profile.sex
        val sex = host.sexNameOverride ?: genderCode?.let { code ->
            val names = if (profile.owner.region == MobvoiCloudRegion.GLOBAL) listOf("UNKNOWN", "MALE", "FEMALE", "NON_BINARY") else listOf("MALE", "FEMALE")
            names.getOrNull(code).also { if (it == null) log("unknown_profile_gender", mapOf("code" to code.toString())) }
        }
        val body = buildJsonObject {
            put("wwid", profile.owner.wwid); profile.owner.accountId?.let { put("accountId", it) }; put("sessionId", lease.credential.sessionId)
            profile.nickname?.let { put("nickName", it) }; profile.email?.let { put("email", it) }; profile.phone?.let { put("phoneNumber", it) }
            sex?.let { put("sex", it) }; profile.height?.let { put("height", it) }; profile.weight?.let { put("weight", it) }
            profile.birthday?.let { put("birthday", it) }; profile.avatarUrl?.let { put("headUrl", it) }
            put("isVpa", host.isVpa); put("model", host.model); put("systemVersion", host.systemVersion)
            put("versionName", host.versionName); put("versionCode", host.versionCode); put("deviceId", host.deviceId)
            host.capabilities?.let { put("capabilities", it) }; (host.unit ?: profile.unit)?.let { put("unit", it) }
            host.temperature?.let { put("temperature", it) }; host.dateFormat?.let { put("dateformat", it) }
        }
        send(SEND_COMPANION_INFO, body.toString().toByteArray(Charsets.UTF_8))
        log("companion_info_submitted")
    }
    suspend fun handleLoginRequest(host: MobvoiCompanionHostInfo, send: suspend (String, ByteArray) -> Unit) {
        if (account.state.value.phase == MobvoiAccountPhase.SIGNED_IN) publishCompanionInfo(host, send)
        else { account.markWatchLoginRequested(); log("watch_requested_login") }
    }
    /** Received from /companion/sync_account_info; partial fields preserve existing profile values. */
    suspend fun acceptCompactProfile(payload: ByteArray, timeZone: ZoneId = ZoneId.systemDefault()): MobvoiAccountProfile? {
        val raw = try { MobvoiCloudJson.parse(payload) } catch (_: Exception) { log("compact_profile_unreadable"); return null }
        val birthday = raw.number("b")?.let { seconds ->
            runCatching { DateTimeFormatter.ofPattern("yyyy.MM.dd", Locale.ROOT).withZone(timeZone).format(Instant.ofEpochSecond(seconds)) }
                .getOrElse { log("compact_profile_birthday_unrepresentable"); null }
        }
        val known = setOf("b", "g", "h", "w", "u")
        if (raw.keys.any { it !in known }) log("compact_profile_extra_fields", mapOf("count" to raw.keys.count { it !in known }.toString()))
        val observedGender = raw.number("g")?.toInt()
        val result = account.applyWatchProfile { old -> old.copy(
            birthday = birthday ?: old.birthday,
            sex = observedGender?.let { if (old.owner.region == MobvoiCloudRegion.GLOBAL) { if (it == 2) 1 else 0 } else { if (it == 0) 1 else 0 } } ?: old.sex,
            healthGender = observedGender ?: old.healthGender,
            height = raw.text("h") ?: old.height, weight = raw.text("w") ?: old.weight, unit = raw.text("u") ?: old.unit,
        ) }
        log("compact_profile_persisted")
        return result
    }
    private fun log(event: String, details: Map<String, String> = emptyMap()) { runCatching { diagnostics.record(ProtocolDiagnostic("account_bridge", event, DiagnosticSeverity.INFO, details = details)) } }
    companion object {
        const val SEND_COMPANION_INFO = "/companion/send_companion_info"
        const val LOGIN = "/companion/login"
        const val SYNC_ACCOUNT_INFO = "/companion/sync_account_info"
    }
}
