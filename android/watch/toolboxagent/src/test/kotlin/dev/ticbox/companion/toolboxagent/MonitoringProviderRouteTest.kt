package dev.ticbox.companion.toolboxagent

import kotlin.test.*

class MonitoringProviderRouteTest {
    private class Provider : MonitoringProviderAccess {
        var denyReads = false
        var denyWrites = false
        var readFailure: MonitoringUnavailable? = null
        var reads = 0
        var writes = 0
        var notices = 0
        val values = mutableMapOf<String, String>()
        override fun read(key: String): String? { reads++; readFailure?.let { throw it }; if (denyReads) throw SecurityException(); return values[key] }
        override fun autoMotionFeature() = read("feature")
        override fun put(key: String, value: String): Boolean { writes++; if (denyWrites) throw SecurityException(); values[key] = value; return true }
        override fun notify(key: String) { notices++ }
        override fun step(enabled: Boolean) { put("step_pedometer", enabled.toString()) }
        override fun notifySteps() { notices++ }
    }
    @Test fun deniedReadSelectsTheVerifiedModuleBeforeAnyMutationAndAllOperationsStayThere() {
        val root = Provider().apply { denyReads = true }
        val module = Provider().apply { values["auto_detect_pressure"] = "false" }
        val route = MonitoringProviderRoute(root, module)
        assertEquals("false", route.read("auto_detect_pressure"))
        assertTrue(route.put("auto_detect_pressure", "true")); route.notify("auto_detect_pressure")
        assertEquals("true", route.read("auto_detect_pressure")); route.step(false); route.notifySteps()
        assertEquals(1, root.reads); assertEquals(0, root.writes)
        assertEquals(2, module.writes); assertEquals(2, module.notices)
    }
    @Test fun directProviderStillWorksWithoutAnInstalledModule() {
        val root = Provider(); val module = Provider()
        val route = MonitoringProviderRoute(root, module)
        assertNull(route.read("auto_detect_pressure")); route.put("auto_detect_pressure", "true")
        assertEquals(0, module.reads); assertEquals(0, module.writes)
    }
    @Test fun aWriteOrObserverFailureDoesNotRetryOnTheModule() {
        val root = Provider().apply { denyWrites = true }; val module = Provider()
        val route = MonitoringProviderRoute(root, module)
        route.read("auto_detect_pressure")
        assertFailsWith<SecurityException> { route.put("auto_detect_pressure", "true") }
        assertEquals(1, root.writes); assertEquals(0, module.writes)
    }
    @Test fun unavailableModuleKeepsAnExplicitProviderPermissionReason() {
        val root = Provider().apply { denyReads = true }
        assertEquals("monitoring_provider_permission_denied", assertFailsWith<MonitoringUnavailable> {
            MonitoringProviderRoute(root, null).read("auto_detect_pressure")
        }.reason)
    }
    @Test fun missingOrOlderModuleIsProbedOnlyOncePerSnapshotAndNeverRetriedAsAMutation() {
        for (reason in listOf("monitoring_module_unavailable", "monitoring_module_update_required")) {
            val root = Provider().apply { denyReads = true }
            val module = Provider().apply { readFailure = MonitoringUnavailable(reason) }
            val route = MonitoringProviderRoute(root, module)
            for (key in listOf("background_hrm", "auto_detect_pressure", "auto_detect_sleep")) {
                assertEquals(reason, assertFailsWith<MonitoringUnavailable> { route.read(key) }.reason)
            }
            assertEquals(1, root.reads); assertEquals(1, module.reads); assertEquals(0, module.writes)
        }
    }

    @Test fun readbackDenialAfterASetterCannotChangeTheExecutionRoute() {
        val root = Provider(); val module = Provider()
        val route = MonitoringProviderRoute(root, module)
        route.read("auto_detect_pressure"); route.put("auto_detect_pressure", "true")
        root.denyReads = true
        assertEquals("monitoring_provider_permission_denied", assertFailsWith<MonitoringUnavailable> { route.read("auto_detect_pressure") }.reason)
        assertEquals(1, root.writes); assertEquals(0, module.reads); assertEquals(0, module.writes)
    }

}
