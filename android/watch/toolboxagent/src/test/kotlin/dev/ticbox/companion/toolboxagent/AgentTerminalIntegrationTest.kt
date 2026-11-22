package dev.ticbox.companion.toolboxagent

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.toolbox.*
import dev.ticbox.companion.protocol.ToolboxProtocol
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Actual host PTYs behind the production agent frames and backend, for both transport kinds. */
class AgentTerminalIntegrationTest {
    private suspend fun both(block: suspend CoroutineScope.(AgentBackendIntegrationTest.Fixture, Path) -> Unit) = coroutineScope {
        assertTrue(PtyProcess.available(), "The native PTY test library must be built and configured")
        for (kind in ToolboxBackendKind.entries) {
            val root = Files.createTempDirectory("terminal-${kind.name}-")
            try {
                AgentBackendIntegrationTest.Fixture(kind, this).use { fixture ->
                    fixture.backend.connect()
                    assertTrue(ToolboxCapability.TERMINAL in fixture.backend.state.value.capabilities!!.features)
                    block(fixture, root)
                }
            } finally { root.toFile().deleteRecursively() }
        }
    }

    private class Transcript(scope: CoroutineScope, session: ToolboxTerminalSession) {
        private val bytes = ByteArrayOutputStream()
        @Volatile var failure: Throwable? = null
        val reader = scope.launch {
            try {
                session.events.collect { event -> if (event is ToolboxTerminalEvent.Output) synchronized(bytes) { bytes.write(event.bytes) } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = error }
        }
        fun text(): String = synchronized(bytes) { bytes.toString(Charsets.UTF_8) }
        suspend fun await(expected: String, after: Int = 0) = withTimeout(5_000) {
            while (!text().drop(after).contains(expected)) {
                failure?.let { throw AssertionError("Terminal closed before expected output", it) }
                delay(10)
            }
        }
    }

    /** Delays real framed writes, so finite tail coverage includes transport backpressure. */
    private class SlowWireFixture(kind: ToolboxBackendKind, scope: CoroutineScope) : AutoCloseable {
        private val listener = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        private val executor = Executors.newCachedThreadPool()
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        val delayedWrites = AtomicInteger()
        val backend = AgentToolboxBackend(kind, scope, object : ToolboxAgentConnector {
            override val device = "slow-wire-watch"
            override suspend fun prepare() = Unit
            override suspend fun open(): ToolboxChannel = toolboxIo(onDiscard = ToolboxChannel::close) {
                val client = Socket(listener.inetAddress, listener.localPort)
                val server = listener.accept()
                client.tcpNoDelay = true; server.tcpNoDelay = true
                sockets += client; sockets += server
                executor.execute {
                    server.use {
                        val output = object : FilterOutputStream(it.getOutputStream()) {
                            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                                delayedWrites.incrementAndGet(); Thread.sleep(25)
                                out.write(bytes, offset, length)
                            }
                            override fun write(value: Int) {
                                delayedWrites.incrementAndGet(); Thread.sleep(25)
                                out.write(value)
                            }
                        }
                        ToolboxAgentRuntime(AgentWire(it.getInputStream(), output),
                            AgentEnvironment(if (kind == ToolboxBackendKind.ROOT_BRIDGE) 0 else 2000,
                                33, "", "/bin/sh", deviceId = "slow-wire-watch")).run()
                    }
                    sockets.remove(server)
                }
                object : ToolboxChannel {
                    override val input = client.getInputStream()
                    override val output = client.getOutputStream()
                    override fun close() { sockets.remove(client); client.close() }
                }
            }
            override fun close() { sockets.forEach { runCatching(it::close) }; sockets.clear() }
        })
        override fun close() {
            backend.close(); listener.close(); executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private suspend fun ToolboxTerminalSession.send(text: String) = write(text.toByteArray(Charsets.UTF_8))
    private suspend fun initialize(session: ToolboxTerminalSession, transcript: Transcript) {
        // A split printf format avoids mistaking the terminal's echoed input for executed output.
        session.send("PS1='PTY> '; stty -echo; printf '__%s__\\n' READY\n")
        transcript.await("__READY__")
    }

    @Test fun controllingTerminalKeepsShellStateRawOutputAndWindowSizeOnBothBackends(): Unit = runBlocking { withTimeout(30_000) { both { fixture, root ->
        val size = ToolboxTerminalSize(rows = 27, columns = 91)
        fixture.backend.openTerminal(ToolboxTerminalOptions(size = size, term = "xterm-256color")).use { session ->
            assertEquals(fixture.backend.state.value.target, session.target)
            val transcript = Transcript(this, session)
            initialize(session, transcript)
            session.send("test -t 0 && test -t 1 && test -t 2 && printf '__%s__\\n' ALL_TTYS; stty size; printf 'TERM=%s\\n' \"\$TERM\"\n")
            transcript.await("__ALL_TTYS__")
            transcript.await("27 91")
            transcript.await("TERM=xterm-256color")
            assertFalse(transcript.text().contains("won't have full job control"))
            assertFalse(transcript.text().contains("can't find tty"))

            session.send("cd ${ToolboxProtocol.quote(root.toString())}; export PTY_TEST_VALUE=kept\n")
            session.send("printf 'STATE=%s:%s\\n' \"\$PWD\" \"\$PTY_TEST_VALUE\"\n")
            transcript.await("STATE=$root:kept")
            session.resize(ToolboxTerminalSize(rows = 41, columns = 97, widthPixels = 970, heightPixels = 820))
            session.send("stty size; printf '__%s__\\n' RESIZED\n")
            transcript.await("__RESIZED__")
            assertTrue(transcript.text().contains("41 97"))

            val command = "printf '\\033[31m通知✓\\033[0m\\n'; printf '__%s__\\n' STDERR >&2\n".toByteArray()
            val split = command.indexOf(0xe9.toByte()) + 1
            assertTrue(split > 0)
            session.write(command.copyOfRange(0, split))
            session.write(command.copyOfRange(split, command.size))
            transcript.await("\u001b[31m通知✓\u001b[0m")
            transcript.await("__STDERR__")
            session.send("exit 7\n")
            assertEquals(7, session.awaitExit().code)
            transcript.reader.join()
            assertNull(transcript.failure)
        }
    } } }

    @Test fun controlCInterruptsTheForegroundJobAndControlDThenExitsOnlyThisShell(): Unit = runBlocking { withTimeout(25_000) { both { fixture, _ ->
        fixture.backend.openTerminal().use { session ->
            val transcript = Transcript(this, session)
            initialize(session, transcript)
            session.send("sh -c 'printf \"__%s__\\n\" FOREGROUND; exec sleep 30'\n")
            transcript.await("__FOREGROUND__")
            val beforeInterrupt = transcript.text().length
            session.write(byteArrayOf(3))
            transcript.await("PTY> ", after = beforeInterrupt)
            session.send("printf '__%s__\\n' STILL_RUNNING\n")
            transcript.await("__STILL_RUNNING__")
            assertEquals(ToolboxPhase.CONNECTED, fixture.backend.state.value.phase)
            session.write(byteArrayOf(4))
            assertEquals(0, session.awaitExit().code)
            transcript.reader.join()
            assertNull(transcript.failure)
        }
        assertTrue(fixture.backend.execute("printf separate-pipe-command").successful)
    } } }

    @Test fun disconnectHangsUpAndReapsTheShellAndDoesNotReplayItsInput(): Unit = runBlocking { withTimeout(25_000) { both { fixture, root ->
        val pidFile = root.resolve("shell.pid")
        val effectFile = root.resolve("effect")
        val session = fixture.backend.openTerminal()
        val transcript = Transcript(this, session)
        try {
            initialize(session, transcript)
            session.send("printf '%s' \"\$\$\" > ${ToolboxProtocol.quote(pidFile.toString())}; printf x >> ${ToolboxProtocol.quote(effectFile.toString())}; printf '__%s__\\n' EFFECT\n")
            transcript.await("__EFFECT__")
            val pid = Files.readString(pidFile).toLong()
            fixture.backend.disconnect()
            assertFailsWith<Exception> { session.awaitExit() }
            session.close()
            transcript.reader.join()
            withTimeout(5_000) { while (Files.exists(Path.of("/proc/$pid"))) delay(10) }
            fixture.backend.connect()
            assertFailsWith<Exception> { session.send("printf should-not-run\n") }
            assertEquals("x", Files.readString(effectFile))
            fixture.backend.openTerminal().use { next ->
                val nextTranscript = Transcript(this, next)
                initialize(next, nextTranscript)
                next.send("exit\n")
                assertEquals(0, next.awaitExit().code)
                nextTranscript.reader.join()
            }
        } finally { session.close(); transcript.reader.cancelAndJoin() }
    } } }

    @Test fun disconnectStillReachesTheAgentWhileTheForegroundProgramIsNotReadingInput(): Unit = runBlocking { withTimeout(25_000) { both { fixture, root ->
        val pidFile = root.resolve("shell.pid")
        val session = fixture.backend.openTerminal()
        val transcript = Transcript(this, session)
        try {
            initialize(session, transcript)
            session.send("printf '%s' \"\$\$\" > ${ToolboxProtocol.quote(pidFile.toString())}; sh -c 'stty -echo -icanon; printf \"__%s__\\n\" INPUT_STOPPED; exec sleep 30'\n")
            transcript.await("__INPUT_STOPPED__")
            val pid = Files.readString(pidFile).toLong()
            val writing = async { runCatching { session.write(ByteArray(ToolboxProtocol.CHUNK) { 'x'.code.toByte() }) } }
            // The terminal line discipline has finite room, while sleep intentionally never reads.
            // This request is awaiting its PTY-write ACK; it must not block observation of wire EOF.
            delay(100)
            assertFalse(writing.isCompleted)
            fixture.backend.disconnect()
            assertTrue(writing.await().isFailure)
            transcript.reader.join()
            withTimeout(5_000) { while (Files.exists(Path.of("/proc/$pid"))) delay(10) }
        } finally { session.close(); transcript.reader.cancelAndJoin() }
    } } }

    @Test fun continuousBackgroundOutputCannotPostponeTheShellExitForever(): Unit = runBlocking { withTimeout(25_000) { both { fixture, root ->
        val pidFile = root.resolve("background.pid")
        var owned: ProcessHandle? = null
        fixture.backend.openTerminal().use { session ->
            val transcript = Transcript(this, session)
            try {
                initialize(session, transcript)
                // HUP is ignored before readiness. The loop exits itself on PTY write failure,
                // so closing the master need not force-kill this deliberately surviving job.
                val command = "trap '' HUP; printf '%s' \"\$\$\" > "+
                    ToolboxProtocol.quote(pidFile.toString()) +
                    "; printf '__%s__\\n' BACKGROUND_READY; while printf '__BACKGROUND_OUTPUT__\\n'; do sleep 0.02; done"
                session.send("sh -c "+ToolboxProtocol.quote(command)+" &\n")
                transcript.await("__BACKGROUND_READY__")
                owned = ProcessHandle.of(Files.readString(pidFile).toLong()).orElseThrow()
                session.send("exit 23\n")
                val result = withTimeout(6_000) { session.awaitExit() }
                transcript.reader.join()
                assertEquals(23, result.code)
                assertTrue(result.outputTruncated, "A continuously producing inherited PTY must hit the fixed drain bound")
                assertTrue(transcript.text().contains("__BACKGROUND_OUTPUT__"))
                assertNull(transcript.failure)
            } finally {
                session.close(); transcript.reader.cancelAndJoin()
                owned?.takeIf { it.isAlive }?.destroy()
            }
        }
    } } }

    @Test fun finiteTailSurvivesSlowFramedWritesBeforeTheDrainLimit(): Unit = runBlocking { withTimeout(30_000) {
        for (kind in ToolboxBackendKind.entries) SlowWireFixture(kind, this).use { fixture ->
            fixture.backend.connect()
            fixture.backend.openTerminal().use { session ->
                val transcript = Transcript(this, session)
                initialize(session, transcript)
                session.send("printf '%65536s' '' | tr ' ' x; printf '\\n__%s__\\n' FINITE_TAIL; exit 19\n")
                val result = withTimeout(10_000) { session.awaitExit() }
                transcript.reader.join()
                assertEquals(19, result.code)
                assertFalse(result.outputTruncated)
                assertEquals(65_536, transcript.text().count { it == 'x' })
                assertTrue(transcript.text().contains("__FINITE_TAIL__"))
                assertTrue(fixture.delayedWrites.get() > 4)
                assertNull(transcript.failure)
            }
        }
    } }

    @Test fun openingARealTerminalReportsExecFailureInsteadOfReturningAReadySession(): Unit = runBlocking { withTimeout(15_000) {
        AgentBackendIntegrationTest.Fixture(ToolboxBackendKind.ROOT_BRIDGE, this,
            environment = { it.copy(shell = "/no-such-ticbox-shell") }).use { fixture ->
            fixture.backend.connect()
            assertEquals("terminal_start_failed", assertFailsWith<ToolboxException> { fixture.backend.openTerminal() }.code)
        }
    } }

    @Test fun invalidRemoteTerminalParametersAreRejectedBeforeStartingAnyProcess() {
        fun args(rows: Long = 24, columns: Long = 80, width: Long = 0, term: String = "xterm-256color") = buildJsonObject {
            put("rows", rows); put("columns", columns); put("widthPixels", width); put("heightPixels", 0); put("term", term)
        }
        for (body in listOf(args(rows = 0), args(rows = Long.MAX_VALUE), args(columns = 1001), args(width = 65536), args(term = "xterm\nBAD=value"))) {
            val input = ByteArrayOutputStream().also { ToolboxProtocol.write(it, ToolboxProtocol.REQUEST, ToolboxProtocol.request("terminal", body)) }
            val output = ByteArrayOutputStream()
            ToolboxAgentRuntime(AgentWire(ByteArrayInputStream(input.toByteArray()), output),
                AgentEnvironment(2000, 33, "", "/bin/sh", deviceId = "host-test")).run()
            val response = ByteArrayInputStream(output.toByteArray())
            assertEquals(ToolboxProtocol.READY, ToolboxProtocol.required(response).type)
            val error = ToolboxProtocol.required(response)
            assertEquals(ToolboxProtocol.ERROR, error.type)
            assertEquals("invalid_request", error.objectValue()["code"]!!.jsonPrimitive.content)
            assertNull(ToolboxProtocol.read(response))
        }
    }
}
