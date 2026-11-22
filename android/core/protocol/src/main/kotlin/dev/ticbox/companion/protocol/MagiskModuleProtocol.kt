package dev.ticbox.companion.protocol

import java.io.InputStream
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** A content catalog for the single shipped payload; not a general-purpose module installer. */
object MagiskModuleProtocol {
    const val OPERATION = "health_module"
    const val CAPABILITY = "HEALTH_MODULE"
    const val ID = "ticwatch_health_settings"
    const val ASSET = "watch/health-module.zip"
    const val CATALOG_ASSET = "watch/health-module.json"
    const val MAX_ZIP_BYTES = 4 * 1024 * 1024
    const val MAX_EXPANDED_BYTES = 8 * 1024 * 1024
    val PAYLOADS = setOf("module.dex", "zygisk/armeabi-v7a.so", "zygisk/arm64-v8a.so", "companion-build.properties")
    data class Catalog(val versionName: String, val versionCode: Long, val buildId: String, val sha256: String, val bytes: Long, val entries: Map<String, String>) {
        fun json() = buildJsonObject {
            put("schema", 1); put("id", ID); put("versionName", versionName); put("versionCode", versionCode)
            put("buildId", buildId); put("sha256", sha256); put("bytes", bytes)
            put("entries", JsonObject(entries.mapValues { JsonPrimitive(it.value) }))
        }
    }
    fun decode(value: JsonObject): Catalog {
        require(value.number("schema") == 1L && value.text("id") == ID)
        val entries = (value["entries"] as? JsonObject)?.mapValues { (_, hash) -> hash.jsonPrimitive.content } ?: error("module_catalog_invalid")
        val catalog = Catalog(value.moduleText("versionName"), value.moduleNumber("versionCode"), value.moduleText("buildId"),
            value.moduleText("sha256"), value.moduleNumber("bytes"), entries)
        require(catalog.versionName.length in 1..80 && catalog.versionCode > 0 && catalog.bytes in 1..MAX_ZIP_BYTES)
        require(digest(catalog.buildId) && digest(catalog.sha256) && entries.size in 7..32 && entries.keys.containsAll(PAYLOADS))
        require(entries.all { (path, hash) -> path.length in 1..160 && !path.startsWith('/') && path.split('/').none { it in setOf("", ".", "..") } && '\\' !in path && digest(hash) })
        return catalog
    }
    fun digest(value: String) = value.matches(Regex("[0-9a-f]{64}"))
    fun sha256(input: InputStream, maximum: Long): String {
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(32 * 1024); var total = 0L
        while (true) { val count = input.read(buffer); if (count < 0) break; require(count > 0); total += count; require(total <= maximum); digest.update(buffer, 0, count) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

fun JsonObject.moduleText(key: String): String = text(key) ?: error("module_field_missing")
fun JsonObject.moduleNumber(key: String): Long = number(key) ?: error("module_field_missing")
