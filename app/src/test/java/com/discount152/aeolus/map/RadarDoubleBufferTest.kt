package com.discount152.aeolus.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RadarDoubleBufferTest {
    @Test
    fun slots_areDistinctAndStable() {
        val buffer = RadarDoubleBuffer()
        assertThat(buffer.sourceId(0)).isNotEqualTo(buffer.sourceId(1))
        assertThat(buffer.layerId(0)).isNotEqualTo(buffer.layerId(1))
        assertThat(buffer.sourceId(0)).isEqualTo("aeolus-radar-src-0")
        assertThat(buffer.layerId(1)).isEqualTo("aeolus-radar-lyr-1")
    }
}
