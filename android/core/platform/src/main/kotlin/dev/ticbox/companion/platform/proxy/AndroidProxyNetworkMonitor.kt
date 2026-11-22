package dev.ticbox.companion.platform.proxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.ticbox.companion.engine.proxy.ProxyNetworkMonitor
import dev.ticbox.companion.engine.proxy.ProxyNetworkStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/** Transport type mapping follows the official client; the final wire byte means metered, not available. */
class AndroidProxyNetworkMonitor(context: Context) : ProxyNetworkMonitor {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun observe(): Flow<ProxyNetworkStatus> = callbackFlow {
        val manager = connectivity
        if (manager == null) {
            trySend(ProxyNetworkStatus.DISCONNECTED)
            close()
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { publish() }
            override fun onLost(network: Network) { publish() }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (network == manager.activeNetwork) trySend(status(capabilities)) else publish()
            }
            private fun publish() { trySend(status(manager.getNetworkCapabilities(manager.activeNetwork))) }
        }
        // Registration errors propagate to the session's detailed diagnostic and do not disable TCP.
        manager.registerDefaultNetworkCallback(callback)
        try {
            trySend(status(manager.getNetworkCapabilities(manager.activeNetwork)))
            awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
        } catch (error: Throwable) {
            runCatching { manager.unregisterNetworkCallback(callback) }
            throw error
        }
    }.conflate().distinctUntilChanged()

    private fun status(capabilities: NetworkCapabilities?): ProxyNetworkStatus {
        if (capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return ProxyNetworkStatus.DISCONNECTED
        }
        val type = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 0
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> 2
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 3
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> 4
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE) -> 5
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_LOWPAN) -> 6
            else -> Int.MAX_VALUE
        }
        return ProxyNetworkStatus(type, !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }
}
