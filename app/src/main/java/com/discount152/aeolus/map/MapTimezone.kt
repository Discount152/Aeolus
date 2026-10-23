package com.discount152.aeolus.map

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class MapTimezoneMode {
    VIEWED_LOCATION,
    DEVICE,
    HOME,
    UTC,
    CUSTOM,
}

object MapTimezone {
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dayFormatter = DateTimeFormatter.ofPattern("EEE d", Locale.US)

    fun resolveZoneId(
        mode: MapTimezoneMode,
        viewedZoneId: String?,
        homeZoneId: String?,
        customZoneId: String?,
        deviceZoneId: ZoneId = ZoneId.systemDefault(),
    ): ZoneId =
        when (mode) {
            MapTimezoneMode.VIEWED_LOCATION ->
                parseZone(viewedZoneId) ?: deviceZoneId
            MapTimezoneMode.DEVICE -> deviceZoneId
            MapTimezoneMode.HOME ->
                parseZone(homeZoneId) ?: deviceZoneId
            MapTimezoneMode.UTC -> ZoneOffset.UTC
            MapTimezoneMode.CUSTOM ->
                parseZone(customZoneId) ?: deviceZoneId
        }

    /**
     * Primary clock label: actual local wall time + short zone name (e.g. `18:40 TRT`).
     * Never presents `UTC+X` arithmetic as the primary form.
     * When [previousEpochSeconds] is set and the calendar day changes in [zoneId], appends a date.
     */
    fun formatInstant(
        epochSeconds: Long,
        zoneId: ZoneId,
        includeZoneLabel: Boolean = true,
        previousEpochSeconds: Long? = null,
    ): String {
        val instant = Instant.ofEpochSecond(epochSeconds)
        val zoned = instant.atZone(zoneId)
        val time = timeFormatter.format(zoned)
        val dayCrossed = previousEpochSeconds != null &&
            LocalDate.ofInstant(Instant.ofEpochSecond(previousEpochSeconds), zoneId) !=
            zoned.toLocalDate()
        val dateSuffix = if (dayCrossed) " ${dayFormatter.format(zoned)}" else ""
        if (!includeZoneLabel) return time + dateSuffix
        val abbr = shortLabel(zoneId, instant)
        return "$time$dateSuffix $abbr"
    }

    /**
     * Short zone label for UI. Prefer IANA abbreviation (CET/CEST/TRT).
     * Avoids `UTC+3` style as the primary clock companion — falls back to city/region id fragment.
     */
    fun shortLabel(zoneId: ZoneId, at: Instant = Instant.now()): String {
        if (zoneId == ZoneOffset.UTC || zoneId.id.equals("UTC", ignoreCase = true)) return "UTC"
        if (zoneId is ZoneOffset) {
            // Fixed offsets are last resort offline; still show clock-friendly token
            return zoneId.id.removePrefix("UTC").ifBlank { "UTC" }.let {
                if (it.startsWith("+") || it.startsWith("-")) "GMT$it" else it
            }
        }
        val zoned = at.atZone(zoneId)
        val abbr = zoned.format(DateTimeFormatter.ofPattern("z", Locale.US))
        if (abbr.length in 2..5 &&
            !abbr.startsWith("GMT") &&
            !abbr.startsWith("UTC")
        ) {
            return abbr
        }
        // e.g. Europe/Istanbul → Istanbul
        val id = zoneId.id
        return id.substringAfterLast('/').replace('_', ' ')
    }

    fun modeMenuLabel(
        mode: MapTimezoneMode,
        viewedZoneId: String?,
        homeZoneId: String?,
        customZoneId: String?,
        deviceZoneId: ZoneId = ZoneId.systemDefault(),
    ): String =
        when (mode) {
            MapTimezoneMode.VIEWED_LOCATION ->
                "Viewed location — ${viewedZoneId ?: "resolving…"}"
            MapTimezoneMode.DEVICE ->
                "Device — ${deviceZoneId.id}"
            MapTimezoneMode.HOME ->
                "Home — ${homeZoneId ?: deviceZoneId.id}"
            MapTimezoneMode.UTC -> "UTC"
            MapTimezoneMode.CUSTOM ->
                "Custom — ${customZoneId ?: "choose zone"}"
        }

    fun modeChipLabel(mode: MapTimezoneMode): String =
        when (mode) {
            MapTimezoneMode.VIEWED_LOCATION -> "VIEWED"
            MapTimezoneMode.DEVICE -> "DEVICE"
            MapTimezoneMode.HOME -> "HOME"
            MapTimezoneMode.UTC -> "UTC"
            MapTimezoneMode.CUSTOM -> "CUSTOM"
        }

    /** Compact label for map time-mode chip (alias of [modeChipLabel]). */
    fun modeLabel(mode: MapTimezoneMode): String = modeChipLabel(mode)

    /**
     * Offline fallback when the viewport center has no saved IANA zone: map longitude to a fixed offset.
     */
    fun approximateZoneFromLongitude(longitude: Double): ZoneId {
        val hours = ((longitude + 7.5) / 15.0).toInt().coerceIn(-12, 14)
        return ZoneOffset.ofHours(hours)
    }

    /**
     * Real IANA id only. Rejects blank, `auto` (map-pin sentinel), and unparseable values.
     */
    fun usableIana(id: String?): String? {
        val zone = parseZone(id) ?: return null
        if (zone is ZoneOffset) return null
        return zone.id
    }

    /**
     * VIEWED-mode zone for the camera. Location TZ is used only when the camera is still
     * on that pin/city. Otherwise a local geographic guess — never the device zone, and
     * never a leftover city after a long pan (Amsterdam over Anamur).
     */
    fun resolveViewedIana(
        cameraLat: Double,
        cameraLon: Double,
        locationZoneId: String? = null,
        locationLat: Double? = null,
        locationLon: Double? = null,
        maxLocationDistanceDeg: Double = 2.0,
    ): String {
        val geo = ianaForCoordinates(cameraLat, cameraLon)
        val locZone = usableIana(locationZoneId)
        if (locZone != null && locationLat != null && locationLon != null) {
            val dLat = cameraLat - locationLat
            val dLon = RadarViewport.normalizeLon(cameraLon) - RadarViewport.normalizeLon(locationLon)
            if (dLat * dLat + dLon * dLon <= maxLocationDistanceDeg * maxLocationDistanceDeg) {
                // Trust a nearby city TZ only when geography does not already know a different IANA
                // (harness/OM can stamp Europe/Amsterdam on an Anamur pin).
                val geoZone = parseZone(geo)
                if (locZone == geo) return locZone
                if (geoZone is ZoneOffset) return locZone
            }
        }
        return geo
    }

    /**
     * Offline camera → IANA (or a longitude offset when the land box is unknown).
     * Specific boxes first so NL/Turkey QA cameras do not share a CET leftover.
     */
    fun ianaForCoordinates(latitude: Double, longitude: Double): String {
        val lon = RadarViewport.normalizeLon(longitude)
        val lat = latitude
        return when {
            inBox(lat, lon, 35.8, 42.4, 25.6, 44.8) -> "Europe/Istanbul"
            inBox(lat, lon, 34.4, 35.8, 32.0, 34.9) -> "Asia/Nicosia"
            inBox(lat, lon, 34.7, 41.8, 19.3, 28.4) -> "Europe/Athens"
            inBox(lat, lon, 50.7, 53.7, 3.2, 7.3) -> "Europe/Amsterdam"
            inBox(lat, lon, 49.4, 51.6, 2.5, 6.4) -> "Europe/Brussels"
            inBox(lat, lon, 47.2, 55.1, 5.8, 15.1) -> "Europe/Berlin"
            inBox(lat, lon, 49.9, 58.8, -8.2, 1.8) -> "Europe/London"
            inBox(lat, lon, 41.0, 51.2, -5.2, 8.4) -> "Europe/Paris"
            inBox(lat, lon, 36.0, 43.8, -9.5, 3.4) -> "Europe/Madrid"
            inBox(lat, lon, 36.6, 47.1, 6.6, 18.6) -> "Europe/Rome"
            else -> approximateZoneFromLongitude(lon).id
        }
    }

    fun parseZone(id: String?): ZoneId? {
        val raw = id?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (raw.equals("auto", ignoreCase = true)) return null
        return runCatching { ZoneId.of(raw) }.getOrNull()
    }

    private fun inBox(
        lat: Double,
        lon: Double,
        latSouth: Double,
        latNorth: Double,
        lonWest: Double,
        lonEast: Double,
    ): Boolean = lat in latSouth..latNorth && lon in lonWest..lonEast
}
