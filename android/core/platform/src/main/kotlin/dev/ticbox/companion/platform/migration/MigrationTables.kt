package dev.ticbox.companion.platform.migration

import android.content.ContentValues
import android.database.Cursor
import dev.ticbox.companion.api.MigrationException
import dev.ticbox.companion.api.MigrationSection
import java.util.Base64
import kotlinx.serialization.json.*

/**
 * The phone database tables a migration file carries, at [SCHEMA]. Rows are JSON objects keyed by
 * column name, one per line, so an importer for a later schema can still read and upgrade them.
 * When `DataSyncSqlSchema` changes, raise [SCHEMA], update these columns, and teach [decodeRow] to
 * upgrade rows from every version since [MIN_SCHEMA]; `MigrationTablesTest` fails until then.
 */
internal object MigrationTables {
    /** The phone database version this app writes. */
    const val SCHEMA = 1
    /** The oldest phone database version an import accepts. */
    const val MIN_SCHEMA = 1

    /** Sources come before their records, and scopes before what refers to them. */
    val health: Map<String, List<String>> = linkedMapOf(
        "local_health_sources" to listOf("local_user", "peer", "kind", "after_id", "upper_id", "in_progress", "partial",
            "accessible", "provider_version", "last_success"),
        "local_health_records" to listOf("local_user", "peer", "kind", "record_key", "source_name", "device_id", "data_type",
            "parent_key", "start_ms", "end_ms", "deleted", "payload", "parsed_payload", "updated_at"),
        "cloud_cache" to listOf("owner_key", "kind", "record_key", "source_name", "data_type", "start_ms", "end_ms",
            "time_zone", "deleted", "payload", "updated_at"),
        "cloud_sync_windows" to listOf("owner_key", "kind", "from_ms", "to_ms", "updated_at"),
        // Written in sample order; the importer numbers merged samples again.
        "observation_samples" to listOf("peer", "kind", "received_at", "battery", "steps", "goal"),
    )
    val pairing: Map<String, List<String>> = linkedMapOf(
        "scopes" to listOf("scope_id", "peer", "local_node", "remote_node", "network_id"),
        "sequences" to listOf("scope_id", "value"),
        "cursors" to listOf("scope_id", "source_node", "sequence_id"),
        "items" to listOf("item_id", "scope_id", "package_name", "package_digest", "uri", "source_node", "sequence_id",
            "relay_sequence", "deleted", "body", "modified", "received_at", "local", "wire"),
        "item_assets" to listOf("item_id", "ordinal", "name", "digest", "type"),
        "assets" to listOf("scope_id", "digest", "body", "received_at"),
        "asset_acl" to listOf("scope_id", "digest", "package_name", "package_digest"),
    )
    /** Columns the file leaves out on purpose. */
    val omitted: Map<String, Set<String>> = mapOf("observation_samples" to setOf("sample_id"))
    /** Tables a migration file never carries, each with the reason. None so far. */
    val notCarried: Map<String, String> = emptyMap()

    fun section(table: String): MigrationSection? = when (table) {
        in health -> MigrationSection.HEALTH
        in pairing -> MigrationSection.PAIRING
        else -> null
    }
    fun columns(table: String): List<String>? = health[table] ?: pairing[table]
    fun selected(sections: Set<MigrationSection>): Map<String, List<String>> = buildMap {
        if (MigrationSection.HEALTH in sections) putAll(health)
        if (MigrationSection.PAIRING in sections) putAll(pairing)
    }

    /** Keeps each value's SQLite storage class: a SPORT cursor is a BLOB in an INTEGER column. */
    fun encodeRow(cursor: Cursor, columns: List<String>): JsonObject = buildJsonObject {
        columns.forEachIndexed { index, name ->
            put(name, when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> JsonNull
                Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(cursor.getLong(index))
                Cursor.FIELD_TYPE_FLOAT -> buildJsonObject { put("real", cursor.getDouble(index)) }
                Cursor.FIELD_TYPE_STRING -> JsonPrimitive(cursor.getString(index))
                else -> buildJsonObject { put("blob", Base64.getEncoder().encodeToString(cursor.getBlob(index))) }
            })
        }
    }

    fun decodeRow(line: String, columns: List<String>, schema: Int): ContentValues {
        check(schema in MIN_SCHEMA..SCHEMA)
        val row = try { Json.parseToJsonElement(line) as? JsonObject } catch (problem: IllegalArgumentException) { throw damaged(problem) }
        if (row == null || row.keys != columns.toSet()) throw damaged(null)
        return ContentValues().apply {
            columns.forEach { name ->
                when (val value = row.getValue(name)) {
                    JsonNull -> putNull(name)
                    is JsonPrimitive -> if (value.isString) put(name, value.content)
                        else put(name, value.content.toLongOrNull() ?: throw damaged(null))
                    is JsonObject -> {
                        val blob = value["blob"] as? JsonPrimitive
                        val real = value["real"] as? JsonPrimitive
                        when {
                            value.size == 1 && blob != null && blob.isString -> put(name, try {
                                Base64.getDecoder().decode(blob.content)
                            } catch (problem: IllegalArgumentException) { throw damaged(problem) })
                            value.size == 1 && real != null && !real.isString -> put(name, real.doubleOrNull ?: throw damaged(null))
                            else -> throw damaged(null)
                        }
                    }
                    else -> throw damaged(null)
                }
            }
        }
    }

    private fun damaged(cause: Throwable?) = MigrationException("migration_damaged", cause)
}
