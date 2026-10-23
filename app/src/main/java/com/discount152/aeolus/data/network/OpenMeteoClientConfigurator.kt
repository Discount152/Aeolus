package com.discount152.aeolus.data.network

import okhttp3.OkHttpClient

/**
 * Narrow hook so debug builds can substitute Open-Meteo HTTP transport.
 * Release uses [ProductionOpenMeteoClientConfigurator] (identity) and live providers.
 */
fun interface OpenMeteoClientConfigurator {
    fun install(builder: OkHttpClient.Builder): OkHttpClient.Builder
}

object ProductionOpenMeteoClientConfigurator : OpenMeteoClientConfigurator {
    override fun install(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder
}
