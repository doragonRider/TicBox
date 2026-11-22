package dev.ticbox.companion.toolboxagent

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import dev.ticbox.companion.protocol.HealthMonitoringProtocol

/** Fixed provider surface; a module proxy may run it under the verified OEM Application identity. */
internal interface MonitoringProviderAccess {
    fun read(key: String): String?
    fun put(key: String, value: String): Boolean
    fun notify(key: String)
    fun step(enabled: Boolean)
    fun notifySteps()
    fun autoMotionFeature(): String?
}

internal class AndroidMonitoringProvider(
    private val resolver: ContentResolver,
    private val fitness: AgentFitnessHeartRateLimit?,
) : MonitoringProviderAccess {
    private val uri = Uri.parse("content://${HealthMonitoringProtocol.AUTHORITY}")
    private fun extras(key: String, table: String = HealthMonitoringProtocol.TABLE) = Bundle().apply {
        putString("table", table); putString("name", key)
    }
    override fun read(key: String): String? {
        if (key == "exercise_heart_rate_upper") return (fitness ?: throw MonitoringUnavailable("monitoring_fitness_provider_missing")).read()
        return get(key)
    }
    private fun get(key: String, table: String = HealthMonitoringProtocol.TABLE): String? {
        val result = resolver.call(uri, "get", null, extras(key, table)) ?: throw MonitoringUnavailable("monitoring_provider_no_reply")
        return result.getString("value")?.also { require(it.length <= 256) { "monitoring_value_too_large" } }
    }
    override fun put(key: String, value: String): Boolean {
        if (key == "exercise_heart_rate_upper") {
            (fitness ?: throw MonitoringUnavailable("monitoring_fitness_provider_missing")).write(value.toLong()); return true
        }
        return resolver.call(uri, "put", null, extras(key).apply { putString("value", value) })?.getBoolean("status", false) == true
    }
    override fun notify(key: String) { resolver.notifyChange(uri.buildUpon().appendPath(HealthMonitoringProtocol.TABLE).appendPath(key).build(), null) }
    override fun step(enabled: Boolean) {
        resolver.update(Uri.parse("content://${HealthMonitoringProtocol.STEP_AUTHORITY}"), ContentValues().apply { put("enable", enabled) }, null, null)
    }
    override fun notifySteps() { resolver.notifyChange(Uri.parse("content://${HealthMonitoringProtocol.STEP_AUTHORITY}"), null) }
    override fun autoMotionFeature() = get("auto_motion", "flags_feature")
}

/** Select the proxy on an explicit read denial, before any setter. Never retry a failed mutation. */
internal class MonitoringProviderRoute(
    private val direct: MonitoringProviderAccess,
    private val module: MonitoringProviderAccess?,
) : MonitoringProviderAccess {
    private var selected = direct
    private var mutationAttempted = false
    private var moduleFailure: MonitoringUnavailable? = null
    private fun <T> readOnly(read: (MonitoringProviderAccess) -> T): T {
        moduleFailure?.let { throw it }
        try { return read(selected) }
        catch (denied: SecurityException) { return useModule(read) }
        catch (unavailable: MonitoringUnavailable) {
            if (selected === direct && unavailable.reason == "monitoring_fitness_permission_denied") return useModule(read)
            rememberFailure(unavailable); throw unavailable
        }
    }
    private fun <T> useModule(read: (MonitoringProviderAccess) -> T): T {
        if (selected !== direct || module == null || mutationAttempted) throw MonitoringUnavailable("monitoring_provider_permission_denied")
        selected = module
        return try { read(selected) } catch (unavailable: MonitoringUnavailable) { rememberFailure(unavailable); throw unavailable }
    }
    private fun rememberFailure(failure: MonitoringUnavailable) {
        if (selected === module && failure.reason in setOf("monitoring_module_unavailable", "monitoring_module_update_required",
                "monitoring_module_version_not_analyzed", "monitoring_module_identity_mismatch", "monitoring_module_protocol_mismatch", "monitoring_module_result_unknown")) moduleFailure = failure
    }
    override fun read(key: String) = readOnly { it.read(key) }
    override fun autoMotionFeature() = readOnly { it.autoMotionFeature() }
    private fun <T> mutate(action: (MonitoringProviderAccess) -> T): T {
        moduleFailure?.let { throw it }
        mutationAttempted = true
        return action(selected)
    }
    override fun put(key: String, value: String) = mutate { it.put(key, value) }
    override fun notify(key: String) = mutate { it.notify(key) }
    override fun step(enabled: Boolean) = mutate { it.step(enabled) }
    override fun notifySteps() = mutate { it.notifySteps() }
}
