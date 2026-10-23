package com.discount152.aeolus.map

import android.util.Log
import com.discount152.aeolus.data.network.OpenMeteoForecastApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import kotlin.math.floor

/**
 * Resolves IANA ZoneId for the viewed map center via Open-Meteo `timezone=auto`.
 * Spatially cached so camera pans do not spam the API.
 * Offline: returns last known zone for nearby cell, else null (no longitude invention).
 */
class ViewedTimezoneResolver(
    private val forecastApi: OpenMeteoForecastApi,
) {
    private val mutex = Mutex()
    private val cache = LinkedHashMap<Long, String>(64, 0.75f, true)
    private var lastKnownZoneId: String? = null

    suspend fun resolve(lat: Double, lon: Double, allowNetwork: Boolean): ZoneId? =
        mutex.withLock {
            val key = cellKey(lat, lon)
            cache[key]?.let { return MapTimezone.parseZone(it) }
            // Nearby cached cells
            for ((k, v) in cache) {
                if (nearby(k, key)) {
                    cache[key] = v
                    return MapTimezone.parseZone(v)
                }
            }
            if (!allowNetwork) {
                Log.d(TAG, "offline miss cell=$key lastKnown=$lastKnownZoneId")
                return MapTimezone.parseZone(lastKnownZoneId)
            }
            return try {
                val resp = forecastApi.timezoneAt(latitude = lat, longitude = lon)
                val tz = resp.timezone?.takeIf { it.isNotBlank() }
                if (tz != null) {
                    cache[key] = tz
                    lastKnownZoneId = tz
                    while (cache.size > 48) {
                        val eldest = cache.entries.iterator().next()
                        cache.remove(eldest.key)
                    }
                    Log.d(TAG, "resolved $tz for $lat,$lon")
                    MapTimezone.parseZone(tz)
                } else {
                    MapTimezone.parseZone(lastKnownZoneId)
                }
            } catch (e: Exception) {
                Log.w(TAG, "resolve failed lat=$lat lon=$lon", e)
                MapTimezone.parseZone(lastKnownZoneId)
            }
        }

    private fun cellKey(lat: Double, lon: Double): Long {
        val la = floor(lat * 2.0).toInt() // 0.5° cells
        val lo = floor(RadarViewport.normalizeLon(lon) * 2.0).toInt()
        return (la.toLong() shl 32) xor (lo.toLong() and 0xffffffffL)
    }

    private fun nearby(a: Long, b: Long): Boolean {
        val la = (a shr 32).toInt()
        val lo = a.toInt()
        val lb = (b shr 32).toInt()
        val lob = b.toInt()
        return kotlin.math.abs(la - lb) <= 1 && kotlin.math.abs(lo - lob) <= 1
    }

    companion object {
        private const val TAG = "ViewedTimezone"
    }
}
