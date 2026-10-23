package com.discount152.aeolus.data.cache

import com.discount152.aeolus.domain.consensus.ConsensusEngine
import com.discount152.aeolus.domain.models.ConfidenceCategory
import com.discount152.aeolus.domain.models.ConfidenceBreakdown
import com.discount152.aeolus.domain.models.DailyPoint
import com.discount152.aeolus.domain.models.EnsembleMemberSummary
import com.discount152.aeolus.domain.models.ForecastModelId
import com.discount152.aeolus.domain.models.GeoLocation
import com.discount152.aeolus.domain.models.HourlyPoint
import com.discount152.aeolus.domain.models.ModelForecast
import com.discount152.aeolus.domain.models.WeatherBundle
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Process-death simulation: [ForecastCache.write] on one instance, [ForecastCache.read] on a new instance.
 */
class ForecastCacheRoundTripTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }

    private val date = "2026-10-22"

    @Test
    fun cacheSurvivesProcessDeath_fullBundleRoundTrip() {
        val cacheDir = tmp.newFolder("forecasts")
        val location = GeoLocation(
            id = "geo:2759794",
            name = "Amsterdam",
            latitude = 52.37,
            longitude = 4.89,
            timezone = "Europe/Amsterdam",
            country = "Netherlands",
            admin1 = "North Holland",
        )
        val bundle = sampleBundle(location)
        val writer = ForecastCache(cacheDir, json)
        writer.write(location.id, bundle)

        val reader = ForecastCache(cacheDir, json)
        val restored = reader.read(location.id)
        assertThat(restored).isNotNull()
        restored!!

        assertThat(restored.fetchedAtEpochMs).isEqualTo(bundle.fetchedAtEpochMs)
        assertThat(restored.location.id).isEqualTo(location.id)
        assertThat(restored.location.latitude).isWithin(0.0001).of(location.latitude)
        assertThat(restored.location.longitude).isWithin(0.0001).of(location.longitude)
        assertThat(restored.models).hasSize(3)
        assertThat(restored.models.map { it.model }).containsExactly(
            ForecastModelId.ECMWF,
            ForecastModelId.ICON,
            ForecastModelId.GFS,
        )
        assertThat(restored.ensemble?.memberCount).isEqualTo(51)
        assertThat(restored.ensembleByDate[date]?.precipMeanMm).isEqualTo(6.0)
        assertThat(restored.consensusDaily.first().precipitationSumMm).isEqualTo(5.0)
        assertThat(restored.consensusHourly).isNotEmpty()
        assertThat(restored.confidenceByDate[date]?.score).isEqualTo(72)
        assertThat(restored.confidenceByDate[date]?.category).isEqualTo(ConfidenceCategory.MODERATE)
    }

    @Test
    fun findNearest_afterDeviceIdChange_findsNearbyBundle() {
        val cacheDir = tmp.newFolder("forecasts")
        val storedAt = GeoLocation(
            id = "device:abc",
            name = "Current Location",
            latitude = 52.3700,
            longitude = 4.8900,
            timezone = "Europe/Amsterdam",
        )
        val bundle = sampleBundle(storedAt)
        ForecastCache(cacheDir, json).write(storedAt.id, bundle)

        val jittered = storedAt.copy(
            id = "device:xyz",
            latitude = 52.3705,
            longitude = 4.8905,
        )
        val reader = ForecastCache(cacheDir, json)
        assertThat(reader.read(jittered.id)).isNull()
        val nearest = reader.findNearest(jittered)
        assertThat(nearest).isNotNull()
        assertThat(nearest!!.location.latitude).isWithin(0.001).of(storedAt.latitude)
        assertThat(nearest.ensemble?.memberCount).isEqualTo(51)
    }

    private fun sampleBundle(location: GeoLocation): WeatherBundle {
        val models = ForecastModelId.entries.map { model ->
            ModelForecast(
                model = model,
                available = true,
                daily = listOf(
                    DailyPoint(
                        date = date,
                        tempMaxC = 12.0,
                        tempMinC = 6.0,
                        precipitationSumMm = 5.0,
                        precipitationProbabilityMax = 80,
                    ),
                ),
                hourly = listOf(
                    HourlyPoint(
                        timeEpochMs = 1_700_000_000_000L,
                        timeIso = "${date}T12:00",
                        temperatureC = 11.0,
                        precipitationProbability = 70,
                    ),
                ),
                fetchedAtEpochMs = 1_700_000_000_000L,
            )
        }
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
        val consensusDaily = ConsensusEngine.buildConsensusDaily(models)
        val consensusHourly = ConsensusEngine.buildConsensusHourly(models, 48)
        val confidence = ConfidenceBreakdown(
            score = 72,
            category = ConfidenceCategory.MODERATE,
            deterministicWeightUsed = 0.4,
            ensembleWeightUsed = 0.3,
            leadTimeWeightUsed = 0.2,
            stabilityWeightUsed = 0.1,
            buildingHistory = false,
            notes = listOf("test"),
        )
        return WeatherBundle(
            location = location,
            fetchedAtEpochMs = 1_700_000_000_000L,
            models = models,
            ensemble = ens,
            ensembleByDate = mapOf(date to ens),
            consensusHourly = consensusHourly,
            consensusDaily = consensusDaily,
            confidenceByDate = mapOf(date to confidence),
            fromCache = false,
            stale = false,
        )
    }
}
