package com.discount152.aeolus.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.discount152.aeolus.BuildConfig
import com.discount152.aeolus.data.cache.ForecastCache
import com.discount152.aeolus.data.cache.ForecastSnapshotStore
import com.discount152.aeolus.data.cache.LastKnownGoodStore
import com.discount152.aeolus.data.network.OpenMeteoClientConfigurator
import com.discount152.aeolus.data.network.OpenMeteoEnsembleApi
import com.discount152.aeolus.data.network.OpenMeteoForecastApi
import com.discount152.aeolus.data.network.OpenMeteoGate
import com.discount152.aeolus.data.network.OpenMeteoGeocodingApi
import com.discount152.aeolus.data.network.ProductionOpenMeteoClientConfigurator
import com.discount152.aeolus.data.network.RainViewerApi
import com.discount152.aeolus.data.prefs.SettingsRepository
import com.discount152.aeolus.data.repository.WeatherRepository
import com.discount152.aeolus.location.LocationService
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "aeolus_preferences")

class AppContainer(
    context: Context,
    openMeteoConfigurator: OpenMeteoClientConfigurator = ProductionOpenMeteoClientConfigurator,
) {
    val applicationContext: Context = context.applicationContext

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    val preferencesDataStore: DataStore<Preferences> = applicationContext.dataStore

    /** Shared Open-Meteo concurrency + 429 backoff (forecast / ensemble / geocoding). */
    val openMeteoGate: OpenMeteoGate = OpenMeteoGate()

    /** Ungated client for RainViewer and other non–Open-Meteo hosts. */
    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                    },
                )
            }
        }
        .build()

    /** Open-Meteo HTTP client — max 2 concurrent, Retry-After / backoff on 429. */
    val openMeteoOkHttpClient: OkHttpClient = okHttpClient.newBuilder()
        .addInterceptor(openMeteoGate.interceptor())
        .let { openMeteoConfigurator.install(it) }
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    private fun openMeteoRetrofit(baseUrl: String): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(openMeteoOkHttpClient)
            .addConverterFactory(json.asConverterFactory(jsonMediaType))
            .build()

    val forecastApi: OpenMeteoForecastApi =
        openMeteoRetrofit("https://api.open-meteo.com/").create(OpenMeteoForecastApi::class.java)

    val ensembleApi: OpenMeteoEnsembleApi =
        openMeteoRetrofit("https://ensemble-api.open-meteo.com/").create(OpenMeteoEnsembleApi::class.java)

    val geocodingApi: OpenMeteoGeocodingApi =
        openMeteoRetrofit("https://geocoding-api.open-meteo.com/").create(OpenMeteoGeocodingApi::class.java)

    val rainViewerApi: RainViewerApi =
        Retrofit.Builder()
            .baseUrl("https://api.rainviewer.com/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(jsonMediaType))
            .build()
            .create(RainViewerApi::class.java)

    val forecastCache: ForecastCache = ForecastCache(applicationContext, json)

    val forecastSnapshotStore: ForecastSnapshotStore =
        ForecastSnapshotStore(applicationContext, json)

    val lastKnownGoodStore: LastKnownGoodStore =
        LastKnownGoodStore(applicationContext, json)

    val settingsRepository: SettingsRepository =
        SettingsRepository(preferencesDataStore, json)

    val weatherRepository: WeatherRepository =
        WeatherRepository(
            forecastApi = forecastApi,
            ensembleApi = ensembleApi,
            geocodingApi = geocodingApi,
            cache = forecastCache,
            snapshots = forecastSnapshotStore,
            lastKnownGoodStore = lastKnownGoodStore,
            okHttp = openMeteoOkHttpClient,
            json = json,
        )

    val locationService: LocationService = LocationService(applicationContext)
}
