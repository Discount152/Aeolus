package com.discount152.aeolus.ui.state

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.discount152.aeolus.data.cache.ForecastCache
import com.discount152.aeolus.data.cache.LastKnownGoodStore
import com.discount152.aeolus.data.prefs.SettingsRepository
import com.discount152.aeolus.data.repository.WeatherRepository
import com.discount152.aeolus.domain.consensus.ConsensusEngine
import com.discount152.aeolus.domain.models.ConfidenceBreakdown
import com.discount152.aeolus.domain.models.ConfidenceCategory
import com.discount152.aeolus.domain.models.DailyPoint
import com.discount152.aeolus.domain.models.EnsembleMemberSummary
import com.discount152.aeolus.domain.models.ForecastModelId
import com.discount152.aeolus.domain.models.GeoLocation
import com.discount152.aeolus.domain.models.HourlyPoint
import com.discount152.aeolus.domain.models.ModelForecast
import com.discount152.aeolus.domain.models.WeatherBundle
import com.discount152.aeolus.location.DeviceLocationSource
import com.discount152.aeolus.location.LocationIdentity
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Production cold-start path: [LastKnownGoodStore] only — empty [ForecastCache] and no network.
 */
class LastKnownGoodColdStartTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }
    private val date = "2026-10-22"

    @Test
    fun coldStartOffline_bootsFromLastKnownGood_withoutLocationCacheOrNetwork() = runTest {
        val location = LocationIdentity.withStableId(
            GeoLocation(
                id = "geo:2759794",
                name = "Amsterdam",
                latitude = 52.37,
                longitude = 4.89,
                timezone = "Europe/Amsterdam",
                country = "Netherlands",
                admin1 = "North Holland",
            ),
        )
        val bundle = sampleBundle(location)

        val lkgFile = File(tmp.newFolder("lkg-session1"), "last_known_good.json")
        LastKnownGoodStore(lkgFile, json).write(bundle)

        val emptyCacheDir = tmp.newFolder("forecasts-empty")
        assertThat(File(emptyCacheDir, ".").list()?.filter { it.endsWith(".json") }).isEmpty()

        val prefsFile = File(tmp.newFolder("prefs"), "aeolus.preferences_pb")
        val job = Job()
        val settings = settingsRepo(prefsFile, job)

        val forecastApi = mockk<com.discount152.aeolus.data.network.OpenMeteoForecastApi>(relaxed = true)
        val ensembleApi = mockk<com.discount152.aeolus.data.network.OpenMeteoEnsembleApi>(relaxed = true)
        val geocodingApi = mockk<com.discount152.aeolus.data.network.OpenMeteoGeocodingApi>(relaxed = true)
        coEvery { forecastApi.forecast(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws java.io.IOException("offline")
        coEvery {
            ensembleApi.ensemble(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } throws java.io.IOException("offline")

        val repo = WeatherRepository(
            forecastApi = forecastApi,
            ensembleApi = ensembleApi,
            geocodingApi = geocodingApi,
            cache = ForecastCache(emptyCacheDir, json),
            snapshots = mockk(relaxed = true),
            lastKnownGoodStore = LastKnownGoodStore(lkgFile, json),
            okHttp = OkHttpClient(),
            json = json,
        )
        val device = object : DeviceLocationSource {
            override fun hasPermission(): Boolean = false
            override suspend fun currentLocation(): GeoLocation? = null
        }
        val coordinator = StartupCoordinator(
            settings = settings,
            locationService = device,
            weatherRepository = repo,
            lastKnownGoodStore = LastKnownGoodStore(lkgFile, json),
        )

        val result = coordinator.coldStartOffline(permissionGranted = false)

        assertThat(result.fromCache).isTrue()
        assertThat(result.location.id).isEqualTo(location.id)
        assertThat(result.bundle).isNotNull()
        val restored = result.bundle!!
        assertThat(restored.stale).isTrue()
        assertThat(restored.fromCache).isTrue()
        assertThat(restored.consensusDaily).isNotEmpty()
        assertThat(restored.consensusHourly).isNotEmpty()
        assertThat(restored.models).hasSize(3)
        assertThat(restored.ensemble?.memberCount).isEqualTo(51)
        assertThat(restored.confidenceByDate[date]?.score).isEqualTo(72)
        assertThat(restored.fetchedAtEpochMs).isEqualTo(bundle.fetchedAtEpochMs)

        assertThat(settings.awaitActiveLocation()?.id).isEqualTo(location.id)
        assertThat(repo.weather.first()).isNotNull()
        job.cancel()
    }

    private fun settingsRepo(file: File, job: Job): SettingsRepository {
        val store = PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined + job),
            produceFile = { file },
        )
        return SettingsRepository(store, json)
    }

    private fun sampleBundle(location: GeoLocation): WeatherBundle {
        val models = listOf(
            ModelForecast(
                ForecastModelId.ECMWF, true,
                hourly = listOf(
                    HourlyPoint(
                        timeEpochMs = 1_700_000_000_000L,
                        timeIso = "${date}T12:00",
                        temperatureC = 12.0,
                        precipitationProbability = 40,
                    ),
                ),
                daily = listOf(DailyPoint(date, tempMaxC = 12.0, precipitationSumMm = 5.0)),
            ),
            ModelForecast(
                ForecastModelId.ICON, true,
                daily = listOf(DailyPoint(date, tempMaxC = 11.0, precipitationSumMm = 4.0)),
            ),
            ModelForecast(
                ForecastModelId.GFS, true,
                daily = listOf(DailyPoint(date, tempMaxC = 13.0, precipitationSumMm = 6.0)),
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
        return WeatherBundle(
            location = location,
            fetchedAtEpochMs = 1_700_000_000_000L,
            models = models,
            ensemble = ens,
            ensembleByDate = mapOf(date to ens),
            consensusHourly = models.first().hourly,
            consensusDaily = ConsensusEngine.buildConsensusDaily(models),
            confidenceByDate = mapOf(
                date to ConfidenceBreakdown(
                    score = 72,
                    category = ConfidenceCategory.MODERATE,
                    deterministicWeightUsed = 0.4,
                    ensembleWeightUsed = 0.3,
                    leadTimeWeightUsed = 0.2,
                    stabilityWeightUsed = 0.1,
                    buildingHistory = false,
                ),
            ),
        )
    }
}
