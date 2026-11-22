package dev.ticbox.companion.healthbridge

import android.app.Application
import android.content.pm.ProviderInfo
import android.os.CancellationSignal
import dev.ticbox.companion.protocol.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.*
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35], application = Application::class)
class DebugSportProbeTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var provider: SportFixtureProvider
    @Before fun setup() {
        provider = SportFixtureProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = SportProviderReader.URI.authority; exported = true })
        ShadowContentResolver.registerProviderInternal(SportProviderReader.URI.authority!!, provider)
    }
    @After fun cleanup() { provider.db.close() }

    @Test fun sportProbeDoesNotExportHealthValuesOrIdentifiers() {
        provider.deny = false; provider.insert("private-workout-id", heart = 171)
        val report = HealthSportProbe.read(context.contentResolver, CancellationSignal(), true)
        assertEquals("query_returned", report.text("status")); assertEquals(1L, report.number("returnedRows"))
        assertEquals(false, report.flag("healthValuesIncluded")); assertEquals(false, report.flag("identifiersIncluded"))
        assertFalse("private-workout-id" in report.toString()); assertNull(report["rows"])
        assertEquals(0, provider.mutations)
    }
}
