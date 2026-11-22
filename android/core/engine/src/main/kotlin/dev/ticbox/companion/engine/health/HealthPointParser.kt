package dev.ticbox.companion.engine.health

import dev.ticbox.companion.api.*
import java.math.BigDecimal
import kotlinx.serialization.json.*

/** Current watch fields and DataType/MotionType mappings, without discarding unknown or malformed rows. */
object HealthPointParser {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }
    fun parse(raw: HealthRawRecord): HealthPointRecord {
        val fields = HealthRowFields(raw)
        val issues = fields.issues; val root = fields.root
        fun text(name: String) = fields.text(name)
        fun integer(name: String, required: Boolean = true) = fields.integer(name, required)
        val id = integer("_id", required = false) ?: (root?.get("id") as? JsonPrimitive)?.content?.let(::healthInteger)
            ?: healthInteger(raw.key)
        if (id == null) issues += HealthParseIssue.MISSING_FIELD
        val typeCode = integer("type")
        val type = HealthPointType.fromCode(typeCode)
        if (type == null) issues += HealthParseIssue.UNKNOWN_TYPE
        val start = integer("time_from"); val end = integer("time_to")
        if (start != null && end != null && end < start) issues += HealthParseIssue.INVALID_TIME_RANGE
        val synced = integer("synced"); val deleted = integer("deleted")
        if (listOfNotNull(synced, deleted).any { it !in 0L..1L }) issues += HealthParseIssue.NONSTANDARD_FLAG
        val contents = text("values")
        if (contents == null) issues += HealthParseIssue.MISSING_FIELD
        val layout = HealthPointLayouts.forType(type)
        val values = contents?.split(',')?.mapIndexed { index, token ->
            val value = token.trim().removePrefix("\uFEFF")
            val field = layout?.field(index)
            val decimal = if (field?.text == true) null else
                try { BigDecimal(value).toString() } catch (_: NumberFormatException) { null }
            val status = when {
                decimal != null -> HealthValueStatus.NUMBER
                value.isEmpty() -> HealthValueStatus.EMPTY
                field?.text == true -> HealthValueStatus.TEXT
                value.lowercase() in setOf("nan", "infinity", "+infinity", "-infinity", "inf", "+inf", "-inf") -> HealthValueStatus.NON_FINITE
                else -> HealthValueStatus.TEXT
            }
            if (field?.text != true) when (status) {
                HealthValueStatus.EMPTY, HealthValueStatus.TEXT -> issues += HealthParseIssue.VALUE_NOT_NUMERIC
                HealthValueStatus.NON_FINITE -> issues += HealthParseIssue.NON_FINITE_VALUE
                else -> Unit
            }
            HealthPointValue(index, token, decimal, status, field?.semantic, field?.unit)
        }.orEmpty()
        if (layout?.expectedCount != null && values.size != layout.expectedCount)
            issues += HealthParseIssue.UNEXPECTED_VALUE_COUNT
        val motion = if (type == HealthPointType.MOTION) {
            val codes = values.take(2).map { it.decimal?.let(::healthInteger) }
            if (codes.size < 2 || codes.any { it == null }) issues += HealthParseIssue.INVALID_INTEGER
            if (codes.filterNotNull().any { HealthMotionState.fromCode(it) == null }) issues += HealthParseIssue.UNKNOWN_MOTION_STATE
            HealthMotionTransition(codes.getOrNull(0), codes.getOrNull(1))
        } else null
        if (layout == null ||
            values.any { it.semantic == null }) issues += HealthParseIssue.UNCONFIRMED_VALUE_LAYOUT
        if ((root?.get("partial") as? JsonPrimitive)?.booleanOrNull == true) issues += HealthParseIssue.PARTIAL_SOURCE_ROW
        return HealthPointRecord(raw, id, text("device_id"), text("wwid"), typeCode, start, end, synced, deleted,
            contents, values, motion, issues)
    }
    /** Persist the parsed business model alongside the complete original row in the same transaction. */
    fun encode(point: HealthPointRecord): String = buildJsonObject {
        point.providerId?.let { put("providerId", it) }; point.typeCode?.let { put("typeCode", it) }
        point.deviceId?.let { put("deviceId", it) }; point.vendorUserId?.let { put("vendorUserId", it) }
        point.startMillis?.let { put("startMillis", it) }; point.endMillis?.let { put("endMillis", it) }
        point.vendorSyncCode?.let { put("vendorSyncCode", it) }; point.deletedCode?.let { put("deletedCode", it) }
        point.rawValues?.let { put("rawValues", it) }
        put("values", buildJsonArray { point.values.forEach { value -> add(buildJsonObject {
            put("index", value.index); put("rawToken", value.rawToken); put("status", value.status.name)
            value.decimal?.let { put("decimal", it) }; value.semantic?.let { put("semantic", it) }; value.unit?.let { put("unit", it) }
        }) } })
        point.motion?.let { motion -> put("motion", buildJsonObject {
            motion.stateCode?.let { put("stateCode", it) }; motion.nextStateCode?.let { put("nextStateCode", it) }
        }) }
        put("issues", JsonArray(point.issues.map { JsonPrimitive(it.name) }))
    }.toString()

    fun decode(raw: HealthRawRecord, payload: String): HealthPointRecord {
        return try {
            val data = json.parseToJsonElement(payload).jsonObject
            fun text(key: String) = (data[key] as? JsonPrimitive)?.contentOrNull
            fun number(key: String) = text(key)?.let(::healthInteger)
            val values = data["values"]!!.jsonArray.map { element ->
                val value = element.jsonObject
                fun field(key: String) = (value[key] as? JsonPrimitive)?.contentOrNull
                HealthPointValue(value["index"]!!.jsonPrimitive.int, field("rawToken")!!,
                    field("decimal"), HealthValueStatus.valueOf(field("status")!!), field("semantic"), field("unit"))
            }
            val motion = (data["motion"] as? JsonObject)?.let { m ->
                HealthMotionTransition(m["stateCode"]?.jsonPrimitive?.longOrNull, m["nextStateCode"]?.jsonPrimitive?.longOrNull)
            }
            HealthPointRecord(raw, number("providerId"), text("deviceId"), text("vendorUserId"), number("typeCode"),
                number("startMillis"), number("endMillis"), number("vendorSyncCode"), number("deletedCode"), text("rawValues"), values,
                motion, data["issues"]!!.jsonArray.map { HealthParseIssue.valueOf(it.jsonPrimitive.content) }.toSet())
        } catch (_: Exception) { parse(raw) } // Recover a damaged cache from the original vendor row.
    }
}
