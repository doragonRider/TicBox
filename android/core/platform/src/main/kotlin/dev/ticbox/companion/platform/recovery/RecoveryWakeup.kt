package dev.ticbox.companion.platform.recovery

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.platform.service.BackendSessionService
import kotlinx.coroutines.CancellationException

/** Active-service events avoid another start request. No UI object or Activity is retained here. */
internal object RecoveryEventBridge {
    @Volatile var listener: ((String) -> Unit)? = null
    fun active(trigger: String): Boolean {
        val current = listener ?: return false
        current(trigger)
        return true
    }
}

internal object RecoveryWakeup {
    /** One event, one attempt. Rejection leaves the saved user intent for a visible host or later event. */
    suspend fun wake(context: Context, trigger: String, associationId: Int? = null, peer: PeerAddress? = null) {
        try {
            val saved = AtomicFileConnectionIntentStore(context).read() ?: return
            if (!saved.reconnectEnabled || saved.peer == null || (associationId != null && saved.associationId != associationId) ||
                (peer != null && saved.peer != peer)) return
            if (RecoveryEventBridge.active(trigger)) return
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "wake_waiting permission=BLUETOOTH_CONNECT trigger=$trigger")
                return
            }
            val environment = AndroidRecoveryEnvironment(context)
            val availability = environment.inspect(saved)
            if (!availability.savedAssociationExists || !availability.bluetoothAvailable || !availability.bonded) {
                Log.i(TAG, "wake_waiting environment_unavailable trigger=$trigger")
                return
            }
            try { environment.observe(saved) } catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                Log.i(TAG, "presence_observation_unavailable exception=${problem.javaClass.simpleName}")
            }
            context.startForegroundService(BackendSessionService.restoreIntent(context, saved.revision, trigger))
            Log.i(TAG, "wake_dispatched trigger=$trigger")
        } catch (problem: Exception) {
            if (problem is CancellationException) throw problem
            Log.w(TAG, "wake_deferred trigger=$trigger exception=${problem.javaClass.simpleName}")
        }
    }
    private const val TAG = "WatchRecovery"
}
