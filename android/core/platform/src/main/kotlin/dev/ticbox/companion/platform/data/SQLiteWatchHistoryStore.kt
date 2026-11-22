package dev.ticbox.companion.platform.data

import android.content.ContentValues
import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.WatchObservationStore

/** Same database and owner as DataApi; a bounded record of real phone-side observations. */
class SQLiteWatchHistoryStore(private val database: SQLiteDataSyncStore, private val maxSamples: Int = 100_000) : WatchObservationStore {
    init { require(maxSamples > 0) }

    override suspend fun append(peer: PeerAddress, kind: WatchSampleKind, atMillis: Long, batteryPercent: Int?, steps: StepSnapshot?) {
        database.withDatabase { db ->
            db.beginTransaction()
            try {
                db.insertOrThrow("observation_samples", null, ContentValues().apply {
                    put("peer", peer.value); put("kind", kind.name); put("received_at", atMillis)
                    if (batteryPercent != null) put("battery", batteryPercent)
                    if (steps != null) { put("steps", steps.currentSteps); put("goal", steps.goal) }
                })
                db.execSQL("DELETE FROM observation_samples WHERE sample_id <= (SELECT sample_id FROM observation_samples ORDER BY sample_id DESC LIMIT 1 OFFSET ?)", arrayOf(maxSamples))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }

    override suspend fun recent(peer: PeerAddress, kind: WatchSampleKind?, limit: Int, beforeId: Long?): List<WatchHistorySample> {
        require(limit in 1..500)
        val where = mutableListOf("peer=?")
        val arguments = mutableListOf(peer.value)
        if (kind != null) { where += "kind=?"; arguments += kind.name }
        if (beforeId != null) { where += "sample_id<?"; arguments += beforeId.toString() }
        return database.readDatabase { db ->
            db.query("observation_samples", arrayOf("sample_id", "kind", "received_at", "battery", "steps", "goal"),
                where.joinToString(" AND "), arguments.toTypedArray(), null, null, "sample_id DESC", limit.toString()).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val savedKind = WatchSampleKind.valueOf(cursor.getString(1))
                        add(WatchHistorySample(cursor.getLong(0), peer, savedKind, cursor.getLong(2),
                            batteryPercent = if (cursor.isNull(3)) null else cursor.getInt(3),
                            steps = if (cursor.isNull(4) || cursor.isNull(5)) null else StepSnapshot(cursor.getInt(4), cursor.getInt(5))))
                    }
                }
            }
        }
    }
}
