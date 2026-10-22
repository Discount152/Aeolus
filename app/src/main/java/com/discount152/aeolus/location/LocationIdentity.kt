package com.discount152.aeolus.location

import com.discount152.aeolus.domain.models.GeoLocation
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object LocationIdentity {
    /** Approximate locality match radius for GPS jitter (meters). */
    const val GPS_MATCH_RADIUS_M = 2_500.0

    /** Distinct cities closer than this are still kept separate if names differ. */
    const val NEARBY_DISTINCT_GUARD_M = 8_000.0

    const val FALLBACK_ID = "fallback:amsterdam"

    val FALLBACK_LOCATION = GeoLocation(
        id = FALLBACK_ID,
        name = "Amsterdam",
        latitude = 52.37,
        longitude = 4.89,
        timezone = "Europe/Amsterdam",
        country = "Netherlands",
        admin1 = "North Holland",
    )

    /**
     * Durable identity for cache keys, selected-location persistence, and dedupe.
     * Rounded to ~100 m so GPS jitter does not mint a new key every launch.
     */
    fun stableId(location: GeoLocation): String {
        if (isFallback(location)) return FALLBACK_ID
        val raw = location.id.trim()
        if (raw.startsWith("geo:", ignoreCase = true)) {
            val provider = raw.removePrefix("geo:").removePrefix("GEO:")
            if (provider.isNotEmpty() && !provider.startsWith("default:", ignoreCase = true)) {
                return "geo:$provider"
            }
        }
        if (raw.isNotEmpty() &&
            !raw.startsWith("device:", ignoreCase = true) &&
            !raw.startsWith("map:", ignoreCase = true) &&
            !raw.startsWith("default:", ignoreCase = true) &&
            !raw.startsWith("fallback:", ignoreCase = true) &&
            !raw.startsWith("meta:", ignoreCase = true) &&
            !raw.startsWith("coord:", ignoreCase = true)
        ) {
            return "geo:$raw"
        }
        return coordKey(location.latitude, location.longitude)
    }

    fun coordKey(latitude: Double, longitude: Double): String =
        "coord:${"%.3f".format(latitude)},${"%.3f".format(longitude)}"

    /** Alias used for forecast cache file keys — always [stableId]. */
    fun cacheKey(location: GeoLocation): String = stableId(location)

    fun withStableId(location: GeoLocation): GeoLocation =
        location.copy(id = stableId(location))

    fun isFallback(location: GeoLocation): Boolean {
        if (location.id.equals(FALLBACK_ID, ignoreCase = true) ||
            location.id.equals("default:amsterdam", ignoreCase = true)
        ) {
            return true
        }
        return normalizeName(location.name) == "amsterdam" &&
            distanceMeters(location.latitude, location.longitude, FALLBACK_LOCATION.latitude, FALLBACK_LOCATION.longitude) <=
            GPS_MATCH_RADIUS_M &&
            (
                location.id.startsWith("default:", ignoreCase = true) ||
                    location.id.startsWith("fallback:", ignoreCase = true) ||
                    location.id.startsWith("meta:amsterdam", ignoreCase = true)
                )
    }

    fun isEphemeralActive(location: GeoLocation): Boolean {
        val id = location.id
        return isFallback(location) ||
            id.startsWith("device:", ignoreCase = true) ||
            id.startsWith("map:", ignoreCase = true) ||
            id.startsWith("coord:", ignoreCase = true) ||
            normalizeName(location.name) == "current location" ||
            normalizeName(location.name) == "map pin"
    }

    fun canonicalSearchId(location: GeoLocation): String = stableId(location)

    fun derivedKey(location: GeoLocation): String = coordKey(location.latitude, location.longitude)

    fun normalizeName(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")

    fun sameSearchResult(a: GeoLocation, b: GeoLocation): Boolean {
        if (stableId(a) == stableId(b)) return true
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
        return distanceMeters(candidate.latitude, candidate.longitude, saved.latitude, saved.longitude) <=
            GPS_MATCH_RADIUS_M
    }

    fun findDuplicate(existing: List<GeoLocation>, incoming: GeoLocation, fromDevice: Boolean): GeoLocation? {
        val incomingKey = stableId(incoming)
        existing.firstOrNull { stableId(it) == incomingKey }?.let { return it }
        return existing.firstOrNull { saved ->
            if (fromDevice) matchesCurrentLocation(incoming, saved)
            else sameSearchResult(incoming, saved)
        }
    }

    fun upsert(existing: List<GeoLocation>, incoming: GeoLocation, fromDevice: Boolean = false): List<GeoLocation> {
        val normalized = withStableId(incoming).let {
            if (fromDevice && normalizeName(it.name) == "current location") it
            else it
        }
        val match = findDuplicate(existing, normalized, fromDevice)
        return if (match == null) {
            existing + normalized
        } else {
            existing.map {
                if (it.id == match.id || stableId(it) == stableId(match)) {
                    it.copy(
                        id = stableId(match),
                        name = if (fromDevice && normalizeName(it.name) != "current location") it.name else normalized.name,
                        latitude = if (fromDevice) normalized.latitude else it.latitude,
                        longitude = if (fromDevice) normalized.longitude else it.longitude,
                        timezone = normalized.timezone.ifBlank { it.timezone },
                        country = normalized.country ?: it.country,
                        admin1 = normalized.admin1 ?: it.admin1,
                    )
                } else {
                    it
                }
            }
        }
    }

    fun dedupe(existing: List<GeoLocation>): List<GeoLocation> {
        val out = mutableListOf<GeoLocation>()
        for (loc in existing) {
            val fromDevice = loc.id.startsWith("device:", ignoreCase = true) ||
                normalizeName(loc.name) == "current location"
            val normalized = withStableId(loc)
            val match = findDuplicate(out, normalized, fromDevice)
            if (match == null) {
                out += normalized
            }
        }
        return out
    }

    /**
     * Upgrade migration: make every saved entry deletable; drop synthetic fallback-only Amsterdam;
     * normalize map/device/meta ids to [stableId].
     */
    fun migrateSavedLocations(existing: List<GeoLocation>): List<GeoLocation> {
        val normalized = existing.map { withStableId(it) }
        val withoutAutoFallback = normalized.filterNot { loc ->
            // Remove fallback Amsterdam that was auto-persisted — never user-saved explicitly
            isFallback(loc) && (
                loc.id.startsWith("default:", ignoreCase = true) ||
                    loc.id.equals(FALLBACK_ID, ignoreCase = true) ||
                    loc.id.startsWith("meta:amsterdam", ignoreCase = true)
                )
        }
        return dedupe(withoutAutoFallback)
    }

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
