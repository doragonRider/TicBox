package dev.ticbox.companion.engine.health

import dev.ticbox.companion.api.*
import java.math.BigDecimal
import kotlinx.serialization.json.*

/** The stock fitness_summary fields. Sport type numbers are independent of health point types. */
data class HealthSportData(val id: String?, val occurrence: Long, val deviceId: String?, val vendorUserId: String?,
    val typeCode: Long?, val startMillis: Long?, val endMillis: Long?, val durationMillis: Long?,
    val calorieDecimal: String?, val averageHeartRateBpm: Long?, val vendorSyncCode: Long?,
    val deletedCode: Long?, val issues: Set<HealthParseIssue>) {
    /** Include the occurrence because the stock HASH index is not unique. The original id stays separate. */
    val recordKey: String? get() = id?.let { buildJsonArray { add(it); add(occurrence) }.toString() }
    override fun toString() = "HealthSportData(contents=<redacted>)"
}

object HealthSportParser {
    fun parse(raw: HealthRawRecord): HealthSportData {
        val fields = HealthRowFields(raw)
        val id = fields.text("id")
        if (id == null) fields.issues += HealthParseIssue.MISSING_FIELD
        val occurrence = (fields.root?.get("sourceOccurrence") as? JsonPrimitive)?.longOrNull ?: 0
        val type = fields.integer("type")
        val start = fields.integer("time_from"); val end = fields.integer("time_to")
        if (start != null && end != null && end < start) fields.issues += HealthParseIssue.INVALID_TIME_RANGE
        val duration = fields.integer("during"); val heart = fields.integer("heart")
        val calorie = fields.text("calorie")?.let { text ->
            try { BigDecimal(text.trim()).toString() }
            catch (_: NumberFormatException) { fields.issues += HealthParseIssue.VALUE_NOT_NUMERIC; null }
        }
        val synced = fields.integer("synced"); val deleted = fields.integer("deleted", required = false)
        return HealthSportData(id, occurrence, fields.text("device_id"), fields.text("wwid"), type, start, end,
            duration, calorie, heart, synced, deleted, fields.issues)
    }

    fun encode(data: HealthSportData): String = buildJsonObject {
        data.id?.let { put("id", it) }; put("occurrence", data.occurrence)
        data.deviceId?.let { put("deviceId", it) }; data.vendorUserId?.let { put("vendorUserId", it) }
        data.typeCode?.let { put("typeCode", it) }; data.startMillis?.let { put("startMillis", it) }
        data.endMillis?.let { put("endMillis", it) }; data.durationMillis?.let { put("durationMillis", it) }
        data.calorieDecimal?.let { put("calorieDecimal", it) }; data.averageHeartRateBpm?.let { put("averageHeartRateBpm", it) }
        data.vendorSyncCode?.let { put("vendorSyncCode", it) }; data.deletedCode?.let { put("deletedCode", it) }
        put("issues", JsonArray(data.issues.map { JsonPrimitive(it.name) }))
    }.toString()
}
