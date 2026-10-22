package com.discount152.aeolus.map

/**
 * RainViewer public weather-map tiles.
 * Source max zoom is 7; MapLibre must overscale beyond that rather than request z8+.
 */
object RadarTiles {
    const val SOURCE_MAX_ZOOM = 7
    const val SOURCE_MIN_ZOOM = 1

    /**
     * Build a TileJSON-compatible template.
     * RainViewer path form: `{host}{path}/256/{z}/{x}/{y}/{color}/{options}.png`
     */
    fun tileTemplate(host: String, path: String, colorScheme: Int = 2): String {
        val h = host.trimEnd('/')
        val p = path.trim().let { if (it.startsWith("/")) it else "/$it" }.trimEnd('/')
        return "$h$p/256/{z}/{x}/{y}/$colorScheme/1_1.png"
    }

    fun clampSourceZoom(mapZoom: Double): Int =
        mapZoom.toInt().coerceIn(SOURCE_MIN_ZOOM, SOURCE_MAX_ZOOM)
}
