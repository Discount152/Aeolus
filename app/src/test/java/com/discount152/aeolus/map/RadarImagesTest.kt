package com.discount152.aeolus.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RadarImagesTest {
    @Test
    fun centeredImageUrl_matchesRainViewerCoordinateForm() {
        val url = RadarImages.centeredImageUrl(
            host = "https://tilecache.rainviewer.com",
            path = "/v2/radar/abc",
            zoom = 5,
            latitude = 52.37000,
            longitude = 4.89000,
        )
        assertThat(url).isEqualTo(
            "https://tilecache.rainviewer.com/v2/radar/abc/512/5/52.37000/4.89000/2/1_1.png",
        )
    }

    @Test
    fun centeredImageUrl_clampsZoomToSeven() {
        val url = RadarImages.centeredImageUrl(
            host = "https://tilecache.rainviewer.com",
            path = "v2/radar/x",
            zoom = 12,
            latitude = 1.0,
            longitude = 2.0,
        )
        assertThat(url).contains("/512/7/")
    }

    @Test
    fun frameCacheKey_includesPathAndFootprint() {
        val fp = RadarViewport.footprintForVisibleBounds(51.0, 52.0, 4.0, 5.0)
        val k1 = RadarImages.frameCacheKey("/v2/radar/a", fp)
        val k2 = RadarImages.frameCacheKey("/v2/radar/b", fp)
        assertThat(k1).isNotEqualTo(k2)
        assertThat(k1).contains(fp.cacheKeyPart())
    }
}
