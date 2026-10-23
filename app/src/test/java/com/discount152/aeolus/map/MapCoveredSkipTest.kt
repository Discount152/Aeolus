package com.discount152.aeolus.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Diagnosis §12.3: never settle reload_covered / empty timeline_publish unless the
 * unified timeline is playable or has ≥1 READY frame.
 */
class MapCoveredSkipTest {

    @Test
    fun emptyUnified_withStaleForecastFootprint_forcesNetwork() {
        val decision = MapCoveredSkip.reloadDecision(
            radarFramesReady = false,
            forecastFramesReady = false,
            radarGeoCovered = true,
            forecastGeoCovered = true,
            unifiedPlayable = false,
            unifiedReadyCount = 0,
        )
        assertThat(decision.skipNetwork).isFalse()
        assertThat(decision.settleCovered).isFalse()
        assertThat(decision.needRadar).isTrue()
        assertThat(decision.needForecast).isTrue()
    }

    @Test
    fun radarReady_forecastEmpty_doesNotCover() {
        val decision = MapCoveredSkip.reloadDecision(
            radarFramesReady = true,
            forecastFramesReady = false,
            radarGeoCovered = true,
            forecastGeoCovered = true,
            unifiedPlayable = true,
            unifiedReadyCount = 3,
        )
        assertThat(decision.skipNetwork).isFalse()
        assertThat(decision.needRadar).isFalse()
        assertThat(decision.needForecast).isTrue()
    }

    @Test
    fun bothReadyAndCovered_playable_skipsNetwork() {
        val decision = MapCoveredSkip.reloadDecision(
            radarFramesReady = true,
            forecastFramesReady = true,
            radarGeoCovered = true,
            forecastGeoCovered = true,
            unifiedPlayable = true,
            unifiedReadyCount = 8,
        )
        assertThat(decision.skipNetwork).isTrue()
        assertThat(decision.needRadar).isFalse()
        assertThat(decision.needForecast).isFalse()
    }

    @Test
    fun bothReadyAndCovered_unplayableEmpty_forcesNetwork() {
        val decision = MapCoveredSkip.reloadDecision(
            radarFramesReady = true,
            forecastFramesReady = true,
            radarGeoCovered = true,
            forecastGeoCovered = true,
            unifiedPlayable = false,
            unifiedReadyCount = 0,
        )
        assertThat(decision.skipNetwork).isFalse()
        assertThat(decision.needRadar).isTrue()
        assertThat(decision.needForecast).isTrue()
    }

    @Test
    fun emptyBothDone_cannotPublishSettle() {
        assertThat(
            MapCoveredSkip.canPublishSettle(
                radarDone = true,
                forecastDone = true,
                observedReadyCount = 0,
                forecastReadyCount = 0,
                unifiedPlayable = false,
                unifiedReadyCount = 0,
            ),
        ).isFalse()
    }

    @Test
    fun radarOnly_bothDone_canPublish() {
        assertThat(
            MapCoveredSkip.canPublishSettle(
                radarDone = true,
                forecastDone = true,
                observedReadyCount = 4,
                forecastReadyCount = 0,
                unifiedPlayable = true,
                unifiedReadyCount = 4,
            ),
        ).isTrue()
    }

    @Test
    fun radarReady_forecastStillPreparing_canPublishEarly() {
        assertThat(
            MapCoveredSkip.canPublishSettle(
                radarDone = true,
                forecastDone = false,
                observedReadyCount = 5,
                forecastReadyCount = 0,
                unifiedPlayable = true,
                unifiedReadyCount = 5,
            ),
        ).isTrue()
    }

    @Test
    fun mapForecast_yieldsWhileCoreInFlightOrCooldown() {
        assertThat(MapCoveredSkip.shouldStartMapForecast(coreGetWeatherInFlight = true, gateCoolingDown = false))
            .isFalse()
        assertThat(MapCoveredSkip.shouldStartMapForecast(coreGetWeatherInFlight = false, gateCoolingDown = true))
            .isFalse()
        assertThat(MapCoveredSkip.shouldStartMapForecast(coreGetWeatherInFlight = false, gateCoolingDown = false))
            .isTrue()
    }
}
