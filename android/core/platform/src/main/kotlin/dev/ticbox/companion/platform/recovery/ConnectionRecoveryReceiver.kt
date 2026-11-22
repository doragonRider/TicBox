package dev.ticbox.companion.platform.recovery

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** CE no-backup state is read after BOOT_COMPLETED; LOCKED_BOOT_COMPLETED is deliberately absent. */
class ConnectionRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val trigger = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "boot_completed"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "package_replaced"
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) != BluetoothAdapter.STATE_ON) return
                "bluetooth_enabled"
            }
            else -> return
        }
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try { RecoveryWakeup.wake(context.applicationContext, trigger) }
            finally { pending.finish(); scope.cancel() }
        }
    }
}
