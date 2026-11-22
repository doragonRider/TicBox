package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*

class AgentTerminalSessionTest {
    private class Remote(val socket: Socket, val closed: AtomicBoolean) : AutoCloseable {
        fun send(type: Int, bytes: ByteArray) = ToolboxProtocol.write(socket.getOutputStream(), type, bytes)
        fun reply(type: Int, body: JsonObject) = ToolboxProtocol.write(socket.getOutputStream(), type, body)
        suspend fun read() = toolboxIo(onCancel = ::close) { ToolboxProtocol.required(socket.getInputStream()) }
        suspend fun awaitClosed() = withTimeout(2_000) { while (!closed.get()) delay(1) }
        fun started() = reply(ToolboxProtocol.RESULT, buildJsonObject { put("started", true) })
        override fun close() = socket.close()
    }
    private class Fixture(scope: CoroutineScope, startupTimeoutMillis: Long = 30_000) : AutoCloseable {
        private val listener = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val calls = Channel<Remote>(Channel.UNLIMITED)
        var identity = "watch"
        var supportsTerminal = true
        var connecting = true
        val opened = CopyOnWriteArrayList<Remote>()
        val backend = AgentToolboxBackend(ToolboxBackendKind.ROOT_BRIDGE, scope, object : ToolboxAgentConnector {
            override val device = "endpoint"
            override suspend fun prepare() { connecting = true }
            override suspend fun open(): ToolboxChannel = toolboxIo(onDiscard = ToolboxChannel::close) {
                val client = Socket(listener.inetAddress, listener.localPort)
                val remote = listener.accept()
                client.tcpNoDelay = true; remote.tcpNoDelay = true
                sockets += client; sockets += remote
                val closed = AtomicBoolean()
                val call = Remote(remote, closed)
                opened += call
                call.reply(ToolboxProtocol.READY, buildJsonObject {
                    put("version", ToolboxProtocol.VERSION); put("deviceId", identity); put("uid", 0); put("sdk", 33)
                    putJsonArray("features") { if (supportsTerminal) add("TERMINAL") }
                })
                if (connecting) {
                    connecting = false
                    call.reply(ToolboxProtocol.RESULT, buildJsonObject { put("success", true) })
                } else calls.trySend(call).getOrThrow()
                object : ToolboxChannel {
                    override val input = client.getInputStream()
                    override val output = client.getOutputStream()
                    override fun close() { closed.set(true); client.close() }
                }
            }
            override fun close() { sockets.forEach { runCatching(it::close) } }
        }, terminalStartTimeoutMillis = startupTimeoutMillis)
        suspend fun request(): Remote = calls.receive().also { assertEquals("terminal", it.read().objectValue().text("op")) }
        override fun close() { backend.close(); sockets.forEach { runCatching(it::close) }; listener.close(); calls.cancel() }
    }
    @Test fun successfulStartNeedsAnAcknowledgementButNoPromptOrOutput(): Unit = runBlocking { withTimeout(5_000) {
        Fixture(this).use { f ->
            f.backend.connect()
            val opening = async { f.backend.bind().openTerminal() }
            val remote = f.request()
            assertFalse(opening.isCompleted)
            remote.started()
            opening.await().use { terminal ->
                assertEquals(f.backend.state.value.target, terminal.target)
                val writing = async { terminal.write(byteArrayOf(3, 4, 27, 91, 65, 13)) }
                val input = remote.read()
                assertEquals(ToolboxProtocol.STDIN, input.type)
                assertContentEquals(byteArrayOf(3, 4, 27, 91, 65, 13), input.bytes)
                assertFalse(writing.isCompleted)
                remote.send(ToolboxProtocol.TERMINAL_ACK, byteArrayOf()); writing.await()
                val resizing = async { terminal.resize(ToolboxTerminalSize(41, 97, 970, 820)) }
                val size = remote.read()
                assertEquals(ToolboxProtocol.TERMINAL_RESIZE, size.type)
                assertEquals(ToolboxTerminalSize(41, 97, 970, 820).wireSize(), size.objectValue())
                assertFalse(resizing.isCompleted)
                remote.send(ToolboxProtocol.TERMINAL_ACK, byteArrayOf()); resizing.await()
                remote.reply(ToolboxProtocol.EXIT, buildJsonObject { put("code", 17) })
                assertEquals(listOf(ToolboxTerminalEvent.Exited(17)), terminal.events.toList())
                assertEquals(ToolboxTerminalEvent.Exited(17), terminal.awaitExit())
            }
            remote.awaitClosed()
        }
    } }
    @Test fun terminalOutputPreservesControlBytesAndFragmentedUnicodeAndOnlyOneCollector(): Unit = runBlocking { withTimeout(5_000) {
        Fixture(this).use { f ->
            f.backend.connect(); val opening = async { f.backend.openTerminal() }; val remote = f.request(); remote.started()
            opening.await().use { terminal ->
                val bytes = "\u001b[31m手表\r\n\b\u0000\u001b[0m".toByteArray()
                bytes.forEach { remote.send(ToolboxProtocol.STDOUT, byteArrayOf(it)) }
                remote.reply(ToolboxProtocol.EXIT, buildJsonObject { put("code", 0) })
                val events = terminal.events.toList()
                assertContentEquals(bytes, events.filterIsInstance<ToolboxTerminalEvent.Output>().flatMap { it.bytes.toList() }.toByteArray())
                assertEquals(ToolboxTerminalEvent.Exited(0), events.last())
                assertFailsWith<IllegalStateException> { terminal.events.toList() }
            }
        }
    } }
    @Test fun exitDrainCutoffIsReportedWithoutChangingActualExitCode(): Unit = runBlocking { withTimeout(5_000) {
        for (truncated in listOf(null, false, true)) Fixture(this).use { f ->
            f.backend.connect(); val opening = async { f.backend.openTerminal() }; val remote = f.request(); remote.started()
            opening.await().use { terminal ->
                val tail = "last admitted output\r\n".toByteArray()
                remote.send(ToolboxProtocol.STDOUT, tail)
                remote.reply(ToolboxProtocol.EXIT, buildJsonObject {
                    put("code", 23); truncated?.let { put("outputTruncated", it) }
                })
                val events = terminal.events.toList()
                assertContentEquals(tail, assertIs<ToolboxTerminalEvent.Output>(events.first()).bytes)
                val expected = ToolboxTerminalEvent.Exited(23, outputTruncated = truncated == true)
                assertEquals(expected, events.last())
                assertEquals(expected, terminal.awaitExit())
            }
        }
    } }
    @Test fun malformedDrainCutoffFlagCannotMasqueradeAsCompleteOutput(): Unit = runBlocking { withTimeout(5_000) {
        for (invalid in listOf(JsonPrimitive("true"), JsonPrimitive(1), JsonNull)) Fixture(this).use { f ->
            f.backend.connect(); val opening = async { f.backend.openTerminal() }; val remote = f.request(); remote.started()
            opening.await().use { terminal ->
                remote.reply(ToolboxProtocol.EXIT, buildJsonObject { put("code", 0); put("outputTruncated", invalid) })
                assertEquals("terminal_protocol_mismatch", assertFailsWith<ToolboxException> { terminal.events.toList() }.code)
                assertEquals("terminal_protocol_mismatch", assertFailsWith<ToolboxException> { terminal.awaitExit() }.code)
            }
            remote.awaitClosed()
        }
    } }
    @Test fun cancelledStartupClosesItsChannelAndCannotReplayTheRequest(): Unit = runBlocking { withTimeout(5_000) {
        Fixture(this).use { f ->
            f.backend.connect(); val opening = async { f.backend.openTerminal() }; val remote = f.request()
            opening.cancelAndJoin()
            remote.awaitClosed(); assertEquals(2, f.opened.size)
        }
    } }
    @Test fun startupFailureIsReportedWithoutWaitingForShellOutput(): Unit = runBlocking { withTimeout(5_000) {
        Fixture(this).use { f ->
            f.backend.connect()
            val opening = async { runCatching { f.backend.openTerminal() } }
            val remote = f.request()
            remote.reply(ToolboxProtocol.ERROR, buildJsonObject { put("code", "terminal_start_failed") })
            assertEquals("terminal_start_failed", assertIs<ToolboxException>(opening.await().exceptionOrNull()).code)
            remote.awaitClosed(); assertEquals(2, f.opened.size)
        }
    } }
    @Test fun missingCapabilityAndWrongIdentityAreRejectedBeforeStartingAShell(): Unit = runBlocking { withTimeout(5_000) {
        Fixture(this).use { f ->
            f.backend.connect(); f.supportsTerminal = false
            assertEquals("terminal_unavailable", assertFailsWith<ToolboxException> { f.backend.openTerminal() }.code)
            assertEquals(-1, toolboxIo(onCancel = f.opened.last()::close) { f.opened.last().socket.getInputStream().read() }, "No operation request may have been sent")
            f.supportsTerminal = true; f.identity = "another-watch"
            assertEquals("device_changed", assertFailsWith<ToolboxException> { f.backend.openTerminal() }.code)
            assertEquals(-1, toolboxIo(onCancel = f.opened.last()::close) { f.opened.last().socket.getInputStream().read() })
        }
    } }
    @Test fun backendDisconnectClosesUncollectedOutputAndOldTerminalsCannotFollowReconnect(): Unit = runBlocking { withTimeout(5_000) {
        Fixture(this).use { f ->
            f.backend.connect(); val oldLease = f.backend.bind()
            val opening = async { oldLease.openTerminal() }; val remote = f.request(); remote.started()
            val terminal = opening.await()
            repeat(40) { remote.send(ToolboxProtocol.STDOUT, ByteArray(10)) }
            f.backend.disconnect()
            remote.awaitClosed()
            assertFailsWith<CancellationException> { terminal.awaitExit() }
            f.backend.connect()
            val opened = f.opened.size
            assertEquals("connection_replaced", assertFailsWith<ToolboxException> { terminal.write("danger\r".toByteArray()) }.code)
            assertEquals("connection_replaced", assertFailsWith<ToolboxException> { oldLease.openTerminal() }.code)
            assertEquals(opened, f.opened.size)
        }
    } }
    @Test fun cancellingOutputCollectionHangsUpTheSession(): Unit = runBlocking { withTimeout(5_000) {
        Fixture(this).use { f ->
            f.backend.connect(); val opening = async { f.backend.openTerminal() }; val remote = f.request(); remote.started()
            val terminal = opening.await(); val collecting = CompletableDeferred<Unit>()
            val reader = launch { collecting.complete(Unit); terminal.events.collect { } }
            collecting.await(); yield(); reader.cancelAndJoin()
            remote.awaitClosed()
            assertFailsWith<CancellationException> { terminal.awaitExit() }
        }
    } }
    @Test fun absentStartAcknowledgementTimesOutAndReleasesTheOperation(): Unit = runBlocking { withTimeout(5_000) {
        Fixture(this, startupTimeoutMillis = 300).use { f ->
            f.backend.connect()
            val opening = async { runCatching { f.backend.openTerminal() } }
            val remote = f.request()
            assertEquals("terminal_start_timeout", assertIs<ToolboxException>(opening.await().exceptionOrNull()).code)
            remote.awaitClosed(); assertEquals(2, f.opened.size)
        }
    } }
    @Test fun invalidTerminalOptionsNeverReachATransport() {
        for (bad in listOf("", "xterm\ncommand", "xterm;sh", "../xterm", "a".repeat(65))) {
            assertFailsWith<IllegalArgumentException> { ToolboxTerminalOptions(term = bad) }
        }
        assertFailsWith<IllegalArgumentException> { ToolboxTerminalSize(rows = 0) }
        assertFailsWith<IllegalArgumentException> { ToolboxTerminalSize(columns = 1001) }
        assertFailsWith<IllegalArgumentException> { ToolboxTerminalSize(widthPixels = -1) }
        assertFailsWith<IllegalArgumentException> { ToolboxTerminalSize(heightPixels = 65536) }
    }
}
