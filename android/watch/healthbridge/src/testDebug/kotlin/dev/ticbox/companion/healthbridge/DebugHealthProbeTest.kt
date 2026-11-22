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

/** Developer-only probes are exercised against synthetic Providers, independently of the release reader tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DebugHealthProbeTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var provider: LocalHealthPipelineTest.ReadOnlyFixtureProvider
    @Before fun setup() {
        provider = LocalHealthPipelineTest.ReadOnlyFixtureProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = HealthProviderReader.URI.authority; exported = true })
        ShadowContentResolver.registerProviderInternal(HealthProviderReader.URI.authority!!, provider)
    }
    @After fun cleanup() { provider.db.close() }

    @Test fun debugSamplerUsesOnlyFiveLatestRowsAndRedactsIdentifiersWithoutChangingHealthValues() {
        repeat(8) { provider.insertFixture(it + 1L, "value-$it", 1) }
        val sample = HealthSamples.read(context.contentResolver, android.os.CancellationSignal(), true)
        val rows = sample["rows"]!!.jsonArray
        assertEquals(listOf("8", "7", "6", "5", "4"), rows.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals("value-7", rows.first().jsonObject["columns"]!!.jsonObject["values"]!!.jsonPrimitive.content)
        assertTrue(rows.all { row -> listOf("wwid", "device_id").all {
            row.jsonObject["columns"]!!.jsonObject[it]!!.jsonPrimitive.content == "<redacted>" } })
        assertFalse(sample.toString().contains("watch-id")); assertEquals(0, provider.mutations)
        provider.deny = true
        val denied = HealthSamples.read(context.contentResolver, android.os.CancellationSignal(), true)
        assertEquals("partial_or_failed", denied["status"]!!.jsonPrimitive.content)
        assertFalse(denied["healthCellValueGettersUsed"]!!.jsonPrimitive.boolean)
        assertFalse(denied.toString().contains("sensitive fixture"))
    }
    @Test @Config(sdk = [30]) fun debugSessionCheckUsesProductionReaderWithoutExportingRowValues() {
        provider.insertSession(1, "private-session-uuid", 946684860000, 946684920000)
        provider.insertSession(2, "another-private-uuid", 946684980000, 946685040000)
        provider.insertFixture(1, "private-point-value", 1)
        val report = HealthSessionProbe.read(context.contentResolver, android.os.CancellationSignal(), true)
        assertEquals("query_returned", report["status"]!!.jsonPrimitive.content)
        assertEquals(1, report["returnedRows"]!!.jsonPrimitive.int)
        assertFalse(report["pageComplete"]!!.jsonPrimitive.boolean)
        assertEquals(setOf("id", "device_id", "wwid", "type", "time_to", "time_from", "synced", "deleted"),
            report["columns"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals("string", report["storageTypes"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        listOf("private-session-uuid", "another-private-uuid", "private-point-value", "watch-id", "no-login-required",
            "946684860000", "946684920000").forEach { assertFalse(it in report.toString()) }
        assertTrue(provider.querySql.any { "UNION ALL SELECT" in it && "FROM data_session" in it })
        assertEquals(0, provider.mutations)
    }
    @Test fun debugSessionCheckDistinguishesEmptyHistoryFromDeniedOrMissingSessionTable() {
        fun check() = HealthSessionProbe.read(context.contentResolver, android.os.CancellationSignal(), true)
        val empty = check()
        assertEquals("query_returned", empty["status"]!!.jsonPrimitive.content)
        assertEquals(0, empty["returnedRows"]!!.jsonPrimitive.int)
        assertTrue(empty["pageComplete"]!!.jsonPrimitive.boolean)
        assertEquals("no_row_observed", empty["columnCoverage"]!!.jsonPrimitive.content)
        assertTrue(empty["columns"]!!.jsonArray.isEmpty())
        provider.deny = true
        val denied = check()
        assertEquals("query_failed", denied["status"]!!.jsonPrimitive.content)
        assertEquals("provider_denied", denied["error"]!!.jsonPrimitive.content)
        assertNull(denied["pageComplete"]); assertFalse("sensitive fixture" in denied.toString())
        provider.deny = false; provider.db.execSQL("DROP TABLE data_session")
        val missing = check()
        assertEquals("query_failed", missing["status"]!!.jsonPrimitive.content)
        assertEquals("query_failed", missing["error"]!!.jsonPrimitive.content)
        assertEquals(0, provider.mutations)
    }
}
