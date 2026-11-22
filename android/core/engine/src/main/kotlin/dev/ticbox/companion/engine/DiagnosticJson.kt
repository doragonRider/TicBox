package dev.ticbox.companion.engine

import dev.ticbox.companion.api.ProtocolDiagnostic
import kotlinx.serialization.json.*

/** Bounded JSONL metadata; numeric IDs retain their exact value without floating-point conversion. */
object DiagnosticJson {
    fun line(diagnostic: ProtocolDiagnostic, atEpochMillis: Long): String {
        var truncated = diagnostic.details.size > 24
        fun bounded(value: String, limit: Int): String {
            if (value.codePointCount(0, value.length) <= limit) return value
            truncated = true
            return value.substring(0, value.offsetByCodePoints(0, limit))
        }
        val fields = linkedMapOf<String, JsonElement>(
            "time" to JsonPrimitive(atEpochMillis),
            "severity" to JsonPrimitive(diagnostic.severity.name),
            "component" to JsonPrimitive(bounded(diagnostic.component, 96)),
            "event" to JsonPrimitive(bounded(diagnostic.event, 128)),
            "sessionId" to JsonPrimitive(diagnostic.sessionId),
        )
        diagnostic.peer?.let { fields["peer"] = JsonPrimitive(it.value) }
        diagnostic.channelId?.let { fields["channelId"] = JsonPrimitive(it.toString()) }
        diagnostic.localIsOpener?.let { fields["localIsOpener"] = JsonPrimitive(it) }
        fields["details"] = JsonObject(diagnostic.details.entries.take(24).associate {
            bounded(it.key, 96) to JsonPrimitive(bounded(it.value, 256))
        })
        fields["truncated"] = JsonPrimitive(truncated)
        return JsonObject(fields).toString()
    }
}
