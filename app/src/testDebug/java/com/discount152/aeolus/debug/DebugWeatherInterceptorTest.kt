package com.discount152.aeolus.debug

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class DebugWeatherInterceptorTest {
    @Before
    fun setUp() {
        DebugWeatherHarness.reset()
    }

    @After
    fun tearDown() {
        DebugWeatherHarness.reset()
    }

    @Test
    fun live_passthroughCallsNextHop() {
        val reached = AtomicBoolean(false)
        val client = OkHttpClient.Builder()
            .addInterceptor(DebugWeatherInterceptor { DebugWeatherScenario.LIVE })
            .addInterceptor {
                reached.set(true)
                throw IOException("network reached")
            }
            .build()
        val thrown = runCatching {
            client.newCall(omRequest("https://api.open-meteo.com/v1/forecast?models=icon_seamless")).execute()
        }.exceptionOrNull()
        assertThat(reached.get()).isTrue()
        assertThat(thrown).isInstanceOf(IOException::class.java)
        assertThat(thrown!!.message).isEqualTo("network reached")
    }

    @Test
    fun nonOpenMeteo_alwaysPassthrough() {
        val reached = AtomicBoolean(false)
        DebugWeatherHarness.scenario = DebugWeatherScenario.SUCCESS_FULL
        val client = OkHttpClient.Builder()
            .addInterceptor(DebugWeatherHarness.interceptor)
            .addInterceptor {
                reached.set(true)
                throw IOException("rainviewer")
            }
            .build()
        val thrown = runCatching {
            client.newCall(Request.Builder().url("https://api.rainviewer.com/public/weather-maps.json").build()).execute()
        }.exceptionOrNull()
        assertThat(reached.get()).isTrue()
        assertThat(thrown!!.message).isEqualTo("rainviewer")
    }

    @Test
    fun successFull_returnsForecastJson() {
        DebugWeatherHarness.scenario = DebugWeatherScenario.SUCCESS_FULL
        val resp = client().newCall(forecastRequest("icon_seamless")).execute()
        assertThat(resp.code).isEqualTo(200)
        val body = resp.body!!.string()
        assertThat(body).contains("\"timezone\"")
        assertThat(body).contains("hourly")
        assertThat(body).contains("daily")
    }

    @Test
    fun successCoreOnly_iconOkOthers500() {
        DebugWeatherHarness.scenario = DebugWeatherScenario.SUCCESS_CORE_ONLY
        val http = client()
        assertThat(http.newCall(forecastRequest("icon_seamless")).execute().code).isEqualTo(200)
        assertThat(http.newCall(forecastRequest("ecmwf_ifs025")).execute().code).isEqualTo(500)
        assertThat(http.newCall(forecastRequest("gfs_seamless")).execute().code).isEqualTo(500)
        assertThat(http.newCall(ensembleRequest()).execute().code).isEqualTo(500)
    }

    @Test
    fun http429_hasRetryAfter() {
        DebugWeatherHarness.scenario = DebugWeatherScenario.HTTP_429
        val resp = client().newCall(forecastRequest("icon_seamless")).execute()
        assertThat(resp.code).isEqualTo(429)
        assertThat(resp.header("Retry-After")).isEqualTo("1")
    }

    @Test
    fun http500_allOpenMeteo() {
        DebugWeatherHarness.scenario = DebugWeatherScenario.HTTP_500
        assertThat(client().newCall(forecastRequest("icon_seamless")).execute().code).isEqualTo(500)
    }

    @Test
    fun timeout_throwsSocketTimeout() {
        DebugWeatherHarness.scenario = DebugWeatherScenario.TIMEOUT
        val thrown = runCatching {
            client().newCall(forecastRequest("icon_seamless")).execute()
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(SocketTimeoutException::class.java)
    }

    @Test
    fun empty200_objectBody() {
        DebugWeatherHarness.scenario = DebugWeatherScenario.EMPTY_200
        val resp = client().newCall(forecastRequest("icon_seamless")).execute()
        assertThat(resp.code).isEqualTo(200)
        assertThat(resp.body!!.string()).isEqualTo("{}")
    }

    @Test
    fun partialModelFailure_ecmwf500OthersOk() {
        DebugWeatherHarness.scenario = DebugWeatherScenario.PARTIAL_MODEL_FAILURE
        val http = client()
        assertThat(http.newCall(forecastRequest("ecmwf_ifs025")).execute().code).isEqualTo(500)
        assertThat(http.newCall(forecastRequest("icon_seamless")).execute().code).isEqualTo(200)
        assertThat(http.newCall(forecastRequest("gfs_seamless")).execute().code).isEqualTo(200)
        assertThat(http.newCall(ensembleRequest()).execute().code).isEqualTo(200)
    }

    @Test
    fun slowSuccess_sleepsThen200() {
        DebugWeatherHarness.scenario = DebugWeatherScenario.SLOW_SUCCESS
        DebugWeatherHarness.slowDelayMs = 40L
        val start = System.nanoTime()
        val resp = client().newCall(forecastRequest("icon_seamless")).execute()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000L
        assertThat(resp.code).isEqualTo(200)
        assertThat(elapsedMs).isAtLeast(40L)
    }

    private fun client(): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(DebugWeatherHarness.interceptor).build()

    private fun forecastRequest(model: String): Request = omRequest(
        "https://api.open-meteo.com/v1/forecast?latitude=53.22&longitude=6.57&timezone=Europe/Amsterdam&models=$model",
    )

    private fun ensembleRequest(): Request = omRequest(
        "https://ensemble-api.open-meteo.com/v1/ensemble?latitude=53.22&longitude=6.57&models=ecmwf_ifs025",
    )

    private fun omRequest(url: String): Request = Request.Builder().url(url).build()
}
