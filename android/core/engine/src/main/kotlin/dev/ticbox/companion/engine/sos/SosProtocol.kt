package dev.ticbox.companion.engine.sos

import dev.ticbox.companion.api.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.*

object SosProtocol {
    const val SETTINGS = "/companion/ticsos/watch_settings"
    const val NUMBER = "/companion/ticsos/edit_number"
    const val CONTACTS = "/companion/ticsos/edit_contacts"
    const val BUTTON = "/companion/ticsos/button_trigger"
    const val SHARE_LOCATION = "/companion/ticsos/share_location"
    const val FALL = "/companion/ticsos/fall_detection"
    const val SYNC_OK = "/companion/ticsos/sync_ok"
    const val START = "/companion/ticsos/start_sos"
    val MESSAGES = setOf(SETTINGS, NUMBER, CONTACTS, BUTTON, SHARE_LOCATION, FALL, SYNC_OK, START)
}

/** Local admission limits; the original wire protocol has no advertised limit negotiation. */
object SosCodec {
    const val MAX_SETTINGS_BYTES = 64 * 1024
    const val MAX_CONTACTS = SosLimits.MAX_CONTACTS
    const val MAX_CONTACTS_BYTES = 8 * 1024
    const val MAX_NUMBER_CHARS = SosLimits.MAX_NUMBER_CHARS
    const val MAX_NAME_CHARS = SosLimits.MAX_NAME_CHARS
    private val json = Json { ignoreUnknownKeys = true }

    fun text(bytes: ByteArray, maximum: Int = MAX_SETTINGS_BYTES): String {
        require(bytes.size <= maximum) { "sos_payload_budget" }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }

    fun settings(bytes: ByteArray): SosWatchSettings {
        val root = parse(text(bytes)) as? JsonObject ?: throw IllegalArgumentException("sos_settings_object_required")
        fun primitive(key: String) = root[key] as? JsonPrimitive ?: throw IllegalArgumentException("sos_settings_field_missing")
        fun string(key: String) = primitive(key).takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("sos_settings_string_required")
        fun flag(key: String) = primitive(key).takeUnless { it.isString }?.booleanOrNull
            ?: throw IllegalArgumentException("sos_settings_boolean_required")
        val timestamp = primitive("u_time").longOrNull ?: throw IllegalArgumentException("sos_settings_time_required")
        val fall = primitive("tic_fall_type").intOrNull ?: throw IllegalArgumentException("sos_settings_fall_required")
        val number = string("e_num").also { require(it.length <= MAX_NUMBER_CHARS) { "sos_number_budget" } }
        val contactsPayload = string("cts")
        return SosWatchSettings(timestamp, flag("button_press"), number, fall, flag("share_loc"),
            contactsOrNull(contactsPayload), contactsPayload)
    }

    /** Persists the exact known watch preference shape; this is never a settings-write message. */
    fun encodeSettings(settings: SosWatchSettings): ByteArray = buildJsonObject {
        put("u_time", settings.updatedAtMillis)
        put("button_press", settings.buttonTriggerEnabled)
        put("e_num", settings.emergencyNumber)
        put("tic_fall_type", settings.fallDetectionValue)
        put("share_loc", settings.shareLocation)
        put("cts", settings.contactsPayload)
    }.toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_SETTINGS_BYTES) { "sos_payload_budget" } }

    fun contacts(contacts: List<SosEmergencyContact>): ByteArray {
        require(contacts.size <= MAX_CONTACTS) { "sos_max_four_contacts" }
        contacts.forEach {
            require(it.name.isNotBlank() && it.name.length <= MAX_NAME_CHARS && it.name.none(Char::isISOControl)) { "sos_contact_name_invalid" }
            require(validNumber(it.number, allowEmpty = false)) { "sos_contact_number_invalid" }
        }
        return JsonArray(contacts.map { buildJsonObject { put("name", it.name); put("num", it.number) } })
            .toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_CONTACTS_BYTES) { "sos_contacts_budget" } }
    }

    fun contactsOrNull(payload: String): List<SosEmergencyContact>? {
        if (payload.isEmpty()) return emptyList()
        if (payload.toByteArray(Charsets.UTF_8).size > MAX_CONTACTS_BYTES) return null
        return try {
            val rows = parse(payload) as? JsonArray ?: return null
            if (rows.size > MAX_CONTACTS) return null
            rows.map { row ->
                val value = row as? JsonObject ?: throw IllegalArgumentException("sos_contact_object_required")
                val name = (value["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw IllegalArgumentException("sos_contact_name_required")
                val number = (value["num"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw IllegalArgumentException("sos_contact_number_required")
                SosEmergencyContact(name, number)
            }.also { contacts(it) }
        } catch (_: IllegalArgumentException) { null }
    }

    fun number(number: String): ByteArray {
        require(validNumber(number, allowEmpty = true)) { "sos_emergency_number_invalid" }
        return number.toByteArray(Charsets.UTF_8)
    }

    /** One dialable/SMS recipient, never a multi-recipient separator or a URI. */
    fun validNumber(number: String, allowEmpty: Boolean = false): Boolean =
        (number.isEmpty() && allowEmpty) || (number.length in 1..MAX_NUMBER_CHARS && number.any { it in '0'..'9' } &&
            number.all { it in '0'..'9' || it in "+*#(). -" })

    private fun parse(value: String): JsonElement {
        var depth = 0; var quoted = false; var escaped = false
        value.forEach { ch ->
            if (quoted) {
                if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false
            } else when (ch) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 16) { "sos_json_depth" } }
                '}', ']' -> depth--
            }
        }
        val parsed = json.parseToJsonElement(value)
        // kotlinx.serialization's object projection otherwise keeps the last duplicate member.
        // Reject ambiguous recipient/settings keys, including escaped aliases such as n\u0075m.
        val containers = mutableListOf<MutableSet<String>?>()
        var index = 0
        while (index < value.length) {
            when (value[index]) {
                '{' -> containers.add(mutableSetOf())
                '[' -> containers.add(null)
                '}', ']' -> containers.removeAt(containers.lastIndex)
                '"' -> {
                    val start = index++
                    var escapedString = false
                    while (index < value.length) {
                        val ch = value[index]
                        if (escapedString) escapedString = false
                        else if (ch == '\\') escapedString = true
                        else if (ch == '"') break
                        index++
                    }
                    var next = index + 1
                    while (next < value.length && value[next].isWhitespace()) next++
                    if (next < value.length && value[next] == ':') {
                        val key = (json.parseToJsonElement(value.substring(start, index + 1)) as JsonPrimitive).content
                        require(containers.lastOrNull()?.add(key) == true) { "sos_duplicate_json_member" }
                    }
                }
            }
            index++
        }
        return parsed
    }
}
