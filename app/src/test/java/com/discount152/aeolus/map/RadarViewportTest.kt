package com.discount152.aeolus.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RadarViewportTest {
    @Test
    fun footprint_enclosesVisibleBoundsWithPadding() {
        val fp = RadarViewport.footprintForVisibleBounds(
            latSouth = 50.5,
            latNorth = 53.8,
            lonWest = 3.2,
            lonEast = 7.5,
        )
        assertThat(fp.latSouth).isAtMost(50.5)
        assertThat(fp.latNorth).isAtLeast(53.8)
        assertThat(fp.lonWest).isAtMost(3.2)
        assertThat(fp.lonEast).isAtLeast(7.5)
        assertThat(fp.zoom).isIn(RadarViewport.SOURCE_MIN_ZOOM..RadarViewport.SOURCE_MAX_ZOOM)
        assertThat(fp.tileSizePx).isEqualTo(512)
    }

    @Test
    fun zoom_finerForSmallerSide() {
        val city = RadarViewport.zoomForSideMeters(80_000.0)
        val continent = RadarViewport.zoomForSideMeters(2_000_000.0)
        assertThat(city).isGreaterThan(continent)
        assertThat(city).isAtMost(RadarViewport.SOURCE_MAX_ZOOM)
        assertThat(continent).isAtLeast(RadarViewport.SOURCE_MIN_ZOOM)
    }

    @Test
    fun zoom_neverAboveSeven() {
        assertThat(RadarViewport.zoomForSideMeters(1.0)).isEqualTo(RadarViewport.SOURCE_MAX_ZOOM)
    }

    @Test
    fun corners_formValidBox() {
        val fp = RadarViewport.footprintForVisibleBounds(51.0, 52.5, 4.0, 6.0)
        assertThat(fp.latNorth).isGreaterThan(fp.latSouth)
        assertThat(fp.centerLat).isGreaterThan(fp.latSouth)
        assertThat(fp.centerLat).isLessThan(fp.latNorth)
    }

    @Test
    fun coversVisible_trueInsidePadding_falseAfterBigPan() {
        val fp = RadarViewport.footprintForVisibleBounds(51.0, 52.0, 4.5, 5.5)
        assertThat(fp.coversVisible(51.2, 51.8, 4.7, 5.3)).isTrue()
        assertThat(fp.coversVisible(40.0, 41.0, 28.0, 30.0)).isFalse()
    }

    @Test
    fun cacheKey_stableForIdenticalFootprint() {
        val a = RadarViewport.footprintForVisibleBounds(51.0, 52.0, 4.5, 5.5)
        val b = RadarViewport.footprintForVisibleBounds(51.0, 52.0, 4.5, 5.5)
        assertThat(a.cacheKeyPart()).isEqualTo(b.cacheKeyPart())
    }

    @Test
    fun cacheKey_changesAfterMeaningfulMove() {
        val nl = RadarViewport.footprintForVisibleBounds(51.0, 53.0, 4.0, 7.0)
        val tr = RadarViewport.footprintForVisibleBounds(36.0, 38.0, 30.0, 33.0)
        assertThat(nl.cacheKeyPart()).isNotEqualTo(tr.cacheKeyPart())
    }
}
