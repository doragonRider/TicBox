package dev.ticbox.companion.platform.toolbox

import dev.ticbox.companion.api.AdbDiscoveredEndpoint
import dev.ticbox.companion.api.AdbEndpoint
import dev.ticbox.companion.api.AdbPairingStatus
import dev.ticbox.companion.engine.toolbox.toolboxIo
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Only a peer's explicit TLS certificate rejection establishes that pairing is needed. */
internal fun adbRequiresPairing(error: Throwable): Boolean {
    if (error is CancellationException) return false
    val alerts = listOf("SSLV3_ALERT_CERTIFICATE_UNKNOWN", "TLSV1_ALERT_UNKNOWN_CA",
        "SSLV3_ALERT_BAD_CERTIFICATE", "TLSV13_ALERT_CERTIFICATE_REQUIRED",
        "fatal alert: certificate_unknown", "fatal alert: unknown_ca",
        "fatal alert: bad_certificate", "fatal alert: certificate_required")
    return generateSequence(error) { it.cause }.take(16).filterIsInstance<SSLException>()
        .any { failure -> alerts.any { failure.message.orEmpty().contains(it, ignoreCase = true) } }
}

/** Own one handshake, including cancellation. No shell or agent operation is required. */
internal suspend fun probeAdbPairing(
    timeoutMillis: Long = 3_000,
    close: () -> Unit,
    authenticate: () -> Unit,
): AdbPairingStatus {
    require(timeoutMillis > 0)
    return try {
        withTimeout(timeoutMillis) {
            toolboxIo(onCancel = close) { authenticate(); AdbPairingStatus.PAIRED }
        }
    } catch (cancelled: CancellationException) {
        if (cancelled !is TimeoutCancellationException || !currentCoroutineContext().isActive) throw cancelled
        AdbPairingStatus.UNKNOWN
    } catch (error: Exception) {
        if (adbRequiresPairing(error)) AdbPairingStatus.REQUIRED else AdbPairingStatus.UNKNOWN
    } finally { runCatching(close) }
}

/** Check each connection endpoint once; pairing-code ports use a different protocol. */
internal suspend fun authenticateAdbDiscovery(
    endpoints: List<AdbDiscoveredEndpoint>,
    timeoutMillis: Long = 5_000,
    authenticate: suspend (AdbEndpoint) -> AdbPairingStatus,
): List<AdbDiscoveredEndpoint> {
    val observed = ConcurrentHashMap<AdbEndpoint, AdbPairingStatus>()
    val parallel = Semaphore(4)
    withTimeoutOrNull(timeoutMillis) {
        endpoints.filterNot { it.pairing }.map { it.endpoint }.distinct().map { endpoint ->
            async { parallel.withPermit { observed[endpoint] = authenticate(endpoint) } }
        }.awaitAll()
    }
    return endpoints.map { found ->
        if (found.pairing) found else found.copy(pairingStatus = observed[found.endpoint] ?: AdbPairingStatus.UNKNOWN)
    }
}

/**
 * Marks the connection endpoints of the device connected last. Wireless debugging takes a new port
 * each time it's turned on and the address can change, but the service name stays; an endpoint
 * typed in by hand has none, so then the host is all there is to go by.
 */
internal fun markLastAdbDevice(found: List<AdbDiscoveredEndpoint>, name: String?, host: String?): List<AdbDiscoveredEndpoint> =
    found.map { endpoint ->
        val last = !endpoint.pairing && if (name != null) endpoint.name == name else host != null && endpoint.endpoint.host == host
        if (last) endpoint.copy(autoConnect = true) else endpoint
    }
