package dev.ticbox.companion.toolboxagent

import dev.ticbox.companion.protocol.ToolboxProtocol
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*
import kotlinx.serialization.json.*

/** Final install replies must survive the other direction still carrying a rejected upload. */
class InstallReplyDrainTest {
    private class Commands(private val failure: String, private val uncertain: Boolean = false) : PackageCommands {
        val actions = CopyOnWriteArrayList<String>()
        val abandoned = CountDownLatch(1)
        override fun run(command: List<String>, input: ((java.io.OutputStream) -> Unit)?, timeoutMillis: Long): CapturedCommand {
            val action = command[2]; actions += action
            if (action == "install-abandon") abandoned.countDown()
            if (action == "install-write") ByteArrayOutputStream().use { input?.invoke(it) }
            if (action == failure) return if (uncertain) CapturedCommand(124, "", completed = false)
                else CapturedCommand(1, "Failure [test rejection]")
            return CapturedCommand(0, if (action == "install-create") "Success: created install session [21]" else "Success")
        }
    }
    private class Peer(val commands: Commands, replyTimeoutMillis: Long = 5_000) : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val client = Socket(listener.inetAddress, listener.localPort).apply { tcpNoDelay = true; soTimeout = 3000 }
        private val server = listener.accept().apply { tcpNoDelay = true }
        val finished = CountDownLatch(1)
        private val worker = thread(name = "install-reply-peer", isDaemon = true) {
            try {
                server.use {
                    ToolboxAgentRuntime(AgentWire(it.getInputStream(), it.getOutputStream()),
                        AgentEnvironment(2000, 33, "", "/bin/sh", commands, deviceId = "install-reply-watch"), replyTimeoutMillis).run()
                }
            } finally { finished.countDown() }
        }
        init { assertEquals(ToolboxProtocol.READY, ToolboxProtocol.required(client.getInputStream()).type) }
        fun begin() = ToolboxProtocol.write(client.getOutputStream(), ToolboxProtocol.REQUEST,
            ToolboxProtocol.request("install", buildJsonObject {
                putJsonArray("parts") { repeat(2) { add(buildJsonObject { put("name", "part$it.apk"); put("size", 4) }) } }
            }))
        fun part() {
            ToolboxProtocol.write(client.getOutputStream(), ToolboxProtocol.DATA, byteArrayOf(1, 2, 3, 4))
            ToolboxProtocol.write(client.getOutputStream(), ToolboxProtocol.END)
        }
        fun result(): ToolboxProtocol.Packet {
            while (true) {
                val packet = ToolboxProtocol.required(client.getInputStream())
                if (packet.type != ToolboxProtocol.PROGRESS) return packet
            }
        }
        override fun close() {
            client.close(); server.close(); listener.close(); worker.join(3000)
            assertFalse(worker.isAlive, "Agent worker must finish after its owned socket closes")
        }
    }

    @Test fun earlyRejectionKeepsReplyChannelOpenWhileRemainingSplitsDrain() {
        for (failure in listOf("install-create", "install-write", "install-commit")) {
            val commands = Commands(failure)
            Peer(commands).use { peer ->
                peer.begin()
                val sent = when (failure) {
                    "install-create" -> 0
                    "install-write" -> 1.also { peer.part() }
                    else -> 2.also { peer.part(); peer.part() }
                }
                val reply = peer.result()
                assertEquals(ToolboxProtocol.RESULT, reply.type)
                assertEquals(false, reply.objectValue()["success"]?.jsonPrimitive?.boolean)
                assertFalse(peer.finished.await(150, TimeUnit.MILLISECONDS),
                    "$failure must not close with a concurrent upload still in flight")
                if (failure != "install-create") assertTrue(commands.abandoned.await(3, TimeUnit.SECONDS),
                    "Rejected package session must be abandoned before waiting for the peer")
                // Bytes already sent by the old streaming client may follow a negative result.
                repeat(2 - sent) { peer.part() }
                peer.client.shutdownOutput()
                assertTrue(peer.finished.await(3, TimeUnit.SECONDS))
                assertEquals(-1, peer.client.getInputStream().read(), "Final reply must end with EOF, not TCP reset")
                assertEquals(if (failure == "install-create") 0 else 1, commands.actions.count { it == "install-abandon" })
                assertEquals(if (failure == "install-commit") 1 else 0, commands.actions.count { it == "install-commit" })
            }
        }
    }

    @Test fun unknownCommitReplyIsPreservedWithoutAbandonOrRetry() {
        val commands = Commands("install-commit", uncertain = true)
        Peer(commands).use { peer ->
            peer.begin(); peer.part(); peer.part()
            val reply = peer.result()
            assertEquals(ToolboxProtocol.ERROR, reply.type)
            assertEquals("install_result_unknown", reply.objectValue()["code"]?.jsonPrimitive?.content)
            assertFalse(peer.finished.await(150, TimeUnit.MILLISECONDS))
            peer.client.shutdownOutput()
            assertTrue(peer.finished.await(3, TimeUnit.SECONDS))
            assertEquals(-1, peer.client.getInputStream().read())
            assertEquals(1, commands.actions.count { it == "install-commit" })
            assertEquals(0, commands.actions.count { it == "install-abandon" })
        }
    }

    @Test fun silentPeerCannotKeepCompletedInstallerAliveIndefinitely() {
        val commands = Commands("install-create")
        Peer(commands, replyTimeoutMillis = 100).use { peer ->
            peer.begin()
            assertEquals(ToolboxProtocol.RESULT, peer.result().type)
            assertTrue(peer.finished.await(3, TimeUnit.SECONDS))
            assertEquals(-1, peer.client.getInputStream().read())
            assertEquals(listOf("install-create"), commands.actions.toList())
        }
    }

    @Test fun receiptDrainRejectsBytesBeyondTheDeclaredUploadBudget() {
        val commands = Commands("install-create")
        Peer(commands).use { peer ->
            peer.begin()
            assertEquals(ToolboxProtocol.RESULT, peer.result().type)
            ToolboxProtocol.write(peer.client.getOutputStream(), ToolboxProtocol.DATA, ByteArray(9))
            assertTrue(peer.finished.await(3, TimeUnit.SECONDS))
            assertEquals(listOf("install-create"), commands.actions.toList())
        }
    }

}
