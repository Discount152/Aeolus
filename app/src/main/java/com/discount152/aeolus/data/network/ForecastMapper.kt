package com.discount152.aeolus.data.network

import com.discount152.aeolus.domain.models.DailyPoint
import com.discount152.aeolus.domain.models.ForecastModelId
import com.discount152.aeolus.domain.models.HourlyPoint
import com.discount152.aeolus.domain.models.ModelForecast
import com.discount152.aeolus.domain.weather.EnsembleAnalyzer
import com.discount152.aeolus.domain.models.EnsembleMemberSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object ForecastMapper {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun toModelForecast(
        model: ForecastModelId,
        response: OpenMeteoForecastResponse,
        fetchedAt: Long = System.currentTimeMillis(),
    ): ModelForecast {
        if (response.error == true) {
            return ModelForecast(model = model, available = false, fetchedAtEpochMs = fetchedAt)
        }
        val hourly = mapHourly(response.hourly)
        val daily = mapDaily(response.daily)
        return ModelForecast(
            model = model,
            available = hourly.isNotEmpty() || daily.isNotEmpty(),
            hourly = hourly,
            daily = daily,
            fetchedAtEpochMs = fetchedAt,
        )
    }

    fun mapHourly(block: HourlyBlock?): List<HourlyPoint> {
        if (block == null || block.time.isEmpty()) return emptyList()
        return block.time.indices.map { i ->
            val iso = block.time[i]
            HourlyPoint(
                timeEpochMs = parseTimeMs(iso),
                timeIso = iso,
                temperatureC = block.temperature2m.getOrNull(i),
                apparentTemperatureC = block.apparentTemperature.getOrNull(i),
                weatherCode = block.weatherCode.getOrNull(i),
                precipitationMm = block.precipitation.getOrNull(i),
                precipitationProbability = block.precipitationProbability.getOrNull(i),
                humidityPercent = block.relativeHumidity2m.getOrNull(i),
                pressureHpa = block.pressureMsl.getOrNull(i),
                visibilityM = block.visibility.getOrNull(i),
                cloudCoverPercent = block.cloudCover.getOrNull(i),
                windSpeedKmh = block.windSpeed10m.getOrNull(i),
                windDirectionDeg = block.windDirection10m.getOrNull(i),
                gustKmh = block.windGusts10m.getOrNull(i),
                uvIndex = block.uvIndex.getOrNull(i),
            )
        }
    }

    fun mapDaily(block: DailyBlock?): List<DailyPoint> {
        if (block == null || block.time.isEmpty()) return emptyList()
        return block.time.indices.map { i ->
            DailyPoint(
                date = block.time[i],
                weatherCode = block.weatherCode.getOrNull(i),
                tempMaxC = block.temperature2mMax.getOrNull(i),
                tempMinC = block.temperature2mMin.getOrNull(i),
                precipitationSumMm = block.precipitationSum.getOrNull(i),
                precipitationProbabilityMax = block.precipitationProbabilityMax.getOrNull(i),
                windSpeedMaxKmh = block.windSpeed10mMax.getOrNull(i),
                gustMaxKmh = block.windGusts10mMax.getOrNull(i),
                sunriseIso = block.sunrise.getOrNull(i),
                sunsetIso = block.sunset.getOrNull(i),
                uvIndexMax = block.uvIndexMax.getOrNull(i),
            )
        }
    }

    /**
     * Parse raw ensemble JSON to extract member arrays for a target local date.
     * Open-Meteo returns keys like precipitation_sum_member01.
     */
    fun parseEnsembleDay(
        rawJson: String,
        targetDate: String,
    ): EnsembleMemberSummary? {
        return runCatching {
            val root = json.parseToJsonElement(rawJson).jsonObject
            val daily = root["daily"]?.jsonObject ?: return null
            val times = daily["time"]?.jsonArray?.map { it.jsonPrimitive.content } ?: return null
            val dayIndex = times.indexOf(targetDate)
            if (dayIndex < 0) return null

            val precipMembers = extractMemberValues(daily, "precipitation_sum", dayIndex)
            val tempMembers = extractMemberValues(daily, "temperature_2m_max", dayIndex)
            if (precipMembers.isEmpty()) return null
            EnsembleAnalyzer.summarizeDay(precipMembers, tempMembers)
        }.getOrNull()
    }

    fun parseEnsembleNearTerm(
        rawJson: String,
        today: LocalDate,
    ): EnsembleMemberSummary? {
        // Prefer today; fall back to first available day
        val summary = parseEnsembleDay(rawJson, today.toString())
        if (summary != null) return summary
        return runCatching {
            val root = json.parseToJsonElement(rawJson).jsonObject
            val daily = root["daily"]?.jsonObject ?: return null
            val times = daily["time"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            val first = times.firstOrNull() ?: return null
            parseEnsembleDay(rawJson, first)
        }.getOrNull()
    }

    private fun extractMemberValues(daily: JsonObject, base: String, index: Int): List<Double> {
        val out = mutableListOf<Double>()
        fun readAt(key: String) {
            val arr = daily[key] as? JsonArray ?: return
            val el = arr.getOrNull(index) ?: return
            if (el is kotlinx.serialization.json.JsonNull) return
            el.jsonPrimitive.doubleOrNull?.let { out.add(it) }
        }
        // Control run (no member suffix) + every *_memberNN dynamically
        readAt(base)
        daily.keys
            .filter { it.startsWith("${base}_member") }
            .sortedWith(compareBy { key ->
                key.substringAfter("${base}_member").toIntOrNull() ?: Int.MAX_VALUE
            })
            .forEach { readAt(it) }
        return out
    }

    /** Discover how many valid ensemble member keys exist for a base variable. */
    fun discoverMemberKeys(daily: JsonObject, base: String): List<String> =
        daily.keys
            .filter { it.startsWith("${base}_member") }
            .sortedWith(compareBy { key ->
                key.substringAfter("${base}_member").toIntOrNull() ?: Int.MAX_VALUE
            })

    fun parseTimeMs(iso: String): Long {
        return runCatching {
            OffsetDateTime.parse(iso, DateTimeFormatter.ISO_DATE_TIME).toInstant().toEpochMilli()
        }.recoverCatching {
            // Local date-time without offset — treat as UTC placeholder; UI uses iso string + location tz
            java.time.LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrDefault(0L)
    }
}
