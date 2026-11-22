package dev.ticbox.companion.platform.sos

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.CancellationSignal
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.telephony.SmsManager
import dev.ticbox.companion.api.*
import dev.ticbox.companion.platform.R
import java.util.Locale
import kotlinx.coroutines.*

/** User-enabled phone contact SMS; the watch's own telephony implementation owns emergency calls. */
class SosAssistanceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var store: AtomicFileSosAssistanceStore
    private lateinit var session: SosAssistanceSession
    private var started = false

    override fun onCreate() {
        super.onCreate()
        store = AtomicFileSosAssistanceStore(this)
        val device = AndroidSosDevice(this)
        session = SosAssistanceSession(scope, store, device, SosAssistanceRegistry.state)
        val requestedEvent = SosAssistanceRegistry.state.value.eventId
        try { device.foreground(true); started = true }
        catch (problem: Exception) {
            val reason = if (problem is SecurityException) "sos_foreground_permission_required" else "sos_foreground_start_failed"
            scope.launch {
                try { runCatching { recordSosStartFailure(store, SosAssistanceRegistry.state, requestedEvent, reason) } }
                finally { stopSelf() }
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) return START_NOT_STICKY
        val requestedEvent = SosAssistanceRegistry.state.value.eventId
        scope.launch {
            try {
                if (intent?.action == ACTION_STOP) session.stop() else session.start()
            } catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                runCatching { recordSosStartFailure(store, SosAssistanceRegistry.state,
                    requestedEvent, "sos_execution_failed") }
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
        // A system recreation resumes only a durable, explicitly accepted alert. The journal
        // reserves each SMS attempt before submission, preventing initial-message replay.
        return START_STICKY
    }
    override fun onDestroy() {
        if (::session.isInitialized) session.release()
        scope.cancel()
        super.onDestroy()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        scope.launch { session.unavailable("sos_foreground_timeout") }
    }
    companion object { internal const val ACTION_STOP = "dev.ticbox.companion.sos.STOP" }
}

/** Retain an accepted event's terminal startup result for the facade after process recreation.
 * This does not resume SMS work and cannot overwrite a newer event or an explicit stop. */
internal suspend fun recordSosStartFailure(store: SosAssistanceStore,
    state: kotlinx.coroutines.flow.MutableStateFlow<SosAssistanceState>, eventId: String?, reason: String) {
    SosAssistanceOwnership.commands.lock()
    try {
        val alert = store.active() ?: return
        if (eventId != null && alert.request.eventId != eventId) return
        state.value = state.value.copy(phase = SosAssistancePhase.FAILED, eventId = alert.request.eventId,
            recipientCount = alert.request.contacts.size, startedAtMillis = alert.startedAtMillis,
            expiresAtMillis = alert.expiresAtMillis, smsPartsSubmitted = alert.partsSubmitted,
            skippedRecipientCount = alert.skippedRecipientCount, issue = SosIssue("phone_assistance", reason))
        store.saveActive(alert.copy(failureReason = reason))
    } finally { SosAssistanceOwnership.commands.unlock() }
}

internal class AndroidSosDevice(private val service: Service) : SosAssistanceDevice {
    override fun capabilities(config: SosPhoneAssistanceConfig) = sosCapabilities(service, config)
    override fun subscription(config: SosPhoneAssistanceConfig) = selectedSmsSubscription(service, config)

    @SuppressLint("MissingPermission") // Checked before every message, including mid-session revocation.
    override fun send(subscriptionId: Int, destination: String, kind: SosSmsKind, location: SosLocationSample?): Int {
        if (service.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
            throw SosPlatformFailure("sms_permission_required")
        require(smsNumber(destination))
        val system = service.getSystemService(SmsManager::class.java) ?: throw SosPlatformFailure("sms_not_supported")
        val sms = system.createForSubscriptionId(subscriptionId)
        val body = sosSmsText(kind, location, lowBatteryPercent())
        val parts = sms.divideMessage(body)
        require(parts.size in 1..16)
        val number = normalizedSmsNumber(destination)
        if (parts.size == 1) sms.sendTextMessage(number, null, parts.single(), null, null)
        else sms.sendMultipartTextMessage(number, null, parts, null, null)
        return parts.size
    }

    @SuppressLint("MissingPermission") // Foreground/background grants are checked by the session and by Android.
    override fun locations(onLocation: (SosLocationSample) -> Unit): AutoCloseable {
        val manager = service.getSystemService(LocationManager::class.java) ?: throw SosPlatformFailure("location_unavailable")
        if (!manager.isLocationEnabled) throw SosPlatformFailure("location_disabled")
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { manager.hasProvider(it) && manager.isProviderEnabled(it) } ?: throw SosPlatformFailure("location_provider_unavailable")
        val listener = LocationListener { location -> sample(location)?.let(onLocation) }
        val cancelled = CancellationSignal()
        try {
            manager.requestLocationUpdates(provider, 10 * 60 * 1000L, 30f, listener, Looper.getMainLooper())
            manager.getCurrentLocation(provider, cancelled, service.mainExecutor) { location -> location?.let(::sample)?.let(onLocation) }
        } catch (problem: Exception) {
            cancelled.cancel(); runCatching { manager.removeUpdates(listener) }; throw problem
        }
        return AutoCloseable { cancelled.cancel(); runCatching { manager.removeUpdates(listener) } }
    }
    private fun sample(location: Location): SosLocationSample? {
        val ageMillis = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        if (location.elapsedRealtimeNanos <= 0 || ageMillis !in 0..SosAssistanceSession.LOCATION_MAX_AGE_MILLIS) return null
        if (!location.latitude.isFinite() || !location.longitude.isFinite() || location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return null
        return SosLocationSample(location.latitude, location.longitude, System.currentTimeMillis() - ageMillis)
    }
    override fun foreground(active: Boolean, location: Boolean) {
        if (!active) { service.stopForeground(Service.STOP_FOREGROUND_REMOVE); service.stopSelf(); return }
        val manager = service.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, if (chinese()) "SOS 紧急联系人援助" else "SOS contact assistance", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(service, 0, Intent(service, SosAssistanceService::class.java).setAction(SosAssistanceService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(service, CHANNEL)
            .setSmallIcon(R.drawable.ic_backend_watch)
            .setContentTitle(if (chinese()) "SOS 紧急联系人援助已启动" else "SOS contact assistance active")
            .setContentText(if (chinese()) "紧急联系人短信援助正在运行。可随时停止。" else "Emergency-contact SMS assistance is running. You can stop at any time.")
            .setCategory(Notification.CATEGORY_SERVICE).setOnlyAlertOnce(true).setOngoing(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .addAction(Notification.Action.Builder(null, if (chinese()) "停止" else "Stop", stop).build()).build()
        service.startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            if (location) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
    }
    private fun lowBatteryPercent(): Int? {
        val battery = service.getSystemService(BatteryManager::class.java) ?: return null
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level.takeIf { !battery.isCharging && it in 0..19 }
    }
    private fun chinese() = service.resources.configuration.locales[0]?.language == "zh"
    internal fun sosSmsText(kind: SosSmsKind, location: SosLocationSample?, lowBattery: Int?): String {
        val text = if (chinese()) when (kind) {
            SosSmsKind.INITIAL -> "我的 TicWatch 已触发紧急求助。我可能需要帮助，请联系我。"
            SosSmsKind.LOCATION -> "TicWatch 紧急求助：我的位置已更新。"
            SosSmsKind.FINISHED -> "TicWatch 紧急联系人援助已达到 24 小时上限并结束。"
        } else when (kind) {
            SosSmsKind.INITIAL -> "My TicWatch has triggered an emergency alert. I may need help. Please contact me."
            SosSmsKind.LOCATION -> "TicWatch emergency alert: my location has updated."
            SosSmsKind.FINISHED -> "TicWatch emergency-contact assistance has reached its 24-hour limit and ended."
        }
        val battery = if (kind == SosSmsKind.INITIAL && lowBattery != null) {
            if (chinese()) " 手机电量：$lowBattery%。" else " Phone battery: $lowBattery%."
        } else ""
        val map = location?.let { String.format(Locale.US, " https://maps.google.com/?q=@%.6f,%.6f", it.latitude, it.longitude) }.orEmpty()
        return text + battery + map
    }
    private companion object { const val CHANNEL = "sos_assistance"; const val NOTIFICATION = 109 }
}
