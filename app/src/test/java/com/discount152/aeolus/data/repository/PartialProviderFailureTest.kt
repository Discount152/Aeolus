package com.discount152.aeolus.data.repository

import com.discount152.aeolus.domain.confidence.ConfidenceEngine
import com.discount152.aeolus.domain.consensus.ConsensusEngine
import com.discount152.aeolus.domain.models.DailyPoint
import com.discount152.aeolus.domain.models.ForecastModelId
import com.discount152.aeolus.domain.models.ModelForecast
import com.discount152.aeolus.domain.models.WeatherBundle
import com.discount152.aeolus.domain.models.GeoLocation
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

/**
 * Simulated individual-provider failures without requiring real network partitions.
 */
class PartialProviderFailureTest {
    private val date = "2026-10-22"
    private val today = LocalDate.parse(date)

    @Test
    fun ecmwfFails_iconGfsStillConsensus() {
        val models = listOf(
            unavailable(ForecastModelId.ECMWF),
            available(ForecastModelId.ICON, 12.0),
            available(ForecastModelId.GFS, 14.0),
        )
        val daily = ConsensusEngine.buildConsensusDaily(models)
        assertThat(daily).isNotEmpty()
        assertThat(daily.first().availableModels).containsExactly(ForecastModelId.ICON, ForecastModelId.GFS)
        assertThat(daily.first().missingModels).contains(ForecastModelId.ECMWF)
        assertThat(daily.first().precipitationSumMm).isNotNull()
        assertThat(daily.first().precipitationSumMm).isNotEqualTo(0.0)
    }

    @Test
    fun iconFails_ecmwfGfsStillConsensus() {
        val models = listOf(
            available(ForecastModelId.ECMWF, 10.0),
            unavailable(ForecastModelId.ICON),
            available(ForecastModelId.GFS, 11.0),
        )
        val day = ConsensusEngine.buildConsensusDaily(models).first()
        assertThat(day.missingModels).contains(ForecastModelId.ICON)
        assertThat(day.availableModels).hasSize(2)
    }

    @Test
    fun gfsFails_ecmwfIconStillConsensus() {
        val models = listOf(
            available(ForecastModelId.ECMWF, 9.0),
            available(ForecastModelId.ICON, 10.0),
            unavailable(ForecastModelId.GFS),
        )
        val day = ConsensusEngine.buildConsensusDaily(models).first()
        assertThat(day.missingModels).contains(ForecastModelId.GFS)
        val conf = ConfidenceEngine.scoreDay(date, today, models, day, ensemble = null, stabilityScore01 = 1.0)
        assertThat(conf.score).isAtMost(88)
        assertThat(conf.notes.any { it.contains("deterministic") || it.contains("ENSEMBLE") }).isTrue()
    }

    @Test
    fun ensembleFails_deterministicStillScores() {
        val models = listOf(
            available(ForecastModelId.ECMWF, 8.0),
            available(ForecastModelId.ICON, 9.0),
            available(ForecastModelId.GFS, 10.0),
        )
        val day = ConsensusEngine.buildConsensusDaily(models).first()
        val conf = ConfidenceEngine.scoreDay(date, today, models, day, ensemble = null, stabilityScore01 = null)
        assertThat(conf.notes).contains("ENSEMBLE DATA UNAVAILABLE")
        assertThat(conf.ensembleWeightUsed).isEqualTo(0.0)
        assertThat(conf.score).isGreaterThan(0)
        // missing ensemble must not become 0% by default
        assertThat(conf.category).isNotNull()
    }

    @Test
    fun liveFails_validCacheSurvives() {
        val cached = WeatherBundle(
            location = GeoLocation("1", "Amsterdam", 52.37, 4.89, "Europe/Amsterdam"),
            fetchedAtEpochMs = 1_700_000_000_000L,
            models = listOf(available(ForecastModelId.ECMWF, 5.0)),
            ensemble = null,
            consensusHourly = emptyList(),
            consensusDaily = ConsensusEngine.buildConsensusDaily(listOf(available(ForecastModelId.ECMWF, 5.0))),
            confidenceByDate = emptyMap(),
            fromCache = false,
            stale = false,
        )
        // Simulate refresh failure policy: keep cached, mark stale — never empty zeros
        val shown = cached.copy(fromCache = true, stale = true)
        assertThat(shown.consensusDaily.first().precipitationSumMm).isEqualTo(5.0)
        assertThat(shown.stale).isTrue()
        assertThat(shown.fromCache).isTrue()
    }

    @Test
    fun recoveryAfterFailure_usesFreshBundle() {
        val failed = WeatherBundle(
            location = GeoLocation("1", "Amsterdam", 52.37, 4.89, "Europe/Amsterdam"),
            fetchedAtEpochMs = 1L,
            models = listOf(unavailable(ForecastModelId.ECMWF)),
            ensemble = null,
            consensusHourly = emptyList(),
            consensusDaily = emptyList(),
            confidenceByDate = emptyMap(),
            fromCache = true,
            stale = true,
        )
        val recovered = failed.copy(
            models = listOf(available(ForecastModelId.ECMWF, 7.0), available(ForecastModelId.ICON, 6.0)),
            consensusDaily = ConsensusEngine.buildConsensusDaily(
                listOf(available(ForecastModelId.ECMWF, 7.0), available(ForecastModelId.ICON, 6.0)),
            ),
            fromCache = false,
            stale = false,
            fetchedAtEpochMs = 2L,
        )
        assertThat(recovered.stale).isFalse()
        assertThat(recovered.consensusDaily).isNotEmpty()
        assertThat(recovered.models.count { it.available }).isEqualTo(2)
    }

    @Test
    fun missingValues_notSilentZeros() {
        val day = ConsensusDailySparse
        assertThat(day.tempMaxC).isNull()
        assertThat(day.precipitationSumMm).isNull()
        // UI formatters must render em-dash, not 0 — covered by format helpers returning "—"
        assertThat(com.discount152.aeolus.ui.components.formatTemp(null, com.discount152.aeolus.domain.units.UnitPreferences()))
            .isEqualTo("—")
        assertThat(com.discount152.aeolus.ui.components.formatPrecip(null, com.discount152.aeolus.domain.units.UnitPreferences()))
            .isEqualTo("—")
    }

    private val ConsensusDailySparse =
        com.discount152.aeolus.domain.models.ConsensusDaily(
            date = date,
            weatherCode = null,
            tempMaxC = null,
            tempMinC = null,
            precipitationSumMm = null,
            precipitationProbabilityMax = null,
            windSpeedMaxKmh = null,
            gustMaxKmh = null,
            sunriseIso = null,
            sunsetIso = null,
        )

    private fun available(id: ForecastModelId, precip: Double) =
        ModelForecast(
            model = id,
            available = true,
            daily = listOf(
                DailyPoint(
                    date = date,
                    tempMaxC = 15.0,
                    tempMinC = 8.0,
                    precipitationSumMm = precip,
                    windSpeedMaxKmh = 20.0,
                    gustMaxKmh = 30.0,
                ),
            ),
        )

    private fun unavailable(id: ForecastModelId) =
        ModelForecast(model = id, available = false)
}
