package dev.ticbox.companion.toolboxagent

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import dev.ticbox.companion.protocol.HealthMonitoringProtocol

/** Read-only inputs used by the selected OEM helpers, not phone-supplied model guesses. */
internal data class AgentMonitoringEnvironment(
    val defaults: Map<String, String> = emptyMap(),
    val autoMotionFeatureEnabled: Boolean? = null,
) {
    companion object {
        @SuppressLint("PrivateApi") // Standalone root app_process; reads one fixed, non-identifying property.
        fun read(context: Context, versionCode: Long, feature: (() -> String?)? = null): AgentMonitoringEnvironment {
            if (versionCode !in HealthMonitoringProtocol.ANALYZED_VERSIONS) return AgentMonitoringEnvironment()
            val model = Build.MODEL.orEmpty()
            val modern = model in setOf("TicWatch Pro 5", "TicWatch Pro 5 Enduro") ||
                (versionCode == 50_503_292L && model == "TicWatch Atlas")
            val watchModel = model.startsWith("Ticwatch") || model.startsWith("TicWatch")
            val packageManager = context.packageManager
            val defaults = mutableMapOf(
                "background_hrm" to modern.toString(),
                "auto_detect_blood_oxygen" to modern.toString(),
                "auto_detect_fitness" to (watchModel || packageManager.hasSystemFeature("cn.google") ||
                    packageManager.hasSystemFeature("com.google.sidewinder")).toString(),
            )
            if (versionCode == 50_603_234L) defaults["detect_sleep_temperature_unit"] = "0"
            val gate = if (!packageManager.hasSystemFeature("android.hardware.type.watch")) true else try {
                val flag = if (feature != null) feature() else {
                    val reply = context.contentResolver.call(Uri.parse("content://${HealthMonitoringProtocol.AUTHORITY}"), "get", null,
                        Bundle().apply { putString("table", "flags_feature"); putString("name", "auto_motion") })
                        ?: throw MonitoringUnavailable("monitoring_provider_no_reply")
                    reply.getString("value")
                }
                if (flag != null) when (flag) { "true" -> true; "false" -> false; else -> null }
                else {
                    val property = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                        .invoke(null, "ticwear.version.name") as String
                    property.isEmpty()
                }
            } catch (_: Exception) { null }
            return AgentMonitoringEnvironment(defaults, gate)
        }
    }
}
