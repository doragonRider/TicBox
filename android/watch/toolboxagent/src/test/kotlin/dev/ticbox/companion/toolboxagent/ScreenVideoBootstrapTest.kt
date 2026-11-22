package dev.ticbox.companion.toolboxagent

import dev.ticbox.companion.protocol.*
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import kotlinx.serialization.json.*

class ScreenVideoBootstrapTest {
    @Test fun coldDecoderAndLostFirstKeyRecoverWithoutAnyNewSourceImageOrDependentDelta() {
        val announcements = LinkedBlockingQueue<JsonObject>()
        val sent = LinkedBlockingQueue<Pair<Int, ByteArray>>()
        var keys = 0
        lateinit var gate: ScreenVideoBootstrap
        gate = ScreenVideoBootstrap(announcements::put, { type, bytes ->
            sent.put(type to bytes)
            if (type == ToolboxProtocol.VIDEO_FRAME && ByteBuffer.wrap(bytes).long == KEY) {
                keys++
                if (keys == 1) { gate.acknowledge(ack(2, "key", 0)); gate.acknowledge(ack(1, "key", 7)) }
                if (keys == 2) gate.acknowledge(ack(1, "key", 0))
            }
        }, keyRetryMillis = 20)
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        val producer = Thread {
            try {
                gate.send(ToolboxProtocol.VIDEO_CONFIG, config)
                gate.send(ToolboxProtocol.VIDEO_FRAME, frame(Long.MIN_VALUE))
                gate.send(ToolboxProtocol.VIDEO_FRAME, frame(KEY))
                gate.send(ToolboxProtocol.VIDEO_FRAME, frame(8_333))
            } catch (error: Throwable) { failure.set(error) } finally { done.countDown() }
        }.apply { start() }
        try {
            assertEquals(1L, announcements.poll(1, TimeUnit.SECONDS)!!.number("generation"))
            gate.acknowledge(ack(2, "decoder")) // A different generation cannot release the producer.
            assertNull(sent.poll(30, TimeUnit.MILLISECONDS))
            gate.acknowledge(ack(1, "decoder"))
            assertTrue(done.await(2, TimeUnit.SECONDS)); assertNull(failure.get())
            assertEquals(5, sent.size)
            val output = sent.toList()
            assertEquals(ToolboxProtocol.VIDEO_CONFIG, output[0].first)
            assertEquals(1L, ToolboxProtocol.Packet(13, output[0].second).objectValue().number("generation"))
            assertContentEquals(output[2].second, output[3].second)
            assertEquals(8_333L, ByteBuffer.wrap(output[4].second).long)
        } finally { gate.close(); producer.join(1_000); assertFalse(producer.isAlive) }
    }

    @Test fun keyRetriesAreBoundedAndCloseUnblocksBothHandshakePhases() {
        var sends = 0
        lateinit var gate: ScreenVideoBootstrap
        gate = ScreenVideoBootstrap({ gate.acknowledge(ack(it.number("generation")!!, "decoder")) }, { type, _ -> if (type == 14) sends++ }, keyRetryMillis = 1, keyAttempts = 3)
        gate.use {
            gate.send(13, config)
            assertEquals("screen_udp_recovery_failed", assertFailsWith<ScreenRelayException> { gate.send(14, frame(KEY)) }.code)
            assertEquals(3, sends)
        }
        for (phase in listOf("decoder", "key")) {
            val waiting = CountDownLatch(1)
            val stopped = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            lateinit var closing: ScreenVideoBootstrap
            closing = ScreenVideoBootstrap({ if (phase == "key") closing.acknowledge(ack(1, "decoder")) else waiting.countDown() }, { type, _ -> if (type == 14) waiting.countDown() }, decoderTimeoutMillis = 60_000, keyRetryMillis = 60_000)
            val thread = Thread { try { closing.send(13, config); closing.send(14, frame(KEY)) } catch (error: Throwable) { failure.set(error) } finally { stopped.countDown() } }.apply { start() }
            try {
                assertTrue(waiting.await(1, TimeUnit.SECONDS)); closing.close()
                assertTrue(stopped.await(1, TimeUnit.SECONDS)); assertIs<InterruptedIOException>(failure.get())
            } finally { closing.close(); thread.join(1_000); assertFalse(thread.isAlive) }
        }
    }

    @Test fun rotationReusesNoOldAcknowledgementEvenWithIdenticalDimensionsAndPts() {
        val announcements = LinkedBlockingQueue<JsonObject>()
        val sent = LinkedBlockingQueue<Int>()
        var token = 0L
        lateinit var gate: ScreenVideoBootstrap
        gate = ScreenVideoBootstrap({ token = it.number("generation")!!; announcements.put(it) }, { type, _ -> sent.put(type); if (type == 14) gate.acknowledge(ack(token, "key", 0)) })
        val errors = LinkedBlockingQueue<Throwable>()
        val thread = Thread { try { repeat(2) { gate.send(13, config); gate.send(14, frame(KEY)) } } catch (error: Throwable) { errors.put(error) } }.apply { start() }
        try {
            assertEquals(1L, announcements.poll(1, TimeUnit.SECONDS)!!.number("generation")); gate.acknowledge(ack(1, "decoder"))
            assertEquals(2L, announcements.poll(1, TimeUnit.SECONDS)!!.number("generation"))
            assertEquals(listOf(13, 14), sent.toList()); sent.clear()
            gate.acknowledge(ack(1, "decoder")); gate.acknowledge(ack(1, "key", 0))
            assertNull(sent.poll(30, TimeUnit.MILLISECONDS))
            gate.acknowledge(ack(2, "decoder")); thread.join(1_000)
            assertFalse(thread.isAlive); assertTrue(errors.isEmpty()); assertEquals(listOf(13, 14), sent.toList())
        } finally { gate.close(); thread.join(1_000) }
    }

    companion object {
        const val KEY = 1L shl 62
        val config = """{"codec":"h265","width":1180,"height":2560}""".toByteArray()
        fun frame(flags: Long): ByteArray = ByteBuffer.allocate(9).putLong(flags).put(1).array()
        fun ack(token: Long, phase: String, pts: Long? = null) = ToolboxProtocol.Packet(ToolboxProtocol.PROGRESS, buildJsonObject {
            put("kind", "screen_video_ack"); put("version", 1); put("generation", token); put("phase", phase)
            pts?.let { put("ptsMicros", it) }
        }.toString().toByteArray())
    }
}
