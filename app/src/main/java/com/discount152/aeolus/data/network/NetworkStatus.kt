package com.discount152.aeolus.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object NetworkStatus {
    fun isValidated(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        return isValidated(cm.getNetworkCapabilities(network))
    }

    fun isValidated(caps: NetworkCapabilities?): Boolean {
        if (caps == null) return false
        return isValidated(
            hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            hasValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        )
    }

    /**
     * User-facing NIC only. USB ADB / USB-only "validated" links do not count —
     * radio-off must present OFFLINE even while a debug cable is attached (D7).
     */
    fun isValidated(
        hasInternet: Boolean,
        hasValidated: Boolean,
        wifi: Boolean,
        cellular: Boolean,
        ethernet: Boolean,
        vpn: Boolean,
    ): Boolean {
        if (!hasInternet || !hasValidated) return false
        return wifi || cellular || ethernet || vpn
    }
}
