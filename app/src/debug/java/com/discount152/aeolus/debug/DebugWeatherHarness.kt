package com.discount152.aeolus.debug

import android.content.Context
import android.util.Log
import com.discount152.aeolus.data.network.NetworkStatus
import okhttp3.Interceptor

/**
 * Process-wide debug weather scenario. Persisted so adb can set it before a cold start.
 * Default is [DebugWeatherScenario.LIVE] — production release never compiles this type.
 */
object DebugWeatherHarness {
    const val TAG = "AeolusHarness"
    const val ACTION_SET_SCENARIO = "com.discount152.aeolus.debug.SET_WEATHER_SCENARIO"
    const val EXTRA_SCENARIO = "scenario"
    const val PREFS_NAME = "aeolus_debug_harness"
    private const val PREF_SCENARIO = "scenario"

    @Volatile
    var scenario: DebugWeatherScenario = DebugWeatherScenario.LIVE

    /** Device SLOW_SUCCESS delay. Tests override this. */
    @Volatile
    var slowDelayMs: Long = 2_500L

    /** Retry-After seconds returned by HTTP_429 (gate treats 1..120 as exact). */
    @Volatile
    var retryAfterSeconds: Long = 1L

    /**
     * Real NIC / validated-network probe. Tests default to online.
     * Device debug attaches [com.discount152.aeolus.data.network.NetworkStatus].
     * Unvalidated NIC must not be papered over by SUCCESS_* fixtures (D7).
     */
    @Volatile
    var networkValidatedProvider: () -> Boolean = { true }

    val interceptor: Interceptor = DebugWeatherInterceptor()

    fun isNetworkValidated(): Boolean = networkValidatedProvider()

    fun reset() {
        scenario = DebugWeatherScenario.LIVE
        slowDelayMs = 2_500L
        retryAfterSeconds = 1L
        networkValidatedProvider = { true }
    }

    fun attachNetworkStatus(context: Context) {
        val app = context.applicationContext
        networkValidatedProvider = { NetworkStatus.isValidated(app) }
    }

    fun setScenario(value: DebugWeatherScenario, context: Context? = null) {
        scenario = value
        context?.let { persist(it) }
        Log.i(TAG, "scenario=$value")
    }

    fun setScenario(raw: String, context: Context? = null): Boolean {
        val parsed = DebugWeatherScenario.parse(raw) ?: run {
            Log.w(TAG, "unknown scenario '$raw' — keeping $scenario")
            return false
        }
        setScenario(parsed, context)
        return true
    }

    fun restore(context: Context) {
        attachNetworkStatus(context)
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_SCENARIO, null)
        DebugWeatherScenario.parse(stored)?.let { scenario = it }
    }

    fun persist(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_SCENARIO, scenario.name)
            .apply()
    }
}
