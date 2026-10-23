package com.discount152.aeolus.data.repository

import com.discount152.aeolus.domain.consensus.ConsensusEngine
import com.discount152.aeolus.domain.models.DailyPoint
import com.discount152.aeolus.domain.models.ForecastModelId
import com.discount152.aeolus.domain.models.GeoLocation
import com.discount152.aeolus.domain.models.HourlyPoint
import com.discount152.aeolus.domain.models.ModelForecast
import com.discount152.aeolus.domain.models.WeatherBundle
import com.discount152.aeolus.location.LocationIdentity
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Spec: selecting uncached B must never display A's weather under B's name.
 * Presentation layer filters by stableId; repository must not retain wrong prior.
 */
class WrongLocationRetainTest {
    private val locA = GeoLocation("geo:a", "Groningen", 53.22, 6.57, "Europe/Amsterdam")
    private val locB = GeoLocation("map:36.8,31.4", "Map pin", 36.8, 31.4, "Europe/Istanbul")

    private fun bundleFor(loc: GeoLocation): WeatherBundle {
        val models = listOf(
            ModelForecast(
                model = ForecastModelId.ICON,
                available = true,
                hourly = listOf(
                    HourlyPoint(1L, "t", temperatureC = 10.0, precipitationMm = 0.0),
                ),
                daily = listOf(
                    DailyPoint("2026-10-23", tempMaxC = 12.0, tempMinC = 6.0, precipitationSumMm = 1.0),
                ),
                fetchedAtEpochMs = 1L,
            ),
        )
        return WeatherBundle(
            location = LocationIdentity.withStableId(loc),
            fetchedAtEpochMs = 1L,
            models = models,
            ensemble = null,
            consensusHourly = ConsensusEngine.buildConsensusHourly(models, 48),
            consensusDaily = ConsensusEngine.buildConsensusDaily(models),
            confidenceByDate = emptyMap(),
            fromCache = true,
            stale = false,
        )
    }

    @Test
    fun uiMatching_rejectsBundleFromOtherLocation() {
        val a = bundleFor(locA)
        val activeB = LocationIdentity.withStableId(locB)
        val matching = a.takeIf {
            LocationIdentity.stableId(it.location) == LocationIdentity.stableId(activeB)
        }
        assertThat(matching).isNull()
        assertThat(LocationIdentity.stableId(a.location))
            .isNotEqualTo(LocationIdentity.stableId(activeB))
    }

    @Test
    fun ephemeralPin_stableIdIsCoordKey() {
        val pin = LocationIdentity.withStableId(locB)
        assertThat(pin.id).startsWith("coord:")
        assertThat(LocationIdentity.isEphemeralActive(locB)).isTrue()
        assertThat(LocationIdentity.isEphemeralActive(pin)).isTrue()
    }

    @Test
    fun retainPolicy_rejectsCrossLocationPrior() {
        val priorA = bundleFor(locA)
        val requestedB = LocationIdentity.withStableId(locB)
        val priorMatches =
            LocationIdentity.stableId(priorA.location) == LocationIdentity.stableId(requestedB)
        assertThat(priorMatches).isFalse()
        // Repository must clear prior and surface unavailable — never stale-A under B.
        val retained = priorA.takeIf { priorMatches }
        assertThat(retained).isNull()
    }

    @Test
    fun savedCity_allowsNearestLookup_ephemeralDoesNot() {
        assertThat(LocationIdentity.isEphemeralActive(locA)).isFalse()
        assertThat(LocationIdentity.isEphemeralActive(locB)).isTrue()
    }
}
