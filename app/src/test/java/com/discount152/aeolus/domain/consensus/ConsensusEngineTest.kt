package com.discount152.aeolus.domain.consensus

import com.discount152.aeolus.domain.models.DailyPoint
import com.discount152.aeolus.domain.models.ForecastModelId
import com.discount152.aeolus.domain.models.ModelForecast
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConsensusEngineTest {
    @Test
    fun median_oddAndEven() {
        assertThat(ConsensusEngine.median(listOf(1.0, 3.0, 2.0))).isEqualTo(2.0)
        assertThat(ConsensusEngine.median(listOf(1.0, 2.0, 3.0, 4.0))).isEqualTo(2.5)
        assertThat(ConsensusEngine.median(emptyList())).isNull()
    }

    @Test
    fun precipitationConsensus_andOutlier() {
        val models = listOf(
            model(ForecastModelId.ECMWF, 16.4),
            model(ForecastModelId.ICON, 13.1),
            model(ForecastModelId.GFS, 27.8),
        )
        val daily = ConsensusEngine.buildConsensusDaily(models)
        assertThat(daily).hasSize(1)
        val day = daily.first()
        assertThat(day.precipitationSumMm).isWithin(0.01).of(16.4)
        assertThat(day.outliers.any { it.model == ForecastModelId.GFS && it.variable == "precipitation" }).isTrue()
        assertThat(day.precipRangeMm!!.low).isWithin(0.01).of(13.1)
        assertThat(day.precipRangeMm!!.high).isWithin(0.01).of(27.8)
    }

    @Test
    fun missingModel_reported() {
        val models = listOf(
            model(ForecastModelId.ECMWF, 5.0),
            model(ForecastModelId.ICON, 6.0),
            ModelForecast(ForecastModelId.GFS, available = false),
        )
        val day = ConsensusEngine.buildConsensusDaily(models).first()
        assertThat(day.availableModels).containsExactly(ForecastModelId.ECMWF, ForecastModelId.ICON)
        assertThat(day.missingModels).contains(ForecastModelId.GFS)
    }

    private fun model(id: ForecastModelId, precip: Double) =
        ModelForecast(
            model = id,
            available = true,
            daily = listOf(
                DailyPoint(
                    date = "2026-10-22",
                    weatherCode = 61,
                    tempMaxC = 18.0,
                    tempMinC = 10.0,
                    precipitationSumMm = precip,
                    precipitationProbabilityMax = 70,
                    windSpeedMaxKmh = 20.0,
                    gustMaxKmh = 35.0,
                ),
            ),
        )
}
