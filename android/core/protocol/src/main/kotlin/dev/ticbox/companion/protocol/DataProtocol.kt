package dev.ticbox.companion.protocol

import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Base64

/** DataApi wire helpers. Models here have no Android or application-module dependency. */
data class DataAppKey(val packageName: String, val packageDigest: String)
data class DataWireAssetRef(val name: String, val digest: String, val type: Int = 4)
data class DataWireItem(
    val app: DataAppKey,
    val uri: String,
    val sequenceId: Long,
    val sourceNodeId: String?,
    val deleted: Boolean = false,
    val data: ByteArray? = null,
    val assets: List<DataWireAssetRef> = emptyList(),
    val lastModifiedMillis: Long? = null,
    val notices: List<ProtoNotice> = emptyList(),
    val unknownFieldNumbers: Set<Int> = emptySet(),
)
data class DataSyncStart(
    val version: Int,
    val receivedSequenceId: Long,
    val cursors: Map<String, Long>,
    val restrictions: List<DataPackageRestriction> = emptyList(),
)
data class DataUriFilter(val kind: Int, val uri: String)
data class DataPackageRestriction(val packageName: String, val filters: List<DataUriFilter>?)

/** Raw submessages retain every field byte for durable replay and owner-digest association. */
class DataEnvelopeParts(val parts: Map<Int, ByteArray>, val hasFile: Boolean)

object DataProtocol {
    private val DATA_FIELDS = setOf(4, 5, 6, 8, 9, 12)

    data class Progress(val trackerId: String?, val sequenceId: Long?)

    fun encodeProgress(trackerId: String, sequenceId: Long): ByteArray =
        ProtoWire.encodedField(1, 2, trackerId.toByteArray(Charsets.UTF_8)) +
            ProtoWire.encodedField(2, 0, ProtoWire.varint(sequenceId.toULong()))

    fun decodeProgress(bytes: ByteArray): Progress {
        val fields = ProtoWire.decodeFields(bytes)
        val tracker = fields.lastOrNull { it.number == 1 && it.wireType == 2 }?.let { (it.contents as ByteArray).decodeToString() }
        val sequence = fields.lastOrNull { it.number == 2 && it.wireType == 0 }?.let { (it.contents as ULong).toLong() }
        return Progress(tracker, sequence)
    }

    fun digest(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-1").digest(bytes))

    /** A merged singular protobuf submessage is its concatenated occurrences. */
    fun split(bytes: ByteArray): DataEnvelopeParts {
        val fields = ProtoWire.decodeFields(bytes)
        val grouped = linkedMapOf<Int, ByteArrayOutputStream>()
        fields.filter { it.number in DATA_FIELDS && it.wireType == 2 }.forEach {
            grouped.getOrPut(it.number) { ByteArrayOutputStream() }.write(it.contents as ByteArray)
        }
        val parts = grouped.mapValues { it.value.toByteArray() }
        val hasFile = fields.lastOrNull { it.number == 13 && it.wireType == 0 }
            ?.let { (it.contents as ULong) != 0uL } ?: false
        return DataEnvelopeParts(parts, hasFile)
    }

    /** This cheap scan is still bounded by the common protobuf allocation limits. */
    fun hasData(bytes: ByteArray): Boolean = ProtoWire.decodeFields(bytes).any { it.number in DATA_FIELDS }
    fun wrapDataItem(rawItem: ByteArray): ByteArray = ProtoWire.encodedField(9, 2, rawItem)
    fun withItemSource(raw: ByteArray, sourceNodeId: String): ByteArray =
        raw + ProtoWire.encodedField(7, 2, sourceNodeId.toByteArray(Charsets.UTF_8))
    fun withItemSequence(raw: ByteArray, sequenceId: Long): ByteArray =
        raw + ProtoWire.encodedField(5, 0, ProtoWire.varint(sequenceId.toULong()))

    fun decodeItem(raw: ByteArray): DataWireItem {
        val item = GmsStructs.decode("SetDataItem", raw)
        return DataWireItem(
            DataAppKey(item["packageName"] as String, item["packageDigest"] as String),
            item["uri"] as String,
            item["sequenceId"] as? Long ?: 0L,
            item["sourceNodeId"] as? String,
            item["deleted"] as? Boolean ?: false,
            item["data"] as? ByteArray,
            (item["assets"] as? List<*>)?.filterIsInstance<DecodedStruct>()?.map {
                val ref = it["value"] as? DecodedStruct
                DataWireAssetRef(it["name"] as? String ?: "", ref?.get("digest") as? String ?: "", it["type"] as? Int ?: 0)
            } ?: emptyList(),
            item["lastModified"] as? Long, item.notices, item.unknownFields.map { it.number }.toSet(),
        )
    }

    fun encodeItem(item: DataWireItem): ByteArray = GmsStructs.encode("SetDataItem", mapOf(
        "packageName" to item.app.packageName, "packageDigest" to item.app.packageDigest,
        "uri" to item.uri, "sequenceId" to item.sequenceId, "sourceNodeId" to item.sourceNodeId,
        "deleted" to item.deleted, "data" to item.data, "lastModified" to item.lastModifiedMillis,
        "assets" to item.assets.map { mapOf("name" to it.name, "type" to it.type, "value" to mapOf("digest" to it.digest)) },
    ))

    fun syncStart(cursors: Map<String, Long>, remoteNodeId: String): ByteArray = GmsStructs.encode("WearMessage", mapOf(
        "syncStart" to mapOf("version" to 2, "receivedSequenceId" to (cursors[remoteNodeId] ?: -1L),
            "syncTable" to cursors.toSortedMap().map { (node, seq) -> mapOf("sourceNodeId" to node, "sequenceId" to seq) }),
    ))

    fun decodeSyncStart(raw: ByteArray): DataSyncStart {
        val sync = GmsStructs.decode("SyncStart", raw)
        val entries = (sync["syncTable"] as? List<*>)?.filterIsInstance<DecodedStruct>() ?: emptyList()
        val cursors = linkedMapOf<String, Long>()
        entries.forEach { entry -> cursors[entry["sourceNodeId"] as? String ?: ""] = entry["sequenceId"] as? Long ?: 0L }
        val restriction = sync["restrictions"] as? DecodedStruct
        val packages = (restriction?.get("packages") as? List<*>)?.filterIsInstance<DecodedStruct>() ?: emptyList()
        return DataSyncStart(sync["version"] as? Int ?: 0, sync["receivedSequenceId"] as? Long ?: 0L, cursors, packages.map { pkg ->
            val filters = pkg["dataFilters"] as? DecodedStruct
            DataPackageRestriction(pkg["packageName"] as? String ?: "", filters?.let {
                (it["filters"] as? List<*>)?.filterIsInstance<DecodedStruct>()?.map { filter ->
                    DataUriFilter(filter["kind"] as? Int ?: 0, filter["uri"] as? String ?: "")
                } ?: emptyList()
            })
        })
    }

    /** GMS matches package, optional host ("*" wildcard) and decoded literal/prefix path.
     * An empty effective filter set leaves sync unrestricted, as GMS does.
     */
    fun allows(item: DataWireItem, restrictions: List<DataPackageRestriction>, localNodeId: String? = null): Boolean {
        val effective = restrictions.flatMap { pkg ->
            if (pkg.filters == null) listOf(pkg.packageName to null) else pkg.filters.map { pkg.packageName to it }
        }
        if (effective.isEmpty()) return true
        return effective.any { (pkg, filter) ->
            pkg == item.app.packageName && (filter == null || uriMatches(item.uri, filter, localNodeId))
        }
    }

    private fun uriMatches(uri: String, filter: DataUriFilter, localNodeId: String?): Boolean = try {
        val wanted = URI(filter.uri)
        val actual = URI(uri)
        val host = (wanted.host ?: wanted.rawAuthority)?.let { if (it == "local") localNodeId ?: it else it }
        (host == null || host == "*" || host == (actual.host ?: actual.rawAuthority)) &&
            (wanted.path == null || if (filter.kind == 2) (actual.path ?: "").startsWith(wanted.path) else actual.path == wanted.path)
    } catch (_: Exception) { uri == filter.uri }

    fun fetchAsset(app: DataAppKey, digest: String, permissionOnly: Boolean): ByteArray = GmsStructs.encode("WearMessage", mapOf(
        "fetchAsset" to mapOf("packageName" to app.packageName, "packageDigest" to app.packageDigest,
            "digest" to digest, "permissionOnly" to permissionOnly),
    ))
    fun setAsset(digest: String, owners: Set<DataAppKey>, inlineData: ByteArray? = null, hasFile: Boolean = false): ByteArray =
        GmsStructs.encode("WearMessage", mapOf("setAsset" to mapOf("digest" to digest, "inlineData" to inlineData,
            "permissions" to mapOf("apps" to owners.sortedWith(compareBy<DataAppKey> { it.packageName }.thenBy { it.packageDigest })
                .map { mapOf("packageName" to it.packageName, "packageDigest" to it.packageDigest) })),
            "hasFile" to hasFile.takeIf { it }))
    fun ackAsset(digest: String): ByteArray = GmsStructs.encode("WearMessage", mapOf("ackAsset" to mapOf("digest" to digest)))
    fun filePiece(ownerDigest: String, data: ByteArray, finalPiece: Boolean, fileDigest: String? = null): ByteArray =
        GmsStructs.encode("WearMessage", mapOf("filePiece" to mapOf("ownerMessageDigest" to ownerDigest,
            "data" to data, "finalPiece" to finalPiece, "fileDigest" to fileDigest)))
}
