package dev.musicstream.feature.session.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.musicstream.feature.session.data.repository.ConnectionRepositoryImpl
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Forgets the resolved server address whenever the default network changes
 * (Wi-Fi <-> mobile, VPN on/off), so the next request re-probes the addresses.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connections: ConnectionRepositoryImpl,
) {
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = connections.invalidate()
            override fun onLost(network: Network) = connections.invalidate()
        })
    }
}
