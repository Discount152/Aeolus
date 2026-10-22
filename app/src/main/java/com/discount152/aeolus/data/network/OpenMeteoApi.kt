package com.discount152.aeolus.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

interface OpenMeteoForecastApi {
    @GET("v1/forecast")
    suspend fun forecast(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("timezone") timezone: String = "auto",
        @Query("forecast_days") forecastDays: Int = 16,
        @Query("models") models: String,
        @Query("current") current: String =
            "temperature_2m,apparent_temperature,weather_code,precipitation,relative_humidity_2m," +
                "pressure_msl,visibility,cloud_cover,wind_speed_10m,wind_direction_10m,wind_gusts_10m",
        @Query("hourly") hourly: String =
            "temperature_2m,apparent_temperature,weather_code,precipitation,precipitation_probability," +
                "relative_humidity_2m,pressure_msl,visibility,cloud_cover,wind_speed_10m," +
                "wind_direction_10m,wind_gusts_10m,uv_index",
        @Query("daily") daily: String =
            "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum," +
                "precipitation_probability_max,wind_speed_10m_max,wind_gusts_10m_max," +
                "sunrise,sunset,uv_index_max",
        @Query("wind_speed_unit") windSpeedUnit: String = "kmh",
        @Query("precipitation_unit") precipitationUnit: String = "mm",
    ): OpenMeteoForecastResponse
}

interface OpenMeteoEnsembleApi {
    @GET("v1/ensemble")
    suspend fun ensemble(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("timezone") timezone: String = "auto",
        @Query("forecast_days") forecastDays: Int = 15,
        @Query("models") models: String = "ecmwf_ifs025",
        @Query("hourly") hourly: String = "temperature_2m,precipitation",
        @Query("daily") daily: String = "temperature_2m_max,precipitation_sum",
        @Query("wind_speed_unit") windSpeedUnit: String = "kmh",
        @Query("precipitation_unit") precipitationUnit: String = "mm",
    ): OpenMeteoEnsembleResponse
}

interface OpenMeteoGeocodingApi {
    @GET("v1/search")
    suspend fun search(
        @Query("name") name: String,
        @Query("count") count: Int = 10,
        @Query("language") language: String = "en",
        @Query("format") format: String = "json",
    ): GeocodingResponse
}

@Serializable
data class OpenMeteoForecastResponse(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val timezone: String? = null,
    @SerialName("utc_offset_seconds") val utcOffsetSeconds: Int? = null,
    val current: CurrentBlock? = null,
    val hourly: HourlyBlock? = null,
    val daily: DailyBlock? = null,
    val error: Boolean? = null,
    val reason: String? = null,
)

@Serializable
data class CurrentBlock(
    val time: String? = null,
    @SerialName("temperature_2m") val temperature2m: Double? = null,
    @SerialName("apparent_temperature") val apparentTemperature: Double? = null,
    @SerialName("weather_code") val weatherCode: Int? = null,
    val precipitation: Double? = null,
    @SerialName("relative_humidity_2m") val relativeHumidity2m: Int? = null,
    @SerialName("pressure_msl") val pressureMsl: Double? = null,
    val visibility: Double? = null,
    @SerialName("cloud_cover") val cloudCover: Int? = null,
    @SerialName("wind_speed_10m") val windSpeed10m: Double? = null,
    @SerialName("wind_direction_10m") val windDirection10m: Double? = null,
    @SerialName("wind_gusts_10m") val windGusts10m: Double? = null,
)

@Serializable
data class HourlyBlock(
    val time: List<String> = emptyList(),
    @SerialName("temperature_2m") val temperature2m: List<Double?> = emptyList(),
    @SerialName("apparent_temperature") val apparentTemperature: List<Double?> = emptyList(),
    @SerialName("weather_code") val weatherCode: List<Int?> = emptyList(),
    val precipitation: List<Double?> = emptyList(),
    @SerialName("precipitation_probability") val precipitationProbability: List<Int?> = emptyList(),
    @SerialName("relative_humidity_2m") val relativeHumidity2m: List<Int?> = emptyList(),
    @SerialName("pressure_msl") val pressureMsl: List<Double?> = emptyList(),
    val visibility: List<Double?> = emptyList(),
    @SerialName("cloud_cover") val cloudCover: List<Int?> = emptyList(),
    @SerialName("wind_speed_10m") val windSpeed10m: List<Double?> = emptyList(),
    @SerialName("wind_direction_10m") val windDirection10m: List<Double?> = emptyList(),
    @SerialName("wind_gusts_10m") val windGusts10m: List<Double?> = emptyList(),
    @SerialName("uv_index") val uvIndex: List<Double?> = emptyList(),
)

@Serializable
data class DailyBlock(
    val time: List<String> = emptyList(),
    @SerialName("weather_code") val weatherCode: List<Int?> = emptyList(),
    @SerialName("temperature_2m_max") val temperature2mMax: List<Double?> = emptyList(),
    @SerialName("temperature_2m_min") val temperature2mMin: List<Double?> = emptyList(),
    @SerialName("precipitation_sum") val precipitationSum: List<Double?> = emptyList(),
    @SerialName("precipitation_probability_max") val precipitationProbabilityMax: List<Int?> = emptyList(),
    @SerialName("wind_speed_10m_max") val windSpeed10mMax: List<Double?> = emptyList(),
    @SerialName("wind_gusts_10m_max") val windGusts10mMax: List<Double?> = emptyList(),
    val sunrise: List<String?> = emptyList(),
    val sunset: List<String?> = emptyList(),
    @SerialName("uv_index_max") val uvIndexMax: List<Double?> = emptyList(),
)

@Serializable
data class OpenMeteoEnsembleResponse(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val timezone: String? = null,
    val hourly: EnsembleHourlyBlock? = null,
    val daily: EnsembleDailyBlock? = null,
    val error: Boolean? = null,
    val reason: String? = null,
)

@Serializable
data class EnsembleHourlyBlock(
    val time: List<String> = emptyList(),
    // Member fields arrive as temperature_2m_member01 etc. — parsed via JsonObject in mapper
)

@Serializable
data class EnsembleDailyBlock(
    val time: List<String> = emptyList(),
)

@Serializable
data class GeocodingResponse(
    val results: List<GeocodingResult> = emptyList(),
)

@Serializable
data class GeocodingResult(
    val id: Long? = null,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val timezone: String? = null,
    val country: String? = null,
    @SerialName("admin1") val admin1: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
)
