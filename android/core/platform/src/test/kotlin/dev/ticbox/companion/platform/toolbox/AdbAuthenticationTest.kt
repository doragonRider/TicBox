package dev.ticbox.companion.platform.toolbox

import dev.ticbox.companion.api.AdbDiscoveredEndpoint
import dev.ticbox.companion.api.AdbEndpoint
import dev.ticbox.companion.api.AdbPairingStatus
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLProtocolException
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class AdbAuthenticationTest {
    @Test fun onlyPeerCertificateRejectionsEstablishThatPairingIsRequired() {
        for (alert in listOf("SSLV3_ALERT_CERTIFICATE_UNKNOWN", "TLSV1_ALERT_UNKNOWN_CA",
            "SSLV3_ALERT_BAD_CERTIFICATE", "TLSV13_ALERT_CERTIFICATE_REQUIRED", "Received fatal alert: certificate_unknown")) {
            assertTrue(adbRequiresPairing(IOException("wrapped", SSLProtocolException("Failure in SSL library: $alert"))))
        }
        for (error in listOf(IOException("SSLV3_ALERT_CERTIFICATE_UNKNOWN"), SocketTimeoutException("timeout"),
            SSLHandshakeException("No trusted certificate found"), SSLProtocolException("wrong version number"),
            CancellationException("cancelled").apply { initCause(SSLProtocolException("SSLV3_ALERT_CERTIFICATE_UNKNOWN")) })) {
            assertFalse(adbRequiresPairing(error), error.toString())
        }
    }

    @Test fun probesDistinguishSuccessfulAuthenticationCertificateRejectionAndNetworkFailure() = runBlocking {
        for ((failure, expected) in listOf(null to AdbPairingStatus.PAIRED,
            SSLProtocolException("SSLV3_ALERT_CERTIFICATE_UNKNOWN") to AdbPairingStatus.REQUIRED,
            SocketTimeoutException("unreachable") to AdbPairingStatus.UNKNOWN)) {
            var closed = false
            assertEquals(expected, probeAdbPairing(close = { closed = true }) { failure?.let { throw it } })
            assertTrue(closed)
        }
    }

    @Test fun probeTimeoutClosesTheRealSocketAndLeavesPairingUnknown() = runBlocking { withTimeout(5_000) {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            Socket(listener.inetAddress, listener.localPort).use { client ->
                listener.accept().use { peer ->
                    assertEquals(AdbPairingStatus.UNKNOWN, probeAdbPairing(50, client::close) { client.getInputStream().read() })
                    assertTrue(client.isClosed)
                    peer.soTimeout = 1_000
                    assertEquals(-1, peer.getInputStream().read())
                }
            }
        }
    } }

    @Test fun callerCancellationReleasesBlockedHandshakeAndRemainsCancellation() = runBlocking { withTimeout(5_000) {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            Socket(listener.inetAddress, listener.localPort).use { client ->
                listener.accept().use { peer ->
                    val started = CompletableDeferred<Unit>()
                    val probe = async { probeAdbPairing(close = client::close) { started.complete(Unit); client.getInputStream().read() } }
                    started.await(); probe.cancelAndJoin()
                    assertTrue(probe.isCancelled); assertTrue(client.isClosed)
                    peer.soTimeout = 1_000
                    assertEquals(-1, peer.getInputStream().read())
                }
            }
        }
    } }

    @Test fun discoveryNeverProbesPairingPortsAndChecksDuplicateConnectionEndpointsOnce() = runTest {
        val connection = AdbEndpoint("192.0.2.1", 37001)
        val pairing = AdbEndpoint("192.0.2.1", 37002)
        val endpoints = listOf(AdbDiscoveredEndpoint("connect", connection, false),
            AdbDiscoveredEndpoint("alias", connection, false), AdbDiscoveredEndpoint("pair", pairing, true))
        val calls = mutableListOf<AdbEndpoint>()
        val paired = authenticateAdbDiscovery(endpoints) { calls += it; AdbPairingStatus.PAIRED }
        assertEquals(listOf(connection), calls)
        assertEquals(listOf(AdbPairingStatus.PAIRED, AdbPairingStatus.PAIRED, AdbPairingStatus.UNKNOWN), paired.map { it.pairingStatus })
        val revoked = authenticateAdbDiscovery(paired) { AdbPairingStatus.REQUIRED }
        assertEquals(AdbPairingStatus.REQUIRED, revoked.first().pairingStatus)
        val offline = authenticateAdbDiscovery(paired) { AdbPairingStatus.UNKNOWN }
        assertEquals(AdbPairingStatus.UNKNOWN, offline.first().pairingStatus)
    }

    @Test fun scanDeadlineLimitsConcurrencyRetainsFinishedResultsAndCancelsUnfinishedChecks() = runTest {
        val endpoints = (1..10).map { AdbDiscoveredEndpoint("device-$it", AdbEndpoint("192.0.2.$it", 37000), false) }
        var active = 0; var peak = 0
        val results = authenticateAdbDiscovery(endpoints, timeoutMillis = 50) {
            if (it == endpoints.first().endpoint) AdbPairingStatus.PAIRED
            else {
                active++; peak = maxOf(peak, active)
                try { awaitCancellation() } finally { active-- }
            }
        }
        assertEquals(AdbPairingStatus.PAIRED, results.first().pairingStatus)
        assertTrue(results.drop(1).all { it.pairingStatus == AdbPairingStatus.UNKNOWN })
        assertEquals(4, peak); assertEquals(0, active)
    }

    @Test fun theLastDeviceIsKnownByItsServiceNameAfterItsAddressChangesAndByHostOnlyWhenTypedIn() {
        val found = listOf(AdbDiscoveredEndpoint("adb-watch-1", AdbEndpoint("192.0.2.9", 41001), false),
            AdbDiscoveredEndpoint("adb-watch-1", AdbEndpoint("192.0.2.9", 41002), true),
            AdbDiscoveredEndpoint("adb-phone-2", AdbEndpoint("192.0.2.1", 37001), false))
        // Connected to the watch at its old address: the new one is still that watch, and the phone now there isn't.
        assertEquals(listOf(true, false, false), markLastAdbDevice(found, "adb-watch-1", "192.0.2.1").map { it.autoConnect })
        assertEquals(listOf(false, false, true), markLastAdbDevice(found, null, "192.0.2.1").map { it.autoConnect })
        assertTrue(markLastAdbDevice(found, null, null).none { it.autoConnect })
    }
}
