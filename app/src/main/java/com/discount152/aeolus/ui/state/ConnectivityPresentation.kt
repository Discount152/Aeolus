package com.discount152.aeolus.ui.state

import com.discount152.aeolus.domain.models.WeatherBundle

enum class ConnectivityPresentation {
    LIVE,
    REFRESHING_WITH_CACHE,
    STALE_WITH_CACHE,
    OFFLINE_WITH_CACHE,
    ONLINE_ERROR_NO_CACHE,
    OFFLINE_NO_CACHE,
}

/**
 * Derives UI connectivity from network validation, meta, and bundle — not from [WeatherBundle.stale] alone
 * while refreshing, and never maps provider failure to offline when the network is validated.
 */
fun computeConnectivityPresentation(
    loading: Boolean,
    refreshing: Boolean,
    error: String?,
    bundle: WeatherBundle?,
    networkValidated: Boolean,
): ConnectivityPresentation {
    if (bundle == null) {
        if (!networkValidated) return ConnectivityPresentation.OFFLINE_NO_CACHE
        if (loading && error == null) return ConnectivityPresentation.LIVE
        return ConnectivityPresentation.ONLINE_ERROR_NO_CACHE
    }
    if (refreshing) return ConnectivityPresentation.REFRESHING_WITH_CACHE
    if (!networkValidated) return ConnectivityPresentation.OFFLINE_WITH_CACHE
    if (error != null || bundle.stale) return ConnectivityPresentation.STALE_WITH_CACHE
    return ConnectivityPresentation.LIVE
}
