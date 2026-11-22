package dev.ticbox.companion.protocol

import java.io.IOException
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.serialization.json.*

class ScreenUdpFailureDiagnosticsTest {
    @Test fun failureReportsSafeStreamAndHeartbeatFactsWithoutExceptionMessages() {
        val state = ScreenUdpReceiveCounters().apply {
            received = 10; authenticated = 8; rejected = 2; pings = 2; pongs = 3; media = 3
            lastAuthenticated = 500; lastMedia = 100; lastPing = 400; lastPong = 500
            loop(0); loop(20); loop(100)
        }
        val error = IOException("screen_udp_io_failed", SocketException("sendto 192.0.2.7 private-key EHOSTUNREACH /private/file"))
        val value = ScreenUdpFailureDiagnostics.snapshot(error, 1, false, 600, state)
        val details = assertNotNull(ScreenUdpFailureDiagnostics.parse(value))
        assertEquals("video", details["stream"]); assertEquals("receiver", details["side"])
        assertEquals("EHOSTUNREACH", details["socketCode"]); assertEquals("SocketException", details["cause"])
        assertEquals("100", details["ageAuthenticatedMillis"]); assertEquals("500", details["ageMediaMillis"])
        assertEquals("80", details["maxLoopGapMillis"])
        assertTrue(details.size <= 24)
        assertFalse(value.toString().contains("192.0.2.7") || value.toString().contains("private"))
        assertNull(ScreenUdpFailureDiagnostics.parse(buildJsonObject { value.forEach { (k, v) -> put(k, v) }; put("stream", "secret") }))
    }

    @Test fun boundaryCrossingAndIdleHeartbeatsWorkWithAndWithoutDiagnostics() {
        val key = ByteArray(32) { it.toByte() }
        val timing = ScreenUdpTransport.Timing(heartbeatMillis = 40, idleMillis = 600)
        for (enabled in listOf(false, true)) {
            val errors = LinkedBlockingQueue<IOException>()
            val received = LinkedBlockingQueue<ScreenUdpTransport.Message>()
            val server = ScreenUdpTransport.listen(key, 1, onFailure = errors::add, timing = timing,
                protection = ScreenUdpTransport.VideoProtection.RS_16_4, collectDiagnostics = enabled)
            try {
                ScreenUdpTransport.connect(InetAddress.getLoopbackAddress(), server.port, key, 1, received::add, errors::add,
                    timing, protection = ScreenUdpTransport.VideoProtection.RS_16_4, collectDiagnostics = enabled).use { client ->
                    server.awaitConnected(); client.awaitConnected()
                    for (sequence in 0L..4101L) {
                        server.send(14, byteArrayOf(sequence.toByte()))
                        val frame = assertNotNull(received.poll(2, TimeUnit.SECONDS))
                        assertEquals(sequence, frame.sequence); assertFalse(frame.discontinuity)
                    }
                    assertNull(errors.poll(900, TimeUnit.MILLISECONDS)) // No media for longer than the idle deadline.
                    server.close()
                    val error = assertNotNull(errors.poll(2, TimeUnit.SECONDS))
                    if (enabled) {
                        val facts = assertNotNull(ScreenUdpFailureDiagnostics.parse(assertNotNull(assertIs<ScreenUdpTransportException>(error).observation)))
                        assertEquals("video", facts["stream"])
                        assertTrue(facts.getValue("receivedPings").toLong() > 0 || facts.getValue("receivedPongs").toLong() > 0)
                    } else assertNull(assertIs<ScreenUdpTransportException>(error).observation)
                }
            } finally { server.close() }
        }
    }
}
