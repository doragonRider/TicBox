package dev.ticbox.companion.protocol

import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ScreenUdpSecurityTest {
    private val key = ByteArray(32) { it.toByte() }
    private val plain = ScreenUdpTransport.SecurityMode.AUTH_ONLY
    private val fec = ScreenUdpTransport.VideoProtection.RS_16_4

    @Test fun authenticatedPlaintextMatchesIndependentHmacVectorAndRejectsTampering() {
        val sender = ScreenUdpCipher(key, 1, true, fec, plain)
        val receiver = ScreenUdpCipher(key, 1, true, fec, plain)
        val frame = ScreenUdpFragment(ScreenUdpCipher.MEDIA, 7, 4, 1, 3, 0, 2, "ABC".toByteArray())
        val encoded = sender.encode(frame)
        // Generated independently (Python hmac/hashlib HMAC-SHA256) over the defined key-domain/header/payload.
        val expected = "5457553104010301000000000000000700000000000000040000000300000002414243569c5dc7faa9968f8112fb8f5b3b2ba3"
        assertEquals(expected, encoded.joinToString("") { "%02x".format(it) })
        assertContentEquals("ABC".toByteArray(), encoded.copyOfRange(32, 35))
        assertContentEquals(frame.payload, assertNotNull(receiver.decode(encoded)).payload)
        for (index in encoded.indices) {
            val corrupted = encoded.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertNull(receiver.decode(corrupted), "modified byte $index accepted")
        }
        assertContentEquals(frame.payload, assertNotNull(receiver.decode(encoded)).payload) // failed tags do not poison MAC state
        assertNull(ScreenUdpCipher(ByteArray(32), 1, true, fec, plain).decode(encoded))
        assertNull(ScreenUdpCipher(key, 1, false, fec, plain).decode(encoded))
        assertNull(ScreenUdpCipher(key, 2, true, security = plain).decode(encoded))
        assertNull(ScreenUdpCipher(key, 1, true, fec).decode(encoded))
        assertNull(receiver.decode(ScreenUdpCipher(key, 1, true, fec).encode(frame)))
        val replay = ScreenUdpReplayWindow()
        assertTrue(replay.accept(assertNotNull(receiver.decode(encoded)).packetSequence))
        assertFalse(replay.accept(assertNotNull(receiver.decode(encoded)).packetSequence))
    }

    @Test fun bothAuthenticatedPlaintextLanesKeepFramingAndFecAcrossRealSockets() {
        for (stream in listOf(ScreenUdpTransport.VIDEO, ScreenUdpTransport.AUDIO)) {
            val messages = LinkedBlockingQueue<ScreenUdpTransport.Message>()
            val errors = LinkedBlockingQueue<IOException>()
            val protection = if (stream == 1) fec else ScreenUdpTransport.VideoProtection.NONE
            ScreenUdpTransport.listen(key, stream, onFailure = errors::add, protection = protection, security = plain).use { server ->
                ScreenUdpTransport.connect(InetAddress.getByName("127.0.0.1"), server.port, key, stream,
                    messages::add, errors::add, protection = protection, security = plain).use { client ->
                    client.awaitConnected(); server.awaitConnected()
                    val content = ByteArray(37_619) { (it * 31).toByte() }
                    val stats = server.sendMeasured(14, content)
                    assertEquals("hmac_sha256_128", stats.mediaSecurity)
                    assertContentEquals(content, assertNotNull(messages.poll(2, TimeUnit.SECONDS)).bytes)
                    assertNull(errors.poll())
                }
            }
        }
    }

    @Test fun authenticatedPlaintextParityRepairsMissingShardsWithoutSkippingTagChecks() {
        val data = Array(16) { row -> ByteArray(1152) { column -> (row * 31 + column).toByte() } }
        val shards = data + ScreenReedSolomon.encode(data)
        val sender = ScreenUdpCipher(key, 1, true, fec, plain)
        val receiver = ScreenUdpCipher(key, 1, true, fec, plain)
        val assembler = ScreenFecAssembler(150, collectDiagnostics = true)
        val frames = mutableListOf<ScreenUdpTransport.Message>()
        for (index in shards.indices) {
            val wire = sender.encode(ScreenUdpFragment(ScreenUdpCipher.MEDIA, index.toLong(), 0, 14,
                16 * 1152, index, 20, shards[index]))
            if (index in setOf(0, 3, 7, 12)) {
                wire[40] = (wire[40].toInt() xor 1).toByte()
                assertNull(receiver.decode(wire)) // Authentication failure is an erasure, not input to RS.
            } else frames += assembler.accept(assertNotNull(receiver.decode(wire)), index.toLong())
        }
        val frame = frames.single()
        val expected = ByteArray(16 * 1152)
        data.forEachIndexed { index, bytes -> bytes.copyInto(expected, index * 1152) }
        assertContentEquals(expected, frame.bytes)
        assertEquals(4, frame.fec!!.recoveredDataShards)
        assertFalse(frame.discontinuity)
    }

    @Test fun aDifferentEncryptionModeCannotCompleteEvenTheHandshake() {
        val timing = ScreenUdpTransport.Timing(handshakeMillis = 200, heartbeatMillis = 25, idleMillis = 100)
        ScreenUdpTransport.listen(key, 1, onFailure = {}, timing = timing, protection = fec, security = plain).use { server ->
            ScreenUdpTransport.connect(InetAddress.getByName("127.0.0.1"), server.port, key, 1, {}, {}, timing = timing, protection = fec).use { client ->
                assertFailsWith<IOException> { client.awaitConnected(1_000) }
                assertFailsWith<IOException> { server.awaitConnected(1_000) }
            }
        }
    }
}
