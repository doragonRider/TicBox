package dev.ticbox.companion.protocol

import kotlinx.serialization.json.*
import kotlin.test.*

class LocalHealthFingerprintTest {
    private fun json(text: String) = LocalHealthProtocol.parse(text.toByteArray())
    @Test fun canonicalFieldComparisonRetainsUnknownValuesTypesNullAndInt64() {
        val a = json("""{"columns":{"_id":9223372036854775807,"future":"opaque","value":null},"storageTypes":{"_id":"integer","future":"string","value":"null"}}""")
        val b = json("""{"bridgeRevision":"99","columns":{"value":null,"future":"opaque","_id":9223372036854775807},"storageTypes":{"value":"null","future":"string","_id":"integer"}}""")
        assertEquals(LocalHealthFingerprint.of(a), LocalHealthFingerprint.of(b))
        listOf(
            json(a.toString().replace("9223372036854775807", "9223372036854775806")),
            json(a.toString().replace("opaque", "updated")),
            JsonObject(a + ("storageTypes" to buildJsonObject { put("_id", "string") })),
            JsonObject(a + ("columns" to JsonObject(a["columns"]!!.jsonObject - "value"))),
            json(a.toString().replace("\"value\":null", "\"value\":\"\"")),
        ).forEach { assertNotEquals(LocalHealthFingerprint.of(a), LocalHealthFingerprint.of(it)) }
    }

    /** How 1.0.5 and earlier computed it; watches keep fingerprints made this way. */
    private fun earlier(row: JsonObject): String {
        fun canonical(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
            is JsonArray -> JsonArray(value.map(::canonical))
            else -> value
        }
        val source = buildJsonObject {
            put("columns", row["columns"] ?: row)
            row["storageTypes"]?.let { put("storageTypes", it) }
            row["sourceOccurrence"]?.let { put("sourceOccurrence", it) }
        }
        return java.security.MessageDigest.getInstance("SHA-256").digest(canonical(source).toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    @Test fun fingerprintsMatchEarlierVersionsSoStoredOnesStayValid() {
        listOf(
            """{"columns":{"_id":9223372036854775807,"values":"{\"hr\":[61,62]}","wwid":null,"z":-0.5,"a":true},
                "storageTypes":{"_id":"integer","values":"string"}}""",
            """{"id":"7","columns":{"_id":7,"名前":"睡眠 \u2603 \ud83d\ude00","quote":"a\"b\\c\n\t","ctrl":"\u0001"},"sourceOccurrence":2}""",
            """{"_id":3,"nested":{"b":[{"y":1,"x":[2,{"k":null}]}],"a":{}},"Upper":1,"lower":2,"_":1.0e3}""",
            """{"columns":null,"storageTypes":{"x":"blob"},"sourceOccurrence":0}""",
            """{"columns":{}}""",
        ).map(::json).forEach { row -> assertEquals(earlier(row), LocalHealthFingerprint.of(row)) }
    }
}
