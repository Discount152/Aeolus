package com.discount152.aeolus.map

import com.discount152.aeolus.data.network.RainViewerApi
import com.discount152.aeolus.data.network.RainViewerFrame
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

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
    @Suppress("UNUSED_PARAMETER") private val okHttp: OkHttpClient,
    @Suppress("UNUSED_PARAMETER") private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    private val mutex = Mutex()
    private var radarCache: RadarState? = null
    private var radarCachedAt = 0L

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
}
