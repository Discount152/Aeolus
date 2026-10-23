package com.discount152.aeolus.map

/**
 * Distinguishes user-initiated pause from automatic pause during camera interaction.
 * Only [AUTO_PAUSED_FOR_CAMERA] auto-resumes when the camera settles.
 */
enum class MapPlaybackState {
    STOPPED,
    PLAYING,
    USER_PAUSED,
    AUTO_PAUSED_FOR_CAMERA,
}

enum class MapPrecipMode {
    OBSERVED,
    FORECAST,
}
