package dev.ticbox.companion.platform.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.WatchClientFacade
import dev.ticbox.companion.engine.recovery.*
import dev.ticbox.companion.platform.AndroidBackendFactory
import dev.ticbox.companion.platform.AndroidCompanionRuntime
import dev.ticbox.companion.platform.calling.AndroidCallAuthorizationActions
import dev.ticbox.companion.platform.sos.AndroidSosAuthorizationActions
import dev.ticbox.companion.platform.R
import dev.ticbox.companion.platform.diagnostics.AndroidProtocolDiagnostics
import dev.ticbox.companion.platform.diagnostics.BufferedDiagnosticSink
import dev.ticbox.companion.platform.migration.AndroidDataMigration
import dev.ticbox.companion.platform.recovery.AndroidRecoveryEnvironment
import dev.ticbox.companion.platform.recovery.AtomicFileConnectionIntentStore
import dev.ticbox.companion.platform.recovery.RecoveryEventBridge
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

/** Single process/session owner. Resource destruction preserves intent; disconnect pauses it. */
open class BackendSessionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val destroyed = AtomicBoolean(false)
    private val permanentlyClosed = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var engine: ChannelBackend
    private lateinit var runtime: AndroidCompanionRuntime
    private lateinit var controlled: ChannelBackend
    private lateinit var client: WatchClient
    private lateinit var recovery: RecoveryCoordinator
    private lateinit var diagnosticLog: BufferedDiagnosticSink
    protected val diagnostics: DiagnosticSink get() = diagnosticLog
    private var foreground = false
    private var retainGeneration = 0L
    private var receiverRegistered = false
    private val environmentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED || intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                restore("bluetooth_environment")
        }
    }
    private val workflowServices: CompanionServices by lazy {
        object : CompanionServices by runtime.services {
            override val recovery: ConnectionRecoveryClient = object : ConnectionRecoveryClient {
                override val state get() = this@BackendSessionService.recovery.state
                override suspend fun resume() {
                    currentCoroutineContext().ensureActive(); requireActive()
                    serviceScope.async { this@BackendSessionService.recovery.resume(); settleForeground() }.await()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        diagnosticLog = AndroidProtocolDiagnostics.create(this, serviceScope)
        runtime = createRuntime(serviceScope)
        engine = runtime.backend
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.service_channel_name), NotificationManager.IMPORTANCE_LOW))
        recovery = RecoveryCoordinator(AtomicFileConnectionIntentStore(this), AndroidRecoveryEnvironment(this, diagnostics),
            object : RecoveryLink {
                override suspend fun connect(peer: PeerAddress) {
                    requireActive()
                    if (runtime.services.unpair.state.value.busy) return
                    retainForeground()
                    val current = engine.state.value
                    if (current.peer == peer && current.phase in setOf(ConnectionPhase.CONNECTING, ConnectionPhase.NEGOTIATING,
                            ConnectionPhase.LINK_READY, ConnectionPhase.RETRYING)) return
                    runtime.startPeer(peer)
                    engine.connect(peer)
                }
                override suspend fun disconnect(userRequested: Boolean) {
                    val activePeer = engine.state.value.peer != null || engine.state.value.phase != ConnectionPhase.IDLE
                    try { engine.disconnect() } finally {
                        // A passive permission/bond check during initial binding must not cancel
                        // a new Pairing workflow that has not selected a physical session yet.
                        if (activePeer || userRequested) runtime.stopPeer()
                    }
                }
                override fun associationTerminated(peer: PeerAddress): Boolean {
                    val current = engine.state.value
                    return current.peer == peer && current.phase == ConnectionPhase.FAILED &&
                        current.failure?.kind == FailureKind.ASSOCIATION_TERMINATED
                }
                override suspend fun releaseTerminatedPeer() { runtime.stopPeer() }
                override suspend fun waitForBluetooth() { requireActive(); retainForeground() }
            }, diagnostics)
        controlled = object : ChannelBackend {
            override val state get() = engine.state
            override val events get() = engine.events
            override suspend fun connect(peer: PeerAddress) {
                currentCoroutineContext().ensureActive(); requireActive()
                if (runtime.services.unpair.state.value.busy) throw PairingBusyException()
                // Commands remain service-owned when a UI coroutine stops awaiting them.
                serviceScope.async { recovery.connect(peer); settleForeground() }.await()
            }
            override suspend fun disconnect() {
                currentCoroutineContext().ensureActive(); requireActive()
                serviceScope.async { recovery.disconnect(); settleForeground() }.await()
            }
            override suspend fun sendMessage(path: String, payload: ByteArray): TransmissionReceipt {
                requireActive(); return engine.sendMessage(path, payload)
            }
            override suspend fun openChannel(path: String, allowOverMetered: Boolean): WearChannel {
                requireActive(); return engine.openChannel(path, allowOverMetered)
            }
            override suspend fun acceptChannel(): WearChannel { requireActive(); return engine.acceptChannel() }
            override fun close() {
                if (!permanentlyClosed.compareAndSet(false, true)) return
                recovery.close()
                try { runtime.close() } finally { diagnosticLog.close() }
                mainHandler.post { finishForeground() }
            }
        }
        client = WatchClientFacade(serviceScope, controlled, diagnostics = diagnostics, history = runtime.history,
            cloudSettings = { runtime.cloudSettings.state.value }, messageSource = runtime.backend)
        registerReceiver(environmentReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }, Context.RECEIVER_EXPORTED) // Bluetooth system broadcasts may come from a privileged Bluetooth UID.
        receiverRegistered = true
        RecoveryEventBridge.listener = { trigger -> restore(trigger) }
        // An association made for the watch already in use (permission sync, or after an import)
        // reaches the saved choice now rather than at the next wake: presence wakes need its ID.
        serviceScope.launch {
            var handled = 0L
            runtime.services.pairing.state.collect { ended ->
                if (ended.operationId == handled || ended.phase !in PAIRING_ENDED || ended.associationId == null) return@collect
                handled = ended.operationId
                val saved = recovery.state.value
                if (ended.peer != null && ended.peer == saved.desiredPeer && saved.associationId == null) restore("association_created")
            }
        }
        serviceScope.launch { engine.state.collect { observed ->
            if (observed.phase == ConnectionPhase.FAILED && observed.failure?.kind == FailureKind.ASSOCIATION_TERMINATED) {
                observed.peer?.let { peer ->
                    recovery.onAssociationTerminated(peer) {
                        val current = engine.state.value
                        current.peer == peer && current.sessionId == observed.sessionId &&
                            current.phase == ConnectionPhase.FAILED && current.failure?.kind == FailureKind.ASSOCIATION_TERMINATED
                    }
                    settleForeground()
                }
            }
            if (foreground) manager.notify(NOTIFICATION_ID, notification(engine.state.value.phase))
        } }
        serviceScope.launch { recovery.state.collect { if (foreground) manager.notify(NOTIFICATION_ID, notification(engine.state.value.phase)) } }
    }

    protected open fun createRuntime(scope: CoroutineScope): AndroidCompanionRuntime =
        AndroidBackendFactory.createRuntime(this, scope, diagnostics = diagnostics, connectPeer = { controlled.connect(it) },
            findPlaybackForeground = ::setFindPlaybackForeground, disconnectPeer = { controlled.disconnect() },
            forgetPeer = { serviceScope.async { recovery.forget(); settleForeground() }.await() })

    override fun onBind(intent: Intent): IBinder {
        restore("bound_host")
        return LocalBinder()
    }

    /** Import stops this service for good; the app starts again afterwards. */
    private val migration: DataMigration by lazy {
        AndroidDataMigration(this, runtime.database, stopBackend = {
            withContext(Dispatchers.Main.immediate) { controlled.close() }
            runtime.database.awaitClosed()
        })
    }

    inner class LocalBinder : Binder() {
        val client: WatchClient get() = this@BackendSessionService.client
        val migration: DataMigration get() = this@BackendSessionService.migration
        val services: CompanionServices get() = workflowServices
        val pairingActions get() = runtime.pairingActions
        val googleActions get() = runtime.googleActions
        val callingActions: AndroidCallAuthorizationActions get() = runtime.callingActions
        val sosActions: AndroidSosAuthorizationActions get() = runtime.sosActions
        internal val backend: ChannelBackend get() = controlled
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_RING) {
            serviceScope.launch { workflowServices.finding.stopPhone() }
            return START_STICKY
        }
        if (intent?.action == ACTION_KEEP_ALIVE) {
            if (intent.getLongExtra(EXTRA_RETAIN_GENERATION, -1) != retainGeneration) {
                if (!foreground) stopSelf(startId)
                return START_STICKY
            }
        } else if (intent != null && intent.action != ACTION_RESTORE) {
            if (!foreground) stopSelf(startId)
            return START_NOT_STICKY
        }
        // Promote before disk IO. BLUETOOTH_CONNECT satisfies the connectedDevice prerequisite
        // even when the adapter is temporarily disabled.
        try {
            requireActive()
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
                ensureForeground(alreadyStarted = true)
            else stopSelf(startId)
        } catch (problem: Exception) {
            log("foreground_restore_refused", problem)
            if (!foreground) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_KEEP_ALIVE) restore(
            intent?.getStringExtra(EXTRA_TRIGGER) ?: "sticky_restart", intent?.getStringExtra(EXTRA_REVISION),
        )
        return START_STICKY
    }

    private fun restore(trigger: String, revision: String? = null) {
        if (destroyed.get() || permanentlyClosed.get()) return
        serviceScope.launch { recovery.restore(trigger, revision); settleForeground() }
    }

    private fun settleForeground() {
        when (recovery.state.value.phase) {
            ConnectionRecoveryPhase.IDLE, ConnectionRecoveryPhase.WAITING_PERMISSION,
            ConnectionRecoveryPhase.WAITING_ASSOCIATION, ConnectionRecoveryPhase.WAITING_BOND,
            ConnectionRecoveryPhase.WAITING_START_ALLOWED, ConnectionRecoveryPhase.FAILED,
            ConnectionRecoveryPhase.CLOSED -> finishForeground()
            else -> Unit // Keep an existing owner while Bluetooth is switched off.
        }
    }

    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>?) {
        super.dump(fd, writer, args)
        if (!::runtime.isInitialized) {
            writer.println("TicBox notification sync: runtime not initialized")
            return
        }
        notificationDiagnostics(runtime.backend.state.value, runtime.services.data.state.value,
            runtime.services.notifications.state.value).forEach(writer::println)
    }

    override fun onDestroy() {
        destroyed.set(true)
        RecoveryEventBridge.listener = null
        if (receiverRegistered) unregisterReceiver(environmentReceiver)
        if (::client.isInitialized) client.close() else if (::runtime.isInitialized) runtime.close()
        if (::diagnosticLog.isInitialized) diagnosticLog.close()
        serviceScope.cancel()
        finishForeground()
        super.onDestroy()
    }

    private fun requireActive() { if (destroyed.get() || permanentlyClosed.get()) throw BackendClosedException() }

    private fun ensureForeground(alreadyStarted: Boolean) {
        if (foreground) return
        retainGeneration += 1
        if (!alreadyStarted) startForegroundService(Intent(this, javaClass).setAction(ACTION_KEEP_ALIVE)
            .putExtra(EXTRA_RETAIN_GENERATION, retainGeneration))
        try { startForeground(NOTIFICATION_ID, notification(engine.state.value.phase), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) }
        catch (problem: Exception) { retainGeneration += 1; throw problem }
        foreground = true
    }

    private fun retainForeground() {
        try { ensureForeground(alreadyStarted = false) }
        catch (problem: android.app.ForegroundServiceStartNotAllowedException) { throw RecoveryStartNotAllowedException(problem) }
    }

    private fun finishForeground() {
        retainGeneration += 1
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false }
        stopSelf()
    }

    private var findPlayback = false
    private fun setFindPlaybackForeground(enabled: Boolean) {
        if (enabled) {
            requireActive()
            ensureForeground(alreadyStarted = false)
            startForeground(NOTIFICATION_ID, notification(engine.state.value.phase),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            findPlayback = true
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(engine.state.value.phase))
        } else if (findPlayback) {
            findPlayback = false
            if (foreground && !destroyed.get()) startForeground(NOTIFICATION_ID, notification(engine.state.value.phase),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }
    }

    /** Built the way the launcher builds it, so an open app comes forward as it was left. */
    private fun openApp(): PendingIntent? = packageManager.getLaunchIntentForPackage(packageName)?.component?.let {
        PendingIntent.getActivity(this, 1, Intent.makeMainActivity(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun notification(phase: ConnectionPhase): Notification {
        val waiting = if (::recovery.isInitialized) recovery.state.value.phase else null
        val text = getString(when (waiting) {
            ConnectionRecoveryPhase.WAITING_BLUETOOTH -> R.string.service_waiting_bluetooth
            ConnectionRecoveryPhase.WAITING_PERMISSION -> R.string.service_permission_required
            ConnectionRecoveryPhase.WAITING_ASSOCIATION, ConnectionRecoveryPhase.WAITING_BOND -> R.string.service_pairing_attention
            else -> when (phase) {
                ConnectionPhase.IDLE -> R.string.service_preparing
                ConnectionPhase.CONNECTING, ConnectionPhase.NEGOTIATING -> R.string.service_connecting
                ConnectionPhase.LINK_READY -> R.string.service_connected
                ConnectionPhase.RETRYING -> R.string.service_reconnecting
                ConnectionPhase.FAILED -> R.string.service_attention
            }
        })
        val builder = Notification.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_backend_watch)
            .setContentTitle(getString(R.string.service_title))
            .setContentText(if (findPlayback) getString(R.string.service_find_phone_ringing) else text).setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true).setOngoing(true).setContentIntent(openApp())
        if (findPlayback) {
            val stop = PendingIntent.getService(this, 2, Intent(this, javaClass).setAction(ACTION_STOP_RING),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, getString(R.string.service_action_stop_ringing), stop).build())
        }
        return builder.build()
    }

    private fun log(event: String, problem: Exception) {
        runCatching { diagnostics.record(ProtocolDiagnostic("recovery", event,
            details = mapOf("exception" to problem.javaClass.simpleName))) }
    }

    companion object {
        private const val CHANNEL_ID = "watch_connection"
        private const val NOTIFICATION_ID = 107
        private const val EXTRA_RETAIN_GENERATION = "retain_generation"
        private const val EXTRA_REVISION = "intent_revision"
        private const val EXTRA_TRIGGER = "restore_trigger"
        private const val ACTION_KEEP_ALIVE = "dev.ticbox.companion.action.KEEP_ALIVE"
        private const val ACTION_RESTORE = "dev.ticbox.companion.action.RESTORE"
        private const val ACTION_STOP_RING = "dev.ticbox.companion.action.STOP_RING"
        private val PAIRING_ENDED = setOf(PairingPhase.COMPLETED, PairingPhase.FAILED, PairingPhase.CANCELLED)

        internal fun restoreIntent(context: Context, revision: String, trigger: String): Intent =
            Intent(context, BackendSessionService::class.java).setAction(ACTION_RESTORE)
                .putExtra(EXTRA_REVISION, revision).putExtra(EXTRA_TRIGGER, trigger)

        /** Optional unbound visible-host entry. Persists intent even if Android refuses this start. */
        suspend fun start(context: Context, peer: PeerAddress) {
            val environment = AndroidRecoveryEnvironment(context)
            val candidate = ConnectionIntent.selected(peer)
            val association = try { environment.inspect(candidate).associationId } catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                null
            }
            val selected = candidate.copy(associationId = association)
            AtomicFileConnectionIntentStore(context).write(selected)
            context.startForegroundService(restoreIntent(context, selected.revision, "visible_host_start"))
        }
    }
}
