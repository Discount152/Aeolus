package com.discount152.aeolus.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RadarTilesTest {
    @Test
    fun tileTemplate_includes256AndHostPath() {
        val url = RadarTiles.tileTemplate("https://tilecache.rainviewer.com", "/v2/radar/abc")
        assertThat(url).isEqualTo(
            "https://tilecache.rainviewer.com/v2/radar/abc/256/{z}/{x}/{y}/2/1_1.png",
        )
    }

    @Test
    fun clampSourceZoom_neverExceedsSeven() {
        assertThat(RadarTiles.clampSourceZoom(3.2)).isEqualTo(3)
        assertThat(RadarTiles.clampSourceZoom(7.0)).isEqualTo(7)
        assertThat(RadarTiles.clampSourceZoom(12.5)).isEqualTo(7)
        assertThat(RadarTiles.clampSourceZoom(0.1)).isEqualTo(1)
    }

    @Test
    fun sourceMaxZoom_isSeven() {
        assertThat(RadarTiles.SOURCE_MAX_ZOOM).isEqualTo(7)
    }
}
