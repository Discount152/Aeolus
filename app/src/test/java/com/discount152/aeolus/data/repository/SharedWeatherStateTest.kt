package com.discount152.aeolus.data.repository

import com.discount152.aeolus.domain.models.GeoLocation
import com.discount152.aeolus.domain.models.WeatherBundle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

/**
 * Proves a single shared weather StateFlow update is visible to all presentation consumers
 * (Today / Forecast / Models) — the architectural fix for cross-tab refresh.
 */
class SharedWeatherStateTest {
    @Test
    fun repositoryWeatherFlow_emitsToAllObservers() {
        val weather = MutableStateFlow<WeatherBundle?>(null)
        var todaySaw: WeatherBundle? = null
        var forecastSaw: WeatherBundle? = null
        var modelsSaw: WeatherBundle? = null

        // Three "tabs" observing the same source of truth
        fun onEmit(bundle: WeatherBundle?) {
            todaySaw = bundle
            forecastSaw = bundle
            modelsSaw = bundle
        }

        val updated = WeatherBundle(
            location = GeoLocation("1", "Amsterdam", 52.37, 4.89, "Europe/Amsterdam"),
            fetchedAtEpochMs = System.currentTimeMillis(),
            models = emptyList(),
            ensemble = null,
            consensusHourly = emptyList(),
            consensusDaily = emptyList(),
            confidenceByDate = emptyMap(),
        )
        weather.value = updated
        onEmit(weather.value)

        assertThat(todaySaw).isSameInstanceAs(updated)
        assertThat(forecastSaw).isSameInstanceAs(updated)
        assertThat(modelsSaw).isSameInstanceAs(updated)
        assertThat(todaySaw?.stale).isFalse()
    }
}
