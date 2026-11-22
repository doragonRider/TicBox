package dev.ticbox.companion.platform.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.data.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persistent DataApi store in noBackupFilesDir. Item mutation, source cursor and relay sequence
 * are one SQLite transaction. Asset bytes are bounded BLOBs with additive AppKey ACLs.
 * Every CRUD method runs on IO; close marks closed promptly and lets an in-flight transaction finish.
 * The database uses WAL: read-only queries share a read lock and run beside each other and beside
 * the single writer, so health/history screens are not queued behind sync transactions.
 */
class SQLiteDataSyncStore(
    context: Context,
    databaseName: String = "data-sync.sqlite",
    private val maxAssetStorageBytes: Long = 64L * 1024 * 1024,
    private val maxDatabaseBytes: Long = 128L * 1024 * 1024,
) : DataSyncStore {
    private val closed = AtomicBoolean()
    private val gate = ReentrantReadWriteLock()
    private val helper: SQLiteOpenHelper

    init {
        require(databaseName.isNotBlank() && File(databaseName).name == databaseName) { "Expected a database basename" }
        require(maxAssetStorageBytes > 0 && maxDatabaseBytes > 0)
        val app = context.applicationContext
        helper = object : SQLiteOpenHelper(app, File(app.noBackupFilesDir, databaseName).absolutePath, null, DataSyncSqlSchema.VERSION) {
            override fun onConfigure(db: SQLiteDatabase) {
                db.setForeignKeyConstraintsEnabled(true)
                db.enableWriteAheadLogging()
            }
            override fun onCreate(db: SQLiteDatabase) { DataSyncSqlSchema.statements.forEach(db::execSQL) }
            override fun onOpen(db: SQLiteDatabase) {
                super.onOpen(db)
                val pageSize = db.rawQuery("PRAGMA page_size", null).use { it.moveToFirst(); it.getLong(0) }
                val pages = (maxDatabaseBytes / pageSize).coerceAtLeast(1)
                db.rawQuery("PRAGMA max_page_count=$pages", null).use { it.moveToFirst() }
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                error("No migration from data sync schema $oldVersion to $newVersion")
            }
        }
    }

    private suspend fun <T> access(block: (SQLiteDatabase) -> T): T = locked(gate.writeLock(), block)
    private suspend fun <T> read(block: (SQLiteDatabase) -> T): T = locked(gate.readLock(), block)
    private suspend fun <T> locked(lock: Lock, block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        try {
            lock.withLock {
                check(!closed.get()) { "Data sync store closed" }
                block(helper.writableDatabase)
            }
        } finally { if (closed.get()) closeWhenIdle() }
    }
    private fun closeWhenIdle() {
        val exclusive = gate.writeLock()
        if (exclusive.tryLock()) try { helper.close() } finally { exclusive.unlock() }
    }
    /** Other companion repositories share this helper's IO scheduling, lock and close ownership. */
    internal suspend fun <T> withDatabase(block: (SQLiteDatabase) -> T): T = access(block)
    /** Read-only variant of [withDatabase]; [block] must not write. */
    internal suspend fun <T> readDatabase(block: (SQLiteDatabase) -> T): T = read(block)
    private fun <T> transaction(db: SQLiteDatabase, block: () -> T): T {
        db.beginTransaction()
        try { val value = block(); db.setTransactionSuccessful(); return value }
        finally { db.endTransaction() }
    }
    private fun remember(db: SQLiteDatabase, scope: DataScope): String {
        val id = scope.id()
        db.insertWithOnConflict("scopes", null, ContentValues().apply {
            put("scope_id", id); put("peer", scope.peer.value); put("local_node", scope.localNodeId)
            put("remote_node", scope.remoteNodeId); put("network_id", scope.networkId)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        db.insertWithOnConflict("sequences", null, ContentValues().apply {
            put("scope_id", id); put("value", 0L)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        return id
    }
    override suspend fun rememberScope(scope: DataScope) { access { db -> transaction(db) { remember(db, scope) } } }
    override suspend fun scopes(peer: PeerAddress?): List<DataScope> = read { db ->
        db.query("scopes", null, peer?.let { "peer=?" }, peer?.let { arrayOf(it.value) }, null, null, "scope_id").use { cursor ->
            buildList { while (cursor.moveToNext()) add(DataScope(PeerAddress(cursor.text("peer")), cursor.text("local_node"),
                cursor.text("remote_node"), cursor.optionalText("network_id"))) }
        }
    }
    override suspend fun cursors(scope: DataScope): Map<String, Long> = read { db ->
        db.query("cursors", arrayOf("source_node", "sequence_id"), "scope_id=?", arrayOf(scope.id()), null, null, "source_node").use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getLong(1)) }
        }
    }
    private fun cursor(db: SQLiteDatabase, scope: String, source: String): Long = db.rawQuery(
        "SELECT sequence_id FROM cursors WHERE scope_id=? AND source_node=?", arrayOf(scope, source),
    ).use { if (it.moveToFirst()) it.getLong(0) else -1L }
    private fun relay(db: SQLiteDatabase, scope: String): Long = db.rawQuery(
        "SELECT value FROM sequences WHERE scope_id=?", arrayOf(scope),
    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
    private fun setCursor(db: SQLiteDatabase, scope: String, source: String, sequence: Long) {
        db.insertWithOnConflict("cursors", null, ContentValues().apply {
            put("scope_id", scope); put("source_node", source); put("sequence_id", sequence)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    private fun setRelay(db: SQLiteDatabase, scope: String, sequence: Long) {
        db.update("sequences", ContentValues().apply { put("value", sequence) }, "scope_id=?", arrayOf(scope))
    }
    override suspend fun applyRemote(record: StoredDataItem): RemoteApplyResult = access { db -> transaction(db) {
        val item = record.item
        val id = remember(db, item.scope)
        if (item.sequenceId <= cursor(db, id, item.sourceNodeId)) RemoteApplyResult.DUPLICATE
        else {
            val previous = maxOf(relay(db, id), if (item.sourceNodeId == item.scope.localNodeId) item.sequenceId else 0L)
            check(previous < Long.MAX_VALUE) { "DataApi relay sequence exhausted" }
            writeItem(db, id, record, previous + 1)
            setCursor(db, id, item.sourceNodeId, item.sequenceId)
            setRelay(db, id, previous + 1)
            RemoteApplyResult.APPLIED
        }
    } }
    override suspend fun publishLocal(scope: DataScope, build: (Long) -> StoredDataItem): StoredDataItem = access { db -> transaction(db) {
        val id = remember(db, scope)
        val previous = maxOf(relay(db, id), cursor(db, id, scope.localNodeId))
        check(previous < Long.MAX_VALUE) { "DataApi local sequence exhausted" }
        val sequence = previous + 1
        val record = build(sequence)
        require(record.item.scope == scope && record.item.sequenceId == sequence && record.item.local)
        writeItem(db, id, record, sequence)
        setCursor(db, id, scope.localNodeId, sequence)
        setRelay(db, id, sequence)
        StoredDataItem(record.item, record.wireDataItem, sequence)
    } }
    private fun writeItem(db: SQLiteDatabase, scopeId: String, record: StoredDataItem, relay: Long) {
        val item = record.item
        val key = hashStrings(listOf(scopeId, item.route.packageName, item.route.packageDigest, item.uri))
        db.insertWithOnConflict("items", null, ContentValues().apply {
            put("item_id", key); put("scope_id", scopeId); put("package_name", item.route.packageName)
            put("package_digest", item.route.packageDigest); put("uri", item.uri); put("source_node", item.sourceNodeId)
            put("sequence_id", item.sequenceId); put("relay_sequence", relay); put("deleted", item.deleted)
            put("body", item.data); put("modified", item.lastModifiedMillis); put("received_at", item.receivedAtEpochMillis)
            put("local", item.local); put("wire", record.wireDataItem)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        // REPLACE cascades the previous references, including references from a prior tombstone.
        item.assets.forEachIndexed { ordinal, ref ->
            db.insertOrThrow("item_assets", null, ContentValues().apply {
                put("item_id", key); put("ordinal", ordinal); put("name", ref.name); put("digest", ref.digest); put("type", ref.type)
            })
        }
    }
    override suspend fun items(scope: DataScope, query: DataItemQuery): DataItemPage = read { db ->
        require(query.offset >= 0 && query.limit in 1..500) { "DataItem page budget exceeded" }
        val selection = mutableListOf("scope_id=?")
        val args = mutableListOf(scope.id())
        query.route?.let { selection += "package_name=? AND package_digest=?"; args += it.packageName; args += it.packageDigest }
        // substr matches a literal prefix: caller percent/underscore characters never become LIKE wildcards.
        query.uriPrefix?.let { selection += "substr(uri,1,length(?))=?"; args += it; args += it }
        if (!query.includeDeleted) selection += "deleted=0"
        val records = db.query("items", null, selection.joinToString(" AND "), args.toTypedArray(), null, null,
            "received_at,package_name,package_digest,uri", "${query.limit + 1} OFFSET ${query.offset}").use { cursor ->
            buildList { while (cursor.moveToNext()) add(readItem(db, scope, cursor).item) }
        }
        DataItemPage(records.take(query.limit), if (records.size > query.limit) query.offset + query.limit else null)
    }
    override suspend fun replay(scope: DataScope): List<StoredDataItem> = read { db ->
        db.query("items", null, "scope_id=?", arrayOf(scope.id()), null, null, "source_node,sequence_id").use { cursor ->
            buildList { while (cursor.moveToNext()) add(readItem(db, scope, cursor)) }
        }
    }
    private fun readItem(db: SQLiteDatabase, scope: DataScope, cursor: Cursor): StoredDataItem {
        val references = db.query("item_assets", arrayOf("name", "digest", "type"), "item_id=?", arrayOf(cursor.text("item_id")), null, null, "ordinal").use { ref ->
            buildList { while (ref.moveToNext()) add(DataAssetReference(ref.getString(0), ref.getString(1), ref.getInt(2))) }
        }
        val item = DataItemRecord(scope, ApplicationRoute(cursor.text("package_name"), cursor.text("package_digest")),
            cursor.text("uri"), cursor.text("source_node"), cursor.long("sequence_id"), cursor.long("deleted") != 0L,
            cursor.optionalBlob("body"), references, cursor.optionalLong("modified"), cursor.long("received_at"), cursor.long("local") != 0L)
        return StoredDataItem(item, cursor.optionalBlob("wire") ?: byteArrayOf(), cursor.long("relay_sequence"))
    }
    override suspend fun asset(scope: DataScope, digest: String): DataAssetRecord? = read { db -> readAsset(db, scope, digest) }
    private fun readAsset(db: SQLiteDatabase, scope: DataScope, digest: String): DataAssetRecord? {
        val id = scope.id()
        return db.query("assets", arrayOf("body", "received_at"), "scope_id=? AND digest=?", arrayOf(id, digest), null, null, null).use { cursor ->
            if (!cursor.moveToFirst()) null else {
                val owners = db.query("asset_acl", arrayOf("package_name", "package_digest"), "scope_id=? AND digest=?", arrayOf(id, digest), null, null, "package_name,package_digest").use { acl ->
                    buildSet { while (acl.moveToNext()) add(ApplicationRoute(acl.getString(0), acl.getString(1))) }
                }
                DataAssetRecord(scope, digest, owners, cursor.optionalBlob("body"), cursor.long("received_at"))
            }
        }
    }
    override suspend fun putAsset(asset: DataAssetRecord) { access { db -> transaction(db) {
        val id = remember(db, asset.scope)
        val existing = readAsset(db, asset.scope, asset.digest)
        val bytes = asset.data ?: existing?.data
        val total = db.rawQuery("SELECT coalesce(sum(length(body)),0) FROM assets", null).use { it.moveToFirst(); it.getLong(0) }
        check(total - (existing?.sizeBytes ?: 0) + (bytes?.size ?: 0) <= maxAssetStorageBytes) { "Asset storage budget exceeded" }
        val values = ContentValues().apply { put("body", bytes); put("received_at", asset.receivedAtEpochMillis) }
        if (existing == null) {
            values.put("scope_id", id); values.put("digest", asset.digest)
            db.insertOrThrow("assets", null, values)
        } else db.update("assets", values, "scope_id=? AND digest=?", arrayOf(id, asset.digest))
        asset.owners.forEach { owner ->
            db.insertWithOnConflict("asset_acl", null, ContentValues().apply {
                put("scope_id", id); put("digest", asset.digest); put("package_name", owner.packageName); put("package_digest", owner.packageDigest)
            }, SQLiteDatabase.CONFLICT_IGNORE)
        }
    } } }

    /** Reclaim only proven-retired notification blobs; preserve metadata, ACLs and all live data. */
    internal suspend fun retireNotificationAssets(scope: DataScope, route: ApplicationRoute, digests: Set<String>): Int = access { db ->
        transaction(db) {
            db.compileStatement(NotificationAssetRetirementSql.clearBody).use { statement ->
                var released = 0
                for (digest in digests) {
                    statement.clearBindings()
                    listOf(scope.id(), digest, route.packageName, route.packageDigest, route.packageName, route.packageDigest)
                        .forEachIndexed { index, value -> statement.bindString(index + 1, value) }
                    released += statement.executeUpdateDelete()
                }
                released
            }
        }
    }
    override suspend fun missingAssets(scope: DataScope): List<Pair<ApplicationRoute, String>> = read { db ->
        db.rawQuery("""SELECT DISTINCT i.package_name,i.package_digest,r.digest FROM items i
            JOIN item_assets r ON r.item_id=i.item_id
            LEFT JOIN assets a ON a.scope_id=i.scope_id AND a.digest=r.digest
            LEFT JOIN asset_acl p ON p.scope_id=i.scope_id AND p.digest=r.digest
                AND p.package_name=i.package_name AND p.package_digest=i.package_digest
            WHERE i.scope_id=? AND i.deleted=0 AND r.digest<>'' AND (a.body IS NULL OR p.digest IS NULL)
            ORDER BY i.package_name,i.package_digest,r.digest""", arrayOf(scope.id())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(ApplicationRoute(cursor.getString(0), cursor.getString(1)) to cursor.getString(2)) }
        }
    }
    override fun close() {
        closed.set(true)
        closeWhenIdle() // If busy, the last access closes it once its transaction has settled.
    }
    /** Closes and waits for an access already under way, so another connection can take the file over. */
    internal suspend fun awaitClosed() = withContext(Dispatchers.IO) {
        closed.set(true)
        gate.writeLock().withLock { helper.close() }
    }
    private fun DataScope.id() = hashStrings(listOf(peer.value, localNodeId, remoteNodeId, networkId))
    private fun hashStrings(parts: List<String?>): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { stream -> parts.forEach { part ->
            if (part == null) stream.writeInt(-1) else {
                val value = part.toByteArray(Charsets.UTF_8); stream.writeInt(value.size); stream.write(value)
            }
        } }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun Cursor.text(name: String): String = getString(getColumnIndexOrThrow(name))
    private fun Cursor.optionalText(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.optionalLong(name: String): Long? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.optionalBlob(name: String): ByteArray? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getBlob(it) }
}
