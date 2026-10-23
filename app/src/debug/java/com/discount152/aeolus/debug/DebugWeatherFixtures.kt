package com.discount152.aeolus.debug

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Compact Open-Meteo-shaped JSON the real mapper / ensemble parser accept. */
object DebugWeatherFixtures {
    private val zone: ZoneId = ZoneId.of("Europe/Amsterdam")
    private val hourFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    fun forecastBody(
        model: String,
        latitude: Double,
        longitude: Double,
        timezone: String = "Europe/Amsterdam",
    ): String {
        val today = LocalDate.now(zone)
        val start = today.atTime(LocalTime.MIDNIGHT)
        val temps = modelTemps(model)
        val hourlyTimes = (0 until 48).joinToString(",") { "\"${start.plusHours(it.toLong()).format(hourFmt)}\"" }
        val hourlyTemps = (0 until 48).joinToString(",") { i ->
            val v = temps.hourly + if (i >= 24) 1.0 else 0.0
            "%.1f".format(Locale.US, v)
        }
        val hourlyPrecip = (0 until 48).joinToString(",") { i -> if (i % 8 == 3) "0.4" else "0.0" }
        val hourlyWeather = (0 until 48).joinToString(",") { "2" }
        val hourlyProb = (0 until 48).joinToString(",") { "10" }
        val hourlyHumidity = (0 until 48).joinToString(",") { "72" }
        val hourlyPressure = (0 until 48).joinToString(",") { "1014.0" }
        val hourlyVis = (0 until 48).joinToString(",") { "20000.0" }
        val hourlyCloud = (0 until 48).joinToString(",") { "40" }
        val hourlyWind = (0 until 48).joinToString(",") { "14.0" }
        val hourlyDir = (0 until 48).joinToString(",") { "220.0" }
        val hourlyGust = (0 until 48).joinToString(",") { "22.0" }
        val hourlyUv = (0 until 48).joinToString(",") { if (it in 10..16) "2.0" else "0.0" }

        val days = (0 until 7).map { today.plusDays(it.toLong()) }
        val dailyTimes = days.joinToString(",") { "\"$it\"" }
        val dailyMax = days.joinToString(",") { "%.1f".format(Locale.US, temps.dailyMax) }
        val dailyMin = days.joinToString(",") { "%.1f".format(Locale.US, temps.dailyMin) }
        val dailyPrecip = days.mapIndexed { i, _ -> if (i == 0) "1.2" else "0.4" }.joinToString(",")
        val dailyProb = days.joinToString(",") { "20" }
        val dailyWind = days.joinToString(",") { "18.0" }
        val dailyGust = days.joinToString(",") { "28.0" }
        val dailyCode = days.joinToString(",") { "2" }
        val sunrise = days.joinToString(",") { "\"${it}T07:30\"" }
        val sunset = days.joinToString(",") { "\"${it}T18:40\"" }
        val uvMax = days.joinToString(",") { "3.0" }

        return """
            {
              "latitude": $latitude,
              "longitude": $longitude,
              "timezone": "$timezone",
              "utc_offset_seconds": 7200,
              "current": {
                "time": "${start.plusHours(12).format(hourFmt)}",
                "temperature_2m": ${temps.hourly},
                "apparent_temperature": ${temps.hourly - 1.0},
                "weather_code": 2,
                "precipitation": 0.0,
                "relative_humidity_2m": 72,
                "pressure_msl": 1014.0,
                "visibility": 20000.0,
                "cloud_cover": 40,
                "wind_speed_10m": 14.0,
                "wind_direction_10m": 220.0,
                "wind_gusts_10m": 22.0
              },
              "hourly": {
                "time": [$hourlyTimes],
                "temperature_2m": [$hourlyTemps],
                "apparent_temperature": [$hourlyTemps],
                "weather_code": [$hourlyWeather],
                "precipitation": [$hourlyPrecip],
                "precipitation_probability": [$hourlyProb],
                "relative_humidity_2m": [$hourlyHumidity],
                "pressure_msl": [$hourlyPressure],
                "visibility": [$hourlyVis],
                "cloud_cover": [$hourlyCloud],
                "wind_speed_10m": [$hourlyWind],
                "wind_direction_10m": [$hourlyDir],
                "wind_gusts_10m": [$hourlyGust],
                "uv_index": [$hourlyUv]
              },
              "daily": {
                "time": [$dailyTimes],
                "weather_code": [$dailyCode],
                "temperature_2m_max": [$dailyMax],
                "temperature_2m_min": [$dailyMin],
                "precipitation_sum": [$dailyPrecip],
                "precipitation_probability_max": [$dailyProb],
                "wind_speed_10m_max": [$dailyWind],
                "wind_gusts_10m_max": [$dailyGust],
                "sunrise": [$sunrise],
                "sunset": [$sunset],
                "uv_index_max": [$uvMax]
              }
            }
        """.trimIndent()
    }

    fun ensembleBody(latitude: Double, longitude: Double, timezone: String = "Europe/Amsterdam"): String {
        val today = LocalDate.now(zone)
        val days = (0 until 15).map { today.plusDays(it.toLong()) }
        val times = days.joinToString(",") { "\"$it\"" }
        fun series(base: Double, step: Double) =
            days.mapIndexed { i, _ -> "%.1f".format(Locale.US, base + (i % 3) * step) }.joinToString(",")
        return """
            {
              "latitude": $latitude,
              "longitude": $longitude,
              "timezone": "$timezone",
              "daily": {
                "time": [$times],
                "temperature_2m_max": [${series(12.0, 0.4)}],
                "temperature_2m_max_member01": [${series(11.4, 0.3)}],
                "temperature_2m_max_member02": [${series(12.8, 0.5)}],
                "temperature_2m_max_member03": [${series(12.1, 0.2)}],
                "precipitation_sum": [${series(1.2, 0.3)}],
                "precipitation_sum_member01": [${series(0.8, 0.4)}],
                "precipitation_sum_member02": [${series(1.6, 0.2)}],
                "precipitation_sum_member03": [${series(1.1, 0.5)}]
              }
            }
        """.trimIndent()
    }

    fun geocodingBody(query: String = ""): String {
        val q = query.trim().lowercase()
        val picked = when {
            q.contains("side") -> listOf(SIDE)
            q.contains("groningen") -> listOf(GRONINGEN)
            q.isBlank() -> listOf(GRONINGEN)
            else -> listOf(GRONINGEN, SIDE).filter { city ->
                city.name.lowercase().contains(q) ||
                    city.admin1.lowercase().contains(q) ||
                    city.country.lowercase().contains(q)
            }.ifEmpty { listOf(GRONINGEN) }
        }
        val results = picked.joinToString(",") { it.toJson() }
        return """{"results":[$results]}"""
    }

    fun emptyBody(): String = "{}"

    fun errorBody(reason: String): String = """{"error":true,"reason":"$reason"}"""

    private data class ModelTemps(val hourly: Double, val dailyMax: Double, val dailyMin: Double)

    private fun modelTemps(model: String): ModelTemps = when {
        model.contains("icon") -> ModelTemps(12.0, 14.0, 7.0)
        model.contains("gfs") -> ModelTemps(13.0, 15.0, 8.0)
        else -> ModelTemps(11.0, 13.0, 6.0)
    }

    private data class GeoCity(
        val id: Int,
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val timezone: String,
        val country: String,
        val admin1: String,
        val countryCode: String,
    ) {
        fun toJson(): String = """
            {
              "id": $id,
              "name": "$name",
              "latitude": $latitude,
              "longitude": $longitude,
              "timezone": "$timezone",
              "country": "$country",
              "admin1": "$admin1",
              "country_code": "$countryCode"
            }
        """.trimIndent()
    }

    private val GRONINGEN = GeoCity(
        id = 2755251,
        name = "Groningen",
        latitude = 53.21917,
        longitude = 6.56667,
        timezone = "Europe/Amsterdam",
        country = "Netherlands",
        admin1 = "Groningen",
        countryCode = "NL",
    )

    private val SIDE = GeoCity(
        id = 300737,
        name = "Side",
        latitude = 36.76667,
        longitude = 31.38889,
        timezone = "Europe/Istanbul",
        country = "Turkey",
        admin1 = "Antalya",
        countryCode = "TR",
    )
}
