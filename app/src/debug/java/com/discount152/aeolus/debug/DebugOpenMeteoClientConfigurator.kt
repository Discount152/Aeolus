package com.discount152.aeolus.debug

import com.discount152.aeolus.data.network.OpenMeteoClientConfigurator
import okhttp3.OkHttpClient

/** Installed after [com.discount152.aeolus.data.network.OpenMeteoGate] so 429 still retries. */
object DebugOpenMeteoClientConfigurator : OpenMeteoClientConfigurator {
    override fun install(builder: OkHttpClient.Builder): OkHttpClient.Builder =
        builder.addInterceptor(DebugWeatherHarness.interceptor)
}
