package dev.ticbox.companion.engine.watchface

import dev.ticbox.companion.api.*
import kotlinx.serialization.json.*

/** Mobvoi Gson/UTF-8 projection. Unknown properties remain in the parsed object. */
internal object WatchFaceMobvoiJson {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }
    fun parse(bytes: ByteArray): JsonObject {
        require(bytes.size <= 1024 * 1024) { "Watch face JSON byte budget exceeded" }
        val text = bytes.toString(Charsets.UTF_8)
        var depth = 0; var quoted = false; var escaped = false
        for (character in text) {
            if (quoted) { if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == '"') quoted = false }
            else when (character) {
                '"' -> quoted = true
                '{', '[' -> { require(++depth <= 32) { "Watch face JSON nesting budget exceeded" } }
                '}', ']' -> depth--
            }
        }
        return json.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("Expected watch face JSON object")
    }
    fun settings(bytes: ByteArray): WatchFacePhotoSettings = parse(bytes).let { obj ->
        fun text(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
        fun number(key: String) = (obj[key] as? JsonPrimitive)?.intOrNull
        WatchFacePhotoSettings(text("pkg"), text("class_name"), number("category") ?: 2, text("name"), number("time_place"), number("show_battery"), number("show_date_and_week"))
    }
    fun encode(value: WatchFacePhotoSettings): ByteArray = buildJsonObject {
        put("category", value.category); value.packageName?.let { put("pkg", it) }; value.className?.let { put("class_name", it) }
        value.name?.let { put("name", it) }; value.timePlace?.let { put("time_place", it) }; value.showBattery?.let { put("show_battery", it) }
        value.showDateAndWeek?.let { put("show_date_and_week", it) }
    }.toString().toByteArray(Charsets.UTF_8)
    fun image(bytes: ByteArray): Pair<String?, WatchFaceImage?> {
        val obj = parse(bytes)
        val image = obj["image"] as? JsonArray
        return (obj["class_name"] as? JsonPrimitive)?.contentOrNull to image?.let { array ->
            require(array.size <= 1024 * 1024) { "Watch face image budget exceeded" }
            val values = array.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
            if (values.size != array.size) null else WatchFaceImage(ByteArray(values.size) { values[it].toByte() }, "image/jpeg")
        }
    }
}
