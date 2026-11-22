package dev.ticbox.companion.protocol

import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Compare source values and storage types, not delivery metadata or JSON member order. */
object LocalHealthFingerprint {
    /**
     * SHA-256 of the row's values and storage types as sorted JSON. The watch keeps these to tell changed rows, so the
     * output must stay the same across versions, or every row would look changed once and be sent again.
     */
    fun of(row: JsonObject): String {
        // The text of {"columns":…,"sourceOccurrence":…,"storageTypes":…} with every object's members sorted, written
        // directly instead of building sorted copies.
        val text = StringBuilder(1024).append("{\"columns\":")
        canonical(row["columns"] ?: row, text)
        row["sourceOccurrence"]?.let { text.append(",\"sourceOccurrence\":"); canonical(it, text) }
        row["storageTypes"]?.let { text.append(",\"storageTypes\":"); canonical(it, text) }
        val digest = DIGEST.get()!!.digest(text.append('}').toString().toByteArray(Charsets.UTF_8))
        val hex = CharArray(digest.size * 2)
        digest.forEachIndexed { index, byte ->
            hex[2 * index] = HEX[(byte.toInt() shr 4) and 15]; hex[2 * index + 1] = HEX[byte.toInt() and 15]
        }
        return String(hex)
    }

    /** Prints as kotlinx does, with object members in natural key order. */
    private fun canonical(value: JsonElement, out: StringBuilder) {
        when (value) {
            is JsonObject -> {
                out.append('{')
                value.keys.sorted().forEachIndexed { index, key ->
                    if (index > 0) out.append(',')
                    out.append(quoted(key)).append(':'); canonical(value.getValue(key), out)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                value.forEachIndexed { index, item -> if (index > 0) out.append(','); canonical(item, out) }
                out.append(']')
            }
            else -> out.append(value.toString())
        }
    }

    /** Column names repeat on every row, so their quoted form is kept. */
    private fun quoted(key: String): String = QUOTED[key] ?: JsonPrimitive(key).toString().also { if (QUOTED.size < 4096) QUOTED[key] = it }

    private val QUOTED = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val HEX = "0123456789abcdef".toCharArray()
    private val DIGEST = object : ThreadLocal<MessageDigest>() { override fun initialValue() = MessageDigest.getInstance("SHA-256") }

    fun key(table: LocalHealthTable, row: JsonObject): String {
        val columns = row["columns"] as? JsonObject ?: row
        return when (table) {
            LocalHealthTable.SESSION -> columns.text("id") ?: row.text("id")
            LocalHealthTable.SPORT -> (columns.text("id") ?: row.text("id"))?.let {
                "$it#${row.number("sourceOccurrence") ?: 0}"
            }
            else -> row.text("id") ?: columns.text("_id")
        } ?: "raw:${of(row)}"
    }
}
