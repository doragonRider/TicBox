package dev.ticbox.companion.toolboxagent

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import java.time.Clock

/**
 * The OEM UI stores this setting as a new Type16 data point, not as an app_ctrl
 * preference. The provider preserves its own notify/storage hooks. TicFitness
 * reads the value when preparing a sport; this does not restart a current sport.
 * Account and device identities stay on the watch and never become wire fields.
 */
internal class AgentFitnessHeartRateLimit(
    private val context: Context,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val resolver get() = context.contentResolver

    fun read(): String? = checked("monitoring_fitness_read_failed") {
        val identity = identity()
        readLatest(identity, exclusiveUpperBound(clock.millis()))
    }

    fun write(value: Long) = checked("monitoring_fitness_write_failed") {
        if (value !in 100L..220L) throw MonitoringUnavailable("monitoring_fitness_limit_out_of_range")
        val identity = identity()
        val now = clock.millis()
        if (now <= 0 || now == Long.MAX_VALUE) throw MonitoringUnavailable("monitoring_fitness_clock_invalid")
        val values = ContentValues().apply {
            put("device_id", identity.deviceId)
            put("wwid", identity.wwid)
            put("type", 16)
            put("time_from", now)
            put("time_to", now)
            put("values", value.toString())
            put("synced", 0)
            put("deleted", 0)
        }
        val inserted = resolver.insert(DATA_URI, values)
            ?: throw MonitoringUnavailable("monitoring_fitness_commit_failed")
        // Refuse an unexpected provider response before claiming the write was verified.
        if (inserted.authority != DATA_AUTHORITY || inserted.pathSegments.size != 2 ||
            inserted.pathSegments[0] != "data_point" || inserted.pathSegments[1].toLongOrNull()?.let { it >= 0 } != true
        ) throw MonitoringUnavailable("monitoring_fitness_commit_unverified")
        if (identity() != identity) throw MonitoringUnavailable("monitoring_fitness_identity_changed")
        if (readLatest(identity, exclusiveUpperBound(maxOf(now, clock.millis()))) != value.toString())
            throw MonitoringUnavailable("monitoring_fitness_readback_mismatch")
    }

    private data class Identity(val deviceId: String, val wwid: String)

    private fun identity(): Identity {
        checkOwner(DATA_AUTHORITY, HEALTH_PACKAGE, required = true)
        checkOwner(INFO_AUTHORITY, INFO_PACKAGE, required = true)
        val device = providerString(INFO_AUTHORITY, "wear", "wear_device_id")
        if (device.isBlank() || device == "illegal_device_id")
            throw MonitoringUnavailable("monitoring_fitness_device_identity_missing")
        // Match the original AccountInfoHelper: fallback only if the account
        // provider does not exist, never after permission or read failures.
        val hasAccount = checkOwner(ACCOUNT_AUTHORITY, ACCOUNT_PACKAGE, required = false)
        val wwid = if (hasAccount) providerString(ACCOUNT_AUTHORITY, "account", "wwid")
            else providerString(INFO_AUTHORITY, "phone", "wwid")
        return Identity(device, wwid)
    }

    @Suppress("DEPRECATION")
    private fun checkOwner(authority: String, owner: String, required: Boolean): Boolean {
        val provider = context.packageManager.resolveContentProvider(authority, 0)
        if (provider == null) {
            if (required) throw MonitoringUnavailable("monitoring_fitness_provider_missing")
            return false
        }
        if (provider.packageName != owner)
            throw MonitoringUnavailable("monitoring_fitness_provider_identity_mismatch")
        return true
    }

    @Suppress("DEPRECATION")
    private fun providerString(authority: String, table: String, key: String): String {
        val extras = Bundle().apply { putString("table", table); putString("name", key) }
        val uri = Uri.Builder().scheme("content").authority(authority).appendPath(table).appendPath(key).build()
        val reply = resolver.call(uri, "get", null, extras)
            ?: throw MonitoringUnavailable("monitoring_fitness_identity_no_reply")
        val value = reply.get("value")
        if (value !is String || value.length > 256)
            throw MonitoringUnavailable("monitoring_fitness_identity_unavailable")
        return value
    }

    private fun readLatest(identity: Identity, end: Long): String? {
        // The original IPC reader filters by current nonempty wwid, type and
        // time. It does not restrict these setting records to one device_id.
        var selection = "(time_to >= ? AND time_to < ?) AND type = ?"
        val args = mutableListOf("0", end.toString(), "16")
        if (identity.wwid.isNotEmpty()) { selection += " AND wwid = ?"; args += identity.wwid }
        val cursor = resolver.query(DATA_URI, arrayOf("values"), selection, args.toTypedArray(), "time_to DESC, _id DESC LIMIT 1")
            ?: throw MonitoringUnavailable("monitoring_fitness_query_no_reply")
        return cursor.use {
            if (!it.moveToFirst()) return@use null
            val index = it.getColumnIndex("values")
            if (index < 0 || it.isNull(index)) throw MonitoringUnavailable("monitoring_fitness_value_unavailable")
            val text = it.getString(index)
            if (text.length > 256) throw MonitoringUnavailable("monitoring_fitness_value_invalid")
            try {
                text.toBigDecimal().toBigIntegerExact().toString().toLongOrNull()?.toString()
                    ?: throw MonitoringUnavailable("monitoring_fitness_value_invalid")
            } catch (_: ArithmeticException) { throw MonitoringUnavailable("monitoring_fitness_value_invalid") }
            catch (_: NumberFormatException) { throw MonitoringUnavailable("monitoring_fitness_value_invalid") }
        }
    }

    private fun exclusiveUpperBound(now: Long): Long {
        if (now <= 0 || now == Long.MAX_VALUE) throw MonitoringUnavailable("monitoring_fitness_clock_invalid")
        // Include a point written during the current millisecond, including in
        // deterministic tests, while preserving an exclusive query upper bound.
        return now + 1
    }

    private inline fun <T> checked(fallback: String, action: () -> T): T = try {
        action()
    } catch (error: MonitoringUnavailable) { throw error }
    catch (_: SecurityException) { throw MonitoringUnavailable("monitoring_fitness_permission_denied") }
    catch (_: Exception) { throw MonitoringUnavailable(fallback) }

    private companion object {
        const val DATA_AUTHORITY = "com.mobvoi.health.db.provider"
        const val HEALTH_PACKAGE = "com.mobvoi.wear.health.at"
        const val INFO_AUTHORITY = "com.mobvoi.wear.provider.info"
        const val INFO_PACKAGE = "com.mobvoi.wear.appsservice"
        const val ACCOUNT_AUTHORITY = "com.mobvoi.wear.provider.accountInfo"
        const val ACCOUNT_PACKAGE = "com.mobvoi.wear.account.aw"
        val DATA_URI: Uri = Uri.parse("content://$DATA_AUTHORITY/data_point")
    }
}
