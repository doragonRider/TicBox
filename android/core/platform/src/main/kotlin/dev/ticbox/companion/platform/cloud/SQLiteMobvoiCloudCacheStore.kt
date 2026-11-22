package dev.ticbox.companion.platform.cloud

import android.content.ContentValues
import android.database.Cursor
import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.cloud.*
import dev.ticbox.companion.platform.data.SQLiteDataSyncStore

class SQLiteMobvoiCloudCacheStore(private val database: SQLiteDataSyncStore,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE) : MobvoiCloudCacheStore {
    override suspend fun commit(owner: MobvoiCloudOwner, entries: List<CloudCacheEntry>, window: CloudSyncWindow?) {
        require(entries.all { it.owner == owner })
        database.withDatabase { db ->
            db.beginTransaction()
            try {
                val ownerKey = CloudIdentity.key(owner)
                fun put(incoming: CloudCacheEntry) {
                    val previous = if (incoming.kind == CloudCacheKind.SPORT) db.query("cloud_cache", null,
                        "owner_key=? AND kind=? AND record_key=?", arrayOf(ownerKey, incoming.kind.name, incoming.key), null, null, null)
                        .use { if (it.moveToFirst()) it.entry(owner, incoming.kind) else null } else null
                    val entry = MobvoiSportCache.merge(previous, incoming, diagnostics)
                    val row = db.insertWithOnConflict("cloud_cache", null, ContentValues().apply {
                        put("owner_key", ownerKey); put("kind", entry.kind.name); put("record_key", entry.key)
                        put("source_name", entry.sourceName); put("data_type", entry.dataType); put("start_ms", entry.startMillis)
                        put("end_ms", entry.endMillis); put("time_zone", entry.timeZone); put("deleted", entry.deleted)
                        put("payload", entry.payload); put("updated_at", entry.updatedAtMillis)
                    }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
                    check(row != -1L) { "Cloud cache insert failed" }
                }
                put(CloudCacheEntry(owner, CloudCacheKind.OWNER, ownerKey, payload = MobvoiCloudJson.ownerJson(owner), updatedAtMillis = System.currentTimeMillis()))
                entries.forEach(::put)
                window?.let {
                    val row = db.insertWithOnConflict("cloud_sync_windows", null, ContentValues().apply {
                        put("owner_key", ownerKey); put("kind", it.kind.name); put("from_ms", it.fromMillis); put("to_ms", it.toMillis); put("updated_at", System.currentTimeMillis())
                    }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
                    check(row != -1L) { "Cloud sync window insert failed" }
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }
    override suspend fun query(kind: CloudCacheKind, query: MobvoiHealthCacheQuery): List<CloudCacheEntry> {
        require(query.offset >= 0 && query.limit in 1..500)
        return database.readDatabase { db ->
            val where = mutableListOf("owner_key=?", "kind=?")
            val args = mutableListOf(CloudIdentity.key(query.owner), kind.name)
            if (!query.includeDeleted) where += "deleted=0"
            query.recordKey?.let { where += "record_key=?"; args += it }
            query.sourceName?.let { where += "source_name=?"; args += it }
            query.dataType?.let { where += "data_type=?"; args += it }
            query.fromMillis?.let { where += "(end_ms IS NULL OR end_ms>=?)"; args += it.toString() }
            query.toMillis?.let { where += "(start_ms IS NULL OR start_ms<=?)"; args += it.toString() }
            db.query("cloud_cache", null, where.joinToString(" AND "), args.toTypedArray(), null, null, "coalesce(start_ms,-9223372036854775808),record_key",
                "${query.limit + 1} OFFSET ${query.offset}").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.entry(query.owner, kind)) }
            }
        }
    }
    override suspend fun owners(): List<MobvoiCloudOwner> = database.readDatabase { db ->
        db.query("cloud_cache", arrayOf("payload"), "kind=?", arrayOf(CloudCacheKind.OWNER.name), null, null, "owner_key").use { cursor ->
            buildList { while (cursor.moveToNext()) add(MobvoiCloudJson.owner(cursor.getString(0))) }
        }
    }
    override suspend fun remove(owner: MobvoiCloudOwner, kind: CloudCacheKind, key: String) { database.withDatabase { db ->
        db.delete("cloud_cache", "owner_key=? AND kind=? AND record_key=?", arrayOf(CloudIdentity.key(owner), kind.name, key))
    } }
    override suspend fun clear(owner: MobvoiCloudOwner) { database.withDatabase { db ->
        db.beginTransaction()
        try {
            db.delete("cloud_cache", "owner_key=?", arrayOf(CloudIdentity.key(owner)))
            db.delete("cloud_sync_windows", "owner_key=?", arrayOf(CloudIdentity.key(owner)))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    } }
    private fun Cursor.entry(owner: MobvoiCloudOwner, kind: CloudCacheKind) = CloudCacheEntry(owner, kind, text("record_key"), optionalText("source_name"),
        optionalText("data_type"), optionalLong("start_ms"), optionalLong("end_ms"), optionalText("time_zone"),
        getInt(getColumnIndexOrThrow("deleted")) != 0, text("payload"), getLong(getColumnIndexOrThrow("updated_at")))
    private fun Cursor.text(name: String): String = getString(getColumnIndexOrThrow(name))
    private fun Cursor.optionalText(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.optionalLong(name: String): Long? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
}
