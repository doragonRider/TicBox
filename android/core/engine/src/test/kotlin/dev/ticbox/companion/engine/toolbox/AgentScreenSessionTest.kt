package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.ToolboxException
import dev.ticbox.companion.api.ToolboxScreenEvent
import dev.ticbox.companion.protocol.ToolboxProtocol
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.io.FilterInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AgentScreenSessionTest {
    @Test fun resetVideoUsesPinnedScrcpyEmptyControlMessage() {
        // v4.1 ControlMessage.TYPE_RESET_VIDEO=17; its reader consumes no payload bytes.
        val output = java.io.ByteArrayOutputStream()
        ToolboxProtocol.write(output, ToolboxProtocol.CONTROL, ScrcpyControl.resetVideo())
        assertContentEquals(byteArrayOf(15, 0, 0, 0, 1, 17), output.toByteArray())
    }

    @Test fun `a stalled screen including one with config but no media reaches a bounded failure`() = runBlocking { withTimeout(7_000) {
        for (stage in listOf("none", "configuration", "codec-config", "empty-frame")) Fixture(this, timeoutMillis = 300).use { fixture ->
            if (stage != "none") fixture.configure()
            if (stage == "codec-config") fixture.frame(Long.MIN_VALUE, byteArrayOf(0x67))
            if (stage == "empty-frame") fixture.frame(1, byteArrayOf())
            val error = assertFailsWith<ToolboxException> { fixture.session.events.collect { } }
            assertEquals("timeout", error.code)
            fixture.awaitClosed()
            assertTrue(fixture.closed.get())
        }
    } }

    @Test fun `sound arrives as its own events and doesn't count as the first picture`() = runBlocking { withTimeout(5_000) {
        Fixture(this, timeoutMillis = 300).use { fixture ->
            fixture.send(ToolboxProtocol.AUDIO_CONFIG, buildJsonObject { put("codec", "opus") }.toString().toByteArray())
            fixture.send(ToolboxProtocol.AUDIO_FRAME, ByteBuffer.allocate(10).putLong(Long.MIN_VALUE or 7).put(1).put(2).array())
            fixture.send(ToolboxProtocol.AUDIO_FRAME, ByteBuffer.allocate(9).putLong(9).put(3).array())
            val events = mutableListOf<ToolboxScreenEvent>()
            val error = assertFailsWith<ToolboxException> { fixture.session.events.collect { events += it } }
            assertEquals("timeout", error.code)
            assertEquals(ToolboxScreenEvent.AudioConfiguration("opus"), events[0])
            val head = assertIs<ToolboxScreenEvent.AudioFrame>(events[1])
            assertTrue(head.codecConfig)
            assertEquals(7, head.ptsMicros)
            assertContentEquals(byteArrayOf(1, 2), head.bytes)
            val frame = assertIs<ToolboxScreenEvent.AudioFrame>(events[2])
            assertFalse(frame.codecConfig)
            assertEquals(9, frame.ptsMicros)
        }
    } }

    @Test fun `a device without sound capture is announced and a short sound frame is refused`() = runBlocking { withTimeout(5_000) {
        Fixture(this).use { fixture ->
            fixture.send(ToolboxProtocol.AUDIO_CONFIG, "{}".toByteArray())
            fixture.send(ToolboxProtocol.AUDIO_FRAME, byteArrayOf(1))
            val events = mutableListOf<ToolboxScreenEvent>()
            val error = assertFailsWith<ToolboxException> { fixture.session.events.collect { events += it } }
            assertEquals("invalid_audio_frame", error.code)
            assertEquals(listOf<ToolboxScreenEvent>(ToolboxScreenEvent.AudioConfiguration(null)), events)
        }
    } }

    @Test fun `a first frame cannot disable the deadline by stopping mid packet`() = runBlocking { withTimeout(5_000) {
        Fixture(this, timeoutMillis = 300).use { fixture ->
            fixture.configure()
            fixture.output.write(byteArrayOf(ToolboxProtocol.VIDEO_FRAME.toByte(), 0, 0, 0, 20, 0))
            fixture.output.flush()
            assertEquals("timeout", assertFailsWith<ToolboxException> { fixture.session.events.collect { } }.code)
            fixture.awaitClosed()
            assertTrue(fixture.closed.get())
        }
    } }

    @Test fun `a live first frame preserves normalized flags and has no ongoing idle timeout`() = runBlocking { withTimeout(5_000) {
        Fixture(this, timeoutMillis = 500).use { fixture ->
            val first = CompletableDeferred<Unit>()
            val events = mutableListOf<ToolboxScreenEvent>()
            val reading = launch { fixture.session.events.collect { event ->
                events += event
                if (event is ToolboxScreenEvent.Frame && !event.codecConfig) first.complete(Unit)
            } }
            fixture.configure()
            fixture.frame(Long.MIN_VALUE, byteArrayOf(0x67))
            fixture.frame((1L shl 62) or 123_456, byteArrayOf(0x65))
            first.await()
            delay(650)
            assertFalse(fixture.closed.get())
            fixture.send(ToolboxProtocol.CLIPBOARD_ACK, ByteBuffer.allocate(8).putLong(8).array())
            fixture.send(ToolboxProtocol.END)
            reading.join()
            val config = assertIs<ToolboxScreenEvent.Configuration>(events[0])
            assertEquals(480, config.width); assertEquals(480, config.height)
            val codec = assertIs<ToolboxScreenEvent.Frame>(events[1])
            assertTrue(codec.codecConfig); assertFalse(codec.keyFrame); assertEquals(0L, codec.ptsMicros)
            val key = assertIs<ToolboxScreenEvent.Frame>(events[2])
            assertTrue(key.keyFrame); assertFalse(key.codecConfig); assertEquals(123_456L, key.ptsMicros)
            assertContentEquals(byteArrayOf(0x65), key.bytes)
            assertEquals(ToolboxScreenEvent.ClipboardAcknowledged(8), events.last())
        }
    } }

    @Test fun `control only sessions may wait without video or a startup timer`() = runBlocking { withTimeout(5_000) {
        Fixture(this, video = false, timeoutMillis = 100).use { fixture ->
            delay(200)
            assertFalse(fixture.closed.get())
            fixture.send(ToolboxProtocol.CLIPBOARD_ACK, ByteBuffer.allocate(8).putLong(4).array())
            fixture.send(ToolboxProtocol.END)
            assertEquals(listOf(ToolboxScreenEvent.ClipboardAcknowledged(4)), fixture.session.events.toList())
        }
    } }

    @Test fun `a structured screen failure reaches the caller instead of waiting for startup`() = runBlocking { withTimeout(5_000) {
        Fixture(this).use { fixture ->
            ToolboxProtocol.write(fixture.output, ToolboxProtocol.ERROR, buildJsonObject { put("code", "screen_protocol_mismatch") })
            assertEquals("screen_protocol_mismatch", assertFailsWith<ToolboxException> { fixture.session.events.collect { } }.code)
            fixture.awaitClosed()
            assertTrue(fixture.closed.get())
        }
    } }

    @Test fun `closing before startup cancels the read and timer without a timeout error`(): Unit = runBlocking { withTimeout(5_000) {
        Fixture(this, timeoutMillis = 100).use { fixture ->
            fixture.session.close()
            delay(200)
            fixture.awaitClosed()
            assertTrue(fixture.closed.get())
            assertFailsWith<kotlinx.coroutines.CancellationException> { fixture.session.events.collect { } }
        }
    } }

    @Test fun `owner cancellation wins when socket close wakes the reader before close returns`(): Unit = runBlocking { withTimeout(5_000) {
        val observed = AtomicReference<Throwable?>()
        val collectionEnded = CountDownLatch(1)
        // Force the reader's close-induced failure to reach the collector before the owner's
        // link.close() returns. This used to race channel.close(SocketException) against cancel.
        Fixture(this, timeoutMillis = 60_000, afterSocketClosed = {
            check(collectionEnded.await(2, TimeUnit.SECONDS)) { "collector did not observe the terminal state" }
        }).use { fixture ->
            val collector = launch(Dispatchers.Default) {
                try { fixture.session.events.collect { } }
                catch (failure: Throwable) { observed.set(failure) }
                finally { collectionEnded.countDown() }
            }
            try {
                fixture.awaitReading()
                val tasks = currentCoroutineContext().job.children.toList()
                fixture.session.close()
                withTimeout(1_000) { tasks.joinAll() }
                assertIs<CancellationException>(observed.get())
                assertTrue(fixture.closed.get())
                assertTrue(tasks.all { it.isCompleted }) // Reader and the 60s timer both ended.
            } finally { collector.cancel(); collectionEnded.countDown() }
        }
    } }

    @Test fun `closing after a remote failure preserves the original failure`(): Unit = runBlocking { withTimeout(5_000) {
        Fixture(this).use { fixture ->
            ToolboxProtocol.write(fixture.output, ToolboxProtocol.ERROR, buildJsonObject { put("code", "screen_protocol_mismatch") })
            fixture.awaitClosed()
            fixture.session.close()
            assertEquals("screen_protocol_mismatch", assertFailsWith<ToolboxException> { fixture.session.events.collect { } }.code)
        }
        Fixture(this).use { fixture ->
            fixture.send(ToolboxProtocol.END)
            fixture.awaitClosed()
            fixture.session.close()
            assertTrue(fixture.session.events.toList().isEmpty())
        }
    } }

    private class Fixture(scope: CoroutineScope, video: Boolean = true, timeoutMillis: Long = 2_000,
        afterSocketClosed: () -> Unit = {},
    ) : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        private val source = Socket(listener.inetAddress, listener.localPort)
        private val sink = listener.accept()
        val output = source.getOutputStream()
        val closed = AtomicBoolean()
        private val closure = CompletableDeferred<Unit>()
        private val reading = CountDownLatch(1)
        private val link = AgentLink(object : ToolboxChannel {
            override val input = object : FilterInputStream(sink.getInputStream()) {
                override fun read(): Int { reading.countDown(); return super.read() }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    reading.countDown(); return super.read(bytes, offset, length)
                }
            }
            override val output = sink.getOutputStream()
            override fun close() { closed.set(true); sink.close(); afterSocketClosed(); closure.complete(Unit) }
        }) { }
        val session = AgentScreenSession(link, scope, video = video, startupTimeoutMillis = timeoutMillis)
        suspend fun awaitReading() = withContext(Dispatchers.IO) { check(reading.await(2, TimeUnit.SECONDS)) { "socket read did not start" } }
        suspend fun awaitClosed() { withTimeout(1_000) { closure.await() } }
        fun send(type: Int, bytes: ByteArray = byteArrayOf()) = ToolboxProtocol.write(output, type, bytes)
        fun configure() = ToolboxProtocol.write(output, ToolboxProtocol.VIDEO_CONFIG, buildJsonObject { put("codec", "h264"); put("width", 480); put("height", 480) })
        fun frame(flags: Long, bytes: ByteArray) = send(ToolboxProtocol.VIDEO_FRAME, ByteBuffer.allocate(8 + bytes.size).putLong(flags).put(bytes).array())
        override fun close() { session.close(); source.close(); sink.close(); listener.close() }
    }
}
