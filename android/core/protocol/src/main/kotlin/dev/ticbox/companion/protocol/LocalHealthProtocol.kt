package dev.ticbox.companion.protocol

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlinx.serialization.json.*

/** Our local bridge protocol, separate from stock Mobvoi messages. No network/Android dependency. */
enum class LocalHealthTable(val wireName: String) {
    POINT("data_point"), SESSION("data_session"), SPORT("fitness_summary"),
    FITNESS_RECORD("fitness_record"), FITNESS_POINT("fitness_point");

    val privateFitness: Boolean get() = this == FITNESS_RECORD || this == FITNESS_POINT

    val sourceName: String get() = when (this) {
        POINT -> LocalHealthProtocol.SOURCE_NAME
        SESSION -> "content://com.mobvoi.heartrate.db.provider/data_session"
        SPORT -> "content://com.mobvoi.fitness.db.provider/fitness_summary"
        FITNESS_RECORD -> "sqlite://com.mobvoi.wear.fitness.at/fitness.db/RECORD"
        FITNESS_POINT -> "sqlite://com.mobvoi.wear.fitness.at/fitness.db/POINT"
    }
}

object LocalHealthProtocol {
    const val WAKE_PATH = "/ticbox_local_health/wake"
    const val RFCOMM_UUID = "a701ba60-6fa4-4acb-b3e7-0d29b381f6ce"
    const val MAX_FRAME_BYTES = 1_048_576
    const val PAGE_SIZE = 128
    const val SESSION_MILLIS = 25_000L
    /** The current self-owned protocol uses persistent batches and commit acknowledgements. */
    const val INCREMENTAL_VERSION = 3
    const val SOURCE_NAME = "content://com.mobvoi.heartrate.db.provider/data_point"
    /** Value of a request's `compression`: its reply may come as a zlib frame. */
    const val ZLIB = "zlib"
    /** A set top bit in the length prefix marks a zlib body. */
    private const val ZLIB_FLAG = Int.MIN_VALUE
    /** Smaller bodies don't gain enough to be worth compressing. */
    private const val ZLIB_MIN_BYTES = 1024
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    fun parse(bytes: ByteArray): JsonObject = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
        ?: throw IllegalArgumentException("Expected a local health object")

    /** With [zlib], a large body goes compressed when that makes it smaller; the reader may only expect that if it asked. */
    fun frame(value: JsonObject, zlib: Boolean = false): ByteArray {
        val body = value.toString().toByteArray(Charsets.UTF_8)
        require(body.size in 1..MAX_FRAME_BYTES) { "Local health frame allocation budget exceeded" }
        if (zlib && body.size >= ZLIB_MIN_BYTES) {
            val packed = deflate(body)
            if (packed.size < body.size) return ByteBuffer.allocate(4 + packed.size).putInt(packed.size or ZLIB_FLAG).put(packed).array()
        }
        return ByteBuffer.allocate(4 + body.size).putInt(body.size).put(body).array()
    }

    /** The body size a length prefix announces, and whether that body is zlib. */
    fun header(prefix: Int): Pair<Int, Boolean> {
        val size = prefix and ZLIB_FLAG.inv()
        require(size in 1..MAX_FRAME_BYTES) { "Local health frame allocation budget exceeded" }
        return size to (prefix and ZLIB_FLAG != 0)
    }

    /** A zlib body may not expand beyond [MAX_FRAME_BYTES] either. */
    fun parse(body: ByteArray, zlib: Boolean): JsonObject = parse(if (zlib) inflate(body) else body)

    private fun deflate(body: ByteArray): ByteArray {
        val deflater = Deflater()
        try {
            deflater.setInput(body); deflater.finish()
            val output = ByteArrayOutputStream(body.size / 4 + 64); val buffer = ByteArray(8192)
            while (!deflater.finished()) output.write(buffer, 0, deflater.deflate(buffer))
            return output.toByteArray()
        } finally { deflater.end() }
    }

    private fun inflate(body: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(body)
            val output = ByteArrayOutputStream(minOf(MAX_FRAME_BYTES, body.size * 8)); val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw IllegalArgumentException("Truncated local health frame")
                require(output.size() + count <= MAX_FRAME_BYTES) { "Local health frame allocation budget exceeded" }
                output.write(buffer, 0, count)
            }
            require(inflater.remaining == 0) { "Trailing bytes after a local health frame" }
            return output.toByteArray()
        } catch (_: DataFormatException) { throw IllegalArgumentException("Malformed local health frame") }
        finally { inflater.end() }
    }

    /** Used by the watch's bounded socket callback. EOF before a new frame is a normal close. */
    fun read(input: InputStream): JsonObject? {
        val first = input.read()
        if (first < 0) return null
        val rest = ByteArray(3); DataInputStream(input).readFully(rest)
        val (size, zlib) = header((first shl 24) or ((rest[0].toInt() and 255) shl 16) or
            ((rest[1].toInt() and 255) shl 8) or (rest[2].toInt() and 255))
        val body = ByteArray(size); DataInputStream(input).readFully(body)
        return parse(body, zlib)
    }

    fun write(output: OutputStream, value: JsonObject, zlib: Boolean = false) { output.write(frame(value, zlib)); output.flush() }

    fun wake(token: String) = buildJsonObject { put("version", 1); put("token", token) }
    fun hello(token: String) = buildJsonObject { put("op", "hello"); put("token", token) }
    fun ready(tables: Collection<LocalHealthTable> = LocalHealthTable.entries) = buildJsonObject {
        put("op", "ready"); put("version", INCREMENTAL_VERSION)
        put("tables", JsonArray(tables.map { JsonPrimitive(it.wireName) }))
    }

    /** An omitted inventory means unknown; an explicit inventory may contain future table names. */
    fun supportedTables(ready: JsonObject): Set<LocalHealthTable>? = (ready["tables"] as? JsonArray)?.let { names ->
        names.mapNotNull { name -> LocalHealthTable.entries.firstOrNull { it.wireName == (name as? JsonPrimitive)?.contentOrNull } }.toSet()
    }
    fun request(table: LocalHealthTable, afterId: Long?, upperId: Long?,
        afterKey: String? = null, upperKey: String? = null) = buildJsonObject {
        put("op", "page"); put("table", table.wireName); afterId?.let { put("afterId", it.toString()) }
        upperId?.let { put("upperId", it.toString()) }; put("limit", PAGE_SIZE)
        afterKey?.let { put("afterKey", it) }; upperKey?.let { put("upperKey", it) }
    }

    /**
     * [window]: a first sync reads only rows from the last this many milliseconds. [ack]: a batch of this table the phone
     * has committed, for a bridge whose replies say `inlineAck`. [zlib]: the reply may be compressed. Older bridges ignore all three.
     */
    fun changes(table: LocalHealthTable, receiver: String, run: String, full: Boolean, window: Long? = null,
        ack: String? = null, zlib: Boolean = false) = buildJsonObject {
        put("op", "changes"); put("table", table.wireName); put("receiver", receiver)
        put("run", run); put("full", full); put("limit", PAGE_SIZE)
        window?.let { put("window", it) }; ack?.let { put("ack", it) }
        if (zlib) put("compression", ZLIB)
    }
    fun acknowledge(receiver: String, table: LocalHealthTable, batch: String) = buildJsonObject {
        put("op", "ack"); put("receiver", receiver); put("table", table.wireName); put("batch", batch)
    }

    /** [wireBytes] is the size of the frame that carried [value], for diagnostics. */
    fun page(value: JsonObject, wireBytes: Long = 0): LocalHealthPage {
        val received = value["rows"] as? JsonArray
        val rows = received.orEmpty().mapNotNull { it as? JsonObject }.map(::LocalHealthRow)
        val incomplete = received == null || rows.size != received.size || rows.any { it.partial }
        return LocalHealthPage(rows, value.number("nextId") ?: rows.mapNotNull { it.id }.maxOrNull(),
            value.number("upperId"), value.flag("complete") ?: rows.isEmpty(),
            incomplete || value.flag("partial") == true, value.text("error"), value.text("providerVersion"),
            value.text("nextKey"), value.text("upperKey"), value.text("batch"), value.number("scannedRows") ?: 0,
            value.number("watchMillis"), wireBytes, value.number("deferredBefore"), value.flag("inlineAck") == true)
    }
}

/** Full object is retained, including unknown wire fields and unknown Cursor columns/types. */
class LocalHealthRow(val raw: JsonObject) {
    val columns: JsonObject get() = raw["columns"] as? JsonObject ?: raw
    val id: Long? get() = raw.number("id") ?: columns.number("_id")
    val partial: Boolean get() = raw.flag("partial") == true
    fun text(name: String): String? = columns.text(name)
    fun number(name: String): Long? = columns.number(name)
    override fun toString() = "LocalHealthRow(contents=<redacted>)"
}

class LocalHealthPage(val rows: List<LocalHealthRow>, val nextId: Long?, val upperId: Long?,
    val complete: Boolean, val partial: Boolean = false, val error: String? = null, val providerVersion: String? = null,
    val nextKey: String? = null, val upperKey: String? = null,
    val batchId: String? = null, val scannedRows: Long = 0,
    /** How long the watch worked on this reply; null from a bridge that doesn't say. */
    val watchMillis: Long? = null,
    val wireBytes: Long = 0,
    /** Rows older than this are still only on the watch, left behind by a first sync's window. */
    val deferredBefore: Long? = null,
    /** The bridge takes this batch's ACK on the table's next changes request. */
    val inlineAck: Boolean = false) {
    override fun toString() = "LocalHealthPage(rows=${rows.size}, complete=$complete, contents=<redacted>)"
}

fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
fun JsonObject.number(name: String): Long? = (get(name) as? JsonPrimitive)?.longOrNull
fun JsonObject.flag(name: String): Boolean? = (get(name) as? JsonPrimitive)?.let { value ->
    value.booleanOrNull ?: when (value.content.trim().lowercase()) { "1", "true" -> true; "0", "false" -> false; else -> null }
}
