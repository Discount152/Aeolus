package com.discount152.aeolus.domain.models

import kotlinx.serialization.Serializable

@Serializable
enum class ForecastModelId(val apiName: String, val displayName: String) {
    ECMWF("ecmwf_ifs025", "ECMWF"),
    ICON("icon_seamless", "ICON"),
    GFS("gfs_seamless", "GFS"),
}

@Serializable
data class GeoLocation(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val timezone: String,
    val country: String? = null,
    val admin1: String? = null,
)

@Serializable
data class HourlyPoint(
    val timeEpochMs: Long,
    val timeIso: String,
    val temperatureC: Double? = null,
    val apparentTemperatureC: Double? = null,
    val weatherCode: Int? = null,
    val precipitationMm: Double? = null,
    val precipitationProbability: Int? = null,
    val humidityPercent: Int? = null,
    val pressureHpa: Double? = null,
    val visibilityM: Double? = null,
    val cloudCoverPercent: Int? = null,
    val windSpeedKmh: Double? = null,
    val windDirectionDeg: Double? = null,
    val gustKmh: Double? = null,
    val uvIndex: Double? = null,
)

@Serializable
data class DailyPoint(
    val date: String,
    val weatherCode: Int? = null,
    val tempMaxC: Double? = null,
    val tempMinC: Double? = null,
    val precipitationSumMm: Double? = null,
    val precipitationProbabilityMax: Int? = null,
    val windSpeedMaxKmh: Double? = null,
    val gustMaxKmh: Double? = null,
    val sunriseIso: String? = null,
    val sunsetIso: String? = null,
    val uvIndexMax: Double? = null,
)

@Serializable
data class ModelForecast(
    val model: ForecastModelId,
    val available: Boolean,
    val hourly: List<HourlyPoint> = emptyList(),
    val daily: List<DailyPoint> = emptyList(),
    val fetchedAtEpochMs: Long = 0L,
)

@Serializable
data class EnsembleMemberSummary(
    val memberCount: Int,
    val membersPredictingPrecipAboveThreshold: Int,
    val precipThresholdMm: Double,
    val temperatureMeanC: Double?,
    val temperatureSpreadC: Double?,
    val precipMeanMm: Double?,
    val precipSpreadMm: Double?,
    val precipP10Mm: Double?,
    val precipP90Mm: Double?,
)

@Serializable
data class OutlierFlag(
    val model: ForecastModelId,
    val variable: String,
    val value: Double,
    val consensus: Double,
)

@Serializable
data class ConsensusDaily(
    val date: String,
    val weatherCode: Int?,
    val tempMaxC: Double?,
    val tempMinC: Double?,
    val precipitationSumMm: Double?,
    val precipitationProbabilityMax: Int?,
    val windSpeedMaxKmh: Double?,
    val gustMaxKmh: Double?,
    val sunriseIso: String?,
    val sunsetIso: String?,
    val modelValues: Map<String, Double?> = emptyMap(),
    val outliers: List<OutlierFlag> = emptyList(),
    val precipRangeMm: PairRange? = null,
    val availableModels: List<ForecastModelId> = emptyList(),
    val missingModels: List<ForecastModelId> = emptyList(),
)

@Serializable
data class PairRange(val low: Double, val high: Double)

@Serializable
data class ConfidenceBreakdown(
    val score: Int,
    val category: ConfidenceCategory,
    val deterministicWeightUsed: Double,
    val ensembleWeightUsed: Double,
    val leadTimeWeightUsed: Double,
    val stabilityWeightUsed: Double,
    val buildingHistory: Boolean,
    val notes: List<String> = emptyList(),
)

@Serializable
enum class ConfidenceCategory {
    HIGH,
    MODERATE,
    LOW,
    VERY_LOW,
}

@Serializable
data class StabilitySignal(
    val label: String,
    val direction: String,
)

@Serializable
data class WeatherBundle(
    val location: GeoLocation,
    val fetchedAtEpochMs: Long,
    val models: List<ModelForecast>,
    val ensemble: EnsembleMemberSummary?,
    val consensusHourly: List<HourlyPoint>,
    val consensusDaily: List<ConsensusDaily>,
    val confidenceByDate: Map<String, ConfidenceBreakdown>,
    val stabilitySignals: List<StabilitySignal> = emptyList(),
    val fromCache: Boolean = false,
    val stale: Boolean = false,
)

object WeatherCodes {
    fun label(code: Int?): String =
        when (code) {
            null -> "—"
            0 -> "Clear"
            1 -> "Mainly clear"
            2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Fog"
            51, 53, 55 -> "Drizzle"
            56, 57 -> "Freezing drizzle"
            61, 63, 65 -> "Rain"
            66, 67 -> "Freezing rain"
            71, 73, 75 -> "Snow"
            77 -> "Snow grains"
            80, 81, 82 -> "Rain showers"
            85, 86 -> "Snow showers"
            95 -> "Thunderstorm"
            96, 99 -> "Thunderstorm + hail"
            else -> "Code $code"
        }
}
