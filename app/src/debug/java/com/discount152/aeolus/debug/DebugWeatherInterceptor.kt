package com.discount152.aeolus.debug

import android.util.Log
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.net.SocketTimeoutException

class DebugWeatherInterceptor(
    private val scenarioProvider: () -> DebugWeatherScenario = { DebugWeatherHarness.scenario },
    private val sleeper: (Long) -> Unit = { ms -> if (ms > 0) Thread.sleep(ms) },
    private val networkValidated: () -> Boolean = { DebugWeatherHarness.isNetworkValidated() },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.host.contains("open-meteo.com")) {
            return chain.proceed(request)
        }
        if (!networkValidated()) {
            Log.i(DebugWeatherHarness.TAG, "nic_unvalidated — not synthesizing ${scenarioProvider()}")
            throw IOException(NO_VALIDATED_NETWORK)
        }
        val scenario = scenarioProvider()
        if (scenario == DebugWeatherScenario.LIVE) {
            return chain.proceed(request)
        }
        Log.i(DebugWeatherHarness.TAG, "intercept scenario=$scenario ${request.url.encodedPath} models=${request.url.queryParameter("models")}")
        if (scenario == DebugWeatherScenario.TIMEOUT) {
            throw SocketTimeoutException("Open-Meteo harness TIMEOUT")
        }
        if (scenario == DebugWeatherScenario.SLOW_SUCCESS) {
            sleeper(DebugWeatherHarness.slowDelayMs)
        }
        return when (scenario) {
            DebugWeatherScenario.HTTP_429 -> status(request, 429, "Too Many Requests", DebugWeatherFixtures.errorBody("Rate limited")) {
                it.header("Retry-After", DebugWeatherHarness.retryAfterSeconds.toString())
            }
            DebugWeatherScenario.HTTP_500 -> status(request, 500, "Internal Server Error", DebugWeatherFixtures.errorBody("Server error"))
            DebugWeatherScenario.EMPTY_200 -> status(request, 200, "OK", DebugWeatherFixtures.emptyBody())
            DebugWeatherScenario.SUCCESS_FULL,
            DebugWeatherScenario.SLOW_SUCCESS,
            -> success(request, failModel = null, failEnsemble = false)
            DebugWeatherScenario.SUCCESS_CORE_ONLY -> success(request, failModel = { it != ICON }, failEnsemble = true)
            DebugWeatherScenario.PARTIAL_MODEL_FAILURE -> success(request, failModel = { it == ECMWF }, failEnsemble = false)
            DebugWeatherScenario.LIVE,
            DebugWeatherScenario.TIMEOUT,
            -> error("unreachable $scenario")
        }
    }

    private fun success(
        request: okhttp3.Request,
        failModel: ((String) -> Boolean)?,
        failEnsemble: Boolean,
    ): Response {
        val url = request.url
        val host = url.host
        val path = url.encodedPath
        val latRaw = url.queryParameter("latitude").orEmpty()
        val lonRaw = url.queryParameter("longitude").orEmpty()
        val lat = latRaw.substringBefore(',').toDoubleOrNull() ?: 53.21917
        val lon = lonRaw.substringBefore(',').toDoubleOrNull() ?: 6.56667
        val timezone = url.queryParameter("timezone")
            ?.takeUnless { it.equals("auto", ignoreCase = true) || it.isBlank() }
            ?: "Europe/Amsterdam"

        if (host.contains("geocoding-api") || path.contains("v1/search")) {
            return status(request, 200, "OK", DebugWeatherFixtures.geocodingBody(url.queryParameter("name").orEmpty()))
        }
        if (host.contains("ensemble-api") || path.contains("v1/ensemble")) {
            if (failEnsemble) {
                return status(request, 500, "Internal Server Error", DebugWeatherFixtures.errorBody("ensemble unavailable"))
            }
            return status(request, 200, "OK", DebugWeatherFixtures.ensembleBody(lat, lon, timezone))
        }

        val model = url.queryParameter("models").orEmpty()
        if (failModel != null && model.isNotBlank() && failModel(model)) {
            return status(request, 500, "Internal Server Error", DebugWeatherFixtures.errorBody("model $model failed"))
        }
        val body = DebugWeatherFixtures.forecastBody(
            model = model.ifBlank { ICON },
            latitude = lat,
            longitude = lon,
            timezone = timezone,
        )
        val locations = latRaw.split(',').size.coerceAtLeast(1)
        val payload = if (locations > 1) "[" + List(locations) { body }.joinToString(",") + "]" else body
        return status(request, 200, "OK", payload)
    }

    private fun status(
        request: okhttp3.Request,
        code: Int,
        message: String,
        body: String,
        extra: (Response.Builder) -> Unit = {},
    ): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(message)
            .body(body.toResponseBody(JSON))
        extra(builder)
        return builder.build()
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val ICON = "icon_seamless"
        private const val ECMWF = "ecmwf_ifs025"
        const val NO_VALIDATED_NETWORK = "No validated network"
    }
}
