@file:Suppress("OVERRIDE_DEPRECATION") // Public presence callbacks remain required on supported Android 14/15 devices.

package dev.ticbox.companion.platform.recovery

import android.annotation.SuppressLint
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import dev.ticbox.companion.api.PeerAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** System-bound CDM callback service. The actual Bluetooth link remains owned by BackendSessionService. */
class WatchPresenceService : CompanionDeviceService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Suppress("DEPRECATION") // API 34/35 receive AssociationInfo callbacks.
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        scope.launch { RecoveryWakeup.wake(applicationContext, "companion_appeared", associationId = associationInfo.id) }
    }

    @Suppress("DEPRECATION")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        // The physical engine observes EOF and owns retry. Presence loss is not proof of a dead socket.
        RecoveryEventBridge.active("companion_disappeared")
    }

    @Suppress("DEPRECATION") // Retain compatibility with OEM implementations using the address callback.
    override fun onDeviceAppeared(address: String) {
        val peer = runCatching { PeerAddress(address) }.getOrNull() ?: return
        scope.launch { RecoveryWakeup.wake(applicationContext, "companion_address_appeared", peer = peer) }
    }

    @Suppress("DEPRECATION")
    override fun onDeviceDisappeared(address: String) {
        RecoveryEventBridge.active("companion_address_disappeared")
    }

    @SuppressLint("NewApi") // This framework callback is delivered only on API 36+.
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED, DevicePresenceEvent.EVENT_BT_CONNECTED ->
                scope.launch { RecoveryWakeup.wake(applicationContext, "companion_presence", associationId = event.associationId) }
            else -> RecoveryEventBridge.active("companion_presence_changed")
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
