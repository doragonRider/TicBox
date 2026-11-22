package dev.ticbox.companion.platform

import android.content.Context
import dev.ticbox.companion.api.ApplicationRoute
import dev.ticbox.companion.api.DiagnosticSink
import dev.ticbox.companion.engine.SessionConfig
import kotlinx.coroutines.CoroutineScope

object AndroidBackendFactory {
    /**
     * These strings reproduce the official watch firmware's wire AppKey. They do not change this APK's
     * package/signing certificate or confer GMS Binder, system or CDM permissions.
     */
    val OFFICIAL_ROUTE = ApplicationRoute(
        "com.mobvoi.companion.at",
        "5cc9866db03e530a21f842a421c7eb42bac58961",
    )

    /** Complete service graph, owned by BackendSessionService. The diagnostic sink is caller-owned. */
    fun createRuntime(
        context: Context,
        scope: CoroutineScope,
        route: ApplicationRoute = OFFICIAL_ROUTE,
        config: SessionConfig = SessionConfig(),
        diagnostics: DiagnosticSink,
        connectPeer: (suspend (dev.ticbox.companion.api.PeerAddress) -> Unit)? = null,
        cloudConfig: dev.ticbox.companion.engine.cloud.MobvoiCloudConfig = dev.ticbox.companion.engine.cloud.MobvoiCloudConfig(),
        findPlaybackForeground: (Boolean) -> Unit = {},
        disconnectPeer: (suspend () -> Unit)? = null,
        forgetPeer: (suspend () -> Unit)? = null,
    ): AndroidCompanionRuntime = AndroidCompanionRuntime(context, scope, route, config, diagnostics, connectPeer, cloudConfig, findPlaybackForeground, disconnectPeer, forgetPeer)

}
