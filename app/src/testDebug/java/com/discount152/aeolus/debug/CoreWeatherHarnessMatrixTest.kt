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
import com.discount152.aeolus.ui.state.ConnectivityPresentation
import com.discount152.aeolus.ui.state.WeatherCopy
import com.discount152.aeolus.ui.state.computeConnectivityPresentation
import com.google.common.truth.Truth.assertThat
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
 * Charter core-weather matrix through the real repository + Retrofit + debug harness.
 * Gate omitted so HTTP_429 is one HttpException (debug APK still runs 429 through the gate).
 */
class CoreWeatherHarnessMatrixTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }

    private val groningen = LocationIdentity.withStableId(
        GeoLocation("geo:2755251", "Groningen", 53.21917, 6.56667, "Europe/Amsterdam", "Netherlands", "Groningen"),
    )
    private val side = LocationIdentity.withStableId(
        GeoLocation("geo:300737", "Side", 36.76667, 31.38889, "Europe/Istanbul", "Turkey", "Antalya"),
    )
    private val groningenPin = LocationIdentity.withStableId(
        GeoLocation("map:53.230,6.550", "Map pin", 53.230, 6.550, "Europe/Amsterdam"),
    )
    private val sidePin = LocationIdentity.withStableId(
        GeoLocation("map:36.770,31.390", "Map pin", 36.770, 31.390, "Europe/Istanbul"),
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
    fun successFull_groningen_todayForecastModelsLive() = runTest {
        assertSuccessFullCity(groningen, expectEnsemble = true, expectAllModels = true)
    }

    @Test
    fun successFull_side_todayForecastModelsLive() = runTest {
        assertSuccessFullCity(side, expectEnsemble = true, expectAllModels = true)
    }

    @Test
    fun successFull_groningenAreaPin_coherentIconOnly() = runTest {
        assertSuccessFullCity(groningenPin, expectEnsemble = false, expectAllModels = false)
    }

    @Test
    fun successFull_sideAreaPin_coherentIconOnly() = runTest {
        assertSuccessFullCity(sidePin, expectEnsemble = false, expectAllModels = false)
    }

    @Test
    fun search_groningenAndSide_returnCorrectCity() = runTest {
        DebugWeatherHarness.scenario = DebugWeatherScenario.SUCCESS_FULL
        val repo = repo()
        val gro = repo.searchLocations("Groningen")
        val sid = repo.searchLocations("Side")
        assertThat(gro).isNotEmpty()
        assertThat(gro.first().name).isEqualTo("Groningen")
        assertThat(LocationIdentity.stableId(gro.first())).isEqualTo(LocationIdentity.stableId(groningen))
        assertThat(sid).isNotEmpty()
        assertThat(sid.first().name).isEqualTo("Side")
        assertThat(LocationIdentity.stableId(sid.first())).isEqualTo(LocationIdentity.stableId(side))
    }

    @Test
    fun http429_sameLocationCache_staleLastUpdated_notOffline() = runTest {
        DebugWeatherHarness.scenario = DebugWeatherScenario.HTTP_429
        val cache = ForecastCache(tmp.newFolder("forecasts-429-cache"), json)
        val prior = sampleBundle(groningen, fetchedAt = 1_700_000_000_000L)
        cache.write(LocationIdentity.cacheKey(groningen), prior)
        val result = repo(cache = cache).getWeather(groningen, forceRefresh = true)

        assertThat(result.stale).isTrue()
        assertThat(result.fromCache).isTrue()
        assertThat(result.consensusDaily).isNotEmpty()
        assertThat(result.consensusHourly).isNotEmpty()
        assertThat(result.fetchedAtEpochMs).isEqualTo(prior.fetchedAtEpochMs)
        assertThat(LocationIdentity.stableId(result.location)).isEqualTo(LocationIdentity.stableId(groningen))
        assertPresentation(
            bundle = result,
            error = "Showing cached forecast (refresh failed).",
            expected = ConnectivityPresentation.STALE_WITH_CACHE,
        )
    }

    @Test
    fun http429_noCache_providerBusy_noFakeLocation() = runTest {
        DebugWeatherHarness.scenario = DebugWeatherScenario.HTTP_429
        val repo = repo()
        repo.publishBootSnapshot(sampleBundle(groningen))
        val thrown = runCatching { repo.getWeather(side, forceRefresh = true) }.exceptionOrNull()

        assertThat(thrown).isNotNull()
        assertThat(repo.weather.value).isNull()
        assertThat(WeatherCopy.allowManualRetry(1_000L, 1_000L + 1_999L)).isFalse()
        assertThat(WeatherCopy.allowManualRetry(1_000L, 1_000L + 2_000L)).isTrue()
        assertThat(WeatherCopy.PROVIDER_BUSY).doesNotContain("429")
        assertThat(WeatherCopy.PROVIDER_BUSY).doesNotContain("OFFLINE")
        assertPresentation(
            bundle = null,
            error = WeatherCopy.PROVIDER_BUSY,
            expected = ConnectivityPresentation.ONLINE_ERROR_NO_CACHE,
        )
    }

    @Test
    fun empty200_retainsCacheAndLkg() = runTest {
        DebugWeatherHarness.scenario = DebugWeatherScenario.SUCCESS_FULL
        val cache = ForecastCache(tmp.newFolder("forecasts-empty"), json)
        val lkg = lkgStore()
        val repo = repo(cache = cache, lkgStore = lkg)
        val live = repo.getWeather(groningen, forceRefresh = true)
        assertThat(WeatherRepository.isCoherentLiveBundle(live)).isTrue()
        val lkgBefore = lkg.read()!!

        DebugWeatherHarness.scenario = DebugWeatherScenario.EMPTY_200
        val retained = repo.getWeather(groningen, forceRefresh = true)

        assertThat(retained.stale).isTrue()
        assertThat(retained.fromCache).isTrue()
        assertThat(WeatherRepository.isCoherentLiveBundle(retained)).isTrue()
        assertThat(LocationIdentity.stableId(retained.location)).isEqualTo(LocationIdentity.stableId(groningen))
        val lkgAfter = lkg.read()!!
        assertThat(lkgAfter.lastSuccessfulRefreshEpochMs).isEqualTo(lkgBefore.lastSuccessfulRefreshEpochMs)
        assertThat(WeatherRepository.isCoherentLiveBundle(lkgAfter.bundle)).isTrue()
        assertPresentation(
            bundle = retained,
            error = "Showing cached forecast (refresh failed).",
            expected = ConnectivityPresentation.STALE_WITH_CACHE,
        )
    }

    @Test
    fun timeout_sameAsOnlineProviderFailure() = runTest {
        DebugWeatherHarness.scenario = DebugWeatherScenario.SUCCESS_FULL
        val cache = ForecastCache(tmp.newFolder("forecasts-timeout"), json)
        val repo = repo(cache = cache)
        repo.getWeather(groningen, forceRefresh = true)

        DebugWeatherHarness.scenario = DebugWeatherScenario.TIMEOUT
        val stale = repo.getWeather(groningen, forceRefresh = true)
        assertThat(stale.stale).isTrue()
        assertThat(stale.fromCache).isTrue()
        assertThat(LocationIdentity.stableId(stale.location)).isEqualTo(LocationIdentity.stableId(groningen))

        val emptyRepo = repo()
        val thrown = runCatching { emptyRepo.getWeather(side, forceRefresh = true) }.exceptionOrNull()
        assertThat(thrown).isNotNull()
        assertThat(emptyRepo.weather.value).isNull()
        assertPresentation(
            bundle = null,
            error = WeatherCopy.PROVIDER_BUSY,
            expected = ConnectivityPresentation.ONLINE_ERROR_NO_CACHE,
        )
    }

    @Test
    fun partialModelFailure_coreUsableModelsDegraded() = runTest {
        DebugWeatherHarness.scenario = DebugWeatherScenario.PARTIAL_MODEL_FAILURE
        val bundle = repo().getWeather(groningen, forceRefresh = true)
        assertThat(WeatherRepository.isCoherentLiveBundle(bundle)).isTrue()
        assertThat(bundle.stale).isFalse()
        assertThat(bundle.models.first { it.model == ForecastModelId.ICON }.available).isTrue()
        assertThat(bundle.models.first { it.model == ForecastModelId.GFS }.available).isTrue()
        assertThat(bundle.models.first { it.model == ForecastModelId.ECMWF }.available).isFalse()
        assertThat(bundle.consensusDaily.first().missingModels).contains(ForecastModelId.ECMWF)
        assertThat(bundle.consensusDaily.first().availableModels)
            .containsAtLeast(ForecastModelId.ICON, ForecastModelId.GFS)
        assertPresentation(bundle = bundle, error = null, expected = ConnectivityPresentation.LIVE)
    }

    @Test
    fun locationRace_groningenThenSide_lateGroningenNeverDisplaysAsSide() = runTest {
        DebugWeatherHarness.scenario = DebugWeatherScenario.SUCCESS_FULL
        DebugWeatherHarness.slowDelayMs = 40L
        val repo = repo()

        val first = async {
            DebugWeatherHarness.scenario = DebugWeatherScenario.SLOW_SUCCESS
            repo.getWeather(groningen, forceRefresh = true)
        }
        delay(10)
        val second = async {
            DebugWeatherHarness.scenario = DebugWeatherScenario.SUCCESS_FULL
            repo.getWeather(side, forceRefresh = true)
        }
        val a = first.await()
        val b = second.await()

        assertThat(LocationIdentity.stableId(a.location)).isEqualTo(LocationIdentity.stableId(groningen))
        assertThat(LocationIdentity.stableId(b.location)).isEqualTo(LocationIdentity.stableId(side))
        val published = repo.weather.value!!
        assertThat(LocationIdentity.stableId(published.location)).isEqualTo(LocationIdentity.stableId(side))
        assertThat(uiMatching(published, side)).isNotNull()
        assertThat(uiMatching(a, side)).isNull()
        assertThat(repo.cachedFor(groningen)).isNotNull()
        assertThat(repo.cachedFor(side)).isNotNull()
    }

    @Test
    fun locationRace_pinThenSaved_wrongBundleFiltered() = runTest {
        DebugWeatherHarness.scenario = DebugWeatherScenario.SUCCESS_FULL
        val repo = repo()
        val pinBundle = repo.getWeather(sidePin, forceRefresh = true)
        val cityBundle = repo.getWeather(groningen, forceRefresh = true)
        assertThat(uiMatching(pinBundle, groningen)).isNull()
        assertThat(uiMatching(cityBundle, sidePin)).isNull()
        assertThat(uiMatching(cityBundle, groningen)).isNotNull()
        assertThat(LocationIdentity.stableId(repo.weather.value!!.location))
            .isEqualTo(LocationIdentity.stableId(groningen))
    }

    private suspend fun assertSuccessFullCity(
        location: GeoLocation,
        expectEnsemble: Boolean,
        expectAllModels: Boolean,
    ) {
        DebugWeatherHarness.scenario = DebugWeatherScenario.SUCCESS_FULL
        val lkg = lkgStore()
        val repo = repo(lkgStore = lkg)
        val bundle = repo.getWeather(location, forceRefresh = true)

        assertThat(WeatherRepository.isCoherentLiveBundle(bundle)).isTrue()
        assertThat(bundle.stale).isFalse()
        assertThat(bundle.fromCache).isFalse()
        assertThat(bundle.consensusDaily).isNotEmpty()
        assertThat(bundle.consensusHourly).isNotEmpty()
        assertThat(bundle.consensusDaily.first().availableModels).isNotEmpty()
        assertThat(LocationIdentity.stableId(bundle.location)).isEqualTo(LocationIdentity.stableId(location))
        if (expectAllModels) {
            assertThat(bundle.models.count { it.available }).isEqualTo(3)
            assertThat(bundle.models.filter { it.available }.map { it.model })
                .containsExactly(ForecastModelId.ECMWF, ForecastModelId.ICON, ForecastModelId.GFS)
        } else {
            assertThat(bundle.models.first { it.model == ForecastModelId.ICON }.available).isTrue()
        }
        if (expectEnsemble) {
            assertThat(bundle.ensemble).isNotNull()
        } else {
            assertThat(bundle.ensemble).isNull()
        }
        assertThat(lkg.read()).isNotNull()
        assertPresentation(bundle = bundle, error = null, expected = ConnectivityPresentation.LIVE)
        assertThat(computeConnectivityPresentation(false, false, null, bundle, false))
            .isEqualTo(ConnectivityPresentation.OFFLINE_WITH_CACHE)
    }

    private fun uiMatching(bundle: WeatherBundle, active: GeoLocation): WeatherBundle? =
        bundle.takeIf {
            LocationIdentity.stableId(it.location) == LocationIdentity.stableId(active)
        }

    private fun assertPresentation(
        bundle: WeatherBundle?,
        error: String?,
        expected: ConnectivityPresentation,
        networkValidated: Boolean = true,
    ) {
        val presentation = computeConnectivityPresentation(
            loading = false,
            refreshing = false,
            error = error,
            bundle = bundle,
            networkValidated = networkValidated,
        )
        assertThat(presentation).isEqualTo(expected)
        assertThat(presentation).isNotEqualTo(ConnectivityPresentation.OFFLINE_NO_CACHE)
        if (networkValidated) {
            assertThat(presentation).isNotEqualTo(ConnectivityPresentation.OFFLINE_WITH_CACHE)
        }
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

    private fun sampleBundle(location: GeoLocation, fetchedAt: Long = 1_700_000_000_000L): WeatherBundle {
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
            fetchedAtEpochMs = fetchedAt,
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
