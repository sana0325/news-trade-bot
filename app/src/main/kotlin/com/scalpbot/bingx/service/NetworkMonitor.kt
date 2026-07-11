package com.scalpbot.bingx.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.scalpbot.bingx.core.util.AppLogger

private const val TAG = "NetworkMonitor"

/**
 * Android 7+ не доставляє CONNECTIVITY_ACTION статичним ресіверам, тож стежимо
 * за зміною мережі (Wi-Fi <-> мобільна) через NetworkCallback у рантаймі сервісу
 * і форсуємо реконект WS, бо стара сокет-сесія на мертвій мережі часто просто
 * висить замість того, щоб одразу впасти з помилкою.
 */
class NetworkMonitor(
    private val context: Context,
    private val onNetworkChanged: () -> Unit,
) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var lastNetwork: Network? = null

    fun register() {
        if (callback != null) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val previous = lastNetwork
                lastNetwork = network
                if (previous != null && previous != network) {
                    AppLogger.i(TAG, "Мережа змінилась, форсую реконект WS")
                    onNetworkChanged()
                }
            }

            override fun onLost(network: Network) {
                if (lastNetwork == network) lastNetwork = null
            }
        }
        callback = cb
        runCatching { connectivityManager.registerNetworkCallback(request, cb) }
            .onFailure { AppLogger.w(TAG, "Не вдалось зареєструвати NetworkCallback", it) }
    }

    fun unregister() {
        callback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        callback = null
    }
}
