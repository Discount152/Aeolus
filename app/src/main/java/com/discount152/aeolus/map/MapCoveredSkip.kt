package com.discount152.aeolus.map

/**
 * Covered-skip / timeline-settle policy for Map overlay reloads.
 *
 * Never treat an empty or unplayable unified timeline as covered. Forecast is covered
 * only when READY frames exist; a failed Open-Meteo load must not lock [reload_covered].
 */
object MapCoveredSkip {
    data class ReloadDecision(
        val skipNetwork: Boolean,
        val needRadar: Boolean,
        val needForecast: Boolean,
    ) {
        val settleCovered: Boolean get() = skipNetwork
    }

    fun reloadDecision(
        radarFramesReady: Boolean,
        forecastFramesReady: Boolean,
        radarGeoCovered: Boolean,
        forecastGeoCovered: Boolean,
        unifiedPlayable: Boolean,
        unifiedReadyCount: Int,
    ): ReloadDecision {
        val unifiedUsable = unifiedPlayable || unifiedReadyCount >= 1
        val radarCovered = radarFramesReady && radarGeoCovered
        val forecastCovered = forecastFramesReady && forecastGeoCovered
        return ReloadDecision(
            skipNetwork = unifiedUsable && radarCovered && forecastCovered,
            needRadar = !radarCovered || !unifiedUsable,
            needForecast = !forecastCovered || !unifiedUsable,
        )
    }

    /**
     * Publish only when the unified timeline has at least one READY frame (or is playable).
     * Empty `rDone && fDone` must not settle as a successful timeline.
     */
    fun canPublishSettle(
        radarDone: Boolean,
        forecastDone: Boolean,
        observedReadyCount: Int,
        forecastReadyCount: Int,
        unifiedPlayable: Boolean,
        unifiedReadyCount: Int,
    ): Boolean {
        val usable = unifiedPlayable || unifiedReadyCount >= 1
        if (!usable) return false
        return when {
            radarDone && observedReadyCount > 0 -> true
            forecastDone && forecastReadyCount > 0 && radarDone -> true
            radarDone && forecastDone -> true
            else -> false
        }
    }

    fun shouldStartMapForecast(coreGetWeatherInFlight: Boolean, gateCoolingDown: Boolean): Boolean =
        !coreGetWeatherInFlight && !gateCoolingDown

    /**
     * Tab remount / network reload must not wipe a still-visible clock.
     * Only blank when there is nothing ready to keep on screen.
     */
    fun shouldBlankPublishedTimeline(currentHasReadyFrame: Boolean): Boolean = !currentHasReadyFrame

    /** PREPARING… only when the chrome has no frame to label. */
    fun showPreparingPlaceholder(hasDisplayedFrame: Boolean, preparing: Boolean): Boolean =
        preparing && !hasDisplayedFrame
}
