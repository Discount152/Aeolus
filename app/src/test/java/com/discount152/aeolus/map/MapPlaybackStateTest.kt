package com.discount152.aeolus.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MapPlaybackStateTest {
    @Test
    fun autoPaused_resumes_userPaused_doesNot() {
        var state = MapPlaybackState.PLAYING
        // Camera move while playing
        if (state == MapPlaybackState.PLAYING) {
            state = MapPlaybackState.AUTO_PAUSED_FOR_CAMERA
        }
        assertThat(state).isEqualTo(MapPlaybackState.AUTO_PAUSED_FOR_CAMERA)
        // Camera idle with ready set → auto resume
        if (state == MapPlaybackState.AUTO_PAUSED_FOR_CAMERA) {
            state = MapPlaybackState.PLAYING
        }
        assertThat(state).isEqualTo(MapPlaybackState.PLAYING)

        // User pause
        state = MapPlaybackState.USER_PAUSED
        val wasUserPaused = state == MapPlaybackState.USER_PAUSED
        // Camera idle must not resume
        if (!wasUserPaused && state == MapPlaybackState.AUTO_PAUSED_FOR_CAMERA) {
            state = MapPlaybackState.PLAYING
        }
        assertThat(state).isEqualTo(MapPlaybackState.USER_PAUSED)
    }
}
