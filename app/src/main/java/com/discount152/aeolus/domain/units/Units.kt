package com.discount152.aeolus.domain.units

import kotlinx.serialization.Serializable

@Serializable
enum class TemperatureUnit { CELSIUS, FAHRENHEIT }

@Serializable
enum class WindUnit { KMH, MPH, MS, BEAUFORT }

@Serializable
enum class PrecipitationUnit { MM, INCHES }

@Serializable
enum class DistanceUnit { KM, MILES }

@Serializable
data class UnitPreferences(
    val temperature: TemperatureUnit = TemperatureUnit.CELSIUS,
    val wind: WindUnit = WindUnit.KMH,
    val precipitation: PrecipitationUnit = PrecipitationUnit.MM,
    val distance: DistanceUnit = DistanceUnit.KM,
)

object UnitConverter {
    fun celsiusToFahrenheit(c: Double): Double = c * 9.0 / 5.0 + 32.0

    fun fahrenheitToCelsius(f: Double): Double = (f - 32.0) * 5.0 / 9.0

    fun convertTemperature(celsius: Double, unit: TemperatureUnit): Double =
        when (unit) {
            TemperatureUnit.CELSIUS -> celsius
            TemperatureUnit.FAHRENHEIT -> celsiusToFahrenheit(celsius)
        }

    fun kmhToMph(kmh: Double): Double = kmh / 1.609344

    fun kmhToMs(kmh: Double): Double = kmh / 3.6

    fun kmhToBeaufort(kmh: Double): Int {
        val ms = kmhToMs(kmh)
        return when {
            ms < 0.3 -> 0
            ms < 1.6 -> 1
            ms < 3.4 -> 2
            ms < 5.5 -> 3
            ms < 8.0 -> 4
            ms < 10.8 -> 5
            ms < 13.9 -> 6
            ms < 17.2 -> 7
            ms < 20.8 -> 8
            ms < 24.5 -> 9
            ms < 28.5 -> 10
            ms < 32.7 -> 11
            else -> 12
        }
    }

    fun convertWindKmh(kmh: Double, unit: WindUnit): Double =
        when (unit) {
            WindUnit.KMH -> kmh
            WindUnit.MPH -> kmhToMph(kmh)
            WindUnit.MS -> kmhToMs(kmh)
            WindUnit.BEAUFORT -> kmhToBeaufort(kmh).toDouble()
        }

    fun mmToInches(mm: Double): Double = mm / 25.4

    fun convertPrecipitation(mm: Double, unit: PrecipitationUnit): Double =
        when (unit) {
            PrecipitationUnit.MM -> mm
            PrecipitationUnit.INCHES -> mmToInches(mm)
        }

    fun kmToMiles(km: Double): Double = km / 1.609344

    fun convertDistance(km: Double, unit: DistanceUnit): Double =
        when (unit) {
            DistanceUnit.KM -> km
            DistanceUnit.MILES -> kmToMiles(km)
        }

    fun temperatureSymbol(unit: TemperatureUnit): String =
        when (unit) {
            TemperatureUnit.CELSIUS -> "°C"
            TemperatureUnit.FAHRENHEIT -> "°F"
        }

    fun windSymbol(unit: WindUnit): String =
        when (unit) {
            WindUnit.KMH -> "km/h"
            WindUnit.MPH -> "mph"
            WindUnit.MS -> "m/s"
            WindUnit.BEAUFORT -> "Bft"
        }

    fun precipitationSymbol(unit: PrecipitationUnit): String =
        when (unit) {
            PrecipitationUnit.MM -> "mm"
            PrecipitationUnit.INCHES -> "in"
        }

    fun distanceSymbol(unit: DistanceUnit): String =
        when (unit) {
            DistanceUnit.KM -> "km"
            DistanceUnit.MILES -> "mi"
        }
}
