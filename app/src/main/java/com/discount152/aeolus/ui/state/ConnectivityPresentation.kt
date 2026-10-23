package com.discount152.aeolus.ui.state

import com.discount152.aeolus.domain.models.WeatherBundle

/** User-facing copy for provider / connectivity empty states. Never expose HTTP 429. */
object WeatherCopy {
    const val PROVIDER_BUSY = "WEATHER SERVICE TEMPORARILY BUSY"
    const val PROVIDER_BUSY_HINT = "Try again shortly."
    const val GENERIC_UNAVAILABLE = "WEATHER DATA TEMPORARILY UNAVAILABLE"
    const val OFFLINE_NO_CACHE = "OFFLINE · NO SAVED FORECAST"
    const val FORECAST_TEMPORARILY_UNAVAILABLE = "FORECAST TEMPORARILY UNAVAILABLE"
    const val PRECIPITATION_UNAVAILABLE = "PRECIPITATION DATA UNAVAILABLE"
    const val MANUAL_RETRY_GUARD_MS = 2_000L

    fun allowManualRetry(lastAttemptEpochMs: Long, nowEpochMs: Long): Boolean =
        nowEpochMs - lastAttemptEpochMs >= MANUAL_RETRY_GUARD_MS
}

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
    // Real NIC wins over harness-fresh bundles and mid-refresh "UPDATING" (D7).
    if (!networkValidated) {
        return if (bundle == null) {
            ConnectivityPresentation.OFFLINE_NO_CACHE
        } else {
            ConnectivityPresentation.OFFLINE_WITH_CACHE
        }
    }
    if (bundle == null) {
        if (loading && error == null) return ConnectivityPresentation.LIVE
        return ConnectivityPresentation.ONLINE_ERROR_NO_CACHE
    }
    if (refreshing) return ConnectivityPresentation.REFRESHING_WITH_CACHE
    if (error != null || bundle.stale) return ConnectivityPresentation.STALE_WITH_CACHE
    return ConnectivityPresentation.LIVE
}
