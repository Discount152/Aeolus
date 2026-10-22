package com.discount152.aeolus.ui.state

import com.discount152.aeolus.domain.models.WeatherBundle

enum class ConnectivityPresentation {
    LIVE,
    REFRESHING_WITH_CACHE,
    OFFLINE_WITH_CACHE,
    ERROR_NO_CACHE,
}

/**
 * Derives UI connectivity from meta + bundle — not from [WeatherBundle.stale] alone while refreshing.
 */
fun computeConnectivityPresentation(
    loading: Boolean,
    refreshing: Boolean,
    error: String?,
    bundle: WeatherBundle?,
): ConnectivityPresentation {
    if (bundle == null) {
        return ConnectivityPresentation.ERROR_NO_CACHE
    }
    if (refreshing) {
        return ConnectivityPresentation.REFRESHING_WITH_CACHE
    }
    if (error != null || bundle.stale) {
        return ConnectivityPresentation.OFFLINE_WITH_CACHE
    }
    return ConnectivityPresentation.LIVE
}
