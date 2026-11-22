package dev.ticbox.companion.engine.cloud

import dev.ticbox.companion.api.*
import kotlinx.serialization.json.*

/** Tolerant semantic projection; original health JSON stays in the repository. */
object MobvoiCloudJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    fun parse(bytes: ByteArray): JsonObject = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).let { element ->
        element as? JsonObject ?: throw MobvoiCloudException(MobvoiCloudIssue(MobvoiCloudIssueKind.MALFORMED_RESPONSE, "json_object"))
    }
    fun ownerJson(owner: MobvoiCloudOwner): String = buildJsonObject {
        put("region", owner.region.name); put("wwid", owner.wwid); owner.accountId?.let { put("account_id", it) }
    }.toString()
    fun owner(raw: String): MobvoiCloudOwner {
        val obj = parse(raw.toByteArray())
        return MobvoiCloudOwner(MobvoiCloudRegion.valueOf(obj.text("region")!!), obj.text("wwid")!!, obj.text("account_id"))
    }
    fun credentialBytes(credential: MobvoiCloudCredential): ByteArray = buildJsonObject {
        put("owner", json.parseToJsonElement(ownerJson(credential.profile.owner)))
        put("session_id", credential.sessionId)
        put("profile", profileJson(credential.profile))
    }.toString().toByteArray(Charsets.UTF_8)
    fun credential(bytes: ByteArray): MobvoiCloudCredential {
        val obj = parse(bytes)
        val owner = owner(obj.getValue("owner").toString())
        val session = obj.text("session_id") ?: throw IllegalStateException("Credential has no session ID")
        return MobvoiCloudCredential(profile(owner, obj["profile"] as? JsonObject ?: JsonObject(emptyMap())), session)
    }
    fun profileJson(profile: MobvoiAccountProfile): JsonObject = buildJsonObject {
        profile.email?.let { put("email", it) }; profile.phone?.let { put("phone", it) }
        profile.nickname?.let { put("nickname", it) }; profile.avatarUrl?.let { put("head_image_url", it) }
        profile.birthday?.let { put("birthday", it) }; profile.sex?.let { put("sex", it) }
        profile.height?.let { put("height", it) }; profile.weight?.let { put("weight", it) }
        profile.pii?.let { put("pii", it) }; profile.agree?.let { put("agree", it) }; profile.region?.let { put("region", it) }; profile.unit?.let { put("unit", it) }
        profile.healthGender?.let { put("health_gender", it) }
    }
    fun loginCredential(raw: JsonObject, region: MobvoiCloudRegion, previous: MobvoiCloudCredential? = null): MobvoiCloudCredential {
        val result = raw["result"] as? JsonObject ?: raw
        val info = result["info"] as? JsonObject ?: JsonObject(emptyMap())
        val session = result.text("session_id") ?: previous?.sessionId
            ?: throw MobvoiCloudException(MobvoiCloudIssue(MobvoiCloudIssueKind.MALFORMED_RESPONSE, "login_session_id"))
        val wwid = result.text("wwid") ?: previous?.profile?.owner?.wwid
            ?: throw MobvoiCloudException(MobvoiCloudIssue(MobvoiCloudIssueKind.MALFORMED_RESPONSE, "login_wwid"))
        val account = info.text("account_id") ?: previous?.profile?.owner?.accountId
        val owner = MobvoiCloudOwner(region, wwid, account)
        val old = previous?.profile?.let(::profileJson) ?: JsonObject(emptyMap())
        val combined = JsonObject(old + info + result.filterKeys { it in setOf("email", "phone") })
        return MobvoiCloudCredential(profile(owner, combined), session)
    }
    private fun profile(owner: MobvoiCloudOwner, raw: JsonObject) = MobvoiAccountProfile(owner,
        raw.text("email"), raw.text("phone"), raw.text("nickname"), raw.text("head_image_url"), raw.text("birthday"),
        raw.number("sex")?.toInt(), raw.text("height"), raw.text("weight"), raw.flag("pii"), raw.flag("agree"), raw.text("region"), raw.text("unit"), raw.number("health_gender")?.toInt())

    fun healthProfile(owner: MobvoiCloudOwner, raw: JsonObject) = MobvoiHealthUserProfile(owner,
        raw.text("birthday"), raw.number("gender")?.toInt(), raw.text("height"), raw.text("weight"), raw.text("unit"),
        raw.number("goal_today_active")?.toInt(), raw.number("goal_today_exercise")?.toInt(), raw.number("goal_today_step")?.toInt(),
        raw.number("expected_sleep_time")?.toInt(), raw.number("expected_get_up_time")?.toInt(),
        raw.number("heart_rate_warning_fitness_upper_limit")?.toInt(), raw.number("heart_rate_warning_rest_lower_limit")?.toInt(),
        raw.number("heart_rate_warning_rest_upper_limit")?.toInt())

    fun source(raw: JsonObject) = MobvoiHealthDataSource(raw.text("data_source_name") ?: "",
        raw.number("data_source_id"), (raw["device"] as? JsonObject)?.let { MobvoiCloudDevice(it.text("id"), it.text("type"), it.text("model"), it.text("version")) },
        (raw["application"] as? JsonObject)?.text("package_name"), (raw["application"] as? JsonObject)?.text("version"))
    fun point(raw: JsonObject, sourceName: String, timeZone: String?): MobvoiHealthPoint {
        val type = raw.text("data_type")
        val from = raw.number("start_time_millis"); val to = raw.number("end_time_millis")
        val key = if (type != null && from != null && to != null) CloudIdentity.hash(listOf(sourceName, type, from.toString(), to.toString()))
            else CloudIdentity.hash(listOf(sourceName, canonical(raw)))
        return MobvoiHealthPoint(key, sourceName, type, from, to, timeZone, raw.flag("deleted") ?: false,
            raw.text("string_val"), raw.real("fp_val"), raw.number("int_val"))
    }
    fun session(raw: JsonObject): MobvoiHealthSession {
        val source = raw.text("data_source_name") ?: ""
        val id = raw.text("session_id")
        val zone = raw.text("time_zone")
        return MobvoiHealthSession(CloudIdentity.hash(listOf(source, id ?: canonical(raw))), source, id, raw.text("activity_type"),
            raw.number("start_time_ms"), raw.number("end_time_ms"), zone, raw.flag("deleted") ?: false,
            raw.objects("points").map { point(it, source, zone) })
    }
    fun sport(raw: JsonObject): MobvoiSportRecord {
        val id = raw.text("id")
        return MobvoiSportRecord(id ?: CloudIdentity.hash(listOf(canonical(raw))), id, raw.text("device_id"), raw.text("type"),
            raw.number("start_at"), raw.number("end_at"), raw.number("total_motion_time"), raw.real("total_distance"), raw.real("total_calorie"),
            raw.number("total_steps"), raw.real("avg_heart_rate"), raw.number("gps_type")?.toInt(),
            raw.objects("track_points").map { p -> MobvoiSportTrackPoint(p.number("timestamp"), p.number("wall_clock_timestamp"), p.text("gps_point"), p.text("gps_state"),
                p.number("heart_rate")?.toInt(), p.number("cumulative_steps"), p.real("cumulative_distance"), p.real("velocity"), p.real("elevation"), p.real("calorie")) })
    }
    fun canonical(element: JsonElement): String = when (element) {
        is JsonObject -> JsonObject(element.toSortedMap().mapValues { json.parseToJsonElement(canonical(it.value)) }).toString()
        is JsonArray -> JsonArray(element.map { json.parseToJsonElement(canonical(it)) }).toString()
        else -> element.toString()
    }
}
internal fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
internal fun JsonObject.number(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.real(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull
internal fun JsonObject.flag(name: String): Boolean? = (this[name] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.longOrNull?.let { number -> number != 0L } }
internal fun JsonObject.objects(name: String): List<JsonObject> = (this[name] as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()
