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
 * Cold-start coordinator test: seeds durable prefs + ForecastCache, destroys graph,
 * rebuilds, runs offline startup with the same cache-key logic as production.
 */
class StartupCoordinatorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }
    private val date = "2026-10-22"

    @Test
    fun coldStartOffline_restoresOfflineWithCache_usingProductionCacheKey() = runTest {
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
        val cacheKey = LocationIdentity.cacheKey(location)
        val bundle = sampleBundle(location)

        // --- Session 1: write durable state ---
        val prefsFile = File(tmp.newFolder("prefs1"), "aeolus.preferences_pb")
        val session1Job = Job()
        val settings1 = settingsRepo(prefsFile, session1Job)
        val cacheDir = tmp.newFolder("forecasts")
        val cache1 = ForecastCache(cacheDir, json)
        cache1.write(cacheKey, bundle)
        settings1.setActiveLocation(location)
        settings1.setSavedLocations(listOf(location))
        settings1.setLocationMigrationVersion(SettingsRepository.CURRENT_LOCATION_MIGRATION_VERSION)
        // Ensure DataStore flushes, then tear down session 1 (process death)
        settings1.activeLocation.first()
        session1Job.cancel()

        assertThat(File(cacheDir, cacheKey.replace(Regex("[^a-zA-Z0-9._-]"), "_") + ".json").isFile).isTrue()

        // --- Session 2: brand-new settings + repository (process death) ---
        val session2Job = Job()
        val settings2 = settingsRepo(prefsFile, session2Job)
        val cache2 = ForecastCache(cacheDir, json)
        val lkgFile = File(tmp.newFolder("lkg"), "last_known_good.json")
        val lkgStore = LastKnownGoodStore(lkgFile, json)
        val repo2 = WeatherRepository(
            forecastApi = mockk(relaxed = true),
            ensembleApi = mockk(relaxed = true),
            geocodingApi = mockk(relaxed = true),
            cache = cache2,
            snapshots = mockk(relaxed = true),
            lastKnownGoodStore = lkgStore,
            okHttp = OkHttpClient(),
            json = json,
        )
        val device = object : DeviceLocationSource {
            override fun hasPermission(): Boolean = false
            override suspend fun currentLocation(): GeoLocation? = null
        }
        val coordinator = StartupCoordinator(settings2, device, repo2, lkgStore)
        val result = coordinator.coldStartOffline(permissionGranted = false)
        session2Job.cancel()

        assertThat(result.cacheKey).isEqualTo(cacheKey)
        assertThat(result.location.id).isEqualTo(location.id)
        assertThat(result.fromCache).isTrue()
        assertThat(result.bundle).isNotNull()
        val restored = result.bundle!!
        assertThat(restored.stale).isTrue()
        assertThat(restored.consensusDaily).isNotEmpty()
        assertThat(restored.consensusHourly).isNotEmpty()
        assertThat(restored.models).hasSize(3)
        assertThat(restored.ensemble?.memberCount).isEqualTo(51)
        assertThat(restored.confidenceByDate[date]?.score).isEqualTo(72)
        assertThat(restored.fetchedAtEpochMs).isEqualTo(bundle.fetchedAtEpochMs)

        val presentation = computeConnectivityPresentation(
            loading = false,
            refreshing = false,
            error = "Showing cached forecast (refresh failed).",
            bundle = restored,
        )
        assertThat(presentation).isEqualTo(ConnectivityPresentation.OFFLINE_WITH_CACHE)
        assertThat(presentation).isNotEqualTo(ConnectivityPresentation.ERROR_NO_CACHE)

        // Repository shared state also populated
        assertThat(repo2.weather.first()).isNotNull()
    }

    @Test
    fun cacheKey_deviceJitter_sameBucket() {
        val a = GeoLocation("device:1", "Current location", 52.3701, 4.8901, "Europe/Amsterdam")
        val b = GeoLocation("device:2", "Current location", 52.3704, 4.8904, "Europe/Amsterdam")
        assertThat(LocationIdentity.cacheKey(a)).isEqualTo(LocationIdentity.cacheKey(b))
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
