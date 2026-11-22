package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.ToolboxProtocol
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.*

/** A TCP/TLS close waiting behind a writer must not freeze the terminal's local owner. */
class TerminalCloseTest {
    private class StalledChannel(private val stallWrite: Boolean = false) : ToolboxChannel {
        val reading = CountDownLatch(1)
        val writing = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val finishClose = CountDownLatch(1)
        private val releaseIo = CountDownLatch(1)
        val closes = AtomicInteger()
        val writes = AtomicInteger()
        override val input = object : InputStream() {
            override fun read(): Int { reading.countDown(); releaseIo.await(); return -1 }
        }
        override val output = object : OutputStream() {
            override fun write(b: Int) { writes.incrementAndGet(); writing.countDown(); if (stallWrite) releaseIo.await() }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { writes.incrementAndGet(); writing.countDown(); if (stallWrite) releaseIo.await() }
        }
        override fun close() {
            closes.incrementAndGet(); closing.countDown()
            finishClose.await(); releaseIo.countDown()
        }
        fun unblockIo() { releaseIo.countDown() }
        fun finish() { finishClose.countDown(); releaseIo.countDown() }
    }
    private val target = ToolboxTarget("watch", 1, ToolboxBackendKind.ADB)
    private fun session(channel: StalledChannel, scope: CoroutineScope, retired: AtomicInteger = AtomicInteger()) =
        AgentTerminalSession(AgentLink(channel, asyncClose = true) { retired.incrementAndGet() }, scope, target, {})

    @Test fun explicitCloseReleasesWaitersBeforeSlowTransportCloseCompletes(): Unit = runBlocking { withTimeout(5_000) {
        val channel = StalledChannel()
        val retired = AtomicInteger()
        val terminal = session(channel, this, retired)
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertTrue(channel.reading.await(1, TimeUnit.SECONDS))
            val closing = executor.submit { terminal.close() }
            closing.get(1, TimeUnit.SECONDS)
            assertEquals(1, retired.get())
            assertTrue(channel.closing.await(1, TimeUnit.SECONDS))
            assertEquals(1L, channel.finishClose.count)
            assertFailsWith<CancellationException> { terminal.awaitExit() }
            terminal.close()
            assertEquals(1, channel.closes.get())
        } finally {
            channel.finish(); terminal.close(); executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    } }
    @Test fun cancellingABlockedWriteDoesNotWaitForTheTlsCloseLock(): Unit = runBlocking { withTimeout(5_000) {
        val channel = StalledChannel(stallWrite = true)
        val retired = AtomicInteger()
        val terminal = session(channel, this, retired)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val writer = launch(Dispatchers.Default) { terminal.write(byteArrayOf(65)) }
            assertTrue(channel.writing.await(1, TimeUnit.SECONDS))
            val cancellation = executor.submit { writer.cancel() }
            cancellation.get(1, TimeUnit.SECONDS)
            withTimeout(1_000) { writer.join() }
            assertEquals(1, retired.get())
            assertFailsWith<CancellationException> { terminal.awaitExit() }
            assertTrue(channel.closing.await(1, TimeUnit.SECONDS))
            assertEquals(1, channel.closes.get())
        } finally {
            channel.finish(); terminal.close(); executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    } }
    @Test fun queuedInputCannotStartAfterTheLinkWasRetired(): Unit = runBlocking { withTimeout(5_000) {
        val channel = StalledChannel(stallWrite = true)
        val link = AgentLink(channel, asyncClose = true) {}
        try {
            val first = async(Dispatchers.Default) { runCatching { link.send(ToolboxProtocol.STDIN, byteArrayOf(65)) } }
            assertTrue(channel.writing.await(1, TimeUnit.SECONDS))
            val queued = async(Dispatchers.Default) { runCatching { link.send(ToolboxProtocol.STDIN, byteArrayOf(66)) } }
            link.close()
            assertTrue(channel.closing.await(1, TimeUnit.SECONDS))
            channel.unblockIo() // The already-in-flight write may finish; the queued one must not start.
            first.await()
            assertEquals("connection_closed", assertIs<ToolboxException>(queued.await().exceptionOrNull()).code)
            assertEquals(1, channel.writes.get())
            assertEquals(1, channel.closes.get())
        } finally { channel.finish(); link.close() }
    } }
    @Test fun cancellingTheSessionOwnerReleasesAnUncollectedReader(): Unit = runBlocking { withTimeout(5_000) {
        val channel = StalledChannel()
        val lifetime = SupervisorJob()
        val terminal = session(channel, CoroutineScope(Dispatchers.Default + lifetime))
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertTrue(channel.reading.await(1, TimeUnit.SECONDS))
            val cancellation = executor.submit { lifetime.cancel() }
            cancellation.get(1, TimeUnit.SECONDS)
            withTimeout(1_000) { lifetime.join() }
            assertFailsWith<CancellationException> { terminal.awaitExit() }
            assertTrue(channel.closing.await(1, TimeUnit.SECONDS))
            assertEquals(1, channel.closes.get())
        } finally {
            channel.finish(); terminal.close(); lifetime.cancel(); executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    } }
}
