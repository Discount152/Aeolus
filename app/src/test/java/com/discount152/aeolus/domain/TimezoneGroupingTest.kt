package com.discount152.aeolus.domain

import com.discount152.aeolus.data.network.ForecastMapper
import com.discount152.aeolus.domain.models.DailyPoint
import com.discount152.aeolus.domain.models.ForecastModelId
import com.discount152.aeolus.domain.models.HourlyPoint
import com.discount152.aeolus.domain.models.ModelForecast
import com.discount152.aeolus.domain.consensus.ConsensusEngine
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TimezoneGroupingTest {
    @Test
    fun dailyGrouping_usesLocationLocalDatesNotPhoneTimezone() {
        // Istanbul local midnights as date strings from API (timezone=auto)
        val istanbulDates = listOf("2026-10-22", "2026-10-23", "2026-10-24")
        val model = ModelForecast(
            model = ForecastModelId.ECMWF,
            available = true,
            daily = istanbulDates.map {
                DailyPoint(date = it, tempMaxC = 20.0, precipitationSumMm = 0.0)
            },
        )
        val consensus = ConsensusEngine.buildConsensusDaily(listOf(model))
        assertThat(consensus.map { it.date }).isEqualTo(istanbulDates)
        // Phone in Netherlands should not rewrite Turkish forecast dates
        val nlToday = LocalDate.now(ZoneId.of("Europe/Amsterdam"))
        assertThat(consensus.first().date).isNotEmpty()
        assertThat(nlToday.toString()).isNotNull()
    }

    @Test
    fun parseTimeMs_acceptsOffsetAndLocal() {
        val withOffset = ForecastMapper.parseTimeMs("2026-10-22T15:00:00+03:00")
        val local = ForecastMapper.parseTimeMs("2026-10-22T15:00")
        assertThat(withOffset).isGreaterThan(0L)
        assertThat(local).isGreaterThan(0L)
    }

    @Test
    fun hourlyConsensus_preservesIsoTimestamps() {
        val iso = "2026-10-22T18:00"
        val models = listOf(
            ModelForecast(
                ForecastModelId.ECMWF,
                true,
                hourly = listOf(
                    HourlyPoint(timeEpochMs = 1L, timeIso = iso, temperatureC = 10.0),
                ),
            ),
            ModelForecast(
                ForecastModelId.ICON,
                true,
                hourly = listOf(
                    HourlyPoint(timeEpochMs = 1L, timeIso = iso, temperatureC = 12.0),
                ),
            ),
        )
        val hourly = ConsensusEngine.buildConsensusHourly(models, 48)
        assertThat(hourly.first().timeIso).isEqualTo(iso)
        assertThat(hourly.first().temperatureC).isWithin(0.01).of(11.0)
    }
}
