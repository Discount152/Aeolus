package com.discount152.aeolus.map

import java.time.Instant
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

    fun formatInstant(
        epochSeconds: Long,
        zoneId: ZoneId,
        includeZoneLabel: Boolean = true,
    ): String {
        val instant = Instant.ofEpochSecond(epochSeconds)
        val zoned = instant.atZone(zoneId)
        val time = timeFormatter.format(zoned)
        if (!includeZoneLabel) return time
        val abbr = shortLabel(zoneId, instant)
        return "$time $abbr"
    }

    fun shortLabel(zoneId: ZoneId, at: Instant = Instant.now()): String {
        if (zoneId == ZoneOffset.UTC || zoneId.id == "UTC") return "UTC"
        val zoned = at.atZone(zoneId)
        val abbr = zoned.format(DateTimeFormatter.ofPattern("z", Locale.US))
        if (abbr.length in 2..5 && !abbr.startsWith("GMT") && !abbr.startsWith("UTC")) {
            return abbr
        }
        val offset = zoned.offset
        val totalSec = offset.totalSeconds
        val sign = if (totalSec >= 0) "+" else "-"
        val abs = kotlin.math.abs(totalSec)
        val h = abs / 3600
        val m = (abs % 3600) / 60
        return if (m == 0) "UTC$sign$h" else "UTC$sign$h:${"%02d".format(m)}"
    }

    fun modeLabel(mode: MapTimezoneMode): String =
        when (mode) {
            MapTimezoneMode.VIEWED_LOCATION -> "VIEWED"
            MapTimezoneMode.DEVICE -> "DEVICE"
            MapTimezoneMode.HOME -> "HOME"
            MapTimezoneMode.UTC -> "UTC"
            MapTimezoneMode.CUSTOM -> "CUSTOM"
        }

    fun parseZone(id: String?): ZoneId? =
        id?.takeIf { it.isNotBlank() }?.let {
            runCatching { ZoneId.of(it) }.getOrNull()
        }

    /**
     * Approximate IANA zone from longitude for VIEWED LOCATION when no network reverse-TZ
     * is available. Prefer Open-Meteo timezone when online; this is the offline fallback.
     */
    fun approximateZoneFromLongitude(lon: Double): ZoneId {
        val offsetHours = ((lon + 7.5) / 15.0).toInt().coerceIn(-12, 14)
        return ZoneOffset.ofHours(offsetHours)
    }
}
