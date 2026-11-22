package dev.ticbox.companion.toolboxagent

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import dev.ticbox.companion.protocol.*
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AgentMonitoringTest {
    private class Provider : ContentProvider() {
        val values = mutableMapOf<String, String?>()
        val writes = mutableListOf<Pair<String, String?>>()
        var failKey: String? = null
        var denied = false
        var stepResets = 0
        override fun onCreate() = true
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
            if (denied) throw SecurityException("OEM permission denied")
            assertNull(arg); assertEquals("app_ctrl", extras!!.getString("table"))
            val key = extras.getString("name")!!
            val old = values[key]
            if (method == "put") {
                writes += key to extras.getString("value")
                if (key != failKey) values[key] = extras.getString("value")
            } else assertEquals("get", method)
            // Reproduce the OEM's important detail: put returns the OLD value.
            return Bundle().apply { putString("value", old); if (method == "put") putBoolean("status", key != failKey) }
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            assertEquals(HealthMonitoringProtocol.STEP_AUTHORITY, uri.authority)
            assertNull(selection); assertNull(selectionArgs)
            assertEquals(setOf("enable"), values!!.keySet())
            val enabled = values.getAsBoolean("enable")!!.toString()
            writes += "step_pedometer" to enabled
            this.values["step_pedometer"] = enabled
            stepResets++
            context!!.contentResolver.notifyChange(uri, null)
            return 0
        }
    }
    private fun fixture(version: Long = 50_603_234L, privateSettings: PrivateMonitoring? = null): Pair<Provider, AgentMonitoring> {
        val context = RuntimeEnvironment.getApplication()
        val provider = Provider()
        val info = ProviderInfo().apply { authority = HealthMonitoringProtocol.AUTHORITY; packageName = HealthMonitoringProtocol.PACKAGE; exported = true }
        provider.attachInfo(context, info)
        ShadowContentResolver.registerProviderInternal(HealthMonitoringProtocol.AUTHORITY, provider)
        ShadowContentResolver.registerProviderInternal(HealthMonitoringProtocol.STEP_AUTHORITY, provider)
        return provider to AgentMonitoring(context.contentResolver, AgentMonitoring.Source(HealthMonitoringProtocol.PACKAGE, "5.6.0-3234.2686", version),
            environment = AgentMonitoringEnvironment(autoMotionFeatureEnabled = true), privateSettings = privateSettings)
    }
    private fun request(id: String, value: String) = buildJsonObject { put("id", id); put("value", value) }
    @Test fun originalProviderCacheIsUpdatedAndObserverSeesTheNewValueBeforeSuccess() {
        val (provider, client) = fixture()
        val observed = mutableListOf<Pair<String, String?>>()
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val root = Uri.parse("content://${HealthMonitoringProtocol.AUTHORITY}/app_ctrl")
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean, uri: Uri?) { val key = uri!!.lastPathSegment!!; observed += key to provider.values[key] }
        }
        resolver.registerContentObserver(root, true, observer)
        try {
            val response = client.execute(request("REST_HEART_RATE_ALERT", "true"))
            assertEquals("STORED_AND_NOTIFIED", response.text("outcome"))
            assertEquals<List<Pair<String, String?>>>(listOf("background_hrm" to "true", "background_hrm_mode" to "1", "rest_hrm_warning" to "true", "auto_motion" to "true"), observed)
            val entry = response["settings"]!!.jsonArray.map { it.jsonObject }.single { it.text("id") == "REST_HEART_RATE_ALERT" }
            assertEquals("true", entry.text("value"))
        } finally { resolver.unregisterContentObserver(observer) }
    }
    @Test fun readbackUsesGetInsteadOfTreatingPutOldValueAsTheAppliedValue() {
        val (provider, client) = fixture(); provider.values["auto_detect_pressure"] = "false"
        val response = client.execute(request("PRESSURE", "true"))
        assertEquals("STORED_AND_NOTIFIED", response.text("outcome"))
        assertEquals("true", response["settings"]!!.jsonArray.map { it.jsonObject }.single { it.text("id") == "PRESSURE" }.text("value"))
        assertEquals("true", provider.values["auto_detect_pressure"])
    }
    @Test fun explicitRepeatRepairsAnObserverFailureWithoutPersistingTheValueAgain() {
        val (provider, client) = fixture()
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val path = Uri.parse("content://${HealthMonitoringProtocol.AUTHORITY}/app_ctrl/auto_detect_pressure")
        var fail = true
        var received = 0
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (fail) throw IllegalStateException("observer failed after persistence")
                received++
            }
        }
        resolver.registerContentObserver(path, false, observer)
        try {
            assertEquals("STORED_UNVERIFIED", client.execute(request("PRESSURE", "true")).text("outcome"))
            assertEquals("true", provider.values["auto_detect_pressure"])
            fail = false
            assertEquals("UNCHANGED", client.execute(request("PRESSURE", "true")).text("outcome"))
            assertEquals(1, provider.writes.size)
            assertEquals(1, received)
        } finally { resolver.unregisterContentObserver(observer) }
    }
    @Test fun failedSecondWriteReportsPartialConfigurationAndNeverClaimsFullSuccess() {
        val (provider, client) = fixture(); provider.failKey = "background_hrm_mode"
        val response = client.execute(request("REST_HEART_RATE_ALERT", "true"))
        assertEquals("STORED_UNVERIFIED", response.text("outcome")); assertEquals("monitoring_commit_failed", response.text("reason"))
        assertEquals("true", provider.values["background_hrm"]); assertNull(provider.values["rest_hrm_warning"])
    }
    @Test fun stepToggleUsesTheOemResetAndNotifyEntryEvenThoughItsUpdateReportsZeroRows() {
        val (provider, client) = fixture(); provider.values["step_pedometer"] = "true"
        val response = client.execute(request("STEP_COUNTING", "false"))
        assertEquals("STORED_AND_NOTIFIED", response.text("outcome"))
        assertEquals(1, provider.stepResets)
        assertEquals<List<Pair<String, String?>>>(listOf("step_pedometer" to "false"), provider.writes)
        assertEquals("false", provider.values["step_pedometer"])
        assertEquals("UNCHANGED", client.execute(request("STEP_COUNTING", "false")).text("outcome"))
        assertEquals(1, provider.stepResets)
    }
    @Test fun unrecognizedVersionAndInvalidRequestCannotWriteAnyOemPreference() {
        val (provider, client) = fixture(999)
        assertEquals("UNAVAILABLE", client.execute(request("PRESSURE", "true")).text("outcome"))
        assertTrue(provider.writes.isEmpty())
        val (next, supported) = fixture()
        assertFailsWith<IllegalArgumentException> { supported.execute(request("SLEEP_BLOOD_OXYGEN_INTERVAL", "5")) }
        assertFailsWith<IllegalArgumentException> { supported.execute(buildJsonObject { put("table", "healthy"); put("id", "PRESSURE"); put("value", "true") }) }
        assertTrue(next.writes.isEmpty())
    }
    @Test fun unknownAutoMotionInputIsRejectedBeforeTheFirstMutation() {
        val (provider, client) = fixture()
        val response = client.execute(request("SLEEP", "false"))
        assertEquals("UNAVAILABLE", response.text("outcome")); assertEquals("monitoring_dependent_default_unknown", response.text("reason"))
        assertTrue(provider.writes.isEmpty())
    }
    @Test fun missingExerciseIdentityDoesNotHideReadableProviderSettingsOrPermitAThresholdWrite() {
        val (provider, client) = fixture()
        val read = client.execute(buildJsonObject { })
        assertEquals("READ", read.text("outcome"))
        val entries = read["settings"]!!.jsonArray.map { it.jsonObject }
        assertEquals(false, entries.single { it.text("id") == "EXERCISE_HEART_RATE_UPPER" }.flag("available"))
        assertEquals(true, entries.single { it.text("id") == "PRESSURE" }.flag("available"))
        assertEquals("UNAVAILABLE", client.execute(request("EXERCISE_HEART_RATE_UPPER", "195")).text("outcome"))
        assertTrue(provider.writes.isEmpty())
    }
    @Test fun shellAgentCannotAcquireAnOemSignaturePermissionBySelectingThisOperation() {
        val error = assertFailsWith<MonitoringUnavailable> {
            AgentMonitoring.handle(AgentWire(byteArrayOf().inputStream(), ByteArrayOutputStream()),
                AgentEnvironment(2000, 33, "", "/system/bin/sh", suppliedContext = RuntimeEnvironment.getApplication()), buildJsonObject {})
        }
        assertEquals("monitoring_root_required", error.reason)
    }
    @Test fun missingModuleOnlyDisablesItsOwnSettingsAndCannotFallThroughToProviderWrites() {
        val (provider, client) = fixture()
        val response = client.execute(buildJsonObject { })
        val settings = response["settings"]!!.jsonArray.map { it.jsonObject }
        assertEquals(16, settings.count { it.flag("available") == false && it.text("unavailableReason") == "monitoring_module_unavailable" })
        assertEquals(true, settings.single { it.text("id") == "PRESSURE" }.flag("available"))
        assertEquals("UNAVAILABLE", client.execute(request("SEDENTARY_REMINDER", "false")).text("outcome"))
        assertTrue(provider.writes.isEmpty())
    }
    @Test fun privateSetterUsesOneInProcessCommandAndNeverWritesTheProviderWithAPrivateKey() {
        val calls = mutableListOf<Pair<String?, String?>>()
        var value = "true"
        val (provider, client) = fixture(privateSettings = PrivateMonitoring { id, raw ->
            calls += id to raw
            if (id != null) value = raw!!
            buildJsonObject {
                put("outcome", if (id == null) "READ" else "STORED_AND_NOTIFIED")
                putJsonArray("settings") { HealthMonitoringProtocol.SETTINGS.filter { it.inProcess }.forEach { spec ->
                    add(buildJsonObject { put("id", spec.id); put("value", if (spec.id == "SEDENTARY_REMINDER") value else spec.default); put("available", true) })
                } }
            }
        })
        val result = client.execute(request("SEDENTARY_REMINDER", "false"))
        assertEquals("STORED_AND_NOTIFIED", result.text("outcome"))
        assertEquals(listOf(null to null, "SEDENTARY_REMINDER" to "false"), calls)
        assertTrue(provider.writes.isEmpty())
        assertEquals("false", result["settings"]!!.jsonArray.map { it.jsonObject }.single { it.text("id") == "SEDENTARY_REMINDER" }.text("value"))
    }
    @Test fun privateSetterCannotClaimConfirmationFromAReadReceiptOrUnchangedReadback() {
        for (outcome in listOf("READ", "UNCHANGED", "STORED_AND_NOTIFIED")) {
            val (provider, client) = fixture(privateSettings = PrivateMonitoring { _, _ -> buildJsonObject {
                put("outcome", outcome)
                putJsonArray("settings") { HealthMonitoringProtocol.SETTINGS.filter { it.inProcess }.forEach { spec ->
                    add(buildJsonObject { put("id", spec.id); put("value", spec.default); put("available", true) })
                } }
            } })
            val result = client.execute(request("SEDENTARY_REMINDER", "false"))
            assertEquals("STORED_UNVERIFIED", result.text("outcome"))
            assertEquals(if (outcome == "READ") "monitoring_module_protocol_mismatch" else "monitoring_module_readback_mismatch", result.text("reason"))
            assertTrue(provider.writes.isEmpty())
        }
    }
    @Test fun deniedPublicProviderDoesNotHideTheInstalledModulesPrivateSettings() {
        val (provider, client) = fixture(privateSettings = PrivateMonitoring { _, _ -> buildJsonObject {
            put("outcome", "READ")
            putJsonArray("settings") { HealthMonitoringProtocol.SETTINGS.filter { it.inProcess }.forEach { spec ->
                add(buildJsonObject { put("id", spec.id); put("value", spec.default); put("available", true) })
            } }
        } })
        provider.denied = true
        val result = client.execute(buildJsonObject { })
        assertEquals("READ", result.text("outcome"))
        val rows = result["settings"]!!.jsonArray.map { it.jsonObject }
        assertEquals("monitoring_provider_permission_denied", rows.single { it.text("id") == "PRESSURE" }.text("unavailableReason"))
        assertEquals(true, rows.single { it.text("id") == "SEDENTARY_REMINDER" }.flag("available"))
        assertEquals("UNAVAILABLE", client.execute(request("PRESSURE", "true")).text("outcome"))
        assertTrue(provider.writes.isEmpty())
    }

}
