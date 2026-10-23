package com.discount152.aeolus.data.repository

import com.discount152.aeolus.data.cache.ForecastCache
import com.discount152.aeolus.data.cache.ForecastSnapshotStore
import com.discount152.aeolus.data.cache.LastKnownGoodStore
import com.discount152.aeolus.data.network.ForecastMapper
import com.discount152.aeolus.data.network.OpenMeteoEnsembleApi
import com.discount152.aeolus.data.network.OpenMeteoForecastApi
import com.discount152.aeolus.data.network.OpenMeteoGeocodingApi
import com.discount152.aeolus.domain.confidence.ConfidenceEngine
import com.discount152.aeolus.domain.consensus.ConsensusEngine
import com.discount152.aeolus.domain.models.EnsembleMemberSummary
import com.discount152.aeolus.domain.models.ForecastModelId
import com.discount152.aeolus.domain.models.GeoLocation
import com.discount152.aeolus.domain.models.ModelForecast
import com.discount152.aeolus.domain.models.WeatherBundle
import com.discount152.aeolus.domain.weather.SkyCondition
import com.discount152.aeolus.domain.weather.StabilityAnalyzer
import com.discount152.aeolus.location.LocationIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.ZoneId

class WeatherRepository(
    private val forecastApi: OpenMeteoForecastApi,
    private val ensembleApi: OpenMeteoEnsembleApi,
    private val geocodingApi: OpenMeteoGeocodingApi,
    private val cache: ForecastCache,
    private val snapshots: ForecastSnapshotStore,
    private val lastKnownGoodStore: LastKnownGoodStore,
    private val okHttp: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true },
) {
    private val mutex = Mutex()

    private val _weather = MutableStateFlow<WeatherBundle?>(null)
    val weather: StateFlow<WeatherBundle?> = _weather.asStateFlow()

    suspend fun searchLocations(query: String): List<GeoLocation> {
        if (query.isBlank()) return emptyList()
        return runCatching {
            geocodingApi.search(query.trim()).results.map {
                val raw = GeoLocation(
                    id = (it.id ?: "${it.latitude},${it.longitude}").toString(),
                    name = it.name,
                    latitude = it.latitude,
                    longitude = it.longitude,
                    timezone = it.timezone ?: "UTC",
                    country = it.country,
                    admin1 = it.admin1,
                )
                raw.copy(id = LocationIdentity.canonicalSearchId(raw))
            }
        }.getOrDefault(emptyList())
    }

    fun cachedOrNull(locationId: String): WeatherBundle? = cache.read(locationId)

    fun cachedFor(location: GeoLocation): WeatherBundle? {
        val key = LocationIdentity.cacheKey(location)
        return cache.read(key) ?: cache.read(location.id) ?: cache.findNearest(location)
    }

    /**
     * Load disk cache into shared state without network.
     * Always looks up by [LocationIdentity.cacheKey] first (same key used on write).
     * @param markStale true only when refresh failed or offline — not while a live refresh is pending.
     */
    fun hydrateFromCache(location: GeoLocation, markStale: Boolean = false): WeatherBundle? {
        val key = LocationIdentity.cacheKey(location)
        val raw = cache.read(key) ?: cache.read(location.id) ?: cache.findNearest(location)
        val cached = raw?.let { bundle ->
            enrichSkySummaries(bundle).copy(
                location = LocationIdentity.withStableId(bundle.location),
                fromCache = true,
                stale = markStale,
            )
        }
        if (cached != null) {
            _weather.value = cached
        }
        return cached
    }

    /** Offline cold-start: publish LKG snapshot without touching per-location cache keys. */
    fun publishBootSnapshot(bundle: WeatherBundle) {
        val stable = LocationIdentity.withStableId(bundle.location)
        _weather.value = enrichSkySummaries(bundle).copy(
            location = stable,
            fromCache = true,
            stale = true,
        )
    }

    /** Drop in-memory weather when it does not belong to [location]. */
    fun clearIfNotMatching(location: GeoLocation) {
        val current = _weather.value ?: return
        if (LocationIdentity.stableId(current.location) != LocationIdentity.stableId(location)) {
            _weather.value = null
        }
    }

    suspend fun getWeather(location: GeoLocation, forceRefresh: Boolean = false): WeatherBundle =
        mutex.withLock {
            val stable = LocationIdentity.withStableId(location)
            val key = LocationIdentity.cacheKey(stable)
            if (!forceRefresh) {
                (cache.read(key) ?: cache.findNearest(stable))?.let { cached ->
                    val age = System.currentTimeMillis() - cached.fetchedAtEpochMs
                    if (age < 30 * 60_000L) {
                        val tagged = enrichSkySummaries(cached).copy(fromCache = true, stale = false)
                        _weather.value = tagged
                        return tagged
                    }
                }
            }
            return try {
                val fresh = fetchLive(stable).copy(location = stable)
                if (!isCoherentLiveBundle(fresh)) {
                    android.util.Log.w(
                        LastKnownGoodStore.TAG,
                        "live_refresh_rejected_incoherent: locationId=${stable.id} key=$key " +
                            "daily=${fresh.consensusDaily.size} hourly=${fresh.consensusHourly.size} " +
                            "availableModels=${fresh.models.count { it.available}} " +
                            "priorWeather=${_weather.value != null}",
                    )
                    error(
                        "Incoherent live forecast " +
                            "(daily=${fresh.consensusDaily.size}, hourly=${fresh.consensusHourly.size}, " +
                            "available=${fresh.models.count { it.available}})",
                    )
                }
                cache.write(key, fresh)
                snapshots.record(key, fresh)
                val lkgOk = lastKnownGoodStore.write(fresh)
                android.util.Log.i(
                    LastKnownGoodStore.TAG,
                    "live_refresh: wrote_lkg=$lkgOk locationId=${stable.id} key=$key " +
                        "daily=${fresh.consensusDaily.size} hourly=${fresh.consensusHourly.size}",
                )
                _weather.value = fresh
                fresh
            } catch (e: Exception) {
                android.util.Log.w(
                    LastKnownGoodStore.TAG,
                    "live_refresh_failed: locationId=${stable.id} key=$key " +
                        "priorWeather=${_weather.value != null} err=${e.message}",
                )
                val cached = (cache.read(key) ?: cache.findNearest(stable))?.let { enrichSkySummaries(it) }
                    ?.copy(fromCache = true, stale = true)
                if (cached != null) {
                    // Never replace valid cache with empty/error state
                    _weather.value = cached
                    cached
                } else if (_weather.value != null) {
                    val kept = enrichSkySummaries(_weather.value!!).copy(fromCache = true, stale = true)
                    _weather.value = kept
                    android.util.Log.i(
                        LastKnownGoodStore.TAG,
                        "live_refresh_failed: retained_existing_bundle locationId=${kept.location.id}",
                    )
                    kept
                } else {
                    throw e
                }
            }
        }

    private suspend fun fetchLive(location: GeoLocation): WeatherBundle = coroutineScope {
        val fetchedAt = System.currentTimeMillis()
        val modelJobs = ForecastModelId.entries.map { model ->
            async(Dispatchers.IO) {
                runCatching {
                    val resp = forecastApi.forecast(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        timezone = location.timezone,
                        models = model.apiName,
                    )
                    ForecastMapper.toModelForecast(model, resp, fetchedAt)
                }.getOrElse {
                    ModelForecast(model = model, available = false, fetchedAtEpochMs = fetchedAt)
                }
            }
        }
        val ensembleRawJob = async(Dispatchers.IO) {
            runCatching { fetchEnsembleRaw(location) }.getOrNull()
        }

        val models = modelJobs.map { it.await() }
        val ensembleRaw = ensembleRawJob.await()
        val zone = runCatching { ZoneId.of(location.timezone) }.getOrDefault(ZoneId.of("UTC"))
        val today = LocalDate.now(zone)

        val consensusHourly = ConsensusEngine.buildConsensusHourly(models, 48)
        val consensusDaily = SkyCondition.attachSkySummaries(
            ConsensusEngine.buildConsensusDaily(models),
            consensusHourly,
        )

        val ensembleByDate = linkedMapOf<String, EnsembleMemberSummary>()
        if (ensembleRaw != null) {
            for (day in consensusDaily) {
                ForecastMapper.parseEnsembleDay(ensembleRaw, day.date)?.let { ensembleByDate[day.date] = it }
            }
            if (ensembleByDate.isEmpty()) {
                ForecastMapper.parseEnsembleNearTerm(ensembleRaw, today)?.let {
                    ensembleByDate[today.toString()] = it
                }
            }
        }
        val ensembleToday = ensembleByDate[today.toString()]
            ?: ensembleByDate.values.firstOrNull()

        val precipHistoryByDate = snapshots.precipHistory(location.id)
        val confidenceByDate = consensusDaily.associate { day ->
            val hist = precipHistoryByDate[day.date]
            val stability = hist?.takeIf { it.size >= 2 }?.let {
                ConfidenceEngine.stabilityFromPrecipHistory(it)
            }
            day.date to ConfidenceEngine.scoreDay(
                date = day.date,
                today = today,
                models = models,
                consensus = day,
                ensemble = ensembleByDate[day.date],
                stabilityScore01 = stability,
            )
        }

        val stabilitySignals = buildList {
            precipHistoryByDate[today.toString()]?.let { addAll(StabilityAnalyzer.signalsForPrecipHistory(it)) }
        }

        WeatherBundle(
            location = location,
            fetchedAtEpochMs = fetchedAt,
            models = models,
            ensemble = ensembleToday,
            ensembleByDate = ensembleByDate,
            consensusHourly = consensusHourly,
            consensusDaily = consensusDaily,
            confidenceByDate = confidenceByDate,
            stabilitySignals = stabilitySignals,
            fromCache = false,
            stale = false,
        )
    }

    /**
     * Open-Meteo Ensemble API for global ECMWF IFS 0.25°.
     * Timezone must be URL-encoded (`Europe/Amsterdam` → `Europe%2FAmsterdam`).
     */
    suspend fun fetchEnsembleRaw(location: GeoLocation): String = withContext(Dispatchers.IO) {
        val url = "https://ensemble-api.open-meteo.com/v1/ensemble".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", location.latitude.toString())
            .addQueryParameter("longitude", location.longitude.toString())
            .addQueryParameter("timezone", location.timezone)
            .addQueryParameter("forecast_days", "15")
            .addQueryParameter("models", "ecmwf_ifs025")
            .addQueryParameter("daily", "temperature_2m_max,precipitation_sum")
            .build()
        val req = Request.Builder().url(url).get().build()
        okHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("ensemble HTTP ${resp.code}")
            resp.body?.string() ?: error("empty ensemble body")
        }
    }

    private fun enrichSkySummaries(bundle: WeatherBundle): WeatherBundle {
        val daily = SkyCondition.attachSkySummaries(bundle.consensusDaily, bundle.consensusHourly)
        return if (daily == bundle.consensusDaily) bundle else bundle.copy(consensusDaily = daily)
    }

    companion object {
        /**
         * A live refresh must produce usable Today/Forecast content before it may replace
         * in-memory state, per-location cache, or last-known-good. Offline failures that
         * return all-unavailable models must not wipe a good boot snapshot.
         */
        fun isCoherentLiveBundle(bundle: WeatherBundle): Boolean {
            val anyModel = bundle.models.any { it.available && (it.hourly.isNotEmpty() || it.daily.isNotEmpty()) }
            return anyModel &&
                bundle.consensusDaily.isNotEmpty() &&
                bundle.consensusHourly.isNotEmpty()
        }

        fun buildEnsembleUrl(location: GeoLocation): String =
            "https://ensemble-api.open-meteo.com/v1/ensemble".toHttpUrl().newBuilder()
                .addQueryParameter("latitude", location.latitude.toString())
                .addQueryParameter("longitude", location.longitude.toString())
                .addQueryParameter("timezone", location.timezone)
                .addQueryParameter("forecast_days", "15")
                .addQueryParameter("models", "ecmwf_ifs025")
                .addQueryParameter("daily", "temperature_2m_max,precipitation_sum")
                .build()
                .toString()
    }
}
