package dev.ticbox.companion.engine

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.*

internal data class StatusNotice(val event: String, val details: Map<String, String>)
internal class StatusReadException(val field: String, val reason: String) : IllegalArgumentException()

/** Shared battery/status syntax. Notices contain representation metadata, never measurement values. */
internal object WatchStatusCodec {
    fun battery(payload: ByteArray, notice: (StatusNotice) -> Unit = {}): Int {
        val text = text(payload, notice)
        val quoted = text.startsWith('"')
        val token = if (quoted) (Json.parseToJsonElement(text) as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw StatusReadException("battery", "expected_numeric_text") else text
        val value = integer(token, "battery", if (quoted) "string" else "text", notice)
        if (value !in 0..100) throw StatusReadException("battery", "outside_0_to_100")
        return value
    }

    fun integer(token: String, field: String, representation: String, notice: (StatusNotice) -> Unit): Int {
        val normalized = token.trim()
        val value = try { BigDecimal(normalized).intValueExact() }
        catch (_: NumberFormatException) { throw StatusReadException(field, "not_decimal_number") }
        catch (_: ArithmeticException) { throw StatusReadException(field, "fractional_or_outside_java_int") }
        if (representation == "string" || token != value.toString()) notice(StatusNotice("status_number_interpreted", mapOf(
            "field" to field, "representation" to representation, "characters" to token.length.toString(),
            "surroundingWhitespace" to (token != normalized).toString(),
            "exponentNotation" to normalized.contains('e', ignoreCase = true).toString(),
            "decimalPoint" to normalized.contains('.').toString(),
            "decision" to "parse equivalent exact integer; apply domain range separately",
        )))
        return value
    }

    fun text(payload: ByteArray, notice: (StatusNotice) -> Unit): String {
        require(payload.size <= 16_384) { "Client business JSON/text budget exceeded" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(payload)).toString()
        var depth = 0; var quoted = false; var escaped = false
        for (character in text) {
            if (quoted) {
                if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 16) { "Client JSON depth budget exceeded" } }
                '}', ']' -> { depth--; require(depth >= 0) }
            }
        }
        val normalized = text.trim().removePrefix("\uFEFF").trim()
        if (normalized != text) notice(StatusNotice("status_text_normalized", mapOf(
            "decision" to "remove surrounding whitespace or leading BOM before interpretation")))
        return normalized
    }
}
