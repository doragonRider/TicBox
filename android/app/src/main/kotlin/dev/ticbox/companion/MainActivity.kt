package dev.ticbox.companion

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.ticbox.companion.api.CallAuthorizationAction
import dev.ticbox.companion.platform.calling.AndroidCallAuthorizationLaunch
import dev.ticbox.companion.platform.notification.NotificationAccess
import dev.ticbox.companion.platform.sos.AndroidSosAuthorizationLaunch
import dev.ticbox.companion.platform.sos.SosAuthorizationAction
import dev.ticbox.companion.ui.CompanionApp
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.SosAccess
import dev.ticbox.companion.ui.SystemActions
import dev.ticbox.companion.ui.common.uiText
import dev.ticbox.companion.ui.session.readySession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), SystemActions {
    private val app
        get() = application as CompanionApplication

    private lateinit var pairing: SystemPairingHost
    private lateinit var google: SystemGoogleHost

    private val notificationAccess =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            NotificationAccess.requestRebind(this)
            val session = app.sessions.state.value.readySession ?: return@registerForActivityResult
            app.applicationScope.launch {
                runCatching { session.services.notifications.resync() }
                    .onFailure { Log.w(TAG, "Notification resync failed", it) }
            }
        }

    private val callingPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val session = app.sessions.state.value.readySession ?: return@registerForActivityResult
            app.applicationScope.launch {
                runCatching { session.services.calling.refreshAccess() }
                    .onFailure { Log.w(TAG, "Refreshing call access failed", it) }
            }
        }

    private val sosPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val session = app.sessions.state.value.readySession ?: return@registerForActivityResult
            app.applicationScope.launch {
                runCatching { session.services.sos.phoneAssistance.refreshCapabilities() }
                    .onFailure { Log.w(TAG, "Refreshing SOS access failed", it) }
            }
        }

    private val shellNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            leaveShellRunning()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) showShellIfAsked(intent)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pairing = SystemPairingHost(this)
        google = SystemGoogleHost(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                app.sessions.binder.collect { binder ->
                    pairing.detach()
                    google.detach()
                    if (binder != null) {
                        pairing.attach(binder.pairingActions)
                        google.attach(binder.googleActions)
                    }
                }
            }
        }
        // Pull new health records when the app comes to the foreground, unless a sync just ran.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.sessions.state
                    .map { it.readySession }
                    .distinctUntilChanged()
                    .collect { session ->
                        try {
                            session?.services?.localHealth?.syncIfStale()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (problem: Exception) {
                            Log.w(TAG, "Starting health sync failed", problem)
                        }
                    }
            }
        }
        setContent { CompanionApp(app, this) }
    }

    override fun openNotificationAccess() {
        try {
            notificationAccess.launch(NotificationAccess.settingsIntent(this))
        } catch (_: ActivityNotFoundException) {
            app.messages.show(uiText(R.string.error_open_settings))
        }
    }

    override fun openAppNotificationSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        } catch (_: ActivityNotFoundException) {
            app.messages.show(uiText(R.string.error_open_settings))
        }
    }

    override fun requestCallingAccess(action: CallAuthorizationAction) {
        val binder = app.sessions.binder.value
        when (val launch = binder?.callingActions?.prepareAuthorization(action)) {
            is AndroidCallAuthorizationLaunch.Permissions ->
                callingPermissions.launch(launch.permissions.toTypedArray())
            is AndroidCallAuthorizationLaunch.Activity ->
                try {
                    startActivity(launch.intent)
                } catch (_: ActivityNotFoundException) {
                    app.messages.show(uiText(R.string.error_open_settings))
                }
            AndroidCallAuthorizationLaunch.PairWatch,
            is AndroidCallAuthorizationLaunch.Unavailable,
            null -> app.messages.show(uiText(R.string.error_open_settings))
        }
    }

    override fun requestSosAccess(access: SosAccess) {
        val binder = app.sessions.binder.value
        val action = SosAuthorizationAction.valueOf(access.name)
        when (val launch = binder?.sosActions?.prepareAuthorization(action)) {
            is AndroidSosAuthorizationLaunch.Permissions ->
                sosPermissions.launch(launch.permissions.toTypedArray())
            is AndroidSosAuthorizationLaunch.Activity ->
                try {
                    startActivity(launch.intent)
                } catch (_: ActivityNotFoundException) {
                    app.messages.show(uiText(R.string.error_open_settings))
                }
            is AndroidSosAuthorizationLaunch.Unavailable,
            null -> app.messages.show(uiText(R.string.error_open_settings))
        }
    }

    override fun sosSmsSubscriptions(): List<Int> =
        app.sessions.binder.value?.sosActions?.availableSmsSubscriptions().orEmpty()

    /** The notification is the only way back to the shell, or to end it, once the app is gone. */
    override fun keepShellRunning() {
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            leaveShellRunning()
        } else {
            shellNotifications.launch(permission)
        }
    }

    private fun leaveShellRunning() {
        keepShellAlive()
        moveTaskToBack(true)
    }

    /** Android freezes a process nothing visible needs, which would quietly end the shell. */
    private fun keepShellAlive() {
        if (!app.shell.state.value.running) return
        try {
            startForegroundService(Intent(this, ShellService::class.java))
        } catch (problem: IllegalStateException) {
            Log.w(TAG, "The shell can't run in the background", problem)
        }
    }

    /** Called while still in front, where a foreground service can certainly be started. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        keepShellAlive()
    }

    /** For leaving without a hint, such as the screen turning off. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) keepShellAlive()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showShellIfAsked(intent)
    }

    private fun showShellIfAsked(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_SHOW_SHELL, false)) app.shell.show()
    }

    override fun onDestroy() {
        pairing.close()
        google.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
        const val EXTRA_SHOW_SHELL = "dev.ticbox.companion.extra.SHOW_SHELL"
    }
}
