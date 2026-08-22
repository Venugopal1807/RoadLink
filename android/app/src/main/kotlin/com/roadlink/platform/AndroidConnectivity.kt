package com.roadlink.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.roadlink.delivery.Connectivity

/**
 * Real network availability, plus a demo override.
 *
 * [forcedOffline] exists because the central claim - "RoadLink keeps the
 * emergency alive when connectivity disappears" - has to be demonstrable on
 * cue. Toggling aeroplane mode mid-demo is slow and takes the debugger with
 * it; this makes the offline path reproducible in one tap.
 *
 * It only ever forces offline, never online. There is no way to use this to
 * make a dead network look alive.
 */
class AndroidConnectivity(
    context: Context,
    @Volatile var forcedOffline: Boolean = false,
) : Connectivity {

    private val manager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override suspend fun isOnline(): Boolean {
        if (forcedOffline) return false
        return hasValidatedNetwork()
    }

    /**
     * NET_CAPABILITY_VALIDATED rather than merely CONNECTED: a phone attached
     * to a captive-portal Wi-Fi is "connected" and cannot reach anything.
     */
    fun hasValidatedNetwork(): Boolean {
        val cm = manager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
