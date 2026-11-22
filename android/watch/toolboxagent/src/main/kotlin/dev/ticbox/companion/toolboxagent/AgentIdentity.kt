package dev.ticbox.companion.toolboxagent

import java.security.MessageDigest

/** Same read-only hardware property under shell and root. Do not expose the serial in the wire/logs. */
@android.annotation.SuppressLint("PrivateApi") // Standalone shell/root app_process reads the same immutable property.
internal fun agentDeviceIdentity(): String = try {
    val properties = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
    val serial = listOf("ro.boot.serialno", "ro.serialno").asSequence()
        .map { properties.invoke(null, it) as String }
        .firstOrNull { it.isNotBlank() && it.lowercase() !in setOf("unknown", "null", "none") }
        ?: throw DeviceIdentityUnavailableException()
    MessageDigest.getInstance("SHA-256").digest(("ticwatch-toolbox-device-v1\u0000" + serial).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
} catch (error: Exception) {
    throw DeviceIdentityUnavailableException(error)
}

internal class DeviceIdentityUnavailableException(cause: Exception? = null) : IllegalStateException("device_identity_required", cause)
