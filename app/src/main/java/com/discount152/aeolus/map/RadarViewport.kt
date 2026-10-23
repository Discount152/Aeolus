package com.discount152.aeolus.map

import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/**
 * Square padded Web-Mercator footprint for one RainViewer coordinate-centered radar image.
 */
data class RadarFootprint(
    val centerLat: Double,
    val centerLon: Double,
    val zoom: Int,
    val latSouth: Double,
    val latNorth: Double,
    val lonWest: Double,
    val lonEast: Double,
    val sizePx: Int = RadarViewport.IMAGE_SIZE_PX,
) {
    /** Stable cache component; rounded so tiny pans inside padding keep the same key. */
    fun cacheKeyPart(): String =
        "z$zoom@" +
            "${"%.3f".format(centerLat)},${"%.3f".format(centerLon)}@" +
            "${"%.3f".format(latSouth)},${"%.3f".format(latNorth)}," +
            "${"%.3f".format(lonWest)},${"%.3f".format(lonEast)}@$sizePx"

    fun coversVisible(
        visLatSouth: Double,
        visLatNorth: Double,
        visLonWest: Double,
        visLonEast: Double,
    ): Boolean =
        visLatSouth >= latSouth &&
            visLatNorth <= latNorth &&
            visLonWest >= lonWest &&
            visLonEast <= lonEast
}

object RadarViewport {
    const val IMAGE_SIZE_PX = 512
    const val SOURCE_MIN_ZOOM = 1
    const val SOURCE_MAX_ZOOM = 7
    const val PAD_FRACTION = 0.25
    private const val MERCATOR_MAX_LAT = 85.05112878
    /** Earth circumference in Web Mercator meters. */
    private const val EARTH_CIRCUMFERENCE_M = 40_075_016.68557849

    /**
     * Build a square footprint that encloses the visible bounds with [PAD_FRACTION] padding,
     * choose the finest RainViewer zoom whose single centered image still covers that square,
     * and compute the exact geographic corners of that image.
     */
    fun footprintForVisibleBounds(
        latSouth: Double,
        latNorth: Double,
        lonWest: Double,
        lonEast: Double,
        padFraction: Double = PAD_FRACTION,
        sizePx: Int = IMAGE_SIZE_PX,
    ): RadarFootprint {
        val south = latSouth.coerceIn(-MERCATOR_MAX_LAT, MERCATOR_MAX_LAT)
        val north = latNorth.coerceIn(-MERCATOR_MAX_LAT, MERCATOR_MAX_LAT)
        var west = lonWest
        var east = lonEast
        if (east < west) east += 360.0

        val xW = lonToMercatorX(west)
        val xE = lonToMercatorX(east)
        val yS = latToMercatorY(south)
        val yN = latToMercatorY(north)
        val cx = (xW + xE) / 2.0
        val cy = (yS + yN) / 2.0
        val halfSpan = max(xE - xW, yN - yS) / 2.0
        val paddedHalf = halfSpan * (1.0 + padFraction).coerceAtLeast(1.0)
        // Avoid degenerate / world-sized requests collapsing to z=1 incorrectly
        val sideM = (paddedHalf * 2.0).coerceAtLeast(1.0)

        val zoom = zoomForSideMeters(sideM)
        val tileSpanM = EARTH_CIRCUMFERENCE_M / 2.0.pow(zoom)
        val halfTile = tileSpanM / 2.0

        val lonW = normalizeLon(mercatorXToLon(cx - halfTile))
        val lonE = normalizeLon(mercatorXToLon(cx + halfTile))
        val latS = mercatorYToLat(cy - halfTile).coerceIn(-MERCATOR_MAX_LAT, MERCATOR_MAX_LAT)
        val latN = mercatorYToLat(cy + halfTile).coerceIn(-MERCATOR_MAX_LAT, MERCATOR_MAX_LAT)
        val centerLat = mercatorYToLat(cy).coerceIn(-MERCATOR_MAX_LAT, MERCATOR_MAX_LAT)
        val centerLon = normalizeLon(mercatorXToLon(cx))

        return RadarFootprint(
            centerLat = centerLat,
            centerLon = centerLon,
            zoom = zoom,
            latSouth = min(latS, latN),
            latNorth = max(latS, latN),
            lonWest = lonW,
            lonEast = lonE,
            sizePx = sizePx,
        )
    }

    /**
     * Finest zoom in [SOURCE_MIN_ZOOM, SOURCE_MAX_ZOOM] whose tile side still covers [sideMeters].
     */
    fun zoomForSideMeters(sideMeters: Double): Int {
        if (sideMeters <= 0) return SOURCE_MAX_ZOOM
        var z = SOURCE_MAX_ZOOM
        while (z > SOURCE_MIN_ZOOM && EARTH_CIRCUMFERENCE_M / 2.0.pow(z) < sideMeters) {
            z--
        }
        return z
    }

    fun cornersMatch(a: RadarFootprint, b: RadarFootprint, epsilonDeg: Double = 1e-4): Boolean =
        a.zoom == b.zoom &&
            kotlin.math.abs(a.centerLat - b.centerLat) < epsilonDeg &&
            kotlin.math.abs(a.centerLon - b.centerLon) < epsilonDeg &&
            kotlin.math.abs(a.latSouth - b.latSouth) < epsilonDeg &&
            kotlin.math.abs(a.latNorth - b.latNorth) < epsilonDeg &&
            kotlin.math.abs(a.lonWest - b.lonWest) < epsilonDeg &&
            kotlin.math.abs(a.lonEast - b.lonEast) < epsilonDeg

    fun latToMercatorY(lat: Double): Double {
        val rad = Math.toRadians(lat.coerceIn(-MERCATOR_MAX_LAT, MERCATOR_MAX_LAT))
        return ln(tan(Math.PI / 4.0 + rad / 2.0)) * (EARTH_CIRCUMFERENCE_M / (2.0 * Math.PI))
    }

    fun mercatorYToLat(y: Double): Double {
        val mercator = y * (2.0 * Math.PI) / EARTH_CIRCUMFERENCE_M
        return Math.toDegrees(2.0 * atan(exp(mercator)) - Math.PI / 2.0)
    }

    fun lonToMercatorX(lon: Double): Double =
        normalizeLon(lon) / 360.0 * EARTH_CIRCUMFERENCE_M

    fun mercatorXToLon(x: Double): Double =
        (x / EARTH_CIRCUMFERENCE_M) * 360.0

    fun normalizeLon(lon: Double): Double {
        var l = lon % 360.0
        if (l > 180.0) l -= 360.0
        if (l < -180.0) l += 360.0
        return l
    }
}
