package dev.ticbox.companion.engine.google

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.CompanionDataInitializer
import dev.ticbox.companion.protocol.GoogleAccountConsentCodec
import java.net.URI
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Cloud consent uses real GMS calls; ROW diagnostic consent is a durable GMS-route DataItem. */
class GoogleConsentEffects(
    private val provider: GoogleServiceProvider,
    private val data: DataSyncClient,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val writer = Mutex()

    suspend fun apply(peer: PeerAddress, sessionId: Long, consents: List<SetupConsent>): List<GoogleServiceResult> = writer.withLock {
        val results = ArrayList<GoogleServiceResult>()
        for (consent in consents.filter { it.type == SetupOptinType.CLOUDSYNC || it.type == SetupOptinType.LOGGING }) {
            val operation = if (consent.type == SetupOptinType.LOGGING) "diagnostic_consent" else "cloud_sync_consent"
            if (consent.choice == SetupOptinChoice.SKIPPED) {
                results += GoogleServiceResult(GoogleServiceStatus.SKIPPED, operation, "no_consent_change")
                continue
            }
            val enabled = consent.choice == SetupOptinChoice.ACCEPTED
            try {
                if (consent.type == SetupOptinType.LOGGING) {
                    results += diagnostic(peer, sessionId, enabled)
                } else {
                    // Terms acceptance uses requestOptIn/requestOptOut; the separate setting API is
                    // available to the user but is not silently changed by this consent operation.
                    results += provider.optInCloudSync(enabled)
                }
            } catch (problem: CancellationException) { throw problem
            } catch (problem: Exception) {
                val result = when (problem) {
                    is GoogleServiceException -> GoogleServiceResult(problem.status, operation, problem.reasonCode, problem.serviceStatusCode)
                    is SecurityException -> GoogleServiceResult(GoogleServiceStatus.PERMISSION_DENIED, operation, "android_or_google_permission_denied")
                    else -> GoogleServiceResult(GoogleServiceStatus.FAILED, operation, problem.javaClass.simpleName)
                }
                results += result
                log(peer, sessionId, "consent_effect_failed", mapOf("operation" to operation, "status" to result.status.name, "exception" to problem.javaClass.simpleName))
            }
        }
        results.toList()
    }

    /** Exposes ConnectionClient.setCloudSyncSetting separately from the user's opt-in decision. */
    suspend fun setCloudSyncSetting(enabled: Boolean): GoogleServiceResult = provider.setCloudSyncSetting(enabled)

    private suspend fun diagnostic(peer: PeerAddress, sessionId: Long, enabled: Boolean): GoogleServiceResult {
        val scope = ensureSession(peer, sessionId)
        var offset = 0
        var newest: DataItemRecord? = null
        do {
            val page = data.items(scope, DataItemQuery(CompanionDataInitializer.GMS_ROUTE, limit = 500, offset = offset))
            for (item in page.items) if (runCatching { URI(item.uri).path == DIAGNOSTIC_PATH }.getOrDefault(false)) {
                if (newest == null || item.receivedAtEpochMillis >= newest.receivedAtEpochMillis) newest = item
            }
            offset = page.nextOffset ?: break
        } while (true)
        val encoded = GoogleAccountConsentCodec.diagnosticSetting(newest?.data, enabled)
        ensureSession(peer, sessionId)
        data.publish(scope, CompanionDataInitializer.GMS_ROUTE,
            DataItemDraft("wear://${scope.localNodeId}$DIAGNOSTIC_PATH", encoded, lastModifiedMillis = clock.millis()))
        log(peer, sessionId, "diagnostic_consent_published", mapOf("enabled" to enabled.toString(), "bytes" to encoded.size.toString(), "remoteCompletion" to "unverified"))
        return GoogleServiceResult(GoogleServiceStatus.COMPLETE, "diagnostic_consent", "persisted_and_submitted_remote_completion_unverified")
    }

    private fun ensureSession(peer: PeerAddress, sessionId: Long): DataScope {
        val state = data.state.value
        val scope = state.scope
        if (state.sessionId != sessionId || scope?.peer != peer || state.phase != DataSyncPhase.ACTIVE) {
            throw GoogleServiceException(GoogleServiceStatus.FAILED, "data_session_unavailable")
        }
        return checkNotNull(scope)
    }
    private fun log(peer: PeerAddress, sessionId: Long, event: String, details: Map<String, String>) {
        runCatching { diagnostics.record(ProtocolDiagnostic("google_services", event, peer = peer, sessionId = sessionId, details = details)) }
    }
    companion object { const val DIAGNOSTIC_PATH = "/system_logging/settings" }
}
