package dev.ticbox.companion.toolboxagent

import android.content.Context
import android.net.LocalSocket
import dev.ticbox.companion.protocol.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

internal fun interface PrivateMonitoring {
    fun exchange(id: String?, value: String?): JsonObject
}

internal interface PrivateMonitoringChannel : AutoCloseable {
    val uid: Int
    val input: InputStream
    val output: OutputStream
}

/** A fixed root-only RPC into the verified OEM process, with no arbitrary reflection or paths. */
internal class AgentPrivateMonitoring(
    private val source: AgentMonitoring.Source,
    private val expectedUid: Int,
    private val open: () -> PrivateMonitoringChannel,
) : PrivateMonitoring, MonitoringProviderAccess {
    override fun exchange(id: String?, value: String?): JsonObject {
        require(if (id == null) value == null else HealthMonitoringProtocol.spec(id).let { it.inProcess && value != null && it.accepts(value) })
        return request(buildJsonObject {
            put("version", HealthMonitoringProtocol.PRIVATE_VERSION); put("op", "settings")
            if (id != null) { put("id", id); put("value", value) }
        }) { reply, digest -> validate(reply, digest, id) }
    }

    private fun provider(method: String, key: String? = null, value: String? = null): JsonObject = request(buildJsonObject {
        put("version", HealthMonitoringProtocol.PRIVATE_VERSION); put("op", "provider"); put("method", method)
        key?.let { put("key", it) }; value?.let { put("value", it) }
    }) { reply, digest ->
        validateIdentity(reply, digest)
        if (reply.number("providerVersion") != 1L) throw MonitoringUnavailable("monitoring_module_update_required")
        reply.text("reason")?.let { throw MonitoringUnavailable(it) }
        if (reply.flag("success") != true) throw MonitoringUnavailable("monitoring_module_protocol_mismatch")
        if (method in setOf("get", "auto_motion_feature", "fitness_get") && "value" !in reply)
            throw MonitoringUnavailable("monitoring_module_protocol_mismatch")
        if (reply["value"] != null && reply["value"] != JsonNull &&
            ((reply["value"] as? JsonPrimitive)?.isString != true || (reply.text("value")?.length ?: 0) > 256))
            throw MonitoringUnavailable("monitoring_module_protocol_mismatch")
        reply
    }
    override fun read(key: String): String? = if (key == "exercise_heart_rate_upper") provider("fitness_get").text("value") else provider("get", key).text("value")
    override fun put(key: String, value: String): Boolean = if (key == "exercise_heart_rate_upper") provider("fitness_put", value = value).flag("status") == true else provider("put", key, value).flag("status") == true
    override fun notify(key: String) { provider("notify", key) }
    override fun step(enabled: Boolean) { provider("step", value = enabled.toString()) }
    override fun notifySteps() { provider("notify_steps") }
    override fun autoMotionFeature() = provider("auto_motion_feature").text("value")

    private fun request(request: JsonObject, validateReply: (JsonObject, String) -> JsonObject): JsonObject {
        val expectedDigest = HealthMonitoringProtocol.ANALYZED_APK_SHA256[source.versionCode]
            ?: throw MonitoringUnavailable("monitoring_module_version_not_analyzed")
        if (source.packageName != HealthMonitoringProtocol.PACKAGE || expectedUid <= 0)
            throw MonitoringUnavailable("monitoring_module_identity_mismatch")
        val channel = try { open() } catch (_: Exception) { throw MonitoringUnavailable("monitoring_module_unavailable") }
        channel.use {
            if (channel.uid != expectedUid) throw MonitoringUnavailable("monitoring_module_identity_mismatch")
            // Local sockets have a read timeout; also bound connect/write/slow-fragment work by
            // closing the entire operation. Closing never means an already-started setter rolled back.
            val expiry = DEADLINES.schedule({ runCatching(channel::close) }, 30, TimeUnit.SECONDS)
            try {
                val bytes = request.toString().toByteArray(Charsets.UTF_8)
                val output = DataOutputStream(channel.output)
                output.writeInt(bytes.size); output.write(bytes); output.flush()
                val input = DataInputStream(channel.input)
                val size = input.readInt()
                if (size !in 1..HealthMonitoringProtocol.PRIVATE_MAX_BYTES) throw MonitoringUnavailable("monitoring_module_protocol_mismatch")
                val response = ByteArray(size).also(input::readFully)
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response)).toString()
                val reply = Json.parseToJsonElement(text) as? JsonObject ?: throw MonitoringUnavailable("monitoring_module_protocol_mismatch")
                return validateReply(reply, expectedDigest)
            } catch (problem: MonitoringUnavailable) { throw problem }
            catch (_: Exception) { throw MonitoringUnavailable("monitoring_module_result_unknown") }
            finally { expiry.cancel(false) }
        }
    }

    private fun validateIdentity(reply: JsonObject, digest: String) {
        if (reply.number("version") != HealthMonitoringProtocol.PRIVATE_VERSION.toLong() || reply.number("moduleVersionCode") != 1L)
            throw MonitoringUnavailable("monitoring_module_protocol_mismatch")
        if (reply.text("packageName") != source.packageName || reply.number("versionCode") != source.versionCode || reply.text("apkSha256") != digest)
            throw MonitoringUnavailable("monitoring_module_identity_mismatch")
    }
    private fun validate(reply: JsonObject, digest: String, id: String?): JsonObject {
        validateIdentity(reply, digest)
        val allowedOutcomes = if (id == null) setOf("READ", "UNAVAILABLE", "FAILED") else
            setOf("UNCHANGED", "STORED_AND_NOTIFIED", "STORED_UNVERIFIED", "INVALID_ARGUMENT", "UNAVAILABLE", "FAILED")
        if (reply.text("outcome") !in allowedOutcomes)
            throw MonitoringUnavailable("monitoring_module_protocol_mismatch")
        val settings = reply["settings"] as? JsonArray ?: throw MonitoringUnavailable("monitoring_module_protocol_mismatch")
        val ids = HealthMonitoringProtocol.SETTINGS.filter { it.inProcess }.map { it.id }.toSet()
        val actual = settings.map { entry ->
            val row = entry as? JsonObject ?: throw MonitoringUnavailable("monitoring_module_protocol_mismatch")
            if (row["value"] != JsonNull && row["value"] != null && (row["value"] as? JsonPrimitive)?.isString != true)
                throw MonitoringUnavailable("monitoring_module_protocol_mismatch")
            if ((row.text("value")?.length ?: 0) > 256) throw MonitoringUnavailable("monitoring_module_protocol_mismatch")
            if (row.flag("available") == null) throw MonitoringUnavailable("monitoring_module_protocol_mismatch")
            row.text("id") ?: throw MonitoringUnavailable("monitoring_module_protocol_mismatch")
        }
        if (actual.size != ids.size || actual.toSet() != ids) throw MonitoringUnavailable("monitoring_module_protocol_mismatch")
        return reply
    }

    companion object {
        private val DEADLINES = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "monitoring-rpc-deadline").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true }
        @Suppress("DEPRECATION")
        fun create(context: Context, source: AgentMonitoring.Source): AgentPrivateMonitoring {
            val uid = context.packageManager.getApplicationInfo(HealthMonitoringProtocol.PACKAGE, 0).uid
            return AgentPrivateMonitoring(source, uid) {
                val socket = LocalSocket()
                val expiry = DEADLINES.schedule({ runCatching(socket::close) }, 30, TimeUnit.SECONDS)
                try {
                    socket.connectToHealthModule(20_000)
                    object : PrivateMonitoringChannel {
                        override val uid get() = socket.peerCredentials.uid
                        override val input get() = socket.inputStream
                        override val output get() = socket.outputStream
                        override fun close() = socket.close()
                    }
                } catch (error: Throwable) { socket.close(); throw error }
                finally { expiry.cancel(false) }
            }
        }
    }
}
