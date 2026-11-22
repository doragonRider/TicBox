package dev.ticbox.companion

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import dev.ticbox.companion.api.WatchToolboxClient
import dev.ticbox.companion.demo.DemoWatch
import dev.ticbox.companion.platform.service.BackendSessionService
import dev.ticbox.companion.ui.WatchUiServices
import dev.ticbox.companion.ui.forUi
import dev.ticbox.companion.ui.session.SessionController
import dev.ticbox.companion.ui.session.SessionState
import dev.ticbox.companion.ui.session.WatchSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Binds the local backend service for the whole process and offers the demo watch as an alternative
 * session. The demo only changes what the UI shows; the real service stays bound.
 */
class ServiceSessionController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val localProfileId: () -> String,
) : SessionController {
    private sealed interface Binding {
        data object Connecting : Binding

        data object Failed : Binding

        data class Bound(val session: WatchSession) : Binding {
            // The service owns ADB. Demo screens borrow it without exposing the real watch bridge.
            val demoToolbox =
                object : WatchToolboxClient {
                    override val adb = session.services.toolbox.adb
                    override val localBridge = WatchToolboxClient.UNAVAILABLE.localBridge

                    override fun close() = Unit
                }
        }
    }

    private val binding = MutableStateFlow<Binding>(Binding.Connecting)
    private val demo = MutableStateFlow<WatchSession?>(null)
    private var demoWatch: DemoWatch? = null
    private var connection: ServiceConnection? = null

    private val boundBinder = MutableStateFlow<BackendSessionService.LocalBinder?>(null)

    /** The real service binder, for Activity hosts that launch system pairing screens. */
    val binder: StateFlow<BackendSessionService.LocalBinder?> = boundBinder.asStateFlow()

    override val state: StateFlow<SessionState> =
        combine(binding, demo) { binding, demo ->
                when {
                    demo != null ->
                        SessionState.Ready(
                            if (binding is Binding.Bound) {
                                demo.copy(
                                    services = demo.services.copy(toolbox = binding.demoToolbox)
                                )
                            } else demo
                        )
                    binding is Binding.Bound -> SessionState.Ready(binding.session)
                    binding is Binding.Failed -> SessionState.Unavailable
                    else -> SessionState.Connecting
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, SessionState.Connecting)

    fun bind() {
        if (connection != null) return
        binding.value = Binding.Connecting
        val attempt =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    if (connection !== this) return
                    val local = service as? BackendSessionService.LocalBinder ?: return fail(this)
                    boundBinder.value = local
                    binding.value =
                        Binding.Bound(
                            WatchSession(
                                key = SERVICE_KEY,
                                isDemo = false,
                                client = local.client,
                                services = local.services.forUi(),
                                localProfileId = localProfileId(),
                            )
                        )
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    // The system rebinds automatically; the UI waits meanwhile.
                    if (connection !== this) return
                    boundBinder.value = null
                    binding.value = Binding.Connecting
                }

                override fun onBindingDied(name: ComponentName) {
                    if (connection !== this) return
                    release(this)
                    bind()
                }

                override fun onNullBinding(name: ComponentName) = fail(this)
            }
        connection = attempt
        val bound =
            try {
                context.bindService(
                    Intent(context, BackendSessionService::class.java),
                    attempt,
                    Context.BIND_AUTO_CREATE,
                )
            } catch (problem: Exception) {
                Log.w(TAG, "Binding the backend service failed", problem)
                false
            }
        if (!bound) fail(attempt)
    }

    override fun retry() {
        connection?.let(::release)
        bind()
    }

    override fun enterDemo() {
        if (demo.value != null) return
        val watch = DemoWatch(context, scope).also { demoWatch = it }
        demo.value =
            WatchSession(
                key = DEMO_KEY,
                isDemo = true,
                client = watch.client,
                services =
                    WatchUiServices(
                        privacy = watch.privacy,
                        pairing = watch.pairing,
                        setup = watch.setup,
                        notifications = watch.notifications,
                        media = watch.media,
                        finding = watch.finding,
                        deviceSettings = watch.deviceSettings,
                        watchFaces = watch.watchFaces,
                        recovery = watch.recovery,
                        history = watch.history,
                        healthRecords = watch.healthRecords,
                        localHealth = watch.localHealth,
                        data = watch.data,
                        calling = watch.calling,
                        network = watch.network,
                        account = watch.account,
                        cloudHealth = watch.cloudHealth,
                        google = watch.google,
                        monitoring = watch.monitoring,
                        sos = watch.sos,
                        healthModule = watch.healthModule,
                        notificationAlertPatch = watch.notificationAlertPatch,
                        watchModuleHost = watch.watchModuleHost,
                    ),
                localProfileId = localProfileId(),
            )
    }

    override fun exitDemo() {
        demo.value = null
        demoWatch = null
    }

    fun onLocaleChanged() {
        demoWatch?.onLocaleChanged()
    }

    private fun fail(attempt: ServiceConnection) {
        if (connection !== attempt) return
        release(attempt)
        binding.value = Binding.Failed
    }

    private fun release(attempt: ServiceConnection) {
        if (connection === attempt) connection = null
        boundBinder.value = null
        try {
            context.unbindService(attempt)
        } catch (_: IllegalArgumentException) {
            // Never bound; nothing to release. The service owns its clients either way.
        }
    }

    private companion object {
        const val TAG = "ServiceSession"
        const val SERVICE_KEY = "service"
        const val DEMO_KEY = "demo"
    }
}
