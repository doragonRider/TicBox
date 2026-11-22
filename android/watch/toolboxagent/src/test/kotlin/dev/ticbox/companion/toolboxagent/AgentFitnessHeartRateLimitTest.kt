package dev.ticbox.companion.toolboxagent

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AgentFitnessHeartRateLimitTest {
    private class Provider : ContentProvider() {
        val rows = mutableListOf<ContentValues>()
        val calls = mutableListOf<Pair<String, String>>()
        val identities = mutableMapOf("wear/wear_device_id" to "watch-local-id", "phone/wwid" to "", "account/wwid" to "local-account")
        var deny = false
        var noIdentityReply = false
        var noQueryReply = false
        var missingValueColumn = false
        var dropInsert = false
        var inserts = 0
        var notifies = 0
        var queryWwid: String? = null

        override fun onCreate() = true
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            if (deny) throw SecurityException("No identity permission")
            assertEquals("get", method); assertNull(arg)
            assertEquals(setOf("table", "name"), extras!!.keySet())
            val table = extras.getString("table")!!; val name = extras.getString("name")!!
            calls += table to name
            if (noIdentityReply) return null
            return Bundle().apply { putString("value", identities["$table/$name"]) }
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            if (deny) throw SecurityException("No database permission")
            assertEquals("content://com.mobvoi.health.db.provider/data_point", uri.toString())
            assertEquals(listOf("values"), projection?.toList())
            assertEquals("time_to DESC, _id DESC LIMIT 1", sortOrder)
            assertEquals(if (selectionArgs!!.size == 4) "(time_to >= ? AND time_to < ?) AND type = ? AND wwid = ?" else "(time_to >= ? AND time_to < ?) AND type = ?", selection)
            assertEquals("0", selectionArgs[0]); assertEquals("16", selectionArgs[2])
            queryWwid = selectionArgs.getOrNull(3)
            if (noQueryReply) return null
            val cursor = MatrixCursor(if (missingValueColumn) arrayOf("unrelated") else arrayOf("values"))
            rows.withIndex().filter { (_, row) ->
                row.getAsLong("time_to")!! < selectionArgs[1].toLong() &&
                    row.getAsInteger("type") == 16 && (queryWwid == null || row.getAsString("wwid") == queryWwid)
            }.maxWithOrNull(compareBy<IndexedValue<ContentValues>> { it.value.getAsLong("time_to") }.thenBy { it.index })
                ?.let { cursor.addRow(arrayOf(it.value.getAsString("values"))) }
            return cursor
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            if (deny) throw SecurityException("No database permission")
            assertEquals("content://com.mobvoi.health.db.provider/data_point", uri.toString())
            assertEquals(setOf("device_id", "wwid", "type", "time_from", "time_to", "values", "synced", "deleted"), values!!.keySet())
            inserts++
            if (!dropInsert) rows += ContentValues(values)
            // The original provider owns notification and G0; the client should
            // preserve its insert entry point rather than issue raw SQL.
            notifies++; context!!.contentResolver.notifyChange(uri, null)
            return uri.buildUpon().appendPath(inserts.toString()).build()
        }
        override fun getType(uri: Uri): String? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("No historical record deletion")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("No existing record replacement")
    }

    private data class Fixture(val context: Context, val provider: Provider, val client: AgentFitnessHeartRateLimit)
    private fun fixture(accountProvider: Boolean = true, wrongOwner: String? = null): Fixture {
        val context = RuntimeEnvironment.getApplication()
        val provider = Provider()
        val owners = mutableListOf(
            "com.mobvoi.health.db.provider" to "com.mobvoi.wear.health.at",
            "com.mobvoi.wear.provider.info" to "com.mobvoi.wear.appsservice",
        )
        if (accountProvider) owners += "com.mobvoi.wear.provider.accountInfo" to "com.mobvoi.wear.account.aw"
        owners.forEach { (authority, owner) ->
            val pkg = if (authority == wrongOwner) "untrusted.owner" else owner
            val info = ProviderInfo().apply { this.authority = authority; packageName = pkg; name = "$pkg.TestProvider"; exported = true }
            @Suppress("DEPRECATION")
            shadowOf(context.packageManager).installPackage(PackageInfo().apply {
                packageName = pkg
                applicationInfo = ApplicationInfo().apply { packageName = pkg }
                providers = arrayOf(info)
            })
            if (authority == "com.mobvoi.health.db.provider") provider.attachInfo(context, info)
            ShadowContentResolver.registerProviderInternal(authority, provider)
        }
        return Fixture(context, provider, AgentFitnessHeartRateLimit(context, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC)))
    }
    private fun row(value: String, wwid: String = "local-account", time: Long = NOW - 1) = ContentValues().apply {
        put("type", 16); put("time_to", time); put("wwid", wwid); put("values", value)
    }

    @Test fun emptyDatabaseIsUnknownAndExistingFloatTextIsNormalizedWithoutInventingADefault() {
        val f = fixture()
        assertNull(f.client.read()); assertEquals(0, f.provider.inserts)
        f.provider.rows += row("195.0")
        f.provider.rows += row("210", wwid = "other-account")
        assertEquals("195", f.client.read()); assertEquals("local-account", f.provider.queryWwid)
    }

    @Test fun writeInsertsFixedType16WithLocalIdentitiesAndVerifiesAtTheSameMillisecond() {
        val f = fixture()
        f.provider.rows += row("180")
        f.client.write(190)
        val actual = f.provider.rows.last()
        assertEquals(2, f.provider.rows.size); assertEquals(1, f.provider.inserts); assertEquals(1, f.provider.notifies)
        assertEquals("watch-local-id", actual.getAsString("device_id")); assertEquals("local-account", actual.getAsString("wwid"))
        assertEquals(NOW, actual.getAsLong("time_from")); assertEquals(NOW, actual.getAsLong("time_to"))
        assertEquals(16, actual.getAsInteger("type")); assertEquals("190", actual.getAsString("values"))
        assertEquals(0, actual.getAsInteger("synced")); assertEquals(0, actual.getAsInteger("deleted"))
        assertEquals("190", f.client.read())
    }

    @Test fun missingAccountProviderUsesTheOriginalInfoFallbackAndAcceptsExplicitAccountlessValue() {
        val f = fixture(accountProvider = false)
        f.client.write(200)
        assertTrue("phone" to "wwid" in f.provider.calls)
        assertFalse("account" to "wwid" in f.provider.calls)
        assertEquals("", f.provider.rows.single().getAsString("wwid")); assertNull(f.provider.queryWwid)
    }

    @Test fun wrongOwnerIsRejectedBeforeMutation() {
        val wrong = fixture(wrongOwner = "com.mobvoi.wear.provider.accountInfo")
        assertEquals("monitoring_fitness_provider_identity_mismatch", assertFailsWith<MonitoringUnavailable> { wrong.client.write(190) }.reason)
        assertEquals(0, wrong.provider.inserts)
    }

    @Test fun permissionDenialNeverFallsBackToAnInferredIdentity() {
        val denied = fixture(); denied.provider.deny = true
        assertEquals("monitoring_fitness_permission_denied", assertFailsWith<MonitoringUnavailable> { denied.client.write(190) }.reason)
        assertEquals(0, denied.provider.inserts)
    }

    @Test fun absentAccountValueDoesNotMeanAnAccountlessWatch() {
        val absent = fixture(); absent.provider.identities.remove("account/wwid")
        assertEquals("monitoring_fitness_identity_unavailable", assertFailsWith<MonitoringUnavailable> { absent.client.write(190) }.reason)
        assertFalse("phone" to "wwid" in absent.provider.calls); assertEquals(0, absent.provider.inserts)
    }

    @Test fun refusedOrMalformedDatabaseDataDoesNotBecomeSuccessOrADeviceDefault() {
        val noReply = fixture(); noReply.provider.noQueryReply = true
        assertEquals("monitoring_fitness_query_no_reply", assertFailsWith<MonitoringUnavailable> { noReply.client.read() }.reason)
        val malformed = fixture(); malformed.provider.rows += row("190.5")
        assertEquals("monitoring_fitness_value_invalid", assertFailsWith<MonitoringUnavailable> { malformed.client.read() }.reason)
        malformed.provider.missingValueColumn = true
        assertEquals("monitoring_fitness_value_unavailable", assertFailsWith<MonitoringUnavailable> { malformed.client.read() }.reason)
        val ignored = fixture(); ignored.provider.dropInsert = true
        assertEquals("monitoring_fitness_readback_mismatch", assertFailsWith<MonitoringUnavailable> { ignored.client.write(190) }.reason)
        assertEquals(1, ignored.provider.inserts)
    }

    @Test fun invalidLimitsAndIllegalDeviceIdentityAreRejectedBeforeInsertion() {
        val f = fixture()
        listOf(99L, 221L, Long.MAX_VALUE).forEach { value ->
            assertEquals("monitoring_fitness_limit_out_of_range", assertFailsWith<MonitoringUnavailable> { f.client.write(value) }.reason)
        }
        assertTrue(f.provider.calls.isEmpty()); assertEquals(0, f.provider.inserts)
        f.provider.identities["wear/wear_device_id"] = "illegal_device_id"
        assertEquals("monitoring_fitness_device_identity_missing", assertFailsWith<MonitoringUnavailable> { f.client.write(190) }.reason)
        assertEquals(0, f.provider.inserts)
    }

    @Test fun missingProviderIsUnavailableInsteadOfAnEmptySettingsDatabase() {
        val client = AgentFitnessHeartRateLimit(RuntimeEnvironment.getApplication(), Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC))
        assertEquals("monitoring_fitness_provider_missing", assertFailsWith<MonitoringUnavailable> { client.read() }.reason)
    }

    private companion object { const val NOW = 1_800_000_000_000L }
}
