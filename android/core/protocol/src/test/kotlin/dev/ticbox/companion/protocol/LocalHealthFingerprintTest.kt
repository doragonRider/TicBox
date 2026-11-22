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
}
