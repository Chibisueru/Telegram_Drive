package io.github.chibisueru.telegramdrive.transfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether the phone is currently on Wi-Fi (or Ethernet), for the "Wi-Fi only" setting. */
class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _onWifi = MutableStateFlow(isWifi(cm.getNetworkCapabilities(cm.activeNetwork)))
    val onWifi: StateFlow<Boolean> = _onWifi

    init {
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _onWifi.value = isWifi(caps)
            }

            override fun onLost(network: Network) {
                _onWifi.value = false
            }
        })
    }

    private fun isWifi(caps: NetworkCapabilities?) = caps != null &&
        (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
