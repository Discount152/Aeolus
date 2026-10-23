package com.discount152.aeolus.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RadarImagesTest {
    @Test
    fun tileUrl_matchesRainViewerXyzForm() {
        val url = RadarImages.tileUrl(
            host = "https://tilecache.rainviewer.com",
            path = "/v2/radar/abc",
            zoom = 5,
            x = 16,
            y = 10,
        )
        assertThat(url).isEqualTo(
            "https://tilecache.rainviewer.com/v2/radar/abc/512/5/16/10/2/1_1.png",
        )
    }

    @Test
    fun tileUrl_clampsZoomToSeven() {
        val url = RadarImages.tileUrl(
            host = "https://tilecache.rainviewer.com",
            path = "v2/radar/x",
            zoom = 12,
            x = 1,
            y = 2,
        )
        assertThat(url).contains("/512/7/")
    }

    @Test
    fun tileCacheKey_includesZxyAndOptions() {
        val k = RadarImages.tileCacheKey("/v2/radar/a", 5, 16, 10)
        assertThat(k).contains("|5|16|10|")
        assertThat(k).contains("512")
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
