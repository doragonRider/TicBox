package dev.ticbox.companion.healthbridge

import dev.ticbox.companion.protocol.ToolboxProtocol
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ToolboxProcessRelayTest {
    @Test fun cooperativeProcessExitsOnEofWithoutEscalation() {
        val process = ProcessBuilder("/bin/cat").start()
        try {
            closeToolboxInput(process)
            assertTrue(process.waitFor(2, TimeUnit.SECONDS))
        } finally { process.destroyForcibly() }
    }
    @Test fun disconnectedTransportDoesNotEscalateAgainstAnUnresponsiveProcess() {
        val process = ProcessBuilder("/bin/sh", "-c", "exec sleep 20").start()
        try {
            closeToolboxInput(process)
            assertFalse(process.waitFor(1800, TimeUnit.MILLISECONDS), "EOF must never start a kill countdown")
        } finally { process.destroyForcibly() }
    }
    @Test fun outputIsDrainedAfterPeerLossSoCommitAndCleanupCanFinish() {
        val directory = Files.createTempDirectory("toolbox-relay-cleanup-")
        val marker = directory.resolve("finished")
        val process = ProcessBuilder("/bin/sh", "-c", "printf start; sleep 2; printf finished > '${marker}'; head -c 2000000 /dev/zero").start()
        var disconnected = 0
        try {
            closeToolboxInput(process)
            relayToolboxOutput(process.inputStream, object : OutputStream() {
                override fun write(value: Int) { throw IOException("peer lost") }
                override fun write(bytes: ByteArray, offset: Int, length: Int) { throw IOException("peer lost") }
            }) { disconnected++ }
            assertEquals(1, disconnected)
            assertTrue(process.waitFor(2, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            assertEquals("finished", Files.readString(marker))
        } finally { process.destroyForcibly(); directory.toFile().deleteRecursively() }
    }
    @Test fun smallProbeAndBinaryFramesReachARealChildBeforeEitherStreamCloses() {
        // Like su/app_process, a real Process exposes buffered stdin. A direct socket fixture
        // bypasses that buffer and cannot reproduce the first probe waiting forever.
        val process = ProcessBuilder("/bin/cat").start()
        val workers = Executors.newFixedThreadPool(2)
        try {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
                Socket(listener.inetAddress, listener.localPort).use { phone ->
                    listener.accept().use { watch ->
                        phone.soTimeout = 3000
                        phone.tcpNoDelay = true
                        watch.tcpNoDelay = true
                        workers.submit {
                            runCatching { relayToolboxStream(watch.getInputStream(), process.outputStream) }
                            runCatching { process.outputStream.close() }
                        }
                        workers.submit {
                            runCatching { relayToolboxStream(process.inputStream, watch.getOutputStream()) }
                        }
                        val probe = ToolboxProtocol.request("probe").toString().toByteArray(Charsets.UTF_8)
                        ToolboxProtocol.write(phone.getOutputStream(), ToolboxProtocol.REQUEST, probe)
                        val echoedProbe = ToolboxProtocol.required(phone.getInputStream())
                        assertEquals(ToolboxProtocol.REQUEST, echoedProbe.type)
                        assertContentEquals(probe, echoedProbe.bytes)

                        val binary = ByteArray(150_001) { (it * 37).toByte() }
                        ToolboxProtocol.write(phone.getOutputStream(), ToolboxProtocol.DATA, binary)
                        val echoedBinary = ToolboxProtocol.required(phone.getInputStream())
                        assertEquals(ToolboxProtocol.DATA, echoedBinary.type)
                        assertContentEquals(binary, echoedBinary.bytes)
                        assertTrue(process.isAlive, "Frames must arrive while the child still waits for stdin")
                    }
                }
            }
        } finally {
            process.destroyForcibly()
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        }
    }
}
