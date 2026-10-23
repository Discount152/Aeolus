package com.discount152.aeolus.data.repository

import com.discount152.aeolus.domain.models.ForecastModelId
import com.discount152.aeolus.domain.models.GeoLocation
import com.discount152.aeolus.domain.models.HourlyPoint
import com.discount152.aeolus.domain.models.ModelForecast
import com.discount152.aeolus.domain.models.WeatherBundle
import com.discount152.aeolus.domain.models.ConsensusDaily
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CoherentLiveBundleTest {
    private val loc = GeoLocation("id", "X", 1.0, 2.0, "UTC")

    @Test
    fun emptyConsensus_isNotCoherent() {
        val bundle = WeatherBundle(
            location = loc,
            fetchedAtEpochMs = 1L,
            models = ForecastModelId.entries.map {
                ModelForecast(model = it, available = false, fetchedAtEpochMs = 1L)
            },
            ensemble = null,
            consensusHourly = emptyList(),
            consensusDaily = emptyList(),
            confidenceByDate = emptyMap(),
        )
        assertThat(WeatherRepository.isCoherentLiveBundle(bundle)).isFalse()
    }

    @Test
    fun populatedConsensus_withAvailableModel_isCoherent() {
        val bundle = WeatherBundle(
            location = loc,
            fetchedAtEpochMs = 1L,
            models = listOf(
                ModelForecast(
                    model = ForecastModelId.ECMWF,
                    available = true,
                    hourly = listOf(HourlyPoint(0L, "t")),
                    fetchedAtEpochMs = 1L,
                ),
            ),
            ensemble = null,
            consensusHourly = listOf(HourlyPoint(0L, "t")),
            consensusDaily = listOf(
                ConsensusDaily(
                    date = "2026-10-23",
                    weatherCode = 0,
                    tempMaxC = 10.0,
                    tempMinC = 5.0,
                    precipitationSumMm = 0.0,
                    precipitationProbabilityMax = 0,
                    windSpeedMaxKmh = null,
                    gustMaxKmh = null,
                    sunriseIso = null,
                    sunsetIso = null,
                ),
            ),
            confidenceByDate = emptyMap(),
        )
        assertThat(WeatherRepository.isCoherentLiveBundle(bundle)).isTrue()
    }
}
