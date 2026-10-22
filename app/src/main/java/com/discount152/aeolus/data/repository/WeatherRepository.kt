package com.discount152.aeolus.data.repository

import com.discount152.aeolus.data.cache.ForecastCache
import com.discount152.aeolus.data.cache.ForecastSnapshotStore
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

    /**
     * Load disk cache into shared state without network.
     * @param markStale true only when refresh failed or offline — not while a live refresh is pending.
     */
    fun hydrateFromCache(location: GeoLocation, markStale: Boolean = false): WeatherBundle? {
        val raw = cache.read(location.id) ?: cache.findNearest(location)
        val cached = raw?.copy(fromCache = true, stale = markStale)
        if (cached != null) {
            _weather.value = cached
        }
        return cached
    }

    suspend fun getWeather(location: GeoLocation, forceRefresh: Boolean = false): WeatherBundle =
        mutex.withLock {
            if (!forceRefresh) {
                cache.read(location.id)?.let { cached ->
                    val age = System.currentTimeMillis() - cached.fetchedAtEpochMs
                    if (age < 30 * 60_000L) {
                        val tagged = cached.copy(fromCache = true, stale = false)
                        _weather.value = tagged
                        return tagged
                    }
                }
            }
            return try {
                val fresh = fetchLive(location)
                cache.write(location.id, fresh)
                snapshots.record(location.id, fresh)
                _weather.value = fresh
                fresh
            } catch (e: Exception) {
                val cached = cache.read(location.id)?.copy(fromCache = true, stale = true)
                if (cached != null) {
                    // Never replace valid cache with empty/error state
                    _weather.value = cached
                    cached
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
        val consensusDaily = ConsensusEngine.buildConsensusDaily(models)

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

    companion object {
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
