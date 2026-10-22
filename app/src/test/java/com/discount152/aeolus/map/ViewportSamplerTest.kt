package com.discount152.aeolus.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ViewportSamplerTest {
    @Test
    fun padBounds_expandsByFraction() {
        val raw = FieldRasterInterpolator.Bounds(53.0, 53.5, 6.4, 7.0)
        val padded = ViewportSampler.padBounds(
            raw.latSouth,
            raw.latNorth,
            raw.lonWest,
            raw.lonEast,
        )
        assertThat(padded.latSouth).isLessThan(raw.latSouth)
        assertThat(padded.latNorth).isGreaterThan(raw.latNorth)
        assertThat(padded.lonWest).isLessThan(raw.lonWest)
        assertThat(padded.lonEast).isGreaterThan(raw.lonEast)
    }

    @Test
    fun gridSize_adaptsToSpan() {
        val regional = ViewportSampler.gridSizeForSpan(2.0, 3.0)
        val global = ViewportSampler.gridSizeForSpan(90.0, 120.0)
        assertThat(regional.first * regional.second).isGreaterThan(global.first * global.second)
    }

    @Test
    fun cacheKey_differsForDistinctRegions() {
        val groningen = FieldRasterInterpolator.Bounds(53.0, 53.5, 6.4, 7.0)
        val antalya = FieldRasterInterpolator.Bounds(36.0, 36.8, 30.5, 31.2)
        val kG = ViewportSampler.cacheKey(WeatherLayer.TEMPERATURE, groningen)
        val kA = ViewportSampler.cacheKey(WeatherLayer.TEMPERATURE, antalya)
        assertThat(kG).isNotEqualTo(kA)
        assertThat(kG).contains("53.0000")
        assertThat(kA).contains("36.0000")
    }

    @Test
    fun buildCoordinates_mercatorYMonotonicSouthToNorth() {
        val bounds = FieldRasterInterpolator.Bounds(50.0, 52.0, 4.0, 6.0)
        val grid = ViewportSampler.buildCoordinates(bounds)
        val cols = grid.cols
        val ys = (0 until grid.rows).map { r ->
            val p = grid.points[r * cols]
            ViewportSampler.latToMercatorY(p.lat)
        }
        for (i in 1 until ys.size) {
            assertThat(ys[i]).isGreaterThan(ys[i - 1])
        }
    }
}
