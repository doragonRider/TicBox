package dev.ticbox.companion.engine.health

import dev.ticbox.companion.api.*
import java.math.BigDecimal
import kotlinx.serialization.json.*

/** Shared parsing of point/session metadata; unknown or malformed input is retained by the caller. */
internal class HealthRowFields(raw: HealthRawRecord) {
    val issues = linkedSetOf<HealthParseIssue>()
    val root = try { Json { isLenient = true }.parseToJsonElement(raw.json) as? JsonObject }
        catch (_: IllegalArgumentException) { null }
    private val columns = root?.get("columns") as? JsonObject ?: root ?: JsonObject(emptyMap())
    init {
        if (root == null) issues += HealthParseIssue.MALFORMED_ROW
        if (root.lacksSourceCells()) issues += HealthParseIssue.PARTIAL_SOURCE_ROW
    }
    fun text(name: String): String? = (columns[name] as? JsonPrimitive)?.contentOrNull
    fun integer(name: String, required: Boolean = true): Long? {
        val value = text(name)
        if (value == null) { if (required) issues += HealthParseIssue.MISSING_FIELD; return null }
        return healthInteger(value).also { if (it == null) issues += HealthParseIssue.INVALID_INTEGER }
    }
}
/** The watch sent this row without some of its cells: unreadable ones (partial) or ones too large to deliver. */
internal fun JsonObject?.lacksSourceCells(): Boolean =
    this != null && ((this["partial"] as? JsonPrimitive)?.booleanOrNull == true || this["oversizedColumns"] is JsonArray)
internal fun healthInteger(value: String): Long? = try { BigDecimal(value.trim().removePrefix("\uFEFF")).longValueExact() }
    catch (_: NumberFormatException) { null } catch (_: ArithmeticException) { null }
