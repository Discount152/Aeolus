package com.discount152.aeolus.data.network

import kotlinx.serialization.Serializable
import retrofit2.http.GET

interface RainViewerApi {
    @GET("public/weather-maps.json")
    suspend fun weatherMaps(): RainViewerMapsResponse
}

@Serializable
data class RainViewerMapsResponse(
    val version: String? = null,
    val generated: Long? = null,
    val host: String? = null,
    val radar: RainViewerRadar? = null,
)

@Serializable
data class RainViewerRadar(
    val past: List<RainViewerFrame> = emptyList(),
    val nowcast: List<RainViewerFrame> = emptyList(),
)

@Serializable
data class RainViewerFrame(
    val time: Long,
    val path: String,
)
