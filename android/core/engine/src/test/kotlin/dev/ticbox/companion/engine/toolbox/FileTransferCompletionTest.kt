package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import java.io.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class FileTransferCompletionTest {
    private class Connector(private val declared: Long = 3, private val finish: Boolean = true, private val resumable: Boolean = false) : ToolboxAgentConnector {
        override val device = "test-device"
        val requests = mutableListOf<String>()
        val closed = AtomicInteger()
        var count = 0
        override suspend fun prepare() = Unit
        override suspend fun open(): ToolboxChannel {
            val probe = ++count == 1
            val incoming = ByteArrayOutputStream().apply {
                ToolboxProtocol.write(this, ToolboxProtocol.READY, buildJsonObject {
                    put("version", ToolboxProtocol.VERSION); put("deviceId", "test-device"); put("uid", 2000); put("sdk", 34)
                    putJsonArray("features") { add("FILES"); if (resumable) add("FILE_RESUME") }
                })
                if (probe) ToolboxProtocol.write(this, ToolboxProtocol.RESULT, buildJsonObject { put("success", true) })
                else {
                    ToolboxProtocol.write(this, ToolboxProtocol.PROGRESS, buildJsonObject { put("total", declared); put("completed", 0) })
                    ToolboxProtocol.write(this, ToolboxProtocol.DATA, byteArrayOf(1, 2, 3))
                    if (resumable) ToolboxProtocol.write(this, ToolboxProtocol.RESULT, buildJsonObject { put("bytes", 3); put("sha256", HASH); put("suffixSha256", HASH) })
                    else if (finish) ToolboxProtocol.write(this, ToolboxProtocol.END)
                }
            }.toByteArray()
            return object : ToolboxChannel {
                override val input = incoming.inputStream()
                override val output = object : ByteArrayOutputStream() {
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        super.write(bytes, offset, length)
                        val packet = ToolboxProtocol.required(ByteArrayInputStream(bytes, offset, length))
                        if (packet.type == ToolboxProtocol.REQUEST) requests += packet.objectValue().text("op")!!
                        if (packet.type == ToolboxProtocol.END) throw IOException("final ACK lost")
                    }
                }
                override fun close() { closed.incrementAndGet(); if (!probe) throw IOException("remote stream already closed") }
            }
        }
        override fun close() = Unit
    }
    @Test fun cleanupFailureAfterValidatedEndDoesNotTurnDownloadIntoFailure() = runBlocking {
        val connector = Connector(); val backend = AgentToolboxBackend(ToolboxBackendKind.ADB, this, connector)
        try {
            backend.connect(); val output = ByteArrayOutputStream(); backend.pull("/file", output)
            assertContentEquals(byteArrayOf(1, 2, 3), output.toByteArray())
            withTimeout(2_000) { while (connector.closed.get() < 2) delay(10) }
        } finally { backend.close() }
    }
    @Test fun aFailedFinalAcknowledgementDoesNotUndoAVerifiedDownload() = runBlocking {
        val connector = Connector(resumable = true); val backend = AgentToolboxBackend(ToolboxBackendKind.ADB, this, connector)
        try {
            backend.connect(); val output = ByteArrayOutputStream()
            val snapshot = ToolboxFileSnapshot(backend.state.value.target!!, "/file", 3, HASH, 0)
            assertEquals(3L, backend.pullResumable(snapshot, output).bytes)
            assertContentEquals(byteArrayOf(1, 2, 3), output.toByteArray())
        } finally { backend.close() }
    }
    @Test fun missingEndAndIncorrectLengthStillFailDespiteCleanupFailure() = runBlocking {
        for (connector in listOf(Connector(finish = false), Connector(declared = 4))) {
            val backend = AgentToolboxBackend(ToolboxBackendKind.ADB, this, connector)
            try {
                backend.connect()
                val error = assertFailsWith<IOException> { backend.pull("/file", ByteArrayOutputStream()) }
                assertNotEquals("remote stream already closed", error.message)
            } finally { backend.close() }
        }
    }
    @Test fun olderAgentRefusesResumeBeforeBusinessRequestIsSent() = runBlocking {
        val connector = Connector(); val backend = AgentToolboxBackend(ToolboxBackendKind.ADB, this, connector)
        try {
            backend.connect()
            assertEquals("file_resume_unavailable", assertFailsWith<ToolboxException> { backend.snapshotFile("/file") }.code)
            assertEquals(listOf("probe"), connector.requests)
        } finally { backend.close() }
    }
    private companion object { const val HASH = "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81" }
}
