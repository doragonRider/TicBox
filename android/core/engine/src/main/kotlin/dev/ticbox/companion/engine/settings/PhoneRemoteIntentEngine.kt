package dev.ticbox.companion.engine.settings

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.CompanionFeatureSession
import dev.ticbox.companion.protocol.*
import kotlinx.coroutines.CancellationException

/** Success means the OS accepted dispatch, not that the target Activity rendered or a service finished. */
fun interface PhoneRemoteIntentExecutor {
    suspend fun execute(request: RemoteIntentRequest): Boolean
}

/** Receives the phone-side half of the bidirectional /remote_intent MessageApi protocol. */
class PhoneRemoteIntentEngine(
    private val executor: PhoneRemoteIntentExecutor,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
) : AutoCloseable {
    @Volatile private var session: CompanionFeatureSession? = null
    @Volatile private var closed = false

    fun attach(next: CompanionFeatureSession) { check(!closed); session = next }
    fun detach(sessionId: Long) { if (session?.sessionId == sessionId) session = null }

    suspend fun handleMessage(peer: PeerAddress, sessionId: Long, path: String, bytes: ByteArray,
        route: ApplicationRoute? = null): Boolean {
        val current = session?.takeIf { it.peer == peer && it.sessionId == sessionId } ?: return false
        if (path != DeviceSettingsProtocol.REMOTE_INTENT || (route != null && route != current.companionRoute)) return false
        val request = try { RemoteIntentCodec.decode(bytes) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) {
            log("request_unreadable", current, mapOf("bytes" to bytes.size.toString(), "exception" to problem.javaClass.simpleName))
            return true // No recoverable callback ID; keep the main link and other handlers alive.
        }
        if (request.notices.isNotEmpty()) log("request_notices", current, mapOf(
            "notices" to request.notices.distinct().take(16).joinToString(","),
            "wireUnknownFields" to request.document.unknownFields.size.toString(),
            "fieldCount" to request.document.values.size.toString()))
        if (session !== current) return true
        val successful = try { executor.execute(request) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) {
            log("dispatch_failed", current, mapOf("mode" to request.startMode.toString(), "exception" to problem.javaClass.simpleName))
            false
        }
        if (session !== current) {
            log("result_session_replaced", current)
            return true
        }
        try {
            current.sendMessage(DeviceSettingsProtocol.REMOTE_INTENT_RESULT,
                RemoteIntentCodec.result(request.invocationId, successful))
            log("dispatch_result", current, mapOf("mode" to request.startMode.toString(), "successful" to successful.toString()))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) { log("result_send_failed", current, mapOf("exception" to problem.javaClass.simpleName)) }
        return true
    }

    private fun log(event: String, current: CompanionFeatureSession, details: Map<String, String> = emptyMap()) {
        runCatching { diagnostics.record(ProtocolDiagnostic("remote_intent", event, peer = current.peer,
            sessionId = current.sessionId, details = details)) }
    }
    override fun close() { closed = true; session = null }
}
