package com.discount152.aeolus.data.cache

import com.discount152.aeolus.domain.consensus.ConsensusEngine
import com.discount152.aeolus.domain.models.DailyPoint
import com.discount152.aeolus.domain.models.EnsembleMemberSummary
import com.discount152.aeolus.domain.models.ForecastModelId
import com.discount152.aeolus.domain.models.GeoLocation
import com.discount152.aeolus.domain.models.ModelForecast
import com.discount152.aeolus.domain.models.WeatherBundle
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Process-restart simulation: write bundle to disk JSON and read it back from a fresh decode.
 */
class ForecastCacheRoundTripTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }

    @Test
    fun cacheSurvivesRestart_andKeepsEnsembleConfidence() {
        val cacheDir = tmp.newFolder("cache", "forecasts")
        val models = listOf(
            ModelForecast(
                ForecastModelId.ECMWF, true,
                daily = listOf(DailyPoint("2026-10-22", tempMaxC = 12.0, precipitationSumMm = 5.0)),
            ),
        )
        val ens = EnsembleMemberSummary(
            memberCount = 51,
            membersPredictingPrecipAboveThreshold = 40,
            precipThresholdMm = 5.0,
            temperatureMeanC = 12.0,
            temperatureSpreadC = 1.2,
            precipMeanMm = 6.0,
            precipSpreadMm = 2.0,
            precipP10Mm = 3.0,
            precipP90Mm = 10.0,
        )
        val bundle = WeatherBundle(
            location = GeoLocation("geo:1", "Amsterdam", 52.37, 4.89, "Europe/Amsterdam"),
            fetchedAtEpochMs = 1_700_000_000_000L,
            models = models,
            ensemble = ens,
            ensembleByDate = mapOf("2026-10-22" to ens),
            consensusHourly = emptyList(),
            consensusDaily = ConsensusEngine.buildConsensusDaily(models),
            confidenceByDate = emptyMap(),
        )
        val file = File(cacheDir, "geo_1.json")
        file.writeText(json.encodeToString(WeatherBundle.serializer(), bundle))

        val restored = json.decodeFromString(WeatherBundle.serializer(), file.readText())
            .copy(fromCache = true, stale = true)
        assertThat(restored.ensemble?.memberCount).isEqualTo(51)
        assertThat(restored.ensembleByDate["2026-10-22"]?.precipMeanMm).isEqualTo(6.0)
        assertThat(restored.consensusDaily.first().precipitationSumMm).isEqualTo(5.0)
        assertThat(restored.stale).isTrue()
        assertThat(restored.fromCache).isTrue()
        assertThat(restored.consensusDaily.first().precipitationSumMm).isNotEqualTo(0.0)
    }
}
