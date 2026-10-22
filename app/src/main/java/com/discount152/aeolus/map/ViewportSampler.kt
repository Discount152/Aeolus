package com.discount152.aeolus.map

import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tan

/**
 * Builds an even sample grid in Web-Mercator screen space over a padded viewport,
 * then converts to lat/lon for Open-Meteo.
 */
object ViewportSampler {
    const val MERCATOR_MAX_LAT = 85.05112878

    data class SampleGrid(
        val bounds: FieldRasterInterpolator.Bounds,
        /** Row-major south→north, west→east; null = missing sample. */
        val values: Array<Array<Double?>>,
        /** Row-major lat/lon for each cell (value NaN until API fill). */
        val points: List<SamplePoint>,
        val rows: Int,
        val cols: Int,
    )

    fun padBounds(
        latSouth: Double,
        latNorth: Double,
        lonWest: Double,
        lonEast: Double,
        padFraction: Double = 0.15,
    ): FieldRasterInterpolator.Bounds {
        val latSpan = (latNorth - latSouth).coerceAtLeast(1e-6)
        val lonSpan = normalizeLonSpan(lonWest, lonEast).coerceAtLeast(1e-6)
        val latPad = latSpan * padFraction
        val lonPad = lonSpan * padFraction
        return FieldRasterInterpolator.Bounds(
            latSouth = (latSouth - latPad).coerceIn(-MERCATOR_MAX_LAT, MERCATOR_MAX_LAT),
            latNorth = (latNorth + latPad).coerceIn(-MERCATOR_MAX_LAT, MERCATOR_MAX_LAT),
            lonWest = lonWest - lonPad,
            lonEast = lonEast + lonPad,
        )
    }

    fun gridSizeForSpan(latSpan: Double, lonSpan: Double): Pair<Int, Int> {
        // ~12×8 for typical phone regional view; slightly coarser for global
        val span = maxOf(latSpan, lonSpan)
        return when {
            span > 80 -> 8 to 6
            span > 30 -> 10 to 7
            else -> 12 to 8
        }
    }

    /**
     * Even mercator grid covering [bounds]. Returns lat/lon sample coordinates (null values).
     */
    fun buildCoordinates(bounds: FieldRasterInterpolator.Bounds): SampleGrid {
        val (cols, rows) = gridSizeForSpan(
            bounds.latNorth - bounds.latSouth,
            normalizeLonSpan(bounds.lonWest, bounds.lonEast),
        )
        val ySouth = latToMercatorY(bounds.latSouth.coerceIn(-MERCATOR_MAX_LAT, MERCATOR_MAX_LAT))
        val yNorth = latToMercatorY(bounds.latNorth.coerceIn(-MERCATOR_MAX_LAT, MERCATOR_MAX_LAT))
        val xWest = lonToMercatorX(bounds.lonWest)
        val xEast = lonToMercatorX(bounds.lonEast)

        val values = Array(rows) { arrayOfNulls<Double?>(cols) }
        val points = mutableListOf<SamplePoint>()
        for (r in 0 until rows) {
            val fy = if (rows == 1) 0.5 else r.toDouble() / (rows - 1)
            val y = ySouth + (yNorth - ySouth) * fy
            val lat = mercatorYToLat(y).coerceIn(-MERCATOR_MAX_LAT, MERCATOR_MAX_LAT)
            for (c in 0 until cols) {
                val fx = if (cols == 1) 0.5 else c.toDouble() / (cols - 1)
                val x = xWest + (xEast - xWest) * fx
                val lon = mercatorXToLon(x)
                // placeholder SamplePoint with NaN until filled — use 0 sentinel in list for API
                points += SamplePoint(lat, lon, Double.NaN)
            }
        }
        return SampleGrid(bounds, values, points, rows, cols)
    }

    fun cacheKey(layer: WeatherLayer, bounds: FieldRasterInterpolator.Bounds): String =
        "${layer.name}:" +
            "${"%.4f".format(bounds.latSouth)},${"%.4f".format(bounds.latNorth)}," +
            "${"%.4f".format(bounds.lonWest)},${"%.4f".format(bounds.lonEast)}"

    fun latToMercatorY(lat: Double): Double {
        val rad = Math.toRadians(lat.coerceIn(-MERCATOR_MAX_LAT, MERCATOR_MAX_LAT))
        return ln(tan(Math.PI / 4.0 + rad / 2.0))
    }

    fun mercatorYToLat(y: Double): Double =
        Math.toDegrees(2.0 * atan(exp(y)) - Math.PI / 2.0)

    fun lonToMercatorX(lon: Double): Double = Math.toRadians(lon)

    fun mercatorXToLon(x: Double): Double = Math.toDegrees(x)

    private fun normalizeLonSpan(west: Double, east: Double): Double {
        var span = east - west
        if (span < 0) span += 360.0
        return span
    }
}
