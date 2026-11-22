package dev.ticbox.companion.engine.health

import dev.ticbox.companion.api.*
import kotlinx.serialization.json.*

/** The original eight data_session fields. This is independent of point DataType numeric codes. */
data class HealthSessionData(val id: String?, val deviceId: String?, val vendorUserId: String?, val typeCode: Long?,
    val startMillis: Long?, val endMillis: Long?, val vendorSyncCode: Long?, val deletedCode: Long?,
    val issues: Set<HealthParseIssue>) {
    override fun toString() = "HealthSessionData(contents=<redacted>)"
}

object HealthSleepParser {
    val relatedTypeCodes = setOf(1L, 2L, 28L, 38L, 42L)
    fun parse(raw: HealthRawRecord): HealthSessionData {
        val fields = HealthRowFields(raw)
        // The envelope id is a rowid paging coordinate, never the session's original TEXT id.
        val id = ((fields.root?.get("columns") as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
        if (id == null) fields.issues += HealthParseIssue.MISSING_FIELD
        val type = fields.integer("type")
        val start = fields.integer("time_from"); val end = fields.integer("time_to")
        if (end == 0L) fields.issues += HealthParseIssue.OPEN_SESSION
        else if (start != null && end != null && end < start) fields.issues += HealthParseIssue.INVALID_TIME_RANGE
        val synced = fields.integer("synced"); val deleted = fields.integer("deleted")
        if (deleted != null && deleted !in 0L..1L) fields.issues += HealthParseIssue.NONSTANDARD_FLAG
        return HealthSessionData(id, fields.text("device_id"), fields.text("wwid"), type, start, end, synced, deleted, fields.issues)
    }
    fun encode(data: HealthSessionData): String = buildJsonObject {
        data.id?.let { put("id", it) }; data.deviceId?.let { put("deviceId", it) }; data.vendorUserId?.let { put("vendorUserId", it) }
        data.typeCode?.let { put("typeCode", it) }; data.startMillis?.let { put("startMillis", it) }; data.endMillis?.let { put("endMillis", it) }
        data.vendorSyncCode?.let { put("vendorSyncCode", it) }; data.deletedCode?.let { put("deletedCode", it) }
        put("issues", JsonArray(data.issues.map { JsonPrimitive(it.name) }))
    }.toString()
    fun decode(raw: HealthRawRecord, payload: String): HealthSessionData = try {
        val data = Json.parseToJsonElement(payload).jsonObject
        fun text(key: String) = (data[key] as? JsonPrimitive)?.contentOrNull
        fun number(key: String) = text(key)?.let(::healthInteger)
        HealthSessionData(text("id"), text("deviceId"), text("vendorUserId"), number("typeCode"), number("startMillis"),
            number("endMillis"), number("vendorSyncCode"), number("deletedCode"),
            data["issues"]!!.jsonArray.map { HealthParseIssue.valueOf(it.jsonPrimitive.content) }.toSet())
    } catch (_: Exception) { parse(raw) }

    /** Motion uses (start,end]; other measurements use [start,end), as in the current APK DAO calls. */
    fun belongsToSession(data: HealthSessionData, point: HealthPointRecord): Boolean {
        if (data.deviceId == null || point.deviceId != data.deviceId || point.typeCode !in relatedTypeCodes) return false
        val start = data.startMillis ?: return false
        val time = point.endMillis ?: return false
        val end = data.endMillis?.takeIf { it > 0 }
        return if (point.type == HealthPointType.MOTION) time > start && (end == null || time <= end)
            else time >= start && (end == null || time < end)
    }
    fun record(raw: HealthRawRecord, data: HealthSessionData, points: List<HealthPointRecord>, status: HealthRecordReadStatus) =
        HealthSleepRecord(raw, data.id, data.deviceId, data.vendorUserId, data.typeCode, data.startMillis, data.endMillis,
            data.vendorSyncCode, data.deletedCode, points.filter { belongsToSession(data, it) }, status, data.issues)
}
