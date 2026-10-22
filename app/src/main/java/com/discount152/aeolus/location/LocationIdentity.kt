package com.discount152.aeolus.location

import com.discount152.aeolus.domain.models.GeoLocation
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object LocationIdentity {
    /** Approximate locality match radius for GPS jitter (meters). */
    const val GPS_MATCH_RADIUS_M = 2_500.0

    /** Distinct cities closer than this are still kept separate if names differ. */
    const val NEARBY_DISTINCT_GUARD_M = 8_000.0

    fun canonicalSearchId(location: GeoLocation): String {
        val providerId = location.id.trim()
        if (providerId.isNotEmpty() &&
            !providerId.startsWith("device:", ignoreCase = true) &&
            !providerId.startsWith("map:", ignoreCase = true) &&
            !providerId.startsWith("default:", ignoreCase = true)
        ) {
            return "geo:$providerId"
        }
        return derivedKey(location)
    }

    fun derivedKey(location: GeoLocation): String {
        val name = normalizeName(location.name)
        val country = normalizeName(location.country.orEmpty())
        val admin = normalizeName(location.admin1.orEmpty())
        val lat = "%.3f".format(location.latitude)
        val lon = "%.3f".format(location.longitude)
        return "meta:$name|$admin|$country|$lat|$lon"
    }

    fun normalizeName(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")

    fun sameSearchResult(a: GeoLocation, b: GeoLocation): Boolean {
        if (canonicalSearchId(a) == canonicalSearchId(b)) return true
        val sameName = normalizeName(a.name) == normalizeName(b.name) &&
            normalizeName(a.country.orEmpty()) == normalizeName(b.country.orEmpty())
        if (!sameName) return false
        return distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude) <= GPS_MATCH_RADIUS_M
    }

    fun matchesCurrentLocation(candidate: GeoLocation, saved: GeoLocation): Boolean {
        val localityMatch =
            normalizeName(candidate.name).isNotEmpty() &&
                normalizeName(candidate.name) != "current location" &&
                normalizeName(candidate.name) == normalizeName(saved.name) &&
                normalizeName(candidate.country.orEmpty()) == normalizeName(saved.country.orEmpty())
        if (localityMatch) {
            return distanceMeters(candidate.latitude, candidate.longitude, saved.latitude, saved.longitude) <=
                NEARBY_DISTINCT_GUARD_M
        }
        // GPS-only / Current location pin: tight radius, ignore volatile device: ids
        return distanceMeters(candidate.latitude, candidate.longitude, saved.latitude, saved.longitude) <=
            GPS_MATCH_RADIUS_M
    }

    fun findDuplicate(existing: List<GeoLocation>, incoming: GeoLocation, fromDevice: Boolean): GeoLocation? {
        return existing.firstOrNull { saved ->
            if (fromDevice) matchesCurrentLocation(incoming, saved)
            else sameSearchResult(incoming, saved)
        }
    }

    fun upsert(existing: List<GeoLocation>, incoming: GeoLocation, fromDevice: Boolean = false): List<GeoLocation> {
        val match = findDuplicate(existing, incoming, fromDevice)
        return if (match == null) {
            val normalized = incoming.copy(id = if (fromDevice) matchDeviceId(incoming) else canonicalSearchId(incoming))
            existing + normalized
        } else {
            existing.map {
                if (it.id == match.id) {
                    // Keep stable id; refresh coords/name lightly
                    it.copy(
                        name = if (fromDevice) it.name else incoming.name,
                        latitude = if (fromDevice) incoming.latitude else it.latitude,
                        longitude = if (fromDevice) incoming.longitude else it.longitude,
                        timezone = incoming.timezone.ifBlank { it.timezone },
                        country = incoming.country ?: it.country,
                        admin1 = incoming.admin1 ?: it.admin1,
                    )
                } else it
            }
        }
    }

    fun dedupe(existing: List<GeoLocation>): List<GeoLocation> {
        val out = mutableListOf<GeoLocation>()
        for (loc in existing) {
            val fromDevice = loc.id.startsWith("device:", ignoreCase = true) ||
                normalizeName(loc.name) == "current location"
            val match = findDuplicate(out, loc, fromDevice)
            if (match == null) {
                out += loc.copy(id = if (fromDevice) matchDeviceId(loc) else canonicalSearchId(loc))
            }
        }
        return out
    }

    private fun matchDeviceId(location: GeoLocation): String =
        "device:${"%.3f".format(location.latitude)},${"%.3f".format(location.longitude)}"

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
