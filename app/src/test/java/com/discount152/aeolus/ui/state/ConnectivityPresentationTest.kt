package com.discount152.aeolus.ui.state

import com.discount152.aeolus.domain.models.GeoLocation
import com.discount152.aeolus.domain.models.WeatherBundle
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConnectivityPresentationTest {
    private val bundle = WeatherBundle(
        location = GeoLocation("geo:1", "Amsterdam", 52.37, 4.89, "Europe/Amsterdam"),
        fetchedAtEpochMs = 1_700_000_000_000L,
        models = emptyList(),
        ensemble = null,
        consensusHourly = emptyList(),
        consensusDaily = emptyList(),
        confidenceByDate = emptyMap(),
        fromCache = true,
        stale = false,
    )

    @Test
    fun cachedWhileRefreshing_isNotOffline() {
        val presentation = computeConnectivityPresentation(
            loading = false,
            refreshing = true,
            error = null,
            bundle = bundle,
            networkValidated = true,
        )
        assertThat(presentation).isEqualTo(ConnectivityPresentation.REFRESHING_WITH_CACHE)
    }

    @Test
    fun freshLiveData_isLive() {
        val live = bundle.copy(fromCache = false, stale = false)
        val presentation = computeConnectivityPresentation(
            loading = false,
            refreshing = false,
            error = null,
            bundle = live,
            networkValidated = true,
        )
        assertThat(presentation).isEqualTo(ConnectivityPresentation.LIVE)
    }

    @Test
    fun validatedNetwork_staleBundle_isStaleNotOffline() {
        val stale = bundle.copy(fromCache = true, stale = true)
        val presentation = computeConnectivityPresentation(
            loading = false,
            refreshing = false,
            error = "Showing cached forecast (refresh failed).",
            bundle = stale,
            networkValidated = true,
        )
        assertThat(presentation).isEqualTo(ConnectivityPresentation.STALE_WITH_CACHE)
        assertThat(presentation).isNotEqualTo(ConnectivityPresentation.OFFLINE_WITH_CACHE)
    }

    @Test
    fun noNetwork_withCache_isOfflineWithCache() {
        val presentation = computeConnectivityPresentation(
            loading = false,
            refreshing = false,
            error = null,
            bundle = bundle.copy(stale = true),
            networkValidated = false,
        )
        assertThat(presentation).isEqualTo(ConnectivityPresentation.OFFLINE_WITH_CACHE)
    }

    @Test
    fun validatedNetwork_noCache_providerFailure_isOnlineError() {
        val presentation = computeConnectivityPresentation(
            loading = false,
            refreshing = false,
            error = "WEATHER DATA TEMPORARILY UNAVAILABLE",
            bundle = null,
            networkValidated = true,
        )
        assertThat(presentation).isEqualTo(ConnectivityPresentation.ONLINE_ERROR_NO_CACHE)
    }

    @Test
    fun noNetwork_noCache_isOfflineNoCache() {
        val presentation = computeConnectivityPresentation(
            loading = false,
            refreshing = false,
            error = null,
            bundle = null,
            networkValidated = false,
        )
        assertThat(presentation).isEqualTo(ConnectivityPresentation.OFFLINE_NO_CACHE)
    }

    @Test
    fun http429StyleError_neverMapsToOfflineWhenValidated() {
        val presentation = computeConnectivityPresentation(
            loading = false,
            refreshing = false,
            error = "rate limited",
            bundle = bundle.copy(stale = true),
            networkValidated = true,
        )
        assertThat(presentation).isEqualTo(ConnectivityPresentation.STALE_WITH_CACHE)
    }
}
