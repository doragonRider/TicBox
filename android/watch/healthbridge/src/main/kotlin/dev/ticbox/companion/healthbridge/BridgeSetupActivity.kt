package dev.ticbox.companion.healthbridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ticbox.companion.healthbridge.ui.BridgeSetupScreen
import dev.ticbox.companion.healthbridge.ui.BridgeTheme
import dev.ticbox.companion.healthbridge.ui.RootRequest
import java.util.concurrent.Executors

internal object BridgeSettings {
    private fun preferences(context: Context) =
        context.getSharedPreferences("local-health-bridge", Context.MODE_PRIVATE)

    fun enabled(context: Context) = preferences(context).getBoolean("enabled", false)
    fun setEnabled(context: Context, enabled: Boolean) = save(context, "enabled", enabled)
    fun rootFitnessEnabled(context: Context) = preferences(context).getBoolean("root-fitness", false)
    fun setRootFitnessEnabled(context: Context, enabled: Boolean) = save(context, "root-fitness", enabled)
    fun journalEnabled(context: Context, source: String) = preferences(context).getBoolean("journal-$source", false)
    fun setJournalEnabled(context: Context, source: String, enabled: Boolean) {
        require(source in setOf("health", "fitness"))
        save(context, "journal-$source", enabled)
    }

    // Both switches are user consent, so turning one off must survive a restart. KTX edit() hides
    // commit's result, and a failed write should crash rather than look saved.
    @SuppressLint("UseKtx")
    private fun save(context: Context, key: String, value: Boolean) {
        check(preferences(context).edit().putBoolean(key, value).commit())
    }
}

/** One-time enablement and runtime Bluetooth consent. Daily collection is owned by HealthWakeService. */
class BridgeSetupActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var rootCancellation: CancellationSignal? = null
    private var readingEnabled by mutableStateOf(false)
    private var hasPermission by mutableStateOf(true)
    private var rootEnabled by mutableStateOf(false)
    private var rootRequest by mutableStateOf(RootRequest.NONE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readingEnabled = BridgeSettings.enabled(this)
        rootEnabled = BridgeSettings.rootFitnessEnabled(this)
        refreshPermission()
        setContent {
            BridgeTheme {
                BridgeSetupScreen(
                    readingEnabled = readingEnabled,
                    hasBluetoothPermission = hasPermission,
                    rootEnabled = rootEnabled,
                    rootRequest = rootRequest,
                    onReadingChange = ::setReading,
                    onRequestBluetooth = ::requestBluetooth,
                    onRootChange = ::setRoot,
                    onExport = { startActivity(Intent(this, HealthExportActivity::class.java)) },
                )
            }
        }
    }

    /** Runs after the permission prompt closes, and after any change made in system Settings. */
    override fun onResume() {
        super.onResume()
        refreshPermission()
    }

    private fun refreshPermission() {
        hasPermission = Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        }
    }

    private fun setReading(enabled: Boolean) {
        BridgeSettings.setEnabled(this, enabled)
        readingEnabled = enabled
        if (enabled && !hasPermission) requestBluetooth()
    }

    private fun setRoot(enabled: Boolean) {
        if (!enabled) {
            BridgeSettings.setRootFitnessEnabled(this, false)
            rootEnabled = false
            rootRequest = RootRequest.NONE
            return
        }
        val cancellation = CancellationSignal()
        rootCancellation = cancellation
        rootRequest = RootRequest.PENDING
        worker.execute {
            val allowed = try { RootProcess.authorize(cancellation) } catch (_: Exception) { false }
            runOnUiThread {
                if (!isDestroyed && !cancellation.isCanceled) {
                    BridgeSettings.setRootFitnessEnabled(this, allowed)
                    rootEnabled = allowed
                    rootCancellation = null
                    rootRequest = if (allowed) RootRequest.NONE else RootRequest.DENIED
                }
            }
        }
    }

    override fun onDestroy() { rootCancellation?.cancel(); worker.shutdownNow(); super.onDestroy() }
}
