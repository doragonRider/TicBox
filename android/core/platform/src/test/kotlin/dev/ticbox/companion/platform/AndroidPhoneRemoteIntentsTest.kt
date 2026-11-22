@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.ticbox.companion.platform

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidPhoneRemoteIntentsTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    @Before fun mainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun resetDispatcher() { Dispatchers.resetMain() }
    private fun request(vararg fields: Pair<String, DataMapValue>) = RemoteIntentCodec.decode(DataMapCodec.encode(mapOf(*fields)))

    @Test fun `activity retains URI MIME package categories and supported optional extras`() = runTest {
        val logs = mutableListOf<ProtocolDiagnostic>()
        val executor = AndroidPhoneRemoteIntents(app, DiagnosticSink { logs += it })
        assertTrue(executor.execute(request(
            "action" to DataMapValue.Text(Intent.ACTION_VIEW),
            "uri_data" to DataMapValue.Text("content://test-provider/record/7"),
            "mime_type" to DataMapValue.Text("application/example"),
            "package_name" to DataMapValue.Text("example.viewer"),
            "categories" to DataMapValue.Strings(listOf(Intent.CATEGORY_DEFAULT)),
            "extras" to DataMapValue.MapValue(mapOf("number" to DataMapValue.IntNumber(7),
                "nested" to DataMapValue.MapValue(mapOf("ok" to DataMapValue.Bool(true))),
                "unsupported" to DataMapValue.AssetIndex(2))),
        )))
        val intent = assertNotNull(shadowOf(app).nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("content://test-provider/record/7", intent.dataString)
        assertEquals("application/example", intent.type)
        assertEquals("example.viewer", intent.`package`)
        assertTrue(intent.hasCategory(Intent.CATEGORY_DEFAULT))
        assertEquals(7, intent.getIntExtra("number", -1))
        assertTrue(assertNotNull(intent.getBundleExtra("nested")).getBoolean("ok"))
        assertFalse(intent.hasExtra("unsupported"))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(logs.any { it.event == "optional_bundle_values_unavailable" })
    }

    @Test fun `broadcast is dispatched while unsupported webview reports false`() = runTest {
        val executor = AndroidPhoneRemoteIntents(app)
        assertTrue(executor.execute(request("action" to DataMapValue.Text("example.LOCAL_COMMAND"),
            "start_mode" to DataMapValue.IntNumber(1))))
        assertTrue(shadowOf(app).broadcastIntents.any { it.action == "example.LOCAL_COMMAND" })
        assertFalse(executor.execute(request("action" to DataMapValue.Text(Intent.ACTION_VIEW),
            "start_mode" to DataMapValue.IntNumber(3))))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun `service acceptance is based on native startService result`() = runTest {
        var requested: Intent? = null
        val refusingContext = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun startService(service: Intent): android.content.ComponentName? { requested = service; return null }
        }
        assertFalse(AndroidPhoneRemoteIntents(refusingContext).execute(request(
            "action" to DataMapValue.Text("example.START"), "package_name" to DataMapValue.Text("example.service"),
            "start_mode" to DataMapValue.IntNumber(2))))
        assertEquals("example.START", assertNotNull(requested).action)
        assertEquals("example.service", requested?.`package`)
    }
}
