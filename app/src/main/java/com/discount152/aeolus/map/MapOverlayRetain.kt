package com.discount152.aeolus.map

import com.discount152.aeolus.data.network.OpenMeteoForecastApi
import com.discount152.aeolus.data.network.RainViewerApi
import okhttp3.OkHttpClient

data class MapCameraRetain(
    val lat: Double,
    val lon: Double,
    val zoom: Double,
)

/**
 * Survives NavHost dispose of [com.discount152.aeolus.ui.map.MapScreen].
 * Today→Map must not rebuild pipelines from empty (that painted PREPARING…).
 */
object MapOverlayRetain {
    private val lock = Any()

    @Volatile private var radarPipeline: RadarFramePipeline? = null
    @Volatile private var forecastPipeline: ForecastPrecipPipeline? = null
    @Volatile private var tzResolver: ViewedTimezoneResolver? = null

    @Volatile var lastTimeline: UnifiedMapTimeline? = null
        private set
    @Volatile var lastSelectedInstant: Long? = null
        private set
    @Volatile var lastViewedZoneId: String? = null
        private set
    @Volatile var lastRadarFootprint: RadarFootprint? = null
        private set
    @Volatile var lastForecastFootprint: ForecastPrecipFootprint? = null
        private set
    @Volatile var lastCamera: MapCameraRetain? = null
        private set

    fun radar(api: RainViewerApi, client: OkHttpClient): RadarFramePipeline =
        radarPipeline ?: synchronized(lock) {
            radarPipeline ?: RadarFramePipeline(api, client).also { radarPipeline = it }
        }

    fun forecast(api: OpenMeteoForecastApi): ForecastPrecipPipeline =
        forecastPipeline ?: synchronized(lock) {
            forecastPipeline ?: ForecastPrecipPipeline(api).also { forecastPipeline = it }
        }

    fun timezoneResolver(api: OpenMeteoForecastApi): ViewedTimezoneResolver =
        tzResolver ?: synchronized(lock) {
            tzResolver ?: ViewedTimezoneResolver(api).also { tzResolver = it }
        }

    fun rememberPublish(
        timeline: UnifiedMapTimeline?,
        selectedInstant: Long?,
        viewedZoneId: String?,
        radarFootprint: RadarFootprint?,
        forecastFootprint: ForecastPrecipFootprint?,
    ) {
        lastTimeline = timeline
        lastSelectedInstant = selectedInstant
        lastViewedZoneId = viewedZoneId
        lastRadarFootprint = radarFootprint
        lastForecastFootprint = forecastFootprint
    }

    fun rememberCamera(lat: Double, lon: Double, zoom: Double) {
        lastCamera = MapCameraRetain(lat, lon, zoom)
    }

    fun resetForTests() {
        synchronized(lock) {
            radarPipeline = null
            forecastPipeline = null
            tzResolver = null
            lastTimeline = null
            lastSelectedInstant = null
            lastViewedZoneId = null
            lastRadarFootprint = null
            lastForecastFootprint = null
            lastCamera = null
        }
    }
}
