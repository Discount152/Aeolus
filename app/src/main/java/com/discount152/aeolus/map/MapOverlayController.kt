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

data class FieldSampleResult(
    val bounds: FieldRasterInterpolator.Bounds,
    val rows: Int,
    val cols: Int,
    /** Row-major south→north, west→east; null = missing API value. */
    val values: Array<Array<Double?>>,
    /** Samples with non-null API values only. */
    val points: List<SamplePoint>,
)

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
    private val sampleCache = mutableMapOf<String, Pair<Long, FieldSampleResult>>()

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
    ): FieldSampleResult = withContext(Dispatchers.IO) {
        val padded = ViewportSampler.padBounds(latSouth, latNorth, lonWest, lonEast)
        val key = ViewportSampler.cacheKey(layer, padded)
        val cached = sampleCache[key]
        if (cached != null && System.currentTimeMillis() - cached.first < 10 * 60_000L) {
            return@withContext cached.second
        }

        if (layer == WeatherLayer.RADAR) {
            return@withContext FieldSampleResult(
                bounds = padded,
                rows = 0,
                cols = 0,
                values = emptyArray(),
                points = emptyList(),
            )
        }

        val grid = ViewportSampler.buildCoordinates(padded)
        val rows = grid.rows
        val cols = grid.cols
        val values = grid.values
        val coordPoints = grid.points

        val variable = when (layer) {
            WeatherLayer.TEMPERATURE -> "temperature_2m"
            WeatherLayer.CLOUDS -> "cloud_cover"
            WeatherLayer.RADAR -> error("unreachable")
        }

        val latParam = coordPoints.joinToString(",") { it.lat.toString() }
        val lonParam = coordPoints.joinToString(",") { it.lon.toString() }
        val url =
            "https://api.open-meteo.com/v1/forecast?latitude=$latParam&longitude=$lonParam" +
                "&current=$variable&timezone=auto"
        val body = okHttp.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) error("sample HTTP ${resp.code}")
            resp.body?.string() ?: error("empty")
        }

        val root = json.parseToJsonElement(body)
        val filledPoints = mutableListOf<SamplePoint>()
        when (root) {
            is JsonArray -> {
                root.jsonArray.forEachIndexed { idx, el ->
                    val obj = el.jsonObject
                    val cur = obj["current"]?.jsonObject
                    val value = cur?.get(variable)?.jsonPrimitive?.doubleOrNull
                    val p = coordPoints.getOrNull(idx) ?: return@forEachIndexed
                    val r = idx / cols
                    val c = idx % cols
                    values[r][c] = value
                    if (value != null) {
                        filledPoints.add(SamplePoint(p.lat, p.lon, value))
                    }
                }
            }
            is JsonObject -> {
                val cur = root.jsonObject["current"]?.jsonObject
                val value = cur?.get(variable)?.jsonPrimitive?.doubleOrNull
                val p = coordPoints.firstOrNull()
                if (p != null) {
                    values[0][0] = value
                    if (value != null) filledPoints.add(SamplePoint(p.lat, p.lon, value))
                }
            }
            else -> Unit
        }

        val result = FieldSampleResult(
            bounds = padded,
            rows = rows,
            cols = cols,
            values = values,
            points = filledPoints,
        )
        sampleCache[key] = System.currentTimeMillis() to result
        result
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
