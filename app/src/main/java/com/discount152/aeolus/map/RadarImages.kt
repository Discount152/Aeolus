package com.discount152.aeolus.map

/**
 * RainViewer XYZ radar tiles (512 px). Aeolus downloads and stitches these offline;
 * MapLibre never fetches animated radar tiles directly.
 *
 * `{host}{path}/{size}/{z}/{x}/{y}/{color}/{options}.png`
 */
object RadarImages {
    const val COLOR_UNIVERSAL_BLUE = 2
    const val OPTIONS_SMOOTH_SNOW = "1_1"
    const val SIZE_PX = RadarViewport.TILE_SIZE_PX

    fun tileUrl(
        host: String,
        path: String,
        zoom: Int,
        x: Int,
        y: Int,
        sizePx: Int = SIZE_PX,
        colorScheme: Int = COLOR_UNIVERSAL_BLUE,
        options: String = OPTIONS_SMOOTH_SNOW,
    ): String {
        val h = host.trimEnd('/')
        val p = path.trim().let { if (it.startsWith("/")) it else "/$it" }.trimEnd('/')
        val z = zoom.coerceIn(RadarViewport.SOURCE_MIN_ZOOM, RadarViewport.SOURCE_MAX_ZOOM)
        return "$h$p/$sizePx/$z/$x/$y/$colorScheme/$options.png"
    }

    fun tileCacheKey(
        framePath: String,
        z: Int,
        x: Int,
        y: Int,
        sizePx: Int = SIZE_PX,
        colorScheme: Int = COLOR_UNIVERSAL_BLUE,
        options: String = OPTIONS_SMOOTH_SNOW,
    ): String = "${framePath.trim()}|$sizePx|$z|$x|$y|$colorScheme|$options"

    fun frameCacheKey(
        framePath: String,
        footprint: RadarFootprint,
    ): String = "${framePath.trim()}|${footprint.cacheKeyPart()}"

    /** @deprecated Prefer [tileUrl]; retained for transitional tests. */
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
}
