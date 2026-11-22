package dev.ticbox.companion.healthbridge

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import androidx.core.net.toUri
import dev.ticbox.companion.protocol.*
import kotlinx.serialization.json.*

/**
 * Stock fitness_summary returns RECORD.HASH as id, not the private numeric RECORD._id.
 * Its projection is ignored and selectionArgs are unsupported. All SQL identifiers/clauses here
 * are fixed; opaque HASH values use UTF-8 hex literals. No account predicate is added.
 * SecurityException is handled by HealthProviderReader: declaring the vendor's signature
 * permission does not grant it to an ordinary installation.
 */
internal class SportProviderReader(private val resolver: ContentResolver, private val providerVersion: String?) {
    fun page(request: JsonObject, cancellation: CancellationSignal): JsonObject {
        val after = request.text("afterKey")
        val consumed = if (after == null) 0 else (request.number("afterId") ?: 0).coerceAtLeast(0)
        var upper = request.text("upperKey")
        if (upper == null) {
            val cursor = resolver.query(URI, null, VISIBLE_RECORDS, null,
                "HASH COLLATE BINARY DESC, _id DESC LIMIT 1", cancellation)
                ?: return queryError("provider_missing")
            upper = cursor.use {
                if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow("id")) else null
            }
        }
        return readPage(request, cancellation, after, consumed, upper)
    }

    private fun readPage(request: JsonObject, cancellation: CancellationSignal, after: String?, consumed: Long,
        upper: String?): JsonObject {
        if (upper == null) return localHealthPage(emptyList(), consumed, null, true, providerVersion,
            nextKey = after)
        val limit = (request.number("limit") ?: LocalHealthProtocol.PAGE_SIZE.toLong())
            .coerceIn(1, LocalHealthProtocol.PAGE_SIZE.toLong()).toInt()
        val where = buildList {
            add(VISIBLE_RECORDS)
            add("HASH COLLATE BINARY<=${sqlKey(upper)}")
            after?.let {
                val key = sqlKey(it)
                // OFFSET belongs only to this HASH. If the previous group disappears, it must
                // never skip the first rows of the following training group.
                add("(HASH COLLATE BINARY>$key OR (HASH COLLATE BINARY=$key AND _id IN " +
                    "(SELECT _id FROM RECORD WHERE DELETED=0 AND $VISIBLE_RECORDS AND HASH COLLATE BINARY=$key " +
                    "ORDER BY _id ASC LIMIT -1 OFFSET $consumed)))")
            }
        }.joinToString(" AND ")
        val cursor = resolver.query(URI, null, where, null,
            "HASH COLLATE BINARY ASC, _id ASC LIMIT ${limit + 1}", cancellation)
            ?: return queryError("provider_missing")
        return cursor.use {
            val rows = mutableListOf<JsonObject>()
            var last = after; var occurrence = consumed; var complete = true; var bytes = 0; var duplicates = 0
            val idColumn = cursor.getColumnIndex("id")
            if (idColumn < 0) throw HealthSchemaException("Missing sport logical ID")
            while (cursor.moveToNext()) {
                cancellation.throwIfCanceled()
                if (rows.size == limit) { complete = false; break }
                val key = cursor.getString(idColumn) ?: throw HealthSchemaException("Missing sport logical ID")
                val index = if (key == last) occurrence else 0
                val raw = cursor.healthRawRow(key, LocalHealthTable.SPORT)
                val row = JsonObject(raw.toMutableMap().apply { put("sourceOccurrence", JsonPrimitive(index)) })
                val size = row.toString().toByteArray(Charsets.UTF_8).size
                if (bytes + size > LocalHealthProtocol.MAX_FRAME_BYTES - 8192) {
                    if (rows.isEmpty()) return queryError("row_exceeds_budget")
                    complete = false; break
                }
                rows += row; bytes += size; last = key; occurrence = index + 1
                if (index > 0) duplicates++
            }
            if (duplicates > 0) android.util.Log.d("TicBoxHealthBridge",
                "Sport page retained $duplicates duplicate logical ID occurrences")
            localHealthPage(rows, occurrence, null, complete, providerVersion, nextKey = last, upperKey = upper)
        }
    }

    companion object {
        val URI: Uri = LocalHealthTable.SPORT.sourceName.toUri()
        // The stock Provider applies these same predicates after LIMIT. Push them before LIMIT
        // so its filtered records cannot make a nonempty source look like an exhausted page.
        private const val VISIBLE_RECORDS = "HASH IS NOT NULL AND HASH<>'' AND START_TIME>0 AND END_TIME>=START_TIME AND TYPE<>-1"
        // The stock Provider rewrites time_from/time_to and scans for `wwid` across the entire
        // selection, including quoted values. Hex encoding preserves every key through that rewrite.
        private fun sqlKey(value: String) = "CAST(X'" + value.toByteArray(Charsets.UTF_8)
            .joinToString("") { "%02x".format(it) } + "' AS TEXT)"
    }
}
