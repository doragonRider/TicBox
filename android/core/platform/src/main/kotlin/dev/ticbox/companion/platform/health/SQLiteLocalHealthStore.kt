package dev.ticbox.companion.platform.health

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.health.*
import dev.ticbox.companion.platform.data.SQLiteDataSyncStore
import dev.ticbox.companion.protocol.*
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Shared no-backup SQLite database; rows and scan cursors are isolated by profile, peer and table. */
class SQLiteLocalHealthStore(private val database: SQLiteDataSyncStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE) : LocalHealthStore, HealthPointReader, HealthSleepReader, HealthFitnessReader {
    override suspend fun begin(source: HealthRecordSource.LocalDevice, table: LocalHealthTable): LocalHealthCheckpoint = database.withDatabase { db ->
        db.query("local_health_sources", null, "local_user=? AND peer=? AND kind=?", source.args(table), null, null, null).use { cursor ->
            if (cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow("in_progress")) != 0) {
                val sport = if (table == LocalHealthTable.SPORT) cursor.getColumnIndexOrThrow("upper_id").let {
                    if (cursor.isNull(it)) null else LocalHealthProtocol.parse(cursor.getBlob(it))
                } else null
                LocalHealthCheckpoint(cursor.optionalLong("after_id"),
                    if (table == LocalHealthTable.SPORT) null else cursor.optionalLong("upper_id"), true,
                    cursor.getInt(cursor.getColumnIndexOrThrow("partial")) != 0,
                    sport?.text("afterKey"), sport?.text("upperKey"), hasBaseline = true)
            } else LocalHealthCheckpoint(hasBaseline = cursor.count > 0)
        }
    }
    override suspend fun lastSuccess(source: HealthRecordSource.LocalDevice): Long? = database.readDatabase { db ->
        // An imported private fitness page must not defer the automatic Provider scan.
        db.rawQuery("SELECT MAX(last_success) FROM local_health_sources WHERE local_user=? AND peer=? AND kind IN ('POINT','SESSION','SPORT')",
            arrayOf(source.localUserId, source.peer.value)).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }
    override suspend fun commit(source: HealthRecordSource.LocalDevice, table: LocalHealthTable,
        page: LocalHealthPage, checkpoint: LocalHealthCheckpoint) =
        commitPage(source, table.recordKind, table.sourceName, page, checkpoint)

    /** Acquired private-table pages use the same local transaction, identity isolation and checkpoint.
     * This accepts already acquired rows; it neither requests root nor opens the watch database. */
    suspend fun commitFitnessPage(source: HealthRecordSource.LocalDevice, kind: HealthRecordKind,
        page: LocalHealthPage, checkpoint: LocalHealthCheckpoint) {
        val sourceName = when (kind) {
            HealthRecordKind.FITNESS_RECORD -> "sqlite://com.mobvoi.wear.fitness.at/fitness.db/RECORD"
            HealthRecordKind.FITNESS_POINT -> "sqlite://com.mobvoi.wear.fitness.at/fitness.db/POINT"
            else -> error("Expected a private fitness table")
        }
        commitPage(source, kind, sourceName, page, checkpoint)
    }

    private suspend fun commitPage(source: HealthRecordSource.LocalDevice, kind: HealthRecordKind, sourceName: String,
        page: LocalHealthPage, checkpoint: LocalHealthCheckpoint) {
        val issueCounts = linkedMapOf<String, Int>()
        database.withDatabase { db ->
            db.beginTransaction()
            try {
                val timestamp = now()
                val sourceValues = ContentValues().apply {
                    put("local_user", source.localUserId); put("peer", source.peer.value); put("kind", kind.name)
                    put("after_id", checkpoint.afterId)
                    if (kind == HealthRecordKind.SPORT) {
                        // This non-STRICT SQLite column has INTEGER affinity, not a type constraint.
                        // SPORT stores its opaque boundary as a BLOB; POINT/SESSION keep integer IDs.
                        // Preserve the opaque SPORT cursor alongside the other local table checkpoints.
                        put("upper_id", buildJsonObject {
                            checkpoint.afterKey?.let { put("afterKey", it) }
                            checkpoint.upperKey?.let { put("upperKey", it) }
                        }.toString().toByteArray(Charsets.UTF_8))
                    } else put("upper_id", checkpoint.upperId)
                    put("in_progress", checkpoint.inProgress); put("partial", checkpoint.partial); put("accessible", true)
                    page.providerVersion?.let { put("provider_version", it) }
                    if (!checkpoint.inProgress) put("last_success", timestamp)
                }
                if (db.update("local_health_sources", sourceValues, "local_user=? AND peer=? AND kind=?", source.args(kind)) == 0)
                    db.insertOrThrow("local_health_sources", null, sourceValues)
                page.rows.forEach { row ->
                    var payload = row.raw.toString()
                    val provisionalKey = row.id?.let { if (kind == HealthRecordKind.SESSION) "cursor:$it" else it.toString() }
                        ?: row.raw.text("bridgeKey") ?: "raw:" + MessageDigest.getInstance("SHA-256")
                        .digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
                    var raw = HealthRawRecord(source, kind, provisionalKey, sourceName,
                        row.text("type"), row.number("time_from"), row.number("time_to"), null,
                        row.number("deleted")?.let { it != 0L } ?: (row.columns.flag("deleted") == true), timestamp, payload)
                    var parsed = parseRow(kind, raw, db)
                    val old = db.query("local_health_records", arrayOf("payload"),
                        "local_user=? AND peer=? AND kind=? AND record_key=?", source.args(kind) + parsed.key,
                        null, null, null).use { if (it.moveToFirst()) LocalHealthProtocol.parse(it.getString(0).toByteArray()) else null }
                    val revision = row.raw.number("bridgeRevision")
                    if (revision != null && old?.text("bridgeEpoch") == row.raw.text("bridgeEpoch") &&
                        (old?.number("bridgeRevision") ?: -1) >= revision) return@forEach
                    if (row.raw.flag("sourceAbsent") == true) {
                        // Source retention is not a request to destroy a phone archive or its parsed values.
                        if (old != null) db.update("local_health_records", ContentValues().apply {
                            put("payload", JsonObject(old.toMutableMap().apply {
                                put("sourceAbsent", JsonPrimitive(true))
                                row.raw["bridgeRevision"]?.let { put("bridgeRevision", it) }
                                row.raw["bridgeEpoch"]?.let { put("bridgeEpoch", it) }
                            }).toString()); put("updated_at", timestamp)
                        }, "local_user=? AND peer=? AND kind=? AND record_key=?", source.args(kind) + parsed.key)
                        return@forEach
                    }
                    if ((row.partial || row.oversized) && old != null) {
                        // Keep the last readable value for unavailable or oversized cells; NULL that was
                        // actually read is still an update. A complete later observation can remove a column.
                        payload = JsonObject((old + row.raw).toMutableMap().apply {
                            remove("sourceAbsent")
                            put("columns", JsonObject((old["columns"] as? JsonObject).orEmpty() + row.columns))
                            put("storageTypes", JsonObject((old["storageTypes"] as? JsonObject).orEmpty() +
                                (row.raw["storageTypes"] as? JsonObject).orEmpty()))
                        }).toString()
                        raw = raw.copy(json = payload)
                        parsed = parseRow(kind, raw, db)
                    }
                    db.insertWithOnConflict("local_health_records", null, ContentValues().apply {
                        put("local_user", source.localUserId); put("peer", source.peer.value); put("kind", kind.name)
                        put("record_key", parsed.key); put("source_name", sourceName); put("device_id", parsed.deviceId)
                        put("parent_key", parsed.parentKey)
                        put("data_type", parsed.typeCode?.toString() ?: row.text("type"))
                        put("start_ms", parsed.startMillis); put("end_ms", parsed.endMillis)
                        put("deleted", parsed.deleted ?: raw.deleted); put("payload", payload); put("updated_at", timestamp)
                        put("parsed_payload", parsed.payload)
                    }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Local health insert failed" } }
                    parsed.issues.forEach { issue -> issueCounts[issue] = issueCounts.getOrDefault(issue, 0) + 1 }
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        if (issueCounts.isNotEmpty()) runCatching { diagnostics.record(ProtocolDiagnostic("health_parser", "page_parse_issues",
            details = mapOf("kind" to kind.name, "records" to page.rows.size.toString(),
                "issues" to issueCounts.entries.joinToString(",") { "${it.key}:${it.value}" }))) }
    }

    private data class ParsedRow(val key: String, val deviceId: String?, val typeCode: Long?,
        val startMillis: Long?, val endMillis: Long?, val deleted: Boolean?, val payload: String,
        val issues: Set<String>, val parentKey: String? = null)
    private fun parseRow(kind: HealthRecordKind, raw: HealthRawRecord, db: SQLiteDatabase): ParsedRow = when (kind) {
        HealthRecordKind.POINT -> HealthPointParser.parse(raw).let { point ->
            ParsedRow(point.providerId?.toString() ?: raw.key, point.deviceId, point.typeCode, point.startMillis,
                point.endMillis, point.deletedCode?.let { it != 0L }, HealthPointParser.encode(point), point.issues.map { it.name }.toSet())
        }
        HealthRecordKind.SESSION -> HealthSleepParser.parse(raw).let { session ->
            // The text data_session.id is the logical identity; envelope.id is only the scan cursor.
            ParsedRow(session.id ?: raw.key, session.deviceId, session.typeCode, session.startMillis,
                session.endMillis, session.deletedCode?.let { it != 0L }, HealthSleepParser.encode(session), session.issues.map { it.name }.toSet())
        }
        HealthRecordKind.SPORT -> HealthSportParser.parse(raw).let { sport ->
            ParsedRow(sport.recordKey ?: raw.key, sport.deviceId, sport.typeCode, sport.startMillis,
                sport.endMillis, sport.deletedCode?.let { it != 0L }, HealthSportParser.encode(sport), sport.issues.map { it.name }.toSet())
        }
        HealthRecordKind.FITNESS_RECORD -> HealthFitnessParser.parseRecord(raw).let { record ->
            ParsedRow(record.id?.toString() ?: raw.key, record.deviceId, record.typeCode, record.startMillis,
                record.endMillis, record.deletedCode?.let { it != 0L }, HealthFitnessParser.encodeRecord(record),
                record.issues.map { it.kind.name }.toSet())
        }
        HealthRecordKind.FITNESS_POINT -> HealthFitnessParser.parsePoint(raw).let { unassociated ->
            val parent = unassociated.recordId?.let { fitnessParent(db, raw.source as HealthRecordSource.LocalDevice, it) }
            val point = if (parent == null) unassociated else HealthFitnessParser.parsePoint(raw, parent)
            // Reads also resolve the current parent: RECORD pages may arrive later or update its mode.
            ParsedRow(point.id?.toString() ?: raw.key, null, null, point.timestampMillis, point.timestampMillis,
                null, HealthFitnessParser.encodePoint(point), point.issues.map { it.kind.name }.toSet(), point.recordId?.toString())
        }
        else -> error("Unsupported local health table")
    }

    override suspend fun sources(): List<HealthRecordSource> = database.readDatabase { db ->
        db.query(true, "local_health_sources", arrayOf("local_user", "peer"), "accessible=1", null,
            null, null, "local_user,peer", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(HealthRecordSource.LocalDevice(cursor.getString(0), PeerAddress(cursor.getString(1)))) }
        }
    }
    override suspend fun records(kind: HealthRecordKind, query: HealthRecordQuery): HealthRecordPage {
        when (kind) {
            HealthRecordKind.POINT, HealthRecordKind.SESSION, HealthRecordKind.SPORT,
            HealthRecordKind.FITNESS_RECORD, HealthRecordKind.FITNESS_POINT -> Unit
            else -> return unavailable()
        }
        val page = queryRows(kind, query) ?: return unavailable()
        return HealthRecordPage(page.items.map { it.raw }, page.nextOffset)
    }
    override suspend fun points(query: HealthPointQuery): HealthPointPage {
        val page = queryRows(HealthRecordKind.POINT, HealthRecordQuery(query.source, query.fromMillis, query.toMillis,
            query.typeCode?.toString(), includeDeleted = query.includeDeleted, offset = query.offset,
            limit = query.limit, recordKey = query.recordKey))
            ?: return HealthPointPage(emptyList(), null, HealthRecordReadStatus.SOURCE_UNAVAILABLE)
        return HealthPointPage(page.items.map { HealthPointParser.decode(it.raw, it.parsed) }, page.nextOffset)
    }
    override suspend fun sleepRecords(query: HealthSleepQuery): HealthSleepPage = database.readDatabase { db ->
        val page = queryRows(db, HealthRecordKind.SESSION, HealthRecordQuery(query.source, query.fromMillis, query.toMillis,
            HealthSleepRecord.AUTO_SLEEP_TYPE_CODE.toString(), includeDeleted = query.includeDeleted, offset = query.offset,
            limit = query.limit, recordKey = query.recordKey))
            ?: return@readDatabase HealthSleepPage(emptyList(), null, HealthRecordReadStatus.SOURCE_UNAVAILABLE)
        val pointsStatus = if (available(db, query.source, HealthRecordKind.POINT)) HealthRecordReadStatus.AVAILABLE
            else HealthRecordReadStatus.SOURCE_UNAVAILABLE
        val records = page.items.map { stored ->
            val session = HealthSleepParser.decode(stored.raw, stored.parsed)
            val points = if (pointsStatus == HealthRecordReadStatus.AVAILABLE)
                relatedPoints(db, query.source, session, query.includeDeleted) else emptyList()
            HealthSleepParser.record(stored.raw, session, points, pointsStatus)
        }
        HealthSleepPage(records, page.nextOffset)
    }

    override suspend fun fitnessRecords(query: HealthFitnessRecordQuery): HealthFitnessRecordPage {
        val page = queryRows(HealthRecordKind.FITNESS_RECORD, HealthRecordQuery(query.source,
            query.fromMillis, query.toMillis, query.typeCode?.toString(), includeDeleted = query.includeDeleted,
            offset = query.offset, limit = query.limit, recordKey = query.recordKey))
            ?: return HealthFitnessRecordPage(emptyList(), null, HealthRecordReadStatus.SOURCE_UNAVAILABLE)
        return HealthFitnessRecordPage(page.items.map { HealthFitnessParser.parseRecord(it.raw) }, page.nextOffset)
    }

    override suspend fun fitnessPoints(query: HealthFitnessPointQuery): HealthFitnessPointPage = database.readDatabase { db ->
        val page = queryRows(db, HealthRecordKind.FITNESS_POINT, HealthRecordQuery(query.source,
            query.fromMillis, query.toMillis, offset = query.offset, limit = query.limit), parentKey = query.recordId.toString())
            ?: return@readDatabase HealthFitnessPointPage(emptyList(), null, HealthRecordReadStatus.SOURCE_UNAVAILABLE)
        val parent = fitnessParent(db, query.source, query.recordId)
        HealthFitnessPointPage(page.items.map { HealthFitnessParser.parsePoint(it.raw, parent) }, page.nextOffset)
    }

    private fun fitnessParent(db: SQLiteDatabase, source: HealthRecordSource.LocalDevice, recordId: Long): HealthFitnessRecord? =
        queryRows(db, HealthRecordKind.FITNESS_RECORD, HealthRecordQuery(source,
            includeDeleted = true, recordKey = recordId.toString(), limit = 1))
            ?.items?.firstOrNull()?.let { HealthFitnessParser.parseRecord(it.raw) }

    private data class StoredRecord(val raw: HealthRawRecord, val parsed: String)
    private data class StoredPage(val items: List<StoredRecord>, val nextOffset: Int?)
    private suspend fun queryRows(kind: HealthRecordKind, query: HealthRecordQuery): StoredPage? =
        database.readDatabase { db -> queryRows(db, kind, query) }

    /** Runs under the caller's database access, including session/point association reads. */
    private fun queryRows(db: SQLiteDatabase, kind: HealthRecordKind, query: HealthRecordQuery, parentKey: String? = null): StoredPage? {
        val source = query.source as? HealthRecordSource.LocalDevice ?: return null
        if (!available(db, source, kind)) return null
        val where = mutableListOf("local_user=?", "peer=?", "kind=?"); val args = source.args(kind).toMutableList()
        parentKey?.let { where += "parent_key=?"; args += it }
        if (!query.includeDeleted) where += "deleted=0"
        query.recordKey?.let { where += "record_key=?"; args += it }
        query.sourceName?.let { where += "source_name=?"; args += it }
        query.dataType?.let { where += "data_type=?"; args += it }
        query.fromMillis?.let {
            where += if (kind == HealthRecordKind.SESSION || kind == HealthRecordKind.FITNESS_RECORD)
                "(end_ms IS NULL OR end_ms<=0 OR end_ms>=?)"
                else "(end_ms IS NULL OR end_ms>=?)"
            args += it.toString()
        }
        query.toMillis?.let { where += "(start_ms IS NULL OR start_ms<=?)"; args += it.toString() }
        val entries = db.query("local_health_records", null, where.joinToString(" AND "), args.toTypedArray(), null, null,
            "coalesce(start_ms,-9223372036854775808),record_key", "${query.limit + 1} OFFSET ${query.offset}").use { cursor ->
            buildList { while (cursor.moveToNext()) add(readRecord(cursor, source, kind)) }
        }
        return StoredPage(entries.take(query.limit), if (entries.size > query.limit) query.offset + query.limit else null)
    }

    private fun available(db: SQLiteDatabase, source: HealthRecordSource.LocalDevice, kind: HealthRecordKind): Boolean =
        db.query("local_health_sources", arrayOf("accessible"), "local_user=? AND peer=? AND kind=?", source.args(kind), null, null, null)
            .use { it.moveToFirst() && it.getInt(0) != 0 }

    private fun relatedPoints(db: SQLiteDatabase, source: HealthRecordSource.LocalDevice,
        session: HealthSessionData, includeDeleted: Boolean): List<HealthPointRecord> {
        val start = session.startMillis ?: return emptyList()
        val deviceId = session.deviceId ?: return emptyList()
        val types = HealthSleepParser.relatedTypeCodes
        val where = mutableListOf("local_user=?", "peer=?", "kind=?", "device_id=?", "end_ms>=?",
            "data_type IN (${types.joinToString(",") { "?" }})")
        val args = source.args(LocalHealthTable.POINT).toMutableList().apply {
            add(deviceId); add(start.toString()); addAll(types.map(Long::toString))
        }
        if (!includeDeleted) where += "deleted=0"
        session.endMillis?.takeIf { it > 0 }?.let { where += "end_ms<=?"; args += it.toString() }
        // All candidates are read. record()/belongsToSession applies each metric's precise boundaries.
        return db.query("local_health_records", null, where.joinToString(" AND "), args.toTypedArray(), null, null,
            "end_ms,record_key").use { cursor ->
            buildList { while (cursor.moveToNext()) {
                val stored = readRecord(cursor, source, HealthRecordKind.POINT)
                add(HealthPointParser.decode(stored.raw, stored.parsed))
            } }
        }
    }
    private fun readRecord(cursor: Cursor, source: HealthRecordSource.LocalDevice, kind: HealthRecordKind) =
        StoredRecord(HealthRawRecord(source, kind, cursor.text("record_key"), cursor.text("source_name"),
            cursor.optionalText("data_type"), cursor.optionalLong("start_ms"), cursor.optionalLong("end_ms"), null,
            cursor.getInt(cursor.getColumnIndexOrThrow("deleted")) != 0, cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
            cursor.text("payload")), cursor.text("parsed_payload"))

    private fun unavailable() = HealthRecordPage(emptyList(), null, HealthRecordReadStatus.SOURCE_UNAVAILABLE)
    private fun HealthRecordSource.LocalDevice.args(table: LocalHealthTable) = args(table.recordKind)
    private fun HealthRecordSource.LocalDevice.args(kind: HealthRecordKind) = arrayOf(localUserId, peer.value, kind.name)
    private val LocalHealthTable.recordKind get() = when (this) {
        LocalHealthTable.POINT -> HealthRecordKind.POINT
        LocalHealthTable.SESSION -> HealthRecordKind.SESSION
        LocalHealthTable.SPORT -> HealthRecordKind.SPORT
        LocalHealthTable.FITNESS_RECORD -> HealthRecordKind.FITNESS_RECORD
        LocalHealthTable.FITNESS_POINT -> HealthRecordKind.FITNESS_POINT
    }
    private fun Cursor.text(name: String) = getString(getColumnIndexOrThrow(name))
    private fun Cursor.optionalText(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.optionalLong(name: String): Long? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
}
