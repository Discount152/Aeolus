package com.discount152.aeolus.map

import com.discount152.aeolus.data.network.RainViewerApi
import com.discount152.aeolus.data.network.RainViewerFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

enum class WeatherLayer { RADAR, TEMPERATURE, CLOUDS }

data class SamplePoint(val lat: Double, val lon: Double, val value: Double)

data class RadarState(
    val host: String? = null,
    val frames: List<RainViewerFrame> = emptyList(),
    val index: Int = 0,
    val playing: Boolean = false,
    val unavailable: Boolean = false,
    val error: String? = null,
)

class MapOverlayController(
    private val rainViewerApi: RainViewerApi,
    private val okHttp: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    private val mutex = Mutex()
    private var radarCache: RadarState? = null
    private var radarCachedAt = 0L
    private val sampleCache = mutableMapOf<String, Pair<Long, List<SamplePoint>>>()

    suspend fun loadRadar(force: Boolean = false): RadarState = mutex.withLock {
        val now = System.currentTimeMillis()
        if (!force && radarCache != null && now - radarCachedAt < 5 * 60_000L) {
            return radarCache!!
        }
        return try {
            val maps = rainViewerApi.weatherMaps()
            val past = maps.radar?.past.orEmpty()
            val frames = past.takeLast(24)
            val state = if (frames.isEmpty()) {
                RadarState(unavailable = true, error = "RADAR DATA UNAVAILABLE")
            } else {
                RadarState(
                    host = maps.host,
                    frames = frames,
                    index = frames.lastIndex,
                    playing = false,
                    unavailable = false,
                )
            }
            radarCache = state
            radarCachedAt = now
            state
        } catch (_: Exception) {
            RadarState(unavailable = true, error = "RADAR DATA UNAVAILABLE").also {
                radarCache = it
                radarCachedAt = now
            }
        }
    }

    suspend fun sampleField(
        layer: WeatherLayer,
        latSouth: Double,
        latNorth: Double,
        lonWest: Double,
        lonEast: Double,
        rows: Int = 6,
        cols: Int = 8,
    ): List<SamplePoint> = withContext(Dispatchers.IO) {
        val key = "${layer.name}:${"%.2f".format(latSouth)}:${"%.2f".format(latNorth)}:" +
            "${"%.2f".format(lonWest)}:${"%.2f".format(lonEast)}"
        val cached = sampleCache[key]
        if (cached != null && System.currentTimeMillis() - cached.first < 10 * 60_000L) {
            return@withContext cached.second
        }

        val lats = (0 until rows).map { i ->
            latSouth + (latNorth - latSouth) * i / (rows - 1).coerceAtLeast(1).toDouble()
        }
        val lons = (0 until cols).map { j ->
            lonWest + (lonEast - lonWest) * j / (cols - 1).coerceAtLeast(1).toDouble()
        }
        val points = mutableListOf<Pair<Double, Double>>()
        for (lat in lats) {
            for (lon in lons) {
                points.add(lat to lon)
            }
        }

        val variable = when (layer) {
            WeatherLayer.TEMPERATURE -> "temperature_2m"
            WeatherLayer.CLOUDS -> "cloud_cover"
            WeatherLayer.RADAR -> return@withContext emptyList()
        }

        val latParam = points.joinToString(",") { it.first.toString() }
        val lonParam = points.joinToString(",") { it.second.toString() }
        val url =
            "https://api.open-meteo.com/v1/forecast?latitude=$latParam&longitude=$lonParam" +
                "&current=$variable&timezone=auto"
        val body = okHttp.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) error("sample HTTP ${resp.code}")
            resp.body?.string() ?: error("empty")
        }

        val root = json.parseToJsonElement(body)
        val samples = mutableListOf<SamplePoint>()
        when (root) {
            is JsonArray -> {
                root.jsonArray.forEachIndexed { idx, el ->
                    val obj = el.jsonObject
                    val cur = obj["current"]?.jsonObject
                    val value = cur?.get(variable)?.jsonPrimitive?.doubleOrNull
                    val p = points.getOrNull(idx) ?: return@forEachIndexed
                    if (value != null) samples.add(SamplePoint(p.first, p.second, value))
                }
            }
            is JsonObject -> {
                val cur = root.jsonObject["current"]?.jsonObject
                val value = cur?.get(variable)?.jsonPrimitive?.doubleOrNull
                val p = points.firstOrNull()
                if (value != null && p != null) samples.add(SamplePoint(p.first, p.second, value))
            }
            else -> Unit
        }
        sampleCache[key] = System.currentTimeMillis() to samples
        samples
    }

    fun colorForTemperature(c: Double): String {
        val t = ((c + 10) / 40.0).coerceIn(0.0, 1.0)
        val r = (t * 255).toInt()
        val b = ((1 - t) * 255).toInt()
        val g = (180 - kotlin.math.abs(t - 0.5) * 120).toInt().coerceIn(0, 255)
        return String.format("#%02X%02X%02X", r, g, b)
    }

    fun colorForCloud(percent: Double): String {
        val a = (percent / 100.0).coerceIn(0.15, 0.85)
        val g = (200 * a).toInt().coerceIn(40, 220)
        return String.format("#%02X%02X%02X", g, g, g)
    }
}
