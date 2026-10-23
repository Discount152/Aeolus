package com.discount152.aeolus.map

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

class MapOverlayRetainTest {
    @After
    fun tearDown() {
        MapOverlayRetain.resetForTests()
    }

    @Test
    fun rememberPublish_survivesResetOfCallers() {
        MapOverlayRetain.rememberPublish(
            timeline = UnifiedMapTimeline(frames = emptyList(), nowEpochSeconds = 1L),
            selectedInstant = 10L,
            viewedZoneId = "Europe/Istanbul",
            radarFootprint = null,
            forecastFootprint = null,
        )
        MapOverlayRetain.rememberCamera(36.075, 32.836, 7.0)
        assertThat(MapOverlayRetain.lastViewedZoneId).isEqualTo("Europe/Istanbul")
        assertThat(MapOverlayRetain.lastSelectedInstant).isEqualTo(10L)
        assertThat(MapOverlayRetain.lastCamera?.lat).isWithin(0.001).of(36.075)
        assertThat(MapOverlayRetain.lastTimeline?.nowEpochSeconds).isEqualTo(1L)
    }
}
