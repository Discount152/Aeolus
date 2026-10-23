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
        assertThat(fp.tiles).isNotEmpty()
        assertThat(fp.tiles.size).isAtMost(RadarViewport.MAX_TILES_PER_FRAME)
    }

    @Test
    fun tileBudget_neverExceedsMaxForRegionalView() {
        val fp = RadarViewport.footprintForVisibleBounds(51.0, 53.0, 4.0, 7.0)
        assertThat(fp.tiles.size).isAtMost(RadarViewport.MAX_TILES_PER_FRAME)
        assertThat(fp.tilesX * fp.tilesY).isEqualTo(fp.tiles.size)
    }

    @Test
    fun adaptiveZoom_finerForCityThanContinent() {
        val city = RadarViewport.footprintForVisibleBounds(52.3, 52.45, 4.8, 5.0)
        val continent = RadarViewport.footprintForVisibleBounds(40.0, 60.0, -10.0, 30.0)
        assertThat(city.zoom).isAtLeast(continent.zoom)
        assertThat(city.tiles.size).isAtMost(RadarViewport.MAX_TILES_PER_FRAME)
        assertThat(continent.tiles.size).isAtMost(RadarViewport.MAX_TILES_PER_FRAME)
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

    @Test
    fun worldBounds_areNotUsable_cityBoundsAre() {
        assertThat(RadarViewport.visibleBoundsUsable(-85.1, 85.1, -180.0, 180.0)).isFalse()
        assertThat(RadarViewport.visibleBoundsUsable(-119.0, 119.0, -140.0, 140.0)).isFalse()
        assertThat(RadarViewport.visibleBoundsUsable(52.8, 53.6, 6.2, 7.1)).isTrue()
    }

    @Test
    fun coarseWorldMosaic_doesNotCoverCityAtDesiredZoom() {
        val world = RadarViewport.footprintForVisibleBounds(-80.0, 80.0, -160.0, 160.0)
        val city = RadarViewport.footprintForVisibleBounds(52.8, 53.6, 6.2, 7.1)
        assertThat(world.zoom).isLessThan(city.zoom)
        assertThat(
            world.coversVisibleAtZoom(52.9, 53.4, 6.4, 6.9, city.zoom),
        ).isFalse()
        assertThat(
            city.coversVisibleAtZoom(52.9, 53.4, 6.4, 6.9, city.zoom),
        ).isTrue()
    }

    @Test
    fun tileCover_reducesZoomWhenBudgetExceeded() {
        val wide = RadarViewport.tileCoverForBounds(
            mercatorWest = RadarViewport.lonToMercatorX(-20.0),
            mercatorEast = RadarViewport.lonToMercatorX(40.0),
            mercatorSouth = RadarViewport.latToMercatorY(30.0),
            mercatorNorth = RadarViewport.latToMercatorY(70.0),
            maxTiles = 4,
        )
        assertThat(wide.count).isAtMost(4)
        assertThat(wide.zoom).isAtMost(RadarViewport.SOURCE_MAX_ZOOM)
    }
}
