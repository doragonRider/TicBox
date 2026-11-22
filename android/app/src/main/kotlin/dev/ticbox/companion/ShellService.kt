package dev.ticbox.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import dev.ticbox.companion.ui.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the process, and so the connection and shell it holds, alive while no page is on screen. It
 * runs for as long as the shell does. `connectedDevice` needs no Bluetooth permission here, because
 * `CHANGE_WIFI_MULTICAST_STATE` also meets its requirement, and a shell over wireless debugging may
 * never have been given Bluetooth.
 */
class ShellService : Service() {
    private val shell
        get() = (application as CompanionApplication).shell

    private val scope = MainScope()
    private var watching: Job? = null

    private val wakeLock by lazy {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ticbox:shell")
            .apply { setReferenceCounted(false) }
    }

    /** Without this a shell over wireless debugging can lose its connection with the screen off. */
    private val wifiLock by lazy {
        getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ticbox:shell")
            .apply { setReferenceCounted(false) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.terminal_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXIT -> shell.stop()
            ACTION_WAKE_LOCK -> if (wakeLock.isHeld) releaseLocks() else acquireLocks()
        }
        try {
            startForeground(
                NOTIFICATION_ID,
                notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (problem: IllegalStateException) {
            return refused(problem)
        } catch (problem: SecurityException) {
            return refused(problem)
        }
        if (watching == null) {
            watching = scope.launch {
                shell.state.first { !it.running }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    private fun refused(problem: Exception): Int {
        Log.w(TAG, "The shell can't run in the background", problem)
        stopSelf()
        return START_NOT_STICKY
    }

    private fun acquireLocks() {
        // Held on purpose until it's released or the shell ends: asked for by name, like Termux's.
        wakeLock.acquire()
        wifiLock.acquire()
    }

    private fun releaseLocks() {
        if (wakeLock.isHeld) wakeLock.release()
        if (wifiLock.isHeld) wifiLock.release()
    }

    private fun notification(): Notification {
        val held = wakeLock.isHeld
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_code)
            .setContentTitle(getString(R.string.terminal_notification_title))
            .apply { if (held) setContentText(getString(R.string.terminal_notification_wake_lock)) }
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openTerminal())
            .addAction(action(R.string.terminal_action_exit, ACTION_EXIT))
            .addAction(
                action(
                    if (held) R.string.terminal_action_release_wake_lock
                    else R.string.terminal_action_acquire_wake_lock,
                    ACTION_WAKE_LOCK,
                )
            )
            .build()
    }

    private fun action(label: Int, action: String): Notification.Action {
        val intent =
            PendingIntent.getService(
                this,
                action.hashCode(),
                Intent(this, ShellService::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification.Action.Builder(null, getString(label), intent).build()
    }

    /** Built the way the launcher builds it, so an open app comes forward as it was left. */
    private fun openTerminal(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent.makeMainActivity(ComponentName(this, MainActivity::class.java))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_SHOW_SHELL, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private companion object {
        const val TAG = "ShellService"
        const val CHANNEL_ID = "shell"
        const val NOTIFICATION_ID = 108
        const val ACTION_EXIT = "dev.ticbox.companion.action.SHELL_EXIT"
        const val ACTION_WAKE_LOCK = "dev.ticbox.companion.action.SHELL_WAKE_LOCK"
    }
}
