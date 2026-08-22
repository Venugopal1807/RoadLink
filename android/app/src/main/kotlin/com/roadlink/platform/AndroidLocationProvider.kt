package com.roadlink.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.roadlink.domain.LocationProvider

/**
 * Last-known-location provider.
 *
 * Uses the last known fix rather than requesting a fresh one: at the moment an
 * emergency is confirmed, waiting seconds for a GPS lock is the wrong trade.
 * A slightly stale position delivered now beats an accurate one delivered late,
 * and the fix's accuracy travels with it so the responder can judge it.
 *
 * Returns null when there is no fix or no permission. It never invents a
 * position - an SOS with no location is still worth delivering, while an SOS
 * with a fabricated one is actively dangerous.
 */
@SuppressLint("MissingPermission")
class AndroidLocationProvider(context: Context) : LocationProvider {

    private val appContext = context.applicationContext
    private val manager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override suspend fun current(): LocationProvider.Fix? {
        if (!hasPermission()) return null
        val lm = manager ?: return null

        // Take the most recent fix across providers rather than trusting one.
        val best = runCatching {
            lm.getProviders(true)
                .mapNotNull { provider -> lm.getLastKnownLocation(provider) }
                .maxByOrNull { it.time }
        }.getOrNull() ?: return null

        return LocationProvider.Fix(
            lat = best.latitude,
            lng = best.longitude,
            accuracyMetres = if (best.hasAccuracy()) best.accuracy.toInt() else null,
        )
    }
}
