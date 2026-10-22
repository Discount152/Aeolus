package com.discount152.aeolus.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.discount152.aeolus.domain.models.GeoLocation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.TimeZone
import kotlin.coroutines.resume

class LocationService(private val context: Context) {
    fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): GeoLocation? {
        if (!hasPermission()) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(
            LocationManager.FUSED_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )
        val best: Location = providers.mapNotNull { provider ->
            runCatching {
                if (lm.isProviderEnabled(provider)) lm.getLastKnownLocation(provider) else null
            }.getOrNull()
        }.maxByOrNull { it.time } ?: return null

        return GeoLocation(
            id = "device:${best.latitude},${best.longitude}",
            name = "Current location",
            latitude = best.latitude,
            longitude = best.longitude,
            timezone = TimeZone.getDefault().id,
            country = null,
            admin1 = null,
        )
    }

    @SuppressLint("MissingPermission")
    suspend fun awaitFreshLocation(timeoutMs: Long = 8_000L): GeoLocation? =
        suspendCancellableCoroutine { cont ->
            if (!hasPermission()) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            // Prefer last-known for v1 reliability; avoid long waits without UI.
            cont.resume(null)
        }.let { currentLocation() }
}
