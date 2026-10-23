package com.discount152.aeolus.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MapTimezoneTest {
    private val instant = Instant.parse("2026-07-15T12:00:00Z") // summer DST

    @Test
    fun sameInstant_amsterdamVsIstanbulVsUtc() {
        val ams = MapTimezone.formatInstant(instant.epochSecond, ZoneId.of("Europe/Amsterdam"))
        val ist = MapTimezone.formatInstant(instant.epochSecond, ZoneId.of("Europe/Istanbul"))
        val utc = MapTimezone.formatInstant(instant.epochSecond, ZoneId.of("UTC"))
        assertThat(ams).startsWith("14:00") // CEST
        assertThat(ist).startsWith("15:00") // TRT
        assertThat(utc).startsWith("12:00")
        assertThat(utc).contains("UTC")
    }

    @Test
    fun deviceMode_usesSystemZone() {
        val zone = MapTimezone.resolveZoneId(
            MapTimezoneMode.DEVICE,
            viewedZoneId = "Europe/Istanbul",
            homeZoneId = "Europe/Amsterdam",
            customZoneId = "Pacific/Honolulu",
            deviceZoneId = ZoneId.of("America/New_York"),
        )
        assertThat(zone.id).isEqualTo("America/New_York")
    }

    @Test
    fun homeMode_fixedWhenDeviceChanges() {
        val home = MapTimezone.resolveZoneId(
            MapTimezoneMode.HOME,
            viewedZoneId = "Europe/Istanbul",
            homeZoneId = "Europe/Amsterdam",
            customZoneId = null,
            deviceZoneId = ZoneId.of("Europe/Istanbul"),
        )
        assertThat(home.id).isEqualTo("Europe/Amsterdam")
    }

    @Test
    fun customMode_usesPersistedIana() {
        val z = MapTimezone.resolveZoneId(
            MapTimezoneMode.CUSTOM,
            viewedZoneId = null,
            homeZoneId = null,
            customZoneId = "Pacific/Honolulu",
        )
        assertThat(z.id).isEqualTo("Pacific/Honolulu")
    }

    @Test
    fun viewedLocation_usesViewportZone() {
        val z = MapTimezone.resolveZoneId(
            MapTimezoneMode.VIEWED_LOCATION,
            viewedZoneId = "Europe/Istanbul",
            homeZoneId = "Europe/Amsterdam",
            customZoneId = null,
        )
        assertThat(z.id).isEqualTo("Europe/Istanbul")
    }

    @Test
    fun dst_winterAmsterdam() {
        val winter = Instant.parse("2026-01-15T12:00:00Z")
        val label = MapTimezone.formatInstant(winter.epochSecond, ZoneId.of("Europe/Amsterdam"))
        assertThat(label).startsWith("13:00") // CET
    }

    @Test
    fun timezone_doesNotAffectRadarCacheIdentity() {
        val fp = RadarViewport.footprintForVisibleBounds(51.0, 52.0, 4.0, 5.0)
        val k1 = RadarImages.frameCacheKey("/v2/radar/a", fp)
        val k2 = RadarImages.frameCacheKey("/v2/radar/a", fp)
        assertThat(k1).isEqualTo(k2)
        assertThat(k1).doesNotContain("Europe")
        assertThat(k1).doesNotContain("UTC")
    }

    @Test
    fun primaryPresentation_isWallClock_notUtcPlusArithmetic() {
        val label = MapTimezone.formatInstant(
            Instant.parse("2026-07-15T15:40:00Z").epochSecond,
            ZoneId.of("Europe/Istanbul"),
        )
        assertThat(label).startsWith("18:40")
        assertThat(label).doesNotContain("UTC+")
        assertThat(label).doesNotContain("UTC +")
    }

    @Test
    fun midnightCrossing_appendsDate() {
        val zone = ZoneId.of("Europe/Amsterdam")
        val before = Instant.parse("2026-07-15T21:40:00Z").epochSecond // 23:40 CEST
        val after = Instant.parse("2026-07-15T22:30:00Z").epochSecond // 00:30 Thu 16
        val label = MapTimezone.formatInstant(after, zone, previousEpochSeconds = before)
        assertThat(label).startsWith("00:30")
        assertThat(label).contains("16") // day-of-month in date suffix
    }

    @Test
    fun menuLabels_areHumanReadable() {
        val label = MapTimezone.modeMenuLabel(
            MapTimezoneMode.HOME,
            viewedZoneId = "Europe/Istanbul",
            homeZoneId = "Europe/Amsterdam",
            customZoneId = null,
        )
        assertThat(label).contains("Home")
        assertThat(label).contains("Europe/Amsterdam")
    }
}
