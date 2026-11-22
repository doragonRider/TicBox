package dev.ticbox.companion.toolboxagent

import dev.ticbox.companion.protocol.*
import java.io.*
import kotlinx.serialization.json.*
import kotlin.test.*

class AgentPrivateMonitoringTest {
    private val source = AgentMonitoring.Source(HealthMonitoringProtocol.PACKAGE, "5.6", 50_603_234L)
    private fun reply(digest: String = HealthMonitoringProtocol.ANALYZED_APK_SHA256.getValue(source.versionCode),
        version: Int = 1, settings: Boolean = true, outcome: String = "READ") = buildJsonObject {
        put("version", version); put("moduleVersionCode", 1); put("packageName", source.packageName)
        put("versionCode", source.versionCode); put("apkSha256", digest); put("outcome", outcome)
        putJsonArray("settings") { if (settings) HealthMonitoringProtocol.SETTINGS.filter { it.inProcess }.forEach { spec ->
            add(buildJsonObject { put("id", spec.id); put("value", spec.default); put("available", true) })
        } }
    }
    private fun frame(bytes: ByteArray) = ByteArrayOutputStream().apply {
        DataOutputStream(this).also { it.writeInt(bytes.size); it.write(bytes) }
    }.toByteArray()
    private class Channel(override val uid: Int, bytes: ByteArray) : PrivateMonitoringChannel {
        override val input = bytes.inputStream()
        override val output = ByteArrayOutputStream()
        var closed = false
        override fun close() { closed = true }
    }
    private fun channel(reply: JsonObject = reply(), uid: Int = 10067) = Channel(uid, frame(reply.toString().toByteArray()))
    private fun client(channel: Channel) = AgentPrivateMonitoring(source, 10067) { channel }

    @Test fun fixedRequestAndCompletePinnedReplyAreAcceptedAndTheChannelIsClosed() {
        val channel = channel(reply(outcome = "STORED_AND_NOTIFIED"))
        val result = client(channel).exchange("SLEEP_EXPECTED_BEDTIME", "22:30")
        assertEquals("STORED_AND_NOTIFIED", result.text("outcome")); assertTrue(channel.closed)
        val stream = DataInputStream(channel.output.toByteArray().inputStream())
        val bytes = ByteArray(stream.readInt()).also(stream::readFully)
        assertEquals(buildJsonObject { put("version", 1); put("op", "settings"); put("id", "SLEEP_EXPECTED_BEDTIME"); put("value", "22:30") }, Json.parseToJsonElement(bytes.decodeToString()))
        assertEquals(-1, stream.read())
    }
    @Test fun aSocketOwnedByAnotherAppCannotReceiveTheRequestOrImpersonateTheOem() {
        val channel = channel(uid = 10100)
        assertEquals("monitoring_module_identity_mismatch", assertFailsWith<MonitoringUnavailable> { client(channel).exchange(null, null) }.reason)
        assertEquals(0, channel.output.size()); assertTrue(channel.closed)
    }
    @Test fun unknownApkAndProtocolIdentityAndMissingRowsFailClosed() {
        for (bad in listOf(reply(digest = "0".repeat(64)), reply(version = 2), reply(settings = false))) {
            val channel = channel(bad)
            assertFailsWith<MonitoringUnavailable> { client(channel).exchange(null, null) }
            assertTrue(channel.closed)
        }
    }
    @Test fun oversizedAndInvalidUtf8RepliesCannotBeAccepted() {
        val oversized = ByteArrayOutputStream().apply { DataOutputStream(this).writeInt(65_537) }.toByteArray()
        for (bytes in listOf(oversized, frame(byteArrayOf(0xc3.toByte(), 0x28)))) {
            val channel = Channel(10067, bytes)
            assertFailsWith<MonitoringUnavailable> { client(channel).exchange(null, null) }
            assertTrue(channel.closed)
        }
    }
    @Test fun unknownFirmwareAndInvalidRequestsDoNotEvenOpenTheSocket() {
        var opened = 0
        val unsupported = AgentPrivateMonitoring(source.copy(versionCode = 999), 10067) { opened++; channel() }
        assertEquals("monitoring_module_version_not_analyzed", assertFailsWith<MonitoringUnavailable> { unsupported.exchange(null, null) }.reason)
        val supported = AgentPrivateMonitoring(source, 10067) { opened++; channel() }
        assertFailsWith<IllegalArgumentException> { supported.exchange("PRESSURE", "true") }
        assertFailsWith<IllegalArgumentException> { supported.exchange("SLEEP_EXPECTED_BEDTIME", "25:00") }
        assertEquals(0, opened)
    }
    @Test fun connectionLossAfterASetterWasSentIsNeverReportedAsAnUnattemptedWrite() {
        val channel = Channel(10067, byteArrayOf())
        assertEquals("monitoring_module_result_unknown", assertFailsWith<MonitoringUnavailable> { client(channel).exchange("SLEEP_ALARM_SOUND", "false") }.reason)
        assertTrue(channel.output.size() > 0); assertTrue(channel.closed)
    }
    @Test fun aReadReceiptCannotCompleteASetter() {
        val channel = channel()
        assertEquals("monitoring_module_protocol_mismatch", assertFailsWith<MonitoringUnavailable> {
            client(channel).exchange("SLEEP_ALARM_SOUND", "false")
        }.reason)
        assertTrue(channel.closed)
    }
    private fun providerReply(value: JsonElement = JsonPrimitive("false"), digest: String = HealthMonitoringProtocol.ANALYZED_APK_SHA256.getValue(source.versionCode)) = buildJsonObject {
        put("version", 1); put("moduleVersionCode", 1); put("providerVersion", 1); put("packageName", source.packageName)
        put("versionCode", source.versionCode); put("apkSha256", digest); put("success", true); put("value", value)
    }
    @Test fun fixedProviderProxyCarriesTheSameOemIdentityAndRootOnlySocketVerification() {
        val channel = channel(providerReply())
        assertEquals("false", client(channel).read("auto_detect_pressure"))
        val input = DataInputStream(channel.output.toByteArray().inputStream())
        val request = Json.parseToJsonElement(ByteArray(input.readInt()).also(input::readFully).decodeToString()).jsonObject
        assertEquals(buildJsonObject { put("version", 1); put("op", "provider"); put("method", "get"); put("key", "auto_detect_pressure") }, request)
        assertTrue(channel.closed)
        val impostor = channel(providerReply(), uid = 10100)
        assertEquals("monitoring_module_identity_mismatch", assertFailsWith<MonitoringUnavailable> { client(impostor).read("auto_detect_pressure") }.reason)
        assertEquals(0, impostor.output.size())
    }
    @Test fun oldModulesAndMalformedProviderRepliesAreExplicitlyRejected() {
        assertEquals("monitoring_module_update_required", assertFailsWith<MonitoringUnavailable> { client(channel()).read("auto_detect_pressure") }.reason)
        assertEquals("monitoring_module_identity_mismatch", assertFailsWith<MonitoringUnavailable> {
            client(channel(providerReply(digest = "0".repeat(64)))).read("auto_detect_pressure")
        }.reason)
        assertEquals("monitoring_module_protocol_mismatch", assertFailsWith<MonitoringUnavailable> {
            client(channel(providerReply(value = JsonPrimitive(true)))).read("auto_detect_pressure")
        }.reason)
    }

}
