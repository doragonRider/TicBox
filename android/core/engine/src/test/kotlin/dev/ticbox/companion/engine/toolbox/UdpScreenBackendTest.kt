package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class UdpScreenBackendTest {
    @Test fun screenDiagnosticsRequireExplicitOptInForBothBackendsAndMediaTransports() = runBlocking {
        for ((kind, udp) in listOf(ToolboxBackendKind.ROOT_BRIDGE to false, ToolboxBackendKind.ADB to false, ToolboxBackendKind.ADB to true)) {
            for (enabled in listOf(false, true)) {
                val connector = Connector(if (kind == ToolboxBackendKind.ADB) 2000 else 0, true, fec = true, keyframe = true)
                val recorded = CopyOnWriteArrayList<ProtocolDiagnostic>()
                AgentToolboxBackend(kind, this, connector, diagnostics = DiagnosticSink(recorded::add)).use { backend ->
                    backend.connect()
                    backend.openScreen(ToolboxScreenOptions(udp = udp, collectDiagnostics = enabled)).use { }
                    val request = connector.requests.flatMap { bytes ->
                        val input = ByteArrayInputStream(bytes.toByteArray())
                        generateSequence { ToolboxProtocol.read(input) }.map { it.objectValue() }.toList()
                    }.single { it.text("op") == "screen" }
                    val args = request["args"] as JsonObject
                    if (udp) { assertEquals(enabled, args.flag("udpDiagnostics")); assertEquals(true, args.flag("udpStartupGate")) }
                    assertEquals(enabled, recorded.any { it.component == "toolbox_screen" && it.event == "stream_open" })
                    if (!enabled) assertTrue(recorded.none { it.component == "toolbox_screen" })
                }
            }
        }
    }

    @Test fun `Bluetooth rejects UDP before opening another operation even if stale preference requests it`() = runBlocking {
        val connector = Connector(uid = 0, udp = true)
        AgentToolboxBackend(ToolboxBackendKind.ROOT_BRIDGE, this, connector).use { backend ->
            backend.connect()
            assertEquals("screen_udp_unavailable", assertFailsWith<ToolboxException> { backend.openScreen(ToolboxScreenOptions(udp = true)) }.code)
            assertEquals(1, connector.opened)
            assertEquals(listOf("probe"), connector.operations())
        }
    }
    @Test fun `older ADB agent cannot ignore UDP option and quietly start reliable media`() = runBlocking {
        val connector = Connector(uid = 2000, udp = false)
        AgentToolboxBackend(ToolboxBackendKind.ADB, this, connector).use { backend ->
            backend.connect()
            assertEquals("screen_udp_unavailable", assertFailsWith<ToolboxException> { backend.openScreen(ToolboxScreenOptions(udp = true)) }.code)
            assertEquals(listOf("probe"), connector.operations())
        }
    }
    @Test fun `UDP requires a direct endpoint and a video session`() = runBlocking {
        val connector = Connector(uid = 2000, udp = true, host = null)
        AgentToolboxBackend(ToolboxBackendKind.ADB, this, connector).use { backend ->
            backend.connect()
            for (video in listOf(true, false)) assertEquals("screen_udp_unavailable", assertFailsWith<ToolboxException> {
                backend.openScreen(ToolboxScreenOptions(udp = true, video = video))
            }.code)
            assertEquals(listOf("probe"), connector.operations())
        }
    }
    @Test fun unencryptedAdbRefusesToExposeUdpSessionSecrets() = runBlocking {
        val connector = Connector(uid = 2000, udp = true, encrypted = false)
        AgentToolboxBackend(ToolboxBackendKind.ADB, this, connector).use { backend ->
            backend.connect()
            assertEquals("screen_udp_tls_required", assertFailsWith<ToolboxException> { backend.openScreen(ToolboxScreenOptions(udp = true)) }.code)
            assertEquals(listOf("probe"), connector.operations())
        }
    }
    @Test fun newUdpRequiresBothFecAndLightweightKeyFrameCapabilitiesBeforeRequest() = runBlocking {
        for ((fec, keyframe) in listOf(false to true, true to false, false to false)) {
            val connector = Connector(uid = 2000, udp = true, fec = fec, keyframe = keyframe)
            AgentToolboxBackend(ToolboxBackendKind.ADB, this, connector).use { backend ->
                backend.connect()
                assertEquals("screen_udp_unavailable", assertFailsWith<ToolboxException> {
                    backend.openScreen(ToolboxScreenOptions(udp = true))
                }.code)
                assertEquals(listOf("probe"), connector.operations())
            }
        }
    }
    @Test fun disablingMediaEncryptionStillRequiresTlsAndExplicitAgentSupport() = runBlocking {
        for (tls in listOf(false, true)) {
            val connector = Connector(uid = 2000, udp = true, encrypted = tls, fec = true, keyframe = true)
            AgentToolboxBackend(ToolboxBackendKind.ADB, this, connector).use { backend ->
                backend.connect()
                assertEquals(if (tls) "screen_udp_encryption_mode_unavailable" else "screen_udp_tls_required",
                    assertFailsWith<ToolboxException> { backend.openScreen(ToolboxScreenOptions(udp = true, udpEncryption = false)) }.code)
                assertEquals(listOf("probe"), connector.operations())
            }
        }
    }
    @Test fun udpCannotSkipTheNegotiatedFirstReferenceHandshake() = runBlocking {
        val connector = Connector(2000, true, fec = true, keyframe = true, bootstrap = false)
        AgentToolboxBackend(ToolboxBackendKind.ADB, this, connector).use { backend ->
            backend.connect()
            assertEquals("screen_udp_unavailable", assertFailsWith<ToolboxException> { backend.openScreen(ToolboxScreenOptions(udp = true)) }.code)
            assertEquals(listOf("probe"), connector.operations())
        }
    }
    private class Connector(private val uid: Int, private val udp: Boolean, private val host: String? = "127.0.0.1", private val encrypted: Boolean = true, private val fec: Boolean = false, private val keyframe: Boolean = false, private val bootstrap: Boolean = true) : ToolboxAgentConnector {
        override val device = "fixture"
        override val screenUdpHost get() = host
        var opened = 0
        val requests = mutableListOf<ByteArrayOutputStream>()
        override suspend fun prepare() = Unit
        override suspend fun open(): ToolboxChannel {
            opened++
            val response = ByteArrayOutputStream().also { out ->
                ToolboxProtocol.write(out, ToolboxProtocol.READY, buildJsonObject {
                    put("version", ToolboxProtocol.VERSION); put("uid", uid); put("sdk", 33); put("deviceId", "same-device")
                    putJsonArray("features") { add("SCREEN"); if (udp) add("SCREEN_UDP"); if (fec) add("SCREEN_UDP_FEC"); if (keyframe) add("SCREEN_KEYFRAME"); if (bootstrap) add("SCREEN_UDP_STARTUP") }
                })
                ToolboxProtocol.write(out, ToolboxProtocol.RESULT, buildJsonObject { put("success", true) })
            }
            val output = ByteArrayOutputStream().also(requests::add)
            return object : ToolboxChannel {
                override val secureScreenUdp = encrypted
                override val input = ByteArrayInputStream(response.toByteArray())
                override val output = output
                override fun close() = Unit
            }
        }
        fun operations() = requests.flatMap { bytes ->
            val input = ByteArrayInputStream(bytes.toByteArray())
            generateSequence { ToolboxProtocol.read(input) }.map { it.objectValue().text("op") }.toList()
        }
        override fun close() = Unit
    }
}
