package dev.ticbox.companion.toolboxagent

import dev.ticbox.companion.protocol.*
import java.io.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*
import kotlinx.serialization.json.*

class FileReceiptAcknowledgementTest {
    @Test fun bothLegacyDownloadAndSnapshotWaitUntilTheirFinalReplyIsAcknowledged() {
        val directory = Files.createTempDirectory("file-receipt-")
        try {
            val path = Files.writeString(directory.resolve("file"), "abc")
            for (snapshot in listOf(false, true)) {
                val incoming = PipedInputStream(1024); val peer = PipedOutputStream(incoming)
                val written = CountDownLatch(1); val finished = CountDownLatch(1); val failure = AtomicReference<Throwable?>()
                val output = object : ByteArrayOutputStream() {
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        super.write(bytes, offset, length)
                        if (bytes[offset].toInt() == if (snapshot) ToolboxProtocol.RESULT else ToolboxProtocol.END) written.countDown()
                    }
                }
                val worker = thread(name = "file-receipt-test", isDaemon = true) {
                    try {
                        val wire = AgentWire(incoming, output)
                        if (snapshot) AgentFileTransfers(wire).snapshot(buildJsonObject { put("path", path.toString()) })
                        else AgentFiles(wire).get(path.toString(), 0)
                    } catch (error: Throwable) { failure.set(error) }
                    finally { finished.countDown() }
                }
                try {
                    assertTrue(written.await(3, TimeUnit.SECONDS))
                    assertFalse(finished.await(100, TimeUnit.MILLISECONDS), "Agent exit would close the root Bluetooth relay before receipt")
                    ToolboxProtocol.write(peer, ToolboxProtocol.END)
                    assertTrue(finished.await(3, TimeUnit.SECONDS)); assertNull(failure.get())
                } finally { peer.close(); incoming.close(); worker.join(3000) }
            }
        } finally { directory.toFile().deleteRecursively() }
    }
}
