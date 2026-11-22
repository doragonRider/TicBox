package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import java.io.*
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Deterministic full-duplex ordering: the reply follows the uploader's failing-source cleanup. */
class InstallReplyRaceTest {
    private enum class Reply { REJECTED, SUCCESS, UNKNOWN, MALFORMED, ABSENT }
    private class Connector(private val reply: Reply, private val sourceError: Boolean = false) : ToolboxAgentConnector {
        override val device = "test-watch"
        private val opens = AtomicInteger()
        private val sourceClosed = CountDownLatch(1)
        private val disconnected = CountDownLatch(1)
        private val dataWrites = AtomicInteger()
        @Volatile private var active: ToolboxChannel? = null
        val replyWaiting = CountDownLatch(1)
        val requests = AtomicInteger()
        val sentDataPackets: Int get() = dataWrites.get()
        val channelClosed = AtomicBoolean()
        val localFailure = ToolboxException("package_unreadable")
        val writeFailure = SocketException("peer rejected the first split")
        override suspend fun prepare() = Unit
        override suspend fun open(): ToolboxChannel {
            val probe = opens.incrementAndGet() == 1
            val hello = ByteArrayOutputStream().apply {
                ToolboxProtocol.write(this, ToolboxProtocol.READY, buildJsonObject {
                    put("version", ToolboxProtocol.VERSION); put("uid", 2000); put("sdk", 33); put("deviceId", device)
                })
                if (probe) ToolboxProtocol.write(this, ToolboxProtocol.RESULT, buildJsonObject { put("success", true) })
            }.toByteArray().inputStream()
            val terminal = ByteArrayOutputStream().apply {
                when (reply) {
                    Reply.REJECTED, Reply.SUCCESS -> ToolboxProtocol.write(this, ToolboxProtocol.RESULT, buildJsonObject {
                        put("success", reply == Reply.SUCCESS); put("output", if (reply == Reply.SUCCESS) "Success" else "Failure [first split rejected]")
                    })
                    Reply.UNKNOWN -> ToolboxProtocol.write(this, ToolboxProtocol.ERROR, buildJsonObject { put("code", "install_result_unknown") })
                    Reply.MALFORMED -> ToolboxProtocol.write(this, ToolboxProtocol.RESULT, buildJsonObject { put("output", "missing success flag") })
                    Reply.ABSENT -> Unit
                }
            }.toByteArray().inputStream()
            val response = object : InputStream() {
                override fun read(): Int {
                    sourceClosed.await()
                    if (reply == Reply.ABSENT) { replyWaiting.countDown(); disconnected.await() }
                    return terminal.read()
                }
            }
            return object : ToolboxChannel {
                override val input: InputStream = if (probe) hello else SequenceInputStream(hello, response)
                override val output = object : OutputStream() {
                    override fun write(value: Int) = error("The small framed fixture writes whole packets")
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        val packet = ToolboxProtocol.required(ByteArrayInputStream(bytes, offset, length))
                        if (probe) return
                        if (packet.type == ToolboxProtocol.REQUEST) requests.incrementAndGet()
                        if (packet.type == ToolboxProtocol.DATA && dataWrites.incrementAndGet() == 2 && !sourceError) throw writeFailure
                    }
                }
                override fun close() {
                    if (!probe) { channelClosed.set(true); disconnected.countDown(); sourceClosed.countDown() }
                }
            }.also { if (!probe) active = it }
        }
        fun apks(): List<ToolboxApk> = listOf(
            ToolboxApk("base.apk", 1) { byteArrayOf(1).inputStream() },
            ToolboxApk("split.apk", 1) {
                object : ByteArrayInputStream(byteArrayOf(2)) {
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        if (sourceError) throw localFailure
                        return super.read(bytes, offset, length)
                    }
                    override fun close() {
                        // The writer is now unwinding its failed send/read on the coroutine's
                        // thread. Its parent cancellation must not erase this pending reply.
                        sourceClosed.countDown(); super.close()
                    }
                }
            },
        )
        override fun close() { active?.close() }
    }
    private suspend fun exercise(connector: Connector, graceMillis: Long = 200, action: suspend (AgentToolboxBackend) -> Unit) = coroutineScope {
        val backend = AgentToolboxBackend(ToolboxBackendKind.ADB, this, connector, installReplyGraceMillis = graceMillis)
        try { backend.connect(); action(backend); assertEquals(1, connector.requests.get()) }
        finally { backend.close() }
    }
    @Test fun aDefinitiveRejectionSurvivesTheConcurrentWriterSocketFailure() = runBlocking { withTimeout(5_000) {
        val connector = Connector(Reply.REJECTED)
        exercise(connector) { backend ->
            val result = backend.install(connector.apks())
            assertFalse(result.success); assertEquals("Failure [first split rejected]", result.output)
        }
        assertTrue(connector.channelClosed.get())
    } }
    @Test fun aSourceFailureKeepsItsOwnReasonInsteadOfThePeersReply() = runBlocking { withTimeout(5_000) {
        withContext(Dispatchers.Default) {
            for (reply in listOf(Reply.REJECTED, Reply.SUCCESS, Reply.UNKNOWN)) {
                val connector = Connector(reply, sourceError = true)
                exercise(connector) { backend ->
                    val error = assertFailsWith<ToolboxException> { backend.install(connector.apks()) }
                    assertEquals("package_unreadable", error.code)
                }
            }
        }
    } }
    @Test fun successCannotOverrideAnUploadThatFailedToFinish() = runBlocking { withTimeout(5_000) {
        val connector = Connector(Reply.SUCCESS)
        exercise(connector) { backend ->
            val error = assertFailsWith<SocketException> { backend.install(connector.apks()) }
            assertEquals(connector.writeFailure.message, error.message)
        }
    } }
    @Test fun anUnknownCommitReplyDoesNotBecomeADefinitiveRejectionOrSuccess() = runBlocking { withTimeout(5_000) {
        val connector = Connector(Reply.UNKNOWN)
        exercise(connector) { backend ->
            val error = assertFailsWith<ToolboxException> { backend.install(connector.apks()) }
            assertEquals("install_result_unknown", error.code)
        }
    } }
    @Test fun aMalformedResultDoesNotBecomeAnInstallationResult() = runBlocking { withTimeout(5_000) {
        val connector = Connector(Reply.MALFORMED)
        exercise(connector) { backend ->
            val error = assertFailsWith<ToolboxException> { backend.install(connector.apks()) }
            assertEquals("invalid_install_result", error.code)
        }
    } }
    @Test fun missingReplyAfterTheWriteFailsHasABoundedWaitAndClosesTheChannel() = runBlocking { withTimeout(5_000) {
        val connector = Connector(Reply.ABSENT)
        exercise(connector) { backend ->
            val error = assertFailsWith<SocketException> { backend.install(connector.apks()) }
            assertEquals(connector.writeFailure.message, error.message)
        }
        assertTrue(connector.channelClosed.get())
    } }
    @Test fun callerCancellationDuringTheReplyGraceClosesWithoutWaitingForItsDeadline() = runBlocking { withTimeout(5_000) {
        val connector = Connector(Reply.ABSENT)
        exercise(connector, graceMillis = 60_000) { backend ->
            val installing = async { backend.install(connector.apks()) }
            try {
                withContext(Dispatchers.IO) { assertTrue(connector.replyWaiting.await(2, TimeUnit.SECONDS)) }
                yield()
                assertFalse(installing.isCompleted)
                installing.cancel(CancellationException("stop_requested"))
                val error = assertFailsWith<CancellationException> { installing.await() }
                assertFalse(error is TimeoutCancellationException)
                withTimeout(1_000) { installing.join() }
                assertTrue(connector.channelClosed.get())
            } finally { installing.cancelAndJoin() }
        }
    } }
    @Test fun aSourceOpenedAfterCancellationIsClosedWithoutSendingItsBytes() = runBlocking { withTimeout(5_000) {
        val connector = Connector(Reply.ABSENT)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val discarded = CountDownLatch(1)
        val source = object : ByteArrayInputStream(byteArrayOf(7)) {
            override fun close() { discarded.countDown(); super.close() }
        }
        try {
            exercise(connector) { backend ->
                val installing = async {
                    backend.install(listOf(ToolboxApk("late.apk", 1) {
                        entered.countDown(); release.await(); source
                    }))
                }
                try {
                    withContext(Dispatchers.IO) { assertTrue(entered.await(2, TimeUnit.SECONDS)) }
                    installing.cancelAndJoin()
                    assertTrue(installing.isCancelled)
                    assertTrue(connector.channelClosed.get())
                    release.countDown()
                    withContext(Dispatchers.IO) { assertTrue(discarded.await(2, TimeUnit.SECONDS)) }
                    assertEquals(0, connector.sentDataPackets)
                } finally { release.countDown(); installing.cancelAndJoin() }
            }
        } finally { release.countDown() }
    } }

}
