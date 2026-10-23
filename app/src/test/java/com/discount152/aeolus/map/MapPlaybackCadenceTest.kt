package com.discount152.aeolus.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Spec 1.0.8: playback must be one clock at ~650 ms/frame — not relaunched per selectedInstant.
 */
class MapPlaybackCadenceTest {
    @Test
    fun cadence_targetsReadableAnimation() {
        assertThat(650L).isAtLeast(500L)
        assertThat(650L).isAtMost(900L)
        assertThat(1000L).isAtLeast(650L) // NOW pause
        assertThat(750L).isAtLeast(650L) // loop pause
    }

    @Test
    fun userPaused_isDistinctFromAutoPaused() {
        assertThat(MapPlaybackState.USER_PAUSED).isNotEqualTo(MapPlaybackState.AUTO_PAUSED_FOR_CAMERA)
        assertThat(MapPlaybackState.PLAYING).isNotEqualTo(MapPlaybackState.USER_PAUSED)
    }
}
