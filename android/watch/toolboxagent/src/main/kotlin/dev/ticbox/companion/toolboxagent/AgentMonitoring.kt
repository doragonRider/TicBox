package dev.ticbox.companion.toolboxagent

import android.content.ContentResolver
import android.content.Context
import dev.ticbox.companion.protocol.*
import kotlinx.serialization.json.*

/** Uses the live OEM provider so its in-process preferences cache and ContentObservers stay coherent. */
internal class AgentMonitoring(
    resolver: ContentResolver,
    private val source: Source,
    fitness: AgentFitnessHeartRateLimit? = null,
    private val environment: AgentMonitoringEnvironment = AgentMonitoringEnvironment(),
    private val privateSettings: PrivateMonitoring? = null,
    private val provider: MonitoringProviderAccess = AndroidMonitoringProvider(resolver, fitness),
) {
    data class Source(val packageName: String, val versionName: String?, val versionCode: Long)

    fun execute(args: JsonObject): JsonObject {
        val id = args.text("id")
        require(args.keys.all { it in setOf("id", "value") }) { "invalid_monitoring_request" }
        require(id != null || args["value"] == null) { "invalid_monitoring_request" }
        val before = readAll()
        if (id == null) return result("READ", null, before)
        if (source.versionCode !in HealthMonitoringProtocol.ANALYZED_VERSIONS)
            return result("UNAVAILABLE", "monitoring_version_not_analyzed", before)
        val raw = args.text("value") ?: throw IllegalArgumentException("monitoring_value_required")
        val spec = HealthMonitoringProtocol.spec(id)
        require(spec.accepts(raw)) { "invalid_monitoring_value" }
        before.unavailable[spec.key]?.let { return result("UNAVAILABLE", it, before) }
        if (spec.inProcess) {
            // The module owns validation against the other live time ranges and calls the
            // original setters/alarms in the health process. Never retry a sent setter.
            return try {
                val reply = (privateSettings ?: throw MonitoringUnavailable("monitoring_module_unavailable")).exchange(id, raw)
                val outcome = reply.text("outcome")
                if (outcome !in setOf("UNCHANGED", "STORED_AND_NOTIFIED", "STORED_UNVERIFIED", "INVALID_ARGUMENT", "UNAVAILABLE", "FAILED"))
                    return result("STORED_UNVERIFIED", "monitoring_module_protocol_mismatch", readAll(reply))
                if (outcome in setOf("UNCHANGED", "STORED_AND_NOTIFIED")) {
                    val row = (reply["settings"] as? JsonArray)?.map { it.jsonObject }?.singleOrNull { it.text("id") == id }
                    if (row?.flag("available") != true || row.text("value") != raw)
                        return result("STORED_UNVERIFIED", "monitoring_module_readback_mismatch", readAll(reply))
                }
                result(outcome!!, reply.text("reason"), readAll(reply))
            } catch (problem: MonitoringUnavailable) {
                result("STORED_UNVERIFIED", problem.reason, readAll())
            } catch (_: Exception) {
                result("STORED_UNVERIFIED", "monitoring_module_result_unknown", readAll())
            }
        }
        val changes = try { HealthMonitoringProtocol.changes(id, raw, before.values, environment.defaults, environment.autoMotionFeatureEnabled) }
        catch (problem: IllegalArgumentException) {
            if (problem.message != "monitoring_dependent_default_unknown") throw problem
            return result("UNAVAILABLE", "monitoring_dependent_default_unknown", before)
        }
        val changed = mutableListOf<String>()
        val notified = mutableListOf<String>()
        var attemptedMutation = false
        var reason: String? = null
        for ((key, value) in changes) {
            try {
                val previous = read(key)
                if (previous == value && key == "exercise_heart_rate_upper") continue
                attemptedMutation = true
                if (previous == value) {
                    // A previous request can persist successfully and then lose its notification.
                    // An explicit repeat must repair observer delivery without resetting steps or
                    // appending a duplicate fitness record.
                    if (key == "step_pedometer") provider.notifySteps()
                } else if (key == "exercise_heart_rate_upper") {
                    if (!provider.put(key, value)) { reason = "monitoring_commit_failed"; break }
                } else if (key == "step_pedometer") {
                    // The OEM setter also clears stale motion/step counters and notifies its step
                    // provider. Its update always returns 0, so verification must use readback.
                    provider.step(value.toBooleanStrict())
                } else {
                    if (!provider.put(key, value)) { reason = "monitoring_commit_failed"; break }
                }
                if (previous != value) changed += key
                if (read(key) != value) { reason = "monitoring_readback_mismatch"; break }
                if (key != "exercise_heart_rate_upper")
                    provider.notify(key)
                notified += key
            } catch (problem: MonitoringUnavailable) { reason = problem.reason; break }
            catch (_: SecurityException) { reason = "monitoring_permission_denied"; break }
            catch (_: Exception) { reason = "monitoring_write_or_notify_failed"; break }
        }
        val after = readAll()
        val matched = changes.all { (key, value) ->
            (after.values[key] ?: if (key == "auto_motion") runCatching { read(key) }.getOrNull() else null) == value
        }
        val outcome = when {
            reason != null || !matched -> if (attemptedMutation) "STORED_UNVERIFIED" else "FAILED"
            changed.isEmpty() -> "UNCHANGED"
            else -> "STORED_AND_NOTIFIED"
        }
        return result(outcome, reason ?: "monitoring_readback_mismatch".takeUnless { matched }, after, changed, notified)
    }

    private fun read(key: String): String? = provider.read(key)
    private data class Snapshot(val values: Map<String, String?>, val unavailable: Map<String, String>, val defaults: Map<String, String>)
    private fun readAll(privateReply: JsonObject? = null): Snapshot {
        val unavailable = mutableMapOf<String, String>()
        val values = HealthMonitoringProtocol.SETTINGS.filter { !it.inProcess }.associate { spec ->
            spec.key to try { read(spec.key) }
            catch (problem: MonitoringUnavailable) { unavailable[spec.key] = problem.reason; null }
            catch (_: SecurityException) { unavailable[spec.key] = "monitoring_provider_permission_denied"; null }
            catch (_: Exception) { unavailable[spec.key] = "monitoring_provider_read_failed"; null }
        }.toMutableMap()
        val defaults = environment.defaults.toMutableMap()
        var failure = "monitoring_module_unavailable"
        val reply = privateReply ?: try { privateSettings?.exchange(null, null) }
        catch (problem: MonitoringUnavailable) { failure = problem.reason; null }
        catch (_: Exception) { failure = "monitoring_module_read_failed"; null }
        val entries = (reply?.get("settings") as? JsonArray)?.map { it.jsonObject }.orEmpty()
        HealthMonitoringProtocol.SETTINGS.filter { it.inProcess }.forEach { spec ->
            val entry = entries.singleOrNull { it.text("id") == spec.id }
            values[spec.key] = entry?.text("value")
            if (entry?.flag("available") != true) unavailable[spec.key] = entry?.text("unavailableReason") ?: failure
            entry?.text("default")?.let { defaults[spec.key] = it }
        }
        return Snapshot(values, unavailable, defaults)
    }
    private fun result(outcome: String, reason: String?, snapshot: Snapshot,
        changed: List<String> = emptyList(), notified: List<String> = emptyList()) = buildJsonObject {
        put("outcome", outcome); reason?.let { put("reason", it) }
        putJsonObject("source") { put("packageName", source.packageName); put("versionName", source.versionName); put("versionCode", source.versionCode) }
        put("writable", source.versionCode in HealthMonitoringProtocol.ANALYZED_VERSIONS)
        putJsonArray("settings") { HealthMonitoringProtocol.SETTINGS.forEach { spec ->
            add(buildJsonObject {
                put("id", spec.id); put("value", snapshot.values[spec.key]); put("available", spec.key !in snapshot.unavailable)
                snapshot.unavailable[spec.key]?.let { put("unavailableReason", it) }
                snapshot.defaults[spec.key]?.let { put("default", it) }
                if (spec.id in setOf("SLEEP", "AUTO_EXERCISE")) environment.autoMotionFeatureEnabled?.let { put("firmwareFeatureEnabled", it) }
            })
        } }
        putJsonArray("changed") { changed.forEach { add(it) } }
        putJsonArray("notified") { notified.forEach { add(it) } }
    }

    companion object {
        @Suppress("DEPRECATION")
        fun handle(wire: AgentWire, environment: AgentEnvironment, args: JsonObject) {
            if (environment.uid != 0) throw MonitoringUnavailable("monitoring_root_required")
            val context: Context = environment.context
            val provider = context.packageManager.resolveContentProvider(HealthMonitoringProtocol.AUTHORITY, 0)
                ?: throw MonitoringUnavailable("monitoring_provider_missing")
            if (provider.packageName != HealthMonitoringProtocol.PACKAGE) throw MonitoringUnavailable("monitoring_provider_identity_mismatch")
            if (args.text("id") == "STEP_COUNTING") {
                val steps = context.packageManager.resolveContentProvider(HealthMonitoringProtocol.STEP_AUTHORITY, 0)
                    ?: throw MonitoringUnavailable("monitoring_step_provider_missing")
                if (steps.packageName != HealthMonitoringProtocol.PACKAGE) throw MonitoringUnavailable("monitoring_provider_identity_mismatch")
            }
            val info = context.packageManager.getPackageInfo(HealthMonitoringProtocol.PACKAGE, 0)
            val source = Source(provider.packageName, info.versionName, info.longVersionCode)
            val privateSettings = AgentPrivateMonitoring.create(context, source)
            val access = MonitoringProviderRoute(AndroidMonitoringProvider(context.contentResolver, AgentFitnessHeartRateLimit(context)), privateSettings)
            wire.json(ToolboxProtocol.RESULT, AgentMonitoring(context.contentResolver, source,
                environment = AgentMonitoringEnvironment.read(context, source.versionCode, access::autoMotionFeature),
                privateSettings = privateSettings, provider = access).execute(args))
        }
    }
}
internal class MonitoringUnavailable(val reason: String) : IllegalStateException(reason)
