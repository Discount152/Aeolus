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
    fun refreshFailedWithCache_onlineNetwork_isStaleWithCache() {
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
    fun refreshFailedWithCache_offlineNetwork_isOfflineWithCache() {
        val stale = bundle.copy(fromCache = true, stale = true)
        val presentation = computeConnectivityPresentation(
            loading = false,
            refreshing = false,
            error = "Showing cached forecast (refresh failed).",
            bundle = stale,
            networkValidated = false,
        )
        assertThat(presentation).isEqualTo(ConnectivityPresentation.OFFLINE_WITH_CACHE)
    }

    @Test
    fun noDataAndError_validatedNetwork_isOnlineErrorNoCache() {
        val presentation = computeConnectivityPresentation(
            loading = false,
            refreshing = false,
            error = "Unable to load forecast. Check connection and retry.",
            bundle = null,
            networkValidated = true,
        )
        assertThat(presentation).isEqualTo(ConnectivityPresentation.ONLINE_ERROR_NO_CACHE)
    }

    @Test
    fun noData_noValidatedNetwork_isOfflineNoCache() {
        val presentation = computeConnectivityPresentation(
            loading = false,
            refreshing = false,
            error = "Unable to load forecast. Check connection and retry.",
            bundle = null,
            networkValidated = false,
        )
        assertThat(presentation).isEqualTo(ConnectivityPresentation.OFFLINE_NO_CACHE)
    }

    @Test
    fun loadingNoBundle_validatedNetwork_staysLiveForLoadingUi() {
        val presentation = computeConnectivityPresentation(
            loading = true,
            refreshing = false,
            error = null,
            bundle = null,
            networkValidated = true,
        )
        assertThat(presentation).isEqualTo(ConnectivityPresentation.LIVE)
    }
}
