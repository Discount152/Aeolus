package com.discount152.aeolus.map

/**
 * RainViewer coordinate-centered complete radar images (not XYZ tiles).
 * `{host}{path}/{size}/{z}/{lat}/{lon}/{color}/{options}.png`
 */
object RadarImages {
    const val COLOR_UNIVERSAL_BLUE = 2
    const val OPTIONS_SMOOTH_SNOW = "1_1"
    const val SIZE_PX = RadarViewport.IMAGE_SIZE_PX

    fun centeredImageUrl(
        host: String,
        path: String,
        zoom: Int,
        latitude: Double,
        longitude: Double,
        sizePx: Int = SIZE_PX,
        colorScheme: Int = COLOR_UNIVERSAL_BLUE,
        options: String = OPTIONS_SMOOTH_SNOW,
    ): String {
        val h = host.trimEnd('/')
        val p = path.trim().let { if (it.startsWith("/")) it else "/$it" }.trimEnd('/')
        val z = zoom.coerceIn(RadarViewport.SOURCE_MIN_ZOOM, RadarViewport.SOURCE_MAX_ZOOM)
        val lat = "%.5f".format(latitude)
        val lon = "%.5f".format(longitude)
        return "$h$p/$sizePx/$z/$lat/$lon/$colorScheme/$options.png"
    }

    fun frameCacheKey(
        framePath: String,
        footprint: RadarFootprint,
    ): String = "${framePath.trim()}|${footprint.cacheKeyPart()}"
}
