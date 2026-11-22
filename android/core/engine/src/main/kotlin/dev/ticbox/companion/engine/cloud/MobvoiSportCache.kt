package dev.ticbox.companion.engine.cloud

import dev.ticbox.companion.api.*
import kotlinx.serialization.json.*

/** Merge only sport projections. Other cache kinds and nested JSON values are replaced verbatim. */
object MobvoiSportCache {
    fun merge(previous: CloudCacheEntry?, incoming: CloudCacheEntry, diagnostics: DiagnosticSink = DiagnosticSink.NONE): CloudCacheEntry {
        if (incoming.kind != CloudCacheKind.SPORT || previous == null) return incoming
        fun parse(entry: CloudCacheEntry, part: String): JsonObject? = try { MobvoiCloudJson.parse(entry.payload.toByteArray()) }
        catch (problem: Exception) {
            runCatching { diagnostics.record(ProtocolDiagnostic("cloud_health", "sport_cache_merge_unavailable", details =
                mapOf("part" to part, "exception" to problem.javaClass.simpleName, "decision" to "retain incoming raw record"))) }
            null
        }
        val old = parse(previous, "previous") ?: return incoming
        val next = parse(incoming, "incoming") ?: return incoming
        val track = next["track_points"]
        val omittedTrack = !incoming.sportTracksRequested && (track == null || track is JsonNull || (track is JsonArray && track.isEmpty()))
        val fields = if (omittedTrack) next.filterKeys { it != "track_points" } else next
        val merged = JsonObject(old + fields)
        return incoming.copy(
            payload = merged.toString(),
            dataType = if ("type" in next) incoming.dataType else previous.dataType,
            startMillis = if ("start_at" in next) incoming.startMillis else previous.startMillis,
            endMillis = if ("end_at" in next) incoming.endMillis else previous.endMillis,
        )
    }
}
