package com.discount152.aeolus.debug

import com.discount152.aeolus.data.cache.ForecastCache
import com.discount152.aeolus.data.cache.LastKnownGoodStore
import com.discount152.aeolus.data.network.OpenMeteoEnsembleApi
import com.discount152.aeolus.data.network.OpenMeteoForecastApi
import com.discount152.aeolus.data.network.OpenMeteoGeocodingApi
import com.discount152.aeolus.data.repository.WeatherRepository
import com.discount152.aeolus.domain.consensus.ConsensusEngine
import com.discount152.aeolus.domain.models.DailyPoint
import com.discount152.aeolus.domain.models.ForecastModelId
import com.discount152.aeolus.domain.models.GeoLocation
import com.discount152.aeolus.domain.models.HourlyPoint
import com.discount152.aeolus.domain.models.ModelForecast
import com.discount152.aeolus.domain.models.WeatherBundle
import com.discount152.aeolus.location.LocationIdentity
import com.google.common.truth.Truth.assertThat
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit
import java.io.File

/**
 * Harness through the real [WeatherRepository] + Retrofit + OkHttp path.
 * Gate is omitted here so HTTP_429 is a single HttpException, not a 10s retry loop.
 * Debug APK still installs the interceptor after [com.discount152.aeolus.data.network.OpenMeteoGate].
 */
class DebugWeatherHarnessRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }
    private val groningen = LocationIdentity.withStableId(
        GeoLocation("geo:2755251", "Groningen", 53.22, 6.57, "Europe/Amsterdam", "Netherlands"),
    )

    @Before
    fun setUp() {
        DebugWeatherHarness.reset()
    }

    @After
    fun tearDown() {
        DebugWeatherHarness.reset()
    }

    @Test
    fun successFull_coherentLiveWritesLkg() = runTest {
        DebugWeatherHarness.scenario = DebugWeatherScenario.SUCCESS_FULL
        val lkg = lkgStore()
        val repo = repo(lkgStore = lkg)

        val bundle = repo.getWeather(groningen, forceRefresh = true)

        assertThat(WeatherRepository.isCoherentLiveBundle(bundle)).isTrue()
        assertThat(bundle.stale).isFalse()
        assertThat(bundle.fromCache).isFalse()
        assertThat(bundle.models.count { it.available }).isEqualTo(3)
        assertThat(bundle.models.filter { it.available }.map { it.model })
            .containsExactly(ForecastModelId.ECMWF, ForecastModelId.ICON, ForecastModelId.GFS)
        assertThat(bundle.consensusDaily).isNotEmpty()
        assertThat(bundle.consensusHourly).isNotEmpty()
        assertThat(bundle.ensemble).isNotNull()
        assertThat(lkg.read()).isNotNull()
    }

    @Test
    fun successCoreOnly_iconOnlyCoherent() = runTest {
        DebugWeatherHarness.scenario = DebugWeatherScenario.SUCCESS_CORE_ONLY
        val bundle = repo().getWeather(groningen, forceRefresh = true)

        assertThat(WeatherRepository.isCoherentLiveBundle(bundle)).isTrue()
        assertThat(bundle.models.first { it.model == ForecastModelId.ICON }.available).isTrue()
        assertThat(bundle.models.first { it.model == ForecastModelId.ECMWF }.available).isFalse()
        assertThat(bundle.models.first { it.model == ForecastModelId.GFS }.available).isFalse()
        assertThat(bundle.consensusDaily.first().availableModels).containsExactly(ForecastModelId.ICON)
        assertThat(bundle.consensusDaily.first().missingModels)
            .containsAtLeast(ForecastModelId.ECMWF, ForecastModelId.GFS)
        assertThat(bundle.ensemble).isNull()
    }

    @Test
    fun partialModelFailure_ecmwfMissingLiveOk() = runTest {
        DebugWeatherHarness.scenario = DebugWeatherScenario.PARTIAL_MODEL_FAILURE
        val bundle = repo().getWeather(groningen, forceRefresh = true)

        assertThat(WeatherRepository.isCoherentLiveBundle(bundle)).isTrue()
        assertThat(bundle.models.first { it.model == ForecastModelId.ECMWF }.available).isFalse()
        assertThat(bundle.models.first { it.model == ForecastModelId.ICON }.available).isTrue()
        assertThat(bundle.models.first { it.model == ForecastModelId.GFS }.available).isTrue()
        assertThat(bundle.consensusDaily.first().missingModels).contains(ForecastModelId.ECMWF)
        assertThat(bundle.stale).isFalse()
    }

    @Test
    fun empty200_incoherentThrowsWithoutCache() = runTest {
        DebugWeatherHarness.scenario = DebugWeatherScenario.EMPTY_200
        val thrown = runCatching { repo().getWeather(groningen, forceRefresh = true) }.exceptionOrNull()
        assertThat(thrown).isNotNull()
        assertThat(thrown!!.message).contains("Incoherent")
    }

    @Test
    fun http500_incoherentThrowsWithoutCache() = runTest {
        DebugWeatherHarness.scenario = DebugWeatherScenario.HTTP_500
        val thrown = runCatching { repo().getWeather(groningen, forceRefresh = true) }.exceptionOrNull()
        assertThat(thrown).isNotNull()
    }

    @Test
    fun timeout_incoherentThrowsWithoutCache() = runTest {
        DebugWeatherHarness.scenario = DebugWeatherScenario.TIMEOUT
        val thrown = runCatching { repo().getWeather(groningen, forceRefresh = true) }.exceptionOrNull()
        assertThat(thrown).isNotNull()
    }

    @Test
    fun http429_sameLocationCache_stale() = runTest {
        DebugWeatherHarness.scenario = DebugWeatherScenario.HTTP_429
        val cache = ForecastCache(tmp.newFolder("forecasts-429"), json)
        cache.write(LocationIdentity.cacheKey(groningen), sampleBundle(groningen))
        val result = repo(cache = cache).getWeather(groningen, forceRefresh = true)
        assertThat(result.stale).isTrue()
        assertThat(result.fromCache).isTrue()
        assertThat(LocationIdentity.stableId(result.location)).isEqualTo(LocationIdentity.stableId(groningen))
    }

    @Test
    fun slowSuccess_returnsLive() = runTest {
        DebugWeatherHarness.scenario = DebugWeatherScenario.SLOW_SUCCESS
        DebugWeatherHarness.slowDelayMs = 20L
        val bundle = repo().getWeather(groningen, forceRefresh = true)
        assertThat(WeatherRepository.isCoherentLiveBundle(bundle)).isTrue()
        assertThat(bundle.stale).isFalse()
    }

    @Test
    fun searchLocations_successFull_returnsGroningen() = runTest {
        DebugWeatherHarness.scenario = DebugWeatherScenario.SUCCESS_FULL
        val results = repo().searchLocations("Groningen")
        assertThat(results).isNotEmpty()
        assertThat(results.first().name).isEqualTo("Groningen")
    }

    private fun repo(
        cache: ForecastCache = ForecastCache(tmp.newFolder("forecasts-${System.nanoTime()}"), json),
        lkgStore: LastKnownGoodStore = lkgStore(),
    ): WeatherRepository {
        val client = OkHttpClient.Builder()
            .addInterceptor(DebugWeatherHarness.interceptor)
            .build()
        val media = "application/json".toMediaType()
        fun retrofit(base: String) = Retrofit.Builder()
            .baseUrl(base)
            .client(client)
            .addConverterFactory(json.asConverterFactory(media))
            .build()
        return WeatherRepository(
            forecastApi = retrofit("https://api.open-meteo.com/").create(OpenMeteoForecastApi::class.java),
            ensembleApi = retrofit("https://ensemble-api.open-meteo.com/").create(OpenMeteoEnsembleApi::class.java),
            geocodingApi = retrofit("https://geocoding-api.open-meteo.com/").create(OpenMeteoGeocodingApi::class.java),
            cache = cache,
            snapshots = mockk(relaxed = true),
            lastKnownGoodStore = lkgStore,
            okHttp = client,
            json = json,
        )
    }

    private fun lkgStore(): LastKnownGoodStore =
        LastKnownGoodStore(File(tmp.newFolder("lkg-${System.nanoTime()}"), "last_known_good.json"), json)

    private fun sampleBundle(location: GeoLocation): WeatherBundle {
        val models = listOf(
            ModelForecast(
                model = ForecastModelId.ICON,
                available = true,
                hourly = listOf(HourlyPoint(1L, "t", temperatureC = 10.0, precipitationMm = 0.0)),
                daily = listOf(DailyPoint("2026-10-23", tempMaxC = 12.0, tempMinC = 6.0, precipitationSumMm = 1.0)),
                fetchedAtEpochMs = 1L,
            ),
        )
        return WeatherBundle(
            location = location,
            fetchedAtEpochMs = 1_700_000_000_000L,
            models = models,
            ensemble = null,
            consensusHourly = ConsensusEngine.buildConsensusHourly(models, 48),
            consensusDaily = ConsensusEngine.buildConsensusDaily(models),
            confidenceByDate = emptyMap(),
            fromCache = false,
            stale = false,
        )
    }
}
