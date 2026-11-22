package dev.ticbox.companion.toolboxagent

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import dev.ticbox.companion.protocol.HealthMonitoringProtocol
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AgentMonitoringEnvironmentTest {
    private class Flags(var flag: String?) : ContentProvider() {
        override fun onCreate() = true
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
            assertEquals("get", method); assertNull(arg)
            assertEquals("flags_feature", extras!!.getString("table")); assertEquals("auto_motion", extras.getString("name"))
            return Bundle().apply { putString("value", flag) }
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("read only")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("read only")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("read only")
    }
    private fun flags(value: String?): Flags {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.packageManager).setSystemFeature("android.hardware.type.watch", true)
        return Flags(value).also {
            it.attachInfo(context, ProviderInfo().apply { authority = HealthMonitoringProtocol.AUTHORITY; packageName = HealthMonitoringProtocol.PACKAGE; exported = true })
            ShadowContentResolver.registerProviderInternal(HealthMonitoringProtocol.AUTHORITY, it)
        }
    }
    @Test fun storedFeatureGateCanBlockEnabledSleepAndExercisePreferences() {
        val flag = flags("false"); ShadowBuild.setModel("TicWatch Pro 5 Enduro")
        val context = RuntimeEnvironment.getApplication()
        val environment = AgentMonitoringEnvironment.read(context, 50_603_234L)
        assertEquals("true", environment.defaults["background_hrm"])
        assertEquals("true", environment.defaults["auto_detect_fitness"])
        assertEquals(false, environment.autoMotionFeatureEnabled)
        assertEquals("false", HealthMonitoringProtocol.changes("SLEEP", "true", emptyMap(),
            environment.defaults, environment.autoMotionFeatureEnabled)["auto_motion"])
        flag.flag = "true"
        assertEquals(true, AgentMonitoringEnvironment.read(context, 50_603_234L).autoMotionFeatureEnabled)
    }
    @Test fun atlasDefaultDifferenceBetweenTheTwoAnalyzedArtifactsIsPreserved() {
        flags("true"); ShadowBuild.setModel("TicWatch Atlas")
        val context = RuntimeEnvironment.getApplication()
        assertEquals("true", AgentMonitoringEnvironment.read(context, 50_503_292L).defaults["background_hrm"])
        assertEquals("false", AgentMonitoringEnvironment.read(context, 50_603_234L).defaults["background_hrm"])
        assertNull(AgentMonitoringEnvironment.read(context, 50_503_292L).defaults["detect_sleep_temperature_unit"])
        assertEquals("0", AgentMonitoringEnvironment.read(context, 50_603_234L).defaults["detect_sleep_temperature_unit"])
    }
    @Test fun malformedGateAndUnreviewedVersionsAreNotGivenInventedDefaults() {
        flags("new-format"); ShadowBuild.setModel("TicWatch Pro 5")
        val context = RuntimeEnvironment.getApplication()
        assertNull(AgentMonitoringEnvironment.read(context, 50_603_234L).autoMotionFeatureEnabled)
        val future = AgentMonitoringEnvironment.read(context, 99)
        assertTrue(future.defaults.isEmpty()); assertNull(future.autoMotionFeatureEnabled)
    }
}
