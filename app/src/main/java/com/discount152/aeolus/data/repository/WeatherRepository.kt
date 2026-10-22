package com.discount152.aeolus.data.repository

import com.discount152.aeolus.data.cache.ForecastCache
import com.discount152.aeolus.data.cache.ForecastSnapshotStore
import com.discount152.aeolus.data.network.ForecastMapper
import com.discount152.aeolus.data.network.OpenMeteoEnsembleApi
import com.discount152.aeolus.data.network.OpenMeteoForecastApi
import com.discount152.aeolus.data.network.OpenMeteoGeocodingApi
import com.discount152.aeolus.domain.confidence.ConfidenceEngine
import com.discount152.aeolus.domain.consensus.ConsensusEngine
import com.discount152.aeolus.domain.models.ForecastModelId
import com.discount152.aeolus.domain.models.GeoLocation
import com.discount152.aeolus.domain.models.ModelForecast
import com.discount152.aeolus.domain.models.WeatherBundle
import com.discount152.aeolus.domain.weather.StabilityAnalyzer
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    private val mutex = Mutex()

    suspend fun searchLocations(query: String): List<GeoLocation> {
        if (query.isBlank()) return emptyList()
        return runCatching {
            geocodingApi.search(query.trim()).results.map {
                GeoLocation(
                    id = (it.id ?: "${it.latitude},${it.longitude}").toString(),
                    name = it.name,
                    latitude = it.latitude,
                    longitude = it.longitude,
                    timezone = it.timezone ?: "UTC",
                    country = it.country,
                    admin1 = it.admin1,
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun getWeather(location: GeoLocation, forceRefresh: Boolean = false): WeatherBundle =
        mutex.withLock {
            if (!forceRefresh) {
                cache.read(location.id)?.let { cached ->
                    val age = System.currentTimeMillis() - cached.fetchedAtEpochMs
                    if (age < 30 * 60_000L) return cached.copy(fromCache = true, stale = false)
                }
            }
            return try {
                val fresh = fetchLive(location)
                cache.write(location.id, fresh)
                snapshots.record(location.id, fresh)
                fresh
            } catch (e: Exception) {
                cache.read(location.id)?.copy(
                    fromCache = true,
                    stale = true,
                ) ?: throw e
            }
        }

    private suspend fun fetchLive(location: GeoLocation): WeatherBundle = coroutineScope {
        val fetchedAt = System.currentTimeMillis()
        val modelJobs = ForecastModelId.entries.map { model ->
            async {
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
        val ensembleRawJob = async {
            runCatching { fetchEnsembleRaw(location) }.getOrNull()
        }

        val models = modelJobs.map { it.await() }
        val ensembleRaw = ensembleRawJob.await()
        val zone = runCatching { ZoneId.of(location.timezone) }.getOrDefault(ZoneId.of("UTC"))
        val today = LocalDate.now(zone)

        val ensembleToday = ensembleRaw?.let { ForecastMapper.parseEnsembleNearTerm(it, today) }
        val consensusHourly = ConsensusEngine.buildConsensusHourly(models, 48)
        val consensusDaily = ConsensusEngine.buildConsensusDaily(models)

        val precipHistoryByDate = snapshots.precipHistory(location.id)
        val confidenceByDate = consensusDaily.associate { day ->
            val hist = precipHistoryByDate[day.date]
            val stability = hist?.let { ConfidenceEngine.stabilityFromPrecipHistory(it) }
            day.date to ConfidenceEngine.scoreDay(
                date = day.date,
                today = today,
                models = models,
                consensus = day,
                ensemble = if (day.date == today.toString()) ensembleToday else
                    ensembleRaw?.let { ForecastMapper.parseEnsembleDay(it, day.date) },
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
            consensusHourly = consensusHourly,
            consensusDaily = consensusDaily,
            confidenceByDate = confidenceByDate,
            stabilitySignals = stabilitySignals,
            fromCache = false,
            stale = false,
        )
    }

    private suspend fun fetchEnsembleRaw(location: GeoLocation): String {
        // Use OkHttp for full JSON so member_* keys are preserved
        val url =
            "https://ensemble-api.open-meteo.com/v1/ensemble" +
                "?latitude=${location.latitude}&longitude=${location.longitude}" +
                "&timezone=${location.timezone}" +
                "&forecast_days=15" +
                "&models=ecmwf_ifs025" +
                "&daily=temperature_2m_max,precipitation_sum" +
                "&hourly=temperature_2m,precipitation"
        val req = Request.Builder().url(url).get().build()
        return okHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("ensemble HTTP ${resp.code}")
            resp.body?.string() ?: error("empty ensemble body")
        }
    }

    fun cachedOrNull(locationId: String): WeatherBundle? = cache.read(locationId)
}
