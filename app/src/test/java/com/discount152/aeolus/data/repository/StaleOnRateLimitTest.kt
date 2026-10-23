package com.discount152.aeolus.data.repository

import com.discount152.aeolus.data.cache.ForecastCache
import com.discount152.aeolus.data.cache.LastKnownGoodStore
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
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Diagnosis §12.1: live 429 / incoherent must show same-location cache, in-memory prior,
 * or matching LKG as STALE_WITH_CACHE — never another location, never OFFLINE.
 */
class StaleOnRateLimitTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }
    private val groningen = LocationIdentity.withStableId(
        GeoLocation("geo:2755251", "Groningen", 53.22, 6.57, "Europe/Amsterdam", "Netherlands"),
    )
    private val pin = LocationIdentity.withStableId(
        GeoLocation("map:36.8,31.4", "Map pin", 36.8, 31.4, "Europe/Istanbul"),
    )

    @Test
    fun live429_sameLocationCache_returnsStaleWithCache() = runTest {
        val cache = ForecastCache(tmp.newFolder("forecasts-cache"), json)
        cache.write(LocationIdentity.cacheKey(groningen), sampleBundle(groningen))
        val repo = failingRepo(cache = cache)

        val result = repo.getWeather(groningen, forceRefresh = true)

        assertThat(result.stale).isTrue()
        assertThat(result.fromCache).isTrue()
        assertThat(LocationIdentity.stableId(result.location)).isEqualTo(LocationIdentity.stableId(groningen))
        assertThat(result.consensusDaily).isNotEmpty()
        assertThat(result.consensusHourly).isNotEmpty()
        assertStaleNotOfflineOrBlank(result)
    }

    @Test
    fun live429_matchingLkg_returnsStaleWhenCacheMissing() = runTest {
        val lkg = LastKnownGoodStore(File(tmp.newFolder("lkg"), "last_known_good.json"), json)
        assertThat(lkg.write(sampleBundle(groningen))).isTrue()
        val repo = failingRepo(lkgStore = lkg)

        val result = repo.getWeather(groningen, forceRefresh = true)

        assertThat(result.stale).isTrue()
        assertThat(result.fromCache).isTrue()
        assertThat(LocationIdentity.stableId(result.location)).isEqualTo(LocationIdentity.stableId(groningen))
        assertStaleNotOfflineOrBlank(result)
    }

    @Test
    fun live429_otherLocationLkg_doesNotHydrate() = runTest {
        val lkg = LastKnownGoodStore(File(tmp.newFolder("lkg-cross"), "last_known_good.json"), json)
        assertThat(lkg.write(sampleBundle(groningen))).isTrue()
        val repo = failingRepo(lkgStore = lkg)

        val thrown = runCatching { repo.getWeather(pin, forceRefresh = true) }.exceptionOrNull()

        assertThat(thrown).isNotNull()
        assertThat(repo.weather.value).isNull()
        val presentation = computeConnectivityPresentation(
            loading = false,
            refreshing = false,
            error = WeatherCopy.PROVIDER_BUSY,
            bundle = null,
            networkValidated = true,
        )
        assertThat(presentation).isEqualTo(ConnectivityPresentation.ONLINE_ERROR_NO_CACHE)
        assertThat(presentation).isNotEqualTo(ConnectivityPresentation.OFFLINE_NO_CACHE)
        assertThat(WeatherCopy.PROVIDER_BUSY).doesNotContain("429")
        assertThat(WeatherCopy.PROVIDER_BUSY).doesNotContain("OFFLINE")
        assertThat(WeatherCopy.PROVIDER_BUSY_HINT).doesNotContain("429")
    }

    @Test
    fun live429_sameLocationInMemoryPrior_retainedWhenCacheAndLkgMissing() = runTest {
        val repo = failingRepo()
        repo.publishBootSnapshot(sampleBundle(groningen))

        val result = repo.getWeather(groningen, forceRefresh = true)

        assertThat(result.stale).isTrue()
        assertThat(LocationIdentity.stableId(result.location)).isEqualTo(LocationIdentity.stableId(groningen))
        assertStaleNotOfflineOrBlank(result)
    }

    @Test
    fun lkgWrite_refusesIncoherent_andKeepsPrior() {
        val lkg = LastKnownGoodStore(File(tmp.newFolder("lkg-refuse"), "last_known_good.json"), json)
        val good = sampleBundle(groningen)
        assertThat(lkg.write(good)).isTrue()
        val empty = good.copy(
            models = ForecastModelId.entries.map { ModelForecast(it, available = false) },
            consensusDaily = emptyList(),
            consensusHourly = emptyList(),
        )
        assertThat(lkg.write(empty)).isFalse()
        val reread = lkg.read()
        assertThat(reread).isNotNull()
        assertThat(reread!!.bundle.consensusDaily).isNotEmpty()
        assertThat(LocationIdentity.stableId(reread.location)).isEqualTo(LocationIdentity.stableId(groningen))
    }

    @Test
    fun retryGuard_blocksHammering() {
        assertThat(WeatherCopy.allowManualRetry(1_000L, 1_000L + 1_999L)).isFalse()
        assertThat(WeatherCopy.allowManualRetry(1_000L, 1_000L + 2_000L)).isTrue()
    }

    private fun assertStaleNotOfflineOrBlank(bundle: WeatherBundle) {
        val presentation = computeConnectivityPresentation(
            loading = false,
            refreshing = false,
            error = "Showing cached forecast (refresh failed).",
            bundle = bundle.copy(stale = true),
            networkValidated = true,
        )
        assertThat(presentation).isEqualTo(ConnectivityPresentation.STALE_WITH_CACHE)
        assertThat(presentation).isNotEqualTo(ConnectivityPresentation.ONLINE_ERROR_NO_CACHE)
        assertThat(presentation).isNotEqualTo(ConnectivityPresentation.OFFLINE_WITH_CACHE)
        assertThat(presentation).isNotEqualTo(ConnectivityPresentation.OFFLINE_NO_CACHE)
    }

    private fun failingRepo(
        cache: ForecastCache = ForecastCache(tmp.newFolder("forecasts-${System.nanoTime()}"), json),
        lkgStore: LastKnownGoodStore = LastKnownGoodStore(
            File(tmp.newFolder("lkg-${System.nanoTime()}"), "last_known_good.json"),
            json,
        ),
    ): WeatherRepository {
        val forecastApi = mockk<com.discount152.aeolus.data.network.OpenMeteoForecastApi>(relaxed = true)
        coEvery {
            forecastApi.forecast(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } throws IOException("Open-Meteo HTTP 429 after 2 retries")
        return WeatherRepository(
            forecastApi = forecastApi,
            ensembleApi = mockk(relaxed = true),
            geocodingApi = mockk(relaxed = true),
            cache = cache,
            snapshots = mockk(relaxed = true),
            lastKnownGoodStore = lkgStore,
            okHttp = OkHttpClient(),
            json = json,
        )
    }

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
