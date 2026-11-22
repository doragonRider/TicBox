package dev.ticbox.companion.engine.privacy

import dev.ticbox.companion.api.CloudCapability
import dev.ticbox.companion.api.CloudPrivacySettings

/** Small local settings record. It contains no health data, identifiers, credentials or endpoints. */
object CloudPrivacyCodec {
    private const val HEADER = "ticbox-cloud-privacy/v1"
    const val MAX_BYTES = 4_096

    fun encode(settings: CloudPrivacySettings): ByteArray = buildString {
        appendLine(HEADER)
        for (capability in CloudCapability.entries) appendLine("${capability.name}=${settings.allows(capability)}")
    }.toByteArray(Charsets.UTF_8)

    /** Malformed records are rejected; the platform restores all-disabled on any decoding failure. */
    fun decode(bytes: ByteArray): CloudPrivacySettings {
        require(bytes.size <= MAX_BYTES) { "privacy_settings_oversized" }
        val lines = bytes.toString(Charsets.UTF_8).lines()
        require(lines.firstOrNull() == HEADER) { "privacy_settings_version" }
        val enabled = mutableSetOf<CloudCapability>()
        val seen = mutableSetOf<String>()
        for (line in lines.drop(1).filter(String::isNotBlank)) {
            val parts = line.split('=', limit = 2)
            require(parts.size == 2 && seen.add(parts[0])) { "privacy_settings_format" }
            val capability = CloudCapability.entries.firstOrNull { it.name == parts[0] } ?: continue
            when (parts[1]) {
                "true" -> enabled += capability
                "false" -> Unit
                else -> error("privacy_settings_boolean")
            }
        }
        return CloudPrivacySettings(enabledCapabilities = enabled.toSet())
    }
}
