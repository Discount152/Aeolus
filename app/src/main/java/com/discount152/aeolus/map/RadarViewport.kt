package com.discount152.aeolus.map

import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/**
 * Padded Web-Mercator footprint covered by a stitched RainViewer XYZ mosaic.
 * MapLibre receives only the complete mosaic via ImageSource — never individual tiles.
 */
data class RadarTileCoord(
    val z: Int,
    val x: Int,
    val y: Int,
)

data class RadarFootprint(
    val centerLat: Double,
    val centerLon: Double,
    val zoom: Int,
    val latSouth: Double,
    val latNorth: Double,
    val lonWest: Double,
    val lonEast: Double,
    val tileSizePx: Int = RadarViewport.TILE_SIZE_PX,
    val tiles: List<RadarTileCoord> = emptyList(),
    val tilesX: Int = 0,
    val tilesY: Int = 0,
) {
    val mosaicWidthPx: Int get() = tilesX * tileSizePx
    val mosaicHeightPx: Int get() = tilesY * tileSizePx

    /** Stable cache component; rounded so tiny pans inside padding keep the same key. */
    fun cacheKeyPart(): String =
        "z$zoom@" +
            "t${tilesX}x$tilesY@" +
            "${tiles.joinToString(";") { "${it.x},${it.y}" }}@" +
            "${"%.3f".format(latSouth)},${"%.3f".format(latNorth)}," +
            "${"%.3f".format(lonWest)},${"%.3f".format(lonEast)}@$tileSizePx"

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

    /** Geographic cover is not enough if this mosaic is coarser than the viewport wants. */
    fun coversVisibleAtZoom(
        visLatSouth: Double,
        visLatNorth: Double,
        visLonWest: Double,
        visLonEast: Double,
        desiredZoom: Int,
    ): Boolean = zoom >= desiredZoom && coversVisible(visLatSouth, visLatNorth, visLonWest, visLonEast)
}

object RadarViewport {
    const val TILE_SIZE_PX = 512
    /** @deprecated Use [TILE_SIZE_PX]; kept for callers expecting IMAGE_SIZE_PX. */
    const val IMAGE_SIZE_PX = TILE_SIZE_PX
    const val SOURCE_MIN_ZOOM = 1
    const val SOURCE_MAX_ZOOM = 7
    /** Target max RainViewer XYZ tiles per complete mosaic frame. */
    const val MAX_TILES_PER_FRAME = 4
    const val PAD_FRACTION = 0.20
    private const val MERCATOR_MAX_LAT = 85.05112878
    private const val EARTH_CIRCUMFERENCE_M = 40_075_016.68557849

    /**
     * Build a padded footprint that encloses the visible bounds, choose the finest RainViewer
     * source zoom whose tile cover fits in [MAX_TILES_PER_FRAME], and return exact mosaic corners.
     */
    fun footprintForVisibleBounds(
        latSouth: Double,
        latNorth: Double,
        lonWest: Double,
        lonEast: Double,
        padFraction: Double = PAD_FRACTION,
        tileSizePx: Int = TILE_SIZE_PX,
        maxTiles: Int = MAX_TILES_PER_FRAME,
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
        val widthM = (xE - xW).coerceAtLeast(1.0)
        val heightM = (yN - yS).coerceAtLeast(1.0)
        val padX = widthM * padFraction
        val padY = heightM * padFraction
        val padWestM = xW - padX
        val padEastM = xE + padX
        val padSouthM = yS - padY
        val padNorthM = yN + padY

        val cover = tileCoverForBounds(
            mercatorWest = padWestM,
            mercatorEast = padEastM,
            mercatorSouth = padSouthM,
            mercatorNorth = padNorthM,
            maxTiles = maxTiles,
        )

        val n = 2.0.pow(cover.zoom)
        val tileSpanM = EARTH_CIRCUMFERENCE_M / n
        // Web Mercator Y: north is +mercator; TMS/XYZ tile y=0 is north
        val mosaicWestM = cover.xMin * tileSpanM - EARTH_CIRCUMFERENCE_M / 2.0
        val mosaicEastM = (cover.xMax + 1) * tileSpanM - EARTH_CIRCUMFERENCE_M / 2.0
        val mosaicNorthM = EARTH_CIRCUMFERENCE_M / 2.0 - cover.yMin * tileSpanM
        val mosaicSouthM = EARTH_CIRCUMFERENCE_M / 2.0 - (cover.yMax + 1) * tileSpanM

        val lonW = normalizeLon(mercatorXToLon(mosaicWestM))
        val lonE = normalizeLon(mercatorXToLon(mosaicEastM))
        val latS = mercatorYToLat(mosaicSouthM).coerceIn(-MERCATOR_MAX_LAT, MERCATOR_MAX_LAT)
        val latN = mercatorYToLat(mosaicNorthM).coerceIn(-MERCATOR_MAX_LAT, MERCATOR_MAX_LAT)
        val centerLat = mercatorYToLat((mosaicSouthM + mosaicNorthM) / 2.0)
            .coerceIn(-MERCATOR_MAX_LAT, MERCATOR_MAX_LAT)
        val centerLon = normalizeLon(mercatorXToLon((mosaicWestM + mosaicEastM) / 2.0))

        val tiles = buildList {
            for (y in cover.yMin..cover.yMax) {
                for (x in cover.xMin..cover.xMax) {
                    add(RadarTileCoord(cover.zoom, x, y))
                }
            }
        }

        return RadarFootprint(
            centerLat = centerLat,
            centerLon = centerLon,
            zoom = cover.zoom,
            latSouth = min(latS, latN),
            latNorth = max(latS, latN),
            lonWest = lonW,
            lonEast = lonE,
            tileSizePx = tileSizePx,
            tiles = tiles,
            tilesX = cover.xMax - cover.xMin + 1,
            tilesY = cover.yMax - cover.yMin + 1,
        )
    }

    data class TileCover(
        val zoom: Int,
        val xMin: Int,
        val xMax: Int,
        val yMin: Int,
        val yMax: Int,
    ) {
        val count: Int get() = (xMax - xMin + 1) * (yMax - yMin + 1)
    }

    /**
     * Highest zoom in [SOURCE_MIN_ZOOM, SOURCE_MAX_ZOOM] whose tile cover ≤ [maxTiles].
     * If even z=1 exceeds budget (world view), still returns the minimal cover at z=1.
     */
    fun tileCoverForBounds(
        mercatorWest: Double,
        mercatorEast: Double,
        mercatorSouth: Double,
        mercatorNorth: Double,
        maxTiles: Int = MAX_TILES_PER_FRAME,
    ): TileCover {
        var chosen: TileCover? = null
        for (z in SOURCE_MAX_ZOOM downTo SOURCE_MIN_ZOOM) {
            val cover = coverAtZoom(z, mercatorWest, mercatorEast, mercatorSouth, mercatorNorth)
            if (cover.count <= maxTiles) {
                chosen = cover
                break
            }
            chosen = cover // keep coarsest if none fit
        }
        return chosen ?: coverAtZoom(
            SOURCE_MIN_ZOOM, mercatorWest, mercatorEast, mercatorSouth, mercatorNorth,
        )
    }

    fun coverAtZoom(
        zoom: Int,
        mercatorWest: Double,
        mercatorEast: Double,
        mercatorSouth: Double,
        mercatorNorth: Double,
    ): TileCover {
        val z = zoom.coerceIn(SOURCE_MIN_ZOOM, SOURCE_MAX_ZOOM)
        val n = 2.0.pow(z)
        val tileSpanM = EARTH_CIRCUMFERENCE_M / n
        fun xIndex(m: Double): Int =
            floor((m + EARTH_CIRCUMFERENCE_M / 2.0) / tileSpanM).toInt().coerceIn(0, n.toInt() - 1)
        fun yIndexFromMercatorY(m: Double): Int {
            // XYZ: y=0 at north pole
            val y = floor((EARTH_CIRCUMFERENCE_M / 2.0 - m) / tileSpanM).toInt()
            return y.coerceIn(0, n.toInt() - 1)
        }
        val xMin = xIndex(mercatorWest)
        val xMax = xIndex(mercatorEast - 1e-9)
        val yMin = yIndexFromMercatorY(mercatorNorth - 1e-9) // north → smaller y
        val yMax = yIndexFromMercatorY(mercatorSouth + 1e-9)
        return TileCover(
            zoom = z,
            xMin = min(xMin, xMax),
            xMax = max(xMin, xMax),
            yMin = min(yMin, yMax),
            yMax = max(yMin, yMax),
        )
    }

    /** Finest zoom whose single-tile side still covers [sideMeters] (legacy helper). */
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
            a.tiles == b.tiles &&
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

    /**
     * Reject MapLibre's pre-camera world bounds (invalid / whole-earth). A first style load
     * must not become the cached footprint that later [coversVisible] every city view.
     */
    fun visibleBoundsUsable(
        latSouth: Double,
        latNorth: Double,
        lonWest: Double,
        lonEast: Double,
    ): Boolean {
        if (latSouth.isNaN() || latNorth.isNaN() || lonWest.isNaN() || lonEast.isNaN()) return false
        if (latSouth < -MERCATOR_MAX_LAT || latNorth > MERCATOR_MAX_LAT) return false
        if (latNorth <= latSouth) return false
        if (latNorth - latSouth > 70.0) return false
        return true
    }
}
