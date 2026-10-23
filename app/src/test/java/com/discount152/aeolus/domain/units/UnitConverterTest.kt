package com.discount152.aeolus.domain.units

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UnitConverterTest {
    @Test
    fun celsiusToFahrenheit_freezingAndBoiling() {
        assertThat(UnitConverter.celsiusToFahrenheit(0.0)).isEqualTo(32.0)
        assertThat(UnitConverter.celsiusToFahrenheit(100.0)).isEqualTo(212.0)
    }

    @Test
    fun fahrenheitToCelsius_roundTrip() {
        val c = 21.5
        val f = UnitConverter.celsiusToFahrenheit(c)
        assertThat(UnitConverter.fahrenheitToCelsius(f)).isWithin(1e-9).of(c)
    }

    @Test
    fun windConversions() {
        assertThat(UnitConverter.kmhToMs(36.0)).isWithin(1e-9).of(10.0)
        assertThat(UnitConverter.kmhToMph(16.09344)).isWithin(1e-6).of(10.0)
        assertThat(UnitConverter.kmhToBeaufort(0.0)).isEqualTo(0)
        assertThat(UnitConverter.kmhToBeaufort(5.0)).isEqualTo(1)
        assertThat(UnitConverter.convertWindKmh(36.0, WindUnit.MS)).isWithin(1e-9).of(10.0)
    }

    @Test
    fun precipAndDistance() {
        assertThat(UnitConverter.mmToInches(25.4)).isWithin(1e-9).of(1.0)
        assertThat(UnitConverter.kmToMiles(1.609344)).isWithin(1e-6).of(1.0)
        assertThat(UnitConverter.convertPrecipitation(10.0, PrecipitationUnit.MM)).isEqualTo(10.0)
        assertThat(UnitConverter.convertDistance(10.0, DistanceUnit.KM)).isEqualTo(10.0)
    }
}
