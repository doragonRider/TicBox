package dev.ticbox.companion.platform.google

import android.content.Context
import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.google.GoogleServicePolicy
import dev.ticbox.companion.engine.google.GoogleServicesEngine
import kotlinx.coroutines.CoroutineScope

/** Runtime-owned integration. Construction performs no bind, account lookup, or network call. */
class AndroidGoogleServices private constructor(private val components: Components) : GoogleServices by components.engine {
    val hostActions: AndroidGoogleActions get() = components.actions
    private class Components(val engine: GoogleServicesEngine, val actions: AndroidGoogleActions)
    constructor(
        context: Context,
        scope: CoroutineScope,
        channels: ChannelBackend,
        data: DataSyncClient,
        diagnostics: DiagnosticSink = DiagnosticSink.NONE,
        policy: GoogleServicePolicy = GoogleServicePolicy(),
        resolutionLauncher: GoogleResolutionLauncher? = null,
    ) : this(create(context, scope, channels, data, diagnostics, policy, resolutionLauncher))

    suspend fun handleHostActivityRequest(peer: PeerAddress, sessionId: Long, payload: ByteArray?): GoogleServiceResult =
        components.engine.handleHostActivityRequest(peer, sessionId, payload)

    private companion object {
        fun create(context: Context, scope: CoroutineScope, channels: ChannelBackend, data: DataSyncClient,
            diagnostics: DiagnosticSink, policy: GoogleServicePolicy, launcher: GoogleResolutionLauncher?): Components {
            val provider = AndroidGoogleServiceProvider(context, scope, diagnostics, launcher)
            return Components(GoogleServicesEngine(channels, provider, data, diagnostics, policy), provider.hostActions)
        }
    }
}
