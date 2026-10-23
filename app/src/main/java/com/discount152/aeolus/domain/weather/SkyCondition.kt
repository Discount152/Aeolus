package com.discount152.aeolus.domain.weather

import com.discount152.aeolus.data.network.ForecastMapper
import com.discount152.aeolus.domain.models.ConsensusDaily
import com.discount152.aeolus.domain.models.HourlyPoint

/**
 * Daily sky summary derived from consensus hourly total cloud cover during daylight.
 *
 * Category thresholds (inclusive upper bound on mean daylight cloud cover %):
 * - 0–10: Clear
 * - 11–30: Mostly clear
 * - 31–60: Partly cloudy
 * - 61–85: Mostly cloudy
 * - 86–100: Overcast
 *
 * When daylight [min, max] cloud cover spread is at least [VARIABLE_CLOUD_SPREAD], the label
 * becomes "Variable clouds" instead of the mean-based category.
 */
object SkyCondition {
    const val VARIABLE_CLOUD_SPREAD = 45

    enum class Category(val displayLabel: String) {
        CLEAR("Clear"),
        MOSTLY_CLEAR("Mostly clear"),
        PARTLY_CLOUDY("Partly cloudy"),
        MOSTLY_CLOUDY("Mostly cloudy"),
        OVERCAST("Overcast"),
        VARIABLE("Variable clouds"),
    }

    fun categoryForMean(meanPercent: Double): Category =
        when {
            meanPercent <= 10 -> Category.CLEAR
            meanPercent <= 30 -> Category.MOSTLY_CLEAR
            meanPercent <= 60 -> Category.PARTLY_CLOUDY
            meanPercent <= 85 -> Category.MOSTLY_CLOUDY
            else -> Category.OVERCAST
        }

    /**
     * Derive a display label from hourly cloud cover samples during sunrise–sunset.
     * Returns null when no usable cloud samples exist for that day.
     */
    fun deriveDailySummary(
        date: String,
        hourly: List<HourlyPoint>,
        sunriseIso: String?,
        sunsetIso: String?,
    ): String? {
        val samples = daylightCloudSamples(date, hourly, sunriseIso, sunsetIso)
        if (samples.isEmpty()) return null
        val mean = samples.average()
        val spread = (samples.maxOrNull()!! - samples.minOrNull()!!).toDouble()
        val category =
            if (spread >= VARIABLE_CLOUD_SPREAD && samples.size >= 2) {
                Category.VARIABLE
            } else {
                categoryForMean(mean)
            }
        return category.displayLabel
    }

    fun attachSkySummaries(
        daily: List<ConsensusDaily>,
        hourly: List<HourlyPoint>,
    ): List<ConsensusDaily> =
        daily.map { day ->
            val summary = deriveDailySummary(day.date, hourly, day.sunriseIso, day.sunsetIso)
            if (summary == day.skySummary) day else day.copy(skySummary = summary)
        }

    private fun daylightCloudSamples(
        date: String,
        hourly: List<HourlyPoint>,
        sunriseIso: String?,
        sunsetIso: String?,
    ): List<Int> {
        val sunriseMs = sunriseIso?.let { ForecastMapper.parseTimeMs(it) }
        val sunsetMs = sunsetIso?.let { ForecastMapper.parseTimeMs(it) }
        return hourly
            .asSequence()
            .filter { it.timeIso.startsWith(date) }
            .filter { point ->
                when {
                    sunriseMs != null && sunsetMs != null ->
                        point.timeEpochMs in sunriseMs..sunsetMs
                    else -> true
                }
            }
            .mapNotNull { it.cloudCoverPercent }
            .toList()
    }
}
