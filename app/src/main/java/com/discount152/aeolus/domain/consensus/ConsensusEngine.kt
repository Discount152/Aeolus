package com.discount152.aeolus.domain.consensus

import com.discount152.aeolus.domain.models.ConsensusDaily
import com.discount152.aeolus.domain.models.ForecastModelId
import com.discount152.aeolus.domain.models.HourlyPoint
import com.discount152.aeolus.domain.models.ModelForecast
import com.discount152.aeolus.domain.models.OutlierFlag
import com.discount152.aeolus.domain.models.PairRange
import kotlin.math.abs
import kotlin.math.max

object ConsensusEngine {
    /** MAD-based outlier: value farther than `k` median absolute deviations from median. */
    const val OUTLIER_MAD_K = 3.0

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
    }

    fun mad(values: List<Double>, center: Double? = median(values)): Double? {
        if (values.isEmpty() || center == null) return null
        return median(values.map { abs(it - center) })
    }

    fun robustConsensus(values: List<Double>): Double? = median(values)

    fun detectOutliers(
        modelValues: Map<ForecastModelId, Double>,
        variable: String,
        k: Double = OUTLIER_MAD_K,
    ): List<OutlierFlag> {
        if (modelValues.size < 3) return emptyList()
        val values = modelValues.values.toList()
        val med = median(values) ?: return emptyList()
        val deviation = mad(values, med) ?: return emptyList()
        // If all equal, no outliers
        if (deviation < 1e-9) {
            // Absolute fallback for precipitation when MAD is 0 but one value differs a lot
            return emptyList()
        }
        val threshold = max(deviation * k, absoluteFloor(variable))
        return modelValues.mapNotNull { (model, value) ->
            if (abs(value - med) > threshold) {
                OutlierFlag(model, variable, value, med)
            } else {
                null
            }
        }
    }

    private fun absoluteFloor(variable: String): Double =
        when (variable) {
            "precipitation" -> 5.0
            "temperature" -> 2.0
            "wind" -> 8.0
            else -> 1.0
        }

    fun buildConsensusDaily(models: List<ModelForecast>): List<ConsensusDaily> {
        val available = models.filter { it.available && it.daily.isNotEmpty() }
        val allDates = available.flatMap { it.daily.map { d -> d.date } }.distinct().sorted()
        return allDates.map { date ->
            val byModel = available.associateWith { m -> m.daily.firstOrNull { it.date == date } }
            val presentModels = byModel.filterValues { it != null }.keys.map { it.model }
            val missing = ForecastModelId.entries.filter { id ->
                presentModels.none { it == id } ||
                    models.none { it.model == id && it.available }
            }.distinct()

            fun collect(selector: (com.discount152.aeolus.domain.models.DailyPoint) -> Double?): Map<ForecastModelId, Double> =
                byModel.mapNotNull { (m, d) ->
                    val v = d?.let(selector)
                    if (v != null) m.model to v else null
                }.toMap()

            val precip = collect { it.precipitationSumMm }
            val tmax = collect { it.tempMaxC }
            val tmin = collect { it.tempMinC }
            val wind = collect { it.windSpeedMaxKmh }
            val gust = collect { it.gustMaxKmh }
            val pop = collect { it.precipitationProbabilityMax?.toDouble() }

            val precipOutliers = detectOutliers(precip, "precipitation")
            val precipValues = precip.values.toList()
            val precipConsensus = robustConsensus(precipValues)
            val precipRange = if (precipValues.isNotEmpty()) {
                PairRange(precipValues.min(), precipValues.max())
            } else null

            val weatherCodes = byModel.values.mapNotNull { it?.weatherCode }
            val codeConsensus = weatherCodes.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

            ConsensusDaily(
                date = date,
                weatherCode = codeConsensus,
                tempMaxC = robustConsensus(tmax.values.toList()),
                tempMinC = robustConsensus(tmin.values.toList()),
                precipitationSumMm = precipConsensus,
                precipitationProbabilityMax = robustConsensus(pop.values.toList())?.toInt(),
                windSpeedMaxKmh = robustConsensus(wind.values.toList()),
                gustMaxKmh = robustConsensus(gust.values.toList()),
                sunriseIso = byModel.values.firstOrNull { it?.sunriseIso != null }?.sunriseIso,
                sunsetIso = byModel.values.firstOrNull { it?.sunsetIso != null }?.sunsetIso,
                modelValues = precip.mapKeys { it.key.name },
                outliers = precipOutliers + detectOutliers(tmax, "temperature"),
                precipRangeMm = precipRange,
                availableModels = presentModels,
                missingModels = missing,
            )
        }
    }

    fun buildConsensusHourly(models: List<ModelForecast>, limitHours: Int = 48): List<HourlyPoint> {
        val available = models.filter { it.available && it.hourly.isNotEmpty() }
        if (available.isEmpty()) return emptyList()
        val times = available.flatMap { it.hourly.map { h -> h.timeIso } }.distinct().sorted().take(limitHours)
        return times.map { iso ->
            val points = available.mapNotNull { m -> m.hourly.firstOrNull { it.timeIso == iso } }
            fun medOf(sel: (HourlyPoint) -> Double?): Double? =
                robustConsensus(points.mapNotNull(sel))
            HourlyPoint(
                timeEpochMs = points.firstOrNull()?.timeEpochMs ?: 0L,
                timeIso = iso,
                temperatureC = medOf { it.temperatureC },
                apparentTemperatureC = medOf { it.apparentTemperatureC },
                weatherCode = points.mapNotNull { it.weatherCode }
                    .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
                precipitationMm = medOf { it.precipitationMm },
                precipitationProbability = medOf { it.precipitationProbability?.toDouble() }?.toInt(),
                humidityPercent = medOf { it.humidityPercent?.toDouble() }?.toInt(),
                pressureHpa = medOf { it.pressureHpa },
                visibilityM = medOf { it.visibilityM },
                cloudCoverPercent = medOf { it.cloudCoverPercent?.toDouble() }?.toInt(),
                windSpeedKmh = medOf { it.windSpeedKmh },
                windDirectionDeg = medOf { it.windDirectionDeg },
                gustKmh = medOf { it.gustKmh },
                uvIndex = medOf { it.uvIndex },
            )
        }
    }

    /** Normalized dispersion 0..1 where 0 is perfect agreement. */
    fun normalizedDispersion(values: List<Double>, scale: Double): Double {
        if (values.size < 2 || scale <= 0) return 0.0
        val med = median(values) ?: return 0.0
        val spread = mad(values, med) ?: return 0.0
        return (spread / scale).coerceIn(0.0, 1.0)
    }
}
