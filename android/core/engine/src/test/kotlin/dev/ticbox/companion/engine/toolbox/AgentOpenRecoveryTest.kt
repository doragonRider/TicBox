package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import java.io.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class AgentOpenRecoveryTest {
    private enum class Failure { OPEN, READY, REQUEST, REPLY, REMOTE, CANCEL }

    private class Connector(private val reply: OutputStream.() -> Unit = {
        ToolboxProtocol.write(this, ToolboxProtocol.RESULT, buildJsonObject { put("success", true) })
    }) : ToolboxAgentConnector {
        override val device = "test endpoint"
        var failure: Failure? = null
        var failuresRemaining = 1
        var recoveryAllowed = true
        var recoveryCalls = 0
        var retryIdentity = "same-device"
        var opened = 0
        var closedChannels = 0
        val requests = mutableListOf<String>()
        override suspend fun prepare() = Unit
        override suspend fun open(): ToolboxChannel {
            opened++
            val probe = opened == 1
            val fault = if (!probe && failuresRemaining-- > 0) failure else null
            if (fault == Failure.OPEN) throw EOFException("lost OPEN reply")
            if (fault == Failure.CANCEL) throw CancellationException("cancelled OPEN")
            val incoming = ByteArrayOutputStream().apply {
                if (fault == Failure.REMOTE) {
                    ToolboxProtocol.write(this, ToolboxProtocol.ERROR, buildJsonObject { put("code", "permission_denied") })
                } else {
                    ToolboxProtocol.write(this, ToolboxProtocol.READY, buildJsonObject {
                        put("version", ToolboxProtocol.VERSION); put("deviceId", if (recoveryCalls > 0) retryIdentity else "same-device")
                        put("uid", 2000); put("sdk", 35)
                    })
                    if (probe) ToolboxProtocol.write(this, ToolboxProtocol.RESULT, buildJsonObject { put("success", true) })
                    else if (fault != Failure.REPLY) reply()
                }
            }.toByteArray()
            return object : ToolboxChannel {
                override val input: InputStream = if (fault == Failure.READY) object : InputStream() {
                    override fun read(): Int = throw EOFException("lost agent READY")
                } else incoming.inputStream()
                override val output = object : OutputStream() {
                    override fun write(byte: Int) = error("Expected framed write")
                    override fun write(bytes: ByteArray, off: Int, len: Int) {
                        val packet = ToolboxProtocol.required(ByteArrayInputStream(bytes, off, len))
                        if (packet.type == ToolboxProtocol.REQUEST) {
                            requests += checkNotNull(packet.objectValue().text("op"))
                            if (fault == Failure.REQUEST) throw IOException("reply lost after request reached peer")
                        }
                    }
                }
                override fun close() { closedChannels++ }
            }
        }
        override fun recoverBeforeRequest(error: IOException): Boolean {
            recoveryCalls++
            return recoveryAllowed
        }
        override fun close() = Unit
    }

    @Test fun filesAppsClipboardAndScreenRecoverBeforeSendingTheirFirstRequest() = runBlocking {
        for (operation in listOf("files", "apps", "clipboard_get", "screen")) {
            for (fault in listOf(Failure.OPEN, Failure.READY)) {
                val connector = Connector {
                    if (operation == "clipboard_get") ToolboxProtocol.write(this, ToolboxProtocol.RESULT, buildJsonObject { put("text", "clipboard") })
                    else ToolboxProtocol.write(this, ToolboxProtocol.END)
                }
                val observations = mutableListOf<ProtocolDiagnostic>()
                val backend = AgentToolboxBackend(ToolboxBackendKind.ADB, this, connector, diagnostics = DiagnosticSink { observations += it })
                try {
                    backend.connect()
                    val target = backend.state.value.target
                    connector.failure = fault
                    when (operation) {
                        "files" -> assertTrue(backend.files("/private-directory").isEmpty())
                        "apps" -> assertTrue(backend.apps().isEmpty())
                        "clipboard_get" -> assertEquals("clipboard", backend.clipboard())
                        "screen" -> backend.openScreen(ToolboxScreenOptions(video = false)).close()
                    }
                    assertEquals(listOf("probe", operation), connector.requests)
                    assertEquals(3, connector.opened)
                    assertEquals(1, connector.recoveryCalls)
                    assertEquals(target, backend.state.value.target)
                    assertEquals(1, observations.count { it.event == "agent_open_retry" })
                    assertTrue(observations.none { it.details.values.any { value -> value.contains("private-directory") || value.contains("clipboard") } })
                } finally { backend.close() }
            }
        }
    }

    @Test fun aPotentiallyExecutedRequestIsNeverReplayedIncludingReadOperations() = runBlocking {
        for (operation in listOf("file", "files", "clipboard_get")) {
            for (fault in listOf(Failure.REQUEST, Failure.REPLY)) {
                val connector = Connector()
                val backend = AgentToolboxBackend(ToolboxBackendKind.ADB, this, connector)
                try {
                    backend.connect(); connector.failure = fault
                    assertFailsWith<IOException> {
                        when (operation) {
                            "file" -> backend.file(ToolboxFileRequest(ToolboxFileAction.DELETE, "/sensitive"))
                            "files" -> backend.files("/sensitive")
                            "clipboard_get" -> backend.clipboard()
                        }
                    }
                    assertEquals(listOf("probe", operation), connector.requests)
                    assertEquals(2, connector.opened)
                    assertEquals(0, connector.recoveryCalls)
                } finally { backend.close() }
            }
        }
    }

    @Test fun aRetryStillChecksHardwareIdentityBeforeAnyBusinessRequest() = runBlocking {
        val connector = Connector()
        val backend = AgentToolboxBackend(ToolboxBackendKind.ADB, this, connector)
        try {
            backend.connect(); connector.failure = Failure.READY; connector.retryIdentity = "different-device"
            assertEquals("device_changed", assertFailsWith<ToolboxException> { backend.setClipboard("private text") }.code)
            assertEquals(listOf("probe"), connector.requests)
            assertEquals(ToolboxPhase.FAILED, backend.state.value.phase)
        } finally { backend.close() }
    }

    @Test fun recoveryHasOneAttemptAndDoesNotLoopOnAnUnavailablePeer() = runBlocking {
        val connector = Connector()
        val backend = AgentToolboxBackend(ToolboxBackendKind.ADB, this, connector)
        try {
            backend.connect(); connector.failure = Failure.READY; connector.failuresRemaining = 2
            assertFailsWith<EOFException> { backend.clipboard() }
            assertEquals(3, connector.opened)
            assertEquals(1, connector.recoveryCalls)
            assertEquals(listOf("probe"), connector.requests)
            assertEquals(3, connector.closedChannels)
        } finally { backend.close() }
    }

    @Test fun liveTransportRefusalRemoteErrorsAndCancellationAreNotRetried() = runBlocking {
        for (fault in listOf(Failure.OPEN, Failure.REMOTE, Failure.CANCEL)) {
            val connector = Connector().apply { recoveryAllowed = false }
            val backend = AgentToolboxBackend(ToolboxBackendKind.ADB, this, connector)
            try {
                backend.connect(); connector.failure = fault
                when (fault) {
                    Failure.CANCEL -> assertFailsWith<CancellationException> { backend.clipboard() }
                    Failure.REMOTE -> assertEquals("permission_denied", assertFailsWith<ToolboxException> { backend.clipboard() }.code)
                    else -> assertFailsWith<EOFException> { backend.clipboard() }
                }
                assertEquals(2, connector.opened)
                assertEquals(if (fault == Failure.OPEN) 1 else 0, connector.recoveryCalls)
                assertEquals(listOf("probe"), connector.requests)
            } finally { backend.close() }
        }
    }
}
