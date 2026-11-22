package dev.ticbox.companion.ui.toolbox

import android.content.Context
import android.net.wifi.WifiManager

/**
 * Keeps this phone's Wi-Fi out of power saving and away from scans while a mirror runs over it. The
 * system only honours this with the screen on and the app in the foreground, which is when a mirror
 * runs.
 */
class ScreenWifiLock(context: Context) {
    private val lock: WifiManager.WifiLock? =
        context
            .getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "ticbox:remote-screen")
            ?.apply { setReferenceCounted(false) }

    fun acquire() {
        lock?.acquire()
    }

    fun release() {
        lock?.takeIf { it.isHeld }?.release()
    }
}
