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

    fun parseZone(id: String?): ZoneId? =
        id?.takeIf { it.isNotBlank() }?.let {
            runCatching { ZoneId.of(it) }.getOrNull()
        }
}
